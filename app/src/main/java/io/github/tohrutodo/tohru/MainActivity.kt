package io.github.tohrutodo.tohru

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import io.github.tohrutodo.tohru.ui.AppRoot
import io.github.tohrutodo.tohru.ui.HapticGate
import io.github.tohrutodo.tohru.ui.ThemeMode
import io.github.tohrutodo.tohru.overlay.OverlayTaskService
import io.github.tohrutodo.tohru.ui.ProvideNow

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TohruApp
        val prefs = app.prefs
        val vault = app.vault
        val git = app.git
        val prompter = app.prompter
        val store = app.store
        stopOverlay()
        setContent {
            var dynamic by remember { mutableStateOf(prefs.getBoolean("dynamic", true)) }
            var themeMode by remember { mutableStateOf(ThemeMode.fromPrefs(prefs)) }
            var haptics by remember { mutableStateOf(prefs.getBoolean("haptics", true)) }
            val view = LocalView.current
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            HapticGate(enabled = haptics) {
                ProvideNow {
                    AppRoot(
                        app = app,
                        store = store,
                        git = git,
                        prompter = prompter,
                        vault = vault,
                        dynamic = dynamic,
                        onDynamicChange = { enabled ->
                            dynamic = enabled
                            prefs.edit { putBoolean("dynamic", enabled) }
                        },
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            ThemeMode.save(prefs, mode)
                        },
                        haptics = haptics,
                        onHapticsChange = { enabled ->
                            haptics = enabled
                            prefs.edit { putBoolean("haptics", enabled) }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopOverlay()
    }

    // Opening the main app is an explicit switch back from the overlay.
    private fun stopOverlay() {
        stopService(Intent(this, OverlayTaskService::class.java))
    }
}
