package com.example.runningapp.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.example.runningapp.FloatingCoachPosition
import com.example.runningapp.ZoneBand
import com.example.runningapp.ui.workout.zoneBandColorOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The floating coach's window over other apps (#545) — thin on purpose. What it shows and when it
 * shows are decided in [floatingCoachFace] and [floatingCoachShows]; this only adds, draws, drags and
 * removes the view.
 *
 * Plain Views rather than Compose: a ComposeView outside an activity needs a lifecycle and a saved
 * state registry wired by hand, and a heart rate, a dial and one line of text do not need either.
 *
 * Main thread only, like every WindowManager call.
 */
class FloatingCoachWindow(
    private val context: Context,
    /** The runner let go after dragging it: where it now sits, to be remembered. */
    private val onMoved: (FloatingCoachPosition) -> Unit,
    /** The runner tapped it without dragging. */
    private val onTapped: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var dial: DialView
    private lateinit var bpmText: TextView
    private lateinit var nextText: TextView

    val isShowing: Boolean get() = root != null

    /**
     * Puts the box up if it is not up already, then draws [face] on it. [position] is used only when
     * the window is first added: once it is up, the runner's own drag is the only thing that moves it.
     */
    fun show(face: FloatingCoachFace, position: FloatingCoachPosition?) {
        if (root == null && !add(position)) return
        render(face)
    }

    /** Takes the box down. Safe to call when it is not up. */
    fun hide() {
        val view = root ?: return
        root = null
        params = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Already gone — the system can take an overlay away when the permission is withdrawn.
            Log.w(TAG, "Floating coach was not attached: ${e.message}")
        }
    }

    private fun add(position: FloatingCoachPosition?): Boolean {
        // Asked here as well as by the caller: adding an overlay without the permission throws, and
        // this is the one line that adds it.
        if (!Settings.canDrawOverlays(context)) return false
        val view = build()
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes the keyboard or the back button from the app underneath; touches outside
            // the box go straight through to it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // A place remembered on another orientation or screen size is pulled back on screen,
            // or the box could open out of reach of the finger that would drag it back.
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val bounds = windowManager.currentWindowMetrics.bounds
            x = (position?.x ?: dp(16)).coerceIn(0, (bounds.width() - view.measuredWidth).coerceAtLeast(0))
            y = (position?.y ?: dp(120)).coerceIn(0, (bounds.height() - view.measuredHeight).coerceAtLeast(0))
        }
        return try {
            windowManager.addView(view, layout)
            root = view
            params = layout
            true
        } catch (e: RuntimeException) {
            // A permission withdrawn between the check and the add (BadTokenException or
            // SecurityException): no box this time, and the next tick asks again.
            Log.w(TAG, "Floating coach could not be added: ${e.message}")
            false
        }
    }

    private fun render(face: FloatingCoachFace) {
        bpmText.text = face.bpmText
        bpmText.setTextColor(bandColor(face.band) ?: Color.WHITE)
        nextText.text = face.nextStepText.orEmpty()
        nextText.visibility = if (face.nextStepText == null) View.GONE else View.VISIBLE
        dial.set(face.needle, face.band)
    }

    @SuppressLint("ClickableViewAccessibility") // performClick is called on a tap, below.
    private fun build(): View {
        dial = DialView(context)
        bpmText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BPM_TEXT_SP)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }
        nextText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NEXT_STEP_TEXT_SP)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // The number sits inside the dial's arch, so the box stays the size of the dial.
        val dialWithNumber = FrameLayout(context).apply {
            addView(dial, FrameLayout.LayoutParams(dp(DIAL_WIDTH_DP), dp(DIAL_HEIGHT_DP)))
            addView(
                bpmText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            // See-through enough that subtitles behind it can still be read.
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(BACKGROUND)
            }
            contentDescription = "Floating coach. Tap to open the app, drag to move."
            addView(dialWithNumber)
            addView(nextText)
        }
        box.setOnTouchListener(DragOrTap(box))
        return box
    }

    /** A drag moves the box; a press that never travelled past the touch slop is a tap. */
    private inner class DragOrTap(private val box: View) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layout = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val bounds = windowManager.currentWindowMetrics.bounds
                        layout.x = (startX + dx.toInt()).coerceIn(0, (bounds.width() - box.width).coerceAtLeast(0))
                        layout.y = (startY + dy.toInt()).coerceIn(0, (bounds.height() - box.height).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(box, layout)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Floating coach moved after it was taken down: ${e.message}")
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        onMoved(FloatingCoachPosition(layout.x, layout.y))
                    } else {
                        view.performClick()
                        onTapped()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onMoved(FloatingCoachPosition(layout.x, layout.y))
                    dragging = false
                }
            }
            return true
        }
    }

    /**
     * A speedo-style arch: below, in and above the target zone as three equal sweeps, left to right,
     * in the live screen's own band colours, with a needle where the heart rate sits.
     */
    private inner class DialView(context: Context) : View(context) {
        private var needle: Float? = null
        private var band: ZoneBand = ZoneBand.UNKNOWN
        private val stroke = dp(6).toFloat()
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
        }
        private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val oval = RectF()

        fun set(needle: Float?, band: ZoneBand) {
            if (needle == this.needle && band == this.band) return
            this.needle = needle
            this.band = band
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val radius = minOf(width / 2f, height.toFloat()) - stroke
            val cx = width / 2f
            val cy = height - stroke / 2f
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            val sweep = 180f * (GAUGE_TARGET_END - GAUGE_TARGET_START)
            listOf(ZoneBand.BELOW, ZoneBand.IN, ZoneBand.ABOVE).forEachIndexed { i, part ->
                arcPaint.color = bandColor(part) ?: Color.GRAY
                // The band the heart rate is in at full strength; the other two faded behind it.
                arcPaint.alpha = if (part == band) 255 else 90
                canvas.drawArc(oval, 180f + i * sweep, sweep, false, arcPaint)
            }
            val at = needle ?: return
            val angle = Math.toRadians((180.0 + at * 180.0))
            // A pointer on the outer part of the arch only, so it never crosses the number inside.
            val outer = radius - stroke
            val inner = outer * 0.55f
            canvas.drawLine(
                cx + (inner * cos(angle)).toFloat(),
                cy + (inner * sin(angle)).toFloat(),
                cx + (outer * cos(angle)).toFloat(),
                cy + (outer * sin(angle)).toFloat(),
                needlePaint
            )
        }
    }

    private fun bandColor(band: ZoneBand): Int? = zoneBandColorOrNull(band)?.toArgb()

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    private companion object {
        const val TAG = "FloatingCoach"
        const val DIAL_WIDTH_DP = 134
        const val DIAL_HEIGHT_DP = 77
        const val BPM_TEXT_SP = 36f
        const val NEXT_STEP_TEXT_SP = 17f
        // Black at about 55%: dark enough to read white on, light enough to see the film through.
        const val BACKGROUND = 0x8C000000.toInt()
    }
}
