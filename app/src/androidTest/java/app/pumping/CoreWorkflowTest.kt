package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.domain.*
import app.pumping.data.PowerPumpStatus
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
                dayOf(it.start) >= LocalDate.now().minusDays(29) &&
                    dayOf(it.start) <= LocalDate.now()
            }
        }
        val number = runBlocking { app.repository.dao.nextNumber() }
        compose.waitForIdle()
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("Track each breast separately").performScrollTo().performClick()
        compose.onNodeWithTag("Left Breast (oz)").performScrollTo().performTextInput("5")
        compose.onNodeWithTag("Right Breast (oz)").performScrollTo().performTextInput("4")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("Stash It").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {
            runBlocking { app.repository.dao.allBags().any { it.number == number && it.initial == Volume.parse("9", "oz") } } &&
                compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Quick Log").fetchSemanticsNodes().isNotEmpty()
        }
        val ordinary = runBlocking { app.repository.dao.allSessions().first { it.volume == Volume.parse("9", "oz") } }
        org.junit.Assert.assertEquals(PowerPumpStatus.NORMAL, ordinary.powerStatus)
        org.junit.Assert.assertTrue(runBlocking { app.repository.dao.phases(ordinary.id).isEmpty() })
        compose.onNodeWithText("Stash").performClick()
        compose.onNodeWithText("Use soon").assertIsDisplayed()
        compose.onNodeWithText("−").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val bagLabel = "#$number · 9 oz · Fridge"
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(bagLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(bagLabel).performClick()
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
        compose.onNodeWithTag("stats-count").assertTextEquals("${baseline.size + 1} sessions")
        val minutes=java.math.BigDecimal(baseline.sumOf { it.activeSeconds } + 1200)
            .divide(java.math.BigDecimal(60),2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        compose.onNodeWithText("Active pumping time: $minutes min").performScrollTo().assertIsDisplayed()
    }
}
