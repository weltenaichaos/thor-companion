package thor.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View

/** The app's colours: dark like the game's own frames, with gold for what you can tap. */
object Theme {
    val BG = Color.rgb(13, 15, 19)
    val SURFACE = Color.rgb(26, 30, 37)
    val RAISED = Color.rgb(36, 41, 50)
    val STROKE = Color.rgb(46, 52, 62)
    val TEXT = Color.rgb(232, 234, 238)
    val DIM = Color.rgb(140, 147, 158)
    val ACCENT = Color.rgb(255, 196, 64)
    val PRESSED = Color.rgb(70, 78, 92)
    val GOOD = Color.rgb(90, 210, 120)
    val WARN = Color.rgb(255, 200, 80)
    val BAD = Color.rgb(255, 90, 90)
    val GOLD = Color.rgb(255, 210, 90)
    val SILVER = Color.rgb(200, 205, 212)
    val COPPER = Color.rgb(222, 140, 90)
    val XP = Color.rgb(160, 80, 220)
    val RESTED = Color.rgb(70, 110, 200)
    val XP_TEXT = Color.rgb(200, 150, 255)
    val RESTED_TEXT = Color.rgb(130, 170, 255)

    /** A rounded box; [stroke] draws a thin edge in that colour. */
    fun box(context: Context, fill: Int, radiusDp: Int = 12, stroke: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        val d = context.resources.displayMetrics.density
        cornerRadius = radiusDp * d
        setColor(fill)
        if (stroke != null) setStroke((strokeDp * d).toInt().coerceAtLeast(1), stroke)
    }

    fun withAlpha(colour: Int, alpha: Int) = Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))
}

/** A thin rounded progress bar, with an optional second value behind the first (rested experience). */
class Bar(context: Context) : View(context) {
    var value = 0f
    var second = 0f
    var colour = Theme.XP
    var secondColour = Theme.RESTED
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        fun part(to: Float, c: Int) {
            if (to <= 0f) return
            paint.color = c
            r.set(0f, 0f, maxOf(height.toFloat(), width * to.coerceIn(0f, 1f)), height.toFloat())
            canvas.drawRoundRect(r, radius, radius, paint)
        }
        part(1f, Theme.RAISED)
        part(second, secondColour)
        part(value, colour)
    }
}
