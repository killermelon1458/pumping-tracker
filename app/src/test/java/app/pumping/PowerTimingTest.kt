package app.pumping

import app.pumping.data.*
import app.pumping.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PowerTimingTest {
    private val end = Instant.parse("2026-11-10T00:20:00Z").toEpochMilli()
    private fun parent(active: Long, elapsed: Long) = Session(id="synthetic-power",start=end-elapsed*1000,end=end,activeSeconds=active,volume=100000,powerStatus=PowerPumpStatus.POWER)
    @Test fun optionalPresetHasOneParentFortyActiveSixtyElapsed() {
        val s=parent(2400,3600);val phases=powerPreset(s.id)
        assertEquals(PhaseTotals(2400,3600),phaseTotals(phases));validateSession(s);validateSessionPhases(s,phases)
        val boundaries=phaseBoundaries(s,phases)
        assertEquals(s.start,boundaries.first().first);assertEquals(s.end,boundaries.last().second)
        assertTrue(boundaries.zipWithNext().all{it.first.second==it.second.first})
        assertEquals(Totals(100000,2400,1),totals(listOf(s)))
    }
    @Test fun customDurationsOrderAndIdsAreNotSessionCounts() {
        val s=parent(900,1200)
        val a=SessionPhase("a",s.id,0,PhaseKind.PUMP,300)
        val b=SessionPhase("b",s.id,1,PhaseKind.REST,300)
        val c=SessionPhase("c",s.id,2,PhaseKind.PUMP,600)
        val list=listOf(a,b,c);validateSessionPhases(s,list)
        val moved=listOf(c.copy(position=0),a.copy(position=1),b.copy(position=2))
        assertEquals(phaseTotals(list),phaseTotals(moved));assertEquals(setOf("a","b","c"),moved.map{it.id}.toSet())
        validateSessionPhases(s,moved)
    }
    @Test fun detailedTimestampEditsAlwaysMoveOtherEndpoint() {
        val initial=RoutineTiming(end-3600000,end)
        val moved=initial.edit(TimeField.START,initial.start+16*60000,3600000)
        assertEquals(initial.end+16*60000,moved.end)
        val ended=moved.edit(TimeField.END,end,3600000)
        assertEquals(initial.start,ended.start)
        assertEquals(end,ended.resize(3900000).end)
        assertEquals(moved.start,moved.resize(3900000).start)
        assertEquals(initial.start,initial.resize(2400000).start-1200000)
    }
    @Test fun basicPowerUsesEveryOrdinaryAuthoritySequence() {
        val base=QuickLogTiming(end-1200000,end,1200)
        for(first in TimeField.entries) for(second in TimeField.entries) {
            var state=base
            for(field in listOf(first,second,first,first,second,TimeField.DURATION)) {
                val value=when(field){TimeField.START->state.start-60000;TimeField.END->state.end+60000;TimeField.DURATION->state.seconds+60}
                val before=state
                state=state.edit(field,value)
                assertEquals((before.history.filterNot{it==field}+field).takeLast(2),state.history)
                assertEquals(state.seconds*1000,state.end-state.start)
            }
        }
        val nineFortyFour=Instant.parse("2026-11-09T09:44:00Z").toEpochMilli()
        val example=QuickLogTiming(nineFortyFour-1200000,nineFortyFour,1200).edit(TimeField.DURATION,2400)
        assertEquals(Instant.parse("2026-11-09T09:04:00Z").toEpochMilli(),example.start)
        val forty=base.edit(TimeField.DURATION,2400)
        assertEquals(end,forty.end);assertEquals(end-40*60000,forty.start)
    }
    @Test fun presetAndTransitionsPreserveLatestManualTimestampNotCalculatedHistory() {
        val base=QuickLogTiming(end-2400000,end,2400)
        val initial=RoutineTiming(base.start,base.end).resize(3600000)
        assertEquals(end-3600000,initial.start);assertEquals(end,initial.end)
        val startEdited=initial.edit(TimeField.START,initial.start+60000,3600000)
        val phaseEdited=startEdited.resize(3900000)
        assertEquals(startEdited.start,phaseEdited.start)
        assertEquals(startEdited.start,phaseEdited.resize(2700000).start)
        assertEquals(listOf(TimeField.END,TimeField.DURATION),base.history)
        assertEquals(end,initial.resize(2400000).end)
    }
    @Test fun pumpAndRestEditsAffectOnlyTheirDefinedTotals() {
        val p=powerPreset("s");val original=phaseTotals(p)
        assertEquals(1200,original.elapsed-original.active)
        assertEquals(PhaseTotals(2700,3900),phaseTotals(p.mapIndexed{i,x->if(i==0)x.copy(seconds=x.seconds+300) else x}))
        assertEquals(PhaseTotals(2400,3900),phaseTotals(p.mapIndexed{i,x->if(i==1)x.copy(seconds=x.seconds+300) else x}))
        assertEquals(end,RoutineTiming(end-3600000,end).resize(3900000).end)
    }
    @Test fun detailedMidnightAndSubsecondTimingRemainConsistent() {
        val a=RoutineTiming(end-3600321,end).edit(TimeField.START,end-2*3600000,3600321)
        assertEquals(3600321,a.end-a.start)
        assertEquals(a.start,a.resize(3900321).start)
        assertTrue(runCatching{a.resize(Long.MAX_VALUE)}.isFailure)
    }
    @Test fun actualElapsedEligibilityExcludesBasicPowerNominalWindows() {
        val basic=parent(1200,1200)
        assertNull(actualElapsedMillis(basic))
        assertNull(actualElapsedMillis(basic.copy(elapsedKnown=false)))
        assertEquals(1200000L,actualElapsedMillis(basic.copy(powerStatus=PowerPumpStatus.NORMAL)))
        val timer=parent(1200,3600).copy(mode="TIMER")
        assertEquals(3600000L,actualElapsedMillis(timer))
        val detailed=parent(2400,3600)
        assertEquals(3600000L,actualElapsedMillis(detailed,powerPreset(detailed.id)))
    }
    @Test fun activeOnlyMetadataIsHonestAndHasNoInventedRest() {
        val s=parent(1500,1500).copy(elapsedKnown=false)
        validateSession(s);validateSessionPhases(s,emptyList())
        assertTrue(pumpNotificationText(listOf(s),PumpReference.START,s.end+60000).contains("started"))
        assertTrue(pumpNotificationText(listOf(s),PumpReference.END,s.end+60000).contains("ended"))
        assertTrue(runCatching{validateSession(s.copy(end=s.end+60000))}.isFailure)
        assertTrue(runCatching{validateSessionPhases(s,powerPreset(s.id))}.isFailure)
    }
    @Test fun invalidIntervalsAndReferencesRejected() {
        val s=parent(2400,3600);val good=powerPreset(s.id)
        val bad=listOf(good.map{it.copy(seconds=0)},good.map{it.copy(kind=PhaseKind.REST)},
            good.map{it.copy(position=0)},good.map{it.copy(id="duplicate")},good.map{it.copy(sessionId="missing")},
            good.map{it.copy(seconds=Long.MAX_VALUE)})
        bad.forEach{assertTrue(runCatching{validateSessionPhases(s,it)}.isFailure)}
        assertTrue(runCatching{validateSessionPhases(s.copy(powerStatus=PowerPumpStatus.NORMAL),good)}.isFailure)
        assertTrue(runCatching{validateSessionPhases(s.copy(activeSeconds=2401),good)}.isFailure)
        assertTrue(runCatching{validateSessionPhases(s.copy(end=s.end+1000),good)}.isFailure)
    }
    @Test fun timerSubsecondSpanAndPausedActiveTimeRemainRecorded() {
        val s=parent(2400,3600).copy(mode="TIMER",end=end+321)
        val p=powerPreset(s.id);validateSession(s);validateSessionPhases(s,p)
        assertEquals(s.end,phaseBoundaries(s,p).last().second)
        assertEquals(2400,s.activeSeconds)
        assertTrue(runCatching{validateSessionPhases(s.copy(activeSeconds=2401),p)}.isFailure)
    }
}
