package thor.companion

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.Writer

/**
 * Sends one key (or one key with modifiers, like CTRL-F9) to a display. One call is
 * one key press. The keys are injected by [InputHelper], a root helper that stays
 * running, so a tap does not start new programs. On the Thor only one screen has key
 * focus at a time, so the helper gives the game's screen the focus before each key.
 */
object KeySender {
    private val names = mapOf(
        "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
        "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
    ) + (1..12).associate { "F$it" to KeyEvent.KEYCODE_F1 + it - 1 } +
        (0..9).associate { "NUMPAD$it" to KeyEvent.KEYCODE_NUMPAD_0 + it } +
        mapOf("ENTER" to KeyEvent.KEYCODE_ENTER, "ESCAPE" to KeyEvent.KEYCODE_ESCAPE, "V" to KeyEvent.KEYCODE_V)

    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: Writer? = null

    /** Sends [key] in WoW's notation (e.g. "CTRL-F9"); returns what went wrong, or null. */
    fun send(context: Context, displayId: Int, key: String): String? {
        val codes = key.split('-').map { names[it] ?: return "unknown key $it" }
        return command(context, "K $displayId ${codes.joinToString(" ")}\n")
    }

    /** Types [text] into the display's focused window (the game's open chat box); returns what went wrong, or null. */
    fun type(context: Context, displayId: Int, text: String): String? {
        if (text.isEmpty()) return null
        val hex = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return command(context, "T $displayId $hex\n")
    }

    @Synchronized
    private fun command(context: Context, line: String): String? {
        // A helper left over from an earlier start can have gone away: one reconnect.
        repeat(2) {
            if (writer == null && !connect(context)) return "the key helper did not start"
            try {
                writer!!.apply { write(line); flush() }
                val reply = reader!!.readLine() ?: throw java.io.IOException("closed")
                return if (reply == "OK") null else reply.removePrefix("OK ").trim()
            } catch (e: java.io.IOException) {
                close()
            }
        }
        return "the key helper stopped answering"
    }

    private fun connect(context: Context): Boolean {
        // One socket per app version, so an updated app never talks to an old helper.
        val name = "thor_companion_input_" + context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode
        if (tryConnect(name)) return true
        val apk = RootShell.quote(context.applicationInfo.sourceDir)
        val uid = android.os.Process.myUid()
        RootShell.exec(
            "CLASSPATH=$apk nohup app_process /system/bin thor.companion.InputHelper $uid $name >/dev/null 2>&1 &"
        ) ?: return false
        val until = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(150)
            if (tryConnect(name)) return true
        }
        return false
    }

    private fun tryConnect(name: String): Boolean = try {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
        socket = s
        reader = s.inputStream.bufferedReader()
        writer = s.outputStream.bufferedWriter()
        true
    } catch (e: java.io.IOException) {
        false
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null; reader = null; writer = null
    }
}
