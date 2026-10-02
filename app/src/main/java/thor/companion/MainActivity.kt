package thor.companion

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import thor.companion.strip.ActionKeys
import thor.companion.strip.GameState
import thor.companion.strip.ChatLine
import thor.companion.strip.ChatLog
import thor.companion.strip.ItemNames
import thor.companion.strip.MapPicture
import thor.companion.strip.PartAssembler
import thor.companion.strip.StripDecoder
import thor.companion.strip.Trail
import thor.companion.strip.ZoneMap
import java.util.Locale

/**
 * The bottom-screen companion: reads the addon's strip off the top screen a couple
 * of times a second and shows the game's panels here instead of over the game.
 */
class MainActivity : Activity() {

    private enum class Panel(val title: String) { BAGS("Bags"), CHARACTER("Character"), MAP("Map"), CHAT("Chat"), KEYS("Keys") }

    private lateinit var screen: TopScreen
    private lateinit var names: NameStore
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private val tabs = HashMap<Panel, Button>()
    private var panel = Panel.BAGS
    private var state: GameState? = null
    private val chat = ChatLog()
    private var zoneMap: ZoneMap? = null
    private val trail = Trail()
    private var trailUnsaved = 0
    private var mapView: MapView? = null
    private var mapTitle: TextView? = null
    private lateinit var scroll: ScrollView
    private var gotKeys: Set<String> = emptySet()
    private var keyResult = ""

