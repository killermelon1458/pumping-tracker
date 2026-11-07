package app.pumping

import app.pumping.data.Session
import app.pumping.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CalculationsTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    private fun session(start: String, minutes: Long, volume: Long = 1000) =
        Session(
            start = at(start),
            end = at(start) + minutes * 60000,
            activeSeconds = minutes * 60,
            volume = volume,
        )

    @Test
    fun intervalUsesSelectedReference() {
        val a = session("2026-11-03T09:10:00Z", 20)
        val b = session("2026-11-03T11:40:00Z", 20)
        assertEquals(150 * 60000L, interval(a, b))
        assertEquals(130 * 60000L, interval(a, b, PumpReference.END))
    }

    @Test
    fun summariesUseActiveTime() {
        val rows = listOf(20L, 20L, 20L, 15L).map { session("2026-11-03T09:10:00Z", it, 1000) }
        assertEquals(Totals(4000, 4500, 4), totals(rows))
    }

    @Test
    fun usOuncesAndStableDisplay() {
        assertEquals(29574L, Volume.parse("1", "oz"))
        val value = Volume.parse("4.7", "oz")
        repeat(100) {
            assertEquals(value, Volume.parse(Volume.format(value, "oz", 5), "oz"))
            assertEquals(value, Volume.parse(Volume.format(value, "mL", 3), "mL"))
        }
        assertEquals("138.996", Volume.format(value, "mL", 3))
    }

    @Test
    fun invalidVolumeRejected() {
        listOf("-1", "NaN", "Infinity", "1000000000000").forEach {
            assertTrue(runCatching { Volume.parse(it, "oz") }.isFailure)
        }
    }

    @Test
    fun expiryCalendarDays() {
        val zone = ZoneId.of("America/Chicago")
        val start = LocalDate.of(2026, 11, 5).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 9), dayOf(expiry(start, 4, zone), zone))
        assertEquals(LocalDate.of(2027, 5, 4), dayOf(expiry(start, 180, zone), zone))
    }

    @Test
    fun dstDaysAreNotFixed24Hours() {
        val zone = ZoneId.of("America/Chicago")
        val d = LocalDate.of(2026, 3, 8)
        assertEquals(23 * 3600000L, dayStart(d.plusDays(1), zone) - dayStart(d, zone))
        assertEquals(d, dayOf(dayStart(d, zone), zone))
    }

    @Test
    fun remindersAnchorToEnd() {
        val end = at("2026-11-05T08:47:00Z")
        assertEquals(at("2026-11-05T11:47:00Z"), nextReminder(end, 180))
    }

    @Test
    fun zeroPreviousComparison() {
        assertNull(comparison(1000, 0))
        assertEquals(50.0, comparison(150, 100)!!, 0.0)
    }

    @Test
    fun streakAllowsYesterdayAndStopsAtGap() {
        val today = LocalDate.now()
        fun row(d: LocalDate) =
            Session(start = dayStart(d), end = dayStart(d) + 60000, activeSeconds = 60, volume = 0)
        assertEquals(
            2,
            streak(
                listOf(row(today.minusDays(1)), row(today.minusDays(2)), row(today.minusDays(4))),
                today,
            ),
        )
        assertEquals(0, streak(emptyList(), today))
    }

    @Test
    fun splitValidation() {
        val s = session("2026-11-03T09:10:00Z", 20, 9000)
        validateSession(s.copy(left = 5000, right = 4000))
        assertTrue(runCatching { validateSession(s.copy(left = 5000, right = 5000)) }.isFailure)
    }

    @Test
    fun timerPauseProcessRestartAndReboot() {
        val running = TimerState("id", 1000, 2000, 5000, 7, true)
        assertEquals(8000L, running.elapsed(11000, 7))
        val paused = running.copy(accumulated = running.elapsed(11000, 7), running = false)
        assertEquals(8000L, paused.elapsed(999999, 7))
        assertEquals(2000L, running.elapsed(300, 8))
        val resumed = paused.copy(running = true, tick = 20000)
        assertEquals(10000L, resumed.elapsed(22000, 7))
    }

    @Test
    fun invalidSessionSpanRejected() {
        val s = session("2026-11-03T09:10:00Z", 20)
        assertTrue(runCatching { validateSession(s.copy(activeSeconds = 1201)) }.isFailure)
        assertTrue(runCatching { validateSession(s.copy(end = s.start)) }.isFailure)
    }
}
