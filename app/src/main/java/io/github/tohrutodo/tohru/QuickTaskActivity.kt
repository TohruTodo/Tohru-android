package io.github.tohrutodo.tohru

import android.annotation.SuppressLint

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.tohrutodo.tohru.overlay.OverlayTaskService
import io.github.tohrutodo.tohru.ui.ThemeMode
import io.github.tohrutodo.tohru.ui.TohruTheme

// Permission gate for the full-screen task overlay. After the first grant the
// alias goes straight to the service.
@SuppressLint("UseKtx")
class QuickTaskActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Settings.canDrawOverlays(this)) {
            startOverlay()
            return
        }
        setContent {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val mode = ThemeMode.fromPrefs(prefs)
            val view = LocalView.current
            val darkTheme = mode.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            TohruTheme(
                dynamic = prefs.getBoolean("dynamic", true),
                mode = mode,
            ) {
                PermissionScreen(
                    onGrant = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) startOverlay()
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayTaskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(stringResource(R.string.quick_overlay_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.quick_overlay_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onGrant) { Text(stringResource(R.string.quick_overlay_grant)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.quick_overlay_later)) }
            }
        }
    }
}
