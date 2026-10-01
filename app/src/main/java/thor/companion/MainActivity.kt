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
import thor.companion.strip.GameState
import thor.companion.strip.ItemNames
import thor.companion.strip.StripDecoder
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
    private var lastSeq = -1

    @Volatile private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        show(Panel.BAGS)
        status.text = "Looking for the game…"
    }

    override fun onStart() {
        super.onStart()
        worker = Thread(::loop, "strip-reader").apply { isDaemon = true; start() }
    }

    override fun onStop() {
        worker = null
        super.onStop()
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
        while (worker === Thread.currentThread()) {
            val t0 = SystemClock.uptimeMillis()
            val px = screen.capture()
            val result = if (px == null) null else StripDecoder.decode(px)
            val frame = (result as? StripDecoder.Result.Ok)?.frame?.takeIf { it.crcOk }
            if (frame != null) {
                misses = 0
                val page = ItemNames.parse(frame.payload)
                if (page != null) {
                    val changed = names.addAll(page)
                    runOnUiThread { onNames(changed) }
                } else {
                    val parsed = GameState.parse(frame.payload)
                    runOnUiThread { onFrame(frame.seq, parsed) }
                }
            } else if (++misses == 4) {
                val why = when {
                    px == null -> "the capture failed"
                    result is StripDecoder.Result.Failed -> result.reason
                    else -> "the data was damaged"
                }
                runOnUiThread { status.text = "No data from the addon ($why). Is the game open with ThorCompanion on?" }
            }
            // The addon changes the strip every half second, alternating state and
            // names, so read at least twice as often to see every frame.
            SystemClock.sleep((200 - (SystemClock.uptimeMillis() - t0)).coerceIn(30, 200))
        }
    }

    private fun onFrame(seq: Int, parsed: GameState?) {
        status.text = if (parsed == null) "Connected, but the addon sent something unexpected." else "Connected"
        if (seq == lastSeq && parsed == state) return
        lastSeq = seq
        state = parsed
        if (panel != Panel.KEYS) render()
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
            Panel.MAP -> {
                content.addView(line("Map ${s.mapId ?: "unknown"}", TEXT, 22f, bold = true))
                if (s.x != null && s.y != null) {
                    content.addView(line(String.format(Locale.US, "%.1f, %.1f", s.x!! * 100, s.y!! * 100), TEXT, 18f))
                }
                content.addView(line("The map picture comes in a later version.", DIM))
            }
            Panel.CHAT -> content.addView(line("Chat comes in a later version.", DIM))
            Panel.KEYS -> {}
        }
    }

    /** Key test: each button sends one key to the game's screen; `/thor keytest` in game prints what arrives. */
    private fun renderKeys() {
        content.addView(line("Type /thor keytest in the game, then tap a key. The game's chat should say which key it got.", DIM))
        val result = line("", TEXT)
        val grid = GridLayout(this).apply { columnCount = 3 }
        for (key in TEST_KEYS) {
            val b = Button(this).apply {
                text = key
                isAllCaps = false
                setTextColor(TEXT)
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(CARD) }
                setOnClickListener {
                    val target = gameDisplay()
                    Thread {
                        val err = KeySender.send(target, key)
                        runOnUiThread { result.text = if (err == null) "Sent $key to screen $target" else "$key: $err" }
                    }.start()
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
        val TEST_KEYS = listOf("F9", "CTRL-F9", "SHIFT-F9", "ALT-F9", "CTRL-SHIFT-F9", "NUMPAD5", "CTRL-NUMPAD5")
        val BG = Color.rgb(16, 18, 22)
        val CARD = Color.rgb(36, 40, 48)
        val TEXT = Color.rgb(230, 232, 236)
        val DIM = Color.rgb(140, 146, 156)
        val ACCENT = Color.rgb(255, 196, 64)
        val GOLD = Color.rgb(255, 210, 90)
    }
}
