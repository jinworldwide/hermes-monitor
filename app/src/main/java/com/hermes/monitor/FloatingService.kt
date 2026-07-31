package com.hermes.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var webView: WebView
    private var isMinimized = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var serverUrl = "http://127.0.0.1:8787"

    companion object {
        private const val CHANNEL_ID = "hermes_monitor_floating"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "hermes_monitor_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val WIDTH = 800
        private const val HEIGHT = 600
        private const val MINIMIZED_SIZE = 60
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Load configurable URL from intent extra or SharedPreferences
        serverUrl = intent?.getStringExtra("server_url")?.trim()?.let { url ->
            if (url.isNotEmpty()) {
                saveServerUrl(url)
                url
            } else {
                loadServerUrl()
            }
        } ?: loadServerUrl()

        // Recreate view if already exists (e.g. after config change)
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        createFloatingView()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveServerUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url)
            .apply()
    }

    private fun loadServerUrl(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "http://127.0.0.1:8787") ?: "http://127.0.0.1:8787"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Монитор",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Плавающее окно монитора"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Монитор")
            .setContentText("Плавающее окно активно • $serverUrl")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun createFloatingView() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = FrameLayout(this)

        // WebView — основной контент
        webView = WebView(this)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowContentAccess = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    view?.loadUrl(url ?: return false)
                    return true
                }
            }
            loadUrl(serverUrl)
        }
        floatingView.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Верхняя панель управления (полупрозрачная, поверх WebView)
        val controlBar = FrameLayout(this)
        controlBar.setBackgroundColor(0x88000000.toInt())
        controlBar.elevation = 4f

        // URL label
        val urlLabel = TextView(this)
        urlLabel.text = serverUrl
        urlLabel.setTextColor(0xFF8B949E.toInt())
        urlLabel.textSize = 10f
        urlLabel.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        urlLabel.isSingleLine = true
        urlLabel.setPadding(8, 0, 0, 0)
        val urlParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(8, 0, 0, 0)
        }
        controlBar.addView(urlLabel, urlParams)

        // Minimize button
        val minBtn = View(this)
        minBtn.setBackgroundResource(android.R.drawable.ic_menu_zoom)
        val minParams = FrameLayout.LayoutParams(36, 36)
        minParams.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        minParams.setMargins(0, 0, 48, 0)
        minBtn.layoutParams = minParams
        minBtn.setOnClickListener { toggleMinimize() }
        controlBar.addView(minBtn)

        // Close button
        val closeBtn = View(this)
        closeBtn.setBackgroundResource(android.R.drawable.ic_menu_close_clear_cancel)
        val closeParams = FrameLayout.LayoutParams(36, 36)
        closeParams.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        closeParams.setMargins(0, 0, 8, 0)
        closeBtn.layoutParams = closeParams
        closeBtn.setOnClickListener { stopSelf() }
        controlBar.addView(closeBtn)

        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            44
        )
        controlBar.layoutParams = barParams
        floatingView.addView(controlBar)

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

        // Drag handling (только по control bar)
        controlBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
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
