package app.pumping

import app.pumping.data.*
import app.pumping.domain.*
import java.math.BigDecimal
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026,11,16)
    private val spectra = PumpProfile(id="spectra",nickname="Stationary Spectra")
    private val momcozy = PumpProfile(id="momcozy",nickname="Wearable Momcozy",type=PumpType.MOBILE_OR_WEARABLE)
    private fun row(id:String, ml:Long=100, seconds:Long=1200, day:LocalDate=today, pump:String?=null,
        breast:BreastfeedingStatus=BreastfeedingStatus.UNKNOWN, power:PowerPumpStatus=PowerPumpStatus.NORMAL):Session {
        val start=day.atTime(10,0).atZone(zone).toInstant().toEpochMilli()
        return Session(id=id,start=start,end=start+seconds*1000,activeSeconds=seconds,volume=ml*1000,
            pumpProfileId=pump,breastfedBefore=breast,powerStatus=power)
    }
    // Synthetic confounding pattern: wearable most often follows nursing; stationary usually does not.
    private fun pattern()=listOf(
        row("s1",180,pump=spectra.id,breast=BreastfeedingStatus.NO),row("s2",160,pump=spectra.id,breast=BreastfeedingStatus.NO),
        row("s3",100,pump=spectra.id,breast=BreastfeedingStatus.YES),row("m1",70,pump=momcozy.id,breast=BreastfeedingStatus.YES),
        row("m2",60,pump=momcozy.id,breast=BreastfeedingStatus.YES),row("m3",130,pump=momcozy.id,breast=BreastfeedingStatus.NO),
        row("power",200,pump=momcozy.id,power=PowerPumpStatus.POWER),row("legacy",90,power=PowerPumpStatus.UNKNOWN))
    private fun eq(expected:String,actual:BigDecimal?) { assertNotNull(actual);assertEquals(0,BigDecimal(expected).compareTo(actual)) }
    @Test fun defaultRollingWindowIncludesTodayAndPreviousTwentyNineDays() {
        val w=statsWindow(StatsRange.DAYS_30,today,today,today)!!
        assertEquals(today.minusDays(29),w.from);assertEquals(today.plusDays(1),w.until)
        assertNull(statsWindow(StatsRange.ALL,today,today,today))
    }
    @Test fun sevenDayAndPreviousPeriodsHaveEqualInclusiveLengths() {
        val w=statsWindow(StatsRange.DAYS_7,today,today,today)!!
        assertEquals(today.minusDays(6),w.from);assertEquals(w.previous(),w.shift(-1))
        assertEquals(w,statsWindow(StatsRange.DAYS_7,today,today,today,-1)!!.shift(1))
    }
    @Test fun customWindowIncludesWholeFinalDayButNotNextMidnight() {
        val w=statsWindow(StatsRange.CUSTOM,today,today.minusDays(1),today)!!
        val begin=dayStart(w.from,zone);val end=dayStart(w.until,zone)
        val rows=listOf(row("before").copy(start=begin-1),row("first").copy(start=begin),row("last").copy(start=end-1),row("after").copy(start=end))
        assertEquals(listOf("first","last"),filterSessions(rows,StatsFilter(window=w),zone).map{it.id})
    }
    @Test fun dstDateMembershipUsesLocalMidnightAndSessionStart() {
        val day=LocalDate.of(2026,11,1);val w=StatsWindow(day,day.plusDays(1))
        assertEquals(25*3600000L,dayStart(w.until,zone)-dayStart(w.from,zone))
        val cross=row("cross",day=day.minusDays(1)).copy(start=dayStart(day,zone)-60000,end=dayStart(day,zone)+60000)
        assertTrue(filterSessions(listOf(cross),StatsFilter(window=w),zone).isEmpty())
        val late=row("late",day=day).copy(start=dayStart(w.until,zone)-1)
        assertEquals(1,filterSessions(listOf(late),StatsFilter(window=w),zone).size)
        val midnight=row("zone").copy(start=Instant.parse("2026-11-01T02:00:00Z").toEpochMilli())
        assertEquals(1,filterSessions(listOf(midnight),StatsFilter(window=w),ZoneOffset.UTC).size)
        assertEquals(0,filterSessions(listOf(midnight),StatsFilter(window=w),zone).size)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidCustomDatesAreRejected() { statsWindow(StatsRange.CUSTOM,today,today,today.minusDays(1)) }
    @Test fun pumpSelectionsSupportOneManyAndExplicitUnspecified() {
        assertEquals(3,filterSessions(pattern(),StatsFilter(pumpIds=setOf("spectra"))).size)
        assertEquals(7,filterSessions(pattern(),StatsFilter(pumpIds=setOf("spectra","momcozy"))).size)
        assertEquals(listOf("legacy"),filterSessions(pattern(),StatsFilter(pumpIds=setOf(null))).map{it.id})
        assertTrue(filterSessions(pattern(),StatsFilter(pumpIds=emptySet())).isEmpty())
    }
    @Test fun breastfeedingFiltersNeverInferUnknownFromOtherFields() {
        for(status in BreastfeedingStatus.entries) assertTrue(filterSessions(pattern(),StatsFilter(breastfed=status)).all{it.breastfedBefore==status})
        assertEquals(2,filterSessions(pattern(),StatsFilter(breastfed=BreastfeedingStatus.UNKNOWN)).size)
    }
    @Test fun allThreeModesRemainDistinct() {
        assertEquals(6,filterSessions(pattern(),StatsFilter(power=PowerPumpStatus.NORMAL)).size)
        assertEquals(1,filterSessions(pattern(),StatsFilter(power=PowerPumpStatus.POWER)).size)
        assertEquals(1,filterSessions(pattern(),StatsFilter(power=PowerPumpStatus.UNKNOWN)).size)
    }
    @Test fun everyPredicateCombinesWithAnd() {
        val outside=row("outside",day=today.minusDays(31),pump="spectra",breast=BreastfeedingStatus.NO)
        val filter=StatsFilter(statsWindow(StatsRange.DAYS_30,today,today,today),setOf("spectra"),BreastfeedingStatus.NO,PowerPumpStatus.NORMAL)
        assertEquals(listOf("s1","s2"),filterSessions(pattern()+outside,filter,zone).map{it.id})
    }
    @Test fun meanAndMedianEvenOddAndFractionalCanonicalQuantities() {
        val rows=listOf(row("a",1),row("b",3),row("c",10),row("d",20))
        eq("8500",pumpStatistics(rows).meanVolume);eq("6500",pumpStatistics(rows).medianVolume)
        eq("3000",pumpStatistics(rows.take(3)).medianVolume)
        eq("0.5",pumpStatistics(listOf(row("a").copy(volume=0),row("b").copy(volume=1))).medianVolume)
    }
    @Test fun activeRateIsRatioOfSumsNotAverageOfSessionRates() {
        val stats=pumpStatistics(listOf(row("a",180,1200),row("b",120,2400)))
        eq("5000",stats.milkPerActiveMinute);eq("1800",stats.meanActiveSeconds)
        assertEquals(3600L,stats.activeSeconds)
    }
    @Test fun actualElapsedUsesEligibleVolumeAndIncludesPausedTimerSpan() {
        val normal=row("normal",100,1200)
        val nominal=row("nominal",900,1200,power=PowerPumpStatus.POWER).copy(elapsedKnown=false)
        val paused=row("timer",200,1200,power=PowerPumpStatus.POWER).copy(mode="TIMER",end=normal.start+2400000)
        val stats=pumpStatistics(listOf(normal,nominal,paused))
        assertEquals(2,stats.elapsedCount);assertEquals(3600000L,stats.elapsedMillis);eq("5000",stats.milkPerElapsedMinute)
    }
    @Test fun detailedPowerPhasesNeverMultiplyParentMilkOrCount() {
        val power=row("power",200,2400,power=PowerPumpStatus.POWER).copy(end=row("x").start+3600000)
        val phases=powerPreset(power.id)
        val stats=pumpStatistics(listOf(power,power),phases)
        assertEquals(1,stats.count);assertEquals(200000L,stats.volume);assertEquals(2400L,stats.activeSeconds)
        assertEquals(1,stats.elapsedCount);assertEquals(3600000L,stats.elapsedMillis)
        assertEquals(0,pumpStatistics(listOf(power)).elapsedCount)
    }
    @Test fun invalidElapsedAndUnknownFlagsAreExcludedGracefully() {
        val bad=row("bad").copy(end=0)
        assertEquals(0,pumpStatistics(listOf(bad)).elapsedCount)
        val unknown=row("unknown").copy(elapsedKnown=false)
        assertNull(pumpStatistics(listOf(unknown)).milkPerElapsedMinute)
    }
    @Test fun emptyZeroVolumeAndZeroDenominatorsStayDistinct() {
        val empty=pumpStatistics(emptyList());assertEquals(0,empty.count);assertNull(empty.meanVolume);assertNull(empty.medianVolume)
        assertNull(empty.milkPerActiveMinute);assertNull(empty.milkPerElapsedMinute);assertNull(empty.left)
        val zero=pumpStatistics(listOf(row("zero",0)));eq("0",zero.meanVolume);eq("0",zero.milkPerActiveMinute)
        assertNull(pumpStatistics(listOf(row("invalid",10,0))).milkPerActiveMinute)
    }
    @Test fun leftRightTotalsUseOnlyRecordedSplits() {
        val stats=pumpStatistics(listOf(row("split",100).copy(left=60000,right=40000),row("other",200)))
        assertEquals(1,stats.splitCount);assertEquals(60000L,stats.left);assertEquals(40000L,stats.right);assertEquals(300000L,stats.volume)
    }
    @Test fun pumpComparisonsUseIdenticalConstraintsAndChangeWithBreastfeeding() {
        val filter=StatsFilter(power=PowerPumpStatus.NORMAL,breastfed=BreastfeedingStatus.NO)
        val no=comparePumps(pattern(),listOf(spectra,momcozy),emptyList(),filter,StatsGrouping.PUMP)
        eq("170000",no.single{it.key=="spectra"}.statistics.meanVolume);eq("130000",no.single{it.key=="momcozy"}.statistics.meanVolume)
        val yes=comparePumps(pattern(),listOf(spectra,momcozy),emptyList(),filter.copy(breastfed=BreastfeedingStatus.YES),StatsGrouping.PUMP)
        eq("100000",yes.single{it.key=="spectra"}.statistics.meanVolume);eq("65000",yes.single{it.key=="momcozy"}.statistics.meanVolume)
        assertEquals(3,no.sumOf{it.statistics.count});assertEquals(3,yes.sumOf{it.statistics.count})
    }
    @Test fun contextGroupsRespectPumpModeAndDateFilters() {
        val rows=pattern()+row("old",day=today.minusDays(60),pump="spectra",breast=BreastfeedingStatus.NO)
        val groups=comparePumps(rows,listOf(spectra,momcozy),emptyList(),StatsFilter(window=statsWindow(StatsRange.DAYS_30,today,today,today),pumpIds=setOf("spectra"),power=PowerPumpStatus.NORMAL),StatsGrouping.BREASTFEEDING,zone)
        assertEquals(listOf(1,2,0),groups.map{it.statistics.count})
        assertNull(groups.last().statistics.meanVolume)
    }
    @Test fun archivedRenamedProfilesUseStableIdsAndCurrentMetadata() {
        val renamed=spectra.copy(nickname="Renamed Spectra",archived=true)
        val group=comparePumps(pattern(),listOf(renamed,momcozy),emptyList(),StatsFilter(pumpIds=setOf(spectra.id)),StatsGrouping.PUMP).single()
        assertEquals(spectra.id,group.key);assertEquals("Renamed Spectra (archived)",group.label);assertEquals(3,group.statistics.count)
    }
    @Test fun canonicalResultsFormatInEitherUnitWithoutMutatingRecords() {
        val rows=listOf(row("a").copy(volume=Volume.parse("1","oz")))
        val stats=pumpStatistics(rows)
        assertEquals("1",Volume.format(stats.meanVolume!!,"oz"))
        assertEquals("29.57",Volume.format(stats.meanVolume!!,"mL"))
        assertEquals(rows.single().volume,stats.volume)
    }
}
