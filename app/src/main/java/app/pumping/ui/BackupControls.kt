package app.pumping.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pumping.PumpViewModel
import app.pumping.data.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ReplacementRequest(val preview: LegacyPreview, val applySettings: Boolean)

@Composable
fun BackupControls(vm: PumpViewModel) {
    val resolver = LocalContext.current.contentResolver
    val backup = remember { Backup(vm.repo, vm.store) }
    val legacy = remember { LegacyBackup(vm.repo, vm.store) }
    var status by rememberSaveable { mutableStateOf("") }
    var text by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LegacyPreview?>(null) }
    var nativeCounts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var mode by remember { mutableStateOf("Merge") }
    var applySettings by remember { mutableStateOf(false) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var busy by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<ReplacementRequest?>(null) }
    var replacementConfirm by remember { mutableStateOf<ReplacementRequest?>(null) }
    var exportPreview by remember { mutableStateOf<LegacyExport?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }

    val nativeExport =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                vm.launch {
                    withContext(Dispatchers.IO) {
                        BackupFiles.write(resolver, uri, backup.export())
                    }
                    status = "Complete record backup exported. Keep this sensitive file private."
                }
        }
    val safetyExport =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            val request = replacement
            replacement = null
            if (uri != null && request != null)
                vm.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            BackupFiles.write(resolver, uri, backup.export())
                        }
                        replacementConfirm = request
                    } finally {
                        busy = false
                    }
                }
            else status = "Replace cancelled. No records were changed."
        }
    val csvExport =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri
            ->
            if (uri != null)
                vm.launch {
                    withContext(Dispatchers.IO) { BackupFiles.write(resolver, uri, backup.csv()) }
                    status = "Session CSV exported."
                }
        }
    val legacyExport =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val payload = exportText
            exportText = null
            if (uri != null && payload != null)
                vm.launch {
                    withContext(Dispatchers.IO) { BackupFiles.write(resolver, uri, payload) }
                    status =
                        "Compatible .pumping exported. It is not encrypted; iPhone acceptance is unverified."
                }
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.launch {
                    busy = true
                    try {
                        val selected =
                            withContext(Dispatchers.IO) { BackupFiles.read(resolver, uri) }
                        mode = "Merge"
                        applySettings = false
                        zone = ZoneId.systemDefault().id
                        if (selected.trimStart().startsWith("{")) {
                            val root =
                                withContext(Dispatchers.Default) {
                                    LegacyFormat.parseJson(selected)
                                }
                            require(
                                root.opt("format") == "Pumping" && root.optInt("version") in 1..3
                            ) {
                                "Not a supported native Pumping backup. Original iPhone backups must use the .pumping envelope."
                            }
                            nativeCounts =
                                root.getJSONArray("sessions").length() to
                                    root.getJSONArray("bags").length()
                            preview = null
                        } else {
                            preview =
                                withContext(Dispatchers.IO) {
                                    legacy.preview(legacy.prepare(selected, ZoneId.of(zone)))
                                }
                            nativeCounts = null
                        }
                        text = selected
                    } finally {
                        busy = false
                    }
                }
        }
    fun importLegacy(p: LegacyPreview, replace: Boolean, preferences: Boolean) {
        busy = true
        vm.launch {
            try {
                withContext(Dispatchers.IO) {
                    legacy.import(
                        p.plan,
                        if (replace) LegacyImportMode.REPLACE else LegacyImportMode.MERGE,
                        preferences,
                        replaceConfirmed = replace,
                    )
                }
                vm.app.reminders.restore(vm.app)
                status =
                    if (replace)
                        "Replaced records with ${p.plan.pumpCount} pump sessions and ${p.plan.stashCount} stash events. Safety backup saved."
                    else
                        "Import complete: ${p.plan.pumpCount-p.duplicatePumps} new pump sessions, ${p.duplicatePumps} already present; ${p.plan.stashCount-p.duplicateStash} new stash events, ${p.duplicateStash} already present."
                if (p.plan.warnings.any { it.contains("excluded") })
                    status +=
                        " Unverified stash events were retained; affected bags are excluded from available inventory."
                text = null
                preview = null
                replacementConfirm = null
            } finally {
                busy = false
            }
        }
    }
    CardBlock {
        Text("Your data", style = MaterialTheme.typography.titleLarge)
        TextButton({ picker.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text("Import backup (.pumping or JSON)")
        }
        TextButton(
            { nativeExport.launch("pumping-backup-${LocalDate.now()}.json") },
            enabled = !busy,
        ) {
            Text("Export complete records (JSON)")
        }
        TextButton(
            {
                busy = true
                vm.launch {
                    try {
                        exportPreview = withContext(Dispatchers.IO) { legacy.export() }
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
        ) {
            Text("Export compatible .pumping (legacy, unencrypted)")
        }
        TextButton(
            { csvExport.launch("pumping-sessions-${LocalDate.now()}.csv") },
            enabled = !busy,
        ) {
            Text("Export session history (CSV)")
        }
        Text(
            "Native JSON retains Android inventory history and original import metadata. Legacy import previews changes before saving. No files are uploaded.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status)
    }
    val p = preview
    if (text != null && p != null)
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    text = null
                    preview = null
                }
            },
            title = { Text("Import iPhone backup") },
            text = {
                Column(
                    Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${p.plan.pumpCount} pump sessions · ${p.plan.stashCount} stash events")
                    Text(
                        "Already present: ${p.duplicatePumps} pumps · ${p.duplicateStash} stash events. Conflicts: ${p.conflicts}."
                    )
                    Text(
                        "Inventory bags understood: ${p.plan.bags.size}. Other stash events remain available for export."
                    )
                    if (p.deletedPumps + p.deletedStash > 0)
                        Text(
                            "Previously deleted: ${p.deletedPumps} pumps · ${p.deletedStash} stash events. Merge keeps these deleted; confirmed Replace can restore them."
                        )
                    Choices(listOf("Merge", "Replace"), mode) { mode = it }
                    Text(
                        if (mode == "Merge")
                            "Existing imported IDs keep local edits. Conflicts reject the entire import."
                        else
                            "Replace removes all current sessions, bags, ledger and legacy records. First save a safety backup, then confirm replacement."
                    )
                    Field("Source time zone", zone, { zone = it })
                    TextButton(
                        {
                            vm.launch {
                                preview =
                                    withContext(Dispatchers.IO) {
                                        legacy.preview(legacy.prepare(text!!, ZoneId.of(zone)))
                                    }
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("Apply time zone")
                    }
                    Text(
                        "Using ${p.plan.zone}. Source wall-clock times are interpreted in this zone, never as UTC. Choose the original zone if you have moved."
                    )
                    Toggle(
                        "Apply supported settings",
                        applySettings,
                        { applySettings = it },
                        "Unit, theme, storage durations, deep-freezer visibility and formula cost. No reminder schedules are activated.",
                    )
                    Text(
                        if (applySettings) "Supported preferences will change with this import."
                        else "Your current preferences will be kept."
                    )
                    p.plan.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    {
                        if (mode == "Replace") {
                            replacement = ReplacementRequest(p, applySettings)
                            safetyExport.launch("pumping-safety-${LocalDate.now()}.json")
                        } else importLegacy(p, false, applySettings)
                    },
                    enabled =
                        !busy && zone == p.plan.zone && (mode == "Replace" || p.conflicts == 0),
                ) {
                    Text(if (mode == "Replace") "Save safety backup" else "Import")
                }
            },
            dismissButton = {
                TextButton(
                    {
                        text = null
                        preview = null
                    },
                    enabled = !busy,
                ) {
                    Text("Cancel")
                }
            },
        )
    val counts = nativeCounts
    if (text != null && p == null && counts != null)
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    text = null
                    nativeCounts = null
                }
            },
            title = { Text("Import native backup?") },
            text = {
                Column {
                    Text(
                        "${counts.first} sessions · ${counts.second} bags. Identical records are skipped. Conflicts reject the complete import."
                    )
                    Toggle(
                        "Restore supported preferences",
                        applySettings,
                        { applySettings = it },
                        "Unit, theme, storage durations, deep-freezer visibility and formula cost, when saved in this backup.",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        val selected = text!!
                        busy = true
                        vm.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    backup.import(selected, applyLegacySettings = applySettings)
                                }
                                vm.app.reminders.restore(vm.app)
                                text = null
                                nativeCounts = null
                                status = "Native backup imported successfully."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(
                    {
                        text = null
                        nativeCounts = null
                    },
                    enabled = !busy,
                ) {
                    Text("Cancel")
                }
            },
        )
    replacementConfirm?.let { request ->
        Confirm(
            "Replace all records?",
            "Safety backup saved. This removes your current sessions, stash records and pump profiles and imports ${request.preview.plan.pumpCount} pumps and ${request.preview.plan.stashCount} stash events. ${if(request.applySettings) "Supported preferences will also change." else "Preferences will be kept."}",
            { replacementConfirm = null },
            {
                replacementConfirm = null
                importLegacy(request.preview, true, request.applySettings)
            },
        )
    }
    exportPreview?.let { result ->
        Confirm(
            "Legacy export is not encrypted",
            result.warnings.joinToString("\n\n") +
                "\n\nNative JSON is the preferred complete backup.",
            { exportPreview = null },
            {
                exportText = result.text
                exportPreview = null
                legacyExport.launch("pumping-compatible-${LocalDate.now()}.pumping")
            },
        )
    }
}
