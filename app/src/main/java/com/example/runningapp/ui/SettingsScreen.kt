package com.example.runningapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.MAX_MAX_HR
import com.example.runningapp.MIN_MAX_HR
import com.example.runningapp.MIN_RESTING_HR
import com.example.runningapp.RESTING_HR_UNSTATED
import com.example.runningapp.UserSettings
import com.example.runningapp.highestStatableRestingHr
import com.example.runningapp.parseMaxHr
import com.example.runningapp.parseRestingHr
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
    onRestingHrCommit: (Int) -> Unit,
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
    val maxHrState = rememberHrFieldState(settings.maxHr, parse = ::parseMaxHr)
    // Judged against Max HR: the two numbers have to leave a usable reserve between them, so what
    // this field accepts moves when the other one does — without disturbing what is being typed.
    //
    // Against the Max HR *in force* rather than the one on disk, because leaving commits both at
    // once: weighed against storage, lowering Max HR to 100 and stating a resting 90 in the same
    // visit accepts both here and then stores the 90 as 50 — the silent replacement 36fef08
    // deleted, arriving through the other door.
    val restingHrState = rememberHrFieldState(settings.restingHr, blankMeans = RESTING_HR_UNSTATED) {
        parseRestingHr(it, maxHrState.valueInForce)
    }

    // Emptying the resting field is the one edit on this screen that is asked about rather than
    // simply applied. Everything else here states a number; this one *withdraws* a measurement,
    // moving every zone edge back and re-banding the whole of history with it — and it is a
    // plausible slip, since clearing a field is how you begin retyping it. So the commit is parked
    // here and only reaches the repository once the runner has read what it does.
    var clearingRestingHr by remember { mutableStateOf(false) }
    fun commitRestingHr(value: Int) {
        if (value == RESTING_HR_UNSTATED) clearingRestingHr = true else onRestingHrCommit(value)
    }

    // Leaving commits both heart-rate fields, and an unusable entry holds the screen open once so
    // the error is readable — see [HrFieldState.onLeaveAttempt]. Both ways out go through it: the
    // top bar arrow and the system back button/gesture, the latter intercepted via [BackHandler]
    // so it cannot dispose the screen behind the check.
    //
    // Intercepting also repairs where the system back went from here, as on [FullScreenMapScreen]:
    // `navigateTo` clears the back stack, so an unhandled back popped the only destination and
    // left the app rather than returning to the main screen.
    //
    // Both fields are asked before the answer is used, so a pending edit in one is never dropped
    // because the other refused — `&&` would short-circuit past it.
    //
    // A parked clear holds the screen too: leaving while the question is on screen would apply it
    // behind the runner's back or drop it silently, and both are the failure this screen deletes.
    fun leave() {
        val maxHrReady = maxHrState.onLeaveAttempt(onMaxHrCommit)
        val restingHrReady = restingHrState.onLeaveAttempt(::commitRestingHr)
        if (maxHrReady && restingHrReady && !clearingRestingHr) onBack()
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
            HrField(
                state = maxHrState,
                label = "Max HR",
                supportingText = null,
                refusalText = "Enter a heart rate between $MIN_MAX_HR and $MAX_MAX_HR",
                onCommit = onMaxHrCommit
            )
            HrField(
                state = restingHrState,
                label = "Resting HR",
                // Says what the number is for and how to get it: zones are sliced from the gap
                // between these two, so an unstated resting heart rate is not a blank to ignore.
                supportingText = "Measured at rest. Your zones are sliced from the gap between these two.",
                refusalText = "Enter a heart rate between $MIN_RESTING_HR and " +
                    "${highestStatableRestingHr(maxHrState.valueInForce)}",
                onCommit = ::commitRestingHr
            )
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

    if (clearingRestingHr) {
        ClearRestingHrDialog(
            currentRestingHr = settings.restingHr,
            // What the target band reads now, and what it would read with the resting heart rate
            // withdrawn — the same arithmetic the zones use, so the warning cannot drift from what
            // actually happens.
            //
            // Off the Max HR *in force* for that reason: leaving commits both fields, so a visit
            // that lowers the maximum and then clears the resting number would otherwise quote a
            // before and after taken from a maximum already on its way out.
            bandNow = targetRangeLabel(
                settings.targetHrZone,
                HrProfile(maxHrState.valueInForce, settings.restingHr)
            ),
            bandAfter = targetRangeLabel(
                settings.targetHrZone,
                HrProfile(maxHrState.valueInForce)
            ),
            targetZone = settings.targetHrZone,
            onConfirm = {
                clearingRestingHr = false
                onRestingHrCommit(RESTING_HR_UNSTATED)
            },
            onDismiss = {
                clearingRestingHr = false
                // Declining puts the number still in force back in the field, so the screen does
                // not ask again on the next way out.
                restingHrState.restore()
            }
        )
    }

    if (showTargetZonePicker) {
        TargetZonePicker(
            selected = settings.targetHrZone,
            // Same reason as the clear dialog: the bands offered here are the ones the runner is
            // choosing between, so they have to be sliced from the maximum on its way to disk
            // rather than the one it is replacing.
            profile = HrProfile(maxHrState.valueInForce, settings.restingHr),
            onSelect = {
                onTargetZoneChange(it)
                showTargetZonePicker = false
            },
            onDismiss = { showTargetZonePicker = false }
        )
    }
}

