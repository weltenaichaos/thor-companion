package thor.companion

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Runs shell commands as root through "PServerBinder", the root service AYN ships on
 * the Thor for its own "Run Script as Root" setting. It needs no setup on the device,
 * but only while SELinux is permissive (the Thor's default).
 *
 * The service takes the command and "0" as a string array (transaction 0, no interface
 * token) and answers with the command's output as a byte array. It keeps only the
 * first line of output, so anything longer goes through a file.
 */
object RootShell {
    private const val TAG = "ThorCompanion"

    private val lock = Any()
    private var binder: IBinder? = null

    /** True when the service runs our commands as root. */
    fun available(): Boolean = exec("id")?.contains("uid=0") == true

    /** The command's first line of output, or null when the service can't be reached. */
    fun exec(cmd: String): String? = synchronized(lock) {
        val service = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeStringArray(arrayOf(cmd, "0"))
            if (!service.transact(0, data, reply, 0)) return null
            reply.createByteArray()?.let { String(it) } ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "root shell: $t")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun service(): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        return try {
            (Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "PServerBinder") as? IBinder)
                .also { binder = it }
        } catch (t: Throwable) {
            Log.w(TAG, "PServerBinder: $t")
            null
        }
    }

    /** Single-quoted for sh. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
