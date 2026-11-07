package app.pumping

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pumping.data.*
import app.pumping.domain.Volume
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkedDeletionWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun listScroll(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }

    private fun app() = compose.activity.application as PumpingApp

    private fun seed(): Pair<Session, List<Bag>> = runBlocking {
        val now = System.currentTimeMillis()
        val s =
            Session(
                start = now - 1200000,
                end = now,
                activeSeconds = 1200,
                volume = Volume.parse("9", "oz"),
                notes = "Synthetic linked-deletion UI",
            )
        app()
            .repository
            .saveSession(
                s,
                listOf(
                    Allocation(Volume.parse("5", "oz"), "Fridge", 4),
                    Allocation(Volume.parse("4", "oz"), "Freezer", 180),
                ),
            )
        s to app().repository.dao.linkedBags(s.id)
    }

    private fun openBag(id: String) {
        compose.onNodeWithText("Stash", substring = false).performClick()
        listScroll(hasText("Show all bag records") or hasText("Hide all bag records"))
        if (compose.onAllNodesWithText("Show all bag records").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Show all bag records").performClick()
        listScroll(hasTestTag("bag-record-$id"))
        compose.onNodeWithTag("bag-record-$id").performClick()
        compose.onNodeWithTag("bag-detail-list").performScrollToNode(hasText("Delete bag"))
        compose.onNodeWithText("Delete bag").performClick()
    }

    @Test
    fun sessionDeletionConfirmsEveryLinkedBagAndCanCancelOrCascade() {
        val (s, bags) = seed()
        compose.waitForIdle()
        listScroll(hasTestTag("session-${s.id}"))
        compose.onNodeWithTag("session-${s.id}").performClick()
        compose.onNodeWithText("Delete session").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("2 linked bags", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("Also delete linked bags").assertIsOff()
        compose.onNodeWithTag("delete-dialog-cancel").performClick()
        assertNotNull(runBlocking { app().repository.dao.session(s.id) })
        compose.onNodeWithText("Delete session").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("2 linked bags", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("Also delete linked bags").performClick()
        bags.forEach { b -> compose.onNodeWithTag("delete-bag-${b.id}").assertExists() }
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(10000) {
            runBlocking {
                app().repository.dao.session(s.id) == null &&
                    bags.all { app().repository.dao.bag(it.id) == null }
            }
        }
        assertTrue(
            runBlocking {
                app().repository.dao.allMovements().none { m -> bags.any { it.id == m.bagId } }
            }
        )
    }

    @Test
    fun bagDeletionCanKeepPumpOrDeletePumpWhileKeepingSibling() {
        val (keptPump, firstBags) = seed()
        openBag(firstBags.first().id)
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithTag("Also delete originating pump")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("Also delete originating pump").assertIsOff()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(10000) {
            runBlocking { app().repository.dao.bag(firstBags.first().id) == null }
        }
        assertNotNull(runBlocking { app().repository.dao.session(keptPump.id) })
        assertEquals(
            keptPump.id,
            runBlocking { app().repository.dao.bag(firstBags.last().id)!!.sourceSession },
        )
        val (removedPump, nextBags) = seed()
        openBag(nextBags.first().id)
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithTag("Also delete originating pump")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("Also delete originating pump").performClick()
        compose.onNodeWithText("Bags kept, with their pump link removed:").assertExists()
        compose.onNodeWithTag("keep-bag-${nextBags.last().id}").assertExists()
        compose.onNodeWithText("Delete permanently").performClick()
        compose.waitUntil(10000) {
            runBlocking { app().repository.dao.session(removedPump.id) == null }
        }
        assertNull(runBlocking { app().repository.dao.bag(nextBags.first().id) })
        assertNull(runBlocking { app().repository.dao.bag(nextBags.last().id)!!.sourceSession })
        assertNotNull(runBlocking { app().repository.dao.session(keptPump.id) })
        assertEquals(
            keptPump.id,
            runBlocking { app().repository.dao.bag(firstBags.last().id)!!.sourceSession },
        )
    }
}
