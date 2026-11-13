/*
 * Copyright (C) 2026 Scrolless
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.scrolless.app.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.scrolless.app.core.repository.AppForegroundUsageStore
import com.scrolless.app.core.repository.UserSettingsStore
import com.scrolless.app.core.repository.setTimerOverlayPosition
import com.scrolless.app.designsystem.util.formatAsTime
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Manages the full-screen touch-through dimming overlay and floating timer overlay
 * specifically while using Instagram.
 *
 * As the user spends time in Instagram today, the timer counts up and the touch-through
 * dark overlay progressively dims the screen from transparent (0%) up to 60% darkness
 * at 30 minutes of daily use.
 */
@Singleton
class InstagramUsageOverlayManager @Inject constructor(
    private val appForegroundUsageStore: AppForegroundUsageStore,
    private val userSettingsStore: UserSettingsStore,
) {

    companion object {
        private const val MAX_DARKNESS_ALPHA = 0.60f
        private const val TARGET_USAGE_MILLIS_FOR_MAX_DARKNESS = 30 * 60 * 1000L // 30 minutes
        private const val FLUSH_INTERVAL_MILLIS = 15_000L // Periodic save every 15 seconds

        fun calculateDimAlpha(totalUsageMillis: Long): Int {
            val ratio = (totalUsageMillis.toFloat() / TARGET_USAGE_MILLIS_FOR_MAX_DARKNESS).coerceIn(0f, 1f)
            val currentDarkness = ratio * MAX_DARKNESS_ALPHA
            return (currentDarkness * 255).toInt().coerceIn(0, 255)
        }
    }

    private lateinit var serviceContext: Context
    private var windowManager: WindowManager? = null

    private var isEnabled = true
    private var activePackage: String? = null
    private var isShowing = false

    private var dimView: View? = null
    private var dimLayoutParams: WindowManager.LayoutParams? = null

    private var pillView: DragInterceptFrameLayout? = null
    private var timerTextView: TextView? = null
    private var pillLayoutParams: WindowManager.LayoutParams? = null

    private var screenBounds: ScreenBounds? = null
    private var touchSlop: Int = 0

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null

    // Session time tracking
    private var sessionStartRealtime: Long = 0L
    private var lastFlushRealtime: Long = 0L
    private var baseUsageToday: Long = 0L
    private var trackingDate: LocalDate = LocalDate.now()

    // Drag state for the floating pill
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var snapAnimator: ValueAnimator? = null

    fun attachServiceContext(context: Context) {
        serviceContext = context
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled && isShowing) {
            val pkg = activePackage
            if (pkg != null) onInstagramExited(pkg)
        }
    }

    fun isShowing(): Boolean = isShowing

    fun onInstagramEntered(packageName: String) {
        if (!isEnabled || !::serviceContext.isInitialized) return
        if (isShowing && activePackage == packageName) return

        Timber.d("Instagram entered: %s, initializing timer and dim overlay", packageName)
        activePackage = packageName
        trackingDate = LocalDate.now()
        sessionStartRealtime = SystemClock.elapsedRealtime()
        lastFlushRealtime = sessionStartRealtime

        coroutineScope.launch {
            baseUsageToday = appForegroundUsageStore.getTodayUsageMillis(packageName)
            showOverlays()
            startTicker(packageName)
        }
    }

    fun onInstagramExited(packageName: String) {
        if (!isShowing && activePackage == null) return
        Timber.d("Instagram exited: %s, saving remaining usage and removing overlays", packageName)

        tickerJob?.cancel()
        tickerJob = null

        val nowRealtime = SystemClock.elapsedRealtime()
        val unflushedElapsed = (nowRealtime - lastFlushRealtime).coerceAtLeast(0L)
        if (unflushedElapsed > 0L) {
            coroutineScope.launch {
                appForegroundUsageStore.addUsageMillis(packageName, unflushedElapsed)
            }
        }

        hideOverlays()
        activePackage = null
    }

    private fun showOverlays() {
        val wm = windowManager ?: return
        screenBounds = calculateScreenBounds()

        // 1. Setup full screen touch-through dimming view
        if (dimView == null) {
            dimView = View(serviceContext).apply {
                setBackgroundColor(Color.argb(calculateDimAlpha(baseUsageToday), 0, 0, 0))
            }
            dimLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.FILL
            }
        }

        // 2. Setup floating timer pill
        if (pillView == null) {
            createPillView()
        }

        // Update initial timer text
        timerTextView?.text = baseUsageToday.formatAsTime()
        dimView?.setBackgroundColor(Color.argb(calculateDimAlpha(baseUsageToday), 0, 0, 0))

        try {
            if (dimView?.isAttachedToWindow == false) {
                wm.addView(dimView, dimLayoutParams)
            }
            if (pillView?.isAttachedToWindow == false) {
                keepTimerInAllowedArea()
                wm.addView(pillView, pillLayoutParams)
            }
            isShowing = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to add Instagram overlays to window")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createPillView() {
        timerTextView = TextView(serviceContext).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)

            val paddingH = dpToPx(16f)
            val paddingV = dpToPx(8f)
            setPadding(paddingH, paddingV, paddingH, paddingV)

            background = GradientDrawable().apply {
                setColor(Color.argb(204, 28, 28, 30)) // Dark translucent rounded pill
                cornerRadius = dpToPx(20f).toFloat()
            }
            elevation = dpToPx(6f).toFloat()
            gravity = Gravity.CENTER
        }

        pillView = DragInterceptFrameLayout(serviceContext).apply {
            addView(
                timerTextView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnTouchListener { _, event -> handleTouch(event) }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepTimerInAllowedArea() }
        }

        pillLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = userSettingsStore.getTimerOverlayPositionX().value
            y = userSettingsStore.getTimerOverlayPositionY().value
        }
    }

    private fun startTicker(packageName: String) {
        tickerJob?.cancel()
        tickerJob = coroutineScope.launch {
            while (isActive) {
                delay(1000L)
                val nowRealtime = SystemClock.elapsedRealtime()
                val today = LocalDate.now()

                // Check for midnight rollover
                if (today != trackingDate) {
                    val untilMidnight = nowRealtime - lastFlushRealtime
                    if (untilMidnight > 0) {
                        appForegroundUsageStore.addUsageMillis(packageName, untilMidnight)
                    }
                    trackingDate = today
                    baseUsageToday = 0L
                    sessionStartRealtime = nowRealtime
                    lastFlushRealtime = nowRealtime
                }

                val currentSessionElapsed = nowRealtime - sessionStartRealtime
                val totalUsageToday = baseUsageToday + currentSessionElapsed

                // Update timer text and dimming alpha
                timerTextView?.text = totalUsageToday.formatAsTime()
                val alpha = calculateDimAlpha(totalUsageToday)
                dimView?.setBackgroundColor(Color.argb(alpha, 0, 0, 0))

                // Periodic flush every 15 seconds
                if (nowRealtime - lastFlushRealtime >= FLUSH_INTERVAL_MILLIS) {
                    val flushDelta = nowRealtime - lastFlushRealtime
                    appForegroundUsageStore.addUsageMillis(packageName, flushDelta)
                    baseUsageToday += flushDelta
                    sessionStartRealtime = nowRealtime
                    lastFlushRealtime = nowRealtime
                }
            }
        }
    }

    private fun hideOverlays() {
        isShowing = false
        val wm = windowManager ?: return

        try {
            dimView?.let { view ->
                if (view.isAttachedToWindow) wm.removeView(view)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove dimView")
        }

        try {
            pillView?.let { view ->
                if (view.isAttachedToWindow) wm.removeView(view)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove pillView")
        }

        dimView = null
        pillView = null
        timerTextView = null
    }

    fun cleanup() {
        val pkg = activePackage
        if (pkg != null) onInstagramExited(pkg)
        hideOverlays()
        coroutineScope.cancel()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = pillLayoutParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                screenBounds = calculateScreenBounds()
                keepTimerInAllowedArea()
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()

                if (!isDragging && abs(deltaX) <= touchSlop && abs(deltaY) <= touchSlop) return true
                isDragging = true

                moveTimerTo(initialX - deltaX, initialY + deltaY)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    snapToEdge()
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun moveTimerTo(x: Int, y: Int) {
        val params = pillLayoutParams ?: return
        params.x = x
        params.y = y
        keepTimerInAllowedArea()
        try {
            windowManager?.updateViewLayout(pillView, params)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update timer overlay position")
        }
    }

    private fun snapToEdge() {
        val params = pillLayoutParams ?: return
        val bounds = screenBounds ?: return
        val viewWidth = pillView?.width ?: dpToPx(80f)
        val minX = dpToPx(12f)
        val maxX = (bounds.width - viewWidth - dpToPx(12f)).coerceAtLeast(minX)

        val targetX = if (params.x < (minX + maxX) / 2) minX else maxX
        val targetY = params.y

        val startX = params.x
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).toInt()
                try {
                    windowManager?.updateViewLayout(pillView, params)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to update view layout on snap")
                }
            }
            start()
        }

        coroutineScope.launch {
            userSettingsStore.setTimerOverlayPosition(targetX, targetY)
        }
    }

    private fun keepTimerInAllowedArea() {
        val bounds = screenBounds ?: return
        val params = pillLayoutParams ?: return
        val view = pillView ?: return
        val viewWidth = if (view.width > 0) view.width else dpToPx(80f)
        val viewHeight = if (view.height > 0) view.height else dpToPx(40f)

        val maxX = (bounds.width - viewWidth).coerceAtLeast(0)
        val maxY = (bounds.height - viewHeight - dpToPx(20f)).coerceAtLeast(0)

        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(dpToPx(40f), maxY)
    }

    private fun calculateScreenBounds(): ScreenBounds {
        val wm = windowManager ?: return ScreenBounds(1080, 2400)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            ScreenBounds(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            ScreenBounds(point.x, point.y)
        }
    }

    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        serviceContext.resources.displayMetrics,
    ).toInt()

    private inner class DragInterceptFrameLayout(context: Context) : FrameLayout(context) {
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            screenBounds = calculateScreenBounds()
            keepTimerInAllowedArea()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
    }

    private data class ScreenBounds(val width: Int, val height: Int)
}
