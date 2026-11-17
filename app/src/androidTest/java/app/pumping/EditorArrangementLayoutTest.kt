package app.pumping

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.SettingsStore
import app.pumping.domain.EditorKind
import app.pumping.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorArrangementLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val job=SupervisorJob()
    private lateinit var file:File
    @After fun close()=runBlocking {job.cancelAndJoin();if(::file.isInitialized)file.delete();Unit}
    private var current:EditorLayoutState? = null
    private fun render(width:Dp,font:Float,long:Boolean=false) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        file=File(context.cacheDir,"layout-ui-${UUID.randomUUID()}.preferences_pb")
        val store=SettingsStore(context,isolatedStore=PreferenceDataStoreFactory.create(scope=CoroutineScope(job+Dispatchers.IO),produceFile={file}))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,font)) {
                PumpingTheme(true) {
                    val layout=rememberEditorLayout(EditorKind.BAG,store)
                    SideEffect { current=layout }
                    Box(Modifier.width(width).height(680.dp).testTag("layout-container")) {
                        EditorLayout("New Bag",{},{},layout=layout) {
                            EditorSections(layout) { id -> Text(id,if(long) Modifier.height(220.dp) else Modifier) }
                        }
                    }
                }
            }
        }
    }
    @Test fun narrowLargeFontLayoutActionsStayWithinScreen() {
        render(280.dp,1.8f);compose.onNodeWithTag("edit-layout").performScrollTo().performClick()
        val container=compose.onNodeWithTag("layout-container").fetchSemanticsNode().boundsInRoot
        for(tag in listOf("layout-reset","layout-cancel","layout-done")) {
            compose.onNodeWithTag(tag).assertIsDisplayed();val b=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(b.left>=container.left && b.right<=container.right && b.bottom<=container.bottom)
        }
        compose.onNodeWithTag("layout-up-notes").performScrollTo().performClick()
        compose.onNodeWithTag("layout-cancel").performClick();compose.onNodeWithTag("layout-handle-notes").assertDoesNotExist()
    }
    @Test fun normalModeHasNoHandlesAndRequiredVisibilityIsLocked() {
        render(360.dp,1f);compose.onNodeWithTag("layout-handle-identity").assertDoesNotExist()
        compose.onNodeWithTag("edit-layout").performScrollTo().performClick();compose.onNodeWithContentDescription("Reorder Bag number").assertExists()
        compose.onNodeWithContentDescription("Move Bag number up").assertIsNotEnabled()
        compose.onNodeWithTag("layout-show-identity").assertIsOn().assertIsNotEnabled();compose.onNodeWithTag("layout-show-notes").assertExists()
    }
    @Test fun draggingNearBottomAutoScrollsTheSingleEditorContainer() {
        render(360.dp,1f,true);compose.onNodeWithTag("edit-layout").performScrollTo().performClick()
        val handle=compose.onNodeWithTag("layout-handle-identity").performScrollTo().fetchSemanticsNode().boundsInRoot
        val bottom=compose.onNodeWithTag("layout-container").fetchSemanticsNode().boundsInRoot.bottom
        val before=requireNotNull(current).scroll.value
        dragEditorHandle(compose,"layout-handle-identity",bottom-handle.center.y-20f,holdFrames=35) {
            assertTrue("Expected edge auto-scroll: viewport=${requireNotNull(current).viewport}, scroll=${requireNotNull(current).scroll.value}/${requireNotNull(current).scroll.maxValue}, drag=${requireNotNull(current).draggingSection}, pointer=${requireNotNull(current).dragPointerY}",requireNotNull(current).scroll.value>before)
        }
        compose.onNodeWithTag("layout-done").performClick()
    }

    private fun assertSingleRow(font:Float) {
        render(360.dp,font);compose.onNodeWithTag("edit-layout").performScrollTo().performClick()
        compose.onNodeWithTag("layout-row-identity").performScrollTo()
        val tags=listOf("show","name","up","down","handle")
        val boxes=tags.map { compose.onNodeWithTag("layout-$it-identity").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        boxes.zipWithNext().forEach { (left,right) -> assertTrue("Horizontal order: $boxes",left.right<=right.left+1f) }
        assertTrue("One horizontal row: $boxes",boxes.maxOf{it.center.y}-boxes.minOf{it.center.y}<2f)
        val row=compose.onNodeWithTag("layout-row-identity").fetchSemanticsNode().boundsInRoot
        assertEquals(row.right,boxes.last().right,1f)
        compose.onNodeWithTag("layout-show-identity").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("layout-row-notes").performScrollTo()
        compose.onNodeWithTag("layout-show-notes").assertIsEnabled().assertIsOn()
    }
    @Test fun sectionControlsUseSingleRowWithRightmostHandle(){assertSingleRow(1f)}
    @Test fun sectionControlsRemainSingleRowWithLargeFonts(){assertSingleRow(1.8f)}

}
