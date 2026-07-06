# AI.md - 屏幕翻译项目指南

## 项目简介

**ScreenTranslate** 是一个 Android 实时屏幕翻译应用。它可以：
- 提取屏幕上显示的所有文字
- 通过 AI 接口（如 GPT、DeepSeek）或 Google 离线翻译将文字翻译成目标语言
- 在原文位置显示翻译结果（悬浮气泡）

## 项目结构

```
app/src/main/java/com/screentranslate/
├── App.kt                          # 应用入口（初始化）
├── collector/                      # 文本采集模块
│   ├── AccessibilityCollector.kt   # 屏幕文本采集器
│   ├── TextNode.kt                 # 文本节点数据结构
│   └── TextSorter.kt               # 文本排序与合并
├── logger/                         # 日志模块
│   └── L.kt                        # 日志工具类
├── overlay/                        # 悬浮窗模块
│   └── OverlayManager.kt           # 悬浮窗管理器
├── service/                        # 服务模块
│   └── TranslateAccessibilityService.kt  # 无障碍翻译服务（核心）
├── translate/                      # 翻译引擎模块
│   ├── Translator.kt               # 翻译器接口
│   ├── AiTranslator.kt             # AI API 翻译实现
│   ├── GoogleTranslator.kt         # Google 离线翻译实现
│   └── TranslationManager.kt       # 翻译管理器（缓存+调度）
└── ui/                             # 界面模块
    ├── MainActivity.kt             # 主界面（权限引导+开关）
    ├── SettingsActivity.kt         # 设置界面（API 配置）
    └── LogActivity.kt              # 日志查看界面
```

## 核心工作流程

```
用户点击启动
    ↓
无障碍服务监听屏幕变化
    ↓
检测到新内容 → 采集屏幕文本（BFS 遍历）
    ↓
排序 + 合并相邻文本
    ↓
内容哈希去重检查
    ↓
调用翻译引擎（AI 或 Google）
    ↓
在原文位置显示蓝色翻译气泡
```

## 关键技术点

### 1. 无障碍服务（AccessibilityService）
- **文件**: `service/TranslateAccessibilityService.kt`
- **作用**: 监听屏幕内容变化，提取可见文本
- **关键代码**: `onAccessibilityEvent()` 方法接收事件，`processScreenContent()` 处理内容

### 2. 文本采集（BFS 遍历）
- **文件**: `collector/AccessibilityCollector.kt`
- **作用**: 遍历屏幕上的所有视图节点，提取文本
- **关键算法**: 广度优先搜索（BFS），使用队列实现

### 3. 文本合并
- **文件**: `collector/TextSorter.kt`
- **作用**: 将相邻的文本片段合并，减少翻译次数
- **判断条件**: 垂直距离 < 10px 且有水平重叠

### 4. 翻译引擎
- **接口**: `translate/Translator.kt`
- **AI 实现**: `translate/AiTranslator.kt` - 调用 OpenAI 兼容 API
- **Google 实现**: `translate/GoogleTranslator.kt` - 使用 ML Kit 离线翻译
- **管理器**: `translate/TranslationManager.kt` - LRU 缓存 + 模式切换

### 5. 悬浮窗
- **文件**: `overlay/OverlayManager.kt`
- **作用**: 在其他应用上层显示翻译结果
- **技术**: WindowManager + TYPE_APPLICATION_OVERLAY

### 6. 防抖机制
- **位置**: `TranslateAccessibilityService.kt`
- **作用**: 避免频繁触发翻译
- **策略**: 600ms 防抖 + 200ms 延迟

## 配置说明

### AI 翻译配置（在设置界面填写）
| 配置项 | 说明 | 示例 |
|--------|------|------|
| API Endpoint | AI API 地址 | `https://api.openai.com/v1/chat/completions` |
| API Key | API 密钥 | `sk-xxxxx` |
| Model | 模型名称 | `gpt-4o-mini` / `deepseek-chat` |
| 源语言 | 原文语言（留空=自动检测） | `日语`、`英语` |
| 目标语言 | 翻译成什么语言 | `中文` |

### 支持的 AI 服务
- OpenAI（GPT-4o-mini 等）
- DeepSeek
- Claude
- 任何兼容 OpenAI API 格式的服务

### 支持的语言（Google 离线翻译）
中文、英语、日语、韩语、法语、德语、俄语、西班牙语、意大利语、葡萄牙语、泰语、越南语、阿拉伯语

## 常见问题

### Q: 为什么需要无障碍权限？
A: 无障碍服务可以读取屏幕上的文字内容，这是实现屏幕翻译的核心能力。

### Q: 为什么需要悬浮窗权限？
A: 需要在其他应用上层显示翻译结果的悬浮气泡。

### Q: AI 翻译失败怎么办？
A: 检查：
1. API Key 是否正确
2. API Endpoint 是否正确
3. 网络是否通畅
4. 模型名称是否正确

### Q: 如何切换翻译模式？
A: 在设置界面的"翻译模式"选项中选择 AI 或 Google。

### Q: 翻译结果不准确？
A: 尝试：
1. 设置正确的源语言（而不是留空自动检测）
2. 使用更强大的模型（如 gpt-4o 而不是 gpt-4o-mini）
3. 检查原文是否清晰可读

## 代码修改记录

### [日期] - [修改者/AI名称]
- **修改内容**: 简述改了什么
- **影响范围**: 涉及哪些文件
- **注意事项**: 后续需要注意什么

---

*本文档帮助你理解和维护屏幕翻译项目。如有疑问，请查看代码中的中文注释。*
