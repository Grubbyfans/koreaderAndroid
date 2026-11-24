# 第三方 TTS 语音引擎接入调研与复盘

> 项目：阅舟 Reader（`com.yuezhou.reader`）
> 日期：2026-11-22
> 结论：**已撤回全部改动，暂用三星默认引擎**。本文记录整个尝试过程、遇到的技术障碍与最终取舍，供后续重新启用时参考。

---

## 一、背景与目标

App 朗读功能基于 Android 原生 `TextToSpeech`（本地 TTS，无网络）。默认跟随系统引擎。

用户反馈：三星手机（Samsung One UI）上朗读音色单调、听起来不像预期。用户希望：

1. 安装第三方引擎（讯飞语记、Google 语音服务）后，在 App 内能**选择引擎**；
2. 切换引擎后能**拿到对应引擎的中文音色**并正常朗读。

为此做了一轮「引擎选择」功能开发，最终因三星 ROM 的系统缺陷未能打通，撤回并退回默认引擎。

---

## 二、功能改动内容（已全部撤回）

为支持引擎选择，本轮改动了 5 个文件：

| 文件 | 改动 | 状态 |
|------|------|------|
| `ReaderActivity.kt` | 引擎下拉选择 UI（`SpeechEnginePreference`）、`installedEngines` 状态、`listEngines` 调用、切换引擎后重启朗读逻辑 | 已还原 |
| `ReaderSettings.kt` | 新增 `speechEngine` 持久化字段 | 已还原 |
| `SpeechPlanStore.kt` | `SpeechConfig` 新增 `enginePackage` 字段 | 已还原 |
| `SpeechService.kt` | `engine.initialize(enginePackage=...)` 显式绑定 | 已还原 |
| `TtsEngine.kt` | `listEngines()` 兜底探测、`createTts()` 辅助函数、引擎诊断日志 | 已还原 |

核心设计思路：

- `TtsEngine.initialize(enginePackage, language)` 支持三参构造 `TextToSpeech(context, listener, enginePackage)` 显式绑定指定引擎；
- `TtsEngine.listEngines()` 枚举可用引擎，并额外探测一批已知引擎包（Google / 讯飞）作为兜底；
- UI 提供「引擎 + 音色」两级选择，切换后重新查询音色并重启朗读。

---

## 三、排查过程与遇到的坑

### 坑 1：三星 ROM 的 `getEngines()` 只返回三星引擎

**现象**：即使 Google TTS（`com.google.android.tts`）已正确安装、语音数据已下载、系统设置里试听正常，App 内 `TextToSpeech.getEngines()` 仍然只返回 `com.samsung.SMT`。

**验证**（`adb shell dumpsys package com.google.android.tts`）：

```
versionName=googletts.google-speech-apk_20261026.00_p0.1007071969
targetSdk=36
android.intent.action.TTS_SERVICE:
    com.google.android.tts/.../service.GoogleTtsService   ← 已正确注册 TTS_SERVICE
```

即：Google TTS **已正确注册 `android.intent.action.TTS_SERVICE`**，但三星 One UI 的 TTS 框架在枚举引擎时会**过滤掉第三方引擎**，只暴露三星 SMT。这是三星 ROM 的已知缺陷，与 App 代码无关。

### 坑 2：`return@TextToSpeech` 标签错误

改动过程中把 `queryVoicesOnce` 内的匿名 `TextToSpeech` 构造提取成了 `createTts()` 辅助函数，导致原来的 `return@TextToSpeech` 标签失效（编译期标签指向错误）。

**修复**：统一改为 `return@createTts`（标签需与 lambda 实际所属的函数名一致）。

### 坑 3：讯飞语记显式绑定返回 `status = -1`

显式三参绑定 `com.iflytek.vflynote`（讯飞语记）时，`OnInitListener` 返回 `ERROR`（`-1`）。

**原因**：讯飞语记作为系统 TTS 引擎不完整（未完整实现 `TextToSpeechService` 绑定协议），无法作为独立引擎被显式绑定。

### 坑 4：兜底探测未生效

在 `listEngines()` 中加入 `KNOWN_ENGINE_PACKAGES` 兜底探测后，日志仍显示引擎列表只有 `com.samsung.SMT`，`com.google.android.tts` 未被补入。

> 注：此问题在本次会话中被用户中途打断，未完成最终根因定位。从 `dumpsys` 已确认 Google TTS 包真实存在且注册了 TTS_SERVICE，推测与 `getPackageInfo` 的可视性（Package Visibility）或包可见性过滤有关，需进一步验证（见「遗留问题」）。

---

## 四、最终决策与当前状态

- **撤回全部引擎选择相关改动**（`git checkout` 还原 5 个文件到 HEAD），工作区干净；
- App 回到「跟随系统默认引擎」的原始行为，朗读面板只剩音色选择（无引擎选项）；
- 已重新打包并 `adb install -r` 安装到手机。

已验收的朗读相关修复（与本轮无关）均保留在 HEAD：

- `b74e2c0` 朗读切后台读完一句就停 → 整章连续入队
- `a9e6d12` 朗读看门狗 → 检测引擎后台静默自动续播
- `5d24dc6` 下载书切后台超时失败 → 前台服务保活
- `dd74e48` 切主题后屏幕边缘残留旧背景色

---

## 五、遗留问题 / 后续可选方向

若未来想重新支持第三方引擎，需先解决以下问题：

1. **兜底探测为何未生效**：确认 `PackageManager.getPackageInfo("com.google.android.tts", 0)` 是否因 Package Visibility（Android 11+ 需在 `AndroidManifest.xml` 声明 `<queries>`）而返回空/抛异常。这是最可能的根因。
2. **显式绑定 Google TTS 是否成功**：本轮未完成验证三参构造绑定 `com.google.android.tts` 的 `status`（0 成功 vs -1 失败），以及能否拿到 `cmn-CN-*` 中文音色。
3. **三星 ROM 绕过方案**：`getEngines()` 过滤缺陷可通过「自维护引擎白名单 + 包可见性声明」绕过，但前提是第 1、2 点打通。

**备选路线（用户曾考虑）**：在 App 内内置离线 TTS 引擎（如 Kokoro-82M），彻底摆脱系统引擎依赖。代价是包体积、模型加载与性能，未进一步评估。

---

## 六、关键结论速览

| 问题 | 结论 |
|------|------|
| 为什么系统里有 Google TTS，App 却选不到？ | 三星 One UI 的 `getEngines()` 过滤了第三方引擎，属 ROM 缺陷 |
| Google TTS 是否注册了 TTS_SERVICE？ | 是（`GoogleTtsService`），但三星仍不枚举 |
| 讯飞语记能否显式绑定？ | 否，返回 `ERROR(-1)`，引擎不完整 |
| 最终决定 | 撤回改动，暂用三星默认引擎 |