    @Volatile private var worker: Thread? = null
    @Volatile private var lastKeyAt = 0L
    /** Start time and duration (ms) of each capture in the last minute, for the battery check. */
    private val captures = java.util.ArrayDeque<LongArray>()
    /** Key presses go out one at a time, in the order they were tapped. */
    private val keys = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Taps here must not take the key focus (or the controller) away from the game.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        screen = TopScreen(this)
        names = NameStore(this)
        screen.displayId = getPreferences(MODE_PRIVATE).getString("display", null)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Panel.entries) {
            val b = Button(this).apply {
                text = p.title
                isAllCaps = false
                textSize = 15f
                setOnClickListener { show(p) }
            }
            tabs[p] = b
            header.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        }
        status = TextView(this).apply {
            setTextColor(DIM)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
            setOnLongClickListener { nextDisplay(); true }
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header)
        root.addView(status)
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        runCatching { trail.load(trailFile().readText()) }
        show(Panel.BAGS)
        status.text = "Looking for the game…"
    }

    override fun onStart() {
        super.onStart()
        worker = Thread(::loop, "strip-reader").apply { isDaemon = true; start() }
    }

    override fun onStop() {
        worker = null
        saveTrail()
        super.onStop()
    }

    private fun trailFile() = java.io.File(filesDir, "trail.txt")

    private fun saveTrail() {
        if (trailUnsaved == 0) return
        trailUnsaved = 0
        runCatching { trailFile().writeText(trail.save()) }
    }

    private fun loop() {
        if (!RootShell.available()) {
            runOnUiThread {
                status.text = "Can't capture the top screen: the Thor's root service isn't reachable. " +
                    "Check that Settings › Thor › Force SELinux is off."
            }
            return
        }
        var misses = 0
        var near = -1
        val assembler = PartAssembler()
        while (worker === Thread.currentThread()) {
            val t0 = SystemClock.uptimeMillis()
            val px = screen.capture()
            val result = if (px == null) null else StripDecoder.decode(px, near)
            val frame = (result as? StripDecoder.Result.Ok)?.frame?.takeIf { it.crcOk }
            synchronized(captures) {
                captures.addLast(longArrayOf(t0, SystemClock.uptimeMillis() - t0))
                while (captures.first()[0] < t0 - 60_000) captures.removeFirst()
            }
            if (frame != null) {
                misses = 0
                near = frame.row
                val message = assembler.add(frame)
                val page = message?.let { ItemNames.parse(it) }
                when {
                    message == null -> {}
                    message.startsWith("TH1|") -> runOnUiThread { onChat(message) }
                    message.startsWith("TM1|") -> ZoneMap.parse(message)?.let { runOnUiThread { onMap(it) } }
                    message.startsWith("TW1|") -> MapPicture.parse(message)?.let { takeMapPicture(it, message) }
                    message.startsWith("TK1|") -> {
                        val keys = message.substring(4).split(',').filter { it.isNotEmpty() }.toSet()
                        runOnUiThread { onKeys(keys) }
                    }
                    page != null -> {
                        val changed = names.addAll(page)
                        runOnUiThread { onNames(changed) }
                    }
                    else -> runOnUiThread { onMessage(message) }
                }
            } else if (++misses == 4) {
                near = -1
                val why = when {
                    px == null -> "the capture failed"
                    result is StripDecoder.Result.Failed -> result.reason
                    else -> "the data was damaged"
                }
                runOnUiThread { status.text = "No data from the addon ($why). Is the game open with ThorCompanion on?" }
            }
            // Reading the screen is what costs battery, so look once a second while
            // nothing is going on. The addon shows each part of a longer message for
            // about a third of a second, so read fast while one is coming in, and
            // right after a tap, when the bags are about to change. Slower still
            // while the game isn't showing the square at all.
            val fast = assembler.waiting || SystemClock.uptimeMillis() - lastKeyAt < 3000
            val period = when {
                fast -> 200L
                misses > 30 -> 3000L
                else -> 1000L
            }
            SystemClock.sleep((period - (SystemClock.uptimeMillis() - t0)).coerceIn(30, period))
        }
    }

    private fun onMessage(message: String) {
        val parsed = GameState.parse(message, state)
        status.text = if (parsed == null) "Connected, but the addon sent something unexpected." else "Connected"
        if (parsed == null || parsed == state) return
        val moved = parsed.copy(x = state?.x, y = state?.y, facing = state?.facing) == state
        state = parsed
        val id = parsed.mapId
        if (id != null && parsed.x != null && parsed.y != null && trail.add(id, parsed.x!!, parsed.y!!)) {
            if (++trailUnsaved >= 30) saveTrail()
        }
        // Walking only moves the arrow; the rest of the tab stays as it is.
        if (panel == Panel.MAP && moved && mapView != null) updateMap() else if (panel != Panel.KEYS) render()
    }

    private fun mapFile(id: Int) = java.io.File(java.io.File(filesDir, "maps").apply { mkdirs() }, "$id.png")

    /**
     * The world map is open in the game: one full screenshot, the map cut out of it and
     * kept as that zone's background. Only when the same screenshot still shows the
     * addon saying so, so a map closed in between is not taken. Runs on the reader thread.
     */
    private fun takeMapPicture(pic: MapPicture, message: String) {
        fun fail(why: String) = runOnUiThread {
            pictureNote = "Map picture not taken: $why"
            if (panel == Panel.MAP) updateMap()
        }
        val px = screen.capture(rows = 4000) ?: return fail("the screenshot failed")
        val found = StripDecoder.decode(px)
        val frame = (found as? StripDecoder.Result.Ok)?.frame?.takeIf { it.crcOk }
            ?: return fail("the data square was not readable in the screenshot (${(found as? StripDecoder.Result.Failed)?.reason ?: "damaged"})")
        if (String(frame.payload) != message) return fail("the map was closed or changed before the screenshot")
        val r = pic.onScreen(frame)
        val l = r[0].coerceIn(0, px.width); val t = r[1].coerceIn(0, px.height)
        val w = r[2].coerceIn(0, px.width) - l; val h = r[3].coerceIn(0, px.height) - t
        if (w < 50 || h < 50) return fail("the map is outside the screen (${r.joinToString(",")} on ${px.width}x${px.height})")
        val pixels = IntArray(w * h) { i -> px.rgb(l + i % w, t + i / w) or (0xFF shl 24) }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val saved = runCatching { mapFile(pic.mapId).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        if (saved.isFailure) return fail("could not save it (${saved.exceptionOrNull()?.message})")
        runOnUiThread {
            pictures.remove(pic.mapId)
            pictureNote = "Took a picture of map ${pic.mapId} (${w}x$h)"
            if (panel == Panel.MAP) updateMap()
        }
    }

    /** What happened to the last map picture, shown under the map (the status line changes too often). */
    private var pictureNote = ""
    private var pictureLine: TextView? = null

    /** Zone pictures loaded so far (null: there is none yet). */
    private val pictures = HashMap<Int, android.graphics.Bitmap?>()

    private fun picture(id: Int): android.graphics.Bitmap? = pictures.getOrPut(id) {
        mapFile(id).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
    }

    private fun onMap(map: ZoneMap) {
        status.text = "Connected"
        if (map == zoneMap) return
        zoneMap = map
        if (panel == Panel.MAP) render()
    }

    private fun onChat(message: String) {
        status.text = "Connected"
        if (chat.add(message) == true && panel == Panel.CHAT) render()
    }

    private fun onKeys(keys: Set<String>) {
        status.text = "Connected, key test running"
        if (keys == gotKeys) return
        gotKeys = keys
        if (panel == Panel.KEYS) render()
    }

    private fun onNames(changed: Boolean) {
        status.text = "Connected"
        if (changed && panel == Panel.BAGS) render()
    }

    /** Long-press on the status line: try the next screen, in case the default one is the wrong one. */
    private fun nextDisplay() {
        Thread {
            val ids = listOf<String?>(null) + screen.listDisplays()
            val next = ids[(ids.indexOf(screen.displayId) + 1) % ids.size]
            screen.displayId = next
            getPreferences(MODE_PRIVATE).edit().putString("display", next).apply()
            runOnUiThread { status.text = "Capturing screen ${next ?: "default"}" }
        }.start()
    }

    private fun show(p: Panel) {
        panel = p
        for ((key, b) in tabs) {
            b.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (key == p) ACCENT else CARD)
            }
            b.setTextColor(if (key == p) Color.BLACK else TEXT)
        }
        render()
    }

    private fun render() {
        content.removeAllViews()
        mapView = null
        if (panel == Panel.KEYS) {
            renderKeys()
            return
        }
        val s = state
        if (s == null) {
            content.addView(line("Waiting for the game…", DIM))
            return
        }
        when (panel) {
            Panel.BAGS -> renderBags(s)
            Panel.CHARACTER -> {
                content.addView(line(s.name, TEXT, 26f, bold = true))
                content.addView(line("Level ${s.level ?: "?"}", TEXT, 18f))
                content.addView(line(money(s), GOLD, 18f))
            }
            Panel.MAP -> renderMap()
            Panel.CHAT -> renderChat()
            Panel.KEYS -> {}
        }
    }

    /** The zone drawn from the addon's places, with the path walked and an arrow for you. */
    private fun renderMap() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = line("", TEXT, 18f, bold = true)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val prefs = getPreferences(MODE_PRIVATE)
        val view = MapView(this).apply {
            close = prefs.getBoolean("mapClose", false)
            onPlace = { p ->
                status.text = MapView.kindName(p.kind) + (if (p.label.isEmpty()) "" else ": ${p.label}") +
                    String.format(Locale.US, " (%.1f, %.1f)", p.x * 100, p.y * 100)
            }
        }
        val zoom = Button(this).apply {
            isAllCaps = false
            text = if (view.close) "Whole zone" else "Around me"
            setOnClickListener {
                view.close = !view.close
                prefs.edit().putBoolean("mapClose", view.close).apply()
                text = if (view.close) "Whole zone" else "Around me"
                view.invalidate()
            }
        }
        val take = Button(this).apply {
            isAllCaps = false
            text = "Get zone picture"
            // One key: the addon shows the zone's map art for a few seconds and the app takes it.
            setOnClickListener {
                pictureNote = "Asked the game for the zone picture…"
                updateMap()
                pressKey(ActionKeys.PICTURE) { err -> pictureNote = "Map picture not taken: $err"; updateMap() }
            }
        }
        row.addView(take)
        row.addView(zoom)
        content.addView(row)
        content.addView(view, LinearLayout.LayoutParams(-1, maxOf(dp(240), scroll.height - dp(90))))
        content.addView(line("Tap a marker for its name. ! pick up a quest, yellow circle: do a quest there, ? turn in, F flight master, D dungeon, ★ rare, ● group. " +
            "The yellow line is where you walked. Get zone picture shows the zone's map in the game for 3 seconds and keeps a picture of it here.", DIM, 12f))
        val note = line("", DIM, 12f)
        content.addView(note)
        pictureLine = note
        mapView = view
        mapTitle = title
        updateMap()
    }

    private fun updateMap() {
        val view = mapView ?: return
        val s = state
        val map = zoneMap?.takeIf { it.mapId == s?.mapId }
        view.zone = map
        view.x = s?.x?.takeIf { it > 0 }
        view.y = s?.y?.takeIf { it > 0 }
        view.facing = s?.facing
        view.trail = s?.mapId?.let { trail.paths[it] }.orEmpty()
        view.picture = s?.mapId?.let { picture(it) }
        val where = if (s?.x != null && s.y != null && s.x!! > 0) String.format(Locale.US, "  %.1f, %.1f", s.x!! * 100, s.y!! * 100) else ""
        mapTitle?.text = (map?.zone?.ifEmpty { null } ?: "Map ${s?.mapId ?: "unknown"}") + where +
            (if (s?.mapId != null && view.picture == null) "  (no picture yet)" else "")
        pictureLine?.text = pictureNote
        view.invalidate()
    }

    /** The chat lines, newest at the bottom, coloured like WoW's chat frame. */
    private fun renderChat() {
        if (chat.lines.isEmpty()) {
            content.addView(line("No chat yet. New lines in say, whispers, party, raid, guild and system messages show up here. Tap a line to whisper its sender.", DIM))
            return
        }
        for (l in chat.lines.takeLast(100)) content.addView(chatLine(l))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatLine(l: ChatLine): TextView {
        val n = l.name
        val who = when {
            n.isEmpty() -> ""
            l.kind == "whisper" -> "$n whispers: "
            l.kind == "whisper_to" -> "To $n: "
            l.kind == "emote" -> "$n "
            l.kind == "yell" -> "$n yells: "
            l.channel.isNotEmpty() -> "[${l.channel}] $n: "
            else -> "$n: "
        }
        val colour = when (l.kind) {
            "whisper", "whisper_to" -> Color.rgb(255, 128, 255)
            "bnwhisper", "bnwhisper_to" -> Color.rgb(0, 255, 246)
            "party" -> Color.rgb(170, 170, 255)
            "raid" -> Color.rgb(255, 127, 0)
            "instance" -> Color.rgb(255, 127, 0)
            "guild" -> Color.rgb(64, 255, 64)
            "yell" -> Color.rgb(255, 64, 64)
            "emote" -> Color.rgb(255, 128, 64)
            "system" -> Color.rgb(255, 255, 0)
            "channel" -> Color.rgb(255, 192, 192)
            else -> TEXT
        }
        val view = line(who + l.text, colour, 15f).apply { setPadding(0, dp(2), 0, dp(2)) }
        // Tap a line: one key that opens the whisper box for its sender in the game.
        val key = chat.whisperSlot(l.sender)?.let { ActionKeys.forWhisper(it) } ?: return view
        view.setOnClickListener {
            pressKey(key, onDone = { status.text = "Whisper to $n: type your message in the game" }) { err ->
                status.text = "Whisper to $n: $err"
            }
        }
        return view
    }

    /** Key test: each button sends one key to the game's screen; `/thor keytest` in game prints what arrives. */
    private fun renderKeys() {
        content.addView(line("Type /thor keytest in the game, then tap each key once. Keys the game reports back turn green.", DIM))
        val result = line(keyResult, TEXT)
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (key in TEST_KEYS) {
            val got = key in gotKeys
            val b = Button(this).apply {
                text = if (got) "✓ $key" else key
                isAllCaps = false
                setTextColor(if (got) Color.BLACK else TEXT)
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (got) GOT else CARD) }
                setOnClickListener {
                    pressKey(key, onDone = { keyResult = "Sent $key"; result.text = keyResult }) { err ->
                        keyResult = "$key: $err"
                        result.text = keyResult
                    }
                }
            }
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(56)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(grid)
        content.addView(result)
        content.addView(line(captureStats(), DIM).apply { setPadding(0, dp(16), 0, 0) })
    }

    /** Battery check: how often and how long the app read the game's screen in the last minute. */
    private fun captureStats(): String {
        val (count, total) = synchronized(captures) { captures.size to captures.sumOf { it[1] } }
        if (count == 0) return "Battery check: the game's screen hasn't been read in the last minute."
        return "Battery check: read the game's screen $count times in the last minute, " +
            "${total / count} ms each (busy ${total * 100 / 60_000}% of the time). Tap the Keys tab again to refresh."
    }

    /** Sends one key to the game's screen; the callbacks run on the UI thread. */
    private fun pressKey(key: String, onDone: () -> Unit = {}, onError: (String) -> Unit) {
        val target = gameDisplay()
        lastKeyAt = SystemClock.uptimeMillis()
        keys.execute {
            val err = KeySender.send(this, target, key)
            runOnUiThread { if (err == null) onDone() else onError(err) }
        }
    }

    /** The other screen: the one this app is not on. */
    private fun gameDisplay(): Int {
        val here = display?.displayId ?: 0
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        return dm.displays.map { it.displayId }.firstOrNull { it != here } ?: 0
    }

    private fun renderBags(s: GameState) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(line(money(s), GOLD, 18f), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(line("${s.freeSlots ?: "?"} of ${s.totalSlots ?: "?"} slots free", TEXT, 16f))
        content.addView(row)

        val grid = GridLayout(this).apply { columnCount = 5; useDefaultMargins = false }
        for (item in s.items) {
            val known = names[item.itemId]
            val tile = TextView(this).apply {
                val count = if (item.count > 1) "\n×${item.count}" else ""
                text = (known?.name ?: "#${item.itemId}") + count
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setTextColor(if (known != null) qualityColour(known.quality) else DIM)
                textSize = 12f
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(CARD) }
                // One tap, one key: the addon bound this key to "use the item in this slot".
                val key = item.key?.let { ActionKeys.forIndex(it) }
                val label = known?.name ?: "the item"
                setOnClickListener { v ->
                    if (key == null) {
                        status.text = "$label has no tap key yet. Is the new addon loaded? /thor taps in game shows the keys."
                        return@setOnClickListener
                    }
                    (v.background as GradientDrawable).setColor(PRESSED)
                    v.postDelayed({ (v.background as GradientDrawable).setColor(CARD) }, 250)
                    pressKey(key, onDone = { status.text = "Sent $key for $label" }) { err ->
                        status.text = "Couldn't use $label: $err"
                    }
                }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(72)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                // GridLayout lines tiles up by text baseline by default, which pushed
                // one-line tiles (no stack count) above their neighbours.
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(grid)
        if (s.items.isEmpty()) content.addView(line("Your bags are empty.", DIM))
        else if (s.items.any { it.key != null }) content.addView(line("Tap an item to use it.", DIM))
    }

    /** WoW's item quality colours. */
    private fun qualityColour(q: Int): Int = when (q) {
        0 -> Color.rgb(157, 157, 157)
        2 -> Color.rgb(30, 255, 0)
        3 -> Color.rgb(0, 112, 221)
        4 -> Color.rgb(163, 53, 238)
        5 -> Color.rgb(255, 128, 0)
        6 -> Color.rgb(230, 204, 128)
        7, 8 -> Color.rgb(0, 204, 255)
        else -> TEXT
    }

    private fun money(s: GameState): String =
        if (s.copper == null) "Gold unknown" else "${s.gold}g ${s.silver}s ${s.copperPart}c"

    private fun line(text: String, colour: Int, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Must match ns.TestKeys in addon/ThorCompanion/KeyTest.lua. */
        val TEST_KEYS = listOf("CTRL", "ALT").flatMap { mod -> (1..12).map { "$mod-F$it" } } - "ALT-F4" +
            listOf("F9", "SHIFT-F9", "CTRL-SHIFT-F9", "NUMPAD5")
        val BG = Color.rgb(16, 18, 22)
        val CARD = Color.rgb(36, 40, 48)
        val TEXT = Color.rgb(230, 232, 236)
        val DIM = Color.rgb(140, 146, 156)
        val ACCENT = Color.rgb(255, 196, 64)
        val GOT = Color.rgb(90, 210, 120)
        val PRESSED = Color.rgb(70, 78, 92)
        val GOLD = Color.rgb(255, 210, 90)
    }
}
