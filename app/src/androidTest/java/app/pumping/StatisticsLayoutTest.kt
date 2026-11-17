package app.pumping

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun narrowLargeFontFiltersAndComparisonsStayUsable() {
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,1.6f)) {
                PumpingTheme(true) { Box(Modifier.width(280.dp)) {
                    StatsScreen(emptyList(),emptyList(),emptyList(),Settings(),System.currentTimeMillis(),{})
                } }
            }
        }
        fun scroll(tag:String){compose.onNodeWithTag("stats-list").performScrollToNode(hasTestTag(tag))}
        scroll("stats-expand-filters");compose.onNodeWithTag("stats-expand-filters").performClick()
        for(tag in listOf("stats-date-range","stats-pumps","stats-breastfed","stats-power","stats-reset")) {
            scroll(tag);compose.onNodeWithTag(tag).assertIsDisplayed()
            assertTrue(compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().width<=280.dp)
        }
        scroll("stats-grouping");compose.onNodeWithTag("stats-grouping").assertIsDisplayed()
    }
    @Test fun emptySummaryShowsUnavailableMeansAndRates() {
        compose.setContent { PumpingTheme(true) { Column { PumpSummary(app.pumping.domain.pumpStatistics(emptyList()),Settings()) } } }
        compose.onNodeWithText("Mean per session: N/A").assertExists()
        compose.onNodeWithText("Milk per pumping minute: N/A").assertExists()
        compose.onNodeWithText("Milk per known elapsed minute: N/A").assertExists()
    }
}
