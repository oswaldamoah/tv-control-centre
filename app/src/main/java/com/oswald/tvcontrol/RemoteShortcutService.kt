package com.oswald.tvcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional, low-workload global number-key listener. */
class RemoteShortcutService : AccessibilityService() {
    private data class Press(val startedAt: Long, var fired: Boolean = false, var task: Runnable? = null)

    private val handler = Handler(Looper.getMainLooper())
    private val presses = HashMap<Int, Press>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val digit = digitFor(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && !presses.containsKey(event.keyCode)) {
                val press = Press(event.downTime)
                presses[event.keyCode] = press
                val task = Runnable {
                    val active = presses[event.keyCode]
                    if (active === press && !press.fired &&
                        SystemClock.uptimeMillis() - press.startedAt >= HOLD_THRESHOLD_MS) {
                        press.fired = true
                        launchShortcut(digit)
                    }
                }
                press.task = task
                handler.postDelayed(task, HOLD_THRESHOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                val press = presses.remove(event.keyCode)
                press?.task?.let(handler::removeCallbacks)
                // Handles a delayed ACTION_UP delivered after the threshold callback.
                if (press != null && !press.fired && event.eventTime - press.startedAt >= HOLD_THRESHOLD_MS) {
                    press.fired = true
                    launchShortcut(digit)
                }
            }
            KeyEvent.ACTION_MULTIPLE -> Unit
        }
        // Preserve ordinary remote operation: never consume the original key event.
        return false
    }

    private fun digitFor(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_0 -> "0"
        KeyEvent.KEYCODE_1 -> "1"
        KeyEvent.KEYCODE_2 -> "2"
        KeyEvent.KEYCODE_3 -> "3"
        KeyEvent.KEYCODE_4 -> "4"
        KeyEvent.KEYCODE_5 -> "5"
        KeyEvent.KEYCODE_6 -> "6"
        KeyEvent.KEYCODE_7 -> "7"
        KeyEvent.KEYCODE_8 -> "8"
        KeyEvent.KEYCODE_9 -> "9"
        else -> null
    }

    private fun launchShortcut(digit: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REMOTE_SHORTCUT
            putExtra(MainActivity.EXTRA_SHORTCUT_DIGIT, digit)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try { startActivity(intent) } catch (_: Exception) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        presses.values.forEach { press -> press.task?.let(handler::removeCallbacks) }
        presses.clear()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object { private const val HOLD_THRESHOLD_MS = 3_000L }
}
