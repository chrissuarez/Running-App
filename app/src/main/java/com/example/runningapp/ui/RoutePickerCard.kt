package com.example.runningapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RouteThumbnail
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
 *
 * The pick's `reversed` is the switch on this card — against the course's *usual* way — and not yet
 * what a Run writes down. The two differ on a flipped course, and START turns one into the other
 * ([runRouteSetOutAlong], #466).
 */
val RunRouteSaver: Saver<RunRoute?, Any> = listSaver(
    save = { route -> route?.let { listOf(it.routeId, it.reversed) } ?: emptyList() },
    restore = { saved ->
        if (saved.size != 2) null else RunRoute(saved[0] as Long, saved[1] as Boolean)
    },
)

/**
 * The choice a pick leaves behind (#56, #496): [routeId] the course tapped, null for no route.
 *
 * A different course starts pointing its usual way. Carrying the last pick's direction over would
 * send the runner backwards round a course they never asked to reverse; re-picking the one already
 * chosen keeps the direction they set on it.
 */
fun runRouteAfterPick(previous: RunRoute?, routeId: Long?): RunRoute? =
    routeId?.let { RunRoute(it, reversed = it == previous?.routeId && previous.reversed) }

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
 *
 * [targetMeters] is how far today's session is likely to cover, or null where too little history
 * has been recorded to say (#422) — see [suggestedRouteDistanceMeters]. It prints a hint here, and
 * the Routes screens [onChoose] opens sort by it. It never picks: the runner still taps, because the
 * target is usually derived from a median and the runner knows things about today that the median
 * does not.
 *
 * The choosing itself happens on the Routes screens rather than in a dialog of names (#496): a
 * runner picks a course by its shape, its lengths and the Runs already on it, and those screens
 * already show all three.
 *
 * [targetIsFixed] says the plan stated that distance rather than the phone estimating it, which is
 * the Test days. It changes both the things [targetMeters] does: the hint's wording
 * ([routeSuggestionHint]) and the picking library's order ([routeLibraryRowsNearestFirst]), because a stated distance is a floor
 * the Run has to reach rather than a middle to be nearest to, so a course short of it is offered
 * after every course that is long enough. It is carried as its own flag rather than worked out from
 * the number here, because "is this a distance the plan holds" is a question about the plan and this
 * card cannot see one.
 */
@Composable
fun RoutePickerCard(
    routes: List<RouteHeader>,
    picked: RouteHeader?,
    /** [picked]'s shape, null while it is being drawn or where the course has none (#496). */
    pickedThumbnail: RouteThumbnail?,
    reversed: Boolean,
    targetMeters: Double?,
    targetIsFixed: Boolean,
    /** Opening the Routes screens to pick from — see [RoutePicking]. */
    onChoose: () -> Unit,
    onReversedChange: (Boolean) -> Unit
) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The square only where there is a course, held open while its drawing is
                    // worked out so the words do not jump sideways when it arrives (#496).
                    if (picked != null) {
                        Box(modifier = Modifier.size(ThumbnailSize)) {
                            pickedThumbnail?.let { RouteThumbnailDrawing(it) }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        text = runRouteChoiceSummary(picked, reversed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Only where there are courses to compare it against (#422). "Today ≈ 7 km" beside
                // "you have no routes yet" is advice about a library that holds nothing to take it.
                if (targetMeters != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    RouteSuggestionHintLine(targetMeters, targetIsFixed)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onChoose,
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
}

/**
 * How far today's session is likely to cover, said once and drawn in both places it belongs (#422).
 *
 * One composable rather than the same three lines on the card and at the top of the picking Routes
 * list (#496), because the two are deliberately the same line: a runner who reads `Today ≈ 7 km` on
 * the card and something styled differently on the list would take them for two different claims.
 */
@Composable
internal fun RouteSuggestionHintLine(
    targetMeters: Double,
    targetIsFixed: Boolean,
    modifier: Modifier = Modifier
) {
    Text(
        text = routeSuggestionHint(targetMeters, targetIsFixed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}
