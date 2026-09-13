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
import kotlinx.coroutines.launch
import org.json.JSONObject

class PetOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var webView: WebView
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var thinking = false

    // ====== Operit 通信暗号 ======
    companion object {
        const val ACTION_PET_TAPPED = "com.ai.assistance.operit.EXTERNAL_CHAT"
        const val ACTION_OPERIT_REPLY = "com.example.screenpet.OPERIT_REPLY"
        const val EXTRA_MESSAGE = "message"
    }

    // 接收 Operit 回复
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
        startForegroundSafely()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupWebView()
        setupWindow()
        handler.post(pollRunnable)

        val filter = IntentFilter(ACTION_OPERIT_REPLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(operitReplyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(operitReplyReceiver, filter)
        }
    }

    private fun startForegroundSafely() {
        val channelId = "screenpet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    channelId, "桌宠运行中", NotificationManager.IMPORTANCE_MIN
                ))
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
                    onDrag = { dx, dy -> moveWindow(dx, dy) },
                    onDragEnd = { snapToEdge() }
                ),
                "AndroidBridge"
            )
            loadUrl("file:///android_asset/pet.html")
        }
    }

    private fun setupWindow() {
        val size = dp(150)
        params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 600
        }
        wm.addView(webView, params)
    }

    private fun moveWindow(dx: Float, dy: Float) {
        params.x += dx.toInt()
        params.y += dy.toInt()
        params.x = params.x.coerceIn(-40, screenWidth() - dp(110))
        params.y = params.y.coerceIn(0, screenHeight() - dp(150))
        try { wm.updateViewLayout(webView, params) } catch (_: Exception) {}
    }

    private fun snapToEdge() {
        val target = if (params.x + dp(75) < screenWidth() / 2) -20 else screenWidth() - dp(130)
        val step = (target - params.x) / 8
        if (step == 0) return
        handler.post(object : Runnable {
            var count = 0
            override fun run() {
                if (count++ >= 8) return
                params.x += step
                try { wm.updateViewLayout(webView, params) } catch (_: Exception) {}
                handler.postDelayed(this, 16)
            }
        })
    }

    // ========== 核心：点击桌宠 ==========
    private fun onPetTapped() {
        if (thinking) return
        thinking = true
        js("window.petThink(true)")

        // 1) 把“被戳了”这件事通过 HTTP 通知 Operit
        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://127.0.0.1:8094/chat")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer 6a7921e915264ea1bbfc3bad67ef871a")
                conn.doOutput = true
                val body = "{\"message\": \"用户戳了桌宠一下，屏幕内容：${ScreenReaderService.latestScreenText}\"}"
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }

        // 2) 本地回应
        scope.launch {
            val reply = DeepSeekClient.chat(
                ScreenReaderService.latestScreenText,
                ForegroundAppTracker.sceneDescription()
            )
            js("window.petSay(${JSONObject.quote(reply)})")
            js("window.petThink(false)")
            thinking = false
        }
    }

    private fun js(code: String) {
        handler.post {
            try { webView.evaluateJavascript(code, null) } catch (_: Exception) {}
        }
    }

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        scope.cancel()
        try { unregisterReceiver(operitReplyReceiver) } catch (_: Exception) {}
        try { wm.removeView(webView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
