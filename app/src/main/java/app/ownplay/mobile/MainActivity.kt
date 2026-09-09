package app.ownplay.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ownplay.mobile.app.OwnPlayApp
import app.ownplay.mobile.design.OwnPlayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OwnPlayTheme {
                OwnPlayApp()
            }
        }
    }
}
