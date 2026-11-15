package dev.ruchin.anvil.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import android.net.Uri
import dev.ruchin.anvil.app.ui.home.HomeScreen
import dev.ruchin.anvil.app.ui.linux.LinuxScreen
import dev.ruchin.anvil.app.ui.settings.SettingsScreen
import dev.ruchin.anvil.app.ui.windows.WindowsScreen
import dev.ruchin.anvil.app.ui.write.WriteScreen

object Routes {
    const val HOME = "home"
    const val WRITE = "write?image={image}"
    fun write(image: String? = null) = "write" + (image?.let { "?image=" + Uri.encode(it) } ?: "")
    const val WINDOWS = "windows"
    const val LINUX = "linux"
    const val SETTINGS = "settings"
}

@Composable
fun AnvilNavHost() {
    val nav = rememberNavController()
    val writeFile: (String) -> Unit = { path -> nav.navigate(Routes.write(Uri.fromFile(java.io.File(path)).toString())) }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onWrite = { nav.navigate(Routes.write()) },
                onWindows = { nav.navigate(Routes.WINDOWS) },
                onLinux = { nav.navigate(Routes.LINUX) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.WRITE,
            arguments = listOf(navArgument("image") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { WriteScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.WINDOWS) { WindowsScreen(onBack = { nav.popBackStack() }, onWriteToUsb = writeFile) }
        composable(Routes.LINUX) { LinuxScreen(onBack = { nav.popBackStack() }, onWriteToUsb = writeFile) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
