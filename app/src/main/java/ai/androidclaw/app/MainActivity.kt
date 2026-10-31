package ai.androidclaw.app

import ai.androidclaw.ui.locale.AppLocaleController
import ai.androidclaw.ui.navigation.AndroidClawApp
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLocaleController.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as AndroidClawApplication
        val container = app.container
        app.ensureStartupMaintenanceStarted()
        setContent {
            AndroidClawApp(container = container)
        }
    }
}
