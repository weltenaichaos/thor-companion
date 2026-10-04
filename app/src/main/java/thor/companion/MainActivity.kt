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
import thor.companion.strip.Cooldowns
import thor.companion.strip.ItemUse
import thor.companion.strip.IconPicture
import thor.companion.strip.ItemNames
import thor.companion.strip.MapPicture
import thor.companion.strip.LevelPlan
import thor.companion.strip.PartAssembler
import thor.companion.strip.Quest
import thor.companion.strip.QuestLog
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

    private enum class Panel(val title: String) { BAGS("Bags"), CHARACTER("Character"), QUESTS("Quests"), MAP("Map"), CHAT("Chat") }

    /** The chat's own tabs: which lines each shows, and the key that answers there. */
    private enum class ChatTab(val title: String, val kinds: Set<String>?) {
        ALL("All", null),
        GROUP("Group", setOf("party", "raid", "instance")),
        WHISPERS("Whispers", setOf("whisper", "whisper_to", "bnwhisper", "bnwhisper_to")),
        GUILD("Guild", setOf("guild")),
        GENERAL("General", setOf("say", "yell", "emote", "channel", "system")),
    }

    private lateinit var screen: TopScreen
    private lateinit var names: NameStore
    private lateinit var icons: IconStore
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var loadButton: TextView
    private lateinit var content: LinearLayout
    /** Above and below the scrolling part: the chat's tabs and answer keys, item details. */
    private lateinit var toolbar: LinearLayout
    private lateinit var footer: LinearLayout
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
    private var quests: QuestLog? = null
    private var chatTab = ChatTab.ALL
    /** Per chat tab, the number of the last line seen there (see ChatLog.total). */
    private val chatSeen = HashMap<ChatTab, Int>()

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
                setOnClickListener { mapHighlight = null; show(p) }
            }
            tabs[p] = b
            header.addView(b, LinearLayout.LayoutParams(0, dp(44), if (p == Panel.CHARACTER) 1.25f else 1f))
        }
        // The status line: a dot that says whether the game's data is coming in, what happened
        // last, and the one key that asks the game for everything when something is missing.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), 0, dp(6))
        }
        statusDot = View(this).apply {
            background = Theme.box(context, Theme.WARN, 5)
            // In case the app reads the wrong screen: long-press the dot to try the next one.
            setOnLongClickListener { nextDisplay(); true }
        }
        status = TextView(this).apply {
            setTextColor(DIM)
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            // Long-press: the battery check (how much reading the game's screen costs).
            setOnLongClickListener { say(captureStats()); true }
        }
        loadButton = chip("Load from the game") {
            pressKey(ActionKeys.REFRESH, onDone = { say("Asked the game for everything again…") }) { err -> say("Couldn't ask the game: $err") }
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(loadButton)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header)
        root.addView(statusRow)
        root.addView(toolbar)
        scroll = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer)
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
        closeCompose()
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
                m.startsWith("TL1|") -> quests = QuestLog.parse(m)
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
                    message.startsWith("TE1|") -> message.split('|').let { f ->
                        f.getOrNull(1)?.toIntOrNull()?.let { n -> chatBox = Triple(n, f.getOrNull(2) == "open", f.getOrNull(3).orEmpty()) }
                    }
                    message.startsWith("TU1|") -> ItemUse.parse(message)?.let { runOnUiThread { onUse(it) } }
                    message.startsWith("TC2|") -> Cooldowns.parse(message)?.let { runOnUiThread { onCooldowns(it) } }
                    message.startsWith("TL1|") -> QuestLog.parse(message)?.let { runOnUiThread { onQuests(it) } }
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
        if (parsed != null && message.startsWith("TS1|")) {
            // Bags kept from before a restart are right when the addon's checksum of its last bags says so.
            val kept = lastMessages["TB1|"]
            if (!bagsFresh && parsed.bagHash != null && kept != null && GameState.hash(kept) == parsed.bagHash) {
                bagsFresh = true
                if (panel == Panel.BAGS) render()
            }
            // The path walked is per game session: a new login or /reload starts it afresh.
            val prefs = getPreferences(MODE_PRIVATE)
            if (parsed.session != null && parsed.session != prefs.getString("trailSession", null)) {
                prefs.edit().putString("trailSession", parsed.session).apply()
                trail.paths.clear()
                trailUnsaved = 1
                saveTrail()
            }
        }
        if (parsed == null) say("Connected, but the addon sent something unexpected: ${message.take(80)}") else connected()
        if (parsed == null || parsed == state) return
        val moved = parsed.copy(x = state?.x, y = state?.y, facing = state?.facing) == state
        state = parsed
        val id = parsed.mapId
        if (id != null && parsed.x != null && parsed.y != null && trail.add(id, parsed.x!!, parsed.y!!)) {
            if (++trailUnsaved >= 30) saveTrail()
        }
        // Walking only moves the arrow on the map; the other tabs don't show where you are.
        if (!moved) render() else if (panel == Panel.MAP && mapView != null) updateMap()
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
        connected()
        if (c == character && g == gear) return
        character = c
        gear = g
        if (panel == Panel.CHARACTER) render()
    }

    private fun onMap(map: ZoneMap) {
        connected()
        if (map == zoneMap) return
        zoneMap = map
        if (panel == Panel.MAP) render()
    }

    private fun onChat(message: String) {
        connected()
        if (chat.add(message) == true && panel == Panel.CHAT) render()
    }

    /** Bag items on cooldown: when each is ready again (uptime ms). */
    private var readyAt: Map<Int, Long> = emptyMap()

    private fun onCooldowns(left: Map<Int, Long>) {
        connected()
        val now = SystemClock.uptimeMillis()
        val next = left.mapValues { now + it.value * 1000 }
        // The same cooldowns again (a resend) give the same times give or take a second.
        if (next.keys == readyAt.keys && next.all { (id, t) -> Math.abs(t - (readyAt[id] ?: 0)) < 2000 }) return
        readyAt = next
        if (panel == Panel.BAGS) render()
    }

    private fun cooldownLeft(itemId: Int): Long = ((readyAt[itemId] ?: 0) - SystemClock.uptimeMillis()) / 1000

    /** The last tap on a bag item, until the addon says what came of it. */
    private var tapped: Pair<Int, String>? = null
    private var lastUse = -1

    private fun onUse(use: ItemUse) {
        if (use.n == lastUse) return
        lastUse = use.n
        val label = names[use.itemId]?.name ?: tapped?.second ?: "the item"
        tapped = null
        say(when (use.result) {
            "ok" -> "Used $label"
            "nouse" -> "$label can't be used: it does nothing when used (long-press it for details)"
            else -> "Couldn't use $label: ${use.result}"
        })
    }

    private fun onQuests(log: QuestLog) {
        connected()
        if (log == quests) return
        quests = log
        if (panel == Panel.QUESTS || panel == Panel.CHARACTER) render()
    }

    private fun onNames(changed: Boolean) {
        connected()
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
        if (p != Panel.CHAT) closeCompose()
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
        toolbar.removeAllViews()
        // While you type a chat message, the box below stays as it is.
        if (composing == null) footer.removeAllViews()
        mapView = null
        updateLoadButton()
        updateBadges()
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
            Panel.QUESTS -> renderQuests()
        }
    }

    /** When the status line last said something other than "Connected", which then stays for a while. */
    private var saidAt = 0L

    /** Data came in: the dot goes green, and the line says so unless it is still showing something you should read. */
    private fun connected() {
        if (SystemClock.uptimeMillis() - saidAt > NOTICE_MS) say("Connected", notice = false) else say(status.text.toString(), notice = false)
    }

    /** The status line, with its dot: green while the game's data comes in, red when it stopped, amber before it ever came. */
    private fun say(text: String, notice: Boolean = true) {
        if (notice) saidAt = SystemClock.uptimeMillis()
        status.text = text
        val since = SystemClock.uptimeMillis() - frameAt
        val colour = when {
            frameAt == 0L -> Theme.WARN
            since < 5000 -> Theme.GOOD
            else -> Theme.BAD
        }
        (statusDot.background as GradientDrawable).setColor(colour)
        updateLoadButton()
        updateBadges()
    }

    /** Load from the game shows only while something is missing or old: then one tap asks the addon for everything. */
    private fun updateLoadButton() {
        val missing = state == null || state?.freeSlots == null || !bagsFresh || character == null || gear.isEmpty()
        loadButton.visibility = if (missing) View.VISIBLE else View.GONE
    }

    /** Name, level, experience, item level, stats and what you wear. */
    private fun renderCharacter(s: GameState) {
        val c = character
        val classColour = c?.classFile?.let { classColour(it) } ?: TEXT
        val head = card()
        head.addView(line(s.name, classColour, 24f, bold = true).apply { setPadding(0, 0, 0, 0) })
        head.addView(line(listOfNotNull("Level ${s.level ?: "?"}", c?.race, c?.className).joinToString(" "), TEXT, 15f).apply { setPadding(0, dp(2), 0, 0) })
        c?.guild?.let { head.addView(line("<$it>", Theme.GOOD, 13f).apply { setPadding(0, dp(2), 0, 0) }) }
        content.addView(head, cardParams())
        levelCard()?.let { content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }

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
            if (g != null) {
                // Worn items do nothing on a tap, so a tap shows their details too.
                tile.setOnClickListener { showDetails(g.itemId) }
                tile.setOnLongClickListener { showDetails(g.itemId); true }
            }
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
            highlight = mapHighlight
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

    /**
     * The chat, one kind at a time if you like: its own tabs on top (with how many new
     * lines each has), the lines newest at the bottom, coloured like WoW's chat frame,
     * and below the key that opens the game's chat box to answer there.
     */
    private fun renderChat() {
        val tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        for (t in ChatTab.entries) {
            val unread = if (t == chatTab) 0 else unread(t)
            val label = t.title + if (unread > 0) "  $unread" else ""
            val chip = chip(label) { chatTab = t; render() }
            if (t == chatTab) { chip.background = Theme.box(this, ACCENT, 18); chip.setTextColor(Color.BLACK) }
            tabsRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        toolbar.addView(tabsRow)
        markSeen(chatTab)
        updateBadges()

        val shown = chat.lines.filter { chatTab.kinds == null || it.kind in chatTab.kinds!! }
        if (shown.isEmpty()) {
            content.addView(empty(if (chat.lines.isEmpty()) "No chat yet" else "Nothing here yet",
                "New lines show up here. Tap a line to whisper its sender; the buttons below open the game's chat box to answer."))
        } else {
            val box = card().apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
            for (l in shown.takeLast(100)) box.addView(chatLine(l))
            content.addView(box, cardParams())
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        if (composing != null) return
        // Answer: type here, with the keyboard on this screen; Send opens the game's chat box and puts it in.
        val answers = when (chatTab) {
            ChatTab.ALL -> listOf("Group" to GROUP, "Guild" to "/g ", "Say" to "/s ", "Reply" to "/r ")
            ChatTab.GROUP -> listOf("Write to your group" to GROUP)
            ChatTab.WHISPERS -> listOf("Reply to the last whisper" to "/r ")
            ChatTab.GUILD -> listOf("Write to your guild" to "/g ")
            ChatTab.GENERAL -> listOf("Say something" to "/s ")
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        for ((label, command) in answers) {
            row.addView(chip(label) { compose(label, command) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        footer.addView(row)
    }

    private fun chatSeq(i: Int) = chat.total - chat.lines.size + i + 1

    /** New lines in [t] since it was last open (or All was). */
    private fun unread(t: ChatTab): Int {
        val seen = maxOf(chatSeen[t] ?: 0, chatSeen[ChatTab.ALL] ?: 0)
        var n = 0
        chat.lines.forEachIndexed { i, l -> if (chatSeq(i) > seen && (t.kinds == null || l.kind in t.kinds)) n++ }
        return n
    }

    private fun markSeen(t: ChatTab) {
        chatSeen[t] = chat.total
    }

    /**
     * Little marks on the tabs for what needs a look: new whispers and group, guild
     * lines in Chat, quests ready to turn in, bags almost full, gear almost broken.
     */
    private fun updateBadges() {
        if (tabs.isEmpty()) return
        val s = state
        val chatNew = if (panel == Panel.CHAT) 0 else unread(ChatTab.WHISPERS) + unread(ChatTab.GROUP) + unread(ChatTab.GUILD)
        val ready = quests?.readyCount ?: 0
        val full = s?.freeSlots?.let { it <= 2 } == true
        val broken = gear.any { (it.durability ?: 100) <= 20 }
        for ((p, b) in tabs) {
            val text = android.text.SpannableStringBuilder(p.title)
            fun badge(mark: String, colour: Int) {
                val start = text.length
                text.append("  ").append(mark)
                text.setSpan(android.text.style.ForegroundColorSpan(if (p == panel) Color.BLACK else colour), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            when (p) {
                Panel.CHAT -> if (chatNew > 0) badge(chatNew.toString(), Color.rgb(255, 128, 255))
                Panel.QUESTS -> if (ready > 0) badge(ready.toString(), ACCENT)
                Panel.BAGS -> if (full) badge("●", Theme.BAD)
                Panel.CHARACTER -> if (broken) badge("●", Theme.BAD)
                Panel.MAP -> {}
            }
            b.text = text
        }
    }

    /** The quest log: quests in this zone first, ready ones marked; tap one to see it on the map. */
    private fun renderQuests() {
        val log = quests
        if (log == null) {
            content.addView(empty("No quests yet", "They arrive with the next update from the game."))
            return
        }
        levelCard()?.let { content.addView(it, cardParams()) }
        if (log.quests.isEmpty()) {
            content.addView(empty("Your quest log is empty", ""))
            return
        }
        for ((title, list) in listOf("Ready to turn in" to log.quests.filter { it.ready },
            "In this zone" to log.quests.filter { it.here && !it.ready }, "Elsewhere" to log.quests.filter { !it.here && !it.ready })) {
            if (list.isEmpty()) continue
            content.addView(section(title))
            for (q in list) content.addView(questCard(q), cardParams())
        }
    }

    private fun questCard(q: Quest): View {
        val box = card().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(line(q.title, if (q.ready) ACCENT else TEXT, 15f, bold = true).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (q.xp > 0) top.addView(line(String.format(Locale.US, "%,d XP", q.xp), Theme.XP_TEXT, 12f).apply { setPadding(dp(8), 0, 0, 0) })
        box.addView(top)
        if (q.ready) box.addView(line("Ready to turn in", Theme.GOOD, 12f).apply { setPadding(0, dp(2), 0, 0) })
        for (o in q.objectives) {
            box.addView(line((if (o.done) "✓ " else "• ") + o.text, if (o.done) DIM else TEXT, 13f).apply { setPadding(dp(4), dp(1), 0, 0) })
        }
        box.setOnClickListener {
            // Its area on the map, if the game shows one in this zone.
            mapHighlight = q.title
            show(Panel.MAP)
            say("${q.title}: marked with a white ring on the map, where the game shows it in this zone")
        }
        return box
    }

    /** Quest whose area the map marks (after a tap on it in the Quests tab). */
    private var mapHighlight: String? = null

    /**
     * The level checker: how far to the next level, the rested experience, how many
     * kills that is (the last kill's experience, with the rested bonus), and how much
     * the quests ready to turn in give, so you see at a glance when to go back.
     */
    private fun levelCard(): View? {
        val c = character ?: return null
        val xp = c.xp ?: return null
        val xpMax = c.xpMax ?: return null
        val log = quests
        val rested = c.rested ?: 0
        val kill = log?.lastKill ?: 0
        val plan = LevelPlan.of(xp, xpMax, rested, kill, log?.readyXp ?: 0) ?: return null
        val level = state?.level
        val box = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(line(if (level != null) "Level $level → ${level + 1}" else "Next level", TEXT, 17f, bold = true).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(line(String.format(Locale.US, "%.1f%%", plan.percent), ACCENT, 17f, bold = true).apply { setPadding(0, 0, 0, 0) })
        box.addView(top)
        box.addView(Bar(this).apply {
            value = xp.toFloat() / xpMax
            second = (xp + rested).toFloat() / xpMax
        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(4) })
        fun row(label: String, value: String, colour: Int = TEXT) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(line(label, DIM, 14f).apply { setPadding(0, dp(2), 0, dp(2)) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(line(value, colour, 14f, bold = true).apply { setPadding(0, dp(2), 0, dp(2)) })
            box.addView(r)
        }
        row("XP", String.format(Locale.US, "%,d / %,d  (%,d to go)", xp, xpMax, plan.toGo))
        row("Rested", String.format(Locale.US, "%,d", rested), Theme.RESTED_TEXT)
        row("Kills", plan.kills?.let { String.format(Locale.US, "~%d  (~%,d XP each)", it, kill) } ?: "kill something to find out")
        val ready = log?.readyCount ?: 0
        row("Quests ready ($ready)", String.format(Locale.US, "%,d XP", log?.readyXp ?: 0), if (ready > 0) ACCENT else TEXT)
        val advice = when {
            plan.questsEnough -> "Turn in your ready quests to ding!"
            ready > 0 && plan.killsAfterQuests != null -> "Kill ~${plan.killsAfterQuests}, then turn in to ding"
            plan.kills != null -> "Kill ~${plan.kills} to ding"
            else -> ""
        }
        if (advice.isNotEmpty()) box.addView(line(advice, Theme.GOOD, 15f, bold = true).apply { setPadding(0, dp(6), 0, 0) })
        return box
    }

    /** An item's details under the list, after a long-press on it; a tap closes them. */
    private fun showDetails(itemId: Int, count: Int = 1, usable: Boolean = false) {
        footer.removeAllViews()
        val known = names[itemId]
        val box = card().apply { background = Theme.box(context, RAISED, 14, known?.let { Theme.withAlpha(qualityColour(it.quality), 150) } ?: STROKE) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon(itemId, known?.let { qualityColour(it.quality) }, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(line(known?.name ?: "Item #$itemId", known?.let { qualityColour(it.quality) } ?: TEXT, 16f, bold = true).apply { setPadding(0, 0, 0, 0) })
        known?.type?.takeIf { it.isNotEmpty() }?.let { words.addView(line(it, DIM, 13f).apply { setPadding(0, 0, 0, 0) }) }
        top.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(top)
        val facts = listOfNotNull(
            known?.itemLevel?.takeIf { it > 0 }?.let { "Item level $it" },
            known?.requiredLevel?.takeIf { it > 1 }?.let { "Requires level $it" },
        )
        if (facts.isNotEmpty()) box.addView(line(facts.joinToString("  ·  "), TEXT, 13f).apply { setPadding(0, dp(6), 0, 0) })
        val price = known?.sellPrice ?: 0
        box.addView(line("", TEXT, 13f).apply {
            text = when {
                known == null -> "No details yet: they arrive with the item's name."
                price <= 0 -> "Can't be sold to a vendor"
                count > 1 -> android.text.SpannableStringBuilder("Sells for ").append(coins(price)).append("  ·  all $count: ").append(coins(price * count))
                else -> android.text.SpannableStringBuilder("Sells for ").append(coins(price))
            }
            setPadding(0, dp(2), 0, 0)
        })
        box.addView(line(if (usable) "Tap the item to use it. Tap here to close." else "Tap here to close.", DIM, 12f).apply { setPadding(0, dp(6), 0, 0) })
        box.setOnClickListener { footer.removeAllViews() }
        footer.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
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
        // Tap a line: write a whisper to its sender.
        if (l.sender.isEmpty() || l.sender == "?" || l.kind == "system") return view
        view.setOnClickListener { compose("Whisper to $n", "/w ${l.sender} ") }
        return view
    }

    /** The message being written, and to whom: (label, the chat command it starts with). */
    private var composing: Pair<String, String>? = null

    /** Stands for your group's command, which the addon says when the chat box opens ("/p ", "/raid " or "/i "). */
    private val GROUP = "group"

    /**
     * A text field with this screen's keyboard, to write a chat message. While it is
     * open the app takes the keyboard (the game gets it back after); Send then
     * presses the game's own Open Chat key (like Enter), waits until the addon says
     * the box is open, types the chat command and the message and presses Enter:
     * one tap on Send is one message, as if you had typed it in the game.
     */
    private fun compose(label: String, command: String) {
        if (panel != Panel.CHAT) show(Panel.CHAT)
        composing = label to command
        footer.removeAllViews()
        val field = android.widget.EditText(this).apply {
            hint = "$label…"
            setHintTextColor(DIM)
            setTextColor(TEXT)
            textSize = 15f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            background = Theme.box(context, SURFACE, 12, ACCENT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(255))  // the game's limit
        }
        fun send() {
            val text = field.text.toString().trim()
            closeCompose()
            if (text.isNotEmpty()) sendChat(label, command, text)
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(chip("Send") { send() }.apply { background = Theme.box(context, ACCENT, 18); setTextColor(Color.BLACK) })
        row.addView(chip("✕") { closeCompose() }, LinearLayout.LayoutParams(dp(44), -2).apply { marginStart = dp(4) })
        footer.addView(row)
        // The keyboard needs a window that can take focus; the app's normally can't (so the game keeps the controller).
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        field.requestFocus()
        field.post {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeCompose() {
        if (composing == null) return
        composing = null
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(footer.windowToken, 0)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (panel == Panel.CHAT) render() else footer.removeAllViews()
    }

    /** The addon's last word on the chat box: its count, whether it opened, and your group's command. */
    @Volatile private var chatBox: Triple<Int, Boolean, String>? = null

    private fun sendChat(label: String, command: String, text: String) {
        val target = gameDisplay()
        val before = chatBox?.first
        lastKeyAt = SystemClock.uptimeMillis()
        say("$label: sending…")
        keys.execute {
            val err = KeySender.send(this, target, ActionKeys.OPEN_CHAT)
            if (err != null && !err.startsWith("(focus")) return@execute runOnUiThread { say("$label not sent: $err") }
            // Wait for the addon to see the box open; never type into the game without it.
            val until = SystemClock.uptimeMillis() + 3000
            while (SystemClock.uptimeMillis() < until && chatBox?.first == before) {
                lastKeyAt = SystemClock.uptimeMillis()
                SystemClock.sleep(50)
            }
            val box = chatBox
            if (box == null || box.first == before || !box.second) {
                return@execute runOnUiThread { say("$label not sent: the game's chat box didn't open (a game menu open, or the addon older than v40?)") }
            }
            val prefix = if (command == GROUP) box.third.ifEmpty { "/p " } else command
            val typed = KeySender.type(this, target, prefix + text)
            if (typed != null && !typed.startsWith("(focus")) return@execute runOnUiThread { say("$label not sent: $typed") }
            KeySender.send(this, target, "ENTER")
            runOnUiThread { say("$label: sent") }
        }
    }

    /** Battery check: how often and how long the app read the game's screen in the last minute. */
    private fun captureStats(): String {
        val (count, total) = synchronized(captures) { captures.size to captures.sumOf { it[1] } }
        if (count == 0) return "Battery check: the game's screen hasn't been read in the last minute."
        return "Battery check: read the game's screen $count times in the last minute, " +
            "${total / count} ms each (busy ${total * 100 / 60_000}% of the time)."
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

    /** Redraws the bags now and then while something there is on cooldown, so the time left counts down. */
    private val cooldownTick = Runnable { if (panel == Panel.BAGS) render() }

    private fun renderBags(s: GameState) {
        content.removeCallbacks(cooldownTick)
        if (s.items.any { cooldownLeft(it.itemId) > 0 }) content.postDelayed(cooldownTick, 15_000)
        val head = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(line("", GOLD, 18f, bold = true).apply { text = s.copper?.let { coins(it) } ?: "Gold unknown"; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
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
            val wait = cooldownLeft(item.itemId)
            if (wait > 0) {
                // On cooldown: the icon darkened with the time left on it, like the game's own.
                pic.addView(TextView(this).apply {
                    text = Cooldowns.left(wait).replace(" min", "m").replace(" h", "h").replace(" s", "s").substringBefore(' ')
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = Theme.box(context, Color.argb(170, 0, 0, 0), 6)
                }, FrameLayout.LayoutParams(-1, -1))
            }
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
            tile.setOnLongClickListener { showDetails(item.itemId, item.count, usable = key != null); true }
            tile.setOnClickListener { v ->
                if (!bagsFresh) {
                    say("These are your bags from before; tap Load from the game to update them before using items.")
                    return@setOnClickListener
                }
                if (key == null) {
                    say("$label has no tap key yet. Is the new addon loaded? /thor taps in game shows the keys.")
                    return@setOnClickListener
                }
                val wait = cooldownLeft(item.itemId)
                if (wait > 0) {
                    say("$label is on cooldown: ready in ${Cooldowns.left(wait)}")
                    return@setOnClickListener
                }
                (v.background as GradientDrawable).setColor(PRESSED)
                v.postDelayed({ (v.background as GradientDrawable).setColor(SURFACE) }, 250)
                val tap = item.itemId to label
                tapped = tap
                pressKey(key, onDone = {
                    say("Using $label…")
                    // The addon answers within a second or so; silence means the key didn't reach the item.
                    content.postDelayed({
                        if (tapped === tap) {
                            tapped = null
                            say("The game didn't react to $label's key ($key). Is the addon v38 loaded, and tap-to-use on (/thor taps)?")
                        }
                    }, 3000)
                }) { err ->
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
    private fun coins(copper: Long): CharSequence {
        val gold = copper / 10000; val silver = copper / 100 % 100
        val text = android.text.SpannableStringBuilder()
        fun add(v: Long?, unit: String, colour: Int) {
            val start = text.length
            text.append(String.format(Locale.US, "%,d", v ?: 0)).append(unit).append("  ")
            text.setSpan(android.text.style.ForegroundColorSpan(colour), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (gold > 0) add(gold, "g", GOLD)
        if (gold > 0 || silver > 0) add(silver, "s", Theme.SILVER)
        add(copper % 100, "c", Theme.COPPER)
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
        /** How long a message in the status line stays before "Connected" replaces it. */
        const val NOTICE_MS = 6000L
        val KEPT = setOf("TS1|", "TB1|", "TM1|", "TP1|", "TQ1|", "TL1|")
        /** The paper doll's slots, left column then right, as Character.lua sends them. */
        val GEAR_SLOTS = listOf(1, 2, 3, 15, 5, 4, 19, 9, 10, 6, 7, 8, 11, 12, 13, 14, 16, 17)
    }
}
