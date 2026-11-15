package dev.ruchin.anvil.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.ruchin.anvil.app.settings.AppSettings
import dev.ruchin.anvil.blockdev.ScsiBlockDevice
import dev.ruchin.anvil.blockdev.UsbTuning
import dev.ruchin.anvil.common.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A plugged-in USB mass-storage device (before we've opened it). */
data class UsbDrive(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val label: String,
    internal val device: UsbDevice,
)

/** A drive we've opened and probed via SCSI: real capacity and identity. */
class OpenedDrive(val drive: UsbDrive, val blockDevice: ScsiBlockDevice) {
    val displayName get() = blockDevice.identity.displayName.ifBlank { drive.label }
    val sizeBytes get() = blockDevice.sizeBytes
}

@Singleton
class UsbDriveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val _drives = MutableStateFlow<List<UsbDrive>>(emptyList())
    val drives: StateFlow<List<UsbDrive>> = _drives.asStateFlow()

    private val _detached = MutableStateFlow(0)
    /** Increments every time any USB device is detached (used to abort running writes promptly). */
    val detachEvents: StateFlow<Int> = _detached.asStateFlow()

    private var pendingPermission: CompletableDeferred<Boolean>? = null

    /** Whether this phone can act as a USB host at all. */
    val hasUsbHost: Boolean = context.packageManager.hasSystemFeature("android.hardware.usb.host")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refresh()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { _detached.value++; refresh() }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    DebugLog.i(TAG, "permission for ${dev?.deviceName}: $granted")
                    pendingPermission?.complete(granted)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        val list = manager?.deviceList?.values.orEmpty()
            .filter { AndroidUsbTransport.findMassStorageInterface(it) != null }
            .map { d ->
                val label = listOfNotNull(d.manufacturerName?.trim(), d.productName?.trim())
                    .filter { it.isNotEmpty() }.distinct().joinToString(" ").ifBlank { "USB drive" }
                UsbDrive(d.deviceName, d.vendorId, d.productId, label, d)
            }
        if (list.map { it.deviceName } != _drives.value.map { it.deviceName }) {
            DebugLog.i(TAG, "drives: ${list.joinToString { "${it.label} (%04x:%04x)".format(it.vendorId, it.productId) }}")
        }
        _drives.value = list
    }

    suspend fun requestPermission(drive: UsbDrive): Boolean {
        val m = manager ?: return false
        if (m.hasPermission(drive.device)) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // Must be MUTABLE: the system adds EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED to it.
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        m.requestPermission(drive.device, PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT))
        return withTimeoutOrNull(120_000) { deferred.await() } ?: false
    }

    /** Opens the drive and probes it over SCSI (INQUIRY / READ CAPACITY). Call after permission is granted. */
    suspend fun open(drive: UsbDrive): OpenedDrive = withContext(Dispatchers.IO) {
        val tuning = UsbTuning.forAndroid(Build.VERSION.SDK_INT, settings.compatibilityMode.value)
        val transport = AndroidUsbTransport.open(manager!!, drive.device, tuning)
        try {
            OpenedDrive(drive, ScsiBlockDevice.open(transport, tuning.maxCommandBytes))
        } catch (t: Throwable) {
            transport.close()
            throw t
        }
    }

    companion object {
        private const val TAG = "UsbDrives"
        private const val ACTION_USB_PERMISSION = "dev.ruchin.anvil.app.USB_PERMISSION"
    }
}
