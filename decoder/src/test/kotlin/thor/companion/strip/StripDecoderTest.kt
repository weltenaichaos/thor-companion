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
    fun reportsBadCrc() {
        val px = render(listOf("TS1|x|1|0|0|0|0".toByteArray()), seq = 1, flipCell = 40)[0]
        assertFalse(decodeOk(px).crcOk)
    }

    @Test
    fun failsWithoutSquare() {
        val px = ArrayPixels(400, 200, IntArray(400 * 200) { 0x203020 })
        assertIs<StripDecoder.Result.Failed>(StripDecoder.decode(px))
    }

    private fun decodeOk(px: Pixels) = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px)).frame

    /**
     * Draws each part the way Strip.lua does on a 1280x720 game frame with a busy dark
     * background, then shows it the way the Thor might: 1.5x bilinear upscale to
     * 1920x1080 and colours pushed through a shifting matrix. Returns the top rows.
     */
    private fun render(
        parts: List<ByteArray>, seq: Int, cell: Int = 3, shade: Int = 24, flipCell: Int = -1,
    ): List<Pixels> {
        val gw = 1280; val gh = 720; val cols = StripDecoder.COLS; val right = 0; val top = 22
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
            val left = gw - right - cols * cell
            for (c in levels.indices) {
                val v = levels[c] * shade
                val colour = (v shl 16) or (v shl 8) or v
                val x0 = left + (c % cols) * cell
                val y0 = top + (c / cols) * cell
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
}
