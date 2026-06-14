package ai.androidclaw.app

import ai.androidclaw.ui.navigation.AndroidClawApp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AndroidClawApplication
        val container = app.container
        app.ensureStartupMaintenanceStarted()
        setContent {
            AndroidClawApp(container = container)
        }
    }
}
