package app.pumping

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorArrangementWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking {app().db.clearAllTables();app().settings.saveTimePickerStyle(app.pumping.domain.TimePickerStyle.SCROLL_WHEELS);app().settings.string("unit","mL");defaults()}
    @After fun cleanup()=runBlocking {defaults()}
    private suspend fun defaults(){for(kind in EditorKind.entries)app().settings.saveEditorLayout(kind,EditorArrangement.default(kind))}
    private fun open(){compose.onNodeWithText("Quick Log").performClick();compose.onNodeWithTag("Duration (minutes)").assertExists()}
    private fun bag(){compose.onNodeWithText("Stash").performClick();compose.onNodeWithText("+").performClick();compose.onNodeWithTag("Volume (mL)").assertExists()}
    private fun edit(){Espresso.closeSoftKeyboard();compose.onNodeWithTag("edit-layout").performScrollTo().performClick()}
    private fun done(){compose.onNodeWithTag("layout-done").performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("edit-layout").fetchSemanticsNodes().isNotEmpty()}}
    private fun show(id:String){compose.onNodeWithTag("layout-show-$id").performScrollTo().performClick()}
    private fun up(id:String){compose.onNodeWithTag("layout-up-$id").performScrollTo().performClick()}
    private fun layout(kind:EditorKind)=runBlocking{app().settings.editorLayout(kind).first()}
    private fun cleanCancel(){compose.onNodeWithTag("editor-cancel").performClick();compose.onNodeWithText("Discard changes?").assertDoesNotExist();compose.onNodeWithText("Discard bag draft?").assertDoesNotExist()}
    @Test fun sectionReorderAndVisibilityPersistIndependentlyAcrossRecreation(){
        bag();edit();up("volume");up("volume");show("notes");done()
        assertEquals("volume",layout(EditorKind.BAG).order.first());compose.onNodeWithTag("section-bag-notes").assertDoesNotExist()
        compose.activityRule.scenario.recreate();compose.onNodeWithTag("section-bag-notes").assertDoesNotExist();cleanCancel()
        compose.onNodeWithText("−").performClick();compose.onNodeWithTag("section-usage-notes").assertExists()
        compose.onNodeWithText("Cancel").performClick();compose.onNodeWithText("Pump").performClick();open()
        compose.onNodeWithTag("section-pump-breastfeeding").assertExists();cleanCancel()
    }
    @Test fun requiredSectionsCannotBeHiddenAndResetCanBeCanceled(){
        open();edit();compose.onNodeWithTag("layout-show-timing").assertIsOn().assertIsNotEnabled();compose.onNodeWithTag("layout-show-milk").assertIsOn().assertIsNotEnabled()
        show("breastfeeding");done();compose.onNodeWithTag("section-pump-breastfeeding").assertDoesNotExist()
        edit();compose.onNodeWithTag("layout-reset").performClick();compose.onNodeWithTag("layout-cancel").performClick()
        compose.onNodeWithTag("section-pump-breastfeeding").assertDoesNotExist()
        edit();compose.onNodeWithTag("layout-reset").performClick();done();compose.onNodeWithTag("section-pump-breastfeeding").assertExists();cleanCancel()
    }
    @Test fun layoutOnlyChangesNeverMarkRecordDirtyAndBackCancelsLayoutFirst(){
        open();edit();up("power");show("notes");Espresso.pressBack()
        compose.onNodeWithTag("edit-layout").assertExists();assertEquals(EditorArrangement.default(EditorKind.PUMP),layout(EditorKind.PUMP))
        edit();up("power");done();Espresso.pressBack();compose.onNodeWithText("Discard changes?").assertDoesNotExist()
    }
    @Test fun reorderingPreservesIncompleteVolumeAndNotesDrafts(){
        bag();compose.onNodeWithTag("Volume (mL)").performTextInput(".")
        compose.onNodeWithTag("Notes (optional)").performScrollTo().performTextInput("Synthetic draft");edit();up("notes");up("notes");done()
        compose.onNodeWithTag("Volume (mL)").assertTextContains(".");compose.onNodeWithTag("Notes (optional)").assertTextContains("Synthetic draft")
        compose.onNodeWithTag("editor-cancel").performClick();compose.onNodeWithText("Discard bag draft?").assertExists()
        compose.onNodeWithTag("confirm-cancel").performClick();compose.onNodeWithTag("Notes (optional)").assertTextContains("Synthetic draft")
    }
    @Test fun hiddenBreastfeedingDefaultsToUnknownWithoutLosingUserDraft(){
        open();compose.onNodeWithText("Yes",substring=false).performScrollTo().performClick();edit();show("breastfeeding");done()
        compose.onNodeWithTag("section-pump-breastfeeding").assertDoesNotExist()
        compose.onNodeWithTag("Volume (mL)").performScrollTo().performTextInput("10");compose.onNodeWithTag("editor-save").performClick()
        compose.waitUntil(10000){runBlocking{app().repository.dao.allSessions().size==1} && compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
        assertEquals(BreastfeedingStatus.YES,runBlocking{app().repository.dao.allSessions().single().breastfedBefore})
        open();compose.onNodeWithTag("section-pump-breastfeeding").assertDoesNotExist();compose.onNodeWithTag("Volume (mL)").performScrollTo().performTextInput("20")
        compose.onNodeWithTag("editor-save").performClick();compose.waitUntil(10000){runBlocking{app().repository.dao.allSessions().size==2}}
        assertEquals(BreastfeedingStatus.UNKNOWN,runBlocking{app().repository.dao.allSessions().single{it.volume==20000L}.breastfedBefore})
    }
    @Test fun existingMeaningfulHiddenInformationIsVisibleAndUnchanged(){
        val now=System.currentTimeMillis();val profile=PumpProfile(type=PumpType.MOBILE_OR_WEARABLE,nickname="Synthetic Wearable")
        val session=Session(start=now-3600000,end=now,activeSeconds=2400,volume=80000,notes="Synthetic notes",pumpProfileId=profile.id,breastfedBefore=BreastfeedingStatus.NO,powerStatus=PowerPumpStatus.POWER)
        runBlocking{app().repository.savePumpProfile(profile);app().repository.saveSession(session,listOf(Allocation(50000,"Fridge",4)),powerPreset(session.id));app().repository.archivePumpProfile(profile.id,true)
            app().settings.saveEditorLayout(EditorKind.PUMP,EditorArrangement.default(EditorKind.PUMP).copy(hidden=EditorKind.PUMP.sections.filterNot{it.required}.map{it.id}.toSet()))}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-${session.id}"));compose.onNodeWithTag("session-${session.id}").performClick()
        for(id in listOf("breastfeeding","power","pump","notes","stash"))compose.onNodeWithTag("section-pump-$id").assertExists()
        compose.onNodeWithTag("Power Pump").assertIsOn();compose.onNodeWithText("Synthetic Wearable",substring=true).assertExists()
        compose.onNodeWithText("Pumping time: 40m").assertExists();cleanCancel()
        assertEquals(session,runBlocking{app().repository.dao.session(session.id)});assertEquals(5,runBlocking{app().repository.dao.phases(session.id).size})
    }
    @Test fun pendingPhaseDraftIsNotSilentlyCommittedByLayoutEditing(){
        open();compose.onNodeWithTag("Power Pump").performScrollTo().performClick()
        compose.onNodeWithText("Timing details (optional)").performClick();compose.onNodeWithTag("power-preset").performScrollTo().performClick()
        compose.onNodeWithTag("Interval 1 minutes").performScrollTo().performTextReplacement("25.")
        // Keep focus: entering layout mode itself must not commit a phase.
        compose.onNodeWithTag("edit-layout").performScrollTo().performClick();up("power");done()
        compose.onNodeWithTag("Interval 1 minutes").assertTextContains("25.")
        compose.onNodeWithText("Pumping time: 40m").assertExists()
        compose.onNodeWithTag("Volume (mL)").performScrollTo().performTextInput("80");Espresso.closeSoftKeyboard();compose.onNodeWithTag("editor-save").performClick()
        compose.waitUntil(15000){runBlocking{app().repository.dao.allSessions().size==1}}
        assertEquals(2700L,runBlocking{app().repository.dao.allSessions().single().activeSeconds})
    }
    @Test fun openTimePickerDraftSurvivesLayoutEditingAndBackDismissesPicker(){
        open();val before=compose.onNodeWithTag("timestamp-start").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("start-time").performScrollTo().performClick();enterWheel("minute",7)
        compose.onNodeWithTag("edit-layout").performScrollTo().performClick();done()
        compose.onNodeWithTag("time-wheels").assertExists();assertEquals(before,compose.onNodeWithTag("timestamp-start").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        Espresso.pressBack();compose.onNodeWithTag("time-wheels").assertDoesNotExist();cleanCancel()
    }
    @Test fun longPressHandleDragReordersWithoutEditingBagData(){
        bag();edit()
        dragEditorHandle(compose,"layout-handle-identity",1200f)
        done();assertTrue("Saved order: ${layout(EditorKind.BAG).order}",layout(EditorKind.BAG).order.indexOf("identity")>0);cleanCancel()
    }
    @Test fun layoutDraftSurvivesActivityRecreationButCancelRestoresSavedLayout(){
        open();edit();show("breastfeeding");up("power");compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("layout-done").assertExists();compose.onNodeWithTag("layout-show-breastfeeding").assertIsOff()
        compose.onNodeWithTag("layout-cancel").performClick();compose.onNodeWithTag("section-pump-breastfeeding").assertExists();cleanCancel()
    }
    @Test fun useMilkReorderingKeepsPartialQuantityReasonAndNotes(){
        runBlocking{app().repository.addBag(Allocation(100000,"Fridge",4),System.currentTimeMillis()-60000)}
        compose.onNodeWithText("Stash").performClick();compose.onNodeWithText("−").performClick();compose.onNodeWithText("#1 · 100 mL · Fridge").performClick()
        compose.onNodeWithTag("Use quantity (mL)").performTextReplacement("25");compose.onNodeWithText("Donation").performClick()
        compose.onNodeWithTag("Notes (optional)").performScrollTo().performTextInput("Synthetic donation");edit();up("notes");up("notes");show("notes");done()
        compose.onNodeWithTag("Use quantity (mL)").assertTextContains("25");compose.onNodeWithText("Save").performClick()
        compose.waitUntil(15000){runBlocking{app().repository.dao.allMovements().size==2}}
        val movement=runBlocking{app().repository.dao.allMovements().single{it.delta<0}};assertEquals(-25000L,movement.delta);assertEquals("Donation",movement.kind);assertEquals("Synthetic donation",movement.notes)
    }
}
