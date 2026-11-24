# ScreenTranslate — Real-time Screen Translation

An Android app that extracts on-screen text via the AccessibilityService and translates it with a choice of engines, drawing the result as overlay bubbles right on top of the original text. A long-press on the floating button switches to OCR mode (PP-OCRv5 / ONNX Runtime) to capture and translate text rendered as images.

[简体中文](README.md) | **English**

## Features

- **Real-time text extraction** — walks the accessibility node tree and collects visible text with its on-screen bounds
- **Seven translation engines**
  - **AI translation** — any OpenAI-compatible endpoint (GPT / DeepSeek / Claude / Ollama / vLLM …), custom prompt, optional reasoning mode
  - **Bilibili Index-Translate** — free public endpoint, no API key required, three model sizes
  - **Google offline** — ML Kit on-device translation, works without network after the language pack is downloaded
  - **Google / Bing / Baidu / Yandex web** — free unofficial endpoints, no API key required
- **Smart text merging** — sorts blocks by position and merges adjacent lines into paragraphs, cutting request count while keeping context
- **Incremental translation** — thread-safe bounded LRU cache (500 entries) plus content-hash deduplication, so only changed text is translated
- **Overlay bubbles** — bubble placement follows the original text; configurable font, colors, corner radius, opacity, shadow and max lines
- **OCR mode** — long-press the floating “译” button to capture the screen via MediaProjection and run PP-OCRv5
- **Paragraph translation** — drag the floating button, aim the “+” at a paragraph and release to translate just that block
- **Automatic language detection** — AI mode can detect the source language, or you can pin it
- **Error feedback** — failures surface as readable toasts instead of silent no-ops
- **Keep-alive** — a foreground service keeps the translation service alive in the background

## Download

[![GitHub Release](https://img.shields.io/github/v/release/BimmerLLG/ScreenTranslate)](https://github.com/BimmerLLG/ScreenTranslate/releases)

Grab the latest APK from [GitHub Releases](https://github.com/BimmerLLG/ScreenTranslate/releases).

> Minimum Android 8.0 (API 26). Accessibility service and overlay permission are required on first run; screen-capture permission is required for OCR.

## Tech stack

| Area | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 (dynamic color) |
| Offline translation | Google ML Kit Translate |
| Online translation | OkHttp + Gson (OpenAI-compatible and web endpoints) |
| OCR | ONNX Runtime + PP-OCRv5 |
| Async | Kotlin Coroutines |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 14 (API 34) |

## Getting started

### Build from source

```bash
git clone https://github.com/BimmerLLG/ScreenTranslate.git
cd ScreenTranslate
./gradlew assembleDebug
```

Or open the project in Android Studio and run it from there.

### Usage

1. **Pick an engine** — Settings → Translation engine. The Bilibili, web and offline engines need no configuration; AI mode needs an endpoint, key and model.
2. **Grant permissions**
   - Accessibility service: Settings → Accessibility → ScreenTranslate
   - Overlay permission: allow drawing over other apps
   - Notification permission: needed by the keep-alive service
   - Screen capture: tap “Enable screen capture” on the home screen (OCR only)
3. **Start** — tap Start on the home screen, then switch to any app
4. **Translate** — text on screen is translated automatically
5. **OCR** — long-press the floating “译” button to translate the whole screen, or drag and release on the “+” to translate a single paragraph

### OCR models

- Models download on first use (about 130 MB); progress and failures are reported in-app
- You can pre-download them with `download_models.bat` (Windows) or `download_models.sh` (Linux/macOS) into `app/src/main/assets/ocr_models/`

## Configuration

### AI translation

Works with any OpenAI-compatible API, including OpenAI GPT, DeepSeek, Claude (via a compatible proxy) and local servers such as Ollama / vLLM / LM Studio.

```
Endpoint: https://api.openai.com/v1
Model:    gpt-4o-mini
API Key:  sk-xxxxxx
```

### Google offline translation

No configuration needed. Language packs download on first use of each language (Wi-Fi recommended).

Supported languages: Chinese, English, Japanese, Korean, French, German, Russian, Spanish, Italian, Portuguese, Thai, Vietnamese, Arabic.

### Bilibili Index-Translate

Select “Bilibili Index-Translate” in Settings — no endpoint or key required. Available models:

- `Index-Translate-35B-A3B` (recommended, best quality)
- `Index-Translate-9B`
- `Index-Translate-2B` (fastest)

The client always injects `chat_template_kwargs: {"enable_thinking": false}`, which the endpoint requires; without it the model returns a chain-of-thought block instead of the translation. Requests are streamed to stay under the free gateway's timeout.

## Project structure

```
app/src/main/java/com/screentranslate/
├── App.kt                          Application entry point
├── collector/                      On-screen text collection
│   ├── AccessibilityCollector.kt   BFS over the accessibility node tree
│   ├── TextNode.kt                 Text node (text + bounds)
│   └── TextSorter.kt               Position sorting, merging, point lookup
├── translate/                      Translation engines
│   ├── Translator.kt               Translator interface
│   ├── AiTranslator.kt             OpenAI-compatible API
│   ├── BiliIndexTranslator.kt      Bilibili Index-Translate
│   ├── GoogleTranslator.kt         ML Kit offline translation
│   ├── GoogleWebTranslator.kt      Google web
│   ├── BingWebTranslator.kt        Bing web
│   ├── BaiduWebTranslator.kt       Baidu web
│   ├── YandexWebTranslator.kt      Yandex web
│   └── TranslationManager.kt       Scheduling, cache, error reporting
├── overlay/
│   └── OverlayManager.kt           Floating button, paragraph “+”, bubbles
├── ocr/                            OCR module
│   ├── OcrEngine.kt                ONNX inference (detect + recognize)
│   ├── OcrTextCollector.kt         Capture + OCR pipeline
│   ├── ModelManager.kt             Model download and verification
│   └── ScreenCapture.kt            MediaProjection screen capture
├── service/
│   ├── TranslateAccessibilityService.kt  Core accessibility service
│   └── KeepAliveService.kt               Foreground keep-alive service
├── logger/
│   └── L.kt                        In-memory + file logging
└── ui/
    ├── MainActivity.kt             Permission setup and start/stop
    ├── SettingsActivity.kt         Engine / mask / overlay / logs / models
    └── TestActivity.kt             OCR test text
```

## Build

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (configure signing first)
./gradlew assembleRelease
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`
Release output: `app/build/outputs/apk/release/app-release.apk`

> Note: the debug APK is fairly large because it bundles ONNX Runtime and ML Kit native libraries for every ABI. Split by ABI for distribution if size matters.

## Acknowledgements

- [ML Kit](https://developers.google.com/ml-kit) — offline translation
- [ONNX Runtime](https://onnxruntime.ai) — OCR inference
- [PaddleOCR / PP-OCRv5](https://github.com/PaddlePaddle/PaddleOCR) — OCR models
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI framework

## License

[Apache License 2.0](LICENSE)
