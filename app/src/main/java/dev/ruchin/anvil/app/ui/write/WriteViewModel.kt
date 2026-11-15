package dev.ruchin.anvil.app.ui.write

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ruchin.anvil.app.jobs.JobManager
import dev.ruchin.anvil.app.settings.AppSettings
import dev.ruchin.anvil.app.source.ContentUriImageSource
import dev.ruchin.anvil.app.source.PickedFolder
import dev.ruchin.anvil.app.usb.OpenedDrive
import dev.ruchin.anvil.app.usb.UsbDrive
import dev.ruchin.anvil.app.usb.UsbDriveRepository
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.image.ImageInfo
import dev.ruchin.anvil.image.ImageInspector
import dev.ruchin.anvil.image.ImageKind
import dev.ruchin.anvil.fat.PartitionScheme
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PickedImage(val source: ContentUriImageSource, val info: ImageInfo)

data class BatteryStatus(val percent: Int, val charging: Boolean) {
    val low get() = percent in 0 until 30 && !charging
}

/** Windows Setup customisation as edited in the UI. */
data class CustomizeUi(
    val enabled: Boolean = true,
    val bypass: Boolean = dev.ruchin.anvil.app.BuildConfig.ALLOW_HW_BYPASS,
    val localAccount: Boolean = true,
    val userName: String = "User",
    val password: String = "",
    val skipPrivacy: Boolean = true,
    val reduceTelemetry: Boolean = true,
    val disableBitLocker: Boolean = true,
    val removeBloat: Boolean = false,
    val useRegion: Boolean = true,
    val region: dev.ruchin.anvil.unattend.Region? = null,
) {
    val userNameValid get() = !localAccount || dev.ruchin.anvil.unattend.LocalAccount.isValidName(userName)

    fun toOptions(): dev.ruchin.anvil.unattend.UnattendOptions? = if (!enabled) null else dev.ruchin.anvil.unattend.UnattendOptions(
        bypassHardwareChecks = bypass && dev.ruchin.anvil.app.BuildConfig.ALLOW_HW_BYPASS,
        localAccount = if (localAccount) dev.ruchin.anvil.unattend.LocalAccount(userName.trim(), password) else null,
        skipOnlineAccount = localAccount,
        skipPrivacyQuestions = skipPrivacy,
        reduceTelemetry = reduceTelemetry,
        disableBitLocker = disableBitLocker,
        removeBloatware = removeBloat,
        region = if (useRegion) region else null,
    )
}

data class WriteUiState(
    val image: PickedImage? = null,
    val inspecting: Boolean = false,
    val imageError: String? = null,
    val opened: OpenedDrive? = null,
    val openingDrive: Boolean = false,
    val driveError: String? = null,
    val writeAnyway: Boolean = false,
    val showConfirm: Boolean = false,
    val battery: BatteryStatus? = null,
    val scheme: PartitionScheme = PartitionScheme.GPT,
    val customize: CustomizeUi = CustomizeUi(region = detectRegion()),
    /** "Add drivers folder" (Windows only): copied to \Drivers for Intel RST/VMD laptops. */
    val addDrivers: Boolean = false,
    val driversFolder: PickedFolder? = null,
    val readingDrivers: Boolean = false,
    val driversError: String? = null,
) {
    val driversReady get() = !isWindows || !addDrivers || driversFolder != null
    val isWindows get() = image?.info?.kind == ImageKind.WINDOWS_ISO
    val fits get() = image != null && opened != null && image.source.sizeBytes <= opened.sizeBytes
    val imageAcceptable get() = image != null && (image.info.rawWritable || isWindows || writeAnyway)
    val canStart get() = imageAcceptable && fits && opened?.blockDevice?.identity?.writeProtected == false &&
        (!isWindows || !customize.enabled || customize.userNameValid) && driversReady
}

