package thor.companion

import android.content.Context
import java.io.File

/**
 * A test: what the game leaves outside the screen while it starts, logs in, logs out
 * and quits, so the app could later follow it without looking at the screen on a timer.
 * For 30 minutes after the app starts it writes to Download/forever-companion-events.txt:
 * files WoW opens, writes, creates or deletes in its WTF and Logs folders, whether the
 * game's process runs, and when the app sees the data square come and go.
 * Only watches and reads; it changes nothing of the game.
 */
object EventProbe {
    private const val LOG = "Download/forever-companion-events.txt"
    @Volatile private var log: String? = null

    fun start(context: Context) {
        val q = RootShell::quote
        val dir = File(context.cacheDir, "probe").apply { mkdirs() }
        val logs = AddonInstaller.STORAGE.map { "$it/$LOG" }
        val event = File(dir, "event.sh")
        val main = File(dir, "probe.sh")
        val pidFile = File(dir, "probe.pid")
        // inotifyd runs this for each event: event letter, folder, file name.
        event.writeText("#!/system/bin/sh\necho \"$(date +%H:%M:%S) file $1 $2/$3\" >> \"\$FC_LOG\"\n")
        main.writeText("""
            chmod 755 ${q(event.path)}
            [ -f ${q(pidFile.path)} ] && kill $(cat ${q(pidFile.path)}) 2>/dev/null
            echo $$ > ${q(pidFile.path)}
            FC_LOG=""
            for L in ${logs.joinToString(" ") { q(it) }}; do
              [ -d "${'$'}{L%/*}" ] && { FC_LOG="${'$'}L"; break; }
            done
            [ -n "${'$'}FC_LOG" ] || exit 0
            export FC_LOG
            log() { echo "$(date +%H:%M:%S) $*" >> "${'$'}FC_LOG"; }
            echo "${'$'}FC_LOG" > ${q(File(dir, "log").path)}
            log "probe start"
            command -v inotifyd >/dev/null || log "no inotifyd on this device"
            WTF=$(find /data/user/0 /data/data -maxdepth 12 -type d -name WTF 2>/dev/null | sort -u)
            log "WTF folders: ${'$'}WTF"
            WATCH=""
            for W in ${'$'}WTF; do
              for D in $(find "${'$'}W" -type d 2>/dev/null) "${'$'}{W%/WTF}/Logs"; do
                [ -d "${'$'}D" ] && WATCH="${'$'}WATCH ${'$'}D:wrndmy"
              done
            done
            IP=""
            if [ -n "${'$'}WATCH" ]; then
              inotifyd ${q(event.path)} ${'$'}WATCH >/dev/null 2>&1 &
              IP=$!
              log "watching $(echo ${'$'}WATCH | wc -w) folders"
            fi
            LAST=""
            END=$(( $(date +%s) + 1800 ))
            while [ $(date +%s) -lt ${'$'}END ]; do
              P=$(ps -A -o ARGS 2>/dev/null | grep -i 'wow[^ ]*\.exe' | grep -v grep | head -1)
              if [ -n "${'$'}P" ]; then S="running"; else S="stopped"; fi
              [ "${'$'}S" != "${'$'}LAST" ] && { log "game ${'$'}S ${'$'}P"; LAST="${'$'}S"; }
              sleep 2
            done
            [ -n "${'$'}IP" ] && kill ${'$'}IP 2>/dev/null
            log "probe end"
            rm -f ${q(pidFile.path)}
        """.trimIndent() + "\n")
        RootShell.exec("nohup sh ${q(main.path)} >/dev/null 2>&1 &")
        log = null
        Thread {
            Thread.sleep(3000)
            log = runCatching { File(dir, "log").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }.start()
    }

    /** One line from the app itself (on its reading thread), next to the game's events. */
    fun note(what: String) {
        val l = log ?: return
        RootShell.exec("echo \"$(date +%H:%M:%S) app ${what.replace("\"", "")}\" >> ${RootShell.quote(l)}")
    }
}
