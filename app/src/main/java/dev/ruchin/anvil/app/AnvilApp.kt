package dev.ruchin.anvil.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import dev.ruchin.anvil.common.DebugLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AnvilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.sink = { line -> Log.d("Anvil", line) }
        DebugLog.i("App", "Anvil ${BuildConfig.VERSION_NAME} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_progress_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_RESULT = "result"
    }
}
