# 构建与自检说明

## 开发前自检

每次开发前按方案 §15.1 重新自检并记录：OS/架构、JDK、Android SDK 组件、ADB、Android Studio、Node.js、磁盘空间、真机系统版本/USB 调试/TTS 音色。本机快照见方案 §15.1（2026-11-19 22:14）。

## 依赖仓库

`settings.gradle.kts` 中 Google Maven 使用 `https://dl.google.com/dl/android/maven2`，不用当前网络下不通的 `maven.google.com`。Maven Central 为 `https://repo.maven.apache.org/maven2`。两者于 2026-11-19 实测可访问。

## 版本锁定

`gradle/libs.versions.toml` 集中管理所有版本。当前组合与依据：

- Gradle 9.7.0：支持 JDK 25（本机 Studio 内置 JBR 25.0.4）。
- AGP 9.3.1：支持 API 37；使用内置 Kotlin（`android.builtInKotlin=true`），不应用 `org.jetbrains.kotlin.android`。
- Kotlin 2.4.20；Compose 编译器插件同版本；Compose BOM 2026.09.00。
- compileSdk 37 / targetSdk 36 / minSdk 26（minSdk 是否最终采用 26 仍按方案 §18.2 在真机确认）。

## 构建命令

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lintDebug
```

 Gradle 由 Wrapper 管理，本机无需全局安装。`gradle/wrapper/gradle-wrapper.jar` 已入库。
