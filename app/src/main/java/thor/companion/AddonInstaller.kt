package thor.companion

import android.content.Context
import java.io.File

/**
 * Puts the addon that came with this app where the game gets it from, so app and
 * addon always match: the AddOns folder of the Thor-Forever kit, which its launcher
 * copies into the game at every start. Only when that folder holds another version;
 * the folder name stays ThorCompanion, so the addon keeps its settings. The game's
 * own folders are left to the launcher.
 *
 * The kit can live anywhere in shared storage (Download/Thor-Forever by default), so
 * it is looked for: a folder with Thor-Forever.exe and installer/launch-game.sh in
 * it. Every kit found gets the addon (an old copy left behind does no harm).
 * The root service may not see shared storage as /sdcard, so it is tried under each
 * name it goes by. The script runs from a file and writes what it did to another,
 * since the root service passes on only one line of output.
 */
object AddonInstaller {
    private const val NAME = "ThorCompanion"
    private const val KIT = "Download/Thor-Forever"
    /** How deep in shared storage a kit is looked for (Download/Thor-Forever/installer/launch-game.sh is 4). */
    private const val DEPTH = 7
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
        // The first name for shared storage under which a Thor-Forever kit shows up; each kit there.
        // One line per kit: "installed <kit>", "same <kit>" or "failed <kit>"; "missing" when there is none.
        script.writeText("""
            for S in ${STORAGE.joinToString(" ")}; do
              [ -d "${'$'}S" ] || continue
              found=0
              for L in ${'$'}(find "${'$'}S" -maxdepth $DEPTH -name launch-game.sh 2>/dev/null); do
                case "${'$'}L" in "${'$'}S"/Android/*) continue ;; */installer/launch-game.sh) ;; *) continue ;; esac
                T="${'$'}{L%/installer/launch-game.sh}"
                [ -f "${'$'}T/Thor-Forever.exe" ] || continue
                found=1
                D="${'$'}T/AddOns/$NAME"
                if grep -qx ${q("## Version: $version")} "${'$'}D/$NAME.toc" 2>/dev/null; then echo "same ${'$'}T"; continue; fi
                mkdir -p "${'$'}D" && cp -f ${q(dir.absolutePath)}/* "${'$'}D/" || { echo "failed ${'$'}T"; continue; }
                # Written straight into the storage's own folder: same owner as the folder around it.
                case "${'$'}S" in /data/*) chown -R "${'$'}(stat -c %u:%g "${'$'}T")" "${'$'}T/AddOns" ;; esac
                grep -qx ${q("## Version: $version")} "${'$'}D/$NAME.toc" && echo "installed ${'$'}T" || echo "failed ${'$'}T"
              done
              # The kit of older versions of Thor Forever, before it could live anywhere.
              if [ ${'$'}found = 0 ] && [ -d "${'$'}S/$KIT" ]; then
                T="${'$'}S/$KIT"; found=1
                D="${'$'}T/AddOns/$NAME"
                if grep -qx ${q("## Version: $version")} "${'$'}D/$NAME.toc" 2>/dev/null; then echo "same ${'$'}T"
                elif mkdir -p "${'$'}D" && cp -f ${q(dir.absolutePath)}/* "${'$'}D/" && grep -qx ${q("## Version: $version")} "${'$'}D/$NAME.toc"; then echo "installed ${'$'}T"
                else echo "failed ${'$'}T"; fi
              fi
              [ ${'$'}found = 1 ] && exit 0
            done
            echo "missing"
        """.trimIndent() + "\n")
        RootShell.exec("sh ${q(script.absolutePath)} > ${q(out.absolutePath)} 2>&1; chown $uid:$uid ${q(out.absolutePath)}")
            ?: return Outcome(false, "the Thor's root service isn't reachable")
        val lines = runCatching { out.readText().trim().lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        // Shown as the folder in shared storage, the way a file manager shows it.
        fun shown(line: String) = line.substringAfter(' ').let { path ->
            STORAGE.fold(path) { p, root -> p.removePrefix("$root/") }
        } + "/AddOns"
        val installed = lines.filter { it.startsWith("installed ") }.map(::shown)
        val failed = lines.filter { it.startsWith("failed ") }.map(::shown)
        return when {
            failed.isNotEmpty() -> Outcome(installed.isNotEmpty(), "Couldn't put the addon $version into ${failed.joinToString(" and ")}.")
            installed.isNotEmpty() -> Outcome(true, "Put the Forever Companion addon $version into ${installed.joinToString(" and ")}; the game gets it at its next start.")
            lines.any { it.startsWith("same ") } -> Outcome(false, "")
            lines.lastOrNull() == "missing" -> Outcome(false, "Couldn't find the Thor-Forever folder (with Thor-Forever.exe in it), so the addon $version wasn't put there.")
            else -> Outcome(false, "Couldn't put the addon $version into the Thor-Forever folder (${lines.lastOrNull() ?: "no answer"}).")
        }
    }
}
