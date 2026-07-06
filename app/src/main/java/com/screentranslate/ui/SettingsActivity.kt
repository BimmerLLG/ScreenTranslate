package com.screentranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentranslate.logger.L
import com.screentranslate.ocr.ModelManager
import com.screentranslate.service.KeepAliveService
import com.screentranslate.translate.TranslationManager
import com.screentranslate.ui.theme.ScreenTranslateTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScreenTranslateTheme {
                SettingsScreen()
            }
        }
    }
}

enum class SettingsPage { MAIN, ENGINE, MASK, FLOAT, LOGS, TEST, MODELS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var currentScreen by remember { mutableStateOf(SettingsPage.MAIN) }
    when (currentScreen) {
        SettingsPage.MAIN -> MainMenuPage(onNavigate = { currentScreen = it })
        SettingsPage.ENGINE -> EngineSettingsPage(onBack = { currentScreen = SettingsPage.MAIN })
        SettingsPage.MASK -> MaskSettingsPage(onBack = { currentScreen = SettingsPage.MAIN })
        SettingsPage.FLOAT -> FloatSettingsPage(onBack = { currentScreen = SettingsPage.MAIN })
        SettingsPage.LOGS -> LogsPage(onBack = { currentScreen = SettingsPage.MAIN })
        SettingsPage.TEST -> TestPage(onBack = { currentScreen = SettingsPage.MAIN })
        SettingsPage.MODELS -> ModelDownloadPage(onBack = { currentScreen = SettingsPage.MAIN })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainMenuPage(onNavigate: (SettingsPage) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MenuItemCard(
                icon = Icons.Default.Language,
                label = "翻译引擎设置",
                onClick = { onNavigate(SettingsPage.ENGINE) }
            )
            MenuItemCard(
                icon = Icons.Default.Palette,
                label = "翻译遮罩设置",
                onClick = { onNavigate(SettingsPage.MASK) }
            )
            MenuItemCard(
                icon = Icons.Default.Adjust,
                label = "翻译悬浮窗设置",
                onClick = { onNavigate(SettingsPage.FLOAT) }
            )
            MenuItemCard(
                icon = Icons.Default.Article,
                label = "运行日志",
                onClick = { onNavigate(SettingsPage.LOGS) }
            )
            MenuItemCard(
                icon = Icons.Default.Description,
                label = "OCR 测试文本",
                onClick = { onNavigate(SettingsPage.TEST) }
            )
            MenuItemCard(
                icon = Icons.Default.Download,
                label = "模型下载管理",
                onClick = { onNavigate(SettingsPage.MODELS) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuItemCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE) }
    val translationManager = remember { TranslationManager(context) }
    val scope = rememberCoroutineScope()

    var endpoint by remember { mutableStateOf(prefs.getString("ai_endpoint", "https://api.openai.com/v1/chat/completions") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("ai_api_key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("ai_model", "gpt-4o-mini") ?: "") }
    var sourceLang by remember { mutableStateOf(prefs.getString("source_lang", "") ?: "") }
    var targetLang by remember { mutableStateOf(prefs.getString("target_lang", "中文") ?: "") }
    var translationMode by remember { mutableStateOf(prefs.getString("translation_mode", "ai") ?: "") }
    var customPrompt by remember { mutableStateOf(prefs.getString("custom_prompt", "") ?: "") }
    var thinkingEnabled by remember { mutableStateOf(prefs.getBoolean("thinking_enabled", false)) }
    var reasoningEffort by remember { mutableStateOf(prefs.getString("reasoning_effort", "medium") ?: "medium") }
    var testResult by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var effortExpanded by remember { mutableStateOf(false) }
    var batchMode by remember { mutableStateOf(prefs.getString("batch_mode", "batch") ?: "batch") }
    var batchMaxChars by remember { mutableIntStateOf(prefs.getInt("batch_max_chars", 2000).coerceIn(500, 3000)) }
    var enableZhRecognition by remember { mutableStateOf(prefs.getBoolean("enable_zh_recognition", false)) }
    val effortOptions = listOf("low", "medium", "high")

    fun save() {
        prefs.edit().apply {
            putString("ai_endpoint", endpoint)
            putString("ai_api_key", apiKey)
            putString("ai_model", model)
            putString("source_lang", sourceLang)
            putString("target_lang", targetLang)
            putString("translation_mode", translationMode)
            putString("custom_prompt", customPrompt)
            putBoolean("thinking_enabled", thinkingEnabled)
            putString("reasoning_effort", reasoningEffort)
            putString("batch_mode", batchMode)
            putInt("batch_max_chars", batchMaxChars)
            putBoolean("enable_zh_recognition", enableZhRecognition)
            apply()
        }
        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译引擎设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("翻译模式", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = translationMode == "ai",
                    onClick = { translationMode = "ai" }
                )
                Text("AI 翻译（需配置 API）", modifier = Modifier.padding(start = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = translationMode == "google",
                    onClick = { translationMode = "google" }
                )
                Text("Google 离线翻译", modifier = Modifier.padding(start = 4.dp))
            }

            if (translationMode == "ai") {
                Spacer(Modifier.height(12.dp))
                Text("翻译策略", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = batchMode == "batch",
                        onClick = { batchMode = "batch" },
                        label = { Text("打包发送（快）") },
                        leadingIcon = if (batchMode == "batch") {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = batchMode == "parallel",
                        onClick = { batchMode = "parallel" },
                        label = { Text("逐条并行") },
                        leadingIcon = if (batchMode == "parallel") {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (batchMode == "batch") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "每组最大字数: $batchMaxChars",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = batchMaxChars.toFloat(),
                        onValueChange = { batchMaxChars = it.toInt() },
                        valueRange = 500f..3000f,
                        steps = 9
                    )
                    Text(
                        "超过此字数自动分多组并行发送",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("识别中文", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "开启后识别中文文字（但翻译时仍跳过）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableZhRecognition,
                        onCheckedChange = { enableZhRecognition = it }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("API Endpoint") },
                supportingText = { Text("支持 OpenAI 兼容接口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "隐藏" else "显示"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名称") },
                supportingText = { Text("gpt-4o-mini / deepseek-chat / deepseek-v4-flash") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = sourceLang,
                    onValueChange = { sourceLang = it },
                    label = { Text("源语言") },
                    placeholder = { Text("留空自动检测") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = targetLang,
                    onValueChange = { targetLang = it },
                    label = { Text("目标语言") },
                    placeholder = { Text("中文") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            if (translationMode == "ai") {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("AI 高级设置", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    label = { Text("自定义提示词") },
                    supportingText = { Text("留空使用默认翻译提示词") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("思考模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "让模型在回答前进行深度思考（DeepSeek 等模型支持）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = thinkingEnabled,
                        onCheckedChange = { thinkingEnabled = it }
                    )
                }

                if (thinkingEnabled) {
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = effortExpanded,
                        onExpandedChange = { effortExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = reasoningEffort.replaceFirstChar { it.uppercase() },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("思考强度") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = effortExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = effortExpanded,
                            onDismissRequest = { effortExpanded = false }
                        ) {
                            effortOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        reasoningEffort = option
                                        effortExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        save()
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
                OutlinedButton(
                    onClick = {
                        save()
                        translationManager.loadAiConfig()
                        testResult = "翻译中..."
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                translationManager.testTranslation(
                                    "Hello, welcome to Screen Translate!",
                                    translationMode
                                )
                            }
                            testResult = "原文: Hello, welcome to Screen Translate!\n译文: $result"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("测试翻译")
                }
            }

            if (testResult.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = testResult,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaskSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE) }

    var maskOpacity by remember { mutableIntStateOf(prefs.getInt("mask_opacity", 80)) }
    var maskFontSize by remember { mutableIntStateOf(prefs.getInt("mask_font_size", 14)) }
    var maskFontColor by remember { mutableStateOf(prefs.getString("mask_font_color", "#FFFFFF") ?: "#FFFFFF") }
    var maskBgColor by remember { mutableStateOf(prefs.getString("mask_bg_color", "#CC2196F3") ?: "#CC2196F3") }
    var maskCornerRadius by remember { mutableIntStateOf(prefs.getInt("mask_corner_radius", 8)) }
    var maskMaxLines by remember { mutableStateOf(prefs.getString("mask_max_lines", "5") ?: "5") }
    var maskElevation by remember { mutableStateOf(prefs.getBoolean("mask_elevation", true)) }
    var maskAnimation by remember { mutableStateOf(prefs.getBoolean("mask_animation", true)) }
    var maxLinesExpanded by remember { mutableStateOf(false) }
    val maxLinesOptions = listOf("1", "3", "5", "10", "0")

    fun save() {
        prefs.edit().apply {
            putInt("mask_opacity", maskOpacity)
            putInt("mask_font_size", maskFontSize)
            putString("mask_font_color", maskFontColor)
            putString("mask_bg_color", maskBgColor)
            putInt("mask_corner_radius", maskCornerRadius)
            putString("mask_max_lines", maskMaxLines)
            putBoolean("mask_elevation", maskElevation)
            putBoolean("mask_animation", maskAnimation)
            apply()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译遮罩设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("遮罩透明度: $maskOpacity%", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = maskOpacity.toFloat(),
                onValueChange = { maskOpacity = it.toInt() },
                onValueChangeFinished = { save() },
                valueRange = 10f..100f,
                steps = 89
            )

            Spacer(Modifier.height(16.dp))

            Text("字体大小: $maskFontSize", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = maskFontSize.toFloat(),
                onValueChange = { maskFontSize = it.toInt() },
                onValueChangeFinished = { save() },
                valueRange = 12f..28f,
                steps = 15
            )

            Spacer(Modifier.height(16.dp))

            Text("字体颜色", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            ColorPickerRow(
                selectedHex = maskFontColor,
                onColorSelected = { maskFontColor = it; save() }
            )

            Spacer(Modifier.height(16.dp))

            Text("背景颜色", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            ColorPickerRow(
                selectedHex = maskBgColor,
                onColorSelected = { maskBgColor = it; save() }
            )

            Spacer(Modifier.height(16.dp))

            Text("气泡圆角: $maskCornerRadius", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = maskCornerRadius.toFloat(),
                onValueChange = { maskCornerRadius = it.toInt() },
                onValueChangeFinished = { save() },
                valueRange = 0f..24f,
                steps = 23
            )

            Spacer(Modifier.height(16.dp))

            Text("最大行数", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            ExposedDropdownMenuBox(
                expanded = maxLinesExpanded,
                onExpandedChange = { maxLinesExpanded = it }
            ) {
                OutlinedTextField(
                    value = if (maskMaxLines == "0") "不限" else "${maskMaxLines}行",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = maxLinesExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = maxLinesExpanded,
                    onDismissRequest = { maxLinesExpanded = false }
                ) {
                    maxLinesOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(if (option == "0") "不限" else "${option}行") },
                            onClick = {
                                maskMaxLines = option
                                maxLinesExpanded = false
                                save()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("阴影", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "为气泡添加阴影效果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = maskElevation,
                    onCheckedChange = { maskElevation = it; save() }
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("动画", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "气泡显示和隐藏时播放动画",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = maskAnimation,
                    onCheckedChange = { maskAnimation = it; save() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloatSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE) }

    var indicatorSize by remember { mutableIntStateOf(prefs.getInt("indicator_size", 56).coerceIn(32, 72)) }
    var indicatorOpacity by remember { mutableIntStateOf(prefs.getInt("indicator_opacity", 100)) }
    var indicatorDraggable by remember { mutableStateOf(prefs.getBoolean("indicator_draggable", true)) }
    var indicatorShape by remember { mutableStateOf(prefs.getString("indicator_shape", "circle") ?: "circle") }
    var indicatorBgColor by remember { mutableStateOf(prefs.getString("indicator_bg_color", "#2196F3") ?: "#2196F3") }

    val colorOptions = listOf(
        "#2196F3" to "蓝", "#4CAF50" to "绿", "#FF9800" to "橙",
        "#F44336" to "红", "#9C27B0" to "紫", "#00BCD4" to "青"
    )

    fun save() {
        prefs.edit().apply {
            putInt("indicator_size", indicatorSize)
            putInt("indicator_opacity", indicatorOpacity)
            putBoolean("indicator_draggable", indicatorDraggable)
            putString("indicator_shape", indicatorShape)
            putString("indicator_bg_color", indicatorBgColor)
            apply()
        }
    }

    fun resetPosition() {
        prefs.edit().putInt("indicator_pos_x", -1).putInt("indicator_pos_y", -1).apply()
        // 触发展示更新
        try {
            val intent = android.content.Intent("com.screentranslate.ACTION_UPDATE_OVERLAY")
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
        android.widget.Toast.makeText(context, "位置已重置，重新打开悬浮窗生效", android.widget.Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译悬浮窗设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 形状选择
            Text("悬浮窗形状", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = indicatorShape == "circle",
                    onClick = { indicatorShape = "circle"; save() },
                    label = { Text("圆形") },
                    leadingIcon = if (indicatorShape == "circle") {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = indicatorShape == "pill",
                    onClick = { indicatorShape = "pill"; save() },
                    label = { Text("贴边条形") },
                    leadingIcon = if (indicatorShape == "pill") {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))

            // 大小
            Text("悬浮窗大小: ${indicatorSize}dp", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = indicatorSize.toFloat(),
                onValueChange = { indicatorSize = it.toInt() },
                onValueChangeFinished = { save() },
                valueRange = 32f..72f,
                steps = 39
            )

            Spacer(Modifier.height(16.dp))

            // 背景颜色
            Text("背景颜色", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                colorOptions.forEach { (hex, name) ->
                    val selected = indicatorBgColor == hex
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { indicatorBgColor = hex; save() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Default.Check, contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 透明度
            Text("指示器透明度: $indicatorOpacity%", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = indicatorOpacity.toFloat(),
                onValueChange = { indicatorOpacity = it.toInt() },
                onValueChangeFinished = { save() },
                valueRange = 30f..100f,
                steps = 69
            )

            Spacer(Modifier.height(16.dp))

            // 可拖动
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("可拖动", style = MaterialTheme.typography.bodyLarge)
                    Text("允许在屏幕上自由拖动指示器位置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = indicatorDraggable, onCheckedChange = { indicatorDraggable = it; save() })
            }

            Spacer(Modifier.height(16.dp))

            // 重置位置
            OutlinedButton(
                onClick = { resetPosition() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重置悬浮窗位置")
            }

            Spacer(Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("操作说明", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("长按：OCR 截屏翻译所有文字", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("松手自动贴边吸附", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("贴边条形更宽，适合放在屏幕边缘", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("") }

    fun refresh() {
        logText = L.getRecentLines(500).joinToString("\n")
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("运行日志", logText))
                        Toast.makeText(context, "已复制 ${logText.length} 字符", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(onClick = { L.shareLogs(context) }) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = {
                        L.clearLogs()
                        refresh()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Text(
                    text = logText.ifEmpty { "暂无日志" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestPage(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR 测试文本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "此页面包含大量英韩文本，可用于测试 OCR 识别效果。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "操作方法：打开此页面 → 长按屏幕右侧的「译」图标 → 查看 OCR 结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    context.startActivity(android.content.Intent(context, TestActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("打开测试文本")
            }
        }
    }
}

private data class ModelDownloadStatus(
    val name: String, val fileName: String, val expectedSize: Long,
    val downloaded: Boolean, val fileSize: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDownloadPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val scope = rememberCoroutineScope()

    val allModels = remember { ModelManager.allModels }
    var statuses by remember {
        mutableStateOf(allModels.map { m ->
            ModelDownloadStatus(m.name, m.fileName, m.expectedSize, modelManager.isModelDownloaded(m), 0L)
        })
    }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun refreshStatus() {
        statuses = allModels.map { m ->
            ModelDownloadStatus(m.name, m.fileName, m.expectedSize, modelManager.isModelDownloaded(m), modelManager.getModelFile(m).length())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型下载管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val readyCount = statuses.count { it.downloaded }
            val totalCount = statuses.size
            val totalSize: Long = statuses.sumOf { it.expectedSize }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (readyCount == totalCount)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "状态: $readyCount/$totalCount 个模型就绪",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "总大小: ${formatSize(totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

    var speedText by remember { mutableStateOf("") }

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = {
            downloading = true
            progress = 0f
            speedText = ""
            scope.launch {
                modelManager.downloadMissingModels { downloaded, total, speedBps ->
                    progress = downloaded.toFloat() / total.toFloat()
                    speedText = formatSpeed(speedBps)
                    refreshStatus()
                }
                downloading = false
                speedText = ""
                refreshStatus()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !downloading && readyCount < totalCount
    ) {
        if (downloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
            Text("下载中 ${(progress * 100).toInt()}%")
        } else if (readyCount == totalCount) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("全部已就绪")
        } else {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("下载全部缺少的模型")
        }
    }

    if (downloading && speedText.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(speedText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }

    if (downloading && progress > 0) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
    }

    Spacer(Modifier.height(12.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = {
                modelManager.deleteModels()
                refreshStatus()
            },
            modifier = Modifier.weight(1f),
            enabled = readyCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("删除全部模型")
        }
    }

    Spacer(Modifier.height(12.dp))
            Text("模型列表", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            statuses.forEach { status ->
                val downloaded = status.downloaded
                val nameMap = mapOf(
                    "detection" to "文字检测 (v5)",
                    "english_recognition" to "英文识别",
                    "korean_recognition" to "韩文识别",
                    "chinese_recognition" to "中文识别",
                    "english_dict" to "英文字典",
                    "korean_dict" to "韩文字典",
                    "chinese_dict" to "中文字典",
                    "english_config" to "英文预处理配置",
                    "korean_config" to "韩文预处理配置",
                    "chinese_config" to "中文预处理配置"
                )
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (downloaded) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (downloaded) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (downloaded) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                nameMap[status.name] ?: status.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${status.fileName}  ${formatSize(status.expectedSize)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }
}

private fun formatSpeed(bps: Long): String {
    if (bps <= 0) return ""
    return when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }
}

private val presetColors = listOf(
    "#FFFFFF" to "白",
    "#000000" to "黑",
    "#FFEB3B" to "黄",
    "#4CAF50" to "绿",
    "#F44336" to "红",
    "#2196F3" to "蓝",
    "#9C27B0" to "紫",
    "#FF9800" to "橙"
)

@Composable
private fun ColorPickerRow(selectedHex: String, onColorSelected: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        presetColors.forEach { (hex, name) ->
            val isSelected = hex == selectedHex
            val color = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier.border(1.dp, Color.Gray.copy(alpha = 0.3f), CircleShape)
                    )
                    .clickable { onColorSelected(hex) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (hex == "#FFFFFF" || hex == "#FFEB3B") Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
