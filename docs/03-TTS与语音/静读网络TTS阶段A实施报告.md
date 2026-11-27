# 静读网络 TTS 阶段 A 实施报告

> 阶段：A（`:nettts` 模块 + 规则管理 + 基础 HTTP 合成 + 独立试听）｜日期：2026-11-27
> 关联方案：[静读网络TTS原版规则移植技术实施方案.md](静读网络TTS原版规则移植技术实施方案.md)（§十七 为本次 8 条实施调整）
> 版本：ttsTest `versionCode 17 / versionName 0.1.16`
>
> **历史阶段报告**：仅记录阶段 A 当时交付，不代表当前完成度；当前实现、已接入能力与待验收项以技术实施方案顶部“当前实施状态”为准。文中的 19/19、版本 17 和离线安装状态均为历史记录。

## 一、结论

阶段 A 目标已完成：规则可导入/编辑/删除/持久化/导出，可用 Legado 原版规则（思必驰 dds.dui.ai 规则集）完成单段合成与独立试听。阶段 A 不接入连续朗读，等待评审。

## 二、代码变更

### 1. 新增 `:nettts` 模块

来源与许可证记录见 [nettts/README.md](../../nettts/README.md)，GPLv3 全文见 [nettts/LEGADO-LICENSE.txt](../../nettts/LEGADO-LICENSE.txt)。

移植（保留原版权头与 `io.legado.app.*` / `com.script.*` 包名）：

| 能力 | 位置 |
| --- | --- |
| 定制 Rhino JS 引擎 + jar | `nettts/src/main/java/com/script/**`、`nettts/libs/htmlunit-core-js-5.3.0-legado.4.jar` |
| URL 规则解析（`@js`/`{{}}`/UrlOption/cookie/限速/重试） | `nettts/.../model/analyzeRule/AnalyzeUrl.kt`、`RuleAnalyzer.kt` |
| JS 辅助方法（约 40 个） | `nettts/.../help/JsExtensions.kt` |
| 规则基类 / 规则实体 | `nettts/.../data/entities/BaseSource.kt`、`HttpTTS.kt` |
| HTTP 与 Cookie | `nettts/.../help/http/**` |
| 共享 jsLib 作用域 | `nettts/.../model/SharedJsScope.kt` |

裁剪：`webView`、`dnsIp`、`serverID`、Glide/Cronet、上传、WebView 分支移除，命中即显式报"暂不支持"。

新增门面：

- `NetworkTtsSynthesizer`：单段合成，有限重试，`Content-Type` 与首字节校验，JS/请求/落盘超时与取消。
- `NetworkTtsAudioCache`：`.tmp` → 校验非空 → 原子发布；目录容量上限 256 MB，按最久未用淘汰。
- `NetworkTtsPlayer`：ExoPlayer 本地文件播放；静音 WAV 媒体项（24 kHz/单声道/16 bit）。`SimpleCache`/流式数据源为预留代码，未接入实际播放。
- `NetworkTtsRuleCodec`：导入保存原始 JSON，导出保留未知字段并以编辑值覆盖，剔除凭证字段。
- `NetworkTtsLog`：日志出口可替换，输出前脱敏。

### 2. 规则管理与入口

入口：**设置 → 语音朗读 → 网络 TTS → 规则管理**

- `app/src/main/java/com/yuezhou/reader/ui/settings/NetworkTtsRulesScreen.kt`：列表、新建、编辑、删除、导入（文本/剪贴板/文件/链接）、导出到剪贴板、单条试听（文本 + speakSpeed + 播放/暂停/停止）。
- `reader/src/main/java/com/yuezhou/reader/reader/networktts/NetworkTtsRuleRepository.kt`：Room 持久化 + 原版 JSON 导入/导出。
- `reader/.../db/NetworkTtsRuleEntity.kt`、`NetworkTtsRuleDao.kt`、`AppDatabase.kt`：新增 `network_tts_rule` 表，数据库 6 → 7 迁移（只建新表，旧数据零改动）。
- `app/.../networktts/NetworkTtsRuntimeBridge.kt`：注入 UA、上下文、缓存目录、日志出口。
- `app/.../networktts/NetworkTtsAudition.kt`：试听控制器，合成与播放分离，停止即取消合成。

