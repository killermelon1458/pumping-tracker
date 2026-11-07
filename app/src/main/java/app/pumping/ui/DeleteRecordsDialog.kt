package app.pumping.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pumping.PumpViewModel
import app.pumping.data.*

@Composable
fun DeleteRecordsDialog(
    vm: PumpViewModel,
    s: Settings,
    target: DeleteTarget,
    id: String,
    cancel: () -> Unit,
    deleted: () -> Unit,
) {
    var plan by remember(target, id) { mutableStateOf<DeletionPlan?>(null) }
    var related by remember(target, id) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(target, id, refresh) { vm.launch { plan = vm.repo.deletionPlan(target, id) } }
    val p = plan
    AlertDialog(
        onDismissRequest = { if (!busy) cancel() },
        title = {
            Text(
                if (target == DeleteTarget.BAG) "Delete bag permanently?"
                else "Delete pump permanently?"
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (p == null) Text("Loading linked records…")
                else {
                    val linkedOption =
                        if (target == DeleteTarget.SESSION) p.bags.isNotEmpty()
                        else p.session != null
                    if (linkedOption)
                        Toggle(
                            if (target == DeleteTarget.SESSION) "Also delete linked bags"
                            else "Also delete originating pump",
                            related,
                            { related = it },
                        )
                    if (target == DeleteTarget.SESSION && p.bags.isNotEmpty())
                        Text(
                            "${p.bags.size} linked bags · ${quantity(p.bags.sumOf {it.initial},s)} originally stored · ${quantity(p.bags.sumOf {p.remaining(it)},s)} remaining"
                        )
                    Text("Records to delete", style = MaterialTheme.typography.titleMedium)
                    p.deletedSession(related)?.let { session ->
                        Text(
                            "Pump · ${dateTime(session.start,s)} → ${dateTime(session.end,s)} · ${quantity(session.volume,s)}"
                        )
                    }
                    val removed = p.deletedBags(related)
                    removed.forEach { bag ->
                        Text(
                            "Bag #${bag.number} · ${dateTime(bag.stored,s)} · ${bag.location}\n${quantity(bag.initial,s)} originally stored; ${quantity(p.remaining(bag),s)} remaining",
                            Modifier.testTag("delete-bag-${bag.id}"),
                        )
                    }
                    val transactions = p.removedMovements(related)
                    if (transactions.isNotEmpty()) {
                        Text("${transactions.size} inventory transactions will be removed:")
                        transactions.forEachIndexed { i, m ->
                            val bag = p.bags.single { it.id == m.bagId }
                            Text(
                                "${i+1}. Bag #${bag.number} · ${m.kind} · ${dateTime(m.time,s)} · ${quantity(m.delta,s)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (p.deletedSession(related) != null) {
                        val kept = p.bags.filter { b -> removed.none { it.id == b.id } }
                        if (kept.isNotEmpty()) {
                            Text("Bags kept, with their pump link removed:")
                            kept.forEach { bag ->
                                Text(
                                    "Bag #${bag.number} · ${dateTime(bag.stored,s)} · ${quantity(p.remaining(bag),s)} remaining",
                                    Modifier.testTag("keep-bag-${bag.id}"),
                                )
                            }
                        }
                    } else
                        p.session?.let {
                            Text(
                                "Pump kept: ${dateTime(it.start,s)} · ${quantity(it.volume,s)}. Other bags from this pump remain linked."
                            )
                        }
                    Text(
                        "Deletion is permanent and changes the affected totals. It does not record feeding, discard, donation or other milk use."
                    )
                    TextButton(
                        {
                            plan = null
                            refresh++
                        },
                        enabled = !busy,
                    ) {
                        Text("Refresh details")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    val selected = plan ?: return@TextButton
                    busy = true
                    vm.launch {
                        try {
                            vm.repo.delete(selected, related)
                            vm.app.reminders.restore(vm.app)
                            deleted()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = p != null && !busy,
            ) {
                Text("Delete permanently", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                cancel,
                modifier = Modifier.testTag("delete-dialog-cancel"),
                enabled = !busy,
            ) {
                Text("Cancel")
            }
        },
    )
}
