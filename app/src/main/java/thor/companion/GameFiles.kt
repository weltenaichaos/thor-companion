package thor.companion

import android.os.FileObserver
import java.io.File
import java.io.Writer

/**
 * Runs in [InputHelper] (as root): follows the game through the files it touches,
 * with the kernel's file events instead of looking on a timer, and tells the app.
 * What WoW does (seen on the Thor):
 *  - it opens its Logs/ files the moment it starts;
 *  - entering the world as a character, it opens that character's own folder
 *    (WTF/Account/<account>/<realm>/<Name-Realm>/config-cache.wtf), then reads the
 *    addons' saved settings there, ours (ThorCompanion.lua) among them;
 *  - logging out, quitting or /reload, it writes those saved settings (each through a
 *    temporary file renamed into place).
 * Lines sent to the app:
 *   F <folder>        the game folder being watched (none found: "F -", and nothing else follows)
 *   R                 the game was already running when the watching began (state unknown)
 *   S                 the game started
 *   L <Name-Realm>    entering the world as that character (the loading screen is up)
 *   A <Name-Realm>    the addons are loading (the loading screen ends soon)
 *   O <Name-Realm>    the character's saved settings were written (logout, quit or /reload)
 *   X                 the game is closed
 * Only watches; changes nothing.
 */
class GameFiles(private val out: Writer, hint: String?) {
    private val observers = mutableListOf<FileObserver>()
    private val folder: File? = find(hint)
    @Volatile private var running = false
    @Volatile private var stopped = false
    private var character: String? = null
    private var inWorld = false
    private var addons = false
    /** Whether the game's process was found since it started: only then does its going away count. */
    private var processSeen = false

    init {
        say("F ${folder?.path ?: "-"}")
        if (folder != null) {
            watch()
            if (process() != null) { running = true; processSeen = true; say("R") }
            Thread { checkProcess() }.apply { isDaemon = true }.start()
        }
    }

    fun stop() {
        stopped = true
        synchronized(observers) { observers.forEach { it.stopWatching() }; observers.clear() }
    }

    private fun say(line: String) = synchronized(out) {
        runCatching { out.write(line + "\n"); out.flush() }.onFailure { stop() }
    }

