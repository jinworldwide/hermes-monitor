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
import android.widget.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var outerContainer: FrameLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var webView: WebView
    private var keyboardWindow: FrameLayout? = null
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
    private var interactionMode = false
    private var closePending = false
    private var touchListener: View.OnTouchListener? = null

    companion object {
        private const val CHANNEL_ID = "hermes_monitor_floating"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "hermes_monitor_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val CONTENT_W = 800
        private const val CONTENT_H = 600
        private const val MINIMIZED_SIZE = 60
        private const val MIN_CONTENT_W = 200
        private const val MIN_CONTENT_H = 150
        private const val CORNER_RADIUS = 24f
        private const val DRAG_THRESHOLD = 10
        private const val BTN_DIAMETER = 60
        private const val OUTER_PAD = BTN_DIAMETER / 2  // 30dp gap between outer edge and content
        private const val CLOSE_CONFIRM_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("server_url")?.trim()?.let { url ->
            if (url.isNotEmpty()) { saveServerUrl(url); url } else { loadServerUrl() }
        } ?: loadServerUrl()

        if (::outerContainer.isInitialized) windowManager.removeView(outerContainer)
        createFloatingView()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveServerUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SERVER_URL, url).apply()
    }
    private fun loadServerUrl(): String =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SERVER_URL, "http://127.0.0.1:8787") ?: "http://127.0.0.1:8787"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Монитор", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
    private fun createNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("Монитор").setContentText("Плавающее окно активно")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    }

    inner class MonitorBridge {
        @JavascriptInterface fun toggleMinimize() { Handler(Looper.getMainLooper()).post { toggleMinimize() } }
        @JavascriptInterface fun closeApp() { Handler(Looper.getMainLooper()).post { confirmClose() } }
        @JavascriptInterface fun toggleInteraction() { Handler(Looper.getMainLooper()).post { toggleInteractionMode() } }
    }

    private fun createFloatingView() {
        // OUTER container — dark background, no clip
        outerContainer = FrameLayout(this)
        outerContainer.setBackgroundColor(0xFF0d1117.toInt())
        outerContainer.clipChildren = false
        outerContainer.clipToPadding = false

        // INNER content container — rounded corners
        contentContainer = object : FrameLayout(this) {
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
        contentContainer.setBackgroundColor(0xFF0d1117.toInt())

        // contentContainer is smaller than outerContainer by OUTER_PAD on each side
        val ccParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        ccParams.setMargins(OUTER_PAD, OUTER_PAD, OUTER_PAD, OUTER_PAD)
        outerContainer.addView(contentContainer, ccParams)

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
            // Desktop User-Agent for PC view
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            // No blanket touch blocker — interaction mode controls this
            addJavascriptInterface(MonitorBridge(), "MonitorBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
            }
            loadUrl(serverUrl)
        }
        contentContainer.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 4 corner buttons — children of outerContainer, positioned at corners
        // Each button is BTN_DIAMETER x BTN_DIAMETER, placed at the very corner of outerContainer
        // Since contentContainer has OUTER_PAD margin, the button naturally overhangs the content
        addCornerButton(Gravity.START or Gravity.TOP, "▬", "Свернуть", "btn_minimize") { toggleMinimize() }
        addCornerButton(Gravity.END or Gravity.TOP, "✕", "Закрыть", "btn_close") { confirmClose() }
        addCornerButton(Gravity.START or Gravity.BOTTOM, "☰", "Взаимодействие", "btn_interact") { toggleInteractionMode() }
        addCornerButton(Gravity.END or Gravity.BOTTOM, "⌨", "Клавиатура", "btn_keyboard") { showKeyboardWindow() }

        // Window params — outer size = content + BTN_DIAMETER
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val outerW = CONTENT_W + BTN_DIAMETER
        val outerH = CONTENT_H + BTN_DIAMETER

        val params = WindowManager.LayoutParams(
            outerW, outerH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (size.x - outerW) / 2
            y = (size.y - outerH) / 2
        }

        // Touch handling
        touchListener = View.OnTouchListener { _, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x
                    val y = event.y
                    val w = outerContainer.width
                    val h = outerContainer.height
                    // If touch is in button zone, let button handle it
                    val onButton = (x < BTN_DIAMETER || x > w - BTN_DIAMETER) &&
                            (y < BTN_DIAMETER || y > h - BTN_DIAMETER)
                    if (onButton) return@OnTouchListener false

                    isDragging = false
                    isPinching = false
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    initialWidth = params.width; initialHeight = params.height
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        isPinching = true; isDragging = false
                        initialDist = spacing(event)
                        initialWidth = params.width; initialHeight = params.height
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPinching && event.pointerCount >= 2) {
                        val scale = spacing(event) / initialDist
                        val minW = MIN_CONTENT_W + BTN_DIAMETER
                        val minH = MIN_CONTENT_H + BTN_DIAMETER
                        params.width = max(minW, min(size.x, (initialWidth * scale).toInt()))
                        params.height = max(minH, min(size.y, (initialHeight * scale).toInt()))
                        windowManager.updateViewLayout(outerContainer, params)
                    } else if (!isPinching) {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (isDragging || dx > DRAG_THRESHOLD || dx < -DRAG_THRESHOLD || dy > DRAG_THRESHOLD || dy < -DRAG_THRESHOLD) {
                            isDragging = true
                            params.x = initialX + dx; params.y = initialY + dy
                            windowManager.updateViewLayout(outerContainer, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { isDragging = false; isPinching = false; true }
                MotionEvent.ACTION_POINTER_UP -> { if (event.pointerCount <= 2) isPinching = false; true }
                else -> false
            }
        }
        outerContainer.setOnTouchListener(touchListener)

        windowManager.addView(outerContainer, params)
    }

    private fun addCornerButton(gravity: Int, text: String, desc: String, tag: String, onClick: () -> Unit) {
        val btn = ImageView(this)
        btn.contentDescription = desc
        btn.isClickable = true
        btn.isFocusable = false
        btn.tag = tag

        val bg = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(0xFF2d2d2d.toInt())
        }
        btn.background = bg

        val params = FrameLayout.LayoutParams(BTN_DIAMETER, BTN_DIAMETER)
        params.gravity = gravity
        // No negative margins — button sits at the very corner of outerContainer
        // Since contentContainer has OUTER_PAD margin, the button naturally overhangs the content
        btn.layoutParams = params
        btn.setOnClickListener { onClick() }

        val paint = android.graphics.Paint().apply {
            color = 0xFF8b949e.toInt()
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        btn.post {
            val bmp = android.graphics.Bitmap.createBitmap(btn.width, btn.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val cx = btn.width / 2f
            val cy = btn.height / 2f
            val fm = paint.fontMetrics
            canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
            btn.setImageBitmap(bmp)
        }

        outerContainer.addView(btn)
    }

    private fun toggleMinimize() {
        val p = outerContainer.layoutParams as WindowManager.LayoutParams
        if (isMinimized) {
            p.width = CONTENT_W + BTN_DIAMETER
            p.height = CONTENT_H + BTN_DIAMETER
            contentContainer.visibility = View.VISIBLE
            isMinimized = false
        } else {
            p.width = MINIMIZED_SIZE
            p.height = MINIMIZED_SIZE
            contentContainer.visibility = View.GONE
            isMinimized = true
        }
        windowManager.updateViewLayout(outerContainer, p)
    }

    private fun confirmClose() {
        if (closePending) {
            stopSelf()
            return
        }
        closePending = true
        Toast.makeText(this, "Ещё раз — закрыть", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ closePending = false }, CLOSE_CONFIRM_MS)
    }

    private fun toggleInteractionMode() {
        interactionMode = !interactionMode
        updateInteractionMode()
    }

    private fun updateInteractionMode() {
        val p = outerContainer.layoutParams as WindowManager.LayoutParams
        if (interactionMode) {
            // Remove NOT_FOCUSABLE so WebView can receive touches
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // Remove touch listener from outerContainer — WebView gets touches directly
            outerContainer.setOnTouchListener(null)
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.isClickable = true
            webView.isLongClickable = true
            webView.requestFocus()
        } else {
            // Add NOT_FOCUSABLE back — outerContainer handles drag/pinch
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            outerContainer.setOnTouchListener(touchListener)
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.isClickable = false
            webView.isLongClickable = false
            webView.clearFocus()
        }
        windowManager.updateViewLayout(outerContainer, p)

        val btn3 = outerContainer.findViewWithTag<ImageView>("btn_interact")
        if (btn3 != null) {
            val bg = btn3.background as? GradientDrawable
            bg?.setColor(if (interactionMode) 0xFF555555.toInt() else 0xFF2d2d2d.toInt())
        }
    }

    private fun showKeyboardWindow() {
        keyboardWindow?.let { windowManager.removeView(it) }

        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        val monitorP = outerContainer.layoutParams as WindowManager.LayoutParams
        val monitorBottom = monitorP.y + outerContainer.height

        val kbdLayout = FrameLayout(this)
        kbdLayout.setBackgroundColor(0xFF2d2d2d.toInt())
        kbdLayout.elevation = 10f
        kbdLayout.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 16f)
            }
        }
        kbdLayout.clipToOutline = true

        val input = EditText(this)
        input.apply {
            hint = "Напиши что показать..."
            setHintTextColor(0xFF8b949e.toInt())
            setTextColor(0xFFc9d1d9.toInt())
            background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                setColor(0xFF1a1a1a.toInt())
                cornerRadius = 8f
                setStroke(1, 0xFF404040.toInt())
            }
            setPadding(16, 12, 16, 12)
            textSize = 16f
            isFocusable = true
            isFocusableInTouchMode = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    sendText(input.text.toString())
                    true
                } else false
            }
        }

        val sendBtn = Button(this)
        sendBtn.text = "➤"
        sendBtn.setTextColor(0xFFc9d1d9.toInt())
        sendBtn.setBackgroundColor(0xFF404040.toInt())
        sendBtn.textSize = 18f
        sendBtn.minimumWidth = 0
        sendBtn.minimumHeight = 0
        sendBtn.setPadding(20, 10, 20, 10)
        sendBtn.setOnClickListener { sendText(input.text.toString()) }

        val inputLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        inputLp.setMargins(12, 8, 60, 8)
        kbdLayout.addView(input, inputLp)

        val sendLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        sendLp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        sendLp.setMargins(0, 8, 12, 8)
        kbdLayout.addView(sendBtn, sendLp)

        val kbdH = 120
        val kbdP = WindowManager.LayoutParams(
            size.x, kbdH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = min(monitorBottom, size.y - kbdH)
        }

        windowManager.addView(kbdLayout, kbdP)
        keyboardWindow = kbdLayout

        Handler(Looper.getMainLooper()).postDelayed({
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun sendText(text: String) {
        if (text.isBlank()) return
        // Send via WebView JavaScript — no HTTP connection needed
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webView.post {
            webView.evaluateJavascript(
                "fetch('/keyboard_input', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({text:'$escaped'})}).catch(e=>{});",
                null
            )
        }
        keyboardWindow?.let { windowManager.removeView(it) }
        keyboardWindow = null
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    override fun onDestroy() {
        keyboardWindow?.let { windowManager.removeView(it) }
        if (::outerContainer.isInitialized) windowManager.removeView(outerContainer)
        super.onDestroy()
    }
}
