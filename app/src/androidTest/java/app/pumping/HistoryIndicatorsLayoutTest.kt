package app.pumping

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.TimerState
import app.pumping.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryIndicatorsLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun allCombinationsHaveAccessibleNonInteractiveIconsOnNarrowScreen() {
        val end=System.currentTimeMillis()
        var record by mutableStateOf(Session(id="icons",start=end-60000,end=end,activeSeconds=60,volume=30000))
        var stashed by mutableStateOf(false)
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,1.3f)) {
                PumpingTheme(true) { Box(Modifier.width(280.dp).testTag("narrow")) {
                    PumpScreen(listOf(record),Settings(),end,TimerState(),{},{},{},{},if(stashed)setOf(record.id) else emptySet())
                } }
            }
        }
        for(breast in BreastfeedingStatus.entries) for(power in PowerPumpStatus.entries) for(stash in listOf(false,true)) {
            compose.runOnIdle { record=record.copy(breastfedBefore=breast,powerStatus=power);stashed=stash }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-icons"))
            val cases=listOf(Triple("breastfed-icons",breast==BreastfeedingStatus.YES,"Breastfed before pumping"),
                Triple("power-icons",power==PowerPumpStatus.POWER,"Power pump"),Triple("stashed-icons",stash,"Stashed"))
            cases.forEach { (tag,shown,label) ->
                val node=compose.onNodeWithTag(tag,useUnmergedTree=true)
                if(shown) {
                    node.assertExists().assertContentDescriptionEquals(label).assertHasNoClickAction()
                    val bounds=node.fetchSemanticsNode().boundsInRoot
                    val screen=compose.onNodeWithTag("narrow").fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left>=screen.left && bounds.right<=screen.right)
                } else node.assertDoesNotExist()
            }
        }
    }
}
