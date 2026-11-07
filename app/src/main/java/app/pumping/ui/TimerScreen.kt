package app.pumping.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.pumping.*
import app.pumping.data.*
import app.pumping.domain.*
import kotlinx.coroutines.delay

@Composable
fun TimerScreen(
    vm: PumpViewModel,
    t: TimerState,
    s: Settings,
    notification: () -> Unit,
    close: () -> Unit,
    finish: (String) -> Unit,
) {
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var reset by remember { mutableStateOf(false) }
    var targetDialog by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(t.targetMinutes.toString()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val elapsed = t.elapsed(tick, vm.boot)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(close) { Text("Close") }
            FilledTonalButton({ targetDialog = true }) { Text("◷ ${t.targetMinutes} min") }
        }
        if (t.start > 0)
            CardBlock {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Started\n${clock(t.start,s)}")
                    Text("→")
                    Text(
                        "Target\n${clock(System.currentTimeMillis()+maxOf(0,t.targetMinutes*60000L-elapsed),s)}"
                    )
                }
            }
        Spacer(Modifier.weight(0.2f))
        Bottle(
            (elapsed.toFloat() / (t.targetMinutes * 60000)).coerceIn(0f, 1f),
            Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            "${elapsed/60000}:${(elapsed/1000%60).toString().padStart(2,'0')}",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (t.id.isNotEmpty() && !t.running && t.boot != vm.boot)
            Text(
                "Timer recovered paused after reboot. Only confirmed active time was retained; review the duration when saving."
            )
        Button(
            onClick = {
                if (t.id.isEmpty()) notification()
                vm.launch { vm.timerAction(if (t.running) "pause" else "start") }
            },
            shape = CircleShape,
            modifier = Modifier.sizeIn(minWidth = 120.dp, minHeight = 64.dp),
        ) {
            Text(if (t.running) "Pause" else if (t.id.isEmpty()) "Start" else "Resume")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton({ reset = true }, enabled = t.id.isNotEmpty()) { Text("Reset") }
            Button(
                {
                    vm.launch {
                        busy = true
                        try {
                            vm.timerAction("done")
                            finish(t.id)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = t.id.isNotEmpty() && elapsed >= 1000 && !busy,
            ) {
                Text("Done")
            }
        }
        Text(
            "Target alerts are approximate. The timer keeps counting after its target.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (reset)
        Confirm(
            "Reset timer?",
            "Active time will be cleared. No pump session has been saved.",
            { reset = false },
            {
                vm.launch {
                    vm.timerAction("reset")
                    reset = false
                }
            },
        )
    if (targetDialog)
        AlertDialog(
            onDismissRequest = { targetDialog = false },
            title = { Text("Select Duration") },
            text = {
                Column {
                    Choices(listOf("5", "10", "15", "20", "30"), target) { target = it }
                    Field("Minutes (1–240)", target, { target = it }, true)
                }
            },
            confirmButton = {
                TextButton(
                    {
                        val n = target.toIntOrNull()
                        if (n != null && n in 1..240)
                            vm.launch {
                                vm.timerAction("target", n)
                                targetDialog = false
                            }
                    },
                    enabled = target.toIntOrNull() in 1..240,
                ) {
                    Text("Set")
                }
            },
        )
}

@Composable
fun Bottle(fill: Float, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width.coerceAtMost(size.height * 0.72f)
        val h = size.height * 0.92f
        val x = (size.width - w) / 2
        val y = (size.height - h) / 2
        val body =
            RoundRect(x + w * 0.16f, y + h * 0.27f, x + w * 0.84f, y + h, CornerRadius(w * 0.12f))
        val bodyPath = Path().apply { addRoundRect(body) }
        drawPath(bodyPath, accent, style = Stroke(4.dp.toPx()))
        drawRoundRect(
            accent,
            Offset(x + w * .19f, y + h * .20f),
            Size(w * .62f, h * .07f),
            CornerRadius(w * .04f),
            style = Stroke(3.dp.toPx()),
        )
        val top =
            Path().apply {
                moveTo(x + w * .28f, y + h * .20f)
                cubicTo(
                    x + w * .28f,
                    y + h * .10f,
                    x + w * .42f,
                    y + h * .12f,
                    x + w * .42f,
                    y + h * .05f,
                )
                cubicTo(
                    x + w * .42f,
                    y - h * .01f,
                    x + w * .58f,
                    y - h * .01f,
                    x + w * .58f,
                    y + h * .05f,
                )
                cubicTo(
                    x + w * .58f,
                    y + h * .12f,
                    x + w * .72f,
                    y + h * .10f,
                    x + w * .72f,
                    y + h * .20f,
                )
            }
        drawPath(top, accent, style = Stroke(3.dp.toPx()))
        // Original bottle artwork; milk fill is clipped within the bottle body.
        drawContext.canvas.save()
        drawContext.canvas.clipPath(bodyPath)
        drawRect(Color(0xFFF3E7CE), Offset(x, y + h - (h * .68f * fill)), Size(w, h * .68f * fill))
        drawContext.canvas.restore()
        for (i in 0..3) drawLine(
            accent,
            Offset(x + w * .68f, y + h * (.40f + i * .12f)),
            Offset(x + w * .79f, y + h * (.40f + i * .12f)),
            3.dp.toPx(),
        )
    }
}
