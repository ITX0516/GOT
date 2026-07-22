# KataGo 引擎启动失败排查与交接文档

## 一、项目概述

**项目名称**: GOT (Go AI Teacher)
**技术栈**: 原生 Android Kotlin + 本地 KataGo 引擎 + LLM AI 讲解
**项目地址**: https://github.com/ITX0516/GOT

### 功能目标
- 本地 KataGo 引擎对弈（无需联网）
- AI 分析（胜率、推荐选点）
- LLM 多模态讲解（需配置 API Key）
- 纯本地运行，不依赖后端服务器

---

## 二、项目结构

```
GOT/
├── app/src/main/
│   ├── assets/katago/              # 引擎资源文件
│   │   ├── 10b.bin                 # 10b 完整模型 (assets 中存 .bin，释放后改名为 .bin.gz)
│   │   ├── 20b_head.bin            # 20b 权重头文件 (释放后改名为 20b.bin.gz)
│   │   ├── 20b.tflite              # 20b TFLite 模型主体
│   │   ├── gtp.cfg                 # 简化配置文件（纯 CPU，无 OpenCL）
│   │   └── gtp_static.cfg          # BadukAI 原始配置文件（含 OpenCL 参数）
│   ├── java/com/goai/
│   │   ├── engine/
│   │   │   ├── GoEngine.kt         # 引擎接口定义
│   │   │   ├── GTPClient.kt        # GTP 协议客户端（子进程通信）
│   │   │   ├── LocalKataGoEngine.kt # KataGo 引擎封装（核心启动逻辑）
│   │   │   └── AssetExtractor.kt   # assets 资源释放工具
│   │   ├── ui/
│   │   │   ├── play/PlayActivity.kt # 对弈页面（引擎初始化入口）
│   │   │   ├── board/GoBoardView.kt # 棋盘自定义 View
│   │   │   └── settings/...
│   │   ├── ai/LLMService.kt
│   │   ├── model/...
│   │   └── data/AppPreferences.kt
│   ├── jniLibs/arm64-v8a/          # 原生库
│   │   ├── libkatago.so            # KataGo 主程序（含 SNPE/QNN 支持）
│   │   ├── libkatago_nosnpe.so     # KataGo 纯 CPU 版（无 SNPE 依赖）
│   │   ├── libtensorflowlite.so    # TFLite 运行时
│   │   ├── libQnn*.so              # QNN 相关库（共13个）
│   │   └── libc++_shared.so        # C++ 运行时
│   └── AndroidManifest.xml
├── .github/workflows/android-build.yml  # GitHub Actions 自动构建
├── README.md
└── ENGINE_TROUBLESHOOTING.md       # 本文档
```

---

## 三、引擎资源说明（从 BadukAI v1.23 提取）

### 模型文件

| 文件 | assets 中名称 | 释放后名称 | 大小 | 用途 |
|------|-------------|-----------|------|------|
| 10b 完整模型 | `10b.bin` | `10b.bin.gz` | ~11MB | 纯 CPU 模式可直接运行 |
| 20b 权重头 | `20b_head.bin` | `20b.bin.gz` | ~249KB | 仅权重头，需配合 TFLite 使用 |
| 20b TFLite 主体 | `20b.tflite` | `20b.tflite` | ~25MB | TFLite 模型主体 |

> ⚠️ **重要**: `20b.bin.gz` 只是权重头，不能单独使用！纯 CPU 模式必须用 `10b.bin.gz`。

### KataGo 二进制

| 文件 | 说明 |
|------|------|
| `libkatago.so` | 完整版，含 SNPE/QNN GPU/NPU 加速支持 |
| `libkatago_nosnpe.so` | 纯 CPU 版，无 SNPE 依赖，兼容性最好 |

### 配置文件

| 文件 | 说明 |
|------|------|
| `gtp.cfg` | 简化配置，移除所有 OpenCL 参数，纯 CPU 兼容 |
| `gtp_static.cfg` | BadukAI 原始配置，含 OpenCL 参数（可能导致初始化失败） |

---

## 四、历史问题与修复记录

### 问题 1: assets 中 .gz 文件无法打开
- **现象**: `AssetManager.openFd()` 抛出 FileNotFoundException
- **原因**: AAPT 对 .gz 扩展名有特殊处理
- **修复**: assets 中存为 `.bin`，释放到内部存储时改回 `.bin.gz`；build.gradle 添加 `noCompress("gz")`

### 问题 2: 配置文件解析错误
- **现象**: 引擎启动后立刻退出
- **原因**: 配置文件缺少必填项或包含不兼容参数
- **修复**: 简化配置，移除 OpenCL 相关参数

