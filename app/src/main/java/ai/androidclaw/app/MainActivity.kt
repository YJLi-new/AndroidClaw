package ai.androidclaw.app

import ai.androidclaw.ui.navigation.AndroidClawApp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AndroidClawApplication).container
        setContent {
            AndroidClawApp(container = container)
        }
    }
}
