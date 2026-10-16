package thor.companion

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a newer build on the project's GitHub releases (pre-releases included) and puts
 * it on the Thor as root. The addon travels inside the app: the new app copies it into
 * the Thor-Forever folder when it starts, and the game takes it at its next start.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/weltenaichaos/thor-companion/releases?per_page=10"

    class Release(val version: String, val apkUrl: String)

    /** "0.24.1" -> [0, 24, 1], for comparing. */
    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }

    fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** The newest release with an app in it, if it is newer than this app; null otherwise (or offline). */
    fun check(context: Context): Release? {
        val text = get(RELEASES) ?: return null
        val list = runCatching { JSONArray(text) }.getOrNull() ?: return null
        var best: Release? = null
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            val version = r.optString("tag_name").removePrefix("v")
            if (best == null || newer(version, best.version)) best = Release(version, apk.optString("browser_download_url"))
        }
        return best?.takeIf { newer(it.version, installedVersion(context)) }
    }

    /** Downloads the app into the cache; [progress] gets whole percents. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit): File? {
        val out = File(context.cacheDir, "update.apk")
        return try {
            val c = open(release.apkUrl)
            val total = c.contentLengthLong
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != shown && pct % 10 == 0) { shown = pct; progress(pct) }
                    }
                }
            }
            if (total > 0 && done != total) null else out
        } catch (e: Exception) {
            android.util.Log.w("ThorCompanion", "update download: $e")
            null
        }
    }

    /**
     * Installs [apk] as root and opens the app again on [display]. Runs detached: this
     * app is stopped while its new version goes in. False when the root shell isn't there.
     */
    fun install(context: Context, apk: File, display: Int): Boolean {
        val tmp = "/data/local/tmp/forever-companion-update.apk"
        val activity = "${context.packageName}/.MainActivity"
        val script = "cp ${RootShell.quote(apk.absolutePath)} $tmp && chmod 644 $tmp && " +
            "pm install -r $tmp > /data/local/tmp/forever-companion-update.txt 2>&1; rm -f $tmp; " +
            "am start --display $display -n $activity"
        return RootShell.exec("nohup sh -c ${RootShell.quote("sleep 1; $script")} > /dev/null 2>&1 & echo started")?.trim() == "started"
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "ForeverCompanion")
    }

    private fun get(url: String): String? = try {
        val c = open(url)
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        android.util.Log.w("ThorCompanion", "update check: $e")
        null
    }
}
