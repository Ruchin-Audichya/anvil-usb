package dev.ruchin.anvil.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ruchin.anvil.app.R
import dev.ruchin.anvil.app.jobs.JobKind
import dev.ruchin.anvil.app.jobs.JobState
import dev.ruchin.anvil.app.jobs.JobStatus
import dev.ruchin.anvil.common.ByteSize
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.common.formatEta

/** The one progress UI used for downloads and USB writes: bar, %, sizes, speed, ETA, stage checklist. */
@Composable
fun TransferProgressCard(
    state: JobState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onWriteDownloaded: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = when (state.status) {
        JobStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        JobStatus.SUCCEEDED -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            val stageText = stringResource(state.stageNote ?: state.stages[state.stageIndex])
            if (state.isActive) {
                val animated by animateFloatAsState(state.fraction, animationSpec = tween(400), label = "progress")
                val pct = (animated * 100).toInt()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$pct%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(stageText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
                }
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(10.dp)
                        .semantics { contentDescription = "$stageText $pct%" },
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                if (state.bytesTotal > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${ByteSize.format(state.bytesDone)} / ${ByteSize.format(state.bytesTotal)}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${ByteSize.formatRate(state.bytesPerSecond)} · ${stringResource(R.string.eta, formatEta(state.etaSeconds))}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.isActive) state.currentFile?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis)
            }

            StageList(state)

            when (state.status) {
                JobStatus.RUNNING -> OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
                JobStatus.SUCCEEDED -> {
                    state.verifiedNote?.let { note ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(note, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        stringResource(if (state.kind == JobKind.WRITE_USB) R.string.write_success else R.string.download_success),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    state.resultDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    state.outputPath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    val path = state.outputPath
                    if (path != null && onWriteDownloaded != null && state.kind == JobKind.DOWNLOAD) {
                        androidx.compose.material3.Button(onClick = { onWriteDownloaded(path) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.write_downloaded_to_usb))
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                }
                JobStatus.FAILED -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(state.errorMessage.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (state.kind == JobKind.WRITE_USB) {
                        Text(stringResource(R.string.write_failed_recovery), style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        TextButton(onClick = { copyDebugLog(context, state) }) { Text(stringResource(R.string.action_copy_log)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    }
                }
                JobStatus.CANCELLED -> {
                    Text(
                        stringResource(if (state.kind == JobKind.WRITE_USB) R.string.write_cancelled else R.string.download_cancelled),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}

@Composable
private fun StageList(state: JobState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.stages.forEachIndexed { i, res ->
            val done = i < state.stageIndex || state.status == JobStatus.SUCCEEDED
            val current = i == state.stageIndex && state.status != JobStatus.SUCCEEDED
            val failedHere = current && state.status == JobStatus.FAILED
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    failedHere -> Icon(Icons.Filled.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    done -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    current && state.isActive -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else -> Icon(Icons.Outlined.Circle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun copyDebugLog(context: Context, state: JobState?) {
    val text = buildString {
        state?.let { appendLine("Job: ${it.kind} ${it.status} — ${it.title}"); it.technicalError?.let { t -> appendLine("Error: $t") } }
        appendLine("---")
        append(DebugLog.dump())
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Anvil debug log", text))
    Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
}
