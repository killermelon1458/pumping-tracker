package app.pumping

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeWheelLayoutTest {
    @get:Rule val compose=createComposeRule()
    private fun render(width:Dp,scale:Float) {
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                PumpingTheme(true) { Box(Modifier.width(width)) {
                    TimeWheelPicker("Start",System.currentTimeMillis(),false,true,{}, {_,_->})
                } }
            }
        }
    }
    @Test fun normalInlineHeightStaysCompact() {
        render(320.dp,1f)
        compose.onNodeWithTag("inline-time-picker").assertHeightIsAtLeast(130.dp)
        assertTrue(compose.onNodeWithTag("inline-time-picker").getUnclippedBoundsInRoot().height <= 170.dp)
        compose.onNodeWithTag("time-done").assertIsDisplayed();compose.onNodeWithTag("time-cancel").assertIsDisplayed()
    }
    @Test fun narrowWidthFallsBackToDialogWithAccessibleActions() {
        render(220.dp,1f)
        compose.onNodeWithTag("inline-time-picker").assertDoesNotExist()
        compose.onNodeWithText("Start time").assertIsDisplayed()
        compose.onNodeWithTag("time-done").assertIsDisplayed();compose.onNodeWithTag("time-cancel").assertIsDisplayed()
    }
    @Test fun largeFontFallsBackAndDoesNotClipActions() {
        render(320.dp,1.6f)
        compose.onNodeWithTag("inline-time-picker").assertDoesNotExist()
        compose.onNodeWithTag("time-done").assertIsDisplayed();compose.onNodeWithTag("time-cancel").assertIsDisplayed()
    }
}
