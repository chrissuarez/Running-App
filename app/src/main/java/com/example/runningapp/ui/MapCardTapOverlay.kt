package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** What a screen reader is offered for the tap, said once so both halves of the layer agree. */
private const val OpenFullScreenMapLabel = "Open full-screen map"

/**
 * How wide the corner is that Mapbox's own controls are left to, measured from the map's left edge.
 *
 * Wide enough to hold the logo wordmark and the attribution "i" beside it, with room to spare. The
 * figures come off the phone rather than off a datasheet: on a Pixel 8a the Run-detail card is 379
 * dp across, its logo runs from 6 dp to 88 dp, and the "i" ends at 113 dp. Everything Mapbox draws
 * is inside 136 dp, so this is that with a margin, on a card that is nowhere near narrow enough for
 * the corner to be most of it.
 */
internal val MapboxCornerWidth = 144.dp

/**
 * How tall that corner is, measured up from the map's bottom edge.
 *
 * The "i" is a 48 dp touch target sat on the bottom edge. Fifty-six is that plus a margin, because
 * a control whose touch target is one pixel outside this corner is a control that cannot be tapped,
 * and there is nothing on either card that needs the extra eight.
 */
internal val MapboxCornerHeight = 56.dp

/**
 * Whether a point on a map falls in the corner [MapboxCornerWidth] and [MapboxCornerHeight] name,
 * in dp from the map's own top-left (#409).
 *
 * This reads the two constants and nothing else, and so is a check on *them* — is the corner big
 * enough to hold the control it is protecting — and not on the layout below, which is the thing
 * that actually places the hole. The layout is built from the same two constants, so the two agree
 * on the size; whether the hole is in the right place is a phone test's answer, and only a phone
 * test's. Worth having anyway: the size is the half of this that can be trimmed by eye months from
 * now, in a diff where nothing looks wrong.
 */
internal fun isInMapboxCorner(xDp: Float, yDp: Float, mapHeightDp: Float): Boolean =
    xDp <= MapboxCornerWidth.value && yDp >= mapHeightDp - MapboxCornerHeight.value

/**
 * The layer that turns a whole map card into one big "open the full-screen map" button (#357),
 * leaving Mapbox's own bottom-left corner alone (#409).
 *
 * The layer has to be over the map and not under it: a Mapbox map is an Android View inside the
 * composition and it takes a touch before anything wrapped around it hears about it, which is why a
 * [clickable] on the Card itself did nothing. But over the map is also over the logo and the
 * attribution "i", which Mapbox draws in the map's bottom-left corner — and those are Compose
 * controls sitting between the map View and this layer, so a layer that covered them swallowed
 * every tap meant for them. Measured on the phone: tapping the "i" on either card opened the
 * full-screen map instead of the attribution dialog, which is the terms this app uses Mapbox's maps
 * under saying the control must stay reachable and the card saying otherwise.
 *
 * So the layer is a full-width strip above and an L around the corner below, with nothing over the
 * corner itself — no layer there means the tap falls through to the control underneath it. A hole
 * and not a smaller overlay, because the corner is the only part of the map that has something of
 * its own to hit; the rest of the bottom edge still opens the map.
 *
 * The full-screen maps need none of this. They have no layer over them at all, which is why the
 * same "i" has always worked there.
 *
 * The ticket read the fault the other way round — `uiautomator` reported the covered "i" as a 21 px
 * sliver sitting just *below* the card, which reads as a control laid out past the bottom of the
 * space the card gives it. It is not: that is what a covered Compose node reports, and the pixels
 * say otherwise. A screenshot has the "i" drawn well inside the card, and with this hole cut the
 * same node reports a whole 126 x 122 px box around where it is drawn. So there is nothing here
 * setting a margin on Mapbox's attribution plugin, which is the fix the ticket expected.
 *
 * A map narrower than [MapboxCornerWidth] would have the hole take the whole of the bottom strip,
 * and taps along that strip would stop opening the map. Left as it is rather than clamped: both
 * cards are the full width of a page, so the narrowest this gets is a 320 dp screen's card, which
 * is still nearly twice the corner — and a clamp would be a second rule about the corner's size,
 * kept honest by nothing.
 */
@Composable
fun BoxScope.MapCardTapOverlay(onClick: () -> Unit) {
    Column(modifier = Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(onClickLabel = OpenFullScreenMapLabel, onClick = onClick)
        )
        Row(modifier = Modifier.fillMaxWidth().height(MapboxCornerHeight)) {
            Spacer(modifier = Modifier.width(MapboxCornerWidth).fillMaxHeight())
            // Hidden from a screen reader, and only from a screen reader. The card is one button
            // and has to be announced as one; two nodes offering the identical "open the map" would
            // read as two buttons where there was one. A finger still opens the map from here — it
            // is the strip above that a screen reader is given, and it does the same thing.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clearAndSetSemantics { }
                    .clickable(onClick = onClick)
            )
        }
    }
}