### 问题 3: 模型文件无法识别
- **现象**: KataGo 找不到模型
- **原因**: 释放后的文件没有 `.gz` 扩展名，KataGo 无法自动解压
- **修复**: 释放时确保文件名为 `*.bin.gz`

### 问题 4: 签名不一致
- **现象**: 无法覆盖安装
- **修复**: debug 构建也使用 release 签名（如果存在）

### 问题 5: 纯 CPU 模式误用 20b_head 模型
- **现象**: 所有模式都启动失败，退出码 0，无任何输出
- **原因**: 代码中所有模式都传 `20b.bin.gz`（只有权重头），纯 CPU 模式应该用 `10b.bin.gz` 完整模型
- **修复**: LocalKataGoEngine 支持两套模型，纯 CPU 模式用 10b

---

## 五、当前问题：引擎启动后立刻退出（退出码 0）

### 现象
- 进程启动后立刻退出，退出码为 0
- STDOUT 为空
- STDERR 为空
- logcat 中也没有明显报错

### 已尝试的启动模式（全部失败）

1. ❌ nosnpe + 10b (纯CPU)
2. ❌ nosnpe + 20b + tflite
3. ❌ dlc + 20b + tflite
4. ❌ dlc + 10b (纯CPU)

---

## 六、详细排查思路（下一位接手请按此顺序）

### 优先级 1: 检查动态库依赖（最可能的原因）

**为什么可能是这个原因**:
- Android 上 native 程序最常见的问题就是缺 .so 库
- 退出码 0 且无任何输出，可能是 linker 在加载阶段就失败了
- libkatago.so 可能依赖系统中不存在的库

**排查方法**:

```bash
# 方法 1: 在 adb shell 中直接运行，看 linker 报错
adb shell
cd /data/app/~~xxx/com.goai-xxx/lib/arm64
export LD_LIBRARY_PATH=/data/app/~~xxx/com.goai-xxx/lib/arm64:/vendor/lib64:/system/vendor/lib64
./libkatago_nosnpe.so gtp -model /data/user/0/com.goai/files/katago/10b.bin.gz -config /data/user/0/com.goai/files/katago/gtp.cfg

# 方法 2: 用 readelf 查看依赖
readelf -d libkatago_nosnpe.so | grep NEEDED

# 方法 3: 查看 logcat 中 linker 的报错
adb logcat -s linker:* AndroidRuntime:* DEBUG:*
# 搜索关键词: dlopen, CANNOT LINK, library, not found
```

**可能缺少的库**:
- libz.so (zlib，解压 .gz 模型需要)
- liblog.so (Android 日志库)
- libm.so (数学库)
- 其他系统库

**如果确认为缺库**:
- 编译静态链接版 KataGo
- 或把缺少的 .so 一起打包到 jniLibs

---

### 优先级 2: 验证 KataGo 二进制是否可执行

**排查方法**:

```bash
adb shell
# 检查文件权限
ls -la /data/app/~~xxx/com.goai-xxx/lib/arm64/libkatago_nosnpe.so

# 检查是否是有效的 ELF 文件
file libkatago_nosnpe.so

# 检查架构是否匹配
readelf -h libkatago_nosnpe.so | grep Machine
# 应该是 AArch64
```

---

### 优先级 3: 配置文件问题

**当前 gtp.cfg 内容**（已简化）:
```
logAllGTPCommunication = true
logSearchInfo = true
logToStderr = true
...
```

**排查方法**:
1. 尝试不带 `-config` 参数启动，看是否能运行
2. 尝试最简配置（只保留 logToStderr）
3. 确认 KataGo 版本与配置参数兼容

```bash
# 测试最简启动
./libkatago_nosnpe.so gtp -model /path/to/10b.bin.gz
```

---

### 优先级 4: 模型文件验证

**排查方法**:
1. 检查文件完整性（MD5 校验）
2. 确认 .gz 文件确实是 gzip 格式
3. 尝试解压后使用

```bash
# 检查文件类型
file 10b.bin.gz
# 应该显示: gzip compressed data

# 测试解压
gzip -t 10b.bin.gz
echo $?  # 0 表示正常
```

---

### 优先级 5: GLIBC/系统库版本兼容性

**为什么可能**:
- BadukAI 的 KataGo 可能是在较新的 NDK 环境编译的
- 旧版 Android 系统的 libc 可能不兼容
- 可以用 `readelf -V` 查看版本需求

---

### 优先级 6: 对比 BadukAI 是怎么启动的

