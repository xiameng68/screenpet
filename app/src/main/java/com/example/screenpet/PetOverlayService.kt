package com.example.screenpet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class PetOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var webView: WebView
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var thinking = false

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var winStartX = 0
    private var winStartY = 0

    // 记录上一次查记忆前的屏幕文字（用来对比出 Operit 的新回复）
    private var lastScreenText: String = ""

    companion object {
        const val ACTION_OPERIT_REPLY = "com.example.screenpet.OPERIT_REPLY"
        const val EXTRA_MESSAGE = "message"
        @Volatile var instance: PetOverlayService? = null
    }

    private val operitReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(EXTRA_MESSAGE) ?: return
            js("window.petSay(${JSONObject.quote(msg)})")
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            ForegroundAppTracker.poll(this@PetOverlayService)
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundSafely()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupWebView()
        setupWindow()
        handler.post(pollRunnable)

        val saved = getSharedPreferences("pet", MODE_PRIVATE).getString("customImg", null)
        if (saved != null) {
            webView.postDelayed({ setPetImage(saved) }, 600)
        }

        val filter = IntentFilter(ACTION_OPERIT_REPLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(operitReplyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(operitReplyReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("resetImage", false) == true) {
            js("window.setPetImage(null)")
        }
        return START_STICKY
    }

    fun setPetImage(dataUrl: String?) {
        handler.post {
            if (dataUrl == null) {
                webView.evaluateJavascript("window.setPetImage(null)", null)
            } else {
                webView.evaluateJavascript(
                    "window.setPetImage(${JSONObject.quote(dataUrl)})", null
                )
            }
        }
    }

    private fun startForegroundSafely() {
        val channelId = "screenpet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "桌宠运行中", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenPet 正在陪你")
            .setContentText("桌宠已在桌面待命")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1001, notif)
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = WebViewClient()
            addJavascriptInterface(
                PetBridge(
                    onPetClick = { onPetTapped() },
                    onDragStart = { x, y ->
                        dragStartX = x; dragStartY = y
                        winStartX = params.x; winStartY = params.y
                    },
                    onDragMove = { x, y ->
                        val dx = x - dragStartX
                        val dy = y - dragStartY
                        params.x = winStartX + dx.toInt()
                        params.y = winStartY + dy.toInt()
                        try { wm.updateViewLayout(webView, params) } catch (_: Exception) {}
                    },
                    onDragEnd = { },
                    onScaleChanged = { s -> resizeWindow(s) }
                ),
                "AndroidBridge"
            )
            loadUrl("file:///android_asset/pet.html")
        }
    }

    private fun setupWindow() {
        val size = dp(180)
        params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 600
        }
        wm.addView(webView, params)
    }

    fun resizeWindow(scale: Float) {
        val base = dp(180)
        val newSize = (base * scale).toInt().coerceIn(dp(80), dp(600))
        params.width = newSize
        params.height = newSize
        try { wm.updateViewLayout(webView, params) } catch (_: Exception) {}
    }

    // ============ 核心：点击桌宠 ============
    private fun onPetTapped() {
        if (thinking) return
        thinking = true
        js("window.petThink(true)")

        // 1) 记录当前屏幕文字，用来对比
        lastScreenText = ScreenReaderService.latestScreenText

        // 2) 给 Operit 发暗号：查记忆
        scope.launch(Dispatchers.IO) {
            try {
                sendOperitCommand("陆知肠，读历史：20")
            } catch (_: Exception) {}
        }

        // 3) 等 3 秒，让 Operit 有时间处理并显示回复
        scope.launch {
            delay(3000)
            val newText = ScreenReaderService.latestScreenText
            val extracted = extractOperitReply(lastScreenText, newText)

            val reply = if (extracted.isNullOrBlank()) {
                DeepSeekClient.chat(
                    this@PetOverlayService,
                    newText,
                    ForegroundAppTracker.sceneDescription()
                )
            } else {
                extracted
            }

            js("window.petSay(${JSONObject.quote(reply)})")
            js("window.petThink(false)")
            thinking = false
        }
    }

    // 给 Operit 的 HTTP 接口发一条消息
    private fun sendOperitCommand(message: String) {
        try {
            val url = java.net.URL("http://127.0.0.1:8094/chat")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer 6a7921e915264ea1bbfc3bad67ef871a")
            conn.doOutput = true
            val body = "{\"message\": ${JSONObject.quote(message)}}"
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    // 从屏幕文字里，找到 Operit 新回复的那一段
    private fun extractOperitReply(old: String, new: String): String? {
        if (new.isBlank()) return null
        // 简单策略：找出 new 里比 old 多出来的尾巴
        if (old.isBlank()) return new.takeLast(300)
        val idx = new.indexOf(old.takeLast(80))
        if (idx < 0) return new.takeLast(300)
        val diff = new.substring(idx + old.takeLast(80).length).trim()
        return diff.ifBlank { null }
    }

    private fun js(code: String) {
        handler.post {
            try { webView.evaluateJavascript(code, null) } catch (_: Exception) {}
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(pollRunnable)
        scope.cancel()
        try { unregisterReceiver(operitReplyReceiver) } catch (_: Exception) {}
        try { wm.removeView(webView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
