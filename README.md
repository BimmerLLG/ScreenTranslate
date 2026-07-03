# ScreenTranslate — 实时屏幕翻译

通过 Android 无障碍服务实时提取屏幕文字，调用 AI API 翻译后以浮窗气泡形式显示译文。

## ✨ 功能

- 实时提取当前屏幕所有可见文本节点
- 支持两套翻译引擎：
  - **AI 翻译**（主力）— 兼容 OpenAI API 格式，支持 GPT / DeepSeek / Claude 等
  - **Google 翻译**（免费离线）— 基于 ML Kit，无需网络和 API Key
- 译文以悬浮气泡形式显示在原文本位置附近
- 支持自动检测源语言 / 手动指定语言
- 增量更新 — 只翻译变化的内容，避免重复请求

## 🚀 快速开始

1. **用 Android Studio 打开本项目**
2. **配置 AI 翻译** → 在设置页填入 API Endpoint、Key、模型名
3. **授予权限**：
   - 无障碍服务（系统设置中找到「屏幕翻译」并开启）
   - 悬浮窗权限（显示翻译气泡）
4. **启动服务** → 切换到任意 App，实时翻译效果立现

## 🧱 项目结构

```
com.screentranslate/
├── collector/          # 文本采集
│   ├── AccessibilityCollector  # DFS 遍历无障碍节点树
│   ├── TextNode               # 文本节点数据类
│   └── TextSorter             # 按屏幕坐标排序 & 合并
├── translate/          # 翻译引擎
│   ├── Translator            # 翻译接口
│   ├── AiTranslator          # AI API 翻译（OpenAI 兼容）
│   ├── GoogleTranslator      # ML Kit 离线翻译
│   └── TranslationManager    # 统筹 & LRU 缓存
├── overlay/            # 浮窗渲染
│   └── OverlayManager        # WindowManager 气泡管理
├── service/
│   └── TranslateAccessibilityService  # 无障碍服务（核心）
└── ui/
    ├── MainActivity           # 权限引导 & 开关
    └── SettingsActivity       # API 配置
```

## 📦 依赖

| 用途 | 库 |
|---|---|
| 离线翻译 | ML Kit Translate (`com.google.mlkit:translate`) |
| AI API 调用 | OkHttp + Gson |
| 异步 | Kotlin Coroutines |

## 🔧 自定义

在 `TranslationManager` 中切换翻译模式：

```kotlin
// AI 翻译（默认，效果好）
prefs.edit().putString("translation_mode", "ai").apply()

// Google 离线翻译（免费，无需 API Key）
prefs.edit().putString("translation_mode", "google").apply()
```

## 📝 说明

- 无障碍服务仅在您主动开启后才开始工作
- AI 翻译需要自行提供 API Key（支持 OpenAI / DeepSeek / Claude 等）
- ML Kit 离线翻译首次使用时需下载语言包（推荐 Wi-Fi 环境）
