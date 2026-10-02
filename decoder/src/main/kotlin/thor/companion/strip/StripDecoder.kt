package thor.companion.strip

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixels of a captured screen (or just its top part), as 0xRRGGBB. */
interface Pixels {
    val width: Int
    val height: Int
    fun rgb(x: Int, y: Int): Int
}

/** One decoded square: one part of a message. [payload] is only meaningful when [crcOk]. */
class StripFrame(
    val version: Int,
    /** Message number; all parts of one message share it. */
    val seq: Int,
    val part: Int,
    val parts: Int,
    val crcOk: Boolean,
    val payload: ByteArray,
    /** Smallest distance between two calibration shades: how much margin decoding had. */
    val shadeMinDistance: Double,
    val cellPx: Double,
    val row: Int,
)

/**
 * Reads the square of dark grey cells the ThorCompanion addon draws in the top-right
 * corner of the game (see addon/ThorCompanion/Strip.lua for the layout).
 *
 * The Thor upscales the game and shifts its colours, so nothing here relies on exact
 * values: the sync row is found by its evenly spaced brightness steps, the cell size
 * is measured from it, and every cell is matched to the nearest of the four
 * calibration shades the same square carries.
 */
object StripDecoder {
    const val VERSION = 4
    const val COLS = 20
    private const val CALIB = 8
    private const val CAPACITY = ((COLS - 1) * COLS - CALIB) / 4

    /** How far down from the top edge the square is searched for. */
    const val SCAN_ROWS = 400

    /** Smallest brightness step between neighbouring sync cells that counts. */
    private const val EDGE = 6.0

    sealed class Result {
        class Ok(val frame: StripFrame) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Decodes the square, or the same cells unrolled into one line. [near] is the row
     * where it was last found (StripFrame.row): only the rows around it are searched
     * first, which is much quicker.
     */
    fun decode(px: Pixels, near: Int = -1): Result {
        val found = (if (near >= 0) findSquare(px, near - 16, near + 16) else null)
            ?: findSquare(px, 0, SCAN_ROWS)
        val (x0, y0, cell) = found ?: return Result.Failed("data square not found")
        // The line's cells go on along the sync row; the square's wrap after COLS.
        val line = decodeAt(px, x0, y0, cell, COLS * COLS)
        if (line is Result.Ok && line.frame.crcOk) return line
        val square = decodeAt(px, x0, y0, cell, COLS)
        return if (square is Result.Ok && !square.frame.crcOk && line is Result.Ok) line else square
    }

    private fun decodeAt(px: Pixels, x0: Double, y0: Int, cell: Double, perRow: Int): Result {
        fun colour(i: Int): Int {
            val row = (COLS + i) / perRow
            val col = (COLS + i) % perRow
            val x = (x0 + (col + 0.5) * cell).toInt().coerceIn(0, px.width - 1)
            val y = (y0 + row * cell).roundToInt().coerceIn(0, px.height - 1)
            return px.rgb(x, y)
        }

        val shades = IntArray(4) { s -> average(colour(s), colour(s + 4)) }
        fun sym(i: Int): Int {
            val c = colour(i)
            var best = 0
            for (s in 1 until 4) if (dist2(c, shades[s]) < dist2(c, shades[best])) best = s
            return best
        }
        fun byte(n: Int): Int {
            val i = CALIB + n * 4
            return (sym(i) shl 6) or (sym(i + 1) shl 4) or (sym(i + 2) shl 2) or sym(i + 3)
        }

        val head = ByteArray(4) { byte(it).toByte() }
        val length = head[3].toInt() and 0xFF
        if (4 + length + 2 > CAPACITY) return Result.Failed("bad length $length")
        val body = ByteArray(length) { byte(4 + it).toByte() }
        val crc = (byte(4 + length) shl 8) or byte(5 + length)

        var spread = Int.MAX_VALUE
        for (p in 0 until 4) for (q in p + 1 until 4) spread = minOf(spread, dist2(shades[p], shades[q]))
        val partByte = head[2].toInt() and 0xFF
        return Result.Ok(
            StripFrame(
                version = head[0].toInt() and 0xFF,
                seq = head[1].toInt() and 0xFF,
                part = partByte shr 4,
                parts = (partByte and 15) + 1,
                crcOk = crc == crc16(head + body),
                payload = body,
                shadeMinDistance = sqrt(spread.toDouble()),
                cellPx = cell,
                row = y0,
            )
        )
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private fun dist2(a: Int, b: Int): Int {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    private fun average(a: Int, b: Int): Int {
        val r = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF)) / 2
        val g = ((a shr 8 and 0xFF) + (b shr 8 and 0xFF)) / 2
        val bl = ((a and 0xFF) + (b and 0xFF)) / 2
        return (r shl 16) or (g shl 8) or bl
    }

