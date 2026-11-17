package app.pumping

import android.view.View
import android.widget.DatePicker
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.*
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.runBlocking
import java.time.*
import org.hamcrest.Matcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StashEditorUxWorkflowTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun app()=compose.activity.application as SyntheticTestApplication
 @Before fun reset()=runBlocking {app().db.clearAllTables();app().settings.saveTimePickerStyle(app.pumping.domain.TimePickerStyle.SCROLL_WHEELS);app().settings.string("unit","mL");app().settings.bool("24h",false)}
 private fun stash(){compose.onAllNodesWithText("Stash").onLast().performClick()}
 private fun bag(){stash();compose.onNodeWithText("+").performClick();compose.onNodeWithTag("date-time").assertExists()}
 private fun cancel(){compose.onNodeWithText("Cancel",substring=false).performClick()}
 private fun closeKeyboard(){Espresso.closeSoftKeyboard()}
 private fun seed():Bag=runBlocking {app().repository.addBag(Allocation(100000,"Fridge",4),System.currentTimeMillis()-3*86400000L);app().repository.dao.allBags().single()}
 private fun use(initial:Boolean=false){
  seed();stash()
  if(initial){compose.onNodeWithText("#1",substring=false).performClick();compose.onNodeWithText("Use milk").performClick()}
  else compose.onNodeWithText("−").performClick()
 }
 private fun date(value:LocalDate,confirm:Boolean=true){
  compose.onNodeWithTag("date-date").performScrollTo().performClick()
  Espresso.onView(isAssignableFrom(DatePicker::class.java)).perform(object:ViewAction{
   override fun getConstraints():Matcher<View> = isAssignableFrom(DatePicker::class.java)
   override fun getDescription()="Synthetic stash date"
   override fun perform(controller:UiController,view:View){(view as DatePicker).updateDate(value.year,value.monthValue-1,value.dayOfMonth);controller.loopMainThreadUntilIdle()}
  });Espresso.onView(withId(if(confirm)android.R.id.button1 else android.R.id.button2)).perform(ViewActions.click())
 }
 private fun stamp()=compose.onNodeWithTag("timestamp-date").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
 @Test fun newBagInitialNumberAndTimeAreCleanAcrossRecreationAndBack(){
  seed();bag();compose.onNodeWithText("#2").assertExists();compose.activityRule.scenario.recreate();cancel()
  compose.onNodeWithText("Discard bag draft?").assertDoesNotExist();compose.onNodeWithText("+").performClick();Espresso.pressBack()
  compose.onNodeWithText("Discard bag draft?").assertDoesNotExist()
 }
 @Test fun bagEditsRevertsAndIncompleteDraftAreProtected(){
  bag();compose.onNodeWithTag("Volume (mL)").performTextInput(".");closeKeyboard();cancel()
  compose.onNodeWithText("Discard bag draft?").assertExists();compose.onNodeWithTag("confirm-cancel").performClick()
  compose.onNodeWithTag("Volume (mL)").assertTextContains(".");compose.onNodeWithTag("Volume (mL)").performTextClearance();closeKeyboard();cancel()
  compose.onNodeWithText("Discard bag draft?").assertDoesNotExist()
 }
 @Test fun bagDateAndTimeAreIndependentAndExpiryUsesSameTimestamp(){
  bag();val original=stamp();val day=LocalDate.now().minusDays(2);date(day)
  assertEquals(original.substringAfter(" · "),stamp().substringAfter(" · "))
  compose.onNodeWithTag("time-wheels").assertDoesNotExist();chooseSessionTime(compose,"Date",10,7)
  compose.onNodeWithTag("Volume (mL)").performTextInput("50");compose.onNodeWithText("Save").performClick()
  compose.waitUntil(15000){runBlocking{app().repository.dao.allBags().size==1}}
  val saved=runBlocking{app().repository.dao.allBags().single()};val z=Instant.ofEpochMilli(saved.stored).atZone(ZoneId.systemDefault())
  assertEquals(day,z.toLocalDate());assertEquals(10,z.hour);assertEquals(7,z.minute)
  assertEquals(expiry(saved.stored,4),saved.expires);assertEquals(saved.stored,runBlocking{app().repository.dao.allMovements().single().time})
 }
 @Test fun bagPickersCanceledOrUnchangedDoNotDirtyAndBackClosesWheelFirst(){
  bag();date(LocalDate.now().minusDays(1),false);compose.onNodeWithTag("date-time").performClick();enterWheel("minute",1)
  Espresso.pressBack();compose.onNodeWithTag("time-wheels").assertDoesNotExist();compose.onNodeWithTag("Volume (mL)").assertExists()
  compose.onNodeWithTag("date-time").performClick();compose.onNodeWithTag("time-done").performClick();cancel()
  compose.onNodeWithText("Discard bag draft?").assertDoesNotExist()
 }
 @Test fun useMilkUntouchedAndAutomaticQuantityAreClean(){
  use();cancel();compose.onNodeWithText("Discard usage draft?").assertDoesNotExist()
  compose.onNodeWithText("−").performClick();compose.onNodeWithText("#1 · 100 mL · Fridge").performClick()
  compose.onNodeWithTag("Use quantity (mL)").assertTextContains("100")
  compose.onNodeWithText("Save").performClick();compose.waitUntil(15000){runBlocking{app().repository.dao.allMovements().size==2}}
  // A fresh seeded bag supplies its default quantity before the baseline is captured.
  runBlocking{app().db.clearAllTables()};use(true);cancel()
  compose.onNodeWithText("Discard usage draft?").assertDoesNotExist()
 }
 @Test fun usageQuantityReasonAndPickerPreserveLedger(){
  use(true);compose.onNodeWithTag("Use quantity (mL)").performTextReplacement("25");closeKeyboard()
  compose.onNodeWithText("Donation").performClick();date(LocalDate.now().minusDays(1));chooseSessionTime(compose,"Date",11,13)
  compose.onNodeWithText("Save").performClick();compose.waitUntil(15000){runBlocking{app().repository.dao.allMovements().size==2}}
  runBlocking{val bag=app().repository.dao.allBags().single();assertEquals(75000L,app().repository.dao.remaining(bag.id));val m=app().repository.dao.allMovements().single{it.delta<0};assertEquals("Donation",m.kind);assertEquals(-25000L,m.delta);assertEquals(11,Instant.ofEpochMilli(m.time).atZone(ZoneId.systemDefault()).hour)}
 }
 @Test fun useMilkRevertingReasonAndNotesClearsWarning(){
  use();compose.onNodeWithText("Donation").performClick();compose.onNodeWithText("Feeding").performClick()
  compose.onNodeWithTag("Notes (optional)").performScrollTo().performTextInput("Synthetic");closeKeyboard();cancel();compose.onNodeWithText("Discard usage draft?").assertExists()
  compose.onNodeWithTag("confirm-cancel").performClick();compose.onNodeWithTag("Notes (optional)").performTextClearance();closeKeyboard();Espresso.pressBack()
  compose.onNodeWithText("Discard usage draft?").assertDoesNotExist()
 }
 @Test fun unknownHistoryOmitsOnlyContextWhileYesAndNoRemain(){
  val now=System.currentTimeMillis()
  runBlocking{for((i,status)in BreastfeedingStatus.entries.withIndex())app().repository.saveSession(Session(id="context-$i",start=now-(i+1)*60000,end=now-i*60000,activeSeconds=60,volume=10000,breastfedBefore=status,powerStatus=PowerPumpStatus.POWER,elapsedKnown=false))}
  compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-context-2"))
  compose.onNodeWithText("Breastfed before pumping: Not specified").assertDoesNotExist()
  compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Breastfed before pumping: Yes"));compose.onNodeWithText("Breastfed before pumping: Yes").assertExists()
  compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Breastfed before pumping: No"));compose.onNodeWithText("Breastfed before pumping: No").assertExists()
  runBlocking{assertEquals(BreastfeedingStatus.UNKNOWN,app().repository.dao.session("context-2")!!.breastfedBefore)}
 }
 @Test fun newBagUsesTwentyFourHourWheelWithoutPeriodSelector(){
  runBlocking{app().settings.bool("24h",true)};bag();compose.onNodeWithTag("date-time").performClick()
  compose.onNodeWithTag("time-period").assertDoesNotExist();enterWheel("hour",23);enterWheel("minute",59)
  compose.onNodeWithTag("time-done").performClick();assertTrue(stamp().endsWith("23:59"))
  compose.onNodeWithTag("Volume (mL)").performTextInput("50");compose.onNodeWithText("Save").performClick()
  compose.waitUntil(15000){runBlocking{app().repository.dao.allBags().size==1}}
  assertEquals(23,Instant.ofEpochMilli(runBlocking{app().repository.dao.allBags().single().stored}).atZone(ZoneId.systemDefault()).hour)
 }
 @Test fun useMilkTwentyFourHourPickerCancelAndDonePreserveDraft(){
  runBlocking{app().settings.bool("24h",true)};use(true);val before=stamp();compose.onNodeWithTag("date-time").performClick()
  enterWheel("hour",19);enterWheel("minute",26);compose.onNodeWithTag("time-cancel").performClick();assertEquals(before,stamp())
  compose.onNodeWithTag("date-time").performClick();compose.onNodeWithTag("time-done").performClick();cancel()
  compose.onNodeWithText("Discard usage draft?").assertDoesNotExist()
 }

}
