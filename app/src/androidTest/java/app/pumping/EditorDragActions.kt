package app.pumping

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Real held Android pointer. Never query Espresso/Compose idleness while auto-scroll is active. */
internal fun dragEditorHandle(compose:ComposeTestRule, tag:String, distance:Float, holdFrames:Int=0,
    beforeRelease:()->Unit = {}) {
    val node=compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
    val x=node.positionOnScreen.x+node.size.width/2f
    val initialY=node.positionOnScreen.y+node.size.height/2f
    val device=InstrumentationRegistry.getInstrumentation().uiAutomation
    val down=SystemClock.uptimeMillis()
    var y=initialY
    fun event(action:Int) {
        val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        try { assertTrue(device.injectInputEvent(motion,true)) } finally { motion.recycle() }
    }
    val previous=compose.mainClock.autoAdvance
    compose.mainClock.autoAdvance=false
    var released=false
    try {
        event(MotionEvent.ACTION_DOWN)
        Thread.sleep(700);compose.mainClock.advanceTimeBy(750)
        repeat(48) { index ->
            y=initialY+(index+1)*distance/48
            event(MotionEvent.ACTION_MOVE)
            compose.mainClock.advanceTimeBy(32);Thread.sleep(16)
        }
        repeat(holdFrames) { compose.mainClock.advanceTimeBy(16);Thread.sleep(16) }
        beforeRelease()
        event(MotionEvent.ACTION_UP)
        released=true
    } finally {
        if (!released) runCatching { event(MotionEvent.ACTION_CANCEL) }
        compose.mainClock.autoAdvance=previous
    }
}
