package thor.companion.strip

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StripDecoderTest {

    private class ArrayPixels(override val width: Int, override val height: Int, val data: IntArray) : Pixels {
        override fun rgb(x: Int, y: Int) = data[y * width + x]
    }

    /** The same, drawn as one line along the top edge (/thor shape line). */
    @Test
    fun decodesLineDrawnByTheAddon() {
        val img = javax.imageio.ImageIO.read(javaClass.getResourceAsStream("/addon-line-v4.png"))
        val px = ArrayPixels(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        val frame = decodeOk(px)
        assertTrue(frame.crcOk)
        assertEquals("TS1|Xandra|5|40912|1420|0.3187|0.6556", String(frame.payload))
    }

    /** Drawn by the real Strip.lua (in a mock game), upscaled and colour-shifted like on the Thor. */
    @Test
    fun decodesSquareDrawnByTheAddon() {
        val img = javax.imageio.ImageIO.read(javaClass.getResourceAsStream("/addon-square-v4.png"))
        val px = ArrayPixels(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        val frame = decodeOk(px)
        assertTrue(frame.crcOk)
        assertEquals("TS1|Xandra|5|40912|1420|0.3187|0.6556", String(frame.payload))
        assertEquals(1, frame.parts)
    }

    @Test
    fun decodesSquareThroughThorLikeDisplay() {
        val payload = "TS1|Ünïcode|60|1234567|1453|0.5000|0.2500"
        val frame = decodeOk(render(listOf(payload.toByteArray()), seq = 7)[0])
        assertTrue(frame.crcOk)
        assertEquals(payload, String(frame.payload))
        assertEquals(7, frame.seq)
        assertEquals(StripDecoder.VERSION, frame.version)
        assertEquals(4.5, frame.cellPx, 0.05)
        // The square sits at the right edge, 22 game pixels (33 screen pixels) down.
        assertEquals(1920.0, frame.x + StripDecoder.COLS * frame.cellPx, 2.0)
        assertEquals(33.0, frame.row - frame.cellPx / 2, 2.0)
    }

    @Test
    fun decodesTheSmallestCellsAndDarkestShades() {
        val payload = "TS1|Xandra|5|40912|1420|0.3187|0.6556"
        val frame = decodeOk(render(listOf(payload.toByteArray()), seq = 1, cell = 2, shade = 12)[0])
        assertTrue(frame.crcOk)
        assertEquals(payload, String(frame.payload))
    }

    @Test
    fun putsPartsBackTogether() {
        val message = "TB1|3/98|" + (1..60).joinToString(",") { "${it * 1000}:$it:$it" }
        val chunks = message.toByteArray().toList().chunked(87).map { it.toByteArray() }
        val squares = render(chunks, seq = 200)
        val assembler = PartAssembler()
        // Seen in any order; the message comes out when the last missing part arrives.
        val got = squares.indices.reversed().map { assembler.add(decodeOk(squares[it])) }
        assertEquals(List(squares.size - 1) { null } + message, got)
        assertNull(assembler.add(decodeOk(squares[0])), "a message is handed over once")
    }

    @Test
    fun usesTheLastPositionAndFallsBackWhenItMoved() {
        val px = render(listOf("TS1|x|1|0|0|0|0".toByteArray()), seq = 3)[0]
        val row = decodeOk(px).row
        assertEquals(row, assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px, near = row)).frame.row)
        assertTrue(assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px, near = 300)).frame.crcOk)
    }

    @Test
    fun decodesTheLineAlongTheTopEdge() {
        val payload = "TB1|3/98|" + (1..7).joinToString(",") { "${it * 1000}:$it:$it" }
        val frame = decodeOk(render(listOf(payload.toByteArray()), seq = 9, line = true)[0])
        assertTrue(frame.crcOk)
        assertEquals(payload, String(frame.payload))
        assertTrue(frame.row < 5, "the line sits at the very top")
    }

    @Test
    fun reportsBadCrc() {
        val px = render(listOf("TS1|x|1|0|0|0|0".toByteArray()), seq = 1, flipCell = 40)[0]
        assertFalse(decodeOk(px).crcOk)
    }

    @Test
    fun failsWithoutSquare() {
        val px = ArrayPixels(400, 200, IntArray(400 * 200) { 0x203020 })
        assertIs<StripDecoder.Result.Failed>(StripDecoder.decode(px))
    }

    /** Drawn by the real Strip.lua (bar.lua mock, top-right 400x24 game pixels), then shown like on the Thor. */
    @Test
    fun decodesTheColourBarDrawnByTheAddon() {
        val img = javax.imageio.ImageIO.read(javaClass.getResourceAsStream("/addon-bar-v5.png"))
        val game = img.getRGB(0, 0, img.width, img.height, null, 0, img.width).map { it and 0xFFFFFF }.toIntArray()
        val sw = img.width * 3 / 2; val sh = img.height * 3 / 2
        val px = ArrayPixels(sw, sh, IntArray(sw * sh) { idx -> thorColour(bilinear(game, img.width, img.height, (idx % sw) / 1.5, (idx / sw) / 1.5)) })
        val frame = decodeOk(px)
        assertTrue(frame.crcOk)
        assertEquals(StripDecoder.BAR_VERSION, frame.version)
        assertEquals("TS1|Xandra|5|40912|1420|0.3187|0.6556|bar test with a longer payload 0123456789 ÄÖÜ", String(frame.payload))
    }

    /** Jesse's screenshot from the Thor (2026-10-13, top right 520x40): the bar as the device really shows it. */
    @Test
    fun decodesTheColourBarFromARealThorScreenshot() {
        val img = javax.imageio.ImageIO.read(javaClass.getResourceAsStream("/thor-bar-real.png"))
        val px = ArrayPixels(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width).map { it and 0xFFFFFF }.toIntArray())
        val frame = decodeOk(px)
        assertTrue(frame.crcOk)
        assertEquals(StripDecoder.BAR_VERSION, frame.version)
        assertEquals("6480:1:62,5075:9:63,3308:1:64,248683:1:65,5085:1:66", String(frame.payload))
    }

    @Test
    fun decodesTheColourBarThroughThorLikeDisplay() {
        val payload = ("TB1|3/98|" + (1..40).joinToString(",") { "${it * 1000}:$it:$it" }).take(153)
        val frame = decodeOk(renderBar(payload.toByteArray(), seq = 42))
        assertTrue(frame.crcOk)
        assertEquals(StripDecoder.BAR_VERSION, frame.version)
        assertEquals(payload, String(frame.payload))
        assertEquals(42, frame.seq)
        assertTrue(frame.row < 6, "the bar sits at the very top")
    }

    @Test
    fun decodesTheColourBarWithBigCellsAndUnicode() {
        val payload = "TS1|Ünïcode|60|1234567|1453|0.5000|0.2500"
        val frame = decodeOk(renderBar(payload.toByteArray(), seq = 3, cell = 4))
        assertTrue(frame.crcOk)
        assertEquals(payload, String(frame.payload))
    }

    /** Draws the bar the way Strip.lua does (top right, top edge), then through the Thor-like display. */
    private fun renderBar(body: ByteArray, seq: Int, cell: Int = 3): Pixels {
        val gw = 1280; val gh = 720; val cols = StripDecoder.BAR_COLS; val rows = StripDecoder.BAR_ROWS
        val rnd = Random(seq)
        val game = IntArray(gw * gh) {
            val v = rnd.nextInt(10, 120)
            (v shl 16) or ((v + rnd.nextInt(0, 40)) shl 8) or (v / 2)
        }
        val head = byteArrayOf(StripDecoder.BAR_VERSION.toByte(), seq.toByte(), 0, body.size.toByte())
        val crc = StripDecoder.crc16(head + body)
        val bytes = head + body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        val level = intArrayOf(0, 51, 102, 153)
        fun grey(l: Int) = (level[l] shl 16) or (level[l] shl 8) or level[l]
        fun colour(v: Int) = (level[v shr 4 and 3] shl 16) or (level[v shr 2 and 3] shl 8) or level[v and 3]
        val cells = IntArray(cols * rows) { colour(0) }
        for (c in 0 until StripDecoder.COLS) cells[c] = grey(if (c % 2 == 0) 3 else 0)
        var i = StripDecoder.COLS
        for (c in 0..3) cells[i++] = grey(c)
        cells[i++] = colour(48); cells[i++] = colour(12); cells[i++] = colour(3); cells[i++] = grey(0)
        for (p in bytes.indices step 3) {
            val v = ((bytes[p].toInt() and 0xFF) shl 16) or ((bytes.getOrElse(p + 1) { 0 }.toInt() and 0xFF) shl 8) or
                (bytes.getOrElse(p + 2) { 0 }.toInt() and 0xFF)
            for (s in 3 downTo 0) cells[i++] = colour((v shr (s * 6)) and 63)
        }
        val left = gw - cols * cell
        for (c in cells.indices) {
            val x0 = left + (c % cols) * cell
            val y0 = (c / cols) * cell
            for (dy in 0 until cell) for (dx in 0 until cell) game[(y0 + dy) * gw + x0 + dx] = cells[c]
        }
        val sw = 1920; val sh = 400
        return ArrayPixels(sw, sh, IntArray(sw * sh) { idx -> thorColour(bilinear(game, gw, gh, (idx % sw) / 1.5, (idx / sw) / 1.5)) })
    }

    private fun decodeOk(px: Pixels) = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px)).frame

    /**
     * Draws each part the way Strip.lua does on a 1280x720 game frame with a busy dark
     * background, then shows it the way the Thor might: 1.5x bilinear upscale to
     * 1920x1080 and colours pushed through a shifting matrix. Returns the top rows.
     */
    private fun render(
        parts: List<ByteArray>, seq: Int, cell: Int = 3, shade: Int = 24, flipCell: Int = -1, line: Boolean = false,
    ): List<Pixels> {
        val gw = 1280; val gh = 720; val cols = StripDecoder.COLS; val right = 0
        val perRow = if (line) cols * cols else cols
        val top = if (line) 0 else 22
        val rnd = Random(seq)
        val background = IntArray(gw * gh) {
            val v = rnd.nextInt(10, 70)
            (v shl 16) or ((v + rnd.nextInt(0, 20)) shl 8) or (v / 2)
        }
        return parts.mapIndexed { index, body ->
            val head = byteArrayOf(StripDecoder.VERSION.toByte(), seq.toByte(), (index * 16 + parts.size - 1).toByte(), body.size.toByte())
            val crc = StripDecoder.crc16(head + body)
            val bytes = head + body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
            val levels = IntArray(cols * cols)
            for (c in 0 until cols) levels[c] = if (c % 2 == 0) 3 else 0
            var i = cols
            for (c in 0 until 8) levels[i++] = c % 4
            for (b in bytes) for (s in 3 downTo 0) levels[i++] = (b.toInt() shr (s * 2)) and 3
            if (flipCell >= 0) levels[cols + flipCell] = levels[cols + flipCell] xor 1

            val game = background.copyOf()
            val left = gw - right - perRow * cell
            for (c in levels.indices) {
                val v = levels[c] * shade
                val colour = (v shl 16) or (v shl 8) or v
                val x0 = left + (c % perRow) * cell
                val y0 = top + (c / perRow) * cell
                for (dy in 0 until cell) for (dx in 0 until cell) game[(y0 + dy) * gw + x0 + dx] = colour
            }
            val sw = 1920; val sh = 400
            val screen = IntArray(sw * sh) { idx -> shift(bilinear(game, gw, gh, (idx % sw) / 1.5, (idx / sw) / 1.5)) }
            ArrayPixels(sw, sh, screen)
        }
    }

    private fun bilinear(img: IntArray, w: Int, h: Int, fx: Double, fy: Double): Int {
        val x = (fx - 0.25).coerceIn(0.0, w - 1.0); val y = (fy - 0.25).coerceIn(0.0, h - 1.0)
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val ax = x - x0; val ay = y - y0
        var out = 0
        for (sh in intArrayOf(16, 8, 0)) {
            fun ch(px: Int, py: Int) = (img[py * w + px] shr sh) and 0xFF
            val upper = ch(x0, y0) * (1 - ax) + ch(x1, y0) * ax
            val lower = ch(x0, y1) * (1 - ax) + ch(x1, y1) * ax
            out = out or ((upper * (1 - ay) + lower * ay).toInt() shl sh)
        }
        return out
    }

    /** Roughly what the Thor does to colours: pure green shows as about 117,251,76. */
    private fun shift(c: Int): Int {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        val nr = (0.85 * r + 0.45 * g + 0.05 * b).toInt().coerceIn(0, 255)
        val ng = (0.20 * r + 0.98 * g + 0.04 * b).toInt().coerceIn(0, 255)
        val nb = (0.03 * r + 0.30 * g + 0.95 * b).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    /**
     * The Thor's colour correction as measured on a real screenshot (2026-10-13): the
     * colours mix in linear light, e.g. red at 0.6 shows as 8c1a11, green as 43972a,
     * blue as 000093, while the grey at 0.6 stays 999999.
     */
    private fun thorColour(c: Int): Int {
        fun lin(v: Int): Double { val f = v / 255.0; return if (f <= 0.04045) f / 12.92 else Math.pow((f + 0.055) / 1.055, 2.4) }
        fun enc(l: Double): Int { val v = l.coerceIn(0.0, 1.0); return (255 * (if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1 / 2.4) - 0.055) + 0.5).toInt() }
        val r = lin(c shr 16 and 0xFF); val g = lin(c shr 8 and 0xFF); val b = lin(c and 0xFF)
        val nr = enc(0.823 * r + 0.177 * g)
        val ng = enc(0.032 * r + 0.968 * g)
        val nb = enc(0.018 * r + 0.073 * g + 0.909 * b)
        return (nr shl 16) or (ng shl 8) or nb
    }
}
