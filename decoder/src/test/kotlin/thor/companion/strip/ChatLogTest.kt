package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatLogTest {

    @Test
    fun keepsLinesOnceInOrder() {
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
        val log = ChatLog()
        log.add("TH1|5\tTrader-Some-Realm\n1\tchannel\tTrader-Some-Realm\tTrade\tWTS stuff")
        val line = log.lines.single()
        assertEquals("Trade", line.channel)
        assertEquals("Trader", line.name)
        assertEquals(1, log.whisperSlot(line.sender))
    }

    @Test
    fun offersWhispersOnlyForNamesWithAKeyNow() {
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
        val log = ChatLog()
        assertTrue(log.add("TH1|1\tA\t1 General,2 Trade,4 LookingForGroup\n")!!)
        assertEquals(listOf(1 to "General", 2 to "Trade", 4 to "LookingForGroup"), log.channels)
        assertFalse(log.add("TH1|1\tA\t1 General,2 Trade,4 LookingForGroup\n")!!)
        assertTrue(log.add("TH1|1\tA\t1 General\n")!!)
        assertEquals(listOf(1 to "General"), log.channels)
    }

    @Test
    fun putsLateLinesInWritingOrder() {
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
        val log = ChatLog()
        log.add(ChatFile.parse("10/7 19:51:34.843  [2. Trade] Azlyn Lee: Olympus?")!!)
        assertEquals(1, log.lines.size)
        assertEquals(1, log.total)
    }
}
