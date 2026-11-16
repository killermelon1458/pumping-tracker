package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.ui.dateTime
import kotlinx.coroutines.runBlocking
import java.time.*
import java.time.format.DateTimeFormatter
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurationSynchronizationWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking { app().db.clearAllTables();app().settings.string("unit","oz");app().settings.bool("24h",false) }
    private fun label(name:String)="$name: " + compose.onNodeWithTag("timestamp-${name.lowercase()}").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun timestamp(name:String):Long = LocalDateTime.parse(label(name).substringAfter(": "),DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun duration()=compose.onNodeWithTag("Duration (minutes)")
    private fun assertTimes(start:Long,end:Long) {
        compose.onNodeWithTag("timestamp-start").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,dateTime(start,Settings())))
        compose.onNodeWithTag("timestamp-end").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,dateTime(end,Settings())))
    }
    @Test fun validDurationSynchronizesImmediatelyWithoutDone() {
        compose.onNodeWithText("Quick Log").performClick()
        val end=timestamp("End")
        duration().performTextReplacement("40")
        duration().assertIsFocused().assertTextContains("40")
        assertTimes(end-2400000,end)
    }
    @Test fun switchRetainsDurationFocusButMustNotLeaveTimingStale() {
        compose.onNodeWithText("Quick Log").performClick()
        val end=timestamp("End")
        duration().performTextReplacement("40")
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithTag("Power Pump").assertIsOn()
        duration().assertIsFocused().assertTextContains("40")
        assertTimes(end-2400000,end)
    }
    private fun draft()=duration().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun fresh():Long {compose.onNodeWithText("Quick Log").performClick();return timestamp("End")}
    private fun replace(value:String){duration().performScrollTo().performTextReplacement(value)}
    private fun save() {
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
    }
    private fun saved()=runBlocking{app().repository.dao.allSessions().single()}
    private fun edit(id:String){compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-$id"));compose.onNodeWithTag("session-$id").performClick()}
    private fun seed(timer:Boolean=false):Session {
        val end=ZonedDateTime.now().minusDays(2).withHour(12).withMinute(31).withSecond(0).withNano(0).toInstant().toEpochMilli()
        val record=Session(id="synthetic-duration",start=end-if(timer)3600321 else 1200000,end=end,
            activeSeconds=if(timer)1234 else 1200,volume=30000,mode=if(timer)"TIMER" else "MANUAL")
        runBlocking{app().repository.saveSession(record)};edit(record.id);return record
    }
    @Test fun breastfeedingChoiceCannotLeaveAValidDraftUnsynchronized() {
        val end=fresh();replace("40")
        compose.onNodeWithText("Yes",substring=false).performScrollTo().performClick()
        assertTimes(end-2400000,end);assertEquals("40",draft())
    }
    @Test fun selectingAnotherPumpCannotLeaveAValidDraftUnsynchronized() {
        val pump=PumpProfile(nickname="Synthetic duration pump")
        runBlocking{app().repository.savePumpProfile(pump)}
        val end=fresh();replace("40")
        compose.onNodeWithTag("pump-selector").performScrollTo().performClick()
        compose.onNodeWithTag("pump-option-${pump.id}").performClick()
        assertTimes(end-2400000,end);assertEquals("40",draft())
        compose.onNodeWithContentDescription("Pump: ${pump.nickname}").assertExists()
    }
    @Test fun focusingAnotherTextFieldKeepsTimesAndOriginalDraftText() {
        val end=fresh();replace("40")
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        assertTimes(end-2400000,end);assertEquals("40",draft())
    }
    @Test fun hidingKeyboardDoesNotRequireDoneToSynchronize() {
        val end=fresh();replace("40")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        assertTimes(end-2400000,end);assertEquals("40",draft())
    }
    @Test fun alreadyPowerAndRepeatedToggleChangesNeverChangeTimingAuthority() {
        val end=fresh()
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        replace("40");assertTimes(end-2400000,end)
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        assertTimes(end-2400000,end)
        replace("20");assertTimes(end-1200000,end)
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        replace("40");assertTimes(end-2400000,end)
    }
    private fun chooseTime(label:String,hour:Int,minute:Int) = chooseSessionTime(compose,label,hour,minute)
    @Test fun liveDurationRetainsTheMostRecentlyEditedStartThenEnd() {
        val old=seed();val day=Instant.ofEpochMilli(old.end).atZone(ZoneId.systemDefault())
        val start=day.withHour(12).withMinute(0).toInstant().toEpochMilli()
        chooseTime("Start",12,0)
        replace("40");assertTimes(start,start+2400000)
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        replace("41");assertTimes(start,start+2460000)
        chooseTime("End",13,0)
        val end=day.withHour(13).withMinute(0).toInstant().toEpochMilli()
        replace("40");assertTimes(end-2400000,end)
    }
    @Test fun invalidAndBlankDisplayedDurationCannotSaveLastValidValue() {
        val end=fresh()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        replace("40");assertTimes(end-2400000,end)
        for(value in listOf("", ".", "-", "0", "invalid", "1e", "999999999999999999999999")) {
            replace(value);assertEquals(value,draft())
            compose.onNodeWithTag("editor-save").performClick()
            compose.onNodeWithTag("editor-header").assertExists()
            assertTrue(runBlocking{app().repository.dao.allSessions().isEmpty()})
            assertTimes(end-2400000,end)
        }
        replace("40");save();assertEquals(2400,saved().activeSeconds)
    }
    @Test fun decimalsDraftSelectionAndRecreationPreservePhysicalTime() {
        val old=seed()
        replace(".");assertTimes(old.start,old.end)
        replace(".5");assertTimes(old.end-30000,old.end)
        replace("40.50");assertTimes(old.end-2430000,old.end);assertEquals("40.50",draft())
        duration().performTextInputSelection(TextRange(0,2))
        duration().performTextInput("25")
        assertEquals("25.50",draft())
        assertEquals(TextRange(2),duration().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertTimes(old.end-1530000,old.end)
        compose.activityRule.scenario.recreate()
        assertEquals("25.50",draft());assertTimes(old.end-1530000,old.end)
        save();assertEquals(1530,saved().activeSeconds);assertEquals(1530000,saved().end-saved().start)
    }
    @Test fun savingAndReopeningFortyMinutesNeverUsesStaleTwenty() {
        val end=fresh()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        replace("40");assertTimes(end-2400000,end);save()
        val record=saved();assertEquals(2400,record.activeSeconds);assertEquals(2400000,record.end-record.start)
        edit(record.id);assertTimes(end-2400000,end);assertEquals("40.0",draft())
        save();assertEquals(record.copy(updated=saved().updated),saved())
    }
    @Test fun detailedPresetAndPhasesRetainTheirSeparateAuthority() {
        val end=fresh()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        replace("40")
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("power-preset").performScrollTo().performClick()
        duration().assertDoesNotExist()
        compose.onNodeWithText("Pumping time: 40m").assertExists()
        compose.onNodeWithText("Total elapsed time: 1h").assertExists()
        assertTimes(end-3600000,end)
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("25")
        compose.onNodeWithTag("Interval 1 minutes").performImeAction()
        assertTimes(end-3900000,end);save()
        assertEquals(2700,saved().activeSeconds);assertEquals(3900000,saved().end-saved().start)
    }
    @Test fun timerActiveEditDoesNotEraseRecordedPauses() {
        val old=seed(timer=true)
        replace("40");assertTimes(old.start,old.end)
        save();assertEquals(2400,saved().activeSeconds)
        assertEquals(old.start,saved().start);assertEquals(old.end,saved().end)
    }

}
