package dev.ruchin.anvil.app.ui.windows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ruchin.anvil.app.R
import dev.ruchin.anvil.app.jobs.JobKind
import dev.ruchin.anvil.app.ui.components.Notice
import dev.ruchin.anvil.app.ui.components.SectionCard
import dev.ruchin.anvil.app.ui.components.TransferProgressCard
import dev.ruchin.anvil.catalog.windows.WindowsProduct
import dev.ruchin.anvil.common.ByteSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsScreen(onBack: () -> Unit, onWriteToUsb: (String) -> Unit = {}, vm: WindowsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val job by vm.jobs.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.windows_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = job
            if (current != null && current.kind == JobKind.DOWNLOAD) {
                TransferProgressCard(current, onCancel = vm.jobs::cancel, onDismiss = vm.jobs::dismiss, onWriteDownloaded = { p -> vm.jobs.dismiss(); onWriteToUsb(p) })
                if (current.isActive) {
                    Notice(Icons.Filled.Info, stringResource(R.string.download_background_hint))
                    return@Column
                }
            }

            SectionCard(stringResource(R.string.step_choose_windows), step = 1) {
                val products = WindowsProduct.entries
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    products.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = ui.product == p,
                            onClick = { vm.selectProduct(p) },
                            shape = SegmentedButtonDefaults.itemShape(i, products.size),
                        ) {
                            Text(
                                when (p) {
                                    WindowsProduct.WINDOWS_11_X64 -> stringResource(R.string.win11)
                                    WindowsProduct.WINDOWS_10_X64 -> stringResource(R.string.win10)
                                    WindowsProduct.WINDOWS_11_ARM64 -> stringResource(R.string.win11_arm)
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (ui.loading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.contacting_microsoft))
                }
                ui.session?.let { s ->
                    Text(s.languages.firstOrNull()?.productDisplayName ?: s.productEditionName, style = MaterialTheme.typography.bodyMedium)
                    LanguagePicker(ui, vm)
                }
            }

            ui.error?.let {
                Notice(Icons.Filled.Warning, it, MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.selectProduct(ui.product) }) { Text(stringResource(R.string.action_retry)) }
            }

            if (ui.session != null) {
                SectionCard(stringResource(R.string.step_download), step = 2) {
                    val link = ui.link
                    if (link == null) {
                        FilledTonalButton(onClick = vm::prepare, enabled = !ui.resolving && ui.language != null) {
                            if (ui.resolving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(stringResource(R.string.get_official_link))
                        }
                    } else {
                        Text(link.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        ui.sizeBytes?.let { Text(stringResource(R.string.download_size, ByteSize.format(it)), style = MaterialTheme.typography.bodyMedium) }
                        if (link.sha256 != null) {
                            Notice(Icons.Filled.Verified, stringResource(R.string.official_hash), MaterialTheme.colorScheme.primary)
                            Text(link.sha256!!, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        } else {
                            Notice(Icons.Filled.Info, stringResource(R.string.no_official_hash))
                        }
                        Notice(Icons.Filled.SdStorage, stringResource(R.string.free_space, ByteSize.format(ui.freeBytes)),
                            if (ui.enoughSpace) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        if (!ui.enoughSpace) Notice(Icons.Filled.Warning, stringResource(R.string.not_enough_space), MaterialTheme.colorScheme.error)
                        if (ui.metered) Notice(Icons.Filled.SignalCellularAlt, stringResource(R.string.metered_warning), MaterialTheme.colorScheme.error)
                        if (ui.alreadyDownloaded != null) Notice(Icons.Filled.CheckCircle, stringResource(R.string.already_downloaded), MaterialTheme.colorScheme.primary)
                        Button(
                            onClick = { vm.startDownload() },
                            enabled = ui.enoughSpace && job?.isActive != true,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(stringResource(if (ui.alreadyDownloaded != null) R.string.verify_existing else R.string.start_download), style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }

            Text(stringResource(R.string.windows_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (ui.confirmMeteredDownload) {
        AlertDialog(
            onDismissRequest = vm::dismissMetered,
            icon = { Icon(Icons.Filled.SignalCellularAlt, null) },
            title = { Text(stringResource(R.string.metered_title)) },
            text = { Text(stringResource(R.string.metered_body, ui.sizeBytes?.let { ByteSize.format(it) } ?: "?")) },
            confirmButton = { Button(onClick = { vm.startDownload(confirmedMetered = true) }) { Text(stringResource(R.string.metered_continue)) } },
            dismissButton = { TextButton(onClick = vm::dismissMetered) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(ui: WindowsUiState, vm: WindowsViewModel) {
    val session = ui.session ?: return
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = ui.language?.localizedLanguage ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            session.languages.forEach { l ->
                DropdownMenuItem(text = { Text(l.localizedLanguage) }, onClick = { vm.selectLanguage(l); expanded = false })
            }
        }
    }
}
