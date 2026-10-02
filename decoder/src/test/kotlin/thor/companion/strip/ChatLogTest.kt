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
    fun ignoresOtherMessages() {
        assertNull(ChatLog().add("TS1|x|1|0|0|0|0"))
    }
}
