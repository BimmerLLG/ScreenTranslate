# ScreenTranslate — 实时屏幕翻译

通过 Android 无障碍服务（AccessibilityService）实时提取屏幕文字，用多种翻译引擎翻译后，以悬浮气泡的形式把译文显示在原文位置。长按悬浮球可切换到 OCR 模式（PP-OCRv5 / ONNX Runtime），翻译以图像形式呈现的文字。

**简体中文** | [English](README.en.md)

## 功能

- **实时文本提取** — 遍历无障碍节点树，采集可见文字及其屏幕坐标
- **七种翻译引擎**
  - **AI 翻译** — 兼容任意 OpenAI 接口（GPT / DeepSeek / Claude / Ollama / vLLM 等），支持自定义提示词与思考模式
  - **B站 Index-Translate** — 免费公开接口，无需 API Key，三种模型规格
  - **Google 离线翻译** — ML Kit 本地翻译，语言包下载完成后可离线使用
  - **Google / Bing / 百度 / Yandex 网页翻译** — 免费非官方接口，无需 API Key
- **智能文本合并** — 按坐标排序并把相邻行合并成段落，减少请求次数、保留上下文
- **增量翻译** — 线程安全的有界 LRU 缓存（500 条）+ 内容哈希去重，只翻译变化部分
- **悬浮气泡** — 气泡位置贴合原文，可配置字体、颜色、圆角、透明度、阴影与最大行数
- **OCR 模式** — 长按悬浮「译」按钮，通过 MediaProjection 截屏并运行 PP-OCRv5
- **段落翻译** — 拖动悬浮球，把「+」对准段落松手，只翻译该段
- **自动语言检测** — AI 模式下可自动识别源语言，也可手动指定
- **错误提示** — 失败时弹出可读的 Toast，而不是静默无反应
- **常驻保活** — 前台服务防止翻译服务被系统回收

## 下载

[![GitHub Release](https://img.shields.io/github/v/release/BimmerLLG/ScreenTranslate)](https://github.com/BimmerLLG/ScreenTranslate/releases)

从 [GitHub Releases](https://github.com/BimmerLLG/ScreenTranslate/releases) 下载最新 APK。

> 最低支持 Android 8.0（API 26）。首次使用需授予无障碍服务与悬浮窗权限；OCR 功能还需截屏权限。

## 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3（动态取色） |
| 离线翻译 | Google ML Kit Translate |
| 在线翻译 | OkHttp + Gson（OpenAI 兼容及网页接口） |
| OCR | ONNX Runtime + PP-OCRv5 |
| 异步 | Kotlin Coroutines |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 14 (API 34) |

## 快速开始

### 从源码构建

```bash
git clone https://github.com/BimmerLLG/ScreenTranslate.git
cd ScreenTranslate
./gradlew assembleDebug
```

或直接用 Android Studio 打开项目运行。

### 使用

1. **选择引擎** — 设置 → 翻译引擎。B站、网页与离线引擎无需配置；AI 模式需填写 Endpoint、Key 和模型名
2. **授予权限**
   - 无障碍服务：系统设置 → 无障碍 → 屏幕翻译
   - 悬浮窗权限：允许在其他应用上层显示
   - 通知权限：保活服务需要
   - 截屏权限：在主页点击「开启截屏权限」（仅 OCR 需要）
3. **启动** — 在主页点击启动，然后切换到任意应用
4. **翻译** — 屏幕上的文字会自动翻译
5. **OCR** — 长按悬浮「译」按钮翻译整屏，或拖动到「+」松手翻译单个段落

### OCR 模型

- 模型在首次使用时下载（约 130MB），进度与失败情况都会在应用内提示
- 也可用 `download_models.bat`（Windows）或 `download_models.sh`（Linux/macOS）预下载到 `app/src/main/assets/ocr_models/`

## 配置说明

### AI 翻译

兼容任意 OpenAI 接口，包括 OpenAI GPT、DeepSeek、Claude（需兼容代理）以及本地部署的 Ollama / vLLM / LM Studio 等。

```
Endpoint: https://api.openai.com/v1
Model:    gpt-4o-mini
API Key:  sk-xxxxxx
```

### Google 离线翻译

无需配置。首次使用某种语言时会自动下载语言包（推荐 Wi-Fi 环境）。

支持语言：中文、英语、日语、韩语、法语、德语、俄语、西班牙语、意大利语、葡萄牙语、泰语、越南语、阿拉伯语。

### B站 Index-Translate

在设置中选择「B站 Index-Translate」即可，无需填写 Endpoint 或 Key。可选模型：

- `Index-Translate-35B-A3B`（推荐，质量最好）
- `Index-Translate-9B`
- `Index-Translate-2B`（最快）

客户端始终注入 `chat_template_kwargs: {"enable_thinking": false}` —— 这是该接口的必需参数，缺失时模型会返回思维链而不是译文。请求以流式方式发送，以避开免费网关的超时限制。

## 项目结构

```
app/src/main/java/com/screentranslate/
├── App.kt                          Application 入口
├── collector/                      屏幕文本采集
│   ├── AccessibilityCollector.kt   BFS 遍历无障碍节点树
│   ├── TextNode.kt                 文本节点（文本 + 坐标）
│   └── TextSorter.kt               坐标排序、段落合并、按点查找
├── translate/                      翻译引擎
│   ├── Translator.kt               翻译接口
│   ├── AiTranslator.kt             OpenAI 兼容接口
│   ├── BiliIndexTranslator.kt      B站 Index-Translate
│   ├── GoogleTranslator.kt         ML Kit 离线翻译
│   ├── GoogleWebTranslator.kt      Google 网页
│   ├── BingWebTranslator.kt        Bing 网页
│   ├── BaiduWebTranslator.kt       百度网页
│   ├── YandexWebTranslator.kt      Yandex 网页
│   └── TranslationManager.kt       调度、缓存、错误上报
├── overlay/
│   └── OverlayManager.kt           悬浮球、段落「+」、译文气泡
├── ocr/                            OCR 模块
│   ├── OcrEngine.kt                ONNX 推理（检测 + 识别）
│   ├── OcrTextCollector.kt         截屏 + OCR 管线
│   ├── ModelManager.kt             模型下载与完整性校验
│   └── ScreenCapture.kt            MediaProjection 截屏
├── service/
│   ├── TranslateAccessibilityService.kt  无障碍服务（核心）
│   └── KeepAliveService.kt               前台保活服务
├── logger/
│   └── L.kt                        内存 + 文件日志
└── ui/
    ├── MainActivity.kt             权限引导与启停
    ├── SettingsActivity.kt         引擎 / 遮罩 / 悬浮窗 / 日志 / 模型
    └── TestActivity.kt             OCR 测试文本
```

## 构建

```bash
# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK（需先配置签名）
./gradlew assembleRelease
```

debug 产物：`app/build/outputs/apk/debug/app-debug.apk`
release 产物：`app/build/outputs/apk/release/app-release.apk`

> 说明：debug APK 体积较大，因为它打包了所有 ABI 的 ONNX Runtime 与 ML Kit 原生库。若要分发，建议按 ABI 拆分以减小体积。

## 致谢

- [ML Kit](https://developers.google.com/ml-kit) — 离线翻译
- [ONNX Runtime](https://onnxruntime.ai) — OCR 推理引擎
- [PaddleOCR / PP-OCRv5](https://github.com/PaddlePaddle/PaddleOCR) — OCR 模型
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI 框架

## 许可证

[Apache License 2.0](LICENSE)
