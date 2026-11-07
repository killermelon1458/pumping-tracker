package app.pumping

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.pumping.reminders.Reminders
import app.pumping.ui.*

class MainActivity : ComponentActivity() {
    private val model: PumpViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        model.launch { model.app.reminders.restore(model.app) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PumpingRoot(model) }
        notificationOpened(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationOpened(intent)
    }

    private fun notificationOpened(intent: Intent?) {
        val token = intent?.getStringExtra("pump-notification-token").orEmpty()
        if (token.isNotEmpty())
            model.launch {
                model.app.reminders.receive(
                    model.app,
                    Intent(Reminders.DISMISS).putExtra("token", token),
                )
            }
    }
}
