package app.pumping

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.DatePicker
import android.widget.NumberPicker
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.*
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.*
import app.pumping.ui.dateTime
import kotlinx.coroutines.runBlocking
import java.time.*
import org.hamcrest.Matcher
import org.hamcrest.Matchers.equalTo
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorUxWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking {app().db.clearAllTables();app().settings.string("unit","oz");app().settings.bool("24h",false)}
    private fun open(){compose.onNodeWithText("Quick Log").performClick();compose.waitUntil(15000){compose.onAllNodesWithTag("start-date").fetchSemanticsNodes().isNotEmpty()}}
    private fun closed()=compose.waitUntil(15000){compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
    private fun cancel(){compose.onNodeWithTag("editor-cancel").performClick()}
    private fun warning(){compose.onNodeWithText("Discard changes?",substring=false).assertExists()}
    private fun retain(){compose.onNode(hasText("Cancel",substring=false) and !hasTestTag("editor-cancel") and !hasTestTag("time-cancel")).performClick()}
    private fun text(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun saved()=runBlocking{app().repository.dao.allSessions().single()}
    private fun seed(phases:Boolean=false):Session {
        val end=ZonedDateTime.now().minusDays(2).withHour(12).withMinute(36).withSecond(42).withNano(123000000).toInstant().toEpochMilli()
        val old=Session(id="synthetic-editor-ux",start=end-if(phases)3600000 else 1200000,end=end,
            activeSeconds=if(phases)2400 else 1200,volume=30000,powerStatus=if(phases)PowerPumpStatus.POWER else PowerPumpStatus.NORMAL)
        runBlocking{app().repository.saveSession(old,phases=if(phases)powerPreset(old.id) else emptyList())}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-${old.id}"));compose.onNodeWithTag("session-${old.id}").performClick()
        compose.onNodeWithTag("start-date").assertExists();return old
    }
    private fun selectDate(label:String,date:LocalDate,confirm:Boolean=true) {
        compose.onNodeWithTag("${label.lowercase()}-date").performScrollTo().performClick()
        Espresso.onView(isAssignableFrom(DatePicker::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription()="Choose a synthetic date"
            override fun perform(controller:UiController,view:View) {
                (view as DatePicker).updateDate(date.year,date.monthValue-1,date.dayOfMonth);controller.loopMainThreadUntilIdle()
            }
        })
        Espresso.onView(withId(if(confirm)android.R.id.button1 else android.R.id.button2)).perform(ViewActions.click())
    }
    private fun save(){compose.onNodeWithTag("editor-save").performClick();closed()}
    private fun step(name:String,forward:Boolean) {
        Espresso.onView(withTagValue(equalTo("wheel-$name"))).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription()="Scroll one native wheel step"
            override fun perform(controller:UiController,view:View) {
                assertTrue(view.accessibilityNodeProvider.performAction(View.NO_ID,if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null))
                controller.loopMainThreadForAtLeast(500)
            }
        })
    }
    @Test fun untouchedNewEditorAndRecompositionsCancelWithoutWarning() {
        open();compose.activityRule.scenario.recreate();cancel();closed()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
    }
    @Test fun untouchedExistingDetailedRecordCancelsWithoutWarningOrWrites() {
        val old=seed(true);cancel();closed();assertEquals(old,saved())
        assertEquals(5,runBlocking{app().repository.dao.phases(old.id).size})
    }
    @Test fun unchangedAndCanceledPickersDoNotDirtyRecord() {
        val old=seed();val z=Instant.ofEpochMilli(old.start).atZone(ZoneId.systemDefault())
        selectDate("Start",z.toLocalDate().plusDays(1),false)
        compose.onNodeWithTag("start-time").performClick();enterWheel("minute",1)
        assertEquals(dateTime(old.start,Settings()),text("timestamp-start"))
        assertEquals(dateTime(old.end,Settings()),text("timestamp-end"))
        compose.activityRule.scenario.recreate()
        assertTrue(text("time-wheels").endsWith(":01"))
        compose.onNodeWithTag("time-cancel").performClick()
        compose.onNodeWithTag("end-time").performClick();compose.onNodeWithTag("time-done").performClick()
        cancel();closed();assertEquals(old,saved())
    }
    @Test fun dateOnlyPreservesCompleteTimeAndDoesNotOpenTimePicker() {
        val old=seed();val zone=ZoneId.systemDefault();val newDate=Instant.ofEpochMilli(old.start).atZone(zone).toLocalDate().minusDays(1)
        selectDate("Start",newDate)
        compose.onNodeWithTag("time-wheels").assertDoesNotExist()
        val expected=editorDate(old.start,newDate,zone)
        assertEquals(dateTime(expected,Settings()),text("timestamp-start"));save()
        assertEquals(expected,saved().start);assertEquals(expected+1200000,saved().end)
        assertEquals(old.volume,saved().volume)
    }
    @Test fun timeOnlyPreservesDateAndDurationAuthorityAndAppliesOnce() {
        val old=seed()
        compose.onNodeWithTag("start-time").performClick();enterWheel("hour",10);enterWheel("minute",5)
        if(text("time-wheels").substringBefore(":").toInt()>=12) compose.onNodeWithTag("time-period").performClick()
        assertEquals(dateTime(old.start,Settings()),text("timestamp-start"))
        assertEquals(dateTime(old.end,Settings()),text("timestamp-end"))
        compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("time-done").performClick()
        val expected=editorTime(old.start,10,5,ZoneId.systemDefault())
        assertEquals(dateTime(expected,Settings()),text("timestamp-start"))
        compose.onNodeWithTag("Duration (minutes)").performTextReplacement("40")
        assertEquals(dateTime(expected+2400000,Settings()),text("timestamp-end"));save()
        assertEquals(expected,saved().start);assertEquals(expected+2400000,saved().end)
        assertEquals(Instant.ofEpochMilli(old.start).atZone(ZoneId.systemDefault()).toLocalDate(),Instant.ofEpochMilli(saved().start).atZone(ZoneId.systemDefault()).toLocalDate())
    }
    @Test fun detailedDateTimeEditsPreservePhasesAndBothTotals() {
        val old=seed(true);val before=runBlocking{app().repository.dao.phases(old.id)}
        chooseSessionTime(compose,"End",11,5)
        val expected=editorTime(old.end,11,5,ZoneId.systemDefault())
        compose.onNodeWithText("Pumping time: 40m").assertExists();compose.onNodeWithText("Total elapsed time: 1h").assertExists();save()
        assertEquals(expected,saved().end);assertEquals(expected-3600000,saved().start)
        assertEquals(before,runBlocking{app().repository.dao.phases(old.id)})
    }
    @Test fun actualDurationAndPartialTextWarnButRevertingClearsWarning() {
        open();compose.onNodeWithTag("Duration (minutes)").performTextReplacement("40");cancel();warning();retain()
        compose.onNodeWithTag("Duration (minutes)").assertTextContains("40")
        compose.onNodeWithTag("Duration (minutes)").performTextReplacement("20");cancel();closed()
        open();compose.onNodeWithTag("Volume (oz)").performTextInput(".");cancel();warning();retain()
        compose.onNodeWithTag("Volume (oz)").performTextClearance()
        compose.onNodeWithTag("Duration (minutes)").performScrollTo().performTextReplacement(".");cancel();warning();retain()
        compose.onNodeWithTag("Duration (minutes)").performTextReplacement("20");cancel();closed()
    }
    @Test fun independentContextPowerNotesPumpAndStashChangesAreComparedSemantically() {
        val pump=PumpProfile(nickname="Synthetic dirty pump");runBlocking{app().repository.savePumpProfile(pump)}
        open()
        compose.onNodeWithText("Yes",substring=false).performScrollTo().performClick();cancel();warning();retain()
        compose.onNodeWithText("Not specified",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("Power Pump").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()};cancel();warning();retain()
        compose.onNodeWithTag("Power Pump").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("pump-selector").performScrollTo().performClick();compose.onNodeWithTag("pump-option-${pump.id}").performClick();cancel();warning();retain()
        compose.onNodeWithTag("pump-selector").performScrollTo().performClick();compose.onNodeWithTag("pump-option-none").performClick()
        compose.onNodeWithTag("Notes (optional)").performScrollTo().performTextInput("Synthetic note");cancel();warning();retain()
        compose.onNodeWithTag("Notes (optional)").performTextClearance()
        compose.onNodeWithTag("Stash It").performScrollTo().performSemanticsAction(SemanticsActions.OnClick){it()};cancel();warning();retain()
        compose.onNodeWithTag("Stash It").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("Stash It").assertIsOff()
        compose.onNodeWithTag("Power Pump").assertIsOff()
        compose.onNodeWithText("Not specified",substring=false).assertIsSelected()
        cancel();closed()
    }
    @Test fun unitOnlyChangeAndDefaultProfileLoadingAreNotUnsavedRecordEdits() {
        val p=PumpProfile(nickname="Default loaded pump");runBlocking {
            app().repository.savePumpProfile(p)
            val end=System.currentTimeMillis()-60000
            app().repository.saveSession(Session(start=end-60000,end=end,activeSeconds=60,volume=1000,pumpProfileId=p.id))
        }
        open();compose.onNodeWithTag("pump-selector").performScrollTo().assertTextContains(p.nickname)
        compose.onNodeWithContentDescription("Input units").performClick();cancel();closed()
    }
    @Test fun systemBackUsesSamePolicyAndRetainsEditedDraftAfterDialogCancel() {
        open();androidx.test.espresso.Espresso.pressBack();closed()
        open();compose.onNodeWithTag("Notes (optional)").performScrollTo().performTextInput("Synthetic Back draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard();androidx.test.espresso.Espresso.pressBack();warning();retain()
        compose.onNodeWithTag("Notes (optional)").assertTextContains("Synthetic Back draft")
        compose.onNodeWithTag("Notes (optional)").performTextClearance()
        androidx.test.espresso.Espresso.closeSoftKeyboard();androidx.test.espresso.Espresso.pressBack();closed()
    }
    @Test fun wheelBoundaryManualPeriodAndTwentyFourHourModes() {
        seed();compose.onNodeWithTag("start-time").performClick()
        enterWheel("hour",11)
        if(text("time-wheels").substringBefore(":").toInt()>=12)compose.onNodeWithTag("time-period").performClick()
        step("hour",true);assertTrue(text("time-wheels").startsWith("12:"));compose.onNodeWithTag("time-period").assertTextContains("PM")
        step("hour",false);assertTrue(text("time-wheels").startsWith("11:"));compose.onNodeWithTag("time-period").assertTextContains("AM")
        compose.onNodeWithTag("time-period").performClick();assertTrue(text("time-wheels").startsWith("23:"))
        compose.onNodeWithTag("time-cancel").performClick();cancel();closed()
        runBlocking{app().settings.bool("24h",true)};open();compose.onNodeWithTag("start-time").performClick()
        compose.onNodeWithTag("time-period").assertDoesNotExist();enterWheel("hour",23);enterWheel("minute",59)
        assertEquals("23:59",text("time-wheels"));step("hour",true);assertTrue(text("time-wheels").startsWith("00:"))
        compose.onNodeWithTag("time-cancel").performClick();cancel();closed()
    }
    @Test fun wheelDragSnapsAndOnlyOnePickerCanBeExpanded() {
        seed();compose.onNodeWithTag("start-time").performClick()
        val before=text("time-wheels")
        Espresso.onView(withTagValue(equalTo("wheel-minute"))).perform(ViewActions.swipeUp())
        compose.waitUntil(15000){!compose.onNodeWithTag("time-done").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)}
        assertNotEquals(before,text("time-wheels"))
        compose.onNodeWithTag("end-time").performScrollTo().performClick()
        compose.onAllNodesWithTag("time-wheels").assertCountEquals(1)
        compose.onNodeWithTag("time-cancel").performClick();cancel();closed()
    }
    @Test fun dateAndTimeRevertsClearDirtyEvenWithChangedAuthorityHistory() {
        val old=seed();val z=Instant.ofEpochMilli(old.start).atZone(ZoneId.systemDefault())
        selectDate("Start",z.toLocalDate().plusDays(1));cancel();warning();retain()
        selectDate("Start",z.toLocalDate());cancel();closed();assertEquals(old,saved())
        compose.onNodeWithTag("session-${old.id}").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("start-time").fetchSemanticsNodes().isNotEmpty()}
        chooseSessionTime(compose,"Start",10,5);cancel();warning();retain()
        chooseSessionTime(compose,"Start",z.hour,z.minute);cancel();closed();assertEquals(old,saved())
    }
    @Test fun uncommittedPhaseDraftAndRevertUseSemanticTotals() {
        val old=seed(true)
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("25")
        cancel();warning();retain()
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("20")
        cancel();closed();assertEquals(old,saved())
    }
    @Test fun hiddenCustomStashDraftIsWorkButAutomaticAllocationIsNot() {
        val old=seed()
        compose.onNodeWithTag("Stash It").performScrollTo().performClick()
        compose.onNodeWithTag("Quantity (oz)").performScrollTo().performTextReplacement("0.5")
        compose.onNodeWithTag("Stash It").performScrollTo().performClick();cancel();warning();retain()
        compose.onNodeWithTag("Stash It").performScrollTo().performClick()
        compose.onNodeWithTag("Quantity (oz)").performScrollTo().performTextReplacement(Volume.format(old.volume,"oz",5))
        compose.onNodeWithTag("Stash It").performScrollTo().performClick();cancel();closed()
        assertTrue(runBlocking{app().repository.dao.allBags().isEmpty()});assertEquals(old,saved())
    }

    @Test fun canceledStartWheelDoesNotStealInitialEndAuthority() {
        val old=seed();compose.onNodeWithTag("start-time").performClick()
        enterWheel("hour",10);enterWheel("minute",5)
        compose.onNodeWithTag("time-cancel").performClick()
        compose.onNodeWithTag("Duration (minutes)").performTextReplacement("40")
        assertEquals(dateTime(old.end,Settings()),text("timestamp-end"))
        save();assertEquals(old.end,saved().end);assertEquals(old.end-2400000,saved().start)
    }

}
