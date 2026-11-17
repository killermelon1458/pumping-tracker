package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorCustomizationPlacementWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking {
        app().db.clearAllTables();app().settings.string("unit","mL")
        app().settings.saveTimePickerStyle(TimePickerStyle.CLOCK_FACE)
        EditorKind.entries.forEach {app().settings.saveEditorLayout(it,EditorArrangement.default(it))}
    }
    private fun verify(kind:EditorKind) {
        val before=runBlocking{app().repository.dao.allMovements()}
        val footer=hasTestTag("editor-customization-footer")
        val sticky=hasTestTag("editor-sticky-header")
        val buttons=listOf("time-picker-style","edit-layout")
        for(tag in buttons) compose.onNodeWithTag(tag)
            .assert(hasAnyAncestor(footer)).assert(hasAnyAncestor(hasTestTag("editor-scroll-content")))
            .assert(!hasAnyAncestor(sticky))
        compose.onNodeWithTag("edit-layout").performScrollTo()
        val picker=compose.onNodeWithTag("time-picker-style").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val layout=compose.onNodeWithTag("edit-layout").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(picker.right<=layout.left+1f);assertEquals(picker.center.y,layout.center.y,1f)
        val last=compose.onNodeWithTag("section-${kind.key}-notes").fetchSemanticsNode().boundsInRoot
        val bottom=compose.onNodeWithTag("editor-customization-footer").fetchSemanticsNode().boundsInRoot
        assertTrue("Customization follows the last ordinary section",last.bottom<=bottom.top)
        compose.onNodeWithTag("time-picker-style").performClick()
        compose.onNodeWithTag("picker-style-SCROLL_WHEELS").performClick()
        compose.waitUntil(10000){runBlocking{app().settings.timePickerStyle.first()==TimePickerStyle.SCROLL_WHEELS}}
        compose.onNodeWithTag("edit-layout").performClick()
        compose.onNodeWithTag("editor-customization-footer").assertDoesNotExist()
        compose.onNodeWithTag("layout-cancel").performClick()
        compose.onNodeWithText("Cancel",substring=false).performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Discard bag draft?").assertDoesNotExist()
        compose.onNodeWithText("Discard usage draft?").assertDoesNotExist()
        assertEquals(before,runBlocking{app().repository.dao.allMovements()})
        assertTrue(runBlocking{app().repository.dao.allSessions().isEmpty()})
    }
    @Test fun pumpCustomizationIsCompactScrollableFooterAndDoesNotEditRecord(){
        compose.onNodeWithText("Quick Log").performClick();verify(EditorKind.PUMP)
    }
    @Test fun bagCustomizationIsCompactScrollableFooterAndDoesNotEditRecord(){
        compose.onNodeWithText("Stash").performClick();compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag("Volume (mL)").assertExists();verify(EditorKind.BAG)
    }
    @Test fun usageCustomizationIsCompactScrollableFooterAndDoesNotEditRecord(){
        runBlocking{app().repository.addBag(Allocation(100000,"Fridge",4),System.currentTimeMillis()-60000)}
        compose.onNodeWithText("Stash").performClick();compose.onNodeWithText("−").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("section-usage-quantity").fetchSemanticsNodes().isNotEmpty()};verify(EditorKind.USAGE)
    }
}
