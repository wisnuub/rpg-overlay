package com.orna.autobattle

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

class AutoBattleAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TAP   = "com.orna.autobattle.TAP"
        const val ACTION_SWIPE = "com.orna.autobattle.SWIPE"
        const val EXTRA_X  = "x";  const val EXTRA_Y  = "y"
        const val EXTRA_X2 = "x2"; const val EXTRA_Y2 = "y2"

        private const val ORNA_PACKAGE = "playorna.com.orna"

        var instance: AutoBattleAccessibilityService? = null
            private set

        fun isRunning() = instance != null
    }


    private val tapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TAP -> performTap(
                    intent.getFloatExtra(EXTRA_X, 0f),
                    intent.getFloatExtra(EXTRA_Y, 0f)
                )
                ACTION_SWIPE -> performSwipe(
                    intent.getFloatExtra(EXTRA_X, 0f),
                    intent.getFloatExtra(EXTRA_Y, 0f),
                    intent.getFloatExtra(EXTRA_X2, 0f),
                    intent.getFloatExtra(EXTRA_Y2, 0f)
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter().apply {
            addAction(ACTION_TAP)
            addAction(ACTION_SWIPE)
        }
        ContextCompat.registerReceiver(this, tapReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Show overlay when Orna comes to foreground; never auto-hide —
        // manufacturer overlays (Game Space, game launchers, etc.) fire
        // spurious window events that make debouncing unreliable.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == ORNA_PACKAGE) {
            OverlayControlService.setOrnaForeground(true)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterReceiver(tapReceiver)
    }

    fun performTap(x: Float, y: Float, durationMs: Long = 80) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            android.util.Log.w("AccessibilitySvc", "performTap failed: ${e.message}")
        }
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300) {
        try {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            android.util.Log.w("AccessibilitySvc", "performSwipe failed: ${e.message}")
        }
    }
}
