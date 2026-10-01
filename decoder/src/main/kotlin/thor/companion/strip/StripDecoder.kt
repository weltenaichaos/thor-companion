package thor.companion.strip

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixels of a captured screen (or just its bottom part), as 0xRRGGBB. */
interface Pixels {
    val width: Int
    val height: Int
    fun rgb(x: Int, y: Int): Int
}

/** One decoded frame of the strip. [payload] is only meaningful when [crcOk]. */
data class StripFrame(
    val seq: Int,
    val version: Int,
    val length: Int,
    val crcOk: Boolean,
    val payload: String,
    /** Smallest distance between two calibration colours: how much margin decoding had. */
    val paletteMinDistance: Double,
    val cellPx: Double,
    val row: Int,
)

/**
 * Reads the data strip the ThorCompanion addon draws near the bottom of the game
 * (see addon/ThorCompanion/Strip.lua for the layout).
 *
 * The Thor upscales the game and shifts its colours a lot, so nothing here relies
 * on exact values: the sync cells are found by colour shape, the cell size is
 * measured from them, and every data cell is matched to the nearest of the 64
 * calibration cells that the same frame carries.
 */
object StripDecoder {
    const val ROWS = 3
    const val DATA = 72
    const val VERSION = 2

    /** How far up from the bottom edge the strip is searched for. */
    private const val SCAN_ROWS = 300

    sealed class Result {
        data class Ok(val frame: StripFrame) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun decode(px: Pixels): Result {
        val (x0, y0, cell) = findStrip(px) ?: return Result.Failed("strip not found")
        val perRow = ((px.width - x0) / cell + 0.01).toInt()

        fun colour(i: Int): Int {
            val row = i / perRow
            val col = i % perRow
            val x = (x0 + (col + 0.5) * cell).toInt().coerceIn(0, px.width - 1)
            val y = (y0 - row * cell).roundToInt().coerceIn(0, px.height - 1)
            return px.rgb(x, y)
        }

        val palette = IntArray(64) { colour(4 + it) }
        fun sym(i: Int): Int {
            val c = colour(i)
            var best = 0
            var bestD = Int.MAX_VALUE
            for (p in 0 until 64) {
                val d = dist2(c, palette[p])
                if (d < bestD) { bestD = d; best = p }
            }
            return best
        }

        val seq = sym(68)
        val version = sym(69)
        val length = (sym(70) shl 6) or sym(71)
        val nsyms = (length * 8 + 5) / 6
        if (DATA + nsyms + 3 > perRow * ROWS) return Result.Failed("bad length $length")

        val out = ByteArray(length)
        var n = 0
        var bits = 0
        var nbits = 0
        for (i in DATA until DATA + nsyms) {
            bits = (bits shl 6) or sym(i)
            nbits += 6
            while (nbits >= 8 && n < length) {
                nbits -= 8
                out[n++] = ((bits shr nbits) and 0xFF).toByte()
            }
            bits = bits and ((1 shl nbits) - 1)
        }
        val end = DATA + nsyms
        val crc = (sym(end) shl 12) or (sym(end + 1) shl 6) or sym(end + 2)

        var spread = Int.MAX_VALUE
        for (p in 0 until 64) for (q in p + 1 until 64) spread = minOf(spread, dist2(palette[p], palette[q]))

        return Result.Ok(
            StripFrame(
                seq = seq,
                version = version,
                length = length,
                crcOk = crc == crc16(out),
                payload = String(out, Charsets.UTF_8),
                paletteMinDistance = sqrt(spread.toDouble()),
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

    // Loose classes: the display shifts colours, so test shape, not exact values.
    private fun magenta(c: Int): Boolean {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return r > 150 && b > 150 && g < minOf(r, b) - 80
    }

    private fun green(c: Int): Boolean {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return g > 150 && r < g - 60 && b < g - 60
    }

    /** (x of the first cell, cell width) if the sync cells start on row [y]. */
    private fun syncAt(px: Pixels, y: Int): Pair<Int, Double>? {
        var x = 0
        while (x < 40 && x < px.width && !magenta(px.rgb(x, y))) x++
        if (x == 40 || x == px.width) return null
        val starts = IntArray(4)
        starts[0] = x
        var found = 1
        var wantGreen = true
        for (xx in x until minOf(px.width, x + 200)) {
            val c = px.rgb(xx, y)
            if (if (wantGreen) green(c) else magenta(c)) {
                starts[found++] = xx
                wantGreen = !wantGreen
                if (found == 4) {
                    val cell = (starts[3] - starts[0]) / 3.0
                    // Real sync cells are evenly spaced; data cells that happen to be
                    // magenta and green rarely are.
                    val slack = maxOf(1.5, cell / 4)
                    val even = (1..3).all { Math.abs(starts[it] - starts[it - 1] - cell) <= slack }
                    return if (cell >= 2 && even) starts[0] to cell else null
                }
            }
        }
        return null
    }

    private fun sameSync(a: Pair<Int, Double>, b: Pair<Int, Double>) =
        Math.abs(a.first - b.first) <= 1 && Math.abs(a.second - b.second) <= 1.0

    /** (x of first cell, middle y of the bottom cell row, cell size) or null. */
    private fun findStrip(px: Pixels): Triple<Int, Int, Double>? {
        var first = -1
        var firstSync: Pair<Int, Double>? = null
        var y = px.height - 1
        val stop = maxOf(px.height - SCAN_ROWS, -1)
        while (y > stop) {
            val found = syncAt(px, y)
            if (found != null && first < 0) {
                first = y
                firstSync = found
            } else if (first >= 0 && (found == null || !sameSync(found, firstSync!!))) {
                // The row above the sync cells ends the block, even when its own
                // cells look like a sync at another place or size.
                val mid = (first + y + 1) / 2 // edge rows are blended; use the middle
                val (x0, cell) = syncAt(px, mid) ?: return null
                return Triple(x0, mid, cell)
            }
            y--
        }
        return null
    }
}
