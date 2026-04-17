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

/**
 * Performs screen taps on behalf of the auto-battle logic.
 * Receives commands from OverlayControlService via LocalBroadcast.
 */
class AutoBattleAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TAP = "com.orna.autobattle.TAP"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val ACTION_SWIPE = "com.orna.autobattle.SWIPE"
        const val EXTRA_X2 = "x2"
        const val EXTRA_Y2 = "y2"

        var instance: AutoBattleAccessibilityService? = null
            private set

        fun isRunning() = instance != null
    }

    private val tapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TAP -> {
                    val x = intent.getFloatExtra(EXTRA_X, 0f)
                    val y = intent.getFloatExtra(EXTRA_Y, 0f)
                    performTap(x, y)
                }
                ACTION_SWIPE -> {
                    val x1 = intent.getFloatExtra(EXTRA_X, 0f)
                    val y1 = intent.getFloatExtra(EXTRA_Y, 0f)
                    val x2 = intent.getFloatExtra(EXTRA_X2, 0f)
                    val y2 = intent.getFloatExtra(EXTRA_Y2, 0f)
                    performSwipe(x1, y1, x2, y2)
                }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterReceiver(tapReceiver)
    }

    fun performTap(x: Float, y: Float, durationMs: Long = 80) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
