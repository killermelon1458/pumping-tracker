package app.pumping

import android.view.View
import android.widget.DatePicker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.*
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.*
import org.hamcrest.Matcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    private val today=LocalDate.now()
    @Before fun seed()=runBlocking {
        val app=app();app.db.clearAllTables();app.settings.string("unit","mL")
        app.repository.savePumpProfile(PumpProfile(id="spectra",nickname="Spectra"))
        app.repository.savePumpProfile(PumpProfile(id="momcozy",nickname="Momcozy",type=PumpType.MOBILE_OR_WEARABLE))
        suspend fun add(id:String,ml:Long,pump:String?,breast:BreastfeedingStatus=BreastfeedingStatus.UNKNOWN,
            power:PowerPumpStatus=PowerPumpStatus.NORMAL,days:Long=2,details:Boolean=false) {
            val start=today.minusDays(days).atTime(10,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val seconds=if(power==PowerPumpStatus.POWER)2400L else 1200L
            val session=Session(id=id,start=start,end=start+if(details)3600000 else seconds*1000,activeSeconds=seconds,
                volume=ml*1000,pumpProfileId=pump,breastfedBefore=breast,powerStatus=power,elapsedKnown=power!=PowerPumpStatus.POWER||details,
                left=if(id=="s1")100000 else null,right=if(id=="s1")80000 else null)
            app.repository.saveSession(session,if(id=="s1")listOf(Allocation(100000,"Fridge",4))else emptyList(),if(details)powerPreset(id)else emptyList())
        }
        // Synthetic usage pattern: wearable usually after nursing, stationary usually without nursing.
        add("s1",180,"spectra",BreastfeedingStatus.NO);add("s2",160,"spectra",BreastfeedingStatus.NO)
        add("s3",100,"spectra",BreastfeedingStatus.YES);add("m1",70,"momcozy",BreastfeedingStatus.YES)
        add("m2",60,"momcozy",BreastfeedingStatus.YES);add("m3",130,"momcozy",BreastfeedingStatus.NO)
        add("detailed",200,"momcozy",power=PowerPumpStatus.POWER,details=true)
        add("basic",150,"spectra",power=PowerPumpStatus.POWER)
        add("legacy",90,null,power=PowerPumpStatus.UNKNOWN)
        add("earlier",110,null,days=10);add("old",100,null,days=45)
        val bag=app.repository.dao.allBags().single();app.repository.useBag(bag.id,20000,"Feeding",System.currentTimeMillis(),"Synthetic stats")
        app.repository.archivePumpProfile("spectra",true)
    }
    private fun scroll(tag:String){compose.onNodeWithTag("stats-list").performScrollToNode(hasTestTag(tag))}
    private fun open(){compose.onNodeWithText("Stats",substring=false).performClick();scroll("stats-expand-filters");compose.onNodeWithTag("stats-expand-filters").performClick()}
    private fun choose(tag:String,value:String){scroll(tag);compose.onNodeWithTag(tag).performClick();compose.onNodeWithText(value,substring=false).performClick()}
    private fun count(n:Int){scroll("stats-count");compose.onNodeWithTag("stats-count").assertTextEquals("$n sessions")}
    private fun contains(tag:String,text:String){scroll(tag);compose.onNodeWithTag(tag).onChildren().filter(hasText(text,substring=true)).assertCountEquals(1)}
    private fun pump(vararg ids:String){scroll("stats-pumps");compose.onNodeWithTag("stats-pumps").performClick();ids.forEach{compose.onNodeWithTag("stats-pump-$it").performClick()};compose.onNodeWithTag("stats-pumps-done").performClick()}
    private fun date(tag:String,date:LocalDate){scroll(tag);compose.onNodeWithTag(tag).performClick()
        Espresso.onView(isAssignableFrom(DatePicker::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription()="Select synthetic statistics date"
            override fun perform(controller:UiController,view:View){(view as DatePicker).updateDate(date.year,date.monthValue-1,date.dayOfMonth);controller.loopMainThreadUntilIdle()}
        });Espresso.onView(withId(android.R.id.button1)).perform(ViewActions.click())
    }
    @Test fun defaultSummaryAndElapsedSampleUseParentSessionsOnly() {
        open();count(10);contains("stats-summary","1250 mL");contains("stats-summary","Mean per session: 125 mL");contains("stats-summary","Median per session: 120 mL")
        contains("stats-efficiency","Elapsed sample: 9 of 10 sessions")
        contains("stats-efficiency","Active pumping time: 240 min");contains("stats-efficiency","Mean active duration: 24 min")
        contains("stats-efficiency","Milk per pumping minute: 5.21 mL/min")
        contains("stats-efficiency","Known elapsed time: 220 min");contains("stats-efficiency","Milk per known elapsed minute: 5 mL/min")
        contains("stats-efficiency","Left: 100 mL · Right: 80 mL")
        assertEquals(5,runBlocking{app().repository.dao.allPhases().size})
    }
    @Test fun simultaneousFiltersAndMultiPumpComparisonsChangeWithBreastfeeding() {
        open();choose("stats-power","Normal");choose("stats-breastfed","No");pump("spectra","momcozy");count(3)
        contains("stats-group-spectra","Mean: 170 mL · Median: 170 mL");contains("stats-group-momcozy","Mean: 130 mL · Median: 130 mL")
        choose("stats-breastfed","Yes");count(3)
        contains("stats-group-spectra","Mean: 100 mL · Median: 100 mL");contains("stats-group-momcozy","Mean: 65 mL · Median: 65 mL")
    }
    @Test fun archivedAndRenamedPumpReferencesRemainAttributable() {
        open();pump("spectra");count(4);contains("stats-group-spectra","Spectra (archived)")
        runBlocking{val old=app().repository.dao.pumpProfile("spectra")!!;app().repository.savePumpProfile(old.copy(nickname="Renamed Spectra"))}
        compose.waitForIdle();contains("stats-group-spectra","Renamed Spectra (archived)");count(4)
    }
    @Test fun unspecifiedModeAndContextEmptyResultsAndReset() {
        open();choose("stats-power","Not specified");pump("");choose("stats-breastfed","Not specified");count(1)
        choose("stats-breastfed","Yes");count(0);contains("stats-summary","No matching sessions")
        contains("stats-summary","Mean per session: N/A");scroll("stats-reset");compose.onNodeWithTag("stats-reset").performClick();count(10)
    }
    @Test fun datePresetsCustomDatesAndPreviousNavigation() {
        open();choose("stats-date-range","Last 7 days");count(9)
        scroll("stats-previous");compose.onNodeWithTag("stats-previous").performClick();count(1)
        scroll("stats-next");compose.onNodeWithTag("stats-next").performClick();count(9)
        choose("stats-date-range","All time");count(11)
        choose("stats-date-range","Custom date range");date("stats-from",today.minusDays(2));date("stats-through",today.minusDays(2));count(9)
        date("stats-from",today);count(0);scroll("stats-summary");compose.onNodeWithText("Choose a valid date range").assertExists()
    }
    @Test fun contextGroupingRetainsPumpAndModeConstraintsAcrossRecreation() {
        open();pump("spectra");choose("stats-power","Normal");choose("stats-grouping","Breastfeeding context")
        contains("stats-group-YES","1 sessions");contains("stats-group-NO","2 sessions");contains("stats-group-UNKNOWN","0 sessions")
        compose.activityRule.scenario.recreate();count(3);contains("stats-group-NO","2 sessions")
    }
    @Test fun displayUnitsChangeResultsWithoutChangingRecords() {
        open();pump("spectra");choose("stats-power","Normal");choose("stats-breastfed","No");count(2)
        val before=runBlocking{app().repository.dao.allSessions()}
        runBlocking{app().settings.string("displayUnit","oz")};compose.waitForIdle()
        contains("stats-summary","11.5 oz");contains("stats-group-spectra","Mean: 5.75 oz · Median: 5.75 oz")
        assertEquals(before,runBlocking{app().repository.dao.allSessions()})
        assertEquals("mL",runBlocking{app().settings.settings.first()}.inputUnit)
    }
    @Test fun stashAnalyticsGoalsAndChartsRemainIndependentOfPumpFilters() {
        open();choose("stats-breastfed","Yes");pump("spectra");count(1)
        compose.onNodeWithTag("stats-list").performScrollToNode(hasText("Stash",substring=false))
        compose.onNode(hasText("Stash",substring=false) and hasClickAction() and hasAnyAncestor(hasTestTag("stats-list"))).performClick()
        contains("stats-stash-summary","Added 100 mL");contains("stats-stash-summary","Used 20 mL");contains("stats-stash-summary","Current inventory: 80 mL")
        compose.onNodeWithTag("stats-list").performScrollToNode(hasText("Daily inventory change"));compose.onNodeWithText("Daily inventory change").assertIsDisplayed()
        compose.onNodeWithTag("stats-list").performScrollToNode(hasText("Countdown"));compose.onNodeWithText("Edit journey and goal").assertExists()
    }
}
