package dev.ruchin.anvil.app.ui.linux

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import dev.ruchin.anvil.common.ByteSize

/** Distro → edition → Download → Write to USB, with the same progress card as Windows. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LinuxScreen(onBack: () -> Unit, onWriteToUsb: (String) -> Unit = {}, vm: LinuxViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val job by vm.jobs.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linux_title)) },
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

            if (ui.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.loading_catalog))
                }
            }
            if (!ui.loading && !ui.fromRemote && ui.entries.isNotEmpty()) {
                Notice(Icons.Filled.CloudOff, stringResource(R.string.catalog_bundled))
            }

            if (ui.entries.isNotEmpty()) {
                SectionCard(stringResource(R.string.step_choose_distro), step = 1) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.distros.forEach { d ->
                            FilterChip(
                                selected = ui.distroId == d.id,
                                onClick = { vm.selectDistro(d.id) },
                                label = { Text(d.name) },
                            )
                        }
                    }
                }

                SectionCard(stringResource(R.string.step_choose_edition), step = 2) {
                    ui.editions.forEach { e ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = ui.entry?.id == e.id, onClick = { vm.selectEntry(e) }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = ui.entry?.id == e.id, onClick = null, modifier = Modifier.padding(top = 2.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${e.distroName} ${e.version} · ${e.edition}", style = MaterialTheme.typography.bodyLarge)
                                val size = e.approxSizeMb?.let { stringResource(R.string.edition_size, ByteSize.format(it * 1024L * 1024)) }
                                Text(
                                    listOfNotNull(e.description, size).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                SectionCard(stringResource(R.string.step_download), step = 3) {
                    val iso = ui.resolved
                    if (iso == null) {
                        FilledTonalButton(onClick = vm::prepare, enabled = !ui.resolving && ui.entry != null) {
                            if (ui.resolving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(stringResource(R.string.verify_and_find_mirror))
                        }
                        if (ui.resolving) Text(stringResource(R.string.checking_signature), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(iso.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        iso.sizeBytes?.let { Text(stringResource(R.string.download_size, ByteSize.format(it)), style = MaterialTheme.typography.bodyMedium) }
                        Notice(Icons.Filled.Verified, stringResource(R.string.signature_verified, iso.signer.owner), MaterialTheme.colorScheme.primary)
                        Text(iso.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        iso.probes.firstOrNull()?.let { best ->
                            val host = runCatching { java.net.URI(best.url).host }.getOrNull() ?: best.url
                            Notice(Icons.Filled.Speed, stringResource(R.string.fastest_mirror, host, ByteSize.formatRate(best.bytesPerSecond)))
                        }
                        if (iso.mirrorUrls.size > 1) Text(stringResource(R.string.mirrors_available, iso.mirrorUrls.size), style = MaterialTheme.typography.bodySmall)
                        Notice(
                            Icons.Filled.SdStorage, stringResource(R.string.free_space, ByteSize.format(ui.freeBytes)),
                            if (ui.enoughSpace) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        if (!ui.enoughSpace) Notice(Icons.Filled.Warning, stringResource(R.string.not_enough_space), MaterialTheme.colorScheme.error)
                        if (ui.metered) Notice(Icons.Filled.SignalCellularAlt, stringResource(R.string.metered_warning), MaterialTheme.colorScheme.error)
                        if (ui.alreadyDownloaded != null) Notice(Icons.Filled.CheckCircle, stringResource(R.string.already_downloaded), MaterialTheme.colorScheme.primary)
                        Button(
                            onClick = { vm.startDownload() },
                            enabled = ui.enoughSpace && job?.isActive != true,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(
                                stringResource(if (ui.alreadyDownloaded != null) R.string.verify_existing else R.string.start_download),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            ui.error?.let {
                Notice(Icons.Filled.Warning, it, MaterialTheme.colorScheme.error)
                TextButton(onClick = { if (ui.entries.isEmpty()) vm.load() else vm.prepare() }) { Text(stringResource(R.string.action_retry)) }
            }

            Text(stringResource(R.string.linux_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
