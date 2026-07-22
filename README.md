# GOT — 围棋 AI 老师

一款原生 Android 围棋应用，集成本地 KataGo 引擎 + LLM 智能讲解，无需电脑后端，手机即可实现 AI 对弈与局面分析。

## ✨ 功能特性

### 🏁 棋盘与对弈
- **原生 Canvas 渲染** — 木色棋盘、径向渐变棋子、阴影与高光，参考腾讯围棋视觉风格
- **三种棋盘尺寸** — 19×19 / 13×13 / 9×9，自动适配星位
- **触摸落子** — 自动吸附最近交叉点，最后一手红点标记
- **坐标标注** — 标准 GTP 坐标（A-T 跳过 I，行号 1-19）

### 🤖 AI 引擎
- **本地 KataGo 引擎** — ARM64 原生编译，TFLite + QNN 后端，支持手机 NPU 加速
- **GTP 协议通信** — 标准 GTP 接口，可切换云端引擎
- **实时胜率分析** — 候选走法、最佳推荐、胜率热力图叠加
- **引擎模式切换** — 本地 / 云端 / 无引擎，按需选择

### 🧠 LLM 智能讲解
- **手机端直连** — 无需自建后端，输入 API Key 即可使用
- **多模态支持** — 支持棋盘截图 + 局面文字描述双输入
- **预设服务商** — 通义千问 / 智谱 GLM / OpenAI / 自定义
- **OpenAI 兼容接口** — 任何兼容 `/chat/completions` 格式的服务都能接入

### ⚙️ 工程质量
- **Kotlin + Material 3** — 原生 Android 开发，流畅高效
- **GitHub Actions 自动构建** — 每次提交自动打包 ARM64 APK
- **Release 签名** — Debug / Release 统一签名，可覆盖安装
- **Lint 检查** — 代码质量静态分析
- **ABI 过滤** — 仅 ARM64-v8a，减小 APK 体积

## 🏗️ 项目架构

```
app/src/main/java/com/goai/
├── MainActivity.kt              # 主入口
├── ai/
│   └── LLMService.kt            # LLM 讲解服务（OpenAI 兼容接口）
├── data/
│   └── AppPreferences.kt        # 应用偏好存储（引擎/LLM 配置）
├── engine/
│   ├── GoEngine.kt              # 引擎接口抽象
│   ├── GTPClient.kt             # GTP 协议客户端（子进程通信）
│   ├── LocalKataGoEngine.kt     # 本地 KataGo 引擎实现
│   └── AssetExtractor.kt        # Assets 资源释放（模型/配置）
├── model/
│   ├── Stone.kt                 # 棋子枚举
│   ├── Move.kt                  # 走法数据类
│   ├── GameState.kt             # 游戏状态（棋盘/轮流/提子）
│   └── AnalysisData.kt          # AI 分析结果数据类
└── ui/
    ├── board/
    │   └── GoBoardView.kt       # 自定义棋盘 View（Canvas 渲染）
    ├── play/
    │   └── PlayActivity.kt      # 对弈页面
    └── settings/
        └── SettingsActivity.kt  # 设置页面
```

## 📦 本地引擎资源

KataGo 引擎相关资源位于 `app/src/main/assets/katago/` 和 `app/src/main/jniLibs/arm64-v8a/`：

| 文件 | 说明 |
|------|------|
| `10b.bin` | KataGo 10 层神经网络模型（gzip 压缩，释放后重命名为 `.bin.gz`） |
| `gtp_static.cfg` | KataGo 配置文件（搜索线程、贴目、分析报告等） |
| `libkatago.so` | KataGo 引擎可执行文件（以 .so 形式打包，通过子进程调用） |
| `libtensorflowlite.so` | TensorFlow Lite 运行时 |
| `libQnn*.so` | Qualcomm QNN SDK 库（NPU 加速，覆盖骁龙 660~8 Gen3） |
| `libc++_shared.so` | C++ 标准库运行时 |

> 模型文件在 assets 中以 `.bin` 扩展名存储，运行时由 `AssetExtractor` 释放到内部存储并重命名为 `10b.bin.gz`，避免 AAPT 对 `.gz` 文件的特殊处理。

## 🚀 快速开始

### 环境要求
- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK 34
- minSdk 26（Android 8.0）

### 构建方式

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（需要签名配置）
./gradlew assembleRelease

# Lint 检查
./gradlew lintDebug
```

### 签名配置

在项目根目录下的 `local.properties` 中添加：

```properties
storeFile=../keystore/release.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

如果 `keystore/release.keystore` 存在，Debug 构建也会使用相同签名，保证可覆盖安装。

## 📱 API Key 配置

1. 打开应用 → 设置
2. 选择 LLM 服务商（通义千问 / 智谱 / OpenAI / 自定义）
3. 填入 API URL、API Key、模型名称
4. 保存后返回对弈页面即可使用 AI 讲解

支持任何 OpenAI 兼容格式的接口（`/chat/completions`）。

## 🔧 开发说明

### 引擎扩展

新增引擎只需实现 `GoEngine` 接口：

```kotlin
interface GoEngine {
    val name: String
    val isReady: Boolean
    suspend fun init(boardSize: Int, komi: Float): Boolean
    suspend fun genMove(color: Stone, gameState: GameState): String
    suspend fun analyze(gameState: GameState): AnalysisData?
    fun close()
}
```

### 棋盘渲染性能优化

`GoBoardView` 遵循以下原则保证 60fps 流畅渲染：
- 所有 `Paint` 对象在 `init` 中创建并缓存，`onDraw` 不分配内存
- 分析点坐标在 `setAnalysis` 中预解析并缓存，避免每帧重复计算
- 使用径向渐变 Shader 模拟棋子光影，无需多层绘制
- 棋盘尺寸变化时重新计算布局参数（`onSizeChanged`）

## 📄 License

本项目仅供学习研究使用。

- KataGo 引擎遵循其原始开源协议
- TFLite / QNN SDK 遵循各自原始协议
- 模型文件遵循其原始发布协议
