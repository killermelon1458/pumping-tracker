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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorHeaderLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun checkGeometry(separateTitle: Boolean) {
        val header = bounds("editor-header")
        val title = bounds("editor-title")
        val cancel = bounds("editor-cancel")
        val save = bounds("editor-save")
        val unit =
            compose.onNodeWithContentDescription("Input units").fetchSemanticsNode().boundsInRoot
        assertEquals(header.center.x, title.center.x, 1f)
        assertTrue(cancel.right <= unit.left)
        assertTrue(unit.right <= save.left)
        assertTrue(cancel.left >= header.left && save.right <= header.right)
        compose
            .onNodeWithContentDescription("Input units")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        if (separateTitle) {
            assertTrue(title.top >= maxOf(cancel.bottom, unit.bottom, save.bottom))
        } else {
            assertTrue(unit.right <= title.left)
            assertTrue(title.right <= save.left)
            assertEquals(cancel.center.y, title.center.y, 1f)
        }
        compose.onNodeWithTag("editor-title").assertIsDisplayed()
        compose.onNodeWithTag("editor-save").assertIsDisplayed()
    }

    private fun render(width: Dp, fontScale: Float) {
        compose.setContent {
            var unit by remember { mutableStateOf("oz") }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PumpingTheme(true) {
                    Box(Modifier.width(width)) {
                        EditorHeader(
                            "Log Pump",
                            unit,
                            {},
                            { unit = if (unit == "oz") "mL" else "oz" },
                            {},
                            true,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun fullScreenCenterDoesNotMoveWhenUnitsChange() {
        render(360.dp, 1f)
        checkGeometry(false)
        compose.onNodeWithContentDescription("Input units").performClick()
        compose.onNodeWithContentDescription("Input units").assertTextContains("mL")
        checkGeometry(false)
        compose.onAllNodesWithText("mL").assertCountEquals(1)
    }

    @Test
    fun narrowScreenWithLargeTextDoesNotOverlapOrClipHeader() {
        render(280.dp, 2f)
        checkGeometry(true)
        compose.onNodeWithContentDescription("Input units").performClick()
        checkGeometry(true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("mL", useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) {
            it(layouts)
        }
        assertFalse("Unit label must fit without clipping", layouts.single().hasVisualOverflow)
    }
}