@HiltViewModel
class WriteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drives: UsbDriveRepository,
    val jobs: JobManager,
    val settings: AppSettings,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val _ui = MutableStateFlow(WriteUiState(battery = readBattery()))
    val ui: StateFlow<WriteUiState> = _ui.asStateFlow()
    val availableDrives: StateFlow<List<UsbDrive>> = drives.drives
    val hasUsbHost get() = drives.hasUsbHost

    init {
        // Opened from "Write to USB" after a download: preselect that ISO.
        savedState.get<String>("image")?.takeIf { it.isNotBlank() }?.let { onImagePicked(Uri.parse(it), persist = false) }
        // Drop the opened drive if it gets unplugged before the write starts.
        viewModelScope.launch {
            drives.drives.collect { list ->
                val opened = _ui.value.opened ?: return@collect
                if (list.none { it.deviceName == opened.drive.deviceName } && !jobs.isBusy) {
                    runCatching { opened.blockDevice.close() }
                    _ui.update { it.copy(opened = null) }
                }
            }
        }
    }

    fun onImagePicked(uri: Uri, persist: Boolean = true) {
        if (persist) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _ui.update { it.copy(inspecting = true, imageError = null, image = null, writeAnyway = false) }
        viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) {
                    val src = ContentUriImageSource(context, uri)
                    if (src.sizeBytes <= 0) throw AnvilException("Couldn't determine the size of this file. Copy it to your phone's storage and pick it again.")
                    val header = src.readHeader(ImageInspector.HEADER_BYTES)
                    PickedImage(src, refineWithUdf(src, ImageInspector.inspect(header, header.size)))
                }
                DebugLog.i(TAG, "picked ${picked.source.displayName} ${picked.source.sizeBytes} B: ${picked.info}")
                _ui.update { it.copy(image = picked, inspecting = false) }
            } catch (e: AnvilException) {
                _ui.update { it.copy(inspecting = false, imageError = e.userMessage) }
            } catch (e: Exception) {
                DebugLog.e(TAG, "inspect failed", e)
                _ui.update { it.copy(inspecting = false, imageError = "Couldn't read this file: ${e.message}") }
            }
        }
    }

    /**
     * Label-based detection misses renamed/custom Windows ISOs. If the ISO has a UDF
     * file system and isn't a hybrid (Linux) image, look inside: Windows installers
     * have sources/install.{wim,esd,swm} and an EFI boot folder.
     */
    private fun refineWithUdf(src: ContentUriImageSource, info: dev.ruchin.anvil.image.ImageInfo): dev.ruchin.anvil.image.ImageInfo {
        if (info.rawWritable || !info.hasUdf) return info
        return runCatching {
            src.openRandomAccess().use { ra ->
                val root = dev.ruchin.anvil.udf.UdfReader(ra).readRoot()
                val hasInstall = listOf("sources/install.wim", "sources/install.esd", "sources/install.swm").any { root.find(it) != null }
                val hasEfi = root.find("efi/boot") != null
                if (hasInstall && hasEfi) info.copy(kind = ImageKind.WINDOWS_ISO) else info
            }
        }.onFailure { DebugLog.w(TAG, "UDF probe failed", it) }.getOrDefault(info)
    }

    fun setWriteAnyway(v: Boolean) = _ui.update { it.copy(writeAnyway = v) }

    fun setAddDrivers(v: Boolean) = _ui.update { it.copy(addDrivers = v, driversError = null) }

    /** SAF folder picked for "Add drivers folder": list its files (sizes are needed to plan the FAT32 layout). */
    fun onDriversPicked(treeUri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        _ui.update { it.copy(readingDrivers = true, driversError = null) }
        viewModelScope.launch {
            try {
                val folder = withContext(Dispatchers.IO) { PickedFolder.read(context, treeUri) }
                if (folder.files.isEmpty()) throw AnvilException("That folder has no files. Pick the folder that contains the driver's .inf files.")
                if (folder.files.size > dev.ruchin.anvil.writer.DriverFolder.MAX_FILES) {
                    throw AnvilException("That folder has too many files. Pick just the storage driver's folder.")
                }
                DebugLog.i(TAG, "drivers folder '${folder.name}': ${folder.files.size} files, ${folder.totalBytes} B")
                _ui.update { it.copy(readingDrivers = false, driversFolder = folder, addDrivers = true) }
            } catch (e: AnvilException) {
                _ui.update { it.copy(readingDrivers = false, driversError = e.userMessage) }
            } catch (e: Exception) {
                DebugLog.e(TAG, "drivers folder read failed", e)
                _ui.update { it.copy(readingDrivers = false, driversError = "Couldn't read that folder: ${e.message}") }
            }
        }
    }

    fun setScheme(s: PartitionScheme) = _ui.update { it.copy(scheme = s) }

    fun updateCustomize(f: (CustomizeUi) -> CustomizeUi) = _ui.update { it.copy(customize = f(it.customize)) }

    fun selectDrive(drive: UsbDrive) {
        if (_ui.value.openingDrive) return
        _ui.update { it.copy(openingDrive = true, driveError = null) }
        viewModelScope.launch {
            _ui.value.opened?.let { runCatching { it.blockDevice.close() } }
            try {
                if (!drives.requestPermission(drive)) {
                    _ui.update { it.copy(openingDrive = false, opened = null, driveError = "USB access was not allowed. Tap the drive again and choose Allow.") }
                    return@launch
                }
                val opened = drives.open(drive)
                _ui.update { it.copy(openingDrive = false, opened = opened) }
            } catch (e: AnvilException) {
                DebugLog.e(TAG, "open failed", e)
                _ui.update { it.copy(openingDrive = false, opened = null, driveError = e.userMessage) }
            } catch (e: Exception) {
                DebugLog.e(TAG, "open failed", e)
                _ui.update { it.copy(openingDrive = false, opened = null, driveError = "Couldn't open the USB drive: ${e.message}") }
            }
        }
    }

    fun requestStart() {
        _ui.update { it.copy(showConfirm = it.canStart, battery = readBattery()) }
    }

    fun dismissConfirm() = _ui.update { it.copy(showConfirm = false) }

    fun confirmAndStart() {
        val s = _ui.value
        val image = s.image ?: return
        val drive = s.opened ?: return
        _ui.update { it.copy(showConfirm = false) }
        if (s.isWindows) {
            val drivers = s.driversFolder?.takeIf { s.addDrivers }?.toDriverFolder(context)
            jobs.startWindowsWrite(image.source, image.info.volumeLabel, drive, s.scheme, s.customize.toOptions(), drivers = drivers)
        }
        else jobs.startWrite(image.source, drive)
        // The job now owns the device handle; forget it here so we don't close it.
        _ui.update { it.copy(opened = null) }
    }

    private fun readBattery(): BatteryStatus? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryStatus(if (level >= 0) level * 100 / scale else -1, charging)
    }

    override fun onCleared() {
        if (!jobs.isBusy) _ui.value.opened?.let { runCatching { it.blockDevice.close() } }
    }

    companion object { private const val TAG = "WriteVM" }
}

/** Phone's time zone / formats / keyboard → Windows equivalents. */
fun detectRegion(): dev.ruchin.anvil.unattend.Region {
    val locale = java.util.Locale.getDefault()
    val tag = locale.toLanguageTag()
    val ownLayout = locale.language in setOf("de", "fr", "es", "it", "pt", "ru", "ja", "ko", "zh", "ar", "tr", "pl", "nl", "sv", "da", "nb", "fi", "cs", "hu", "el", "he", "th", "uk")
    return dev.ruchin.anvil.unattend.Region(
        timeZone = dev.ruchin.anvil.unattend.TimeZones.windowsIdFor(java.util.TimeZone.getDefault().id),
        userLocale = if (locale.language == "hi") "en-IN" else tag,
        inputLocale = if (ownLayout) tag else "en-US",
    )
}
