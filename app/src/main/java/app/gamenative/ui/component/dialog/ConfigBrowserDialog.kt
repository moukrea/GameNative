package app.gamenative.ui.component.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.utils.PlannedChange

/** Hardware-proximity section a config falls under, best first. */
enum class ConfigTier { GAMENATIVE, SAME_DEVICE, SAME_GPU, LOWER_TIER, OTHER }

/**
 * One browsable config row. [appliedKey] is matched against the container's recorded applied profile
 * to mark the active one. [onPreview] (when non-null) shows the planned changes before applying.
 */
data class ConfigBrowserRow(
    val source: String, // "GameNative" | "EmuReady"
    val tier: ConfigTier,
    val appliedKey: String, // matched against currentApplied to flag the active config
    val title: String, // primary line: device name, or "GameNative recommended"
    val subtitle: String?, // GPU · performance
    val fps: String?, // best-effort free-text, e.g. "25" or "20-30"
    val notes: String?, // EmuReady report notes
    val onApply: () -> Unit,
    val onPreview: (() -> Unit)?,
)

/**
 * Unified known-config browser: configs from BOTH GameNative and EmuReady, sectioned by hardware
 * proximity (same device > same GPU > lower-tier GPU > other), showing which is applied, with inline
 * notes, a planned-changes preview, one-tap apply, and reset-to-defaults.
 */
@Composable
fun ConfigBrowserDialog(
    currentApplied: String?,
    rows: List<ConfigBrowserRow>,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                    Text(
                        text = stringResourceSafe(R.string.config_browser_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResourceSafe(R.string.config_browser_applied, currentApplied ?: "—"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                HorizontalDivider()

                if (rows.isEmpty()) {
                    Text(
                        text = stringResourceSafe(R.string.emuready_no_configs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ConfigTier.values().forEach { tier ->
                            val group = rows.filter { it.tier == tier }
                            if (group.isNotEmpty()) {
                                item { SectionHeader(tier) }
                                items(group) { row ->
                                    ConfigCard(row = row, applied = currentApplied == row.appliedKey)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onResetDefaults(); onDismiss() }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResourceSafe(R.string.config_browser_reset_defaults))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResourceSafe(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(tier: ConfigTier) {
    val id = when (tier) {
        ConfigTier.GAMENATIVE -> R.string.config_browser_section_gamenative
        ConfigTier.SAME_DEVICE -> R.string.config_browser_section_same_device
        ConfigTier.SAME_GPU -> R.string.config_browser_section_same_gpu
        ConfigTier.LOWER_TIER -> R.string.config_browser_section_lower
        ConfigTier.OTHER -> R.string.config_browser_section_other
    }
    Text(
        text = stringResourceSafe(id),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ConfigCard(row: ConfigBrowserRow, applied: Boolean) {
    var notesExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = if (applied) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            row.subtitle?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        Icons.Filled.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (!row.fps.isNullOrBlank()) {
                    Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResourceSafe(R.string.config_browser_fps, row.fps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (applied) {
                    if (!row.fps.isNullOrBlank()) Spacer(Modifier.width(12.dp))
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResourceSafe(R.string.config_browser_applied_chip),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (!row.notes.isNullOrBlank()) {
                TextButton(onClick = { notesExpanded = !notesExpanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (notesExpanded) stringResourceSafe(R.string.config_browser_hide_notes) else stringResourceSafe(R.string.config_browser_show_notes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Icon(
                        if (notesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (notesExpanded) {
                    Text(
                        row.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.onPreview?.let { preview ->
                    TextButton(onClick = preview) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResourceSafe(R.string.config_browser_preview))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = row.onApply) { Text(stringResourceSafe(R.string.config_browser_apply)) }
            }
        }
    }
}

/**
 * Planned-changes preview: the settings a config will alter (current -> new), plus an optional note
 * about components that will be installed/substituted and a custom-component (DXVK "other") warning.
 */
@Composable
fun PlannedChangesDialog(
    title: String,
    changes: List<PlannedChange>,
    missingNote: String?,
    customComponentNote: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResourceSafe(R.string.config_browser_preview_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
                    if (changes.isEmpty()) {
                        Text(stringResourceSafe(R.string.config_browser_no_changes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        changes.forEach { c ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.42f))
                                Text(c.current.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.28f))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text(c.next.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.28f))
                            }
                        }
                    }
                    customComponentNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 12.dp))
                    }
                    missingNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResourceSafe(R.string.close)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(); onDismiss() }) { Text(stringResourceSafe(R.string.config_browser_apply)) }
                }
            }
        }
    }
}

@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)
