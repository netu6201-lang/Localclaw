package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.EdgeClawApp
import com.example.ui.state.EdgeClawViewModel
import com.example.ui.theme.EdgeClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdgeClawTheme(darkTheme = true, dynamicColor = false) {
                val vm: EdgeClawViewModel = viewModel(
                    factory = EdgeClawViewModel.factory(application)
                )
                EdgeClawApp(viewModel = vm)
            }
        }
    }
}