/**
 * What a heart-rate field knows: what has been typed, whether it was refused, and whether leaving
 * the screen is allowed yet.
 *
 * Split out from the composable because the rules it holds are the ones worth being sure about —
 * when a commit happens, when it doesn't, and what Back does with an entry that can't be stored —
 * and none of them are testable while they live inside a `@Composable`.
 *
 * One class for both numbers rather than one each: Max HR and resting heart rate are the same kind
 * of input — a measured value, deliberately stated, refused where you can see it — and the rules
 * below are the ones that make that true. [parse] is the only thing that differs, so it is the
 * only thing passed in; a second copy of these rules is how the two fields would drift apart.
 */
@Stable
class HrFieldState(
    private val stored: Int,
    parse: (String) -> Int?,
    /**
     * What an emptied field means, for a number that has an "unstated" to go back to.
     *
     * Null for Max HR — there is no such thing as not having one, so blank stays a refusal. For the
     * resting heart rate blank is [RESTING_HR_UNSTATED], the value that gives back the Max-HR-only
     * model; without this the only way out of a measurement is another measurement.
     */
    private val blankMeans: Int? = null
) {
    /**
     * What the field will accept, kept current rather than captured once.
     *
     * The resting field's range depends on the Max HR beside it, so it moves while this state is
     * alive. Rebuilding the state to pick that up would throw away whatever was half-typed — and
     * a Max HR commit can land seconds later, after its re-tally of history, which is exactly when
     * someone is likely to be typing the second number. Replacing the rule keeps both true: the
     * entry survives, and it is judged by the range in force now.
     */
    var parse: (String) -> Int? = parse
    // An unstated number shows an empty field, not a zero: zero is how storage spells "nobody has
    // said", and printing it would look like a heart rate the runner had somehow chosen.
    private fun storedAsTyped(): String = if (stored > 0) stored.toString() else ""

    var typed by mutableStateOf(storedAsTyped())
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
     * Puts the field back to what is stored, with nothing pending.
     *
     * For backing out of a commit the screen decided to ask about: declining the question has to
     * leave the field showing the number still in force, or the screen would keep asking on every
     * way out.
     */
    fun restore() {
        typed = storedAsTyped()
        edited = false
        refused = false
        leaveRefused = false
    }

    /**
     * What the field currently holds, or null if it cannot be stored.
     *
     * Blank is the one entry whose meaning depends on the field: for a number with an unstated
     * state it *is* a value ([blankMeans]), everywhere else it is the same mistake as "abc".
     */
    private fun pending(): Int? =
        if (typed.isBlank()) blankMeans else parse(typed)

    /**
     * The number this field will put in force: its pending entry if that is usable, otherwise the
     * one already stored.
     *
     * The field beside it is judged against this rather than against storage, because leaving
     * commits both and the writes are asynchronous. Judged against disk, a resting heart rate can
     * be accepted here and then quietly clamped by the Max HR landing beside it — the runner is
     * shown back a number they never typed, which is the one failure this screen exists to delete.
     */
    val valueInForce: Int get() = pending() ?: stored

    /**
     * Blur, or Done on the keyboard. An unusable entry is refused where you can see it, keeping
     * what you typed — the old field kept the *previous* number instead and said nothing.
     */
    fun onCommitAttempt(onCommit: (Int) -> Unit) {
        if (!edited) return
        val parsed = pending()
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
        val parsed = pending()
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

/**
 * Keyed on the stored value alone, so an outside change (the #65 card, once it lands) shows up
 * here. A moved *range* refreshes [HrFieldState.parse] instead of rebuilding the state, so it
 * never costs the runner what they were part-way through typing.
 */
@Composable
private fun rememberHrFieldState(
    stored: Int,
    blankMeans: Int? = null,
    parse: (String) -> Int?
): HrFieldState = remember(stored) { HrFieldState(stored, parse, blankMeans) }.also { it.parse = parse }

/**
 * The two inputs the whole zone model hangs off, so the place a silent failure would cost the
 * most: an unusable entry is refused where you can see it and the field keeps what you typed.
 * It never quietly reverts to the old number.
 *
 * Commits on blur rather than per keystroke, because typing "190" passes through "1" and "19" —
 * both of which are invalid, and neither of which is a mistake.
 */
@Composable
private fun HrField(
    state: HrFieldState,
    label: String,
    supportingText: String?,
    refusalText: String,
    onCommit: (Int) -> Unit
) {
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = state.typed,
        onValueChange = state::onTyped,
        label = { Text(label) },
        singleLine = true,
        isError = state.refused,
        supportingText = {
            if (state.refused) Text(refusalText) else if (supportingText != null) Text(supportingText)
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

/**
 * The one warning on this screen, and it earns its place: withdrawing a measurement moves every
 * zone edge and re-bands the whole of history behind it.
 *
 * Says what changes in the numbers the runner actually reads — their target band, before and after
 * — rather than naming the model, and says plainly what is *not* touched, because "recalculated"
 * beside a list of runs reads like the runs are at risk. Neither sentence is a warning about
 * danger; nothing here is lost. It is a warning about scope.
 */
@Composable
private fun ClearRestingHrDialog(
    currentRestingHr: Int,
    bandNow: String,
    bandAfter: String,
    targetZone: HrZone,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear your resting heart rate?") },
        text = {
            Column {
                Text(
                    "Your zones go back to being sliced from Max HR alone. " +
                        "Zone ${targetZone.number} would read $bandAfter BPM instead of $bandNow.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Every past run's zone times are worked out again to match, so your history " +
                        "stays comparable. Your runs, distances and heart-rate recordings are not " +
                        "affected, and stating the number again puts it all back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep $currentRestingHr") } }
    )
}

@Composable
private fun TargetZonePicker(
    selected: HrZone,
    profile: HrProfile,
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
                            "${targetRangeLabel(zone, profile)} BPM",
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
