package com.example.runningapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.ui.workout.TodayCardUiState

@Composable
internal fun MainBottomBar(
    onOpenHistory: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenManageDevices: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalButton(
                onClick = { },
                enabled = false,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget),
                contentPadding = BottomBarButtonPadding
            ) {
                Text(
                    text = "Home",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            FilledTonalButton(
                onClick = onOpenHistory,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget),
                contentPadding = BottomBarButtonPadding
            ) {
                Text(
                    text = "History",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            // Beside History, because the two answer the same question at different lengths: what
            // one run was, and what all of them add up to (#63).
            FilledTonalButton(
                onClick = onOpenProgress,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget),
                contentPadding = BottomBarButtonPadding
            ) {
                Text(
                    text = "Progress",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            FilledTonalButton(
                onClick = onOpenManageDevices,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget),
                contentPadding = BottomBarButtonPadding
            ) {
                Text(
                    text = "Devices",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            FilledTonalButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget),
                contentPadding = BottomBarButtonPadding
            ) {
                Text(
                    text = "Prefs",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Renders [TodayCardUiState] — which is where what this card is and why lives.
 *
 * Two shape decisions belong here. The link is a text link inside the card, bottom-right, so it
 * reads as an edit to the card it sits in rather than an alternative to starting — and undo lands
 * in the exact slot skip vacated, because the slot is the same one either way.
 *
 * And where a Stage offers a Pick (#174), today's Run keeps the heading it always had and the
 * three Workouts sit under it as rows. The heading is what the card is *about* — it carries the
 * target and the coach's note, which belong to the Run being started and to no other row — so the
 * rows are the menu it was Picked from, with the Pick highlighted among them.
 */

/**
 * How much room each bottom-bar button gives away to padding.
 *
 * Far tighter than a button's usual 24dp a side, because five of them share the width of the phone:
 * at the default 24dp the labels had only ~26dp of text space left and every one of them ellipsized
 * — including the two that begin the same way, leaving "Pr…" next to "Pr…" (#63). The buttons still
 * meet the minimum touch target through [RunningUiTokens.MinTouchTarget]; it is only the ink inside
 * them that moves.
 */
private val BottomBarButtonPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
