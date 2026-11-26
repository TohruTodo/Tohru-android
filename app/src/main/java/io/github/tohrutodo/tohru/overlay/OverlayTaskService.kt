package io.github.tohrutodo.tohru.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import io.github.tohrutodo.tohru.MainActivity
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.TohruApp
import io.github.tohrutodo.tohru.sync.SyncSettings
import io.github.tohrutodo.tohru.ui.OverlayTaskScreen
import io.github.tohrutodo.tohru.ui.ThemeMode
import io.github.tohrutodo.tohru.ui.TohruTheme

// Full-screen floating task panel. The window is transparent and uses
// blur-behind where the platform supports it; the task content supplies its
// own dark surfaces for contrast.
class OverlayTaskService : Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(io.github.tohrutodo.tohru.AppLocale.wrap(newBase))
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val vmStore = ViewModelStore()
    private val backDispatcher = OnBackPressedDispatcher()
    private var overlayView: ComposeView? = null
    private val systemDialogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = intent.getStringExtra("reason")
            if (reason == "homekey" || reason == "recentapps") stopSelf()
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        backDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = stopSelf()
        })
        createChannel()
        runCatching {
            ContextCompat.registerReceiver(
                this,
                systemDialogReceiver,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (overlayView == null) addOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as TohruApp
        if (!SyncSettings.readKeepOverlay(app.prefs)) stopSelf()
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runCatching { unregisterReceiver(systemDialogReceiver) }
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        OverlayRuntime.active.value = false
        QuickTaskTileService.requestUpdate(this)
        vmStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val windowManager: WindowManager
        get() = getSystemService(WindowManager::class.java)

    private fun addOverlay() {
        val app = application as TohruApp
        val view = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusableInTouchMode = true
            requestFocus()
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    backDispatcher.onBackPressed()
                    true
                } else {
                    false
                }
            }
            setViewTreeLifecycleOwner(this@OverlayTaskService)
            setViewTreeSavedStateRegistryOwner(this@OverlayTaskService)
            setViewTreeViewModelStoreOwner(this@OverlayTaskService)
            setViewTreeOnBackPressedDispatcherOwner(this@OverlayTaskService)
            setContent {
                TohruTheme(
                    dynamic = app.prefs.getBoolean("dynamic", true),
                    mode = ThemeMode.fromPrefs(app.prefs),
                ) {
                    OverlayTaskScreen(app = app, onClose = { stopSelf() })
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                windowManager.isCrossWindowBlurEnabled
            ) {
                setBlurBehindRadius((28 * resources.displayMetrics.density).toInt())
            }
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                overlayView = view
                applyGestureExclusions(view)
                view.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
                    if (focused) view.requestFocus()
                }
                OverlayRuntime.active.value = true
                QuickTaskTileService.requestUpdate(this)
            }
            .onFailure { stopSelf() }
    }

    // Keep the system back/home edge gestures from acting on the app below
    // the overlay, so the Compose edge handler receives the drag.
    private fun applyGestureExclusions(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val edge = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val update = View.OnLayoutChangeListener { target, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@OnLayoutChangeListener
            target.systemGestureExclusionRects = listOf(
                Rect(0, 0, edge, height),
                Rect(width - edge, 0, width, height),
            )
        }
        view.addOnLayoutChangeListener(update)
        view.doOnLayout { update.onLayoutChange(view, 0, 0, view.width, view.height, 0, 0, 0, 0) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.overlay_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification {
        val close = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayTaskService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_task)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.overlay_close), close)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "overlay-task"
        private const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "io.github.tohrutodo.tohru.overlay.STOP"
    }
}
