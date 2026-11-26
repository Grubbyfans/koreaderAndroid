# Kokoro 离线朗读实验

这里保留旧版的 Android 适配、模型导入、ADB 试音和后台朗读验收代码。该目录在应用的 Gradle 源集之外。测试版和正式版都使用 Android 系统 TTS。

Sherpa-ONNX 1.13.4 AAR 保留在 `speech/libs/`。如果以后恢复实验，请为它单独建立构建变体，接入这里的 Kotlin 源码和 Manifest，并只在该变体声明 Sherpa-ONNX、Commons Compress 1.27.1 和 OkHttp。应用入口通过 `ReaderTtsApplication` 装配实验引擎。模型继续由设备端导入。
