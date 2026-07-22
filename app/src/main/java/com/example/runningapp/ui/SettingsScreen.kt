package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.example.runningapp.HrZone
import com.example.runningapp.MAX_MAX_HR
import com.example.runningapp.MIN_MAX_HR
import com.example.runningapp.UserSettings
import com.example.runningapp.parseMaxHr
import com.example.runningapp.targetHrZone
import com.example.runningapp.targetRangeLabel
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * Everything the app still lets you configure: two numbers defining what coaching *means*, three
 * switches for what it *does*, then the hardware, then the developer drawer (#112).
 *
 * **There is no Save button.** Every change reaches disk the moment it is made. Three of the five
 * items are switches, and a switch that hasn't taken effect is lying — it is already drawn "on",
 * which is exactly what made the old discard-on-Back invisible. Applying immediately deletes that
 * bug rather than guarding against it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    strapSummary: String,
    onMaxHrCommit: (Int) -> Unit,
    onTargetZoneChange: (HrZone) -> Unit,
    onCoachingEnabledChange: (Boolean) -> Unit,
    onSplitAnnouncementsChange: (Boolean) -> Unit,
    onAutoPauseChange: (Boolean) -> Unit,
    onAiDataSharingChange: (Boolean) -> Unit,
    onTestingModeChange: (Boolean) -> Unit,
    onManageStrap: () -> Unit,
    onBack: () -> Unit
) {
    var showTargetZonePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(RunningUiTokens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsSectionHeader("Your zones")
            MaxHrField(maxHr = settings.maxHr, onCommit = onMaxHrCommit)
            SettingsRow(
                label = "Target zone",
                // Load-bearing: the workout sets the target and this is only the fallback, so
                // unqualified the label is a lie by omission — you would set it to Threshold and
                // wonder why your Moderate plan ignored it.
                subtitle = "Used for open runs. Plans set their own.",
                value = "Zone ${settings.targetHrZone.number} · ${settings.targetHrZone.zoneName}",
                onClick = { showTargetZonePicker = true }
            )

            Spacer(modifier = Modifier.height(RunningUiTokens.SectionSpacing))
            SettingsSectionHeader("During a run")
            SettingsSwitchRow(
                label = "Zone coaching",
                checked = settings.coachingEnabled,
                onCheckedChange = onCoachingEnabledChange
            )
            SettingsSwitchRow(
                label = "Split announcements",
                checked = settings.splitAnnouncementsEnabled,
                onCheckedChange = onSplitAnnouncementsChange
            )
            SettingsSwitchRow(
                label = "Auto-pause",
                checked = settings.autoPauseEnabled,
                onCheckedChange = onAutoPauseChange
            )

            Spacer(modifier = Modifier.height(RunningUiTokens.SectionSpacing))
            SettingsSectionHeader("Heart rate strap")
            SettingsRow(
                label = strapSummary,
                subtitle = null,
                value = null,
                onClick = onManageStrap
            )

            Spacer(modifier = Modifier.height(RunningUiTokens.SectionSpacing))
            SettingsSectionHeader("Advanced")
            SettingsSwitchRow(
                label = "AI training data sharing",
                checked = settings.aiDataSharingEnabled,
                onCheckedChange = onAiDataSharingChange
            )
            SettingsSwitchRow(
                label = "Testing mode",
                checked = settings.testingModeEnabled,
                onCheckedChange = onTestingModeChange
            )
        }
    }

    if (showTargetZonePicker) {
        TargetZonePicker(
            selected = settings.targetHrZone,
            maxHr = settings.maxHr,
            onSelect = {
                onTargetZoneChange(it)
                showTargetZonePicker = false
            },
            onDismiss = { showTargetZonePicker = false }
        )
    }
}

/**
 * The single input the whole zone model hangs off, so the one place a silent failure would cost
 * the most: an unusable entry is refused where you can see it and the field keeps what you typed.
 * It never quietly reverts to the old number.
 *
 * Commits on blur rather than per keystroke, because typing "190" passes through "1" and "19" —
 * both of which are invalid, and neither of which is a mistake.
 */
@Composable
private fun MaxHrField(maxHr: Int, onCommit: (Int) -> Unit) {
    // Keyed on the stored value so an outside change (the #65 card, once it lands) shows up here.
    var typed by remember(maxHr) { mutableStateOf(maxHr.toString()) }
    var refused by remember(maxHr) { mutableStateOf(false) }
    // Commit needs a keystroke, not just a visit. Retyping the number it already holds still
    // counts as a deliberate set — that is the point of the flag — but tapping the field by
    // accident and tapping away must not: that would spend the one-shot history recompute on the
    // placeholder 190, which is the exact outcome the flag exists to prevent. The explicit
    // "keep the current value" gesture belongs to #65's confirmation card.
    var edited by remember(maxHr) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun commitIfEdited() {
        if (!edited) return
        val parsed = parseMaxHr(typed)
        if (parsed == null) {
            // Refused where you can see it, keeping what you typed. The old field kept the
            // previous number instead and said nothing.
            refused = true
        } else {
            refused = false
            edited = false
            onCommit(parsed)
        }
    }

    // Leaving the screen commits too. Back doesn't blur the field in touch mode, so hanging the
    // commit on focus alone would drop a typed number on the way out — the same silent discard
    // this screen exists to delete, just moved one gesture along.
    val commitOnLeaving by rememberUpdatedState(::commitIfEdited)
    DisposableEffect(Unit) {
        onDispose { commitOnLeaving() }
    }

    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it
            edited = true
            refused = false
        },
        label = { Text("Max HR") },
        singleLine = true,
        isError = refused,
        supportingText = {
            if (refused) Text("Enter a heart rate between $MIN_MAX_HR and $MAX_MAX_HR")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        // Done clears focus rather than committing directly, so both ways of finishing with the
        // field take the same path.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) commitIfEdited()
            }
    )
}

@Composable
private fun TargetZonePicker(
    selected: HrZone,
    maxHr: Int,
    onSelect: (HrZone) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target zone") },
        text = {
            Column {
                HrZone.COACHING_TARGETS.forEach { zone ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                            .selectable(
                                selected = zone == selected,
                                onClick = { onSelect(zone) },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = zone == selected, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Zone ${zone.number} · ${zone.zoneName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${targetRangeLabel(zone, maxHr)} BPM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    label: String,
    subtitle: String?,
    value: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * How the strap row reads: the strap you would run with, and what it is doing right now.
 *
 * The row is a link to the pairing screen rather than a device list, so this line is the whole of
 * what Settings says about hardware.
 */
fun strapRowSummary(settings: UserSettings, connectionStatus: String): String {
    val activeAddress = settings.activeDeviceAddress ?: return "No strap paired"
    val name = settings.savedDevices.firstOrNull { it.address == activeAddress }?.name
        ?: "Saved strap"
    return "$name · $connectionStatus"
}
