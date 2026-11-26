package io.github.tohrutodo.tohru.overlay

import android.annotation.SuppressLint

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.tohrutodo.tohru.QuickTaskActivity
import io.github.tohrutodo.tohru.R

// Control Center entry point. A second tap closes the running overlay.
class QuickTaskTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (OverlayRuntime.active.value) {
            stopService(Intent(this, OverlayTaskService::class.java))
            return
        }
        if (Settings.canDrawOverlays(this)) {
            startOverlay()
        } else {
            val intent = Intent(this, QuickTaskActivity::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        3,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayTaskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.ui_quick_tile_label)
        tile.state = if (OverlayRuntime.active.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_quick_task_tile)
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: android.content.Context) {
            requestListeningState(
                context,
                ComponentName(context, QuickTaskTileService::class.java),
            )
        }
    }
}
