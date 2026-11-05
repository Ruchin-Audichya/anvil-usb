package dev.ruchin.anvil.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import dev.ruchin.anvil.app.ui.AnvilNavHost
import dev.ruchin.anvil.app.ui.theme.AnvilTheme
import dev.ruchin.anvil.app.usb.UsbDriveRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var drives: UsbDriveRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            AnvilTheme { AnvilNavHost() }
        }
    }

    override fun onResume() {
        super.onResume()
        drives.refresh() // catches drives plugged in while we were in the background
    }
}
