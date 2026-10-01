package thor.companion.strip

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StripDecoderTest {

    private class ArrayPixels(override val width: Int, override val height: Int, val data: IntArray) : Pixels {
        override fun rgb(x: Int, y: Int) = data[y * width + x]
    }

    /** The bottom 120 rows of a real Thor screenshot (1920x1080, addon v2, 2026-10-01). */
    @Test
    fun decodesRealThorScreenshot() {
        val img = ImageIO.read(javaClass.getResourceAsStream("/thor-strip-v2.png"))
        val px = ArrayPixels(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        val frame = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px)).frame
        assertTrue(frame.crcOk)
        assertEquals(15, frame.seq)
        assertEquals(2, frame.version)
        assertEquals(107, frame.length)
        assertEquals(9.0, frame.cellPx)
        assertEquals(
            "TC1|Xandra|5|409|1420|0.3187|0.6556|17/26|4604:7,159:5,6948:1,2589:17,11848:1,3270:1,3263:1,3274:1,247841:1",
            frame.payload,
        )
    }

    @Test
    fun decodesSyntheticFrameThroughThorLikeDisplay() {
        val payload = "TC1|Ünïcode|60|1234567|1453|0.5000|0.2500|3/98|" + (1..40).joinToString(",") { "${it * 1000}:$it" }
        val px = render(payload, seq = 7)
        val frame = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px)).frame
        assertTrue(frame.crcOk)
        assertEquals(payload, frame.payload)
        assertEquals(7, frame.seq)
    }

    /** Unshifted colours: data cells above the sync can look like another sync and must not move it. */
    @Test
    fun ignoresSyncLookalikesAboveTheStrip() {
        val names = "TN1|" + (1..20).joinToString("\n") { "${6948 + it},${it % 5},Item é $it" }
        val px = render(names, seq = 3, shiftColours = false)
        val frame = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px)).frame
        assertTrue(frame.crcOk)
        assertEquals(names, frame.payload)
    }

    @Test
    fun reportsBadCrc() {
        val px = render("TC1|x|1|0|0|0|0|0/0|", seq = 1, flipCell = StripDecoder.DATA + 2)
        val frame = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px)).frame
        assertFalse(frame.crcOk)
    }

    @Test
    fun failsWithoutStrip() {
        val px = ArrayPixels(400, 200, IntArray(400 * 200) { 0x203020 })
        assertIs<StripDecoder.Result.Failed>(StripDecoder.decode(px))
    }

    /**
     * Encodes [payload] the way Strip.lua does on a 1280x720 game frame, then shows it the
     * way the Thor does: 1.5x upscale to 1920 wide, colours pushed through a shifting
     * matrix, and the bottom rows cropped.
     */
    private fun render(payload: String, seq: Int, flipCell: Int = -1, shiftColours: Boolean = true): Pixels {
        val gw = 1280; val gh = 720; val cell = 6; val offset = 32
        val perRow = gw / cell
        val syms = IntArray(perRow * StripDecoder.ROWS)
        val raw = HashMap<Int, Int>()
        raw[0] = 0xFF00FF; raw[1] = 0x00FF00; raw[2] = 0xFF00FF; raw[3] = 0x00FF00
        for (p in 0 until 64) syms[4 + p] = p
        val bytes = payload.toByteArray(Charsets.UTF_8)
        syms[68] = seq; syms[69] = StripDecoder.VERSION
        syms[70] = bytes.size shr 6; syms[71] = bytes.size and 63
        var i = StripDecoder.DATA; var acc = 0; var nbits = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xFF); nbits += 8
            while (nbits >= 6) { nbits -= 6; syms[i++] = (acc shr nbits) and 63 }
            acc = acc and ((1 shl nbits) - 1)
        }
        if (nbits > 0) syms[i++] = (acc shl (6 - nbits)) and 63
        val crc = StripDecoder.crc16(bytes)
        syms[i] = crc shr 12; syms[i + 1] = (crc shr 6) and 63; syms[i + 2] = crc and 63
        if (flipCell >= 0) syms[flipCell] = syms[flipCell] xor 1

        fun level(v: Int) = v * 255 / 3
        val game = IntArray(gw * gh) { 0x1A2A12 }
        for (c in syms.indices) {
            val colour = raw[c] ?: run {
                val s = syms[c]
                (level(s / 16 % 4) shl 16) or (level(s / 4 % 4) shl 8) or level(s % 4)
            }
            val x0 = (c % perRow) * cell
            val yBottom = gh - 1 - offset - (c / perRow) * cell
            for (dy in 0 until cell) for (dx in 0 until cell) game[(yBottom - dy) * gw + x0 + dx] = colour
        }

        val sw = 1920; val sh = 1058
        val screen = IntArray(sw * sh) { idx ->
            val sx = idx % sw; val sy = idx / sw
            game[(sy * gh / 1080) * gw + sx * gw / sw].let { if (shiftColours) shift(it) else it }
        }
        return ArrayPixels(sw, sh, screen)
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
