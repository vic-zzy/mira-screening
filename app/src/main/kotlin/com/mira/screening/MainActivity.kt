package com.mira.screening

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mira.screening.ui.MiraNavHost
import com.mira.screening.ui.theme.MiraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiraTheme {
                MiraNavHost()
            }
        }
    }
}
