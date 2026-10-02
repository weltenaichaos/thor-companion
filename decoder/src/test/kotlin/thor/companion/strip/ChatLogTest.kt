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
        assertEquals(true, log.add("TH1|77\n1\tsay\tMoria\tHello [Hearthstone] |pipe\n2\twhisper\tOwarh\tpsst"))
        // A refresh sends the same lines again, plus a new one.
        assertEquals(true, log.add("TH1|77\n2\twhisper\tOwarh\tpsst\n3\tsystem\t\tYou are now Away."))
        assertEquals(false, log.add("TH1|77\n3\tsystem\t\tYou are now Away."))
        assertEquals(
            listOf(
                ChatLine("say", "Moria", "Hello [Hearthstone] |pipe"),
                ChatLine("whisper", "Owarh", "psst"),
                ChatLine("system", "", "You are now Away."),
            ),
            log.lines,
        )
    }

    @Test
    fun newSessionStartsNumbersOver() {
        val log = ChatLog()
        log.add("TH1|77\n1\tsay\tA\tbefore reload")
        assertTrue(log.add("TH1|99\n1\tsay\tA\tafter reload")!!)
        assertEquals(2, log.lines.size)
    }

    @Test
    fun keepsOnlyTheNewest() {
        val log = ChatLog(keep = 2)
        log.add("TH1|1\n1\tsay\tA\tone\n2\tsay\tA\ttwo\n3\tsay\tA\tthree")
        assertEquals(listOf("two", "three"), log.lines.map { it.text })
        assertFalse(log.add("TH1|1\n3\tsay\tA\tthree")!!)
    }

    @Test
    fun ignoresOtherMessages() {
        assertNull(ChatLog().add("TS1|x|1|0|0|0|0"))
    }
}
