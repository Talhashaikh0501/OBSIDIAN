package com.obsidian.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.obsidian.assistant.ui.theme.OBSIDIANTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OBSIDIANTheme {
                ObsidianHomeScreen()
            }
        }
    }
}