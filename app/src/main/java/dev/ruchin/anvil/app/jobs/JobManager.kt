package dev.ruchin.anvil.app.jobs

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.ruchin.anvil.app.R
import dev.ruchin.anvil.app.source.ContentUriImageSource
import dev.ruchin.anvil.app.usb.OpenedDrive
import dev.ruchin.anvil.catalog.linux.ResolvedLinuxIso
import dev.ruchin.anvil.catalog.windows.MicrosoftIsoResolver
import dev.ruchin.anvil.catalog.windows.WindowsDownloadLink
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.ByteSize
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.download.DownloadProgress
import dev.ruchin.anvil.download.DownloadRequest
import dev.ruchin.anvil.download.DownloadStage
import dev.ruchin.anvil.download.ResumableDownloader
import dev.ruchin.anvil.writer.RawImageWriter
import dev.ruchin.anvil.writer.WindowsProgress
import dev.ruchin.anvil.writer.WindowsStage
import dev.ruchin.anvil.writer.WindowsUsbWriter
import dev.ruchin.anvil.writer.WindowsWriteOptions
import dev.ruchin.anvil.writer.DriverFolder
import dev.ruchin.anvil.fat.PartitionScheme
import dev.ruchin.anvil.writer.WriteProgress
import dev.ruchin.anvil.writer.WriteStage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class JobKind { WRITE_USB, DOWNLOAD }
enum class JobStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** One long-running operation, rendered identically by the progress UI and the notification. */
data class JobState(
    val kind: JobKind,
    val title: String,
    val status: JobStatus = JobStatus.RUNNING,
    @StringRes val stages: List<Int>,
    val stageIndex: Int = 0,
    @StringRes val stageNote: Int? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val errorMessage: String? = null,
    val technicalError: String? = null,
    val resultDetail: String? = null,
    val outputPath: String? = null,
    /** File currently being copied (Windows writes). */
    val currentFile: String? = null,
    /** Shown as a green badge on success, e.g. "Signature + checksum verified" for Linux downloads. */
    val verifiedNote: String? = null,
    /** Linux catalog download (vs. Windows): decides which screen the home card opens. */
    val linux: Boolean = false,
    /** A Windows installer write; the done screen shows the "No drivers were found" help. */
    val windowsWrite: Boolean = false,
    val driversIncluded: Boolean = false,
) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
    val isActive get() = status == JobStatus.RUNNING
}

/**
 * Owns the single active long-running job (USB write or ISO download). Lives in
 * the application scope so it survives screen rotation and backgrounding; the
 * [TransferService] keeps the process alive and mirrors state to a notification.
 */
