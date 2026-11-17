package app.pumping

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.*
import androidx.test.espresso.matcher.ViewMatchers.*
import android.view.View
import android.widget.NumberPicker
import android.widget.EditText
import org.hamcrest.Matcher
import org.hamcrest.Matchers.equalTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.Settings
import app.pumping.domain.*
import app.pumping.ui.*
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClockFacePickerLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val original=ZonedDateTime.now().minusDays(2).withHour(10).withMinute(0).withSecond(42).withNano(123000000).toInstant().toEpochMilli()
    private val value=mutableStateOf(original)
    private val style=mutableStateOf(TimePickerStyle.CLOCK_FACE)
    private val expanded=mutableStateOf(false)
    private val commits=AtomicInteger(0)
    private fun render(clock24:Boolean=false) {
        compose.setContent {PumpingTheme(true){Column {
            SessionTimestamp("Start",value.value,Settings(clock24=clock24,timePickerStyle=style.value),expanded.value,true,
                {expanded.value=false},{expanded.value=true},{expanded.value=false}) {value.value=it;commits.incrementAndGet()}
        }}}
        compose.onNodeWithTag("start-time").performClick()
    }
    @Test fun nativeClockChangesAreTemporaryAndDoneAppliesExactlyOnce(){
        render();clockTime(11,7,false);assertEquals(0,commits.get());assertEquals(original,value.value)
        closeClock(true);compose.waitForIdle();assertEquals(1,commits.get());assertEquals(editorTime(original,11,7,ZoneId.systemDefault()),value.value)
    }
    @Test fun nativeCancelAndBackApplyNoTimestampEdit(){
        render();clockTime(23,59,false);closeClock(false);compose.waitForIdle();assertEquals(0,commits.get());assertEquals(original,value.value)
        compose.onNodeWithTag("start-time").performClick();clockTime(12,5,false);Espresso.pressBack();compose.waitForIdle()
        assertEquals(0,commits.get());assertEquals(original,value.value)
    }
    @Test fun presentationsShareTheSameDraftInBothDirections(){
        render();clockTime(11,7,false);compose.runOnIdle{style.value=TimePickerStyle.SCROLL_WHEELS}
        compose.onNodeWithTag("time-wheels").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"11:07"));assertEquals(0,commits.get())
        enterWheel("minute",13);compose.runOnIdle{style.value=TimePickerStyle.CLOCK_FACE}
        assertEquals(11 to 13,clockTime());assertEquals(0,commits.get());closeClock(true);compose.waitForIdle()
        assertEquals(1,commits.get());assertEquals(editorTime(original,11,13,ZoneId.systemDefault()),value.value)
    }
    @Test fun clockFaceTwentyFourHourMidnightRetainsTheDateAndSubminutePrecision(){
        render(true);clockTime(0,1,true);closeClock(true);compose.waitForIdle()
        assertEquals(1,commits.get());assertEquals(editorTime(original,0,1,ZoneId.systemDefault()),value.value)
    }
    @Test fun wheelDoneValidatesDirectTypingBeforeApplyingExactlyOneEdit(){
        style.value=TimePickerStyle.SCROLL_WHEELS;render()
        Espresso.onView(withTagValue(equalTo("wheel-minute"))).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription()="Leave a synthetic minute draft focused until Done"
            override fun perform(controller:UiController,view:View){
                val picker=view as NumberPicker
                val input=(0 until picker.childCount).map{picker.getChildAt(it)}.filterIsInstance<EditText>().single()
                input.requestFocus();input.setText("7");controller.loopMainThreadUntilIdle()
            }
        })
        assertEquals(0,commits.get());compose.onNodeWithTag("time-done").performClick();compose.waitForIdle()
        assertEquals(1,commits.get());assertEquals(editorTime(original,10,7,ZoneId.systemDefault()),value.value)
    }

}
