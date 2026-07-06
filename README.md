# ScreenTranslate — 实时屏幕翻译

通过 Android 无障碍服务（AccessibilityService）实时提取屏幕文字，调用 AI API 或 Google ML Kit 离线翻译后，以悬浮气泡形式显示译文在原文本位置附近。同时支持 OCR 模式（PP-OCRv5 / ONNX Runtime），长按悬浮球即可触发全屏截图文字识别与翻译。

## 功能

- **实时文本提取** — 利用无障碍服务遍历当前屏幕可见节点，提取文字及其屏幕位置
- **双翻译引擎**
  - **AI 翻译** — 兼容 OpenAI API 格式，支持 GPT / DeepSeek / Claude 等任意大模型，可自定义 Prompt
  - **Google 离线翻译** — 基于 ML Kit，免费离线，无需 API Key，支持 12 种语言
- **智能文本合并** — 按屏幕坐标排序合并相邻文本块，减少 API 请求，保留上下文
- **增量翻译** — 200 条 LRU 缓存 + 内容哈希去重，只翻译变化部分
- **悬浮窗气泡** — 译文气泡浮动在原文本附近，支持自定义字体/颜色/圆角/透明度/阴影/最大行数
- **OCR 模式** — 长按悬浮「译」按钮，通过 MediaProjection 截屏 + PP-OCRv5 识别，翻译屏幕任意图像文字
- **自动语言检测** — AI 模式下自动识别源语言，也可手动指定
- **思考模式** — 支持 DeepSeek 等模型的思考链显示
- **常驻保活** — 前台服务防止进程被系统回收

## 截图

<!-- TODO: 添加截图 -->
| 主界面 | 翻译效果 | 设置 |
|:---:|:---:|:---:|
| | | |

## 下载

[![GitHub Release](https://img.shields.io/github/v/release/yourname/ScreenTranslate)](https://github.com/yourname/ScreenTranslate/releases)

从 [GitHub Releases](https://github.com/yourname/ScreenTranslate/releases) 下载最新 APK。

> 首次使用需先授予无障碍服务 + 悬浮窗权限。

## 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 离线翻译 | Google ML Kit Translate |
| AI 翻译 | OkHttp + Gson（OpenAI 兼容 API） |
| OCR | ONNX Runtime + PP-OCRv5 |
| 异步 | Kotlin Coroutines |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 14 (API 34) |

## 快速开始

### 从源码构建

1. 克隆仓库
2. 用 Android Studio 打开项目，等待 Gradle 同步完成
3. 连接设备或启动模拟器，点击 Run

### 使用

1. **配置 AI 翻译**（可选） — 打开设置，填入 API Endpoint、Key、模型名
2. **授予权限**
   - 无障碍服务：系统设置 → 无障碍 → 屏幕翻译
   - 悬浮窗权限：允许在其他应用上层显示
   - 通知权限：保活服务需要
3. **启动服务** — 回到主界面点击「启动」
4. **切换到任意 App** — 实时翻译效果立现
5. **长按悬浮「译」按钮** — 触发 OCR 全屏翻译

### OCR 模型

- OCR 模型默认在首次使用时从 HuggingFace 自动下载（约 130MB）
- 也可运行 `download_models.bat`（Windows）或 `download_models.sh`（Linux/macOS）预下载到 `app/src/main/assets/ocr_models/`

## 配置说明

### AI 翻译

支持的 OpenAI 兼容 API，包括但不限于：
- OpenAI GPT-4 / GPT-3.5
- DeepSeek
- Claude（需兼容代理）
- 本地部署的 Ollama / vLLM / LM Studio 等

推荐配置：
```
Endpoint: https://api.openai.com/v1
Model: gpt-4o-mini
API Key: sk-xxxxxx
```

### Google 离线翻译

无需配置，首次使用特定语言时会自动下载语言包（推荐 Wi-Fi 环境）。

支持语言：中文、英语、日语、韩语、法语、德语、俄语、西班牙语、意大利语、葡萄牙语、泰语、越南语、阿拉伯语

## 项目结构

```
app/src/main/java/com/screentranslate/
├── App.kt                          # Application 入口
├── collector/                      # 屏幕文本采集
│   ├── AccessibilityCollector.kt   # BFS 遍历无障碍节点树
│   ├── TextNode.kt                 # 文本节点数据类（文本 + 位置）
│   └── TextSorter.kt               # 按坐标排序 & 合并相邻文本
├── translate/                      # 翻译引擎
│   ├── Translator.kt               # 翻译接口
│   ├── AiTranslator.kt             # AI API 翻译（OpenAI 兼容）
│   ├── GoogleTranslator.kt         # ML Kit 离线翻译
│   └── TranslationManager.kt       # 统筹调度 & LRU 缓存
├── overlay/
│   └── OverlayManager.kt           # 悬浮窗管理（指示器 + 气泡）
├── ocr/                            # OCR 模块
│   ├── OcrEngine.kt                # ONNX 推理（检测 + 识别）
│   ├── OcrTextCollector.kt         # 截图 + OCR 管线
│   ├── ModelManager.kt             # OCR 模型下载管理
│   └── ScreenCapture.kt            # MediaProjection 截图
├── service/
│   ├── TranslateAccessibilityService.kt  # 无障碍服务（核心）
│   └── KeepAliveService.kt               # 保活前台服务
├── logger/
│   └── L.kt                        # 日志工具
└── ui/
    ├── MainActivity.kt             # 主界面（权限引导 + 开关）
    ├── SettingsActivity.kt         # 设置界面
    ├── LogActivity.kt              # 日志查看器
    └── TestActivity.kt             # OCR 测试页
```

## 构建

```bash
# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK
./gradlew assembleRelease
```

Release APK 位于 `app/build/outputs/apk/release/app-release.apk`。

## 致谢

- [ML Kit](https://developers.google.com/ml-kit) — 离线翻译
- [ONNX Runtime](https://onnxruntime.ai) — OCR 推理引擎
- [PaddleOCR / PP-OCRv5](https://github.com/PaddlePaddle/PaddleOCR) — OCR 模型
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI 框架

## 许可证

[Apache License 2.0](LICENSE)
