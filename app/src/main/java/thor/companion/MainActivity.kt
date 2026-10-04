package thor.companion

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import thor.companion.strip.ActionKeys
import thor.companion.strip.GameState
import thor.companion.strip.CharacterInfo
import thor.companion.strip.ChatLine
import thor.companion.strip.Gear
import thor.companion.strip.GearItem
import thor.companion.strip.ChatLog
import thor.companion.strip.IconPicture
import thor.companion.strip.ItemNames
import thor.companion.strip.MapPicture
import thor.companion.strip.PartAssembler
import thor.companion.strip.StripDecoder
import thor.companion.strip.Trail
import thor.companion.strip.ZoneMap
import thor.companion.Theme.ACCENT
import thor.companion.Theme.BG
import thor.companion.Theme.DIM
import thor.companion.Theme.GOLD
import thor.companion.Theme.PRESSED
import thor.companion.Theme.RAISED
import thor.companion.Theme.STROKE
import thor.companion.Theme.SURFACE
import thor.companion.Theme.TEXT
import java.util.Locale

/**
 * The bottom-screen companion: reads the addon's strip off the top screen a couple
 * of times a second and shows the game's panels here instead of over the game.
 */
class MainActivity : Activity() {

    private enum class Panel(val title: String) { BAGS("Bags"), CHARACTER("Character"), MAP("Map"), CHAT("Chat"), KEYS("Keys") }

    private lateinit var screen: TopScreen
    private lateinit var names: NameStore
    private lateinit var icons: IconStore
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var loadButton: TextView
    private lateinit var content: LinearLayout
    private val tabs = HashMap<Panel, TextView>()
    private var panel = Panel.BAGS
    private var state: GameState? = null
    private val chat = ChatLog()
    private var zoneMap: ZoneMap? = null
    private var character: CharacterInfo? = null
    private var gear: List<GearItem> = emptyList()
    private val trail = Trail()
    private var trailUnsaved = 0
    private var mapView: MapView? = null
    private var mapTitle: TextView? = null
    private lateinit var scroll: ScrollView
    private var gotKeys: Set<String> = emptySet()
    private var keyResult = ""

    @Volatile private var worker: Thread? = null
    @Volatile private var lastKeyAt = 0L
    /** When the square was last read: the dot by the status line is green while that is recent. */
    @Volatile private var frameAt = 0L
    /** When the last status (with the position) came in: it is only sent when something changed. */
    private var movedAt = 0L
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

