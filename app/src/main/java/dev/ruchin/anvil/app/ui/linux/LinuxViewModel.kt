package dev.ruchin.anvil.app.ui.linux

import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ruchin.anvil.app.jobs.JobManager
import dev.ruchin.anvil.catalog.linux.LinuxEntry
import dev.ruchin.anvil.catalog.linux.LinuxIsoResolver
import dev.ruchin.anvil.catalog.linux.LinuxManifestSource
import dev.ruchin.anvil.catalog.linux.ResolvedLinuxIso
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class Distro(val id: String, val name: String)

data class LinuxUiState(
    val loading: Boolean = true,
    val entries: List<LinuxEntry> = emptyList(),
    val fromRemote: Boolean = true,
    val distroId: String? = null,
    val entry: LinuxEntry? = null,
    val resolving: Boolean = false,
    val resolved: ResolvedLinuxIso? = null,
    val freeBytes: Long = 0,
    val metered: Boolean = false,
    val alreadyDownloaded: File? = null,
    val error: String? = null,
    val confirmMeteredDownload: Boolean = false,
) {
    val distros: List<Distro> get() = entries.map { Distro(it.distro, it.distroName) }.distinct()
    val editions: List<LinuxEntry> get() = entries.filter { it.distro == distroId }
    val sizeBytes: Long? get() = resolved?.sizeBytes
    val enoughSpace get() = sizeBytes == null || freeBytes > sizeBytes!! + 200L * 1024 * 1024
}

@HiltViewModel
class LinuxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manifestSource: LinuxManifestSource,
    private val resolver: LinuxIsoResolver,
    val jobs: JobManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(LinuxUiState())
    val ui: StateFlow<LinuxUiState> = _ui.asStateFlow()
    private var resolveJob: Job? = null

    private val isoDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "isos").apply { mkdirs() }

    init { load() }

    fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val loaded = manifestSource.load()
                val entries = loaded.manifest.entries
                _ui.update {
                    it.copy(
                        loading = false, entries = entries, fromRemote = loaded.fromRemote,
                        distroId = entries.firstOrNull()?.distro, entry = entries.firstOrNull(),
                    )
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "manifest load failed", e)
                _ui.update { it.copy(loading = false, error = "Couldn't load the Linux catalog: ${e.message}") }
            }
        }
    }

    fun selectDistro(id: String) {
        resolveJob?.cancel()
        _ui.update { s -> s.copy(distroId = id, entry = s.entries.firstOrNull { it.distro == id }, resolved = null, resolving = false, error = null) }
    }

    fun selectEntry(entry: LinuxEntry) {
        resolveJob?.cancel()
        _ui.update { it.copy(entry = entry, resolved = null, resolving = false, error = null) }
    }

    /** Signed checksum list → ISO + SHA-256 → fastest mirrors, plus the storage / metered checks. */
    fun prepare() {
        val entry = _ui.value.entry ?: return
        _ui.update { it.copy(resolving = true, error = null) }
        resolveJob = viewModelScope.launch {
            try {
                val iso = resolver.resolve(entry)
                val free = StatFs(isoDir.path).availableBytes
                val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
                val existing = File(isoDir, iso.fileName).takeIf { it.exists() && (iso.sizeBytes == null || it.length() == iso.sizeBytes) }
                _ui.update { it.copy(resolving = false, resolved = iso, freeBytes = free, metered = metered, alreadyDownloaded = existing) }
            } catch (e: AnvilException) {
                DebugLog.e(TAG, "resolve failed: ${e.message}", e)
                _ui.update { it.copy(resolving = false, error = e.userMessage) }
            } catch (e: Exception) {
                DebugLog.e(TAG, "resolve failed", e)
                _ui.update { it.copy(resolving = false, error = "Couldn't prepare the download: ${e.message}") }
            }
        }
    }

    fun startDownload(confirmedMetered: Boolean = false) {
        val s = _ui.value
        val iso = s.resolved ?: return
        if (s.metered && !confirmedMetered) {
            _ui.update { it.copy(confirmMeteredDownload = true) }
            return
        }
        _ui.update { it.copy(confirmMeteredDownload = false) }
        jobs.startLinuxDownload(iso, File(isoDir, iso.fileName))
    }

    fun dismissMetered() = _ui.update { it.copy(confirmMeteredDownload = false) }

    companion object { private const val TAG = "LinuxVM" }
}
