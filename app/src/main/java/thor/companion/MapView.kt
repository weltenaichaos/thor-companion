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
 * and an arrow for you, over the zone's picture once the app has taken one (a grid
 * until then). Shows the whole zone, or with [close] the area around you, [span]
 * of the zone wide; pinch to change it, like the minimap's zoom.
 */
class MapView(context: Context) : View(context) {
    var zone: ZoneMap? = null
    /** The zone's picture, taken from the game's map art; null draws a grid. */
    var picture: android.graphics.Bitmap? = null
    var trail: List<List<DoubleArray>> = emptyList()
    var x: Double? = null
    var y: Double? = null
    var facing: Double? = null
    var close = false
    /** How much of the zone's width the close view shows (0..1). */
    var span = 0.4
    /** Whether quest objective areas are drawn. */
    var questAreas = true
    /** A quest title whose places are marked more strongly (tapped in the Quests tab). */
    var highlight: String? = null
    /** The selected quest's nearest place and how far it is ("120 yd"), written under its ring. */
    var targetNote: Pair<MapPlace, String>? = null
    /** Called with the place nearest to a tap. */
    var onPlace: (MapPlace) -> Unit = {}
    /** Called after a pinch changed [close] or [span]. */
    var onZoom: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = Color.rgb(40, 46, 56); strokeWidth = d }
    private val edge = Paint().apply { color = Color.rgb(70, 78, 92); style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 196, 64); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 229, 234); textSize = 11 * d; setShadowLayer(2.5f * d, 0f, 0f, Color.BLACK) }
    private val photo = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = Color.argb(35, 0, 0, 0) }
    // Quest areas stay faint, so the zone picture under them can still be read.
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 210, 0) }
    private val areaEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 210, 0); style = Paint.Style.STROKE; strokeWidth = 1.2f * d
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val areaText = Paint(text).apply { textAlign = Paint.Align.CENTER; color = Color.rgb(255, 226, 130); textSize = 10 * d }
    private val marked = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val distanceText = Paint(text).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE; textSize = 13 * d; isFakeBoldText = true }
    private val bounds = android.graphics.RectF()
    private val arrow = Path()
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // The part of the map shown (0..1 map units) and where it lands on screen.
    private var left = 0.0
    private var top = 0.0
    private var shown = 1.0
    private var ox = 0f
    private var oy = 0f
    private var w = 0f
    private var h = 0f

    private fun sx(mx: Double) = ox + ((mx - left) / shown * w).toFloat()
    private fun sy(my: Double) = oy + ((my - top) / shown * h).toFloat()

    override fun onDraw(canvas: Canvas) {
        // WoW's zone maps are 3:2; fit that into the view.
        w = minOf(width.toFloat(), height * 1.5f)
        h = w / 1.5f
        ox = (width - w) / 2
        oy = (height - h) / 2
        val px = x; val py = y
        if (close && px != null && py != null) {
            shown = span.coerceIn(MIN_SPAN, 1.0)
            // Centred on you, but not past the zone's edge, where there is nothing to see.
            left = (px - shown / 2).coerceIn(0.0, 1 - shown)
            top = (py - shown / 2).coerceIn(0.0, 1 - shown)
        } else {
            shown = 1.0; left = 0.0; top = 0.0
        }
        val zoomed = shown <= 0.45
        canvas.save()
        canvas.clipRect(ox, oy, ox + w, oy + h)
        canvas.drawColor(Color.rgb(22, 26, 32))
        val pic = picture
        if (pic != null) {
            bounds.set(sx(0.0), sy(0.0), sx(1.0), sy(1.0))
            canvas.drawBitmap(pic, null, bounds, photo)
            canvas.drawRect(ox, oy, ox + w, oy + h, shade)
        } else {
            val stepGrid = if (shown < 0.5) 0.02 else 0.1
            var g = Math.floor(left / stepGrid) * stepGrid
            while (g <= left + shown) { canvas.drawLine(sx(g), oy, sx(g), oy + h, grid); g += stepGrid }
            g = Math.floor(top / stepGrid) * stepGrid
            while (g <= top + shown) { canvas.drawLine(ox, sy(g), ox + w, sy(g), grid); g += stepGrid }
        }

        // Quest objectives are areas, not spots: a soft circle where to do them.
        for (place in zone?.places.orEmpty()) {
            if (place.kind != 'q' || !questAreas) continue
            val radius = (0.03 / shown * w).toFloat()
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
                if (questAreas && zoomed) canvas.drawText(short(place.label), cx, cy + 4 * d, areaText)
                continue
            }
            // Where to pick up and turn in quests stands out most.
            val r = if (place.kind == 'a' || place.kind == 'Q') 9 * d else 7 * d
            dot.color = colour(place.kind)
            canvas.drawCircle(cx, cy, r, dot)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawText(symbol(place.kind), cx, cy + 3.5f * d, glyph)
            if (place.kind in "aQwcfd" || zoomed) {
                canvas.drawText(short(place.label), cx + r + 3 * d, cy + 4 * d, text)
            }
        }

        // The quest tapped in the Quests tab: a white ring round each of its places, whatever the toggles.
        val title = highlight
        if (title != null) for (place in zone?.places.orEmpty()) {
            if (place.kind !in "qQa" || !matches(place.label, title)) continue
            val r = if (place.kind == 'q') (0.03 / shown * w).toFloat() else 13 * d
            canvas.drawCircle(sx(place.x), sy(place.y), r, marked)
            if (place.kind == 'q' && !zoomed) canvas.drawText(short(place.label), sx(place.x), sy(place.y) + 4 * d, areaText)
        }
        // Where the selected quest wants you: a white ring, and how far it is.
        targetNote?.let { (place, note) ->
            val r = if (place.kind == 'q') (0.03 / shown * w).toFloat() else 13 * d
            canvas.drawCircle(sx(place.x), sy(place.y), r, marked)
            if (note.isNotEmpty()) canvas.drawText(note, sx(place.x), sy(place.y) + r + 14 * d, distanceText)
        }

        if (px != null && py != null) {
            canvas.save()
            canvas.translate(sx(px), sy(py))
            // Facing: 0 is north, counter-clockwise; the canvas turns clockwise.
            canvas.rotate(-Math.toDegrees(facing ?: 0.0).toFloat())
            arrow.rewind()
            arrow.moveTo(0f, -11 * d); arrow.lineTo(7 * d, 8 * d); arrow.lineTo(0f, 4 * d); arrow.lineTo(-7 * d, 8 * d); arrow.close()
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

    /**
     * Moves you to [nx], [ny] facing [nf]. The addon sends your position a few times
     * a second while you walk; in between the arrow glides there instead of jumping,
     * so the map moves smoothly without the game sending (or the app reading) more.
     * A jump (new zone, a portal) is shown at once.
     */
    fun glideTo(nx: Double?, ny: Double?, nf: Double?) {
        val fx = x; val fy = y; val ff = facing
        glide?.cancel()
        if (nx == null || ny == null || fx == null || fy == null || hypot(nx - fx, ny - fy) > 0.03) {
            x = nx; y = ny; facing = nf
            invalidate()
            return
        }
        // Turn the short way round.
        var turn = if (nf != null && ff != null) nf - ff else 0.0
        while (turn > Math.PI) turn -= 2 * Math.PI
        while (turn < -Math.PI) turn += 2 * Math.PI
        glide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLIDE_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction.toDouble()
                x = fx + (nx - fx) * t
                y = fy + (ny - fy) * t
                facing = if (nf == null || ff == null) nf else ff + turn * t
                invalidate()
            }
            start()
        }
    }

    private var glide: android.animation.ValueAnimator? = null

    override fun onDetachedFromWindow() {
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    /** Pinch to zoom: closer than the whole zone switches to the area around you. */
    private var pinched = false
    private val pinch = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: android.view.ScaleGestureDetector): Boolean {
            pinched = true
            val now = if (close) span else 1.0
            val next = (now / g.scaleFactor).coerceIn(MIN_SPAN, 1.0)
            close = next < 0.95
            if (close) span = next
            invalidate()
            return true
        }

        override fun onScaleEnd(g: android.view.ScaleGestureDetector) = onZoom()
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pinch.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            pinched = false
            // The tab scrolls; a pinch on the map must not turn into scrolling.
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_UP || pinched) return true
        val nearest = zone?.places?.minByOrNull { hypot(sx(it.x) - e.x, sy(it.y) - e.y) } ?: return true
        if (hypot(sx(nearest.x) - e.x, sy(nearest.y) - e.y) < 28 * d) onPlace(nearest) else performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** The addon cuts long labels with "…", so a label matches a title it starts. */
    private fun matches(label: String, title: String) = label.isNotEmpty() && title.startsWith(label.removeSuffix("…"))

    private fun short(s: String) = if (s.length > 18) s.take(17) + "…" else s

    companion object {
        /** About the time between two positions from the addon while walking. */
        const val GLIDE_MS = 500L

        const val MIN_SPAN = 0.08

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