        icons = IconStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        // The tabs: one bar, the open one in gold.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.box(context, SURFACE, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for (p in Panel.entries) {
            val b = TextView(this).apply {
                text = p.title
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { show(p) }
            }
            tabs[p] = b
            header.addView(b, LinearLayout.LayoutParams(0, dp(44), if (p == Panel.KEYS) 0.7f else 1f))
        }
        // The status line: a dot that says whether the game's data is coming in, what happened
        // last, and the one key that asks the game for everything when something is missing.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), 0, dp(6))
        }
        statusDot = View(this).apply { background = Theme.box(context, Theme.WARN, 5) }
        status = TextView(this).apply {
            setTextColor(DIM)
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            setOnLongClickListener { nextDisplay(); true }
        }
        loadButton = chip("Load from the game") {
            pressKey(ActionKeys.REFRESH, onDone = { say("Asked the game for everything again…") }) { err -> say("Couldn't ask the game: $err") }
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(loadButton)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header)
        root.addView(statusRow)
        scroll = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        runCatching { trail.load(trailFile().readText()) }
        loadLast()
        show(Panel.BAGS)
        say("Looking for the game…")
    }

    override fun onStart() {
        super.onStart()
        worker = Thread(::loop, "strip-reader").apply { isDaemon = true; start() }
    }

    override fun onStop() {
        worker = null
        saveTrail()
        saveLast()
        super.onStop()
    }

    private fun trailFile() = java.io.File(filesDir, "trail.txt")

    // The last message of each kind that describes the game's state, kept so a restarted
    // app shows the bags, character and map right away instead of after the addon's
    // next refresh (or a tap on Refresh).
    /** False while the bags shown are the ones kept from before a restart: then a tap could use whatever moved into that slot since. */
    private var bagsFresh = false
    private val lastMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun lastFile() = java.io.File(filesDir, "last-messages.txt")

    private fun remember(message: String) {
        val kind = message.take(4)
        if (kind !in KEPT || lastMessages[kind] == message) return
        lastMessages[kind] = message
        if (kind != "TS1|") saveLast()
    }

    private fun saveLast() = runCatching {
        // One message per record; messages contain newlines, so records are separated by a NUL.
        lastFile().writeText(lastMessages.values.joinToString("\u0000"))
    }

    private fun loadLast() = runCatching {
        if (!lastFile().exists()) return@runCatching
        for (m in lastFile().readText().split('\u0000')) {
            if (m.length < 4) continue
            lastMessages[m.take(4)] = m
            when {
                m.startsWith("TM1|") -> zoneMap = ZoneMap.parse(m)
                m.startsWith("TP1|") -> character = CharacterInfo.parse(m)
                m.startsWith("TQ1|") -> gear = Gear.parse(m).orEmpty()
                else -> GameState.parse(m, state)?.let { state = it }
            }
        }
    }

    private fun saveTrail() {
        if (trailUnsaved == 0) return
        trailUnsaved = 0
        runCatching { trailFile().writeText(trail.save()) }
    }

    private fun loop() {
        if (!RootShell.available()) {
            runOnUiThread {
                say("Can't capture the top screen: the Thor's root service isn't reachable. " +
                    "Check that Settings › Thor › Force SELinux is off.")
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
                frameAt = SystemClock.uptimeMillis()
                near = frame.row
                val message = assembler.add(frame)
                val page = message?.let { ItemNames.parse(it) }
                if (message?.startsWith("TS1|") == true) movedAt = SystemClock.uptimeMillis()
                if (message != null) remember(message)
                when {
                    message == null -> {}
                    message.startsWith("TH1|") -> runOnUiThread { onChat(message) }
                    message.startsWith("TM1|") -> ZoneMap.parse(message)?.let { runOnUiThread { onMap(it) } }
                    message.startsWith("TP1|") -> CharacterInfo.parse(message)?.let { runOnUiThread { onCharacter(it, gear) } }
                    message.startsWith("TQ1|") -> Gear.parse(message)?.let { runOnUiThread { onCharacter(character, it) } }
                    message.startsWith("TW1|") -> MapPicture.parse(message)?.let { takeMapPicture(it, message) }
                    message.startsWith("TI1|") -> IconPicture.parse(message)?.let { takeIcons(it, assembler.lastSeq) }
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
                runOnUiThread { say("No data from the addon ($why). Is the game open with ThorCompanion on?") }
            }
            // Reading the screen is what costs battery, so look once a second while
            // nothing is going on. The addon shows each part of a longer message for
            // about a third of a second, so read fast while one is coming in, and
            // right after a tap, when the bags are about to change. Slower still
            // while the game isn't showing the square at all.
            val fast = assembler.waiting || SystemClock.uptimeMillis() - lastKeyAt < 3000
            // While you walk the position changes all the time: look more often, so the
            // arrow on the map keeps up, and slow down again once you stand still.
            val walking = SystemClock.uptimeMillis() - movedAt < 3000
            val period = when {
                fast -> 200L
                walking -> 400L
                misses > 30 -> 3000L
                else -> 1000L
            }
            SystemClock.sleep((period - (SystemClock.uptimeMillis() - t0)).coerceIn(30, period))
        }
    }

    private fun onMessage(message: String) {
        if (message.startsWith("TB1|") && !bagsFresh) {
            bagsFresh = true
            if (panel == Panel.BAGS) render()
        }
        val parsed = GameState.parse(message, state)
        say(if (parsed == null) "Connected, but the addon sent something unexpected: ${message.take(80)}" else "Connected")
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

    /**
     * The addon shows item icons in a grid for a few seconds: one full screenshot, and
     * each icon cut out of it and kept. Only when the same screenshot still shows that
     * message ([seq]), so icons that went away in between are not taken. Runs on the
     * reader thread.
     */
    private fun takeIcons(pic: IconPicture, seq: Int) {
        fun done(note: String) = runOnUiThread {
            say(note)
            if (panel == Panel.BAGS || panel == Panel.CHARACTER) render()
        }
        val px = screen.capture(rows = 4000) ?: return done("Item icons not taken: the screenshot failed")
        val frame = (StripDecoder.decode(px) as? StripDecoder.Result.Ok)?.frame?.takeIf { it.crcOk }
        if (frame == null || frame.seq != seq) return done("Item icons not taken: they went away before the screenshot")
        var taken = 0
        for ((i, id) in pic.itemIds.withIndex()) {
            val r = pic.onScreen(frame, i)
            // One pixel in from each side: the edges blend into the black around the icon.
            val l = r[0] + 1; val t = r[1] + 1; val w = r[2] - r[0] - 2; val h = r[3] - r[1] - 2
            if (w < 8 || h < 8 || l < 0 || t < 0 || l + w > px.width || t + h > px.height) continue
            val pixels = IntArray(w * h) { k -> px.rgb(l + k % w, t + k / w) or (0xFF shl 24) }
            icons.put(id, android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888))
            taken++
        }
        done(if (taken == 0) "Item icons not taken: they were outside the screen" else "Took $taken item icons")
    }

    /** What happened to the last map picture, shown under the map (the status line changes too often). */
    private var pictureNote = ""
    private var pictureLine: TextView? = null

    /** Zone pictures loaded so far (null: there is none yet). */
    private val pictures = HashMap<Int, android.graphics.Bitmap?>()

    private fun picture(id: Int): android.graphics.Bitmap? = pictures.getOrPut(id) {
        mapFile(id).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
    }

    private fun onCharacter(c: CharacterInfo?, g: List<GearItem>) {
        say("Connected")
        if (c == character && g == gear) return
        character = c
        gear = g
        if (panel == Panel.CHARACTER) render()
    }

    private fun onMap(map: ZoneMap) {
        say("Connected")
        if (map == zoneMap) return
        zoneMap = map
        if (panel == Panel.MAP) render()
    }

    private fun onChat(message: String) {
        say("Connected")
        if (chat.add(message) == true && panel == Panel.CHAT) render()
    }

    private fun onKeys(keys: Set<String>) {
        say("Connected, key test running")
        if (keys == gotKeys) return
        gotKeys = keys
        if (panel == Panel.KEYS) render()
    }

    private fun onNames(changed: Boolean) {
        say("Connected")
        if (changed && (panel == Panel.BAGS || panel == Panel.CHARACTER)) render()
    }

    /** Long-press on the status line: try the next screen, in case the default one is the wrong one. */
    private fun nextDisplay() {
        Thread {
            val ids = listOf<String?>(null) + screen.listDisplays()
            val next = ids[(ids.indexOf(screen.displayId) + 1) % ids.size]
            screen.displayId = next
            getPreferences(MODE_PRIVATE).edit().putString("display", next).apply()
            runOnUiThread { say("Capturing screen ${next ?: "default"}") }
        }.start()
    }

    private fun show(p: Panel) {
        panel = p
        for ((key, b) in tabs) {
            b.background = if (key == p) Theme.box(this, ACCENT, 10) else null
            b.setTextColor(if (key == p) Color.BLACK else DIM)
        }
        scroll.scrollTo(0, 0)
        render()
    }

    private fun render() {
        content.removeAllViews()
        mapView = null
        updateLoadButton()
        if (panel == Panel.KEYS) {
            renderKeys()
            return
        }
        val s = state
        if (s == null) {
            content.addView(empty("Waiting for the game…", "Open WoW with the ThorCompanion addon on. If it is already open, tap Load from the game."))
            return
        }
        when (panel) {
            Panel.BAGS -> renderBags(s)
            Panel.CHARACTER -> renderCharacter(s)
            Panel.MAP -> renderMap()
            Panel.CHAT -> renderChat()
            Panel.KEYS -> {}
        }
    }

    /** The status line, with its dot: green while the game's data comes in, red when it stopped, amber before it ever came. */
    private fun say(text: String) {
        status.text = text
        val since = SystemClock.uptimeMillis() - frameAt
        val colour = when {
            frameAt == 0L -> Theme.WARN
            since < 5000 -> Theme.GOOD
            else -> Theme.BAD
        }
        (statusDot.background as GradientDrawable).setColor(colour)
        updateLoadButton()
    }

    /** Load from the game shows only while something is missing or old: then one tap asks the addon for everything. */
    private fun updateLoadButton() {
        val missing = state == null || state?.freeSlots == null || !bagsFresh || character == null || gear.isEmpty()
        loadButton.visibility = if (missing && panel != Panel.KEYS) View.VISIBLE else View.GONE
    }

    /** Name, level, experience, item level, stats and what you wear. */
    private fun renderCharacter(s: GameState) {
        val c = character
        val classColour = c?.classFile?.let { classColour(it) } ?: TEXT
        val head = card()
        head.addView(line(s.name, classColour, 24f, bold = true).apply { setPadding(0, 0, 0, 0) })
        head.addView(line(listOfNotNull("Level ${s.level ?: "?"}", c?.race, c?.className).joinToString(" "), TEXT, 15f).apply { setPadding(0, dp(2), 0, 0) })
        c?.guild?.let { head.addView(line("<$it>", Theme.GOOD, 13f).apply { setPadding(0, dp(2), 0, 0) }) }
        val xp = c?.xp; val xpMax = c?.xpMax
        if (xp != null && xpMax != null && xpMax > 0) {
            val bar = Bar(this).apply {
                value = xp.toFloat() / xpMax
                second = (xp + (c?.rested ?: 0)).toFloat() / xpMax
            }
            head.addView(bar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
            val rested = c?.rested?.takeIf { it > 0 }?.let { "  ·  rested ${String.format(Locale.US, "%,d", it)}" } ?: ""
            head.addView(line(String.format(Locale.US, "%,d / %,d experience (%d%%)", xp, xpMax, xp * 100 / xpMax) + rested, DIM, 12f).apply { setPadding(0, dp(4), 0, 0) })
        }
        content.addView(head, cardParams())

        val facts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        facts.addView(fact("Item level", c?.itemLevel?.let { String.format(Locale.US, "%.1f", it) } ?: "?", ACCENT), weighted())
        facts.addView(fact("Gold", money(s), GOLD), weighted(1.6f))
        facts.addView(fact("Armor", c?.armor?.toString() ?: "?", TEXT), weighted())
        content.addView(facts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (c != null && c.stats.any { it != null }) {
            val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, name) in listOf("Strength", "Agility", "Stamina", "Intellect").withIndex()) {
                stats.addView(fact(name, c.stats[i]?.toString() ?: "?", TEXT), weighted())
            }
            content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        val worn = gear.associateBy { it.slot }
        if (worn.values.any { (it.durability ?: 100) <= 20 }) {
            content.addView(line("Some gear is almost broken: repair soon.", Theme.BAD, 15f, bold = true).apply { setPadding(dp(4), dp(10), 0, 0) })
        }
        content.addView(section("Equipment" + iconHint(gear.map { it.itemId })))
        val grid = GridLayout(this).apply { columnCount = 2 }
        for (slot in GEAR_SLOTS) {
            val g = worn[slot]
            val known = g?.let { names[it.itemId] }
            val dur = g?.durability
            val detail = listOfNotNull(g?.itemLevel?.takeIf { it > 0 }?.let { "ilvl $it" }, dur?.let { "$it%" }).joinToString("  ·  ")
            val quality = known?.let { qualityColour(it.quality) }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(5), dp(8), dp(5))
                background = Theme.box(context, SURFACE, 10, quality?.let { Theme.withAlpha(it, 110) } ?: STROKE)
            }
            tile.addView(icon(g?.itemId, quality, 36), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(line(Gear.slotName(slot), DIM, 11f).apply { setPadding(0, 0, 0, 0) })
            words.addView(line(if (g == null) "Empty" else known?.name ?: "#${g.itemId}", quality ?: DIM, 13f).apply {
                setPadding(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (detail.isNotEmpty()) words.addView(line(detail, when {
                dur != null && dur <= 20 -> Theme.BAD
                dur != null && dur <= 50 -> Theme.WARN
                else -> DIM
            }, 11f).apply { setPadding(0, 0, 0, 0) })
            tile.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(grid)
    }

    /** WoW's class colours. */
    private fun classColour(file: String): Int = when (file) {
        "WARRIOR" -> Color.rgb(198, 155, 109); "PALADIN" -> Color.rgb(244, 140, 186); "HUNTER" -> Color.rgb(170, 211, 114)
        "ROGUE" -> Color.rgb(255, 244, 104); "PRIEST" -> Color.rgb(255, 255, 255); "DEATHKNIGHT" -> Color.rgb(196, 30, 58)
        "SHAMAN" -> Color.rgb(0, 112, 221); "MAGE" -> Color.rgb(63, 199, 235); "WARLOCK" -> Color.rgb(135, 136, 238)
        "MONK" -> Color.rgb(0, 255, 152); "DRUID" -> Color.rgb(255, 124, 10); "DEMONHUNTER" -> Color.rgb(163, 48, 201)
        "EVOKER" -> Color.rgb(51, 147, 127)
        else -> TEXT
    }

    /** The zone drawn from the addon's places, with the path walked and an arrow for you. */
    private fun renderMap() {
        val prefs = getPreferences(MODE_PRIVATE)
        val title = line("", TEXT, 17f, bold = true).apply { setPadding(dp(4), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        content.addView(title)
        val view = MapView(this).apply {
            close = prefs.getBoolean("mapClose", false)
            span = prefs.getFloat("mapSpan", 0.4f).toDouble()
            questAreas = prefs.getBoolean("mapQuests", true)
            onPlace = { p ->
                say(MapView.kindName(p.kind) + (if (p.label.isEmpty()) "" else ": ${p.label}") +
                    String.format(Locale.US, " (%.1f, %.1f)", p.x * 100, p.y * 100))
            }
        }
        val whole = chip("") {}
        fun zoomed() {
            prefs.edit().putBoolean("mapClose", view.close).putFloat("mapSpan", view.span.toFloat()).apply()
            whole.text = if (view.close) "Whole zone" else "Around me"
            view.invalidate()
        }
        view.onZoom = ::zoomed
        whole.setOnClickListener { view.close = !view.close; zoomed() }
        // Zoom in steps, like the minimap's + and −; a pinch on the map works too.
        fun zoom(by: Double) {
            val now = if (view.close) view.span else 1.0
            val next = (now * by).coerceIn(MapView.MIN_SPAN, 1.0)
            view.close = next < 0.95
            if (view.close) view.span = next
            zoomed()
        }
        val quests = chip(if (view.questAreas) "Quest areas on" else "Quest areas off") {}
        quests.setOnClickListener {
            view.questAreas = !view.questAreas
            prefs.edit().putBoolean("mapQuests", view.questAreas).apply()
            quests.text = if (view.questAreas) "Quest areas on" else "Quest areas off"
            view.invalidate()
        }
        val take = chip("Get zone picture") {
            // One key: the addon shows the zone's map art for a few seconds and the app takes it.
            pictureNote = "Asked the game for the zone picture…"
            updateMap()
            pressKey(ActionKeys.PICTURE) { err -> pictureNote = "Map picture not taken: $err"; updateMap() }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(chip("−") { zoom(1.4) }, LinearLayout.LayoutParams(dp(48), -2).apply { marginEnd = dp(6) })
        controls.addView(chip("+") { zoom(1 / 1.4) }, LinearLayout.LayoutParams(dp(48), -2).apply { marginEnd = dp(6) })
        controls.addView(whole, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        controls.addView(quests, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        controls.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        controls.addView(take)
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(6) })
        content.addView(view, LinearLayout.LayoutParams(-1, maxOf(dp(240), scroll.height - dp(110))))
        val note = line("", DIM, 12f).apply { setPadding(dp(4), dp(4), 0, 0) }
        content.addView(note)
        pictureLine = note
        pictureButton = take
        mapView = view
        mapTitle = title
        zoomed()
        updateMap()
    }

    private var pictureButton: TextView? = null

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
        val where = if (s?.x != null && s.y != null && s.x!! > 0) String.format(Locale.US, "   %.1f, %.1f", s.x!! * 100, s.y!! * 100) else ""
        mapTitle?.text = (map?.zone?.ifEmpty { null } ?: "Map ${s?.mapId ?: "unknown"}") + where
        // Without a picture yet, the button that takes one is the thing to tap.
        pictureButton?.let { b ->
            val none = s?.mapId != null && view.picture == null
            b.text = if (none) "Get zone picture" else "New picture"
            b.background = Theme.box(this, if (none) ACCENT else RAISED, 18)
            b.setTextColor(if (none) Color.BLACK else TEXT)
        }
        pictureLine?.text = pictureNote
        pictureLine?.visibility = if (pictureNote.isEmpty()) View.GONE else View.VISIBLE
        view.invalidate()
    }

    /** The chat lines, newest at the bottom, coloured like WoW's chat frame. */
    private fun renderChat() {
        if (chat.lines.isEmpty()) {
            content.addView(empty("No chat yet", "New lines in say, whispers, party, raid, guild and system messages show up here. Tap a line to whisper its sender."))
            return
        }
        val box = card().apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
        for (l in chat.lines.takeLast(100)) box.addView(chatLine(l))
        content.addView(box, cardParams())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatLine(l: ChatLine): TextView {
        val n = l.name
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
        // The channel quiet, the name bold, then what was said.
        val text = android.text.SpannableStringBuilder()
        fun add(part: String, vararg spans: Any) {
            val start = text.length
            text.append(part)
            for (span in spans) text.setSpan(span, start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (l.channel.isNotEmpty()) add("${l.channel}  ", android.text.style.ForegroundColorSpan(DIM))
        val who = when {
            n.isEmpty() -> ""
            l.kind == "whisper" -> "$n whispers: "
            l.kind == "whisper_to" -> "To $n: "
            l.kind == "emote" -> "$n "
            l.kind == "yell" -> "$n yells: "
            else -> "$n: "
        }
        if (who.isNotEmpty()) add(who, android.text.style.StyleSpan(Typeface.BOLD))
        add(l.text)
        val view = line("", colour, 15f).apply { this.text = text; setPadding(0, dp(3), 0, dp(3)) }
        // Tap a line: one key that opens the whisper box for its sender in the game.
        val key = chat.whisperSlot(l.sender)?.let { ActionKeys.forWhisper(it) } ?: return view
        view.setOnClickListener {
            pressKey(key, onDone = { say("Whisper to $n: type your message in the game") }) { err ->
                say("Whisper to $n: $err")
            }
        }
        return view
    }

    /** Key test: each button sends one key to the game's screen; `/thor keytest` in game prints what arrives. */
    private fun renderKeys() {
        content.addView(line("Type /thor keytest in the game, then tap each key once. Keys the game reports back turn green.", DIM).apply { setPadding(dp(4), 0, 0, dp(6)) })
        val result = line(keyResult, TEXT)
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (key in TEST_KEYS) {
            val got = key in gotKeys
            val b = TextView(this).apply {
                text = if (got) "✓ $key" else key
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(if (got) Color.BLACK else TEXT)
                background = Theme.box(context, if (got) Theme.GOOD else SURFACE, 10, if (got) null else STROKE)
                setOnClickListener {
                    pressKey(key, onDone = { keyResult = "Sent $key"; result.text = keyResult }) { err ->
                        keyResult = "$key: $err"
                        result.text = keyResult
                    }
                }
            }
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(52)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(grid)
        content.addView(result)
        content.addView(line(captureStats(), DIM).apply { setPadding(dp(4), dp(16), 0, 0) })
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
        val head = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(line("", GOLD, 18f, bold = true).apply { text = coins(s); setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val free = s.freeSlots; val total = s.totalSlots
        val slots = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        slots.addView(line(if (free != null && total != null) "$free of $total free" else "Slots unknown", TEXT, 14f).apply { setPadding(0, 0, 0, dp(4)) })
        if (free != null && total != null && total > 0) {
            val bar = Bar(this).apply {
                value = (total - free).toFloat() / total
                colour = if (free <= 3) Theme.BAD else if (free <= 8) Theme.WARN else Theme.GOOD
            }
            slots.addView(bar, LinearLayout.LayoutParams(dp(110), dp(6)))
        }
        head.addView(slots)
        content.addView(head, cardParams())

        val ids = s.items.map { it.itemId }
        val hint = iconHint(ids)
        content.addView(section((if (s.items.any { it.key != null } && bagsFresh) "Tap an item to use it" else "Items") + hint))
        val grid = GridLayout(this).apply { columnCount = 5; useDefaultMargins = false }
        for (item in s.items) {
            val known = names[item.itemId]
            val quality = known?.let { qualityColour(it.quality) }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(3), dp(6), dp(3), dp(4))
                background = Theme.box(context, SURFACE, 10, known?.takeIf { it.quality >= 2 }?.let { Theme.withAlpha(qualityColour(it.quality), 150) } ?: STROKE)
            }
            val pic = FrameLayout(this)
            pic.addView(icon(item.itemId, quality, 40), FrameLayout.LayoutParams(dp(40), dp(40)))
            if (item.count > 1) pic.addView(TextView(this).apply {
                text = item.count.toString()
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(1) })
            tile.addView(pic, LinearLayout.LayoutParams(dp(40), dp(40)))
            tile.addView(TextView(this).apply {
                text = known?.name ?: "#${item.itemId}"
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 10.5f
                setTextColor(quality ?: DIM)
                setPadding(0, dp(3), 0, 0)
            })
            // One tap, one key: the addon bound this key to "use the item in this slot".
            val key = item.key?.let { ActionKeys.forIndex(it) }
            val label = known?.name ?: "the item"
            tile.setOnClickListener { v ->
                if (!bagsFresh) {
                    say("These are your bags from before; tap Load from the game to update them before using items.")
                    return@setOnClickListener
                }
                if (key == null) {
                    say("$label has no tap key yet. Is the new addon loaded? /thor taps in game shows the keys.")
                    return@setOnClickListener
                }
                (v.background as GradientDrawable).setColor(PRESSED)
                v.postDelayed({ (v.background as GradientDrawable).setColor(SURFACE) }, 250)
                pressKey(key, onDone = { say("Sent $key for $label") }) { err ->
                    say("Couldn't use $label: $err")
                }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(92)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                // GridLayout lines tiles up by text baseline by default, which pushed
                // tiles with one line of name above their neighbours.
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(grid)
        when {
            s.freeSlots == null -> content.addView(empty("The bags haven't arrived yet", "Tap Load from the game above."))
            !bagsFresh -> content.addView(line("These are your bags from before; they update when the game sends them.", DIM).apply { setPadding(dp(4), dp(6), 0, 0) })
            s.items.isEmpty() -> content.addView(empty("Your bags are empty", ""))
        }
    }

    /** "  ·  N without icons" and a tap that asks the game for them, or "" when every icon is here. */
    private fun iconHint(ids: List<Int>): String {
        val missing = ids.distinct().count { !icons.has(it) }
        return if (missing == 0) "" else "  ·  $missing without icon"
    }

    /** An item's icon, or a quiet square with a question mark until the app has it. */
    private fun icon(itemId: Int?, quality: Int?, sizeDp: Int): View {
        val bitmap = itemId?.let { icons[it] }
        if (bitmap != null) return ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            clipToOutline = true
            background = Theme.box(context, RAISED, 6)
        }
        return TextView(this).apply {
            text = if (itemId == null) "" else "?"
            gravity = Gravity.CENTER
            textSize = sizeDp / 2.6f
            setTextColor(quality?.let { Theme.withAlpha(it, 160) } ?: DIM)
            background = Theme.box(context, RAISED, 6)
        }
    }

    /** One key: the addon shows the icons of your items for a few seconds and the app takes them. */
    private fun askForIcons() {
        say("Asked the game for the item icons…")
        pressKey(ActionKeys.ICONS) { err -> say("Item icons not taken: $err") }
    }

    /** A title over a group, with a "Get icons" tap when some icons are missing. */
    private fun section(title: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), 0, dp(4)) }
        row.addView(line(title, DIM, 13f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (title.contains("without icon")) row.addView(chip("Get icons") { askForIcons() })
        return row
    }

    /** A rounded box to group things in. */
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.box(context, SURFACE, 14, STROKE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }

    private fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, -2, w).apply { setMargins(dp(3), 0, dp(3), 0) }

    /** A small labelled value, like "Item level 24.5". */
    private fun fact(label: String, value: String, colour: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.box(context, SURFACE, 10, STROKE)
        setPadding(dp(10), dp(6), dp(10), dp(7))
        addView(line(label, DIM, 11f).apply { setPadding(0, 0, 0, 0) })
        addView(line(value, colour, 16f, bold = true).apply { setPadding(0, 0, 0, 0); maxLines = 1 })
    }

    /** A rounded button for the few things to tap that are not items. */
    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        background = Theme.box(context, RAISED, 18)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    /** What a tab shows while it has nothing yet. */
    private fun empty(title: String, detail: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(28), dp(16), dp(28))
        addView(line(title, TEXT, 16f, bold = true).apply { gravity = Gravity.CENTER })
        if (detail.isNotEmpty()) addView(line(detail, DIM, 13f).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
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
        if (s.copper == null) "?" else "${s.gold}g ${s.silver}s ${s.copperPart}c"

    /** The money with each coin in its colour, like the game's bag frame. */
    private fun coins(s: GameState): CharSequence {
        if (s.copper == null) return "Gold unknown"
        val text = android.text.SpannableStringBuilder()
        fun add(v: Long?, unit: String, colour: Int) {
            val start = text.length
            text.append(String.format(Locale.US, "%,d", v ?: 0)).append(unit).append("  ")
            text.setSpan(android.text.style.ForegroundColorSpan(colour), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if ((s.gold ?: 0) > 0) add(s.gold, "g", GOLD)
        if ((s.gold ?: 0) > 0 || (s.silver ?: 0) > 0) add(s.silver, "s", Theme.SILVER)
        add(s.copperPart, "c", Theme.COPPER)
        return text.trimEnd()
    }

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
        /** Message kinds kept across restarts. */
        val KEPT = setOf("TS1|", "TB1|", "TM1|", "TP1|", "TQ1|")
        /** The paper doll's slots, left column then right, as Character.lua sends them. */
        val GEAR_SLOTS = listOf(1, 2, 3, 15, 5, 4, 19, 9, 10, 6, 7, 8, 11, 12, 13, 14, 16, 17)
        /** Must match ns.TestKeys in addon/ThorCompanion/KeyTest.lua. */
        val TEST_KEYS = listOf("CTRL", "ALT").flatMap { mod -> (1..12).map { "$mod-F$it" } } - "ALT-F4" +
            listOf("F9", "SHIFT-F9", "CTRL-SHIFT-F9", "NUMPAD5")
    }
}
