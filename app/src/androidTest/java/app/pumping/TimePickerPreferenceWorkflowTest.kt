package app.pumping

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.*
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.*
import app.pumping.ui.dateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.*
import org.hamcrest.Matcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimePickerPreferenceWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking {
        app().db.clearAllTables();app().settings.string("unit","mL");app().settings.bool("24h",false)
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${app().packageName} android.permission.POST_NOTIFICATIONS").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
        app().settings.saveTimePickerStyle(TimePickerStyle.CLOCK_FACE)
        for(kind in EditorKind.entries)app().settings.saveEditorLayout(kind,EditorArrangement.default(kind))
    }
    @After fun cleanup()=runBlocking {app().settings.saveTimePickerStyle(TimePickerStyle.CLOCK_FACE)}
    private fun style(value:TimePickerStyle, scroll:Boolean=true) {
        val control=compose.onNodeWithTag("time-picker-style")
        if (scroll) control.performScrollTo()
        control.performClick()
        compose.onNodeWithTag("picker-style-${value.name}").performClick()
        compose.waitUntil(10000) { runBlocking {app().settings.settings.first().timePickerStyle==value} }
        compose.onNodeWithTag("time-picker-style").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,value.label))
    }
    private fun open(){compose.onNodeWithText("Quick Log").performClick();compose.onNodeWithTag("start-time").assertExists()}
    private fun cleanCancel(){compose.onNodeWithTag("editor-cancel").performClick();compose.onNodeWithText("Discard changes?").assertDoesNotExist();compose.onNodeWithText("Discard bag draft?").assertDoesNotExist()}
    private fun stamp(label:String)=compose.onNodeWithTag("timestamp-${label.lowercase()}").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun seed(detailed:Boolean=false): Session {
        val start=ZonedDateTime.now().minusDays(2).withHour(10).withMinute(0).withSecond(42).withNano(123000000).toInstant().toEpochMilli()
        val session=Session(id="synthetic-picker-style",start=start,end=start+if(detailed)3600000 else 1200000,
            activeSeconds=if(detailed)2400 else 1200,volume=30000,powerStatus=if(detailed)PowerPumpStatus.POWER else PowerPumpStatus.NORMAL)
        runBlocking {app().repository.saveSession(session,phases=if(detailed)powerPreset(session.id) else emptyList())}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-${session.id}"));compose.onNodeWithTag("session-${session.id}").performClick()
        compose.onNodeWithTag("start-time").assertExists();return session
    }
    @Test fun clockFaceIsDefaultAndCancelOrBackNeverChangesRecord(){
        val original=seed();val before=stamp("Start")
        compose.onNodeWithTag("time-picker-style").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Clock face"))
        compose.onNodeWithTag("start-time").performScrollTo().performClick();clockTime(23,59,false)
        assertEquals(before,stamp("Start"));closeClock(false)
        compose.onNodeWithTag("start-time").performScrollTo().performClick();clockTime(11,5,false);Espresso.pressBack()
        assertEquals(before,stamp("Start"));cleanCancel();assertEquals(original,runBlocking{app().repository.dao.session(original.id)})
    }
    @Test fun clockFaceCommitPreservesDatePrecisionAndMidnightTimingAuthority(){
        val original=seed();compose.onNodeWithTag("start-time").performScrollTo().performClick();clockTime(23,59,false);closeClock(true)
        val expected=editorTime(original.start,23,59,ZoneId.systemDefault())
        assertEquals(dateTime(expected,Settings()),stamp("Start"));assertEquals(dateTime(expected+1200000,Settings()),stamp("End"))
        compose.onNodeWithTag("Duration (minutes)").performScrollTo().performTextReplacement("40")
        assertEquals(dateTime(expected,Settings()),stamp("Start"));assertEquals(dateTime(expected+2400000,Settings()),stamp("End"))
        compose.onNodeWithTag("editor-save").performClick();compose.waitUntil(15000){compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
        val saved=runBlocking{app().repository.dao.session(original.id)!!};assertEquals(expected,saved.start);assertEquals(expected+2400000,saved.end);assertEquals(2400L,saved.activeSeconds)
        assertEquals(original.volume,saved.volume)
    }
    @Test fun clockFaceSupportsTwentyFourHourSelection(){
        runBlocking{app().settings.bool("24h",true)};val original=seed()
        compose.onNodeWithTag("end-time").performScrollTo().performClick();clockTime(23,59,true);closeClock(true)
        val end=editorTime(original.end,23,59,ZoneId.systemDefault())
        compose.onNodeWithTag("editor-save").performClick();compose.waitUntil(15000){compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
        val saved=runBlocking{app().repository.dao.session(original.id)!!};assertEquals(end,saved.end);assertEquals(end-1200000,saved.start)
    }
    @Test fun stylePreferencePersistsAndAppliesAcrossAllThreeEditors(){
        open();style(TimePickerStyle.SCROLL_WHEELS);cleanCancel();compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Stash").performClick();compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag("time-picker-style").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Scroll wheels"))
        compose.onNodeWithTag("date-time").performScrollTo().performClick();compose.onNodeWithTag("time-wheels").assertExists();compose.onNodeWithTag("time-cancel").performClick();cleanCancel()
        compose.onNodeWithText("−").performClick();style(TimePickerStyle.CLOCK_FACE)
        compose.onNodeWithTag("date-time").performScrollTo().performClick();clockTime(clock24=false);closeClock(false)
        compose.onNodeWithText("Cancel",substring=false).performClick();compose.onNodeWithText("Discard usage draft?").assertDoesNotExist()
        compose.onNodeWithText("Pump").performClick();open();compose.onNodeWithTag("start-time").performScrollTo().performClick();clockTime(clock24=false);closeClock(false);cleanCancel()
    }
    @Test fun switchingPresentationKeepsPendingWheelValueWithoutDirtyingRecord(){
        val original=seed();style(TimePickerStyle.SCROLL_WHEELS)
        val before=stamp("Start");compose.onNodeWithTag("start-time").performScrollTo().performClick();enterWheel("minute",7)
        val pending=compose.onNodeWithTag("time-wheels").fetchSemanticsNode().config[SemanticsProperties.StateDescription].split(':').map{it.toInt()}
        style(TimePickerStyle.CLOCK_FACE)
        assertEquals(pending[0] to pending[1],clockTime());assertEquals(before,stamp("Start"));closeClock(false)
        cleanCancel();assertEquals(original,runBlocking{app().repository.dao.session(original.id)})
    }
    @Test fun pickerStyleMenuDoesNotCommitPendingPowerPhaseDraft(){
        val original=seed(true);compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("25.")
        style(TimePickerStyle.SCROLL_WHEELS)
        compose.onNodeWithText("Pumping time: 40m").assertExists();compose.onNodeWithTag("Interval 1 minutes").assertTextContains("25.")
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("20");cleanCancel()
        assertEquals(original,runBlocking{app().repository.dao.session(original.id)})
    }
    @Test fun clockFaceDetailedEndEditRetainsIntervalsAndActiveTime(){
        val original=seed(true);compose.onNodeWithTag("end-time").performScrollTo().performClick();clockTime(23,59,false);closeClock(true)
        val expected=editorTime(original.end,23,59,ZoneId.systemDefault())
        compose.onNodeWithText("Pumping time: 40m").assertExists();compose.onNodeWithText("Total elapsed time: 1h").assertExists()
        compose.onNodeWithTag("editor-save").performClick();compose.waitUntil(15000){compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
        val saved=runBlocking{app().repository.dao.session(original.id)!!};assertEquals(expected,saved.end);assertEquals(expected-3600000,saved.start);assertEquals(2400L,saved.activeSeconds)
        assertEquals(powerPreset(original.id).map{it.kind to it.seconds},runBlocking{app().repository.dao.phases(original.id).map{it.kind to it.seconds}})
    }
    @Test fun dateOnlySelectionDoesNotOpenClockAndPreservesTime(){
        val original=seed();val date=dayOf(original.start).minusDays(1)
        compose.onNodeWithTag("start-date").performClick()
        Espresso.onView(isAssignableFrom(DatePicker::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription()="Select a synthetic date without selecting a time"
            override fun perform(controller:UiController,view:View){(view as DatePicker).updateDate(date.year,date.monthValue-1,date.dayOfMonth);controller.loopMainThreadUntilIdle()}
        });Espresso.onView(withId(android.R.id.button1)).perform(ViewActions.click())
        Espresso.onView(isAssignableFrom(TimePicker::class.java)).check(doesNotExist())
        assertEquals(dateTime(editorDate(original.start,date,ZoneId.systemDefault()),Settings()),stamp("Start"))
    }
    @Test fun reminderAndQuickLogReminderUseGlobalStyleWithIndependentControls(){
        compose.onNodeWithText("Set Reminder").performClick();compose.onNodeWithTag("remind at-time").performScrollTo().performClick();clockTime(clock24=false);closeClock(false)
        style(TimePickerStyle.SCROLL_WHEELS,scroll=false);compose.onNodeWithTag("remind at-time").performScrollTo().performClick();compose.onNodeWithTag("time-wheels").assertExists()
        compose.onNodeWithTag("time-cancel").performClick();compose.onNodeWithText("Cancel",substring=false).performClick()
        open();compose.onNodeWithTag("Next Pump").performScrollTo().performClick()
        compose.onNodeWithTag("remind at-time").performScrollTo().performClick();compose.onNodeWithTag("time-wheels").assertExists();compose.onNodeWithTag("time-cancel").performClick()
    }
    @Test fun clockFaceDraftSurvivesActivityRecreationWithoutChangingSavedTimes(){
        val original=seed();compose.onNodeWithTag("start-time").performScrollTo().performClick();clockTime(11,7,false)
        compose.activityRule.scenario.recreate();assertEquals(11 to 7,clockTime())
        assertEquals(dateTime(original.start,Settings()),stamp("Start"));closeClock(false);cleanCancel()
        assertEquals(original,runBlocking{app().repository.dao.session(original.id)})
    }

}
