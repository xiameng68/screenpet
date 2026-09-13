package com.example.screenpet

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etApiUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etPersonality: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etApiUrl = findViewById(R.id.etApiUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        etPersonality = findViewById(R.id.etPersonality)

        val prefs = getSharedPreferences("pet", MODE_PRIVATE)
        etApiUrl.setText(prefs.getString("apiUrl", "https://api.deepseek.com/chat/completions"))
        etApiKey.setText(prefs.getString("apiKey", ""))
        etModel.setText(prefs.getString("model", "deepseek-chat"))
        etPersonality.setText(prefs.getString("personality", ""))

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.text = "请先开启悬浮窗权限"
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(
                this, Intent(this, PetOverlayService::class.java)
            )
            tvStatus.text = "桌宠已召唤 · 返回桌面看看"
            val saved = prefs.getString("customImg", null)
            if (saved != null) {
                PetOverlayService.instance?.setPetImage(saved)
            }
        }

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, 1001)
        }

        findViewById<Button>(R.id.btnResetImage).setOnClickListener {
            PetOverlayService.instance?.setPetImage(null)
            prefs.edit().remove("customImg").apply()
            Toast.makeText(this, "已恢复默认小猫", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSaveAI).setOnClickListener {
            val url = etApiUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()
            val model = etModel.text.toString().trim()
            val personality = etPersonality.text.toString().trim()

            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) {
                Toast.makeText(this, "接口、Key、模型 三项都要填哦", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("apiUrl", url)
                .putString("apiKey", key)
                .putString("model", model)
                .putString("personality", personality)
                .apply()
            Toast.makeText(this, "已保存，点桌宠就生效", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return
                val bytes = inputStream.readBytes()
                inputStream.close()

                if (bytes.size > 1_500_000) {
                    Toast.makeText(this, "图片太大，选一张小一点的", Toast.LENGTH_LONG).show()
                    return
                }

                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val dataUrl = "data:$mime;base64,$base64"

                getSharedPreferences("pet", MODE_PRIVATE).edit()
                    .putString("customImg", dataUrl).apply()

                PetOverlayService.instance?.setPetImage(dataUrl)
                Toast.makeText(this, "已换上你的图", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "读取图片失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val access = if (isAccessibilityEnabled()) "✅" else "❌"
        val usage = if (hasUsageAccess()) "✅" else "❌"
        tvStatus.text = "悬浮窗 $overlay    无障碍 $access    使用统计 $usage"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${ScreenReaderService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
