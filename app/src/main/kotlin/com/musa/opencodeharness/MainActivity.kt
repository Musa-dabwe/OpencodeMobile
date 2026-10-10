package com.musa.opencodeharness

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
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
import com.musa.opencodeharness.ui.theme.OpenCodeMobileTheme
import androidx.lifecycle.lifecycleScope
import java.io.File

class MainActivity : ComponentActivity() {

    /**
     * Activity-scoped, so the ViewModel -- and therefore the OpenCodeClient it owns and the
     * proot server it started -- survives rotation, and is released exactly once, in
     * `ViewModel.onCleared()`.
     *
     * The factory is handed a DIRECTORY, not an endpoint: there is no endpoint to hand over.
     * `ProotLauncher` picks a free loopback port and generates the password, and the ViewModel
     * obtains the resulting endpoint from the server it started itself.
     *
     * The scope handed to the factory is the Activity's `lifecycleScope` on purpose: the
     * runtime's log-tail and death-watch collectors must not outlive it.
     */
    private val viewModel: SessionListViewModel by viewModels {
        SessionListViewModel.factory(
            runtimeDir = File(filesDir, "runtime"),
            // Where Android extracts jniLibs, and the only place the app may execute from.
            nativeLibraryDir = File(applicationInfo.nativeLibraryDir),
            scope = lifecycleScope,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark only, but the SYSTEM may be in light mode, and the default
        // `enableEdgeToEdge()` picks its icon colour from the system setting rather than from
        // the app. That leaves dark status-bar icons on a #0C0C0E background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            OpenCodeMobileTheme {
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
