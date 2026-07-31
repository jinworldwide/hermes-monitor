package com.hermes.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var webView: WebView
    private lateinit var touchOverlay: View
    private var isMinimized = false
    private var isDragging = false
    private var isPinching = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialDist = 0f
    private var serverUrl = "http://127.0.0.1:8787"

    companion object {
        private const val CHANNEL_ID = "hermes_monitor_floating"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "hermes_monitor_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val WIDTH = 800
        private const val HEIGHT = 600
        private const val MINIMIZED_SIZE = 60
        private const val MIN_W = 200
        private const val MIN_H = 150
        private const val CORNER_RADIUS = 24f
        private const val DRAG_THRESHOLD = 10
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("server_url")?.trim()?.let { url ->
            if (url.isNotEmpty()) {
                saveServerUrl(url)
                url
            } else {
                loadServerUrl()
            }
        } ?: loadServerUrl()

        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        createFloatingView()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveServerUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url).apply()
    }

    private fun loadServerUrl(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "http://127.0.0.1:8787") ?: "http://127.0.0.1:8787"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Монитор", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("Монитор")
            .setContentText("Плавающее окно активно")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    inner class MonitorBridge {
        @JavascriptInterface
        fun toggleMinimize() {
            Handler(Looper.getMainLooper()).post { toggleMinimize() }
        }
        @JavascriptInterface
        fun closeApp() {
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    private fun createFloatingView() {
        floatingView = object : FrameLayout(this) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, CORNER_RADIUS)
                    }
                }
                clipToOutline = true
            }
        }
        floatingView.setBackgroundColor(0xFF0d1117.toInt())

        // WebView
        webView = WebView(this)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowContentAccess = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            setOnTouchListener { _, _ -> false }
            addJavascriptInterface(MonitorBridge(), "MonitorBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
            loadUrl(serverUrl)
        }
        floatingView.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Прозрачный слой поверх WebView для жестов
        touchOverlay = View(this)
        touchOverlay.setBackgroundColor(0x00000000.toInt())
        touchOverlay.isClickable = true
        touchOverlay.isFocusable = false

        floatingView.addView(touchOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Window params
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)

        val params = WindowManager.LayoutParams(
            WIDTH, HEIGHT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (size.x - WIDTH) / 2
            y = (size.y - HEIGHT) / 2
        }

        // Touch handling
        touchOverlay.setOnTouchListener { _, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    isPinching = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWidth = params.width
                    initialHeight = params.height
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        isPinching = true
                        isDragging = false
                        initialDist = spacing(event)
                        initialWidth = params.width
                        initialHeight = params.height
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPinching && event.pointerCount >= 2) {
                        val newDist = spacing(event)
                        val scale = newDist / initialDist
                        val newW = max(MIN_W, min(size.x, (initialWidth * scale).toInt()))
                        val newH = max(MIN_H, min(size.y, (initialHeight * scale).toInt()))
                        params.width = newW
                        params.height = newH
                        windowManager.updateViewLayout(floatingView, params)
                    } else if (!isPinching) {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (isDragging || Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                            isDragging = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(floatingView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    isPinching = false
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) isPinching = false
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun toggleMinimize() {
        val params = floatingView.layoutParams as WindowManager.LayoutParams
        if (isMinimized) {
            params.width = WIDTH
            params.height = HEIGHT
            webView.visibility = View.VISIBLE
            isMinimized = false
        } else {
            params.width = MINIMIZED_SIZE
            params.height = MINIMIZED_SIZE
            webView.visibility = View.GONE
            isMinimized = true
        }
        windowManager.updateViewLayout(floatingView, params)
    }

    override fun onDestroy() {
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }
}
