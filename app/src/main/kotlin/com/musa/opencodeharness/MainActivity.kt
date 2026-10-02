package com.musa.opencodeharness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musa.opencodeharness.ui.SessionListScreen
import com.musa.opencodeharness.ui.SessionListViewModel
import com.musa.opencodeharness.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {

    /**
     * Activity-scoped, so the ViewModel -- and therefore the one OpenCodeClient it owns --
     * survives rotation and is released exactly once, in `ViewModel.onCleared()`.
     *
     * The endpoint is the documented placeholder; see `SessionListViewModel.factory`.
     */
    private val viewModel: SessionListViewModel by viewModels {
        SessionListViewModel.factory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme {
                // Lifecycle-aware: collection stops when the Activity is stopped, so a
                // backgrounded app is not re-rendering a session list nobody can see.
                val state by viewModel.state.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SessionListScreen(
                        state = state,
                        onRetry = viewModel::load,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
