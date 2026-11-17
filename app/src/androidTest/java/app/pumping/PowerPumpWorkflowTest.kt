package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerPumpWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking { app().db.clearAllTables();app().settings.saveTimePickerStyle(app.pumping.domain.TimePickerStyle.SCROLL_WHEELS);app().settings.string("unit","oz") }
    private fun saved()=runBlocking { app().repository.dao.allSessions().single() }
    private fun closed()=compose.waitUntil(15000){compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
    private fun save(){compose.onNodeWithText("Save",substring=false).performClick();closed()}
    private fun edit(id:String){compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-$id"));compose.onNodeWithTag("session-$id").performClick()}
    @Test fun normalDefaultAndToggleOnlyDoNotInventIntervals() {
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Power Pump").assertIsOff()
        compose.onNodeWithText("Timing details (optional)").assertDoesNotExist()
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Use 20 / 10 / 10 / 10 / 10 preset").assertDoesNotExist()
        compose.onNodeWithTag("Duration (minutes)").performScrollTo().performTextReplacement("25")
        compose.onNodeWithTag("Duration (minutes)").performImeAction()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        save()
        val s=saved();assertEquals(PowerPumpStatus.POWER,s.powerStatus);assertFalse(s.elapsedKnown)
        assertEquals(1500,s.activeSeconds);assertEquals(1500000,s.end-s.start)
        assertTrue(runBlocking{app().repository.dao.allPhases().isEmpty()})
        compose.activityRule.scenario.recreate();edit(s.id)
        compose.onNodeWithTag("Power Pump").assertIsOn()
        compose.onNodeWithTag("Duration (minutes)").assertTextContains("25.0")
        save();assertEquals(s.copy(updated=saved().updated),saved())
    }
    @Test fun presetCustomChangesReorderAndReopenKeepOneParentAndStableIds() {
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("power-preset").performScrollTo().performClick()
        compose.onNodeWithText("Pumping time: 40m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Total elapsed time: 1h").assertExists()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("3")
        save()
        val first=saved();val phases=runBlocking{app().repository.dao.phases(first.id)}
        assertEquals(2400,first.activeSeconds);assertEquals(3600000,first.end-first.start);assertEquals(5,phases.size)
        compose.activityRule.scenario.recreate();edit(first.id)
        compose.onNodeWithTag("power-preset").assertDoesNotExist()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("25")
        compose.onNodeWithTag("Interval 1 minutes").performImeAction()
        compose.onNodeWithTag("phase-down-0").performScrollTo().performClick()
        compose.onNodeWithText("Add rest interval").performScrollTo().performClick()
        compose.onNodeWithTag("phase-remove-5").performScrollTo().performClick()
        compose.onNodeWithText("Confirm",substring=false).performClick()
        save()
        val changed=saved();val newPhases=runBlocking{app().repository.dao.phases(first.id)}
        assertEquals(2700,changed.activeSeconds);assertEquals(3900000,changed.end-changed.start)
        assertEquals(first.end,changed.end);assertEquals(first.volume,changed.volume)
        assertEquals(phases.map{it.id}.toSet(),newPhases.map{it.id}.toSet())
        assertEquals(phases[1].id,newPhases[0].id)
        assertEquals(1,runBlocking{app().repository.dao.allSessions().size})
    }
    @Test fun invalidIntermediatePhaseInputCannotSaveOrChangeParentUntilCommitted() {
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("power-preset").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("-")
        compose.onNodeWithText("Pumping time: 40m").assertExists()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        compose.onNodeWithText("Save",substring=false).performClick()
        assertTrue(runBlocking{app().repository.dao.allSessions().isEmpty()})
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("15")
        compose.onNodeWithTag("Interval 1 minutes").performImeAction()
        save();assertEquals(2100,saved().activeSeconds);assertEquals(3300000,saved().end-saved().start)
    }
    @Test fun oldUnknownAndPausedTimerPowerMarkingPreserveRecordedSpan() {
        val end=System.currentTimeMillis()-60000
        val old=Session(id="synthetic-paused",start=end-3600321,end=end,activeSeconds=1200,volume=10000,mode="TIMER")
        runBlocking{app().repository.saveSession(old)}
        edit(old.id)
        compose.onNodeWithTag("Power Pump").assertIsOff()
        compose.onNodeWithText("Not specified for this historical record").assertExists()
        compose.onNodeWithTag("Power Pump").performClick()
        save()
        val marked=saved();assertEquals(old.copy(powerStatus=PowerPumpStatus.POWER,updated=marked.updated),marked)
        assertTrue(marked.elapsedKnown);assertTrue(runBlocking{app().repository.dao.allPhases().isEmpty()})
        edit(old.id)
        compose.onNodeWithTag("Duration (minutes)").performTextReplacement("25")
        compose.onNodeWithTag("Duration (minutes)").performImeAction()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithText("Add pump interval").performScrollTo().performClick()
        compose.onNodeWithText("Add rest interval").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 2 minutes").performScrollTo().performTextReplacement("35")
        compose.onNodeWithTag("Interval 2 minutes").performImeAction();save()
        assertEquals(old.start,saved().start);assertEquals(old.end,saved().end);assertEquals(1500,saved().activeSeconds)
        assertEquals(2,runBlocking{app().repository.dao.phases(old.id).size})
    }
    @Test fun toggleOffRequiresConfirmationAndRetainsKnownTotals() {
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("power-preset").performScrollTo().performClick()
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Remove timing intervals?").assertExists()
        compose.onNode(hasText("Cancel",substring=false) and !hasTestTag("editor-cancel")).performClick()
        compose.onNodeWithTag("Power Pump").assertIsOn()
        compose.onNodeWithTag("Power Pump").performClick()
        compose.onNodeWithText("Confirm",substring=false).performClick()
        compose.onNodeWithTag("Power Pump").assertIsOff()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        save();val s=saved();assertEquals(PowerPumpStatus.NORMAL,s.powerStatus)
        assertEquals(2400,s.activeSeconds);assertEquals(2400000,s.end-s.start);assertFalse(s.elapsedKnown)
        assertTrue(runBlocking{app().repository.dao.allPhases().isEmpty()})
    }
    @Test fun basicToggleDoesNotMoveTimesAndFortyMinutesKeepsEnd() {
        compose.onNodeWithText("Quick Log").performClick()
        fun text(prefix:String)=compose.onNodeWithTag("timestamp-${prefix.removeSuffix(":").lowercase()}").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        val start=text("Start:");val end=text("End:")
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        assertEquals(start,text("Start:"));assertEquals(end,text("End:"))
        compose.onNodeWithText("anchor",substring=true,ignoreCase=true).assertDoesNotExist()
        compose.onNodeWithTag("Duration (minutes)").performScrollTo().performTextReplacement("40")
        compose.onNodeWithTag("Duration (minutes)").performImeAction()
        assertEquals(end,text("End:"))
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        assertEquals(end,text("End:"))
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1");save()
        assertEquals(2400,saved().activeSeconds);assertEquals(2400000,saved().end-saved().start)
    }
    @Test fun deletingFinalIntervalConfirmsAndReturnsToBasic() {
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithText("Add pump interval").performScrollTo().performClick()
        compose.onNodeWithTag("Duration (minutes)").assertDoesNotExist()
        compose.onNodeWithTag("phase-remove-0").performScrollTo().performClick()
        compose.onNode(hasText("Cancel",substring=false) and !hasTestTag("editor-cancel")).performClick()
        compose.onNodeWithTag("Interval 1 minutes").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("phase-remove-0").performScrollTo().performClick()
        compose.onNodeWithText("Confirm",substring=false).performClick()
        compose.onNodeWithTag("Duration (minutes)").assertExists()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1");save()
        assertTrue(runBlocking{app().repository.dao.allPhases().isEmpty()})
    }

    private fun chooseTime(label:String,hour:Int,minute:Int) = chooseSessionTime(compose,label,hour,minute)
    @Test fun detailedTimeEditsMoveOtherEndpointAndPhaseEditsKeepLatestTimestamp() {
        val end=java.time.ZonedDateTime.now().minusDays(2).withHour(9).withMinute(44).withSecond(0).withNano(0).toInstant().toEpochMilli()
        val original=Session(id="detailed-edit",start=end-3600000,end=end,activeSeconds=2400,volume=30000,powerStatus=PowerPumpStatus.POWER,notes="Synthetic unchanged note")
        val phases=app.pumping.domain.powerPreset(original.id)
        runBlocking{app().repository.saveSession(original,phases=phases)}
        edit(original.id)
        compose.onNodeWithTag("Duration (minutes)").assertDoesNotExist()
        compose.onNodeWithText("Pumping time: 40m").assertExists()
        compose.onNodeWithText("Total elapsed time: 1h").assertExists()
        chooseTime("Start",9,0)
        chooseTime("End",9,44)
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("25")
        compose.onNodeWithTag("Interval 1 minutes").performImeAction()
        compose.onNodeWithText("Pumping time: 45m").assertExists()
        compose.onNodeWithText("Total elapsed time: 1h 5m").assertExists()
        save()
        assertEquals(end,saved().end);assertEquals(end-3900000,saved().start)
        edit(original.id);chooseTime("Start",9,0)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Timing details (optional)").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 2 minutes").performScrollTo().performTextReplacement("15")
        compose.onNodeWithTag("Interval 2 minutes").performImeAction();save()
        val changed=saved();assertEquals(2700,changed.activeSeconds)
        assertEquals(4200000,changed.end-changed.start)
        val expectedStart=java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).withHour(9).withMinute(0).toInstant().toEpochMilli()
        assertEquals(expectedStart,changed.start)
        assertEquals(original.volume,changed.volume);assertEquals(original.notes,changed.notes)
        assertEquals(phases.map{it.id},runBlocking{app().repository.dao.phases(original.id)}.map{it.id})
    }

    @Test fun togglePreservesPreviouslyEditedStartAuthority() {
        val end=java.time.ZonedDateTime.now().minusDays(2).withHour(9).withMinute(44).withSecond(0).withNano(0).toInstant().toEpochMilli()
        val old=Session(id="basic-authority",start=end-1200000,end=end,activeSeconds=1200,volume=30000,powerStatus=PowerPumpStatus.NORMAL)
        runBlocking{app().repository.saveSession(old)}
        edit(old.id);chooseTime("Start",9,0)
        compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithTag("Duration (minutes)").performScrollTo().performTextReplacement("40")
        compose.onNodeWithTag("Duration (minutes)").performImeAction();save()
        val start=java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).withHour(9).withMinute(0).toInstant().toEpochMilli()
        assertEquals(start,saved().start);assertEquals(start+2400000,saved().end)
        assertEquals(2400,saved().activeSeconds)
    }

    @Test fun equivalentDurationTextDoesNotRetimeExistingBasicRecord() {
        val end=System.currentTimeMillis()-60000
        val old=Session(id="existing-basic-span",start=end-3600000,end=end,activeSeconds=1200,
            volume=30000,powerStatus=PowerPumpStatus.POWER)
        runBlocking{app().repository.saveSession(old)}
        edit(old.id)
        compose.onNodeWithTag("Duration (minutes)").performTextReplacement("20")
        compose.onNodeWithTag("Duration (minutes)").performImeAction();save()
        assertEquals(old.copy(updated=saved().updated),saved())
    }

}
