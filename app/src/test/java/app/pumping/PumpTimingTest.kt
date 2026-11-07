package app.pumping

import app.pumping.data.Session
import app.pumping.domain.*
import org.junit.Assert.*
import org.junit.Test

class PumpTimingTest {
    private val start = 1800000000000L
    private val session =
        Session(start = start, end = start + 20 * 60000L, activeSeconds = 1200, volume = 1000)

    @Test
    fun selectedReferenceControlsDeadlinesAndHistoricalIntervals() {
        assertEquals(
            start + 180 * 60000L,
            nextAutomaticPump(pumpReferenceTime(session, PumpReference.START)!!, 180, start),
        )
        assertEquals(
            start + 200 * 60000L,
            nextAutomaticPump(pumpReferenceTime(session, PumpReference.END)!!, 180, start),
        )
        val next = session.copy(start = start + 200 * 60000L, end = start + 220 * 60000L)
        assertEquals(200 * 60000L, interval(session, next, PumpReference.START))
        assertEquals(180 * 60000L, interval(session, next, PumpReference.END))
    }

    @Test
    fun textUsesDeliveryTimeAndCorrectVerb() {
        val now = start + 200 * 60000L
        assertEquals(
            "Last pump started 3h 20m ago",
            pumpNotificationText(listOf(session), PumpReference.START, now),
        )
        assertEquals(
            "Last pump ended 3h ago",
            pumpNotificationText(listOf(session), PumpReference.END, now),
        )
        assertEquals(
            "Last pump ended 5m ago",
            pumpNotificationText(listOf(session), PumpReference.END, session.end + 5 * 60000L),
        )
        assertEquals(
            "No previous pump recorded.",
            pumpNotificationText(emptyList(), PumpReference.START, now),
        )
        assertEquals(
            "No previous pump recorded.",
            pumpNotificationText(listOf(session), PumpReference.START, start - 1),
        )
    }

    @Test
    fun missingStartCanBeDerivedWithoutEditingSavedTimestamps() {
        val old = session.copy(start = 0)
        assertEquals(session.start, pumpReferenceTime(old, PumpReference.START))
        assertEquals(0L, old.start)
        assertNull(pumpReferenceTime(old.copy(activeSeconds = 0), PumpReference.START))
        assertNull(pumpReferenceTime(old.copy(activeSeconds = Long.MAX_VALUE), PumpReference.START))
    }

    @Test
    fun timingFormattingAndHistoricalRecoveryDoNotDrift() {
        assertEquals("0m", pumpElapsedText(-1))
        assertEquals("59m", pumpElapsedText(59 * 60000L))
        assertEquals("24h", pumpElapsedText(24 * 3600000))
        val now = start + 24 * 3600000
        val next = nextAutomaticPump(start, 180, now)
        assertTrue(next > now)
        assertEquals(0L, (next - start) % (180 * 60000L))
        assertEquals(PumpReference.START, PumpReference.from(null))
    }
}
