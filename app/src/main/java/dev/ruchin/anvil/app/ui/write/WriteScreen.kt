package dev.ruchin.anvil.app.ui.write

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import dev.ruchin.anvil.image.ImageKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteScreen(onBack: () -> Unit, vm: WriteViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val drives by vm.availableDrives.collectAsStateWithLifecycle()
    val job by vm.jobs.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onImagePicked) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onDriversPicked) }
    val compat by vm.settings.compatibilityMode.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.write_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = job
            if (current != null && current.kind == JobKind.WRITE_USB) {
                TransferProgressCard(current, onCancel = vm.jobs::cancel, onDismiss = vm.jobs::dismiss)
                if (current.status == dev.ruchin.anvil.app.jobs.JobStatus.SUCCEEDED && current.windowsWrite) {
                    DriversHelpCard(current.driversIncluded)
                }
                if (current.isActive) {
                    Notice(Icons.Filled.Warning, stringResource(R.string.write_keep_plugged), MaterialTheme.colorScheme.error)
                    return@Column
                }
            }

            // Step 1: image
            SectionCard(stringResource(R.string.step_choose_image), step = 1) {
                val img = ui.image
                when {
                    ui.inspecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.inspecting_image))
                    }
                    img != null -> {
                        Text(img.source.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(ByteSize.format(img.source.sizeBytes) + (img.info.volumeLabel?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        ImageKindNotice(img.info.kind)
                        if (img.info.kind == ImageKind.WINDOWS_ISO) SchemePicker(ui.scheme, vm::setScheme)
                        if (img.info.kind == ImageKind.NON_HYBRID_ISO || img.info.kind == ImageKind.UNKNOWN) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.setWriteAnyway(!ui.writeAnyway) }) {
                                Checkbox(ui.writeAnyway, onCheckedChange = vm::setWriteAnyway)
                                Text(stringResource(R.string.write_anyway))
                            }
                        }
                    }
                }
                ui.imageError?.let { Notice(Icons.Filled.Warning, it, MaterialTheme.colorScheme.error) }
                FilledTonalButton(onClick = { picker.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) }) {
                    Text(stringResource(if (ui.image == null) R.string.choose_iso else R.string.choose_other_iso))
                }
            }

            // Step 2: drive
            SectionCard(stringResource(R.string.step_choose_drive), step = 2) {
                if (!vm.hasUsbHost) Notice(Icons.Filled.Warning, stringResource(R.string.no_usb_host), MaterialTheme.colorScheme.error)
                val opened = ui.opened
                if (opened != null) {
                    ListItem(
                        headlineContent = { Text(opened.displayName, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text("${ByteSize.marketingCapacity(opened.sizeBytes)} · ${ByteSize.format(opened.sizeBytes)}") },
                        leadingContent = { Icon(Icons.Filled.Usb, null) },
                        trailingContent = { Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                    if (opened.blockDevice.identity.writeProtected) Notice(Icons.Filled.Lock, stringResource(R.string.drive_write_protected), MaterialTheme.colorScheme.error)
                    val img = ui.image
                    if (img != null && !ui.fits) {
                        Notice(Icons.Filled.Warning, stringResource(R.string.drive_too_small, ByteSize.format(img.source.sizeBytes)), MaterialTheme.colorScheme.error)
                    }
                } else if (drives.isEmpty()) {
                    Notice(Icons.Filled.Info, stringResource(R.string.plug_in_drive))
                } else {
                    drives.forEach { d ->
                        ListItem(
                            headlineContent = { Text(d.label) },
                            supportingContent = { Text(stringResource(R.string.tap_to_use_drive)) },
                            leadingContent = { Icon(Icons.Filled.Usb, null) },
                            modifier = Modifier.clickable(enabled = !ui.openingDrive) { vm.selectDrive(d) },
                        )
                    }
                }
                if (ui.openingDrive) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.opening_drive))
                }
                ui.driveError?.let { Notice(Icons.Filled.Warning, it, MaterialTheme.colorScheme.error) }
                if (compat) Notice(Icons.Filled.Info, stringResource(R.string.compat_on_notice))
            }

            if (ui.isWindows) CustomizeCard(ui.customize, vm::updateCustomize)
            if (ui.isWindows) DriversCard(ui, vm::setAddDrivers) { folderPicker.launch(null) }

            ui.battery?.takeIf { it.low }?.let {
                Notice(Icons.Filled.BatteryAlert, stringResource(R.string.battery_low, it.percent), MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = vm::requestStart,
                enabled = ui.canStart && job?.isActive != true,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.start_write), style = MaterialTheme.typography.titleMedium) }
        }
    }

    if (ui.showConfirm) EraseConfirmDialog(ui, onConfirm = vm::confirmAndStart, onDismiss = vm::dismissConfirm)
}

