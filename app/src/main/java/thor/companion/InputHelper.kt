package thor.companion

import android.net.LocalServerSocket
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Root helper that stays running so a tap does not have to start new programs
 * (starting `input` and a focus tool per tap took about a second on the Thor).
 *
 * Started by [KeySender] through the root service:
 * `app_process /system/bin thor.companion.InputHelper <app uid> <socket name>`.
 * It listens on an abstract local socket and only accepts connections from the
 * app's own uid, so no other app can use it to inject keys. One line per command:
 *   K <display> <keycode> [<keycode>...]   one press: modifiers down, last key down/up, modifiers up
 *   T <display> <text as UTF-8 hex>        types the text, as a keyboard would (for the chat box)
 * Each command answers "OK" or "ERR <reason>". It exits after 30 idle minutes.
 */
object InputHelper {
    private const val IDLE_EXIT_MS = 30 * 60 * 1000L

    @Volatile private var lastUse = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val uid = args.getOrNull(0)?.toIntOrNull() ?: return println("ERR no uid")
        val name = args.getOrNull(1) ?: return println("ERR no socket name")
        val server = try {
            LocalServerSocket(name)
        } catch (e: Exception) {
            return println("ERR ${e.message}") // already running
        }
        lastUse = SystemClock.uptimeMillis()
        Thread {
            while (true) {
                Thread.sleep(60_000)
                if (SystemClock.uptimeMillis() - lastUse > IDLE_EXIT_MS) System.exit(0)
            }
        }.apply { isDaemon = true }.start()

        while (true) {
            val socket = server.accept()
            if (socket.peerCredentials.uid != uid) {
                socket.close()
                continue
            }
            Thread {
                socket.use { s ->
                    val out = s.outputStream.bufferedWriter()
                    s.inputStream.bufferedReader().forEachLine { line ->
                        lastUse = SystemClock.uptimeMillis()
                        val reply = try { handle(line) } catch (t: Throwable) { "ERR ${t.javaClass.simpleName}: ${t.message}" }
                        out.write(reply + "\n")
                        out.flush()
                    }
                }
            }.start()
        }
    }

    private fun handle(line: String): String {
        val parts = line.trim().split(' ')
        if (parts.firstOrNull() == "T" && parts.size == 3) {
            val display = parts[1].toIntOrNull() ?: return "ERR bad display"
            val text = String(parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
            val focus = focus(display)
            type(display, text)
            return if (focus == null) "OK" else "OK (focus: $focus)"
        }
        if (parts.firstOrNull() != "K" || parts.size < 3) return "ERR bad command"
        val display = parts[1].toIntOrNull() ?: return "ERR bad display"
        val codes = parts.drop(2).map { it.toIntOrNull() ?: return "ERR bad key code" }
        val focus = focus(display)
        press(display, codes)
        return if (focus == null) "OK" else "OK (focus: $focus)"
    }

    /** Gives key focus to the display's top task; null when that worked. */
    private fun focus(display: Int): String? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as IBinder
        val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        // Looked up by name: the exact signature differs between Android builds.
        val m = atm.javaClass.methods.firstOrNull { it.name == "focusTopTask" && it.parameterTypes.size == 1 }
        if (m == null) "focusTopTask not available" else { m.invoke(atm, display); null }
    } catch (t: Throwable) {
        "${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message}"
    }

    private val inputManager: Any by lazy {
        Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
    }
    private val inject by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayId by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    /** Like a real keyboard: modifiers down, the key held briefly, then everything up in reverse. */
    private fun press(display: Int, codes: List<Int>) {
        val down = SystemClock.uptimeMillis()
        var meta = 0
        val mods = codes.dropLast(1)
        for (m in mods) {
            meta = meta or metaFor(m)
            send(display, down, KeyEvent.ACTION_DOWN, m, meta)
            Thread.sleep(15)
        }
        send(display, down, KeyEvent.ACTION_DOWN, codes.last(), meta)
        Thread.sleep(40)
        send(display, down, KeyEvent.ACTION_UP, codes.last(), meta)
        for (m in mods.reversed()) {
            Thread.sleep(15)
            meta = meta and metaFor(m).inv()
            send(display, down, KeyEvent.ACTION_UP, m, meta)
        }
    }

    private val keyMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /**
     * Types [text] one character at a time with the key presses a keyboard would make
     * (shift included). A character the keyboard has no key for goes as text on its own.
     */
    private fun type(display: Int, text: String) {
        for (c in text) {
            val events = keyMap.getEvents(charArrayOf(c))
            if (events == null) {
                val ev = KeyEvent(SystemClock.uptimeMillis(), c.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
                setDisplayId.invoke(ev, display)
                inject.invoke(inputManager, ev, 0)
                Thread.sleep(12)
                continue
            }
            for (e in events) {
                val ev = KeyEvent(
                    e.downTime, SystemClock.uptimeMillis(), e.action, e.keyCode, 0, e.metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
                )
                setDisplayId.invoke(ev, display)
                inject.invoke(inputManager, ev, 0)
                Thread.sleep(6)
            }
        }
    }

    private fun metaFor(code: Int) = when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        else -> 0
    }

    private fun send(display: Int, downTime: Long, action: Int, code: Int, meta: Int) {
        val ev = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        setDisplayId.invoke(ev, display)
        inject.invoke(inputManager, ev, 0) // 0 = don't wait; the sleeps above keep the order
    }
}
