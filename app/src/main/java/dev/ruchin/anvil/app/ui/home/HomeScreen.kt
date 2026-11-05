package dev.ruchin.anvil.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ruchin.anvil.app.R
import dev.ruchin.anvil.app.jobs.JobKind
import dev.ruchin.anvil.app.jobs.JobManager
import dev.ruchin.anvil.app.ui.components.Notice
import dev.ruchin.anvil.app.usb.UsbDriveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(val drives: UsbDriveRepository, val jobs: JobManager) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onWrite: () -> Unit, onWindows: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    val job by vm.jobs.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!vm.drives.hasUsbHost) Notice(Icons.Filled.Warning, stringResource(R.string.no_usb_host), MaterialTheme.colorScheme.error)

            job?.takeIf { it.isActive }?.let { j ->
                Card(onClick = if (j.kind == JobKind.WRITE_USB) onWrite else onWindows, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(j.title, style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(j.fraction * 100).toInt()}% · ${stringResource(j.stageNote ?: j.stages[j.stageIndex])}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Usb, null, Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            if (drives.isEmpty()) stringResource(R.string.no_drive) else drives.joinToString { it.label },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(if (drives.isEmpty()) R.string.plug_in_drive else R.string.drive_connected),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            ActionCard(Icons.Filled.Terminal, stringResource(R.string.home_linux_title), stringResource(R.string.home_linux_body), onWrite)
            ActionCard(Icons.Filled.Download, stringResource(R.string.home_windows_title), stringResource(R.string.home_windows_body), onWindows)

            Text(stringResource(R.string.home_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
        }
    }
}
