package thor.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Item icons taken off the game's screen (Icons.lua), one PNG per item, kept across restarts. */
class IconStore(context: Context) {
    private val dir = File(context.filesDir, "icons").apply {
        // Once: icons taken before 0.22.2 may be bits of the world (the grid had gone before
        // the screenshot). The addon shows them all again by itself.
        val prefs = context.getSharedPreferences("icons", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("redone", false)) {
            deleteRecursively()
            prefs.edit().putBoolean("redone", true).apply()
        }
        mkdirs()
    }
    private val cache = HashMap<Int, Bitmap?>()

    private fun file(id: Int) = File(dir, "$id.png")

    operator fun get(id: Int): Bitmap? = synchronized(cache) {
        cache.getOrPut(id) { file(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }
    }

    fun has(id: Int): Boolean = synchronized(cache) { cache[id] != null || file(id).exists() }

    fun put(id: Int, icon: Bitmap) {
        runCatching { file(id).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        synchronized(cache) { cache[id] = icon }
    }
}
