package com.example.runningapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RouteThumbnail

// The little square drawn beside a row in a list, and everything drawn inside it. One file rather
// than one per screen because the whole point of the square is that it reads as the same thing in
// both the lists that hold one — see [ThumbnailCanvas].

/** How big the drawing is, and how thick its line — a thumb-tip of route beside a row's words. */
internal val ThumbnailSize = 56.dp
private val ThumbnailLineWidth = 2.dp

/**
 * The shape of a route, drawn beside the row it belongs to — a Run in History (#51), a Route in the
 * library (#59). See [RouteThumbnail] for why it is an outline and not a map.
 *
 * Each stroke is a stretch that was actually covered, so a Run that paused is drawn as two lines
 * with a gap between them rather than one line across ground nobody witnessed. A kept course is
 * always one stroke, having no Breaks to cut it at.
 */
@Composable
internal fun RouteThumbnailDrawing(thumbnail: RouteThumbnail) {
    ThumbnailCanvas { stroked ->
        thumbnail.strokes.forEach { stretch ->
            stroked {
                stretch.forEachIndexed { i, point ->
                    if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
        }
    }
}

/**
 * The square a row holds open, and the one line weight and colour everything in it is drawn with — so a route and a treadmill read as one family of squares rather than as a drawing
 * beside an icon (#232).
 *
 * [content] is handed the one thing it needs: a way to stroke a line whose points are fractions of
 * the square, from (0,0) at the top left to (1,1) at the bottom right — the square [RouteThumbnail]
 * already speaks in.
 */
@Composable
private fun ThumbnailCanvas(content: (stroked: (ThumbnailStroke.() -> Unit) -> Unit) -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    val stroke = with(LocalDensity.current) { ThumbnailLineWidth.toPx() }
    Canvas(modifier = Modifier.size(ThumbnailSize)) {
        // Inset by half the line's width, so anything drawn along the edge of the square is drawn
        // whole rather than shaved in half by it.
        val inset = stroke / 2f
        val span = size.minDimension - stroke
        content { build ->
            val path = Path()
            ThumbnailStroke(path, inset, span).build()
            drawPath(
                path = path,
                color = line,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/** A line being drawn in the thumbnail's square, in fractions of it rather than in pixels. */
private class ThumbnailStroke(
    private val path: Path,
    private val inset: Float,
    private val span: Float,
) {
    fun moveTo(x: Float, y: Float) = path.moveTo(at(x), at(y))
    fun lineTo(x: Float, y: Float) = path.lineTo(at(x), at(y))
    private fun at(fraction: Float) = inset + fraction * span
}

/**
 * The treadmill drawn beside an indoor Run (#232) — a deck, and the console raised at the end of it.
 *
 * Worth being honest about what this is: a route outline earns its square by telling you something,
 * since every Run's is a different shape. This is the same picture every time and tells you only
 * what kind of Run it was; it earns its square by keeping the text edge still down a list that mixes
 * indoor and outdoor Runs. So it is stroked at the same weight and in the same colour as a route —
 * the list has to read as one family of squares, not as an icon set beside a drawing.
 *
 * Drawn in profile, in as few lines as it can be and still be a treadmill: at 56dp anything closer
 * together than about four of these hundredths is a smudge. Chosen from seven candidates against
 * the real list — the ones that lost were either too dense to survive the size (a head-on view, a
 * running figure) or closed shapes that read as an icon rather than as a drawn line.
 */
@Composable
internal fun TreadmillDrawing() {
    ThumbnailCanvas { stroked ->
        // Deck, arm and console in one unbroken line, the way a route is drawn: the console tilts
        // back over the deck because that is the side the runner reads it from, and joined to the
        // arm rather than floating above it, which at this size read as a detached tick.
        stroked {
            moveTo(0.09f, 0.72f)
            lineTo(0.64f, 0.72f)
            lineTo(0.80f, 0.32f)
            lineTo(0.62f, 0.26f)
        }
        // The deck's two feet, splayed the way a machine's are — most of what says this is a
        // machine and not a bar.
        stroked {
            moveTo(0.16f, 0.72f)
            lineTo(0.12f, 0.82f)
        }
        stroked {
            moveTo(0.57f, 0.72f)
            lineTo(0.61f, 0.82f)
        }
    }
}

