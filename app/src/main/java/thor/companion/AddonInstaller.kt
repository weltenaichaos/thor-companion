package thor.companion

import android.content.Context
import java.io.File

/**
 * Puts the addon that came with this app where the game gets it from, so app and
 * addon always match: Thor Forever's AddOns folder in Download, which its launcher
 * copies into the game at every start. Only when that folder holds another version;
 * the folder name stays ThorCompanion, so the addon keeps its settings. The game's
 * own folders are left to the launcher.
 *
 * The root service may not see shared storage as /sdcard, so the same folder is
 * tried under each name it goes by. The script runs from a file and writes what it
 * did to another, since the root service passes on only one line of output.
 */
object AddonInstaller {
    private const val NAME = "ThorCompanion"
    private const val KIT = "Download/Thor-Forever"
    private val STORAGE = listOf("/sdcard", "/storage/emulated/0", "/mnt/user/0/emulated/0", "/data/media/0")

    /** What came of [install]: [installed] when the folder got this version, else why not. */
    data class Outcome(val installed: Boolean, val note: String)

    /** The bundled addon's version, from its .toc. */
    fun version(context: Context): String? = runCatching {
        context.assets.open("$NAME/$NAME.toc").bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("## Version:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull()

    /** Installs where needed (root, so call off the UI thread). */
    fun install(context: Context): Outcome {
        val version = version(context) ?: return Outcome(false, "the app has no addon in it")
        val dir = File(context.cacheDir, "addon/$NAME").apply { deleteRecursively(); mkdirs() }
        val files = context.assets.list(NAME).orEmpty()
        for (f in files) context.assets.open("$NAME/$f").use { input -> File(dir, f).outputStream().use { input.copyTo(it) } }
        if (files.isEmpty()) return Outcome(false, "the app has no addon in it")
        val q = RootShell::quote
        val script = File(context.cacheDir, "addon/install.sh")
        val out = File(context.cacheDir, "addon/result.txt").apply { delete() }
        val uid = android.os.Process.myUid()
        // The first name for shared storage under which Thor Forever's folder shows up.
        script.writeText("""
            for S in ${STORAGE.joinToString(" ")}; do
              T="${'$'}S/$KIT"
              [ -d "${'$'}T" ] || continue
              D="${'$'}T/AddOns/$NAME"
              if grep -qx ${q("## Version: $version")} "${'$'}D/$NAME.toc" 2>/dev/null; then echo "same ${'$'}T"; exit 0; fi
              mkdir -p "${'$'}D" && cp -f ${q(dir.absolutePath)}/* "${'$'}D/" || { echo "failed ${'$'}T"; exit 0; }
              # Written straight into the storage's own folder: same owner as the folder around it.
              case "${'$'}S" in /data/*) chown -R "${'$'}(stat -c %u:%g "${'$'}T")" "${'$'}T/AddOns" ;; esac
              grep -qx ${q("## Version: $version")} "${'$'}D/$NAME.toc" && echo "installed ${'$'}T" || echo "failed ${'$'}T"
              exit 0
            done
            echo "missing"
        """.trimIndent() + "\n")
        RootShell.exec("sh ${q(script.absolutePath)} > ${q(out.absolutePath)} 2>&1; chown $uid:$uid ${q(out.absolutePath)}")
            ?: return Outcome(false, "the Thor's root service isn't reachable")
        val result = runCatching { out.readText().trim().lines().lastOrNull().orEmpty() }.getOrDefault("")
        return when {
            result.startsWith("installed") -> Outcome(true, "Put the Forever Companion addon $version into Download/Thor-Forever/AddOns; the game gets it at its next start.")
            result.startsWith("same") -> Outcome(false, "")
            result == "missing" -> Outcome(false, "Couldn't find Download/Thor-Forever, so the addon $version wasn't put there.")
            else -> Outcome(false, "Couldn't put the addon $version into Download/Thor-Forever/AddOns (${result.ifEmpty { "no answer" }}).")
        }
    }
}
