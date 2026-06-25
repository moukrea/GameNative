package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R

/**
 * One browsable config row: which source it came from, a hardware-tiered label, optional EmuReady
 * notes (expandable inline), and the apply action.
 */
data class ConfigBrowserRow(
    val source: String, // e.g. "GameNative" | "EmuReady"
    val label: String, // e.g. "Adreno 740 · Playable · your GPU"
    val notes: String?, // EmuReady report notes (where "other" values are explained)
    val onApply: () -> Unit,
)

/**
 * Unified "known config" browser: lists configs from BOTH GameNative and EmuReady (already ordered
 * closest-hardware-first), shows which profile is currently applied, lets the user expand an
 * EmuReady report's notes inline, apply any row, or reset the container to defaults.
 */
@Composable
fun ConfigBrowserDialog(
    currentApplied: String?,
    rows: List<ConfigBrowserRow>,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expandedNotes by remember { mutableStateOf(-1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResourceSafe(R.string.config_browser_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResourceSafe(R.string.config_browser_applied, currentApplied ?: "—"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (rows.isEmpty()) {
                    Text(stringResourceSafe(R.string.emuready_no_configs))
                }
                rows.forEachIndexed { i, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(onClick = {}, label = { Text(row.source) })
                        Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { row.onApply(); onDismiss() }) {
                            Text(stringResourceSafe(R.string.config_browser_apply))
                        }
                    }
                    if (!row.notes.isNullOrBlank()) {
                        TextButton(onClick = { expandedNotes = if (expandedNotes == i) -1 else i }) {
                            Text(
                                if (expandedNotes == i) {
                                    stringResourceSafe(R.string.config_browser_hide_notes)
                                } else {
                                    stringResourceSafe(R.string.config_browser_show_notes)
                                },
                            )
                        }
                        if (expandedNotes == i) {
                            Text(
                                row.notes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResetDefaults(); onDismiss() }) {
                Text(stringResourceSafe(R.string.config_browser_reset_defaults))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResourceSafe(R.string.close)) } },
    )
}

@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)