    private fun lum(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3.0

    /** (x of the first cell, cell width) if the sync row crosses row [y]. */
    private fun syncAt(px: Pixels, y: Int): Pair<Double, Double>? {
        val w = px.width
        val l = DoubleArray(w) { lum(px.rgb(it, y)) }
        // Edges: where brightness steps up or down. A step blended over two pixels
        // still gives one peak of the difference across three pixels.
        val d = DoubleArray(w)
        for (x in 1 until w - 1) d[x] = l[x + 1] - l[x - 1]
        val edgeX = ArrayList<Int>()
        val edgeUp = ArrayList<Boolean>()
        for (x in 1 until w - 1) {
            if (abs(d[x]) >= EDGE && abs(d[x]) >= abs(d[x - 1]) && abs(d[x]) > abs(d[x + 1])) {
                edgeX += x
                edgeUp += d[x] > 0
            }
        }
        // The COLS - 1 edges between sync cells: evenly spaced, alternating, the first going dark.
        val need = COLS - 1
        for (i in 0..edgeX.size - need) {
            if (edgeUp[i]) continue
            if ((1 until need).any { edgeUp[i + it] == edgeUp[i + it - 1] }) continue
            val pitch = (edgeX[i + need - 1] - edgeX[i]) / (need - 1).toDouble()
            if (pitch < 2.5) continue
            val slack = maxOf(1.5, pitch / 4)
            if ((0 until need).any { abs(edgeX[i + it] - edgeX[i] - it * pitch) > slack }) continue
            val x0 = edgeX[i] + 0.5 - pitch
            if (x0 < 0) continue
            // Light cells alike, dark cells alike, and clearly apart.
            val light = (0 until COLS step 2).map { l[(x0 + (it + 0.5) * pitch).toInt()] }
            val dark = (1 until COLS step 2).map { l[(x0 + (it + 0.5) * pitch).toInt()] }
            if (light.min() - dark.max() >= EDGE && light.max() - light.min() < EDGE && dark.max() - dark.min() < EDGE) {
                return x0 to pitch
            }
        }
        return null
    }

    /** (x of the first cell, middle y of the sync row, cell size) or null. */
    private fun findSquare(px: Pixels, from: Int, to: Int): Triple<Double, Int, Double>? {
        var first = -1
        var firstSync: Pair<Double, Double>? = null
        for (y in maxOf(0, from) until minOf(px.height, to)) {
            val found = syncAt(px, y)
            if (found != null && first < 0) {
                first = y
                firstSync = found
            } else if (first >= 0 && (found == null || abs(found.first - firstSync!!.first) > 1.5)) {
                val mid = (first + y - 1) / 2 // edge rows are blended; use the middle
                val (x0, cell) = syncAt(px, mid) ?: firstSync!!
                return Triple(x0, mid, cell)
            }
        }
        return null
    }
}

/** Puts messages split over several squares back together. */
class PartAssembler {
    private var seq = -1
    private var parts: Array<ByteArray?> = emptyArray()
    private var done = -1

    /** True while part of a message has been seen but not all of it. */
    val waiting: Boolean get() = done != seq && parts.size > 1

    /** The whole message once [frame] completes it (once per message), else null. */
    fun add(frame: StripFrame): String? {
        if (!frame.crcOk || frame.part >= frame.parts) return null
        if (frame.seq != seq || parts.size != frame.parts) {
            seq = frame.seq
            parts = arrayOfNulls(frame.parts)
        }
        parts[frame.part] = frame.payload
        if (done == seq || parts.any { it == null }) return null
        done = seq
        return String(parts.fold(ByteArray(0)) { acc, p -> acc + p!! }, Charsets.UTF_8)
    }
}
