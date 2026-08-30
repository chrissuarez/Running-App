package com.example.runningapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.run.RunRoute
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * How a pending route choice survives the process being rebuilt (#56).
 *
 * [RunRoute] is not `Parcelable` and is not going to be: it is the rulebook's word for a course a
 * Run set out on, and making it carry an Android interface to suit one screen's saved state would
 * put Android in the run module. Two values written down and read back is the whole of what saving
 * it needs.
 */
val RunRouteSaver: Saver<RunRoute?, Any> = listSaver(
    save = { route -> route?.let { listOf(it.routeId, it.reversed) } ?: emptyList() },
    restore = { saved ->
        if (saved.size != 2) null else RunRoute(saved[0] as Long, saved[1] as Boolean)
    },
)

/**
 * The pre-run route picker (#56): which course this Run will follow, and which way round.
 *
 * Outdoor only, and offered by the screen rather than decided by it — a treadmill Run follows no
 * course, and that rule is the Run's, applied where its configuration is pinned. Shown even when the
 * library is empty, because "you have no routes yet" is the answer a runner looking for the picker
 * needs, and a card that simply is not there reads as a feature that is not built.
 *
 * [picked] is the Route the library actually holds for the runner's pick, not the pick itself: a
 * course deleted from the library while this screen sat open leaves the card saying "No route",
 * which is the truth, rather than naming a row that has gone.
 */
@Composable
fun RoutePickerCard(
    routes: List<RouteHeader>,
    picked: RouteHeader?,
    reversed: Boolean,
    onPick: (Long?) -> Unit,
    onReversedChange: (Boolean) -> Unit
) {
    var choosing by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Text("Route", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            if (routes.isEmpty()) {
                Text(
                    text = runRouteLibraryEmptyLine(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = runRouteChoiceSummary(picked, reversed),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { choosing = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RunningUiTokens.MinTouchTarget)
                ) {
                    Text("Choose a route")
                }
                // Only where there is a course to turn round. A switch offered beside "No route"
                // would be a control with nothing to act on, and one left on from a previous pick
                // would silently apply itself to the next.
                if (picked != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RunningUiTokens.MinTouchTarget),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ROUTE_REVERSED_TOGGLE_LABEL,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = reversed, onCheckedChange = onReversedChange)
                    }
                }
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Choose a route") },
            text = {
                LazyColumn {
                    // "No route" at the top rather than as a separate button, so following nothing
                    // is one of the choices in the same list and can be got back to the same way.
                    item {
                        RouteChoiceRow(
                            label = NO_ROUTE_CHOICE_LABEL,
                            subtitle = null,
                            selected = picked == null,
                            onSelect = {
                                onPick(null)
                                choosing = false
                            }
                        )
                    }
                    items(routes, key = { it.id }) { route ->
                        RouteChoiceRow(
                            label = route.name,
                            subtitle = routeRowSubtitle(route),
                            selected = picked?.id == route.id,
                            onSelect = {
                                onPick(route.id)
                                choosing = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun RouteChoiceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunningUiTokens.MinTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

