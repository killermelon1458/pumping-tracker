package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PumpProfilesWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun app()=compose.activity.application as SyntheticTestApplication
    @Before fun reset()=runBlocking{app().db.clearAllTables();app().settings.string("unit","oz")}
    private fun profiles()=runBlocking{app().repository.dao.allPumpProfiles()}
    private fun manage(){
        compose.onNodeWithText("Profile").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("My Pumps"))
        compose.onNodeWithText("My Pumps").performClick()
    }
    private fun closed()=compose.waitUntil(15000){compose.onAllNodesWithTag("editor-header").fetchSemanticsNodes().isEmpty()}
    private fun editSession(id:String){
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-$id"))
        compose.onNodeWithTag("session-$id").performClick()
    }
    @Test fun createEachTypeWithOptionalFieldsAndGeneratedNickname(){
        manage()
        PumpType.entries.forEachIndexed { index,type ->
            compose.onNodeWithText("Add pump").performClick()
            compose.onNodeWithText(type.label,substring=false).performClick()
            compose.onNodeWithTag("Nickname").assertTextContains(type.shortLabel)
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(15000){profiles().size==index+1 && compose.onAllNodesWithText("Add Pump",substring=false).fetchSemanticsNodes().isEmpty()}
        }
        assertEquals(PumpType.entries.toSet(),profiles().map{it.type}.toSet())
        assertTrue(profiles().all{it.brand.isEmpty()&&it.model.isEmpty()&&it.notes.isEmpty()})
    }
    @Test fun generatedNicknameFollowsBrandUntilCustomized(){
        manage();compose.onNodeWithText("Add pump").performClick()
        compose.onNodeWithText("Optional details").performClick()
        compose.onNodeWithTag("Brand (optional)").performScrollTo().performTextInput("Spectra")
        compose.onNodeWithTag("Nickname").performScrollTo().assertTextContains("Stationary Spectra")
        compose.onNodeWithTag("Nickname").performTextReplacement("Morning pump")
        compose.onNodeWithTag("Brand (optional)").performScrollTo().performTextReplacement("Momcozy")
        compose.onNodeWithText(PumpType.OTHER.label).performScrollTo().performClick()
        compose.onNodeWithTag("Custom type (optional)").performScrollTo().performTextInput("Travel")
        compose.onNodeWithTag("Nickname").performScrollTo().assertTextContains("Morning pump")
        compose.onNodeWithTag("Model (optional)").performScrollTo().performTextInput("Synthetic model")
        compose.onNodeWithTag("Pump notes (optional)").performScrollTo().performTextInput("Synthetic note")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(15000){profiles().size==1&&compose.onAllNodesWithText("Add Pump",substring=false).fetchSemanticsNodes().isEmpty()}
        val p=profiles().single();assertEquals("Morning pump",p.nickname);assertEquals("Travel",p.customType)
        assertEquals("Momcozy",p.brand);assertEquals("Synthetic model",p.model);assertEquals("Synthetic note",p.notes)
    }
    @Test fun customizedNameSurvivesTypeBrandChangesAndArchivePreservesHistory(){
        val end=System.currentTimeMillis()-60000
        val p=PumpProfile(nickname="Original",brand="Spectra")
        val s=Session(start=end-60000,end=end,activeSeconds=60,volume=100000,breastfedBefore=BreastfeedingStatus.NO,pumpProfileId=p.id)
        runBlocking{app().repository.savePumpProfile(p);app().repository.saveSession(s,listOf(Allocation(50000,"Fridge",4)))}
        val bags=runBlocking{app().repository.dao.allBags()};val moves=runBlocking{app().repository.dao.allMovements()}
        manage()
        compose.onNodeWithTag("pump-profile-${p.id}").performScrollTo().performClick()
        compose.onNodeWithTag("Nickname").performTextReplacement("My own pump")
        compose.onNodeWithText(PumpType.MOBILE_OR_WEARABLE.label).performClick()
        compose.onNodeWithTag("Brand (optional)").performScrollTo().performTextReplacement("Momcozy")
        compose.onNodeWithTag("Nickname").performScrollTo().assertTextContains("My own pump")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(15000){profiles().single().nickname=="My own pump"&&compose.onAllNodesWithText("Edit Pump",substring=false).fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("pump-archive-${p.id}").performScrollTo().performClick()
        compose.waitUntil(15000){profiles().single().archived}
        assertEquals(s,runBlocking{app().repository.dao.session(s.id)})
        assertEquals(bags,runBlocking{app().repository.dao.allBags()});assertEquals(moves,runBlocking{app().repository.dao.allMovements()})
        compose.onNodeWithText("Back").performClick();compose.onNodeWithText("Pump",substring=false).performClick()
        editSession(s.id)
        compose.onNodeWithTag("pump-selector").performScrollTo().assertTextContains("My own pump (archived)")
        compose.onNodeWithText("No",substring=false).assertIsSelected()
        compose.onNodeWithTag("pump-selector").performClick()
        compose.onNodeWithTag("pump-option-${p.id}").assertExists().performClick()
        compose.onNodeWithText("Save").performClick();closed()
        val saved=runBlocking{app().repository.dao.session(s.id)}!!
        assertEquals(s.copy(updated=saved.updated),saved)
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("pump-selector").performScrollTo().assertTextContains("Unspecified").performClick()
        compose.onNodeWithTag("pump-option-${p.id}").assertDoesNotExist()
    }
    @Test fun newEntriesRememberLastSelectionAndHistoricalSessionsStayUnspecified(){
        val now=System.currentTimeMillis()-60000
        val a=PumpProfile(nickname="Home");val b=PumpProfile(type=PumpType.MOBILE_OR_WEARABLE,nickname="Travel")
        val old=Session(id="historical",start=now-120000,end=now-60000,activeSeconds=60,volume=10000,updated=1)
        runBlocking{
            app().repository.savePumpProfile(a);app().repository.savePumpProfile(b)
            app().repository.saveSession(old)
            app().repository.saveSession(old.copy(id="recent",start=now-60000,end=now,pumpProfileId=a.id,updated=2))
        }
        compose.onNodeWithText("Quick Log").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Home",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("pump-selector").performScrollTo().performClick()
        compose.onNodeWithTag("pump-option-${b.id}").performClick()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        compose.onNodeWithText("Save").performClick();closed()
        compose.onNodeWithText("Quick Log").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Travel",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("pump-selector").performScrollTo().performClick()
        compose.onNodeWithTag("pump-option-none").performClick()
        compose.onNodeWithTag("Volume (oz)").performScrollTo().performTextInput("1")
        compose.onNodeWithText("Save").performClick();closed()
        compose.onNodeWithText("Quick Log").performClick()
        compose.onNodeWithTag("pump-selector").performScrollTo().assertTextContains("Unspecified")
        compose.onNodeWithText("Not specified",substring=false).assertIsSelected()
        compose.onNodeWithText("Cancel",substring=false).performClick();compose.onNodeWithText("Confirm",substring=false).performClick();closed()
        editSession(old.id)
        compose.onNodeWithTag("pump-selector").performScrollTo().assertTextContains("Unspecified")
        compose.onNodeWithText("Save").performClick();closed()
        val saved=runBlocking{app().repository.dao.session(old.id)}!!
        assertEquals(old.copy(updated=saved.updated),saved)
    }
}