    /** One folder's events, with the folder known (a FileObserver for several folders doesn't say which). */
    private inner class Watch(val dir: File, mask: Int, val on: (Int, String) -> Unit) : FileObserver(dir, mask) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null && !stopped) synchronized(this@GameFiles) { on(event and ALL_EVENTS, path) }
        }
    }

    private fun add(dir: File, mask: Int, on: (Int, String) -> Unit) {
        if (!dir.isDirectory) return
        val w = Watch(dir, mask, on)
        w.startWatching()
        synchronized(observers) { observers += w }
    }

    /** (Re)builds the watches: Logs, the account and realm folders (for new characters) and each character's. */
    private fun watch() {
        val game = folder ?: return
        synchronized(observers) { observers.forEach { it.stopWatching() }; observers.clear() }
        add(File(game, "Logs"), FileObserver.OPEN) { _, _ ->
            if (!running) { running = true; processSeen = false; character = null; inWorld = false; say("S") }
        }
        val accounts = File(game, "WTF/Account")
        add(accounts, FileObserver.CREATE) { _, _ -> rewatch() }
        for (account in accounts.listFiles().orEmpty().filter { it.isDirectory && it.name != "SavedVariables" }) {
            add(account, FileObserver.CREATE) { _, _ -> rewatch() }
            for (realm in account.listFiles().orEmpty().filter { it.isDirectory && it.name != "SavedVariables" }) {
                // A new character's folder is made the first time it enters the world.
                add(realm, FileObserver.CREATE) { event, name ->
                    if ((event and FileObserver.CREATE) != 0 && File(realm, name).isDirectory) { rewatch(); entered(name) }
                }
                for (char in realm.listFiles().orEmpty().filter { it.isDirectory }) watchCharacter(char)
            }
        }
    }

    /** A new folder appeared: the watches again, off the event's own thread. */
    private fun rewatch() = Thread { if (!stopped) watch() }.start()

    private fun watchCharacter(dir: File) {
        val name = dir.name
        // When this character's files were last written (logout, quit, /reload).
        var writtenAt = 0L
        add(dir, FileObserver.OPEN or FileObserver.CREATE) { event, path ->
            val now = android.os.SystemClock.uptimeMillis()
            if ((event and FileObserver.CREATE) != 0) writtenAt = now
            // config-cache.wtf is opened again around logout, when the files are written:
            // while in the world as them, or right after writing, that is not a login.
            if ((event and FileObserver.OPEN) != 0 && path == "config-cache.wtf" &&
                !(inWorld && character == name) && now - writtenAt > 10_000) entered(name)
            if (path == "SavedVariables") rewatch()
        }
        add(File(dir, "SavedVariables"), FileObserver.OPEN or FileObserver.MOVED_TO or FileObserver.CREATE) { event, path ->
            val now = android.os.SystemClock.uptimeMillis()
            when {
                // Saving opens the old file too: only a read well apart from any writing is the addons loading.
                (event and FileObserver.OPEN) != 0 && path == "ThorCompanion.lua" && now - writtenAt > 5000 &&
                    !(inWorld && character == name && addons) -> {
                    character = name; inWorld = true; addons = true
                    say("A $name")
                }
                (event and (FileObserver.MOVED_TO or FileObserver.CREATE)) != 0 -> {
                    writtenAt = now
                    if ((event and FileObserver.MOVED_TO) != 0 && path.endsWith(".lua") && inWorld && character == name) {
                        inWorld = false; addons = false
                        say("O $name")
                    }
                }
            }
        }
    }

    private fun entered(name: String) {
        if (!running) { running = true; say("S") }
        character = name; inWorld = true; addons = false
        say("L $name")
    }

    /** While the game runs, whether its process is still there (the only thing not told by a file). */
    private fun checkProcess() {
        while (!stopped) {
            Thread.sleep(if (running) 3000 else 10_000)
            val now = process() != null
            synchronized(this) {
                if (now) processSeen = true
                if (running && !now && processSeen) { running = false; inWorld = false; addons = false; character = null; say("X") }
                else if (!running && now) { running = true; say("S") }
            }
        }
    }

    companion object {
        private val EXE = Regex("""(?i)wow[^/\\]*\.exe$""")

        /** The game's process: its folder, from its command line ("wine /data/.../WowB-ARM64.exe ..."). */
        fun process(): File? {
            for (pid in File("/proc").list().orEmpty()) {
                if (pid.firstOrNull()?.isDigit() != true) continue
                val args = runCatching { File("/proc/$pid/cmdline").readText().split('\u0000') }.getOrNull() ?: continue
                val exe = args.take(3).firstOrNull { EXE.containsMatchIn(it) } ?: continue
                val unix = if (exe.length > 2 && exe[1] == ':') exe.substring(2).replace('\\', '/') else exe
                File(unix).parentFile?.takeIf { File(it, "WTF").isDirectory }?.let { return it }
            }
            return null
        }

        /** The game folder: the one the app remembers, the running game's, or the newest WTF on the device. */
        fun find(hint: String?): File? {
            hint?.let { File(it) }?.takeIf { File(it, "WTF").isDirectory }?.let { return it }
            process()?.let { return it }
            val found = runCatching {
                val p = ProcessBuilder("sh", "-c", "find /data/user/0 -maxdepth 12 -type d -name WTF 2>/dev/null").start()
                p.inputStream.bufferedReader().readLines().also { p.waitFor() }
            }.getOrDefault(emptyList())
            return found.map { File(it).parentFile!! }
                .filter { !it.path.contains(".old") && File(it, "Logs").isDirectory }
                .maxByOrNull { File(it, "WTF/Account").lastModified() }
        }
    }
}
