package thor.companion

import android.content.Context
import thor.companion.strip.Pixels
import java.io.File
import java.io.RandomAccessFile

/**
 * Captures the screen the game runs on with `screencap` (as root, raw RGBA, no PNG
 * encoding) and keeps only the bottom rows, where the addon's strip is.
 */
class TopScreen(private val context: Context) {

    /** Physical display id for `screencap -d`, or null for the default (top) display. */
    @Volatile var displayId: String? = null

    private val file = File(context.cacheDir, "top.raw")

    /** The bottom [rows] rows of the screen, or null when the capture failed. */
    fun capture(rows: Int = 300): Pixels? {
        val path = RootShell.quote(file.absolutePath)
        val uid = android.os.Process.myUid()
        val target = displayId?.takeIf { it.all(Char::isDigit) }?.let { "-d $it " } ?: ""
        RootShell.exec("screencap $target> $path; chown $uid:$uid $path") ?: return null
        return try {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(12)
                f.readFully(head)
                val w = le(head, 0)
                val h = le(head, 4)
                if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
                // The header is 12 or 16 bytes depending on the Android version; 4 bytes per pixel.
                val header = f.length() - w.toLong() * h * 4
                if (header !in 12..16) return null
                val n = minOf(rows, h)
                val buf = ByteArray(w * n * 4)
                f.seek(header + (h - n).toLong() * w * 4)
                f.readFully(buf)
                RgbaPixels(w, n, buf)
            }
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    /** Physical display ids SurfaceFlinger knows, for picking the game's screen. */
    fun listDisplays(): List<String> {
        val out = File(context.cacheDir, "displays.txt")
        val path = RootShell.quote(out.absolutePath)
        val uid = android.os.Process.myUid()
        RootShell.exec("dumpsys SurfaceFlinger --display-id > $path; chown $uid:$uid $path") ?: return emptyList()
        return try {
            Regex("""Display (\d+)""").findAll(out.readText()).map { it.groupValues[1] }.distinct().toList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            out.delete()
        }
    }

    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private class RgbaPixels(override val width: Int, override val height: Int, private val data: ByteArray) : Pixels {
        override fun rgb(x: Int, y: Int): Int {
            val o = (y * width + x) * 4
            return ((data[o].toInt() and 0xFF) shl 16) or ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o + 2].toInt() and 0xFF)
        }
    }
}
