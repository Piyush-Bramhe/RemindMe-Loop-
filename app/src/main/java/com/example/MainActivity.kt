package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.MainApp
import com.example.ui.ReminderViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize the Viewmodel using the android application context
        val viewModel = ReminderViewModel(application)

        setContent {
            MyApplicationTheme {
                MainApp(viewModel = viewModel)
            }
        }
    }
}