@Composable
private fun CustomizeCard(c: CustomizeUi, update: ((CustomizeUi) -> CustomizeUi) -> Unit) {
    SectionCard(stringResource(R.string.step_customize), step = 3) {
        ToggleRow(stringResource(R.string.cust_enable), stringResource(R.string.cust_enable_desc), c.enabled) { v -> update { it.copy(enabled = v) } }
        if (!c.enabled) return@SectionCard
        androidx.compose.material3.HorizontalDivider()
        if (dev.ruchin.anvil.app.BuildConfig.ALLOW_HW_BYPASS) {
            ToggleRow(stringResource(R.string.cust_bypass), stringResource(R.string.cust_bypass_desc), c.bypass) { v -> update { it.copy(bypass = v) } }
        }
        ToggleRow(stringResource(R.string.cust_local), stringResource(R.string.cust_local_desc), c.localAccount) { v -> update { it.copy(localAccount = v) } }
        if (c.localAccount) {
            androidx.compose.material3.OutlinedTextField(
                value = c.userName, onValueChange = { v -> update { it.copy(userName = v.take(20)) } },
                label = { Text(stringResource(R.string.cust_username)) }, singleLine = true,
                isError = !c.userNameValid,
                supportingText = { if (!c.userNameValid) Text(stringResource(R.string.cust_username_invalid)) },
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.OutlinedTextField(
                value = c.password, onValueChange = { v -> update { it.copy(password = v) } },
                label = { Text(stringResource(R.string.cust_password)) }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                supportingText = { Text(stringResource(R.string.cust_password_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ToggleRow(stringResource(R.string.cust_privacy), stringResource(R.string.cust_privacy_desc), c.skipPrivacy) { v -> update { it.copy(skipPrivacy = v) } }
        ToggleRow(stringResource(R.string.cust_telemetry), stringResource(R.string.cust_telemetry_desc), c.reduceTelemetry) { v -> update { it.copy(reduceTelemetry = v) } }
        ToggleRow(stringResource(R.string.cust_bitlocker), stringResource(R.string.cust_bitlocker_desc), c.disableBitLocker) { v -> update { it.copy(disableBitLocker = v) } }
        ToggleRow(stringResource(R.string.cust_bloat), stringResource(R.string.cust_bloat_desc), c.removeBloat) { v -> update { it.copy(removeBloat = v) } }
        val r = c.region
        ToggleRow(
            stringResource(R.string.cust_region),
            if (r != null) listOfNotNull(r.timeZone, r.userLocale, stringResource(R.string.cust_keyboard, r.inputLocale)).joinToString(" · ") else "",
            c.useRegion,
        ) { v -> update { it.copy(useRegion = v) } }
    }
}

@Composable
private fun DriversCard(ui: WriteUiState, setEnabled: (Boolean) -> Unit, pick: () -> Unit) {
    SectionCard(stringResource(R.string.drivers_title)) {
        ToggleRow(stringResource(R.string.drivers_title), stringResource(R.string.drivers_desc), ui.addDrivers, setEnabled)
        if (!ui.addDrivers) return@SectionCard
        val folder = ui.driversFolder
        if (folder != null) {
            Notice(
                Icons.Filled.CheckCircle,
                stringResource(R.string.drivers_summary, folder.name, folder.files.size, ByteSize.format(folder.totalBytes)),
                MaterialTheme.colorScheme.primary,
            )
        } else if (!ui.readingDrivers) {
            Notice(Icons.Filled.Info, stringResource(R.string.drivers_needed))
        }
        if (ui.readingDrivers) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.drivers_reading))
        }
        ui.driversError?.let { Notice(Icons.Filled.Warning, it, MaterialTheme.colorScheme.error) }
        FilledTonalButton(onClick = pick, enabled = !ui.readingDrivers) {
            Text(stringResource(if (folder == null) R.string.drivers_pick else R.string.drivers_change))
        }
    }
}

/** Shown after a Windows write: the fix for Setup's "No drivers were found" on Intel RST/VMD laptops. */
@Composable
private fun DriversHelpCard(driversIncluded: Boolean) {
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.drivers_help_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(
                stringResource(if (driversIncluded) R.string.drivers_help_body else R.string.drivers_help_body_none),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc.isNotEmpty()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SchemePicker(scheme: dev.ruchin.anvil.fat.PartitionScheme, onChange: (dev.ruchin.anvil.fat.PartitionScheme) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.advanced_hide else R.string.advanced_show))
    }
    if (expanded) {
        Column {
            Text(stringResource(R.string.partition_scheme), style = MaterialTheme.typography.labelLarge)
            for (s in dev.ruchin.anvil.fat.PartitionScheme.entries) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(s) }) {
                    androidx.compose.material3.RadioButton(selected = scheme == s, onClick = { onChange(s) })
                    Column {
                        Text(stringResource(if (s == dev.ruchin.anvil.fat.PartitionScheme.GPT) R.string.scheme_gpt else R.string.scheme_mbr))
                        Text(
                            stringResource(if (s == dev.ruchin.anvil.fat.PartitionScheme.GPT) R.string.scheme_gpt_desc else R.string.scheme_mbr_desc),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageKindNotice(kind: ImageKind) {
    when (kind) {
        ImageKind.HYBRID_ISO, ImageKind.DISK_IMAGE ->
            Notice(Icons.Filled.CheckCircle, stringResource(R.string.kind_bootable), MaterialTheme.colorScheme.primary)
        ImageKind.WINDOWS_ISO ->
            Notice(Icons.Filled.Info, stringResource(R.string.kind_windows), MaterialTheme.colorScheme.tertiary)
        ImageKind.NON_HYBRID_ISO ->
            Notice(Icons.Filled.Warning, stringResource(R.string.kind_non_hybrid), MaterialTheme.colorScheme.error)
        ImageKind.UNKNOWN ->
            Notice(Icons.Filled.Warning, stringResource(R.string.kind_unknown), MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun EraseConfirmDialog(ui: WriteUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val drive = ui.opened ?: return
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp)) },
        title = {
            Text(
                stringResource(R.string.erase_title, drive.displayName, ByteSize.marketingCapacity(drive.sizeBytes)),
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.erase_body))
                ui.battery?.takeIf { it.low }?.let { Text(stringResource(R.string.battery_low, it.percent), color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { understood = !understood }) {
                    Checkbox(understood, onCheckedChange = { understood = it })
                    Text(stringResource(R.string.erase_understand))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = understood,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(stringResource(R.string.erase_confirm)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
