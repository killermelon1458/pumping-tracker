package app.pumping

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pumping.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BreastfeedingContextWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking { app().db.clearAllTables();app().settings.string("unit","oz") }
    private fun saved()=runBlocking { app().repository.dao.allSessions().single() }
    private fun waitClosed()=compose.waitUntil(15000) { compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty() }
    private fun edit(id:String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-$id"))
        compose.onNodeWithTag("session-$id").performClick()
    }
    @Test fun oneTapContextSurvivesRecreationAndEditWithoutChangingTimes() {
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithText("Unknown",substring=false).assertIsSelected()
        compose.onNodeWithText("Yes",substring=false).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Yes",substring=false).assertIsSelected()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        compose.onNodeWithText("Save").performClick();waitClosed()
        val first=saved();assertEquals(BreastfeedingStatus.YES,first.breastfedBefore)
        compose.activityRule.scenario.recreate();edit(first.id)
        compose.onNodeWithText("Yes",substring=false).assertIsSelected()
        compose.onNodeWithText("Save").performClick();waitClosed()
        assertEquals(first.copy(updated=saved().updated),saved())
        edit(first.id);compose.onNodeWithText("No",substring=false).performClick()
        compose.onNodeWithText("Save").performClick();waitClosed()
        assertEquals(BreastfeedingStatus.NO,saved().breastfedBefore)
        edit(first.id);compose.onNodeWithText("Unknown",substring=false).performClick()
        compose.onNodeWithText("Save").performClick();waitClosed()
        val last=saved();assertEquals(BreastfeedingStatus.UNKNOWN,last.breastfedBefore)
        assertEquals(first.start,last.start);assertEquals(first.end,last.end);assertEquals(first.activeSeconds,last.activeSeconds)
    }
    @Test fun importedIosSessionIsTruthfullyUnknown() {
        runBlocking {
            val text=InstrumentationRegistry.getInstrumentation().context.assets.open("legacy/synthetic_fixture.pumping").bufferedReader().use { it.readText() }
            val repo=app().repository;val legacy=LegacyBackup(repo,app().settings)
            legacy.import(legacy.prepare(text))
        }
        val first=runBlocking { app().repository.dao.allSessions().first() }
        edit(first.id)
        compose.onNodeWithText("Unknown",substring=false).assertIsSelected()
        compose.onNodeWithText("Save").performClick();waitClosed()
        assertEquals(first.copy(updated=runBlocking { app().repository.dao.session(first.id) }!!.updated),runBlocking { app().repository.dao.session(first.id) })
    }
    @Test fun nativeV2SafImportRestoresExplicitContext() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val source=Uri.parse("content://${instrumentation.context.packageName}.synthetic-backup/synthetic-context-v2.json")
        val original=Session(id="synthetic-native-context",start=1000,end=61000,activeSeconds=60,volume=1000,breastfedBefore=BreastfeedingStatus.YES)
        val text=runBlocking {
            app().repository.saveSession(original)
            Backup(app().repository,app().settings).export().also { app().db.clearAllTables() }
        }
        compose.activity.contentResolver.openOutputStream(source,"wt")!!.use { it.write(text.toByteArray()) }
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_OPEN_DOCUMENT)return null
                return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("Profile").performClick()
            val label="Import backup (.pumping or JSON)"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Import native backup?").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Import",substring=false).performClick()
            compose.waitUntil(15000) { runBlocking { app().repository.dao.session(original.id) } != null }
            assertEquals(original,saved())
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
