package dev.ruchin.anvil.app.jobs

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.ruchin.anvil.app.AnvilApp
import dev.ruchin.anvil.app.MainActivity
import dev.ruchin.anvil.app.R
import dev.ruchin.anvil.common.ByteSize
import dev.ruchin.anvil.common.formatEta
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps Anvil alive (and the CPU awake) while a
 * multi-GB download or USB write runs with the screen off, and shows a
 * progress notification with a Cancel action.
 */
@AndroidEntryPoint
class TransferService : LifecycleService() {
    @Inject lateinit var jobs: JobManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) {
            jobs.cancel()
            return START_NOT_STICKY
        }
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let { runCatching { JobKind.valueOf(it) }.getOrNull() } ?: JobKind.DOWNLOAD
        val type = if (Build.VERSION.SDK_INT >= 29) {
            if (kind == JobKind.WRITE_USB) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        val initial = jobs.state.value
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(initial), type)

        if (!started) {
            started = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Anvil:transfer")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            lifecycleScope.launch {
                jobs.state.collectLatest { s ->
                    if (s == null || !s.isActive) {
                        finish(s)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= 1000) {
                            lastNotify = now
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync services get ~6 h/day. Cancel cleanly; downloads resume later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        jobs.cancel()
        stopSelf()
    }

    private fun finish(s: JobState?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (s != null && s.status != JobStatus.CANCELLED) {
            val text = when (s.status) {
                JobStatus.SUCCEEDED -> getString(if (s.kind == JobKind.WRITE_USB) R.string.notif_write_done else R.string.notif_download_done)
                else -> s.errorMessage ?: getString(R.string.notif_failed)
            }
            val n = NotificationCompat.Builder(this, AnvilApp.CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s.title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, n)
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        stopSelf()
    }

    private fun buildNotification(s: JobState?): Notification {
        val b = NotificationCompat.Builder(this, AnvilApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.action_cancel), cancelIntent())
        if (s == null) return b.setContentTitle(getString(R.string.app_name)).setProgress(0, 0, true).build()
        val stage = getString(s.stageNote ?: s.stages[s.stageIndex])
        val pct = (s.fraction * 100).toInt()
        val detail = if (s.bytesTotal > 0) {
            "$pct% · ${ByteSize.format(s.bytesDone)} / ${ByteSize.format(s.bytesTotal)} · ${ByteSize.formatRate(s.bytesPerSecond)} · ${formatEta(s.etaSeconds)}"
        } else stage
        return b.setContentTitle(s.title)
            .setContentText("$stage — $detail")
            .setSubText(stage)
            .setProgress(100, pct, s.bytesTotal <= 0)
            .build()
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent() = PendingIntent.getService(
        this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        private const val ACTION_CANCEL = "dev.ruchin.anvil.app.CANCEL"
        private const val NOTIFICATION_ID = 42
        private const val RESULT_NOTIFICATION_ID = 43
    }
}
