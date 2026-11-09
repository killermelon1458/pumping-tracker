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
class QuickLogTimingWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun app() = compose.activity.application as SyntheticTestApplication
    @Before fun reset() = runBlocking { app().db.clearAllTables(); app().settings.string("unit", "oz") }

    @Test fun durationCommitsOnlyOnDoneAndMovesStartNotEnd() {
        compose.onNodeWithText("Quick Log").performClick()
        val endText = compose.onNodeWithText("Ended:", substring=true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
        compose.onNodeWithTag("Active duration (minutes)").performTextReplacement("25")
        compose.onNodeWithTag("Active duration (minutes)").performImeAction()
        compose.onNodeWithText("Ended:", substring=true).assertTextContains(endText.removePrefix("[").removeSuffix("]"))
        compose.onNodeWithTag("Volume (oz)").performTextInput("1")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) { runBlocking { app().repository.dao.allSessions().isNotEmpty() } }
        val saved = runBlocking { app().repository.dao.allSessions().single() }
        assertEquals(1500,saved.activeSeconds)
        assertEquals(1500000,saved.end-saved.start)
    }

    @Test fun pausedTimerRecordKeepsActiveSecondsWhenOpenedAndSaved() {
        val end=System.currentTimeMillis()-60000
        val original=Session(id="paused-synthetic",start=end-3600000,end=end,activeSeconds=1234,volume=30000,mode="TIMER")
        runBlocking { app().repository.saveSession(original) }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("session-${original.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session-${original.id}").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty() }
        val saved=runBlocking { app().repository.dao.session(original.id) }!!
        assertEquals(original.copy(updated=saved.updated),saved)
    }

    @Test fun futureSaveReviewAndSaveAnywayPreserveExistingValues() {
        val end=System.currentTimeMillis()+3600000
        val original=Session(id="future-synthetic",start=end-1200000,end=end,activeSeconds=1200,volume=30000)
        runBlocking { app().repository.saveSession(original) }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("session-${original.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session-${original.id}").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("This session ends in the future. Did you mean to save it this way?").assertExists()
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("Edit Pump").assertExists()
        assertEquals(original,runBlocking { app().repository.dao.session(original.id) })
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save anyway").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty() }
        val saved=runBlocking { app().repository.dao.session(original.id) }!!
        assertEquals(original.copy(updated=saved.updated),saved)
    }
}
