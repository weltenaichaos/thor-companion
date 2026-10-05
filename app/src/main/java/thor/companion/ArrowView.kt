package thor.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** A small arrow that turns to point the way, for the quest compass. */
class ArrowView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val arrow = Path()
    private var shownDegrees = 0f

    /** Turns to [degrees] (clockwise, 0 = up) the short way round, smoothly. */
    fun pointTo(degrees: Float) {
        var delta = (degrees - shownDegrees) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        shownDegrees += delta
        animate().rotation(shownDegrees).setDuration(MapView.GLIDE_MS).start()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - d
        arrow.rewind()
        arrow.moveTo(cx, cy - r)
        arrow.lineTo(cx + r * 0.7f, cy + r * 0.8f)
        arrow.lineTo(cx, cy + r * 0.35f)
        arrow.lineTo(cx - r * 0.7f, cy + r * 0.8f)
        arrow.close()
        canvas.drawPath(arrow, fill)
    }
}
