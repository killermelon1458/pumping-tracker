package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.domain.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only with synthetic values in the emulator's test installation. */
@RunWith(AndroidJUnit4::class)
class CoreWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun logSplitPumpStashAndPartialWithdrawal() {
        val app = compose.activity.application as PumpingApp
        val baseline = runBlocking {
            app.settings.string("unit", "oz")
            app.repository.dao.allSessions().filter {
                dayOf(it.start) >= LocalDate.now().minusDays(6) &&
                    dayOf(it.start) <= LocalDate.now()
            }
        }
        val number = runBlocking { app.repository.dao.nextNumber() }
        compose.waitForIdle()
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Track each breast separately").performClick()
        compose.onNodeWithTag("Left Breast (oz)").performTextInput("5")
        compose.onNodeWithTag("Right Breast (oz)").performTextInput("4")
        compose.onNodeWithTag("Stash It").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Quick Log").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Stash").performClick()
        compose.onNodeWithText("Use soon").assertIsDisplayed()
        compose.onNodeWithText("−").performClick()
        compose.onNodeWithText("#$number · 9 oz · Fridge").performClick()
        compose.onNodeWithTag("Use quantity (oz)").performTextReplacement("2")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("My Stash (oz)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("7 oz")[0].assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onAllNodesWithText("7 oz")[0].assertIsDisplayed()
        compose.onNodeWithText("Stats").performClick()
        compose
            .onNodeWithText(
                "${Volume.format(baseline.sumOf { it.volume } + Volume.parse("5", "oz") + Volume.parse("4", "oz"), "oz")} oz"
            )
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                "${baseline.size + 1} sessions · ${baseline.sumOf { it.activeSeconds } / 60 + 20} active minutes"
            )
            .assertIsDisplayed()
    }
}
