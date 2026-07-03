package com.screentranslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.screentranslate.R
import com.screentranslate.service.TranslateAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggleService)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnRequestOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } else {
                    Toast.makeText(this, "悬浮窗权限已获取", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnToggle.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("需要无障碍服务")
                    .setMessage("请在无障碍设置中找到「屏幕翻译」并开启。")
                    .setPositiveButton("去开启") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("请授予悬浮窗权限以显示翻译气泡。")
                    .setPositiveButton("去授权") { _, _ ->
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }

            serviceStarted = !serviceStarted
            val intent = Intent(this, TranslateAccessibilityService::class.java)
            intent.action = if (serviceStarted) {
                TranslateAccessibilityService.ACTION_START
            } else {
                TranslateAccessibilityService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateButtonState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    private fun updateButtonState() {
        val accessibilityOk = isAccessibilityServiceEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        if (!accessibilityOk || !overlayOk) {
            tvStatus.text = "需要完成权限配置"
            btnToggle.text = "▶ 启动翻译服务"
            serviceStarted = false
            return
        }

        if (serviceStarted) {
            tvStatus.text = "翻译运行中... 切换到其他应用查看效果"
            btnToggle.text = "⏹ 停止翻译服务"
        } else {
            tvStatus.text = "就绪，点击启动"
            btnToggle.text = "▶ 启动翻译服务"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val cn = ComponentName(this, TranslateAccessibilityService::class.java)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == cn.packageName }
    }
}
