package app.pumping

import app.pumping.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class QuickLogTimingTest {
    private val end = Instant.parse("2026-11-08T15:00:00Z").toEpochMilli()
    private fun initial() = QuickLogTiming(end - 1200000, end, 1200)
    @Test fun initialAndIndependentFirstEdits() {
        val t = initial()
        assertEquals(listOf(TimeField.END, TimeField.DURATION), t.history)
        assertEquals(end - 600000, t.edit(TimeField.START, end - 1800000).end)
        assertEquals(end - 600000, t.edit(TimeField.END, end + 600000).start)
        assertEquals(end - 1500000, t.edit(TimeField.DURATION, 1500).start)
    }
    @Test fun allSixDistinctPairs() {
        for (a in TimeField.entries) for (b in TimeField.entries.filter { it != a }) {
            fun value(f: TimeField) = when(f) {
                TimeField.START -> end - 1800000
                TimeField.END -> end + 300000
                TimeField.DURATION -> 1500L
            }
            val t = initial().edit(a, value(a)).edit(b, value(b))
            assertEquals(listOf(a,b), t.history)
            assertEquals(t.end, t.start + t.seconds * 1000)
            assertEquals(value(a), when(a) { TimeField.START -> t.start; TimeField.END -> t.end; else -> t.seconds })
            assertEquals(value(b), when(b) { TimeField.START -> t.start; TimeField.END -> t.end; else -> t.seconds })
        }
    }
    @Test fun longSequenceAndRepeats() {
        val a = initial().edit(TimeField.START,end-1800000)
        val b = a.edit(TimeField.END,end+300000)
        assertEquals(2100,b.seconds)
        val c = b.edit(TimeField.DURATION,1500)
        assertEquals(end-1200000,c.start)
        val d = c.edit(TimeField.DURATION,1800)
        assertEquals(c.end,d.end)
        assertEquals(c.history,d.history)
    }
    @Test fun initializationAndProgrammaticCopyDoNotEditHistoryOrValues() {
        val t = QuickLogTiming(end-3600000,end,1234)
        assertEquals(1234,t.seconds)
        assertEquals(end-3600000,t.start)
        assertEquals(t.history,t.copy(end=end+1000).history)
        assertEquals(initial().history,initial().history)
    }
    @Test fun midnightAndDstUseElapsedInstants() {
        val zone=ZoneId.of("America/Chicago")
        for (instant in listOf("2026-03-08T08:10:00Z","2026-11-01T07:10:00Z","2026-11-09T06:05:00Z")) {
            val at=Instant.parse(instant).toEpochMilli()
            val t=QuickLogTiming(at-1200000,at,1200).edit(TimeField.DURATION,1800)
            assertEquals(1800000,t.end-t.start)
            assertEquals(Duration.ofMinutes(30),Duration.between(Instant.ofEpochMilli(t.start).atZone(zone),Instant.ofEpochMilli(t.end).atZone(zone)))
        }
    }
    @Test fun pickerResolvesDstGapsAndRetainsOverlapOffset() {
        val zone=ZoneId.of("America/Chicago")
        val fall=LocalDate.of(2026,11,1)
        val earlier=resolveEditorTime(fall,1,30,zone,ZoneOffset.ofHours(-5))
        val later=resolveEditorTime(fall,1,30,zone,ZoneOffset.ofHours(-6))
        assertEquals(3600000,later-earlier)
        val spring=resolveEditorTime(LocalDate.of(2026,3,8),2,30,zone,ZoneOffset.ofHours(-6))
        assertEquals(3,Instant.ofEpochMilli(spring).atZone(zone).hour)
        assertEquals(30,Instant.ofEpochMilli(spring).atZone(zone).minute)
    }
    @Test fun invalidIntervalsAndTypingRejected() {
        for (text in listOf("", ".", "-1", "0", "NaN", "Infinity", "999999999999999999999999")) {
            assertTrue(runCatching { durationSeconds(text) }.isFailure)
        }
        assertTrue(runCatching { initial().edit(TimeField.DURATION,-1) }.isFailure)
        val t=initial().edit(TimeField.START,end-1000)
        assertTrue(runCatching { t.edit(TimeField.END,end-2000) }.isFailure)
        assertEquals(1234,durationSeconds("20.57"))
        assertEquals(75,durationSeconds("1.25"))
    }
}
