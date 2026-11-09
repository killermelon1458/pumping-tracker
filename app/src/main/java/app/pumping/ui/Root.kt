package app.pumping.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pumping.*
import app.pumping.data.*
import kotlinx.coroutines.delay

@Composable
fun PumpingRoot(vm: PumpViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val bags by vm.bags.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val tabState = rememberSaveableStateHolder()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf("") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var bagId by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val context = LocalContext.current
    var permissionNotice by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionNotice = !granted
        }
    val requestNotification: () -> Unit = {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                !s.dark
        }
    }
    PumpingTheme(s.dark) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val labels = listOf("Pump", "Stash", "Stats", "Profile")
                    val icons =
                        listOf(
                            Icons.Outlined.WaterDrop,
                            Icons.Outlined.AcUnit,
                            Icons.Outlined.BarChart,
                            Icons.Outlined.Person,
                        )
                    labels.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icons[i], label) },
                            label = { Text(label) },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    indicatorColor =
                                        (if (i == 1) MaterialTheme.colorScheme.secondary
                                            else MaterialTheme.colorScheme.primary)
                                            .copy(alpha = 0.15f),
                                    selectedIconColor =
                                        if (i == 1) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.primary,
                                    selectedTextColor =
                                        if (i == 1) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.primary,
                                ),
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                tabState.SaveableStateProvider(tab) {
                    when (tab) {
                        0 ->
                            PumpScreen(
                                sessions,
                                s,
                                now,
                                timer,
                                { sheet = "reminder" },
                                { sheet = "timer" },
                                {
                                    editId = null
                                    sheet = "session"
                                },
                                {
                                    editId = it.id
                                    sheet = "session"
                                },
                                stashedSessionIds = bags.mapNotNull { it.bag.sourceSession }.toSet(),
                            )
                        1 ->
                            StashScreen(
                                bags,
                                movements,
                                s,
                                now,
                                { sheet = "bag" },
                                {
                                    bagId = null
                                    sheet = "use"
                                },
                                {
                                    bagId = it
                                    sheet = "detail"
                                },
                            )
                        2 -> StatsScreen(sessions, bags, movements, s, now, { sheet = "goal" })
                        3 -> ProfileScreen(vm, s, requestNotification, { sheet = it })
                    }
                }
            }
        }
        if (sheet.isNotEmpty())
            Dialog(
                onDismissRequest = { if (sheet !in listOf("session", "bag", "use")) sheet = "" },
                properties =
                    DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
            ) {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    when (sheet) {
                        "session" ->
                            if (editId != timer.id || timer.draftEnd > 0)
                                SessionEditor(
                                    vm,
                                    s,
                                    sessions.find { it.id == editId },
                                    if (editId == timer.id) timer else null,
                                    requestNotification,
                                    { sheet = "" },
                                )
                        "timer" ->
                            TimerScreen(
                                vm,
                                timer,
                                s,
                                requestNotification,
                                { sheet = "" },
                                {
                                    editId = it
                                    sheet = "session"
                                },
                            )
                        "reminder" ->
                            ReminderScreen(
                                vm,
                                s,
                                now,
                                requestNotification,
                                { sheet = "" },
                                { sheet = "reminderSettings" },
                            )
                        "bag" -> BagEditor(vm, s, { sheet = "" })
                        "use" -> UsageEditor(vm, s, bags, bagId, { sheet = "" })
                        "detail" ->
                            BagDetail(
                                vm,
                                s,
                                bags.find { it.bag.id == bagId },
                                movements,
                                { sheet = "" },
                                { sheet = "use" },
                            )
                        "pumps" -> PumpManager(vm, { sheet = "" })
                        "goal" -> GoalEditor(vm, s, { sheet = "" })
                        else -> SettingsDetail(vm, s, sheet, requestNotification, { sheet = "" })
                    }
                }
            }
        if (error != null)
            AlertDialog(
                onDismissRequest = { vm.error.value = null },
                title = { Text("Unable to complete action") },
                text = { Text(error!!) },
                confirmButton = { TextButton({ vm.error.value = null }) { Text("OK") } },
            )
        if (permissionNotice)
            AlertDialog(
                onDismissRequest = { permissionNotice = false },
                title = { Text("Notifications are off") },
                text = {
                    Text(
                        "Records and timer still work. Enable notifications in Android app settings to receive reminders."
                    )
                },
                confirmButton = { TextButton({ permissionNotice = false }) { Text("OK") } },
            )
    }
}
