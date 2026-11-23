# 构建与自检说明

## 开发前自检

每次开发前按方案 §15.1 重新自检并记录：OS/架构、JDK、Android SDK 组件、ADB、Android Studio、Node.js、磁盘空间、真机系统版本/USB 调试/TTS 音色。本机快照见方案 §15.1（Windows：2026-11-19 22:14；macOS：2026-11-20 09:38）。

## 依赖仓库

`settings.gradle.kts` 中 Google Maven 使用 `https://dl.google.com/dl/android/maven2`，不用当前网络下不通的 `maven.google.com`。Maven Central 为 `https://repo.maven.apache.org/maven2`。两者于 2026-11-19 实测可访问。

## 版本锁定

`gradle/libs.versions.toml` 集中管理所有版本。当前组合与依据：

- Gradle 9.7.0：支持 JDK 25（本机 Studio 内置 JBR 25.0.4）。
- AGP 9.3.1：支持 API 37；使用内置 Kotlin（`android.builtInKotlin=true`），不应用 `org.jetbrains.kotlin.android`。
- Kotlin 2.4.20；Compose 编译器插件同版本；Compose BOM 2026.09.00。
- compileSdk 37 / targetSdk 36 / minSdk 26（minSdk 是否最终采用 26 仍按方案 §18.2 在真机确认）。

## 构建命令

`app` 有两个 distribution flavor（2026-11-23 起）：

| flavor | applicationId | 显示名 | 用途 |
| --- | --- | --- | --- |
| `production` | `com.yuezhou.reader` | 静读 | 正式包，包名与数据保持原样 |
| `ttsTest` | `com.yuezhou.reader.tts.test` | 静读测试版 | 与正式包并存安装，用于 TTS 改造验证 |

```bash
# 正式版
./gradlew :app:assembleProductionDebug
./gradlew :app:lintProductionDebug

# 测试版（可与正式版同时安装）
./gradlew :app:assembleTtsTestDebug
./gradlew :app:lintTtsTestDebug
```

## 阶段 B 试音（仅 ttsTestDebug）

Kokoro 模型不随 APK 分发。把官方包解压后复制到测试版私有目录：

```bash
PKG=com.yuezhou.reader.tts.test
adb push <解压目录> /sdcard/Android/data/$PKG/files/tts-models/
adb shell "run-as $PKG sh -c 'mkdir -p files/tts-models && cp -r /sdcard/Android/data/$PKG/files/tts-models/* files/tts-models/'"
```

在目标机（S25+ / Android 16）上，应用进程看不到 adb 直接写入外部目录的子目录，必须用 `run-as` 复制到内部 `files/`；试音代码读取 `filesDir/tts-models/<model-id>`。

自动试音：

```bash
adb shell am start -n $PKG/com.yuezhou.reader.tts.KokoroTrialActivity \
  --ez autorun true --es model int8 --es speeds 0.8,1.0,1.2,1.5
adb logcat -s KokoroTrial:I
```

模型标识：`int8` → `kokoro-int8-multi-lang-v1_1`，`fp32` → `kokoro-multi-lang-v1_1`。
WAV 落在 `files/trials/`，可用 `adb exec-out run-as $PKG cat files/trials/<name>.wav > out.wav` 取回。
该 Activity 与 sherpa-onnx AAR 只存在于 `ttsTest` 变体，正式包不含。

注意：

- AGP 不允许 flavor 名以 `test` 开头（大小写不敏感），因此测试 flavor 命名为 `ttsTest`，任务后缀是 `TtsTest`，不能使用 `assembleTestingDebug`。
- 本机 shell 无全局 `java`，需指定 Studio JBR：
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`。
- Gradle 由 Wrapper 管理，本机无需全局安装。`gradle/wrapper/gradle-wrapper.jar` 已入库。
