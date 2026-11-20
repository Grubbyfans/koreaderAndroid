# 阅舟 Reader（yuezhou-reader）

独立的 Android 阅读 App，通过百度网盘或 WebDAV 与 KOReader 上的 BaiduPanSync 插件共享 EPUB 书籍、书库列表、封面和阅读进度。

- 技术方案：[Android阅读App_技术方案.md](Android阅读App_技术方案.md)
- 云端对接流程：[docs/cloud-integration.md](docs/cloud-integration.md)（WebDAV / 百度网盘）
- 同步冲突模型：[docs/sync-model.md](docs/sync-model.md)（冲突检测 / 裁决 / 待发布恢复）
- 兼容基线：BaiduPanSync 0.10.8/0.10.9 现行 `schemaVersion=2` 云端协议（见方案 §4）
- 应用名：阅舟；包名：`com.yuezhou.reader`
- 首版范围与实施阶段：见方案 §1.2、§16（P0→P6）

## 工程结构

| 目录 | 职责 |
| --- | --- |
| `app/` | Compose 页面、导航、依赖装配 |
| `core/` | 协议模型、Room、网络、文件、业务服务 |
| `reader/` | WebView、桥接与 ReaderEngine 适配 |
| `speech/` | 本地 TTS、后台服务、媒体控制 |
| `reader-web/` | TypeScript、foliate-js、CFI/XPointer 与测试 |
| `protocol-fixtures/` | 脱敏云端 JSON、位置测试 EPUB 与锚点 |
| `docs/` | 架构、协议、验收、构建说明 |

模块依赖单向：`app → core/reader/speech`，`reader/speech → core`，模块间不互相依赖 Activity。

## 工具链版本（2026-11-19 锁定）

Gradle 9.7.0 · AGP 9.3.1 · Kotlin 2.4.20 · Compose BOM 2026.09.00 · JDK 25（Android Studio 内置 JBR）。
版本集中在 `gradle/libs.versions.toml`，升级时须按官方兼容表整组核对。详见方案 §15.1、§15.2。

## 构建

```bash
./gradlew assembleDebug          # Debug APK
./gradlew test                   # 单元测试
./gradlew :app:assembleDebug
```

SDK 路径由本机 `local.properties` 的 `sdk.dir` 指定（该文件不入库）。

- **Windows**：`GRADLE_USER_HOME` 建议指向 D 盘（如 `D:\Android\gradle-home`）以避免占用 C 盘；`sdk.dir` 通常为 `D:\Android\Sdk`。
- **macOS**：`sdk.dir` 通常为 `/Users/<用户名>/Library/Android/sdk`；`GRADLE_USER_HOME` 默认 `~/.gradle` 即可，无需另设。
