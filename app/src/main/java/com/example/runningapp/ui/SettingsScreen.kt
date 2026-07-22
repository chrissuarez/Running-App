package com.example.runningapp.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
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
    val maxHrState = rememberMaxHrFieldState(settings.maxHr)

    // Leaving commits the Max HR field, and an unusable entry holds the screen open once so the
    // error is readable — see [MaxHrFieldState.onLeaveAttempt]. Both ways out go through it: the
    // top bar arrow and the system back button/gesture, the latter intercepted via [BackHandler]
    // so it cannot dispose the screen behind the check.
    //
    // Intercepting also repairs where the system back went from here, as on [FullScreenMapScreen]:
    // `navigateTo` clears the back stack, so an unhandled back popped the only destination and
    // left the app rather than returning to the main screen.
    fun leave() {
        if (maxHrState.onLeaveAttempt(onMaxHrCommit)) onBack()
    }
    BackHandler(onBack = ::leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
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
            MaxHrField(state = maxHrState, onCommit = onMaxHrCommit)
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
                onCheckedChange = onAiDataSharingChange,
                // Testing mode forces sharing off and holds it there, so the row says so rather
                // than accepting a tap the store would refuse.
                subtitle = "Off while Testing mode is on.".takeIf { settings.testingModeEnabled },
                enabled = !settings.testingModeEnabled
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
 * What the Max HR field knows: what has been typed, whether it was refused, and whether leaving
 * the screen is allowed yet.
 *
 * Split out from the composable because the rules it holds are the ones worth being sure about —
 * when a commit happens, when it doesn't, and what Back does with an entry that can't be stored —
 * and none of them are testable while they live inside a `@Composable`.
 */
@Stable
class MaxHrFieldState(storedMaxHr: Int) {
    var typed by mutableStateOf(storedMaxHr.toString())
        private set
    var refused by mutableStateOf(false)
        private set

    // Commit needs a keystroke, not just a visit. Retyping the number it already holds still
    // counts as a deliberate set — that is the point of the flag — but tapping the field by
    // accident and tapping away must not: that would spend the one-shot history recompute on the
    // placeholder Max HR, which is the exact outcome the flag exists to prevent. The explicit
    // "keep the current value" gesture belongs to #65's confirmation card.
    private var edited = false

    // Whether Back has already been refused once for the entry currently in the field.
    private var leaveRefused = false

    fun onTyped(text: String) {
        typed = text
        edited = true
        refused = false
        leaveRefused = false
    }

    /**
     * Blur, or Done on the keyboard. An unusable entry is refused where you can see it, keeping
     * what you typed — the old field kept the *previous* number instead and said nothing.
     */
    fun onCommitAttempt(onCommit: (Int) -> Unit) {
        if (!edited) return
        val parsed = parseMaxHr(typed)
        if (parsed == null) {
            refused = true
        } else {
            refused = false
            edited = false
            onCommit(parsed)
        }
    }

    /**
     * Whether Back may proceed, committing a valid pending edit on the way out.
     *
     * Back doesn't blur the field in touch mode, so a typed number would otherwise be dropped on
     * the way out — the same silent discard this screen exists to delete, moved one gesture along.
     *
     * An *unusable* entry can't be committed and mustn't vanish quietly either, so the first Back
     * stays put and shows the error rather than disposing the screen before it can be read. A
     * second Back leaves anyway: refusing once makes the refusal visible, refusing forever would
     * trap the runner behind a number they may have no way to make valid.
     */
    fun onLeaveAttempt(onCommit: (Int) -> Unit): Boolean {
        if (!edited) return true
        val parsed = parseMaxHr(typed)
        if (parsed != null) {
            refused = false
            edited = false
            onCommit(parsed)
            return true
        }
        if (leaveRefused) return true
        refused = true
        leaveRefused = true
        return false
    }
}

@Composable
private fun rememberMaxHrFieldState(storedMaxHr: Int): MaxHrFieldState =
    // Keyed on the stored value so an outside change (the #65 card, once it lands) shows up here.
    remember(storedMaxHr) { MaxHrFieldState(storedMaxHr) }

/**
 * The single input the whole zone model hangs off, so the one place a silent failure would cost
 * the most: an unusable entry is refused where you can see it and the field keeps what you typed.
 * It never quietly reverts to the old number.
 *
 * Commits on blur rather than per keystroke, because typing "190" passes through "1" and "19" —
 * both of which are invalid, and neither of which is a mistake.
 */
@Composable
private fun MaxHrField(state: MaxHrFieldState, onCommit: (Int) -> Unit) {
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = state.typed,
        onValueChange = state::onTyped,
        label = { Text("Max HR") },
        singleLine = true,
        isError = state.refused,
        supportingText = {
            if (state.refused) Text("Enter a heart rate between $MIN_MAX_HR and $MAX_MAX_HR")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        // Done clears focus rather than committing directly, so both ways of finishing with the
        // field take the same path.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) state.onCommitAttempt(onCommit)
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
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                // Greyed with the switch, so a row that will not respond doesn't read as one that
                // is simply switched off.
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
