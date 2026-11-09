package app.pumping.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pumping.PumpViewModel
import app.pumping.data.*

@Composable
fun PumpSelector(profiles: List<PumpProfile>, selected: String?, original: String?, change: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = profiles.find { it.id == selected }
    val label = selectedProfile?.let { it.nickname + if (it.archived) " (archived)" else "" } ?: "Unspecified"
    Text("Pump", style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag("pump-selector").semantics {
            contentDescription = "Pump: $label"
        }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Unspecified") }, onClick = { change(null); expanded = false }, modifier = Modifier.testTag("pump-option-none"))
            profiles.filter { !it.archived || it.id == original }.forEach { p ->
                DropdownMenuItem(text = { Text(p.nickname + if (p.archived) " (archived)" else "") },
                    onClick = { change(p.id); expanded = false }, modifier = Modifier.testTag("pump-option-${p.id}"))
            }
        }
    }
}

@Composable
fun PumpManager(vm: PumpViewModel, close: () -> Unit) {
    val profiles by vm.pumps.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = profiles.find { it.id == editing }
    if (adding || editing != null) {
        if (adding || selected != null) PumpProfileEditor(vm, selected, profiles) { adding = false; editing = null }
        else Text("Loading pump…")
        return
    }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(close) { Text("Back") }
            TextButton({ adding = true }) { Text("Add pump") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading("My Pumps")
            Text("Choose a pump when logging. Archived pumps stay linked to past sessions.")
            if (profiles.isEmpty()) Text("No pumps yet. Brand and model are optional.")
            listOf(false, true).forEach { archived ->
                val rows = profiles.filter { it.archived == archived }
                if (rows.isNotEmpty()) Text(if (archived) "Archived pumps" else "Active pumps", style = MaterialTheme.typography.titleMedium)
                rows.forEach { p ->
                    CardBlock {
                        Column(Modifier.fillMaxWidth().testTag("pump-profile-${p.id}").clickable { editing = p.id }) {
                            Text(p.nickname, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(p.type.label, p.customType, p.brand, p.model).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                        Row {
                            TextButton({ editing = p.id }) { Text("Edit") }
                            TextButton({ vm.launch { vm.repo.archivePumpProfile(p.id, !p.archived) } }, Modifier.testTag("pump-archive-${p.id}").semantics {
                                contentDescription = "${if (p.archived) "Restore" else "Archive"} ${p.nickname}"
                            }) { Text(if (p.archived) "Restore" else "Archive") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PumpProfileEditor(vm: PumpViewModel, existing: PumpProfile?, profiles: List<PumpProfile>, close: () -> Unit) {
    val id = rememberSaveable { existing?.id ?: newId() }
    var typeName by rememberSaveable { mutableStateOf((existing?.type ?: PumpType.STATIONARY).name) }
    val type = PumpType.valueOf(typeName)
    var customType by rememberSaveable { mutableStateOf(existing?.customType ?: "") }
    var brand by rememberSaveable { mutableStateOf(existing?.brand ?: "") }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var nickname by rememberSaveable { mutableStateOf(existing?.nickname ?: "") }
    var customized by rememberSaveable { mutableStateOf(existing != null) }
    var details by rememberSaveable { mutableStateOf(existing?.let { it.brand.isNotBlank() || it.model.isNotBlank() || it.notes.isNotBlank() } ?: false) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val displayName = if (customized) nickname else suggestedPumpNickname(type, brand, customType, profiles.filter { it.id != id })
    BackHandler { discard = true }
    EditorLayout(if (existing == null) "Add Pump" else "Edit Pump", { discard = true }, {
        busy = true
        vm.launch {
            try {
                vm.repo.savePumpProfile(PumpProfile(id, type, displayName.trim(), if (type == PumpType.OTHER) customType.trim() else "",
                    brand.trim(), model.trim(), notes, existing?.archived ?: false))
                close()
            } finally { busy = false }
        }
    }, !busy && displayName.isNotBlank()) {
        CardBlock {
            Text("Pump type")
            Choices(PumpType.entries.map { it.label }, type.label) { label -> typeName = PumpType.entries.first { it.label == label }.name }
            if (type == PumpType.OTHER) Field("Custom type (optional)", customType, { customType = it })
            Field("Nickname", displayName, { nickname = it; customized = true })
        }
        TextButton({ details = !details }) { Text(if (details) "Hide optional details" else "Optional details") }
        if (details) CardBlock {
            Field("Brand (optional)", brand, { brand = it })
            Field("Model (optional)", model, { model = it })
            Field("Pump notes (optional)", notes, { notes = it }, lines = 3)
        }
    }
    if (discard) Confirm("Discard pump changes?", "Unsaved pump changes will be lost.", { discard = false }, close)
}
