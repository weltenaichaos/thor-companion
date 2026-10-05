package thor.companion

import android.content.Context
import java.io.File

/**
 * Puts the addon that came with this app into the game, so app and addon always
 * match. It goes to both places it is read from on the Thor: Thor Forever's AddOns
 * folder in Download (the launcher copies it into the game at every start) and the
 * game's own Interface\AddOns. Only where the folder exists and holds another
 * version; the folder name stays ThorCompanion, so the addon keeps its settings.
 */
object AddonInstaller {
    private const val NAME = "ThorCompanion"
    private val TARGETS = listOf(
        "/sdcard/Download/Thor-Forever/AddOns",
        "/data/user/0/com.ludashi.aibench/files/usr/home/thor-forever/*/game/_classic_beta_/Interface/AddOns",
    )

    /** The bundled addon's version, from its .toc. */
    fun version(context: Context): String? = runCatching {
        context.assets.open("$NAME/$NAME.toc").bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("## Version:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull()

    /**
     * Installs where needed (root, so call off the UI thread). Returns how many places
     * got this version, or null when it couldn't be done.
     */
    fun install(context: Context): Int? {
        val version = version(context) ?: return null
        val dir = File(context.cacheDir, "addon/$NAME").apply { deleteRecursively(); mkdirs() }
        val files = context.assets.list(NAME).orEmpty()
        for (f in files) context.assets.open("$NAME/$f").use { input -> File(dir, f).outputStream().use { input.copyTo(it) } }
        if (files.isEmpty()) return null
        val q = RootShell::quote
        // The Download folder is made only when Thor Forever's folder is there.
        val script = """
            exec 2>/dev/null
            T=/sdcard/Download/Thor-Forever; [ -d "${'$'}T" ] && mkdir -p "${'$'}T/AddOns"
            n=0
            for AD in ${TARGETS.joinToString(" ")}; do
              [ -d "${'$'}AD" ] || continue
              D="${'$'}AD/$NAME"
              grep -qx ${q("## Version: $version")} "${'$'}D/$NAME.toc" 2>/dev/null && continue
              mkdir -p "${'$'}D" && cp -f ${q(dir.absolutePath)}/* "${'$'}D/" || continue
              chown -R "${'$'}(stat -c %u:%g "${'$'}AD")" "${'$'}D" 2>/dev/null
              chmod 755 "${'$'}D"; chmod 644 "${'$'}D"/*
              restorecon -R "${'$'}D" 2>/dev/null
              n=${'$'}((n + 1))
            done
            echo "installed:${'$'}n"
        """.trimIndent()
        val out = RootShell.exec(script) ?: return null
        return out.substringAfter("installed:", "").trim().toIntOrNull()
    }
}