@Singleton
class JobManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writer: RawImageWriter,
    private val downloader: ResumableDownloader,
    private val resolver: MicrosoftIsoResolver,
    private val windowsWriter: WindowsUsbWriter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<JobState?>(null)
    val state: StateFlow<JobState?> = _state.asStateFlow()
    private var job: Job? = null

    val isBusy get() = _state.value?.isActive == true

    fun startWrite(source: ContentUriImageSource, drive: OpenedDrive, verify: Boolean = true) {
        check(!isBusy) { "a job is already running" }
        _state.value = JobState(
            kind = JobKind.WRITE_USB,
            title = "${source.displayName} → ${drive.displayName} (${ByteSize.marketingCapacity(drive.sizeBytes)})",
            stages = WRITE_STAGES,
            bytesTotal = source.sizeBytes,
        )
        launch(JobKind.WRITE_USB) {
            try {
                val result = writer.write(source, drive.blockDevice, verify) { p -> onWriteProgress(p) }
                drive.blockDevice.eject()
                _state.update {
                    it?.copy(
                        status = JobStatus.SUCCEEDED, stageIndex = WRITE_STAGES.lastIndex,
                        bytesDone = result.bytesWritten,
                        resultDetail = "SHA-256 ${result.sha256.take(16)}… · ${result.durationMs / 1000}s",
                    )
                }
            } finally {
                runCatching { drive.blockDevice.close() }
            }
        }
    }

    /** Windows installer ISO → GPT/MBR + FAT32 stick, install.wim split into .swm parts if needed. */
    fun startWindowsWrite(
        source: ContentUriImageSource, isoLabel: String?, drive: OpenedDrive, scheme: PartitionScheme,
        unattend: dev.ruchin.anvil.unattend.UnattendOptions? = null, verify: Boolean = true,
        drivers: Pair<DriverFolder, java.io.Closeable>? = null,
    ) {
        check(!isBusy) { "a job is already running" }
        _state.value = JobState(
            kind = JobKind.WRITE_USB,
            title = "${source.displayName} → ${drive.displayName} (${ByteSize.marketingCapacity(drive.sizeBytes)})",
            stages = WINDOWS_STAGES,
            bytesTotal = source.sizeBytes,
            windowsWrite = true,
            driversIncluded = drivers != null,
        )
        launch(JobKind.WRITE_USB) {
            try {
                val options = WindowsWriteOptions(scheme = scheme, verify = verify, unattend = unattend, drivers = drivers?.first)
                val result = source.openRandomAccess().use { iso ->
                    windowsWriter.write(iso, isoLabel, drive.blockDevice, options) { p -> onWindowsProgress(p) }
                }
                drive.blockDevice.eject()
                _state.update {
                    it?.copy(
                        status = JobStatus.SUCCEEDED, stageIndex = WINDOWS_STAGES.lastIndex, currentFile = null,
                        bytesDone = result.bytesWritten, bytesTotal = result.bytesWritten,
                        resultDetail = buildString {
                            append("${result.filesWritten} files · ${scheme.name} + FAT32 (UEFI)")
                            if (result.swmParts.isNotEmpty()) append(" · install.wim split into ${result.swmParts.size} parts")
                            if (unattend != null) append(" · Setup customised")
                            drivers?.let { append(" · ${it.first.files.size} driver files in \\Drivers") }
                            if (result.verified) append(" · verified")
                            append(" · ${result.durationMs / 1000}s")
                        },
                    )
                }
            } finally {
                runCatching { drive.blockDevice.close() }
                drivers?.let { runCatching { it.second.close() } }
            }
        }
    }

    /**
     * Linux catalog download. [iso] was resolved from a checksum list whose signature already
     * checked out; the downloader now verifies the ISO against that signed SHA-256. Only a file that
     * passes both ends up at [destination]; anything else is deleted and the job fails, so the
     * "Write to USB" step never sees an unverified ISO.
     */
    fun startLinuxDownload(iso: ResolvedLinuxIso, destination: File) {
        check(!isBusy) { "a job is already running" }
        _state.value = JobState(
            kind = JobKind.DOWNLOAD,
            title = iso.title,
            stages = LINUX_DOWNLOAD_STAGES,
            stageIndex = 1,
            bytesTotal = iso.sizeBytes ?: 0,
            linux = true,
        )
        launch(JobKind.DOWNLOAD) {
            var mirror = 0
            val req = DownloadRequest(
                urlProvider = { refresh ->
                    if (refresh) mirror = (mirror + 1) % iso.mirrorUrls.size
                    iso.mirrorUrls[mirror].also { DebugLog.i(TAG, "mirror ${mirror + 1}/${iso.mirrorUrls.size}: $it") }
                },
                destination = destination,
                expectedSha256 = iso.sha256,
                expectedSize = iso.sizeBytes,
                switchUrlAfterFailures = 3,
            )
            val file = downloader.download(req) { p -> onDownloadProgress(p, stageOffset = 1) }
            _state.update {
                it?.copy(
                    status = JobStatus.SUCCEEDED, stageIndex = LINUX_DOWNLOAD_STAGES.lastIndex, stageNote = null,
                    bytesDone = file.length(), bytesTotal = file.length(),
                    outputPath = file.absolutePath,
                    verifiedNote = context.getString(R.string.verified_badge),
                    resultDetail = "${iso.signer.owner} · SHA-256 ${iso.sha256.take(16)}…",
                )
            }
        }
    }

    fun startWindowsDownload(link: WindowsDownloadLink, destination: File) {
        check(!isBusy) { "a job is already running" }
        _state.value = JobState(
            kind = JobKind.DOWNLOAD,
            title = "${link.productDisplayName} · ${link.language}",
            stages = DOWNLOAD_STAGES,
        )
        launch(JobKind.DOWNLOAD) {
            val req = DownloadRequest(
                urlProvider = { refresh ->
                    if (!refresh) link.url
                    else resolver.resolveLatest(link.product, link.language).also {
                        if (it.fileName != link.fileName) {
                            throw AnvilException(
                                "Microsoft released a newer Windows build while downloading. Please start the download again.",
                                "file changed ${link.fileName} -> ${it.fileName}",
                            )
                        }
                    }.url
                },
                destination = destination,
                expectedSha256 = link.sha256,
            )
            val file = downloader.download(req) { p -> onDownloadProgress(p) }
            _state.update {
                it?.copy(
                    status = JobStatus.SUCCEEDED, stageIndex = DOWNLOAD_STAGES.lastIndex, stageNote = null,
                    bytesDone = file.length(), bytesTotal = file.length(),
                    outputPath = file.absolutePath,
                    resultDetail = if (link.sha256 != null) "SHA-256 verified against Microsoft's published hash" else null,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!isBusy) _state.value = null
    }

    private fun launch(kind: JobKind, block: suspend () -> Unit) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TransferService::class.java).putExtra(TransferService.EXTRA_KIND, kind.name),
        )
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                _state.update { it?.copy(status = JobStatus.CANCELLED) }
            } catch (e: AnvilException) {
                DebugLog.e(TAG, "job failed: ${e.message}", e)
                _state.update { it?.copy(status = JobStatus.FAILED, errorMessage = e.userMessage, technicalError = e.message) }
            } catch (e: Exception) {
                DebugLog.e(TAG, "job crashed", e)
                _state.update {
                    it?.copy(
                        status = JobStatus.FAILED,
                        errorMessage = "Something went wrong: ${e.message ?: e.javaClass.simpleName}",
                        technicalError = e.stackTraceToString().take(4000),
                    )
                }
            }
        }
    }

    private fun onWriteProgress(p: WriteProgress) {
        val idx = when (p.stage) {
            WriteStage.PREPARING -> 0
            WriteStage.CLEARING -> 1
            WriteStage.WRITING -> 2
            WriteStage.FLUSHING -> 3
            WriteStage.VERIFYING -> 4
            WriteStage.DONE -> 5
        }
        _state.update {
            it?.copy(stageIndex = idx, bytesDone = p.bytesDone, bytesTotal = p.bytesTotal, bytesPerSecond = p.bytesPerSecond, etaSeconds = p.etaSeconds)
        }
    }

    private fun onWindowsProgress(p: WindowsProgress) {
        val idx = when (p.stage) {
            WindowsStage.READING_ISO -> 0
            WindowsStage.PARTITIONING -> 1
            WindowsStage.FORMATTING -> 2
            WindowsStage.COPYING -> 3
            WindowsStage.SPLITTING_WIM -> 4
            WindowsStage.VERIFYING -> 5
            WindowsStage.DONE -> 6
        }
        _state.update {
            it?.copy(
                stageIndex = idx, bytesDone = p.bytesDone,
                bytesTotal = if (p.bytesTotal > 0) p.bytesTotal else it.bytesTotal,
                bytesPerSecond = p.bytesPerSecond, etaSeconds = p.etaSeconds,
                currentFile = p.currentFile?.trimStart('/'),
            )
        }
    }

    private fun onDownloadProgress(p: DownloadProgress, stageOffset: Int = 0) {
        val (idx, note) = when (p.stage) {
            DownloadStage.CONNECTING -> 0 to null
            DownloadStage.DOWNLOADING -> 1 to null
            DownloadStage.WAITING_FOR_NETWORK -> 1 to R.string.stage_waiting_network
            DownloadStage.VERIFYING -> 2 to null
            DownloadStage.DONE -> 3 to null
        }
        _state.update {
            it?.copy(
                stageIndex = idx + stageOffset, stageNote = note, bytesDone = p.bytesDone,
                bytesTotal = if (p.bytesTotal > 0) p.bytesTotal else it.bytesTotal,
                bytesPerSecond = p.bytesPerSecond, etaSeconds = p.etaSeconds,
            )
        }
    }

    companion object {
        private const val TAG = "Jobs"
        val WRITE_STAGES = listOf(
            R.string.stage_preparing, R.string.stage_clearing, R.string.stage_writing,
            R.string.stage_syncing, R.string.stage_verifying, R.string.stage_done,
        )
        val WINDOWS_STAGES = listOf(
            R.string.stage_reading_iso, R.string.stage_partitioning, R.string.stage_formatting,
            R.string.stage_copying, R.string.stage_splitting_wim, R.string.stage_verifying, R.string.stage_done,
        )
        val DOWNLOAD_STAGES = listOf(
            R.string.stage_connecting, R.string.stage_downloading, R.string.stage_checksum, R.string.stage_done,
        )
        val LINUX_DOWNLOAD_STAGES = listOf(R.string.stage_signature) + DOWNLOAD_STAGES
    }
}
