package com.musa.opencodeharness

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musa.opencodeharness.ui.SessionListScreen
import com.musa.opencodeharness.ui.SessionListViewModel
import com.musa.opencodeharness.ui.nav.AppShell
import com.musa.opencodeharness.ui.nav.NavTab
import com.musa.opencodeharness.ui.nav.PlaceholderDestination
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

                // Survives rotation and process death via the SaveableStateRegistry, so a
                // relaunch comes back to the tab the user left rather than to Chat every time.
                // Started on Chat because that is the only destination with content: opening on a
                // placeholder is a worse first screen than opening on the app's real content.
                var tab by rememberSaveable { mutableStateOf(NavTab.startDestination) }

                AppShell(selected = tab, onSelect = { tab = it }) { destination ->
                    when (destination) {
                        // The one destination with real content. `SessionListScreen` is untouched:
                        // it is restyled to the design in Task 3, not here.
                        NavTab.CHAT -> SessionListScreen(
                            state = state,
                            onRetry = viewModel::load,
                        )
                        // Honest placeholders. No invented rows, no fake "you have no projects".
                        else -> PlaceholderDestination(destination)
                    }
                }
            }
        }
    }
}
