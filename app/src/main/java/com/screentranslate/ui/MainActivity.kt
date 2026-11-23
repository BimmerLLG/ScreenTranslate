package com.screentranslate.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.screentranslate.logger.L
import com.screentranslate.ocr.ScreenCapture
import com.screentranslate.service.KeepAliveService
import com.screentranslate.service.TranslateAccessibilityService
import com.screentranslate.ui.theme.ScreenTranslateTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "已禁止通知权限，保活通知和翻译状态将无法显示", Toast.LENGTH_LONG).show()
        }
    }

    val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ScreenCapture.pendingIntent = result.data
            val intent = Intent(this, TranslateAccessibilityService::class.java).apply {
                action = TranslateAccessibilityService.ACTION_INIT_OCR
            }
            // 服务内部会进入前台（MEDIA_PROJECTION 类型），用 startForegroundService 更稳妥
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "截屏权限已获取，长按「译」即可使用 OCR 翻译", Toast.LENGTH_LONG).show()
            L.d("Main", "截屏权限授权成功")
        } else {
            Toast.makeText(this, "截屏权限被拒绝，OCR 翻译功能无法使用", Toast.LENGTH_LONG).show()
            L.w("Main", "截屏权限授权失败")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.d("Main", "MainActivity onCreate")
        // Android 13+ 首次启动时检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                showNotificationPermissionDialog()
            }
        }
        // App 启动即开启保活前台服务，防止进程被杀
        KeepAliveService.start(this)
        L.d("Main", "保活服务已启动")
        setContent {
            ScreenTranslateTheme {
                MainScreen(context = this)
            }
        }
    }

    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要通知权限")
            .setMessage("屏幕翻译需要通知权限来：\n\n" +
                    "1. 在通知栏显示翻译服务运行状态\n" +
                    "2. 防止系统在后台杀死翻译服务\n" +
                    "3. 点击通知可快速返回应用\n\n" +
                    "如果拒绝，服务可能在后台被系统杀死，翻译会中断。")
            .setPositiveButton("允许") { _, _ ->
                L.d("Main", "用户点击「允许」通知权限")
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("拒绝") { _, _ ->
                L.d("Main", "用户点击「拒绝」通知权限")
                Toast.makeText(this, "已跳过通知权限，服务保活效果可能受限", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(context: Context) {
    val prefs = remember { context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE) }
    // 服务开关以持久化状态为准，重启 App 后不会和实际运行状态不一致
    var serviceStarted by remember { mutableStateOf(prefs.getBoolean("service_enabled", false)) }
    var accessibilityOk by remember { mutableStateOf(context.isAccessibilityServiceEnabled()) }
    var overlayOk by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(context) else true
        )
    }
    var captureOk by remember { mutableStateOf(ScreenCapture.hasPermission) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOk = context.isAccessibilityServiceEnabled()
                overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Settings.canDrawOverlays(context) else true
                captureOk = ScreenCapture.hasPermission
                serviceStarted = prefs.getBoolean("service_enabled", false)
                L.d("Main", "权限检查: 无障碍=${accessibilityOk}, 悬浮窗=${overlayOk}, 截屏=${captureOk}")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("屏幕翻译") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "实时提取屏幕文字并通过 AI 翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            SettingsButton(
                icon = Icons.Default.Tune,
                text = "设置",
                onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }
            )

            Spacer(Modifier.height(8.dp))

            SettingsButton(
                icon = Icons.Default.Accessibility,
                text = "开启无障碍服务",
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )

            Spacer(Modifier.height(8.dp))

            SettingsButton(
                icon = Icons.Default.PictureInPicture,
                text = "开启悬浮窗权限",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ))
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            SettingsButton(
                icon = if (captureOk) Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                text = if (captureOk) "截屏权限已开启（OCR 可用）" else "开启截屏权限（OCR 翻译）",
                onClick = {
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                    (context as MainActivity).requestScreenCapture.launch(manager.createScreenCaptureIntent())
                }
            )

            Spacer(Modifier.height(32.dp))

            ServiceStatusCard(
                accessibilityOk = accessibilityOk,
                overlayOk = overlayOk,
                captureOk = captureOk,
                serviceStarted = serviceStarted
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (!overlayOk) {
                        Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    // 启动前必须已开启无障碍服务，否则服务不会真正工作
                    if (!serviceStarted && !accessibilityOk) {
                        Toast.makeText(context, "请先开启无障碍服务，再启动翻译", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    val target = !serviceStarted
                    val intent = Intent(context, TranslateAccessibilityService::class.java)
                    intent.action = if (target)
                        TranslateAccessibilityService.ACTION_START
                    else
                        TranslateAccessibilityService.ACTION_STOP
                    L.d("Main", "发送 ${if (target) "ACTION_START" else "ACTION_STOP"}")
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            context.startForegroundService(intent)
                        else
                            context.startService(intent)
                        serviceStarted = target
                        // 更新保活通知状态
                        KeepAliveService.updateStatus(context, target)
                        L.d("Main", "保活状态更新: isRunning=$target")
                        if (target) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                (context as? android.app.Activity)?.moveTaskToBack(true)
                            }, 500)
                        }
                    } catch (e: Exception) {
                        L.e("Main", "启动/停止翻译服务失败", e)
                        Toast.makeText(
                            context,
                            "服务操作失败：${e.message?.take(40) ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
                // 保持可点击：缺少权限时点击会通过 Toast 明确告知原因，而不是静默禁用
            ) {
                Icon(
                    if (serviceStarted) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (serviceStarted) "停止翻译服务" else "启动翻译服务")
            }
        }
    }
}

@Composable
fun SettingsButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
fun ServiceStatusCard(
    accessibilityOk: Boolean,
    overlayOk: Boolean,
    captureOk: Boolean,
    serviceStarted: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusRow(Icons.Default.Accessibility, "无障碍服务", accessibilityOk)
            Spacer(Modifier.height(8.dp))
            StatusRow(Icons.Default.PictureInPicture, "悬浮窗权限", overlayOk)
            Spacer(Modifier.height(8.dp))
            StatusRow(Icons.Default.CameraAlt, "截屏权限（OCR）", captureOk)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    !accessibilityOk || !overlayOk -> "需要完成权限配置"
                    serviceStarted -> "翻译运行中... 切换到其他应用查看效果"
                    else -> "就绪，点击启动"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$label: ${if (ok) "已开启" else "未开启"}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun Context.isAccessibilityServiceEnabled(): Boolean {
    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    val cn = ComponentName(this, TranslateAccessibilityService::class.java)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == cn.packageName &&
        it.resolveInfo.serviceInfo.name == cn.className
    }
}
