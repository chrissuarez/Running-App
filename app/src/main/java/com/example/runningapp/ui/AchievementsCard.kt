package com.example.runningapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordUnit
import com.example.runningapp.data.Achievement
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * What this Run took a medal for (#49) — gold, silver or bronze at any of the seven records.
 *
 * Draws nothing at all when the Run won nothing, which is most Runs. An achievements card that
 * appeared on every page saying "no records" would turn an ordinary Tuesday into a report card; the
 * card is worth having precisely because seeing it means something happened.
 *
 * Silver and bronze are shown beside gold rather than only all-time bests, because a runner who is
 * improving takes second and third place constantly and a page that only ever congratulated the
 * outright best would be silent for months at a time.
 */
@Composable
fun AchievementsCard(achievements: List<Achievement>, modifier: Modifier = Modifier) {
    if (achievements.isEmpty()) return
    // Best first, and within a place in the records' own order, so the card reads the same way every
    // time rather than in whatever order the database handed the rows over.
    val ordered = achievements.sortedWith(compareBy({ it.medal.ordinal }, { it.type.ordinal }))

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            ordered.forEachIndexed { index, achievement ->
                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                AchievementRow(achievement)
            }
        }
    }
}

@Composable
private fun AchievementRow(achievement: Achievement) {
    val value = achievement.valueText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "${achievement.medal.face.spoken}, ${achievement.type.label}, $value"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        MedalDisc(achievement.medal)
        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
        Text(
            text = achievement.type.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The medal itself: a disc in its own metal carrying its place.
 *
 * The number is on it because colour alone cannot be read by everyone who will look at this page,
 * and silver and bronze are not far apart on a small screen at arm's length after a run.
 *
 * Shared with the Run's Segments card (#71) rather than drawn again there: a medal for a hill and a
 * medal for a 5 km are the same claim about the same runner, and two discs would sooner or later be
 * two different golds on one page.
 */
@Composable
internal fun MedalDisc(medal: Medal) {
    Box(
        modifier = Modifier
            .size(RunningUiTokens.MedalDiscSize)
            .clip(CircleShape)
            .background(medal.face.color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${medal.face.place}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            // Fixed rather than from the scheme: the discs are their own metals in either theme, and
            // a light-theme "on surface" would be white text on gold.
            color = Color(0xFF1A1A1A)
        )
    }
}

/** How a medal is shown and how it is said: its metal, its place, and its name out loud. */
internal data class MedalFace(val color: Color, val place: Int, val spoken: String)

internal val Medal.face: MedalFace
    get() = when (this) {
        Medal.GOLD -> MedalFace(Color(0xFFD4AF37), 1, "Gold")
        Medal.SILVER -> MedalFace(Color(0xFFB8BCC2), 2, "Silver")
        Medal.BRONZE -> MedalFace(Color(0xFFC08A4E), 3, "Bronze")
    }

/**
 * The effort that won the medal, said in the record's own unit: a time for the five distances and
 * for the longest Run by the clock, a distance for the longest by ground.
 */
private val Achievement.valueText: String
    get() = when (type.unit) {
        RecordUnit.SECONDS -> formatDuration(value.toLong())
        RecordUnit.METERS -> "%.2f km".format(value / 1_000.0)
    }
