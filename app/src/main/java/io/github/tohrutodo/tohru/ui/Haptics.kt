package io.github.tohrutodo.tohru.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

// Every haptic goes through here so one switch turns them all off. Short cues
// use the platform haptic engine; celebrate() runs a slightly longer vibration
// for finishing a task.
class Haptics internal constructor(
    private val feedback: HapticFeedback,
    private val vibrator: Vibrator?,
    private val enabled: Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(hapticFeedbackType)
    }

    fun celebrate() {
        if (!enabled) return
        val device = vibrator
        if (device != null && device.hasVibrator()) {
            runCatching {
                device.vibrate(VibrationEffect.createOneShot(CELEBRATE_MILLIS, CELEBRATE_AMPLITUDE))
            }
        } else {
            feedback.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    private companion object {
        const val CELEBRATE_MILLIS = 70L
        const val CELEBRATE_AMPLITUDE = 190
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

// Routes every haptic below it through the user's switch. Compose components
// that perform their own haptics read the same local, so one switch turns all
// of them off.
@Composable
fun HapticGate(enabled: Boolean, content: @Composable () -> Unit) {
    val system = LocalHapticFeedback.current
    val context = LocalContext.current
    val vibrator = remember(context) { vibratorOf(context) }
    val haptics = remember(enabled, system, vibrator) {
        Haptics(feedback = system, vibrator = vibrator, enabled = enabled)
    }
    CompositionLocalProvider(
        LocalHapticFeedback provides haptics,
        LocalHaptics provides haptics,
        content = content,
    )
}

private fun vibratorOf(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
