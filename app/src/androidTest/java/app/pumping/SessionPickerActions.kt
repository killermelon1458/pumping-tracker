package app.pumping

import android.view.View
import android.widget.EditText
import android.widget.NumberPicker
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.espresso.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matcher
import org.hamcrest.Matchers.equalTo

/** Exercise native direct entry and its focus-loss validation, not an app callback. */
internal fun enterWheel(name: String, value: Int) {
    Espresso.onView(withTagValue(equalTo("wheel-$name"))).perform(object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
        override fun getDescription() = "Enter a native wheel value"
        override fun perform(controller: UiController, view: View) {
            val picker = view as NumberPicker
            val input = (0 until picker.childCount).map { picker.getChildAt(it) }.filterIsInstance<EditText>().single()
            input.requestFocus(); input.setText(value.toString()); picker.clearFocus()
            controller.loopMainThreadUntilIdle()
        }
    })
}
internal fun chooseSessionTime(compose: ComposeTestRule, label: String, hour: Int, minute: Int) {
    compose.onNodeWithTag("${label.lowercase()}-time").performScrollTo().performClick()
    enterWheel("hour", (hour % 12).let { if (it == 0) 12 else it })
    enterWheel("minute", minute)
    val selected = compose.onNodeWithTag("time-wheels").fetchSemanticsNode().config[SemanticsProperties.StateDescription].substringBefore(":").toInt()
    if (selected / 12 != hour / 12) compose.onNodeWithTag("time-period").performClick()
    compose.onNodeWithTag("time-done").performClick()
}
