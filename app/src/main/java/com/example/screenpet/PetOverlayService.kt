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
        if (dataUrl == null) js("window.setPetImage(null)")
        else js("window.setPetImage(${JSONObject.quote(dataUrl)})")
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
                    onDragStart = { _, _ -> },
                    onDragMove = { x, y -> movePetTo(x, y) },
                    onDragEnd = { }
                ),
                "AndroidBridge"
            )
            loadUrl("file:///android_asset/pet.html")
        }
    }

    private fun setupWindow() {
        // 铺满全屏，不再裁剪桌宠
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        wm.addView(webView, params)
    }

    // 拖动：把 JS 发来的手指坐标透传给 JS，由 JS 决定猫的位置
    // 因为窗口铺满全屏，坐标 1:1 对应
    private fun movePetTo(x: Float, y: Float) {
        js("window.__nativeDragTo(${x},${y})")
    }

    private fun onPetTapped() {
        if (thinking) return
        thinking = true
        js("window.petThink(true)")

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
