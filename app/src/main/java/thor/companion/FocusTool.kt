package thor.companion

import android.os.IBinder

/**
 * Gives Android's key focus to the top task of a display, so injected keys reach
 * the game instead of the screen that was touched last. Runs as root in its own
 * app_process VM (hidden framework calls are allowed there), started by
 * [KeySender]: `app_process /system/bin thor.companion.FocusTool <displayId>`.
 * Prints "OK" or "ERR <reason>".
 */
object FocusTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val displayId = args.getOrNull(0)?.toIntOrNull() ?: run { println("ERR no display id"); return }
        println(
            try {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "activity_task") as IBinder
                val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)!!
                // Looked up by name: the exact signature differs between Android builds.
                val focus = atm.javaClass.methods.firstOrNull { it.name == "focusTopTask" && it.parameterTypes.size == 1 }
                    ?: return println("ERR focusTopTask not available")
                focus.invoke(atm, displayId)
                "OK"
            } catch (t: Throwable) {
                "ERR ${(t.cause ?: t).javaClass.simpleName}: ${(t.cause ?: t).message}"
            }
        )
    }
}
