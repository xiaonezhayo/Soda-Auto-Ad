package com.soda.adhelper.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.soda.adhelper.core.AutomationRuntime
import com.soda.adhelper.core.NotificationHelper
import com.soda.adhelper.model.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FloatingOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var rootView: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var toggleButton: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AutomationRuntime.init(this)
        NotificationHelper.ensureChannels(this)

        val notification = NotificationHelper.buildOverlayForegroundNotification(this, "悬浮窗控制条已就绪")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_OVERLAY,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_OVERLAY, notification)
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        attachOverlayView()
        observeSnapshot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOverlayView() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6111827"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#4DFFFFFF"))
            }
            elevation = dp(6).toFloat()
        }

        val statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "待机 · 已领 0 次"
            setPadding(0, 0, dp(10), 0)
        }

        val toggleBtn = createPillButton(
            text = "暂停",
            bgColor = Color.parseColor("#0E9F6E"),
            dp = ::dp
        ).apply {
            setOnClickListener {
                when (AutomationRuntime.snapshot.value.status) {
                    RunStatus.RUNNING -> AutomationRuntime.pause("用户通过悬浮窗暂停")
                    RunStatus.PAUSED -> AutomationRuntime.resumeSession()
                    else -> AutomationRuntime.startNewSession()
                }
            }
        }

        val stopBtn = createPillButton(
            text = "停止",
            bgColor = Color.parseColor("#DC2626"),
            dp = ::dp
        ).apply {
            setOnClickListener {
                AutomationRuntime.stop("用户通过悬浮窗停止任务")
                stopSelf()
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(1))
        }

        container.addView(statusView)
        container.addView(toggleBtn)
        container.addView(spacer)
        container.addView(stopBtn)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        statusView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(container, layoutParams) }
                    true
                }

                else -> false
            }
        }

        statusTextView = statusView
        toggleButton = toggleBtn
        rootView = container
        windowManager.addView(container, layoutParams)
    }

    private fun createPillButton(text: String, bgColor: Int, dp: (Int) -> Int): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(16).toFloat()
            }
        }
    }

    private fun observeSnapshot() {
        scope.launch {
            AutomationRuntime.snapshot.collectLatest { snap ->
                val dot = when (snap.status) {
                    RunStatus.RUNNING -> "●"
                    RunStatus.PAUSED -> "❚❚"
                    RunStatus.COMPLETED -> "✓"
                    else -> "○"
                }
                statusTextView?.text = "$dot ${snap.status.label} · 已领 ${snap.completedAds} 次"
                toggleButton?.text = when (snap.status) {
                    RunStatus.RUNNING -> "暂停"
                    RunStatus.PAUSED -> "继续"
                    else -> "开始"
                }
            }
        }
    }

    companion object {
        private const val ACTION_HIDE = "com.soda.adhelper.action.HIDE_OVERLAY"

        fun show(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }
}
