package com.rusure.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.rusure.app.ui.settings.SettingsScreen
import com.rusure.app.ui.settings.SettingsViewModel
import com.rusure.app.ui.theme.RuSureTheme

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory((application as RuSureApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RuSureTheme {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}
