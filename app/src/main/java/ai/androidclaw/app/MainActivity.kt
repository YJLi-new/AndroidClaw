package ai.androidclaw.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.androidclaw.ui.navigation.AndroidClawApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AndroidClawApplication).container
        setContent {
            AndroidClawApp(container = container)
        }
    }
}
