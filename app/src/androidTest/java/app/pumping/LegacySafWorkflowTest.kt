package app.pumping

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pumping.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySafWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun uri(name: String) =
        Uri.parse("content://${instrumentation.context.packageName}.synthetic-backup/$name")

    private fun intercept(
        action: String,
        destination: Uri?,
        assertIntent: (Intent) -> Unit = {},
    ): Instrumentation.ActivityMonitor {
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != action) return null
                    assertIntent(intent)
                    return Instrumentation.ActivityResult(
                        if (destination == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                        Intent()
                            .setData(destination)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            ),
                    )
                }
            }
        instrumentation.addMonitor(monitor)
        return monitor
    }

    private fun press(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }

    private fun waitFor(label: String) =
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun safImportCancelMergeAgainReplaceSafetyAndExport() {
        val app = compose.activity.application as PumpingApp
        val fixture =
            instrumentation.context.assets
                .open("legacy/synthetic_fixture.pumping")
                .bufferedReader()
                .use { it.readText() }
        // Preserve the fixture's quantities/IDs; use the next vacant bag number to exercise merging
        // with an existing Android installation.
        val root = LegacyFormat.decode(fixture)
        val number = runBlocking { app.repository.dao.nextNumber() }
        root
            .getJSONObject("data")
            .getJSONArray("stash_records")
            .getJSONObject(0)
            .put("bagId", number)
        root
            .getJSONObject("data")
            .getJSONArray("pump_records")
            .getJSONObject(1)
            .put("stashBagId", number)
        val resolver = compose.activity.contentResolver
        val source = uri("synthetic-source.pumping")
        resolver.openOutputStream(source, "wt")!!.use {
            it.write(LegacyFormat.encode(root).toByteArray())
        }
        fun read(uri: Uri) = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        fun size(uri: Uri): Long =
            resolver.query(uri, null, null, null, null)!!.use { c ->
                c.moveToFirst()
                c.getLong(c.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
            }
        val before = runBlocking { app.repository.dao.allSessions().size }
        compose.onNodeWithText("Profile").performClick()
        val cancelled = intercept(Intent.ACTION_OPEN_DOCUMENT, null)
        try {
            press("Import backup (.pumping or JSON)")
            compose.waitForIdle()
            assertEquals(before, runBlocking { app.repository.dao.allSessions().size })
        } finally {
            instrumentation.removeMonitor(cancelled)
        }
        fun chooseSource() {
            val monitor =
                intercept(Intent.ACTION_OPEN_DOCUMENT, source) { intent ->
                    assertEquals("*/*", intent.type)
                }
            try {
                press("Import backup (.pumping or JSON)")
                waitFor("Import iPhone backup")
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
        chooseSource()
        compose.onNodeWithText("3 pump sessions · 1 stash events").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(before, runBlocking { app.repository.dao.allSessions().size })
        chooseSource()
        compose.onNodeWithText("Import", substring = false).performClick()
        compose.waitUntil(15000) {
            runBlocking { app.repository.dao.allSessions().size } == before + 3 &&
                compose.onAllNodesWithText("Import iPhone backup").fetchSemanticsNodes().isEmpty()
        }
        chooseSource()
        compose
            .onNodeWithText("Already present: 3 pumps · 1 stash events. Conflicts: 0.")
            .assertIsDisplayed()
        compose.onNodeWithText("Import", substring = false).performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Import iPhone backup").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(before + 3, runBlocking { app.repository.dao.allSessions().size })
        assertEquals(1, runBlocking { app.repository.dao.allMovements().count { it.id == "1" } })
        chooseSource()
        compose.onNodeWithText("Replace", substring = false).performClick()
        val cancelSafety = intercept(Intent.ACTION_CREATE_DOCUMENT, null)
        try {
            compose.onNodeWithText("Save safety backup").performClick()
            compose.waitForIdle()
            assertEquals(before + 3, runBlocking { app.repository.dao.allSessions().size })
        } finally {
            instrumentation.removeMonitor(cancelSafety)
        }
        val safety = uri("synthetic-safety.json")
        val safetyMonitor = intercept(Intent.ACTION_CREATE_DOCUMENT, safety)
        try {
            compose.onNodeWithText("Save safety backup").performClick()
            waitFor("Replace all records?")
        } finally {
            instrumentation.removeMonitor(safetyMonitor)
        }
        assertTrue(size(safety) > 0)
        assertEquals(3, JSONObject(read(safety)).getInt("version"))
        assertEquals(before + 3, JSONObject(read(safety)).getJSONArray("sessions").length())
        compose.onNodeWithText("Confirm", substring = false).performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Import iPhone backup").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(3, runBlocking { app.repository.dao.allSessions().size })
        assertEquals(1, runBlocking { app.repository.dao.allBags().size })
        val destination = uri("synthetic-export.pumping")
        val exportMonitor =
            intercept(Intent.ACTION_CREATE_DOCUMENT, destination) { intent ->
                assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".pumping"))
            }
        try {
            press("Export compatible .pumping (legacy, unencrypted)")
            waitFor("Legacy export is not encrypted")
            compose.onNodeWithText("Confirm", substring = false).performClick()
            compose.waitUntil(15000) { size(destination) > 0 }
            val exported = LegacyFormat.decode(read(destination))
            assertEquals(3, exported.getJSONObject("data").getJSONArray("pump_records").length())
            assertEquals(1, exported.getJSONObject("data").getJSONArray("stash_records").length())
        } finally {
            instrumentation.removeMonitor(exportMonitor)
        }
        // The test APK and its synthetic provider data are removed by the test tooling.
    }
}
