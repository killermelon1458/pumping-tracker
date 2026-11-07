package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnitsAndIndicatorsWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun app() = compose.activity.application as PumpingApp

    @Before
    fun resetSyntheticData() {
        check(app() is SyntheticTestApplication)
        app().db.clearAllTables()
        runBlocking { app().settings.string("unit", "oz") }
        compose.waitForIdle()
    }

    private fun toggleTo(unit: String) {
        compose.onNodeWithContentDescription("Input units").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodes(
                    hasContentDescription("Input units") and
                        SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                            unit,
                        )
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun scroll(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }

    @Test
    fun indicatorsUseRetainedRelationshipsIncludingEmptyAndImportedBags() {
        val repo = app().repository
        val (linked, independent, bagIds) =
            runBlocking {
                val now = System.currentTimeMillis()
                val linked =
                    Session(start = now - 60000, end = now, activeSeconds = 60, volume = 100000)
                val independent = linked.copy(id = newId(), start = now - 120000, end = now - 60000)
                repo.saveSession(
                    linked,
                    listOf(Allocation(50000, "Fridge", 4), Allocation(50000, "Freezer", 180)),
                )
                repo.saveSession(independent, emptyList())
                Triple(linked, independent, repo.dao.linkedBags(linked.id).map { it.id })
            }
        scroll(hasTestTag("session-${linked.id}"))
        compose.onNodeWithTag("stashed-${linked.id}", useUnmergedTree = true).assertExists()
        compose
            .onNodeWithTag("stashed-${independent.id}", useUnmergedTree = true)
            .assertDoesNotExist()
        runBlocking {
            repo.useBag(bagIds[0], 50000, "Feeding", System.currentTimeMillis(), "")
            repo.useBag(bagIds[1], 50000, "Discard", System.currentTimeMillis(), "")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("stashed-${linked.id}", useUnmergedTree = true).assertExists()
        runBlocking { repo.deleteBag(bagIds[0]) }
        compose.waitForIdle()
        compose.onNodeWithTag("stashed-${linked.id}", useUnmergedTree = true).assertExists()
        runBlocking { repo.deleteBag(bagIds[1]) }
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithTag("stashed-${linked.id}", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        val imported = runBlocking {
            val legacy = LegacyBackup(repo, app().settings)
            val fixture =
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("legacy/synthetic_fixture.pumping")
                    .bufferedReader()
                    .use { it.readText() }
            legacy.import(legacy.prepare(fixture, java.time.ZoneId.of("America/Chicago")))
            repo.dao.allBags().single { it.sourceSession != null }.sourceSession!!
        }
        scroll(hasTestTag("session-$imported"))
        compose.onNodeWithTag("stashed-$imported", useUnmergedTree = true).assertExists()
    }

    @Test
    fun newBagHeaderConvertsBothWaysAndReopensInPersistedInputUnits() {
        runBlocking {
            app().settings.string("inputUnit", "mL")
            app().settings.string("displayUnit", "oz")
        }
        compose.waitForIdle()
        compose.onNodeWithText("Stash").performClick()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag("editor-title").assertTextEquals("New Bag")
        compose.onNodeWithTag("Volume (mL)").performTextInput("100")
        repeat(3) {
            toggleTo("oz")
            toggleTo("mL")
        }
        toggleTo("oz")
        compose.onNodeWithTag("Volume (oz)").assertTextContains(Volume.format(100000, "oz", 5))
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {
            runBlocking { app().repository.dao.allBags().size == 1 } &&
                compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()
        }
        runBlocking {
            assertEquals(100000L, app().repository.dao.allBags().single().initial)
            assertEquals("oz", app().settings.settings.first().inputUnit)
            assertEquals("oz", app().settings.settings.first().displayUnit)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag("editor-title").assertTextEquals("New Bag")
        compose.onNodeWithTag("Volume (oz)").performTextInput("1")
        toggleTo("mL")
        compose
            .onNodeWithTag("Volume (mL)")
            .assertTextContains(Volume.format(Volume.parse("1", "oz"), "mL", 5))
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag("Volume (mL)").assertExists()
        runBlocking {
            assertEquals("mL", app().settings.settings.first().inputUnit)
            assertEquals("oz", app().settings.settings.first().displayUnit)
            assertEquals(100000L, app().repository.dao.allBags().single().initial)
        }
    }

    @Test
    fun editorToggleConvertsPersistsAndProfileSettingsAreIndependent() {
        runBlocking { app().settings.string("unit", "oz") }
        compose.waitForIdle()
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("editor-title").assertTextEquals("Log Pump")
        compose.onAllNodesWithText("oz").assertCountEquals(1)
        compose.onNodeWithTag("Volume (oz)").performTextInput("1.23456")
        val canonical = Volume.parse("1.23456", "oz")
        repeat(2) {
            toggleTo("mL")
            toggleTo("oz")
        }
        toggleTo("mL")
        compose.onNodeWithTag("Volume (mL)").assertExists()
        compose.onNodeWithTag("Stash It").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {
            runBlocking { app().repository.dao.allSessions().any { it.volume == canonical } } &&
                compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()
        }
        runBlocking {
            assertEquals("mL", app().settings.settings.first().inputUnit)
            assertEquals("oz", app().settings.settings.first().displayUnit)
            assertEquals(canonical, app().repository.dao.allBags().single().initial)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Volume (mL)").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Profile").performClick()
        scroll(hasText("Input Units"))
        compose.onNode(hasText("mL") and hasAnyAncestor(hasTestTag("display-units"))).performClick()
        compose.waitForIdle()
        runBlocking { assertEquals("mL", app().settings.settings.first().inputUnit) }
        compose.onNode(hasText("oz") and hasAnyAncestor(hasTestTag("input-units"))).performClick()
        compose.waitForIdle()
        runBlocking {
            assertEquals("oz", app().settings.settings.first().inputUnit)
            assertEquals("mL", app().settings.settings.first().displayUnit)
            assertEquals(canonical, app().repository.dao.allSessions().single().volume)
        }
        compose.onNodeWithText("Pump").performClick()
        val id = runBlocking { app().repository.dao.allSessions().single().id }
        scroll(hasTestTag("session-$id"))
        compose
            .onNodeWithTag("session-$id")
            .assertTextContains("${Volume.format(canonical, "mL")} mL")
    }
}
