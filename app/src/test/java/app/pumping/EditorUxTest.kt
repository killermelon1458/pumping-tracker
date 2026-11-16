package app.pumping

import app.pumping.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EditorUxTest {
    private val zone=ZoneId.of("America/Chicago")
    private fun at(text:String)=ZonedDateTime.parse(text).toInstant().toEpochMilli()
    @Test fun dateOnlyRetainsCompleteTimeIncludingMilliseconds() {
        val old=at("2026-11-16T12:36:42.123-06:00[America/Chicago]")
        val next=Instant.ofEpochMilli(editorDate(old,LocalDate.of(2026,11,17),zone)).atZone(zone)
        assertEquals(LocalDate.of(2026,11,17),next.toLocalDate())
        assertEquals(LocalTime.of(12,36,42,123000000),next.toLocalTime())
        assertEquals(old,editorDate(editorDate(old,LocalDate.of(2026,11,17),zone),LocalDate.of(2026,11,16),zone))
    }
    @Test fun timeOnlyPreservesDateAndUnexposedPrecision() {
        val old=at("2026-11-16T23:59:42.123-06:00[America/Chicago]")
        val next=Instant.ofEpochMilli(editorTime(old,0,1,zone)).atZone(zone)
        assertEquals(LocalDate.of(2026,11,16),next.toLocalDate())
        assertEquals(LocalTime.of(0,1,42,123000000),next.toLocalTime())
        assertEquals(old,editorTime(old,23,59,zone))
        assertEquals(old,editorTime(next.toInstant().toEpochMilli(),23,59,zone))
    }
    @Test fun dstGapResolvesForwardAndOverlapRetainsOffset() {
        val before=at("2026-03-08T01:30:17.001-06:00[America/Chicago]")
        val gap=Instant.ofEpochMilli(editorTime(before,2,30,zone)).atZone(zone)
        assertEquals(3,gap.hour);assertEquals(17,gap.second)
        for(offset in listOf("-05:00","-06:00")) {
            val old=at("2026-11-01T01:30:00$offset[America/Chicago]")
            assertEquals(Instant.ofEpochMilli(old).atZone(zone).offset,Instant.ofEpochMilli(editorTime(old,1,45,zone)).atZone(zone).offset)
        }
    }
    @Test fun twelveHourBoundaryFlipsInBothDirectionsButNotTwelveOneWrap() {
        assertEquals(12,wheelHour24(11,11,12,false))
        assertEquals(11,wheelHour24(12,12,11,false))
        assertEquals(0,wheelHour24(23,11,12,false))
        assertEquals(23,wheelHour24(0,12,11,false))
        assertEquals(13,wheelHour24(12,12,1,false))
        assertEquals(12,wheelHour24(13,1,12,false))
        assertEquals(1,wheelHour24(0,12,1,false))
        assertEquals(0,wheelHour24(1,1,12,false))
    }
    @Test fun fullClockCyclesAndTwentyFourHourModeKeepCorrectValues() {
        for(start in 0..23) {
            var hour=start
            repeat(24) { val old=(hour%12).let{if(it==0)12 else it};hour=wheelHour24(hour,old,old%12+1,false) }
            assertEquals(start,hour)
        }
        assertEquals(0,wheelHour24(23,23,0,true));assertEquals(23,wheelHour24(0,0,23,true))
        for(hour in 0..23) assertEquals(hour,wheelHour24(0,0,hour,true))
    }
    @Test fun normalizedDraftsRecognizeNumericRevertsAndKeepInvalidWork() {
        assertEquals(normalizedSeconds("20"),normalizedSeconds("20.00"))
        assertNotEquals(normalizedSeconds(""),normalizedSeconds("20"))
        assertNotEquals(normalizedSeconds("."),normalizedSeconds(""))
        val ml=Volume.format(Volume.parse("1","oz"),"mL",5)
        assertEquals(normalizedVolume("1","oz"),normalizedVolume(ml,"mL"))
        assertNotEquals(normalizedVolume("","oz"),normalizedVolume("0","oz"))
        assertNotEquals(normalizedVolume("bad","oz"),normalizedVolume("","oz"))
        assertEquals(EditorSnapshot(listOf("a","b")),EditorSnapshot(listOf("a","b")))
        assertNotEquals(EditorSnapshot(listOf("a","b")),EditorSnapshot(listOf("b","a")))
    }
    @Test fun independentSelectionsUseExistingAuthorityExactlyOnce() {
        val end=at("2026-11-16T12:36:42.123-06:00[America/Chicago]")
        val original=QuickLogTiming(end-1200000,end,1200)
        val changed=original.edit(TimeField.START,editorDate(original.start,LocalDate.of(2026,11,15),zone))
        assertEquals(listOf(TimeField.DURATION,TimeField.START),changed.history)
        assertEquals(original.seconds,changed.seconds)
        val timed=changed.edit(TimeField.END,editorTime(changed.end,13,15,zone))
        assertEquals(listOf(TimeField.START,TimeField.END),timed.history)
        assertEquals(changed.start,timed.start)
    }
    @Test fun savedBaselineIsCompactSerializableAndHasUnambiguousFieldBoundaries() {
        val snapshot=EditorSnapshot(listOf("synthetic private note", "b"))
        assertEquals(64,snapshot.fingerprint.length)
        assertFalse(snapshot.toString().contains("synthetic private note"))
        assertNotEquals(EditorSnapshot(listOf("ab","c")),EditorSnapshot(listOf("a","bc")))
        val bytes=java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(bytes).use{it.writeObject(snapshot)}
        val restored=java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())).use{it.readObject()}
        assertEquals(snapshot,restored)
    }

}