**建议**: 反编译 BadukAI APK，看它的启动逻辑

1. 启动参数到底是什么？
2. 环境变量怎么设置的？
3. 工作目录是哪里？
4. 配置文件是动态生成的还是静态的？
5. TFLite 模型参数名到底是 `-tflite-model` 还是别的？

> 💡 **提示**: BadukAI 是开源的吗？如果是，直接看源码最准确。
> 地址: https://github.com/aki65/aki65.github.io

---

## 七、关键代码文件说明

### LocalKataGoEngine.kt
- **位置**: `app/src/main/java/com/goai/engine/LocalKataGoEngine.kt`
- **作用**: KataGo 引擎的主封装类
- **关键方法**:
  - `init()`: 初始化，按顺序尝试多种启动模式
  - `tryStartGtp()`: 单次启动尝试
- **当前启动顺序**:
  1. nosnpe + 10b (纯CPU) — 最优先，兼容性最好
  2. nosnpe + 20b + tflite
  3. dlc + 20b + tflite
  4. dlc + 10b (纯CPU) — 兜底

### GTPClient.kt
- **位置**: `app/src/main/java/com/goai/engine/GTPClient.kt`
- **作用**: GTP 协议客户端，通过子进程 stdin/stdout 通信
- **注意**: start() 方法不再主动检查进程退出，交给上层控制

### AssetExtractor.kt
- **位置**: `app/src/main/java/com/goai/engine/AssetExtractor.kt`
- **作用**: 把 assets 中的模型和配置释放到内部存储
- **关键逻辑**: 用版本号判断是否需要重新释放

### PlayActivity.kt
- **位置**: `app/src/main/java/com/goai/ui/play/PlayActivity.kt`
- **作用**: 对弈页面，引擎初始化入口
- **错误日志位置**: `/sdcard/Android/data/com.goai/files/engine_error.log`

---

## 八、日志收集机制

### 错误日志文件
- **路径**: `/sdcard/Android/data/com.goai/files/engine_error.log`
- **内容**:
  - 调试信息（文件路径、存在性、大小）
  - 每种启动模式的尝试日志
  - STDERR 输出
  - STDOUT 输出
  - logcat 过滤后的相关日志

### logcat 过滤关键词
- libkatago, KataGo, katago
- tflite, TFLite
- QNN, qnn
- FATAL, ERROR, Fatal
- Signal, signal
- libc, DEBUG
- crash, Crash
- dlopen, linker, Linker, CANNOT LINK

---

## 九、快速验证脚本（建议在 adb shell 中手动测试）

```bash
# 1. 进入应用 lib 目录
cd /data/app/~~*/com.goai-*/lib/arm64

# 2. 设置环境变量
export LD_LIBRARY_PATH=$(pwd):/vendor/lib64:/system/vendor/lib64

# 3. 测试最简启动（纯 CPU + 10b 模型）
./libkatago_nosnpe.so gtp -model /data/user/0/com.goai/files/katago/10b.bin.gz

# 4. 如果上面失败，加上配置文件试试
./libkatago_nosnpe.so gtp \
  -model /data/user/0/com.goai/files/katago/10b.bin.gz \
  -config /data/user/0/com.goai/files/katago/gtp.cfg

# 5. 同时开另一个窗口看 logcat
adb logcat -s linker:* AndroidRuntime:* DEBUG:* *:F *:E
```

---

## 十、最坏情况的备选方案

如果 KataGo 实在跑不起来，可以考虑：

1. **换用更轻量的引擎**: 比如 GnuGo、Fuego 等纯 CPU 引擎
2. **用 LLM 直接下棋**: 不依赖本地引擎，完全用 LLM 生成走法（但棋力有限）
3. **云端引擎**: 搭一个后端服务器跑 KataGo，手机端通过网络调用
4. **重新编译 KataGo**: 从源码编译，确保兼容性（工作量大）

---

## 十一、Git 提交历史（关键节点）

```
69e78b8 关键修复: 纯CPU模式用10b完整模型而非20b_head
db17ee8 修复引擎启动失败: 添加-tflite-model参数+移除OpenCL配置
da2b726 完全照抄BadukAI启动逻辑: nosnpe二进制+logcat捕获+动态gtp.cfg
f4740e0 完全照抄BadukAI启动方式: 添加20b DLC模型+环境变量
678e570 添加项目README
f36c9d1 修复assets中.gz文件AAPT处理问题
5dc4dc0 使用BadukAI原始10b.bin.gz模型和gtp_static.cfg配置
```

---

**祝好运！** 🍀
