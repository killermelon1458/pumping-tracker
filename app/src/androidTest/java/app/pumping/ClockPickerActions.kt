package app.pumping

import android.view.View
import android.view.ViewGroup
import android.widget.TimePicker
import androidx.test.espresso.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import org.hamcrest.Matcher
import org.junit.Assert.*

/** Exercise the real native clock widget, including its listener, not an editor callback. */
internal fun clockTime(hour: Int? = null, minute: Int? = null, clock24: Boolean? = null): Pair<Int,Int> {
    var selected: Pair<Int,Int>? = null
    Espresso.onView(isAssignableFrom(TimePicker::class.java))
        .inRoot(withDecorView(hasDescendant(isAssignableFrom(TimePicker::class.java)))).perform(object: ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
        override fun getDescription() = "Use the synthetic clock-face time picker"
        override fun perform(controller: UiController, view: View) {
            fun hasDial(child: View): Boolean = child.javaClass.simpleName == "RadialTimePickerView" ||
                (child is ViewGroup && (0 until child.childCount).any { hasDial(child.getChildAt(it)) })
            assertTrue("Clock style must contain a clock dial",hasDial(view))
            val picker = view as TimePicker
            if (clock24 != null) assertEquals(clock24,picker.is24HourView)
            if (hour != null) picker.hour = hour
            if (minute != null) picker.minute = minute
            selected = picker.hour to picker.minute
            controller.loopMainThreadUntilIdle()
        }
    })
    return requireNotNull(selected)
}
internal fun closeClock(confirm: Boolean) {
    Espresso.onView(withId(if(confirm) android.R.id.button1 else android.R.id.button2))
        .inRoot(withDecorView(hasDescendant(isAssignableFrom(TimePicker::class.java))))
        .perform(androidx.test.espresso.action.ViewActions.click())
}
