package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatLogTest {

    @Test
    fun keepsLinesOnceInOrder() {
        assertEquals(null, ChatFile.parse("10/7 20:33:01.000  Left Channel: |Hchannel:1140896|h[(null)]|h"))
        assertEquals(ChatLine("system", "", "Joined Channel: [2. Trade - City]"), ChatFile.parse("10/7 20:33:01.000  Joined Channel: |Hchannel:CHANNEL:2|h[2. Trade - City]|h"))
        assertEquals(ChatLine("say", "Bo", "got [Linen Cloth]"), ChatFile.parse("10/7 20:33:02.000  Bo says: got |cff9d9d9d|Hitem:2589::::|h[Linen Cloth]|h|r"))
        val log = ChatLog()
        assertEquals(true, log.add("TH1|77\tMoria-Deathknell,Owarh-Realm,,\n1\tsay\tMoria-Deathknell\t\tHello [Hearthstone] |pipe\n2\twhisper\tOwarh-Realm\t\tpsst"))
        // A refresh sends the same lines again, plus a new one.
        assertEquals(true, log.add("TH1|77\tMoria-Deathknell,Owarh-Realm,,\n2\twhisper\tOwarh-Realm\t\tpsst\n3\tsystem\t\t\tYou are now Away."))
        assertEquals(false, log.add("TH1|77\tMoria-Deathknell,Owarh-Realm,,\n3\tsystem\t\t\tYou are now Away."))
        assertEquals(
            listOf(
                ChatLine("say", "Moria-Deathknell", "Hello [Hearthstone] |pipe"),
                ChatLine("whisper", "Owarh-Realm", "psst"),
                ChatLine("system", "", "You are now Away."),
            ),
            log.lines,
        )
        assertEquals("Moria", log.lines[0].name)
    }

    @Test
    fun channelLinesKeepTheWholeName() {
        assertEquals(null, ChatFile.parse("10/7 20:33:01.000  Left Channel: |Hchannel:1140896|h[(null)]|h"))
        assertEquals(ChatLine("system", "", "Joined Channel: [2. Trade - City]"), ChatFile.parse("10/7 20:33:01.000  Joined Channel: |Hchannel:CHANNEL:2|h[2. Trade - City]|h"))
        assertEquals(ChatLine("say", "Bo", "got [Linen Cloth]"), ChatFile.parse("10/7 20:33:02.000  Bo says: got |cff9d9d9d|Hitem:2589::::|h[Linen Cloth]|h|r"))
        val log = ChatLog()
        log.add("TH1|5\tTrader-Some-Realm\n1\tchannel\tTrader-Some-Realm\tTrade\tWTS stuff")
        val line = log.lines.single()
        assertEquals("Trade", line.channel)
        assertEquals("Trader", line.name)
        assertEquals(1, log.whisperSlot(line.sender))
    }

    @Test
    fun offersWhispersOnlyForNamesWithAKeyNow() {
        assertEquals(null, ChatFile.parse("10/7 20:33:01.000  Left Channel: |Hchannel:1140896|h[(null)]|h"))
        assertEquals(ChatLine("system", "", "Joined Channel: [2. Trade - City]"), ChatFile.parse("10/7 20:33:01.000  Joined Channel: |Hchannel:CHANNEL:2|h[2. Trade - City]|h"))
        assertEquals(ChatLine("say", "Bo", "got [Linen Cloth]"), ChatFile.parse("10/7 20:33:02.000  Bo says: got |cff9d9d9d|Hitem:2589::::|h[Linen Cloth]|h|r"))
        val log = ChatLog()
        log.add("TH1|5\tA,B,,\n1\tsay\tA\t\tone\n2\tsay\tB\t\ttwo")
        assertEquals(2, log.whisperSlot("B"))
        // B's key went to C: B's old line can no longer be tapped, C's can.
        assertTrue(log.add("TH1|5\tA,C,,\n3\tsay\tC\t\tthree")!!)
        assertNull(log.whisperSlot("B"))
        assertEquals(2, log.whisperSlot("C"))
        assertNull(log.whisperSlot(""))
    }

    @Test
    fun newSessionStartsNumbersOver() {
        assertEquals(null, ChatFile.parse("10/7 20:33:01.000  Left Channel: |Hchannel:1140896|h[(null)]|h"))
        assertEquals(ChatLine("system", "", "Joined Channel: [2. Trade - City]"), ChatFile.parse("10/7 20:33:01.000  Joined Channel: |Hchannel:CHANNEL:2|h[2. Trade - City]|h"))
        assertEquals(ChatLine("say", "Bo", "got [Linen Cloth]"), ChatFile.parse("10/7 20:33:02.000  Bo says: got |cff9d9d9d|Hitem:2589::::|h[Linen Cloth]|h|r"))
        val log = ChatLog()
        log.add("TH1|77\t\n1\tsay\tA\t\tbefore reload")
        assertTrue(log.add("TH1|99\t\n1\tsay\tA\t\tafter reload")!!)
        assertEquals(2, log.lines.size)
    }

    @Test
    fun keepsOnlyTheNewest() {
        val log = ChatLog(keep = 2)
        log.add("TH1|1\t\n1\tsay\tA\t\tone\n2\tsay\tA\t\ttwo\n3\tsay\tA\t\tthree")
        assertEquals(listOf("two", "three"), log.lines.map { it.text })
        assertFalse(log.add("TH1|1\t\n3\tsay\tA\t\tthree")!!)
    }

    @Test
    fun readsTheChannelsYouAreIn() {
        assertEquals(null, ChatFile.parse("10/7 20:33:01.000  Left Channel: |Hchannel:1140896|h[(null)]|h"))
        assertEquals(ChatLine("system", "", "Joined Channel: [2. Trade - City]"), ChatFile.parse("10/7 20:33:01.000  Joined Channel: |Hchannel:CHANNEL:2|h[2. Trade - City]|h"))
        assertEquals(ChatLine("say", "Bo", "got [Linen Cloth]"), ChatFile.parse("10/7 20:33:02.000  Bo says: got |cff9d9d9d|Hitem:2589::::|h[Linen Cloth]|h|r"))
        val log = ChatLog()
        assertTrue(log.add("TH1|1\tA\t1 General,2 Trade,4 LookingForGroup\n")!!)
        assertEquals(listOf(1 to "General", 2 to "Trade", 4 to "LookingForGroup"), log.channels)
        assertFalse(log.add("TH1|1\tA\t1 General,2 Trade,4 LookingForGroup\n")!!)
        assertTrue(log.add("TH1|1\tA\t1 General\n")!!)
        assertEquals(listOf(1 to "General"), log.channels)
    }

    @Test
    fun putsLateLinesInWritingOrder() {
        assertEquals(null, ChatFile.parse("10/7 20:33:01.000  Left Channel: |Hchannel:1140896|h[(null)]|h"))
        assertEquals(ChatLine("system", "", "Joined Channel: [2. Trade - City]"), ChatFile.parse("10/7 20:33:01.000  Joined Channel: |Hchannel:CHANNEL:2|h[2. Trade - City]|h"))
        assertEquals(ChatLine("say", "Bo", "got [Linen Cloth]"), ChatFile.parse("10/7 20:33:02.000  Bo says: got |cff9d9d9d|Hitem:2589::::|h[Linen Cloth]|h|r"))
        val log = ChatLog()
        // The party line (3) comes first, the older Trade line (2) after it.
        log.add("TH1|5\t\t\n1\tsay\tA\t\tone\n3\tparty\tB\t\tthree")
        log.add("TH1|5\t\t\n2\tchannel\tC\tTrade\ttwo\n3\tparty\tB\t\tthree")
        assertEquals(listOf("one", "two", "three"), log.lines.map { it.text })
        // A new session's lines come after the old one's, whatever their numbers.
        log.add("TH1|9\t\t\n1\tsay\tA\t\tafter reload")
        assertEquals("after reload", log.lines.last().text)
    }

    @Test
    fun ignoresOtherMessages() {
        assertNull(ChatLog().add("TS1|x|1|0|0|0|0"))
    }

    @Test
    fun linesFromTheChatLogFile() {
        assertEquals(ChatLine("channel", "Azlyn Lee", "Olympus?", "Trade"), ChatFile.parse("10/7 19:51:34.843  [2. Trade] Azlyn Lee: Olympus?"))
        // Other players' crafting and loot are left out; yours and the game's announcements stay.
        assertEquals(null, ChatFile.parse("10/7 19:51:35.202  Zaria Stormstrike creates Light Armor Kit."))
        assertEquals(null, ChatFile.parse("10/7 19:51:35.202  Bo receives loot: [Linen Cloth]x2."))
        assertEquals(ChatLine("system", "", "You create Medium Leather."), ChatFile.parse("10/7 19:51:35.202  You create Medium Leather."))
        assertEquals(ChatLine("system", "", "You receive loot: [Linen Cloth]."), ChatFile.parse("10/7 19:51:35.202  You receive loot: [Linen Cloth]."))
        assertEquals(ChatLine("system", "", "Bo has come online."), ChatFile.parse("10/7 19:51:35.202  Bo has come online."))
        assertEquals(ChatLine("party", "Pagrin", "on my way: 2 min"), ChatFile.parse("10/7 19:51:36.000  [Party Leader] Pagrin: on my way: 2 min"))
        assertEquals(ChatLine("whisper", "Moria-Deathknell", "hi"), ChatFile.parse("10/7 19:51:37.000  Moria-Deathknell whispers: hi"))
        assertEquals(ChatLine("whisper_to", "Moria-Deathknell", "hey"), ChatFile.parse("10/7 19:51:38.000  To Moria-Deathknell: hey"))
        assertEquals(ChatLine("say", "Sanbica", "hello"), ChatFile.parse("10/7/2026 19:51:39.000  Sanbica says: hello"))
        assertEquals(ChatLine("guild", "Bob", "gz"), ChatFile.parse("10/7 19:51:40.000  [Guild] Bob: gz"))
        assertEquals(ChatLine("channel", "Bo", "lfg", "General"), ChatFile.parse("10/7 19:51:41.000  [1. General - Brill] Bo: lfg"))
        assertEquals(null, ChatFile.parse("10/7 20:33:01.000  Left Channel: |Hchannel:1140896|h[(null)]|h"))
        assertEquals(ChatLine("system", "", "Joined Channel: [2. Trade - City]"), ChatFile.parse("10/7 20:33:01.000  Joined Channel: |Hchannel:CHANNEL:2|h[2. Trade - City]|h"))
        assertEquals(ChatLine("say", "Bo", "got [Linen Cloth]"), ChatFile.parse("10/7 20:33:02.000  Bo says: got |cff9d9d9d|Hitem:2589::::|h[Linen Cloth]|h|r"))
        val log = ChatLog()
        log.add(ChatFile.parse("10/7 19:51:34.843  [2. Trade] Azlyn Lee: Olympus?")!!)
        assertEquals(1, log.lines.size)
        assertEquals(1, log.total)
    }

    @Test
    fun squareLinesAfterFileLines() {
        val log = ChatLog()
        log.add(ChatLine("channel", "Bo", "wts", "Trade"))
        log.add("TH1|s1\t\t\n1\tparty\tPagrin\t\tomw")
        log.add(ChatLine("channel", "Al", "lfg", "Trade"))
        log.add("TH1|s1\t\t\n2\tguild\tBob\t\tgz")
        assertEquals(listOf("wts", "omw", "lfg", "gz"), log.lines.map { it.text })
    }

    @Test
    fun whenALineWasSaid() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 7, 20, 0, 10); set(java.util.Calendar.MILLISECOND, 0) }
        assertEquals(now.timeInMillis - 9_500, ChatFile.saidAt("10/7 20:00:00.500  [2. Trade] Bo: hi", now))
        val midnight = java.util.Calendar.getInstance().apply { set(2026, 9, 8, 0, 0, 5); set(java.util.Calendar.MILLISECOND, 0) }
        assertEquals(midnight.timeInMillis - 10_000, ChatFile.saidAt("10/7 23:59:55.000  [2. Trade] Bo: hi", midnight))
        assertNull(ChatFile.saidAt("no time"))
    }
}
