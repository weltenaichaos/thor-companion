package thor.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import thor.companion.strip.MapPlace
import thor.companion.strip.ZoneMap
import kotlin.math.hypot

/**
 * The zone drawn from what the addon sends: the places on it, the path you walked
 * and an arrow for you. Not the game's map picture (the app can't read that), so
 * the background is a plain grid. Shows the whole zone, or with [close] the area
 * around you.
 */
class MapView(context: Context) : View(context) {
    var zone: ZoneMap? = null
    /** The zone's picture, taken from the game's world map; null draws a grid. */
    var picture: android.graphics.Bitmap? = null
    var trail: List<List<DoubleArray>> = emptyList()
    var x: Double? = null
    var y: Double? = null
    var facing: Double? = null
    var close = false
    /** Called with the place nearest to a tap. */
    var onPlace: (MapPlace) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = Color.rgb(40, 46, 56); strokeWidth = d }
    private val edge = Paint().apply { color = Color.rgb(70, 78, 92); style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 196, 64); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 224, 230); textSize = 11 * d; setShadowLayer(2 * d, 0f, 0f, Color.BLACK) }
    private val photo = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = Color.argb(70, 0, 0, 0) }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 210, 0) }
    private val areaEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 0); style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val areaText = Paint(text).apply { textAlign = Paint.Align.CENTER; color = Color.rgb(255, 230, 120) }
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // The part of the map shown (0..1 map units) and where it lands on screen.
    private var left = 0.0
    private var top = 0.0
    private var span = 1.0
    private var ox = 0f
    private var oy = 0f
    private var w = 0f
    private var h = 0f

    private fun sx(mx: Double) = ox + ((mx - left) / span * w).toFloat()
    private fun sy(my: Double) = oy + ((my - top) / span * h).toFloat()

    override fun onDraw(canvas: Canvas) {
        // WoW's zone maps are 3:2; fit that into the view.
        w = minOf(width.toFloat(), height * 1.5f)
        h = w / 1.5f
        ox = (width - w) / 2
        oy = (height - h) / 2
        val px = x; val py = y
        if (close && px != null && py != null) {
            span = 0.2
            left = px - span / 2
            top = py - span / 2
        } else {
            span = 1.0; left = 0.0; top = 0.0
        }
        canvas.save()
        canvas.clipRect(ox, oy, ox + w, oy + h)
        canvas.drawColor(Color.rgb(22, 26, 32))
        val pic = picture
        if (pic != null) {
            canvas.drawBitmap(pic, null, android.graphics.RectF(sx(0.0), sy(0.0), sx(1.0), sy(1.0)), photo)
            canvas.drawRect(ox, oy, ox + w, oy + h, shade)
        } else {
            val stepGrid = if (close) 0.02 else 0.1
            var g = Math.floor(left / stepGrid) * stepGrid
            while (g <= left + span) { canvas.drawLine(sx(g), oy, sx(g), oy + h, grid); g += stepGrid }
            g = Math.floor(top / stepGrid) * stepGrid
            while (g <= top + span) { canvas.drawLine(ox, sy(g), ox + w, sy(g), grid); g += stepGrid }
        }

        // Quest objectives are areas, not spots: a soft circle where to do them.
        for (place in zone?.places.orEmpty()) {
            if (place.kind != 'q') continue
            val radius = (0.035 / span * w).toFloat()
            canvas.drawCircle(sx(place.x), sy(place.y), radius, area)
            canvas.drawCircle(sx(place.x), sy(place.y), radius, areaEdge)
        }

        for (piece in trail) {
            if (piece.size < 2) continue
            val p = Path()
            p.moveTo(sx(piece[0][0]), sy(piece[0][1]))
            for (i in 1 until piece.size) p.lineTo(sx(piece[i][0]), sy(piece[i][1]))
            canvas.drawPath(p, path)
        }

        for (place in zone?.places.orEmpty()) {
            val cx = sx(place.x); val cy = sy(place.y)
            if (place.kind == 'q') {
                // Many quests share an area; their names only fit when zoomed in (tap one otherwise).
                if (close) canvas.drawText(short(place.label), cx, cy + 4 * d, areaText)
                continue
            }
            // Where to pick up and turn in quests stands out most.
            val r = if (place.kind == 'a' || place.kind == 'Q') 11 * d else 8 * d
            dot.color = colour(place.kind)
            canvas.drawCircle(cx, cy, r, dot)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawText(symbol(place.kind), cx, cy + 4 * d, glyph)
            if (place.kind != 'g' && place.kind != 'v' || close) {
                canvas.drawText(short(place.label), cx + r + 3 * d, cy + 4 * d, text)
            }
        }

        if (px != null && py != null) {
            canvas.save()
            canvas.translate(sx(px), sy(py))
            // Facing: 0 is north, counter-clockwise; the canvas turns clockwise.
            canvas.rotate(-Math.toDegrees(facing ?: 0.0).toFloat())
            val arrow = Path().apply {
                moveTo(0f, -11 * d); lineTo(7 * d, 8 * d); lineTo(0f, 4 * d); lineTo(-7 * d, 8 * d); close()
            }
            if (facing == null) {
                canvas.drawCircle(0f, 0f, 6 * d, me)
                canvas.drawCircle(0f, 0f, 6 * d, ring)
            } else {
                canvas.drawPath(arrow, me)
                canvas.drawPath(arrow, ring)
            }
            canvas.restore()
        }
        canvas.restore()
        canvas.drawRect(ox, oy, ox + w, oy + h, edge)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action != MotionEvent.ACTION_UP) return false
        val nearest = zone?.places?.minByOrNull { hypot(sx(it.x) - e.x, sy(it.y) - e.y) } ?: return true
        if (hypot(sx(nearest.x) - e.x, sy(nearest.y) - e.y) < 28 * d) onPlace(nearest) else performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun short(s: String) = if (s.length > 18) s.take(17) + "…" else s

    companion object {
        fun symbol(kind: Char) = when (kind) {
            'a' -> "!"; 'q' -> ""; 'Q' -> "?"; 'w' -> "•"; 'c' -> "✝"; 'f' -> "F"; 'd' -> "D"; 'p' -> "◆"; 'v' -> "★"; 'g' -> "●"
            else -> ""
        }

        fun colour(kind: Char) = when (kind) {
            'a' -> Color.rgb(255, 210, 0)
            'q' -> Color.rgb(255, 210, 0)
            'Q' -> Color.rgb(255, 240, 120)
            'w' -> Color.rgb(90, 200, 255)
            'c' -> Color.rgb(200, 200, 200)
            'f' -> Color.rgb(120, 220, 120)
            'd' -> Color.rgb(200, 140, 255)
            'p' -> Color.rgb(170, 180, 200)
            'v' -> Color.rgb(255, 140, 60)
            'g' -> Color.rgb(100, 160, 255)
            else -> Color.GRAY
        }

        fun kindName(kind: Char) = when (kind) {
            'a' -> "Pick up a quest"; 'q' -> "Do this quest here"; 'Q' -> "Turn in a quest"; 'w' -> "Map pin"; 'c' -> "Corpse"
            'f' -> "Flight master"; 'd' -> "Dungeon"; 'p' -> "Place"; 'v' -> "Rare or treasure"; 'g' -> "Group member"
            else -> "Place"
        }
    }
}