## 三、8 条实施调整落实情况

| # | 要求 | 落实 |
| --- | --- | --- |
| 1 | 失败不得静音跳过 | 重试仍失败抛 `SynthesisException`，界面提示并保留位置；无空白音频占位 |
| 2 | rawJson 导出 | 原始 JSON 为底、编辑值覆盖、未知字段保留；`loginInfo/loginHeader/userInfo/cookie/authorization/password...` 剔除 |
| 3 | 超时/取消/脱敏 | JS 死循环经 Rhino 指令观察器中断；请求与逐块下载可取消；日志统一 `sanitize`；不提供 WebView/文件等系统能力 |
| 4 | 队列与缓存 | 队列上限常量沿用 12；落盘目录 256 MB 上限；`SimpleCache` 未接入实际播放 |
| 5 | 段间停顿 | 统一静音 WAV 媒体项，不引入 PCM 逻辑；静音项不推进阅读位置 |
| 6 | 真实规则 | 阶段 A 以本地 Mock + 用户规则集验链路；真实规则端到端验收留阶段 B |
| 7 | 流式回退 | 缓存键含 `ruleHash`，回退下载可复用已合成文件 |
| 8 | 测试与交付 | 仅 JVM 单元测试 + 构建；未操作真机（设备均 offline） |

## 四、验证结果

`:nettts:testDebugUnitTest` **19/19 通过**：

- 本地 Mock HTTP 服务：正常 WAV 落盘；`{{java.encodeURI(speakText)}}` 编码正确；JSON 错误响应失败且无音频产物；空响应失败；缓存命中不重复请求；规则变更缓存失效；`webView` 参数显式报错。
- 超时与取消：JS `while(true){}` 被中断；慢速流下载取消 < 5s 完成，无 `.tmp`/`.audio` 残留。
- 缓存：空载荷拒绝、原子发布、容量淘汰、清空。
- 导入/导出：未知字段保留、凭证剔除、坏 JSON 报错、逐条错误隔离。
- 真实规则集（84 条，`NETTTS_RULES_JSON` 注入）：全部解析成功，导出后可再次解析。

运行方式：

```bash
NETTTS_RULES_JSON=/tmp/nettts_rules.json ./gradlew :nettts:testDebugUnitTest
```

补充实测（2026-11-27）：`curl` 请求思必驰规则 URL 返回 `audio/wav` 200；同一 URL 重复 `voiceId` 参数会返回 400（规则本身只有一个，正常）。

## 五、构建与安装

- APK：`dist/yuezhou-reader-nett-tts-phaseA-ttsTest-debug.apk`（45 MB）
- 体积变化：较上一测试版 +2.93 MB（Rhino 引擎 1.8 MB + Media3 + Gson + 新增代码；未压缩 dex +9.1 MB）
- 安装状态：`adb devices` 两台设备均为 `offline`，未安装，需手动安装

## 六、已知边界与待人工验收

阶段 B/C 处理：

- 登录 UI 与凭证加密存储装配、`loginCheckJs` 复杂流程、`jsLib` 深度兼容。
- 播放队列、跨章预取、流式播放与回退、阅读位置联动、朗读服务接入。
- `webView` / `dnsIp` / `serverID` / hex 类规则。

待人工验收（未验证项）：

1. 真机安装后：设置 → 语音朗读 → 网络 TTS 入口与返回导航。
2. 导入用户提供的 84 条规则，随机取样试听，确认音色与文本正确。
3. 断网/错误规则时的提示文案与位置保留行为。
4. 连续多次试听、页面退出后的播放器释放（无串音、无残留通知）。
