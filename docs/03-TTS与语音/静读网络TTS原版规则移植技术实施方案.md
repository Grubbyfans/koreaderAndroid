# 静读网络 TTS 原版规则移植技术实施方案

> 初始阶段：0（源码调研与可行性验证）｜日期：2026-11-27；当前实施状态见下方补充
> 基准源码：用户提供的 `legado-master.zip`（Legado 原版，非 MD3 分支）
> 目标：确认"Legado 原版可用的网络朗读规则，免修改在静读导入、试听、连续朗读"的可行路径、最小依赖范围与风险
> 结论先行：**技术可行；采用 R3（仅自用、直接复用 GPLv3 源码）与 Media3 ExoPlayer 独立播放链路；基础规则样本已有，真实 JS/鉴权规则及连续朗读效果仍待人工验收**
>
> **当前实施状态（2026-11-27 更新）**
> - 已实现规则导入/管理/试听、正式朗读后端、JS 超时与取消、loginUi v1/v2、登录脚本入口、凭证与 Cookie 加密持久化、缓存使用中保护及会话代次隔离；代码存在不代表功能验收通过。
> - 当前正式播放使用本地音频文件 + `DefaultDataSource`；`SimpleCache`、Downloader、边下边播和跨章预取**未接入实际播放链路**，仍属于后续计划。
> - 已有 84 条思必驰基础样本及音频响应记录；含复杂 JS/登录的真实规则样本与登录信息不足，不能标记为“Legado 原版规则完整兼容”。
> - 测试与交付以工作区 `AGENTS.md` 为准：不主动执行设备/自动化功能测试；构建独立测试版 APK，ADB 可用时直接安装；功能统一待人工验收。
>
> **已确认决策（2026-11-27）**
> 1. 许可证路线 **R3**：仅个人使用、不对外分发，允许直接复用 Legado 原版 GPLv3 网络 TTS 相关源码，优先开发效率与原版兼容性。
> 2. 音频路线 **B**：原计划复用 Media3 ExoPlayer + SimpleCache + Downloader；当前实际为 ExoPlayer 本地文件播放，不强制解码为 PCM，不改动原生 TTS 与 Kokoro 播放实现。

---

## 一、结论摘要

| 结论 | 说明 |
| --- | --- |
| 执行链路可复刻 | 原版网络朗读 = `AnalyzeUrl`（规则→URL/请求）→ okhttp 请求 → 音频字节流 → ExoPlayer 播放；链路清晰，约 5 个核心类 |
| JS 引擎不可替代 | 原版用定制 Rhino 分支 `org.htmlunit:htmlunit-core-js:5.3.0-legado.4`，含 Legado 自定义特性（Java Map 索引访问、`this.source` 语义、动态 eval 域），普通 Rhino 无法兼容 |
| 规则解析不能"简化版" | 代表性规则依赖 `@js`、`{{js}}`、URL 内嵌 UrlOption JSON、`jsLib` 全局函数、`loginUrl/loginCheckJs` 鉴权、`source.getLoginInfoMap()`，必须按原版语义实现 |
| 静读侧接入成本低 | 静读已有 `SpeechPlaybackBackend` 端口与 `SpeechService` 统一控制（会话代次/进度/跨章/媒体通知）；网络 TTS 以**独立 ExoPlayer 播放链路**实现同一端口，上层控制零重构 |
| 需要新增的重型依赖 | JS 引擎 jar（1.8 MB，MPL2.0/Apache2.0）+ Media3（exoplayer / datasource / datasource-okhttp，+约 1.2–1.6 MB）；规则执行层与播放层直接复用原版源码 |
| 核心阻塞 | ①含复杂 JS/鉴权的真实规则与登录信息不足；②上游 `gedoor/legado` 仓库已清空，只能以本 zip 为固定基线 |

---

## 二、源码基线与取证

### 2.1 基准信息

| 项 | 值 | 取证位置 |
| --- | --- | --- |
| 源码包 | `legado-master.zip`（无 `.git`，无 tag） | 用户上传 |
| 版本锚点 | `versionCodeAtBase = 37540`，基线 commit `9639f027fbc8cfdf57ce136a9e0c98829a235aef` | `app/build.gradle` |
| 应用 id / SDK | `com.legado.app`，minSdk 23 / targetSdk 36 | `app/build.gradle` |
| 工具链 | Kotlin 2.4.20 / AGP 8.13.2 / okhttp 5.4.0 / media3 1.10.1 / htmlunitCoreJs 5.3.0-legado.4 | `gradle/libs.versions.toml` |
| 上游仓库状态 | **`github.com/gedoor/legado` 已于 2026-11-27 检查时清空为公告页**（`git clone` 后仅 `README.md` + 公告图，无代码、无 tag） | 现场验证 |

> 上游清空意味着：后续版本追踪只能以本次 zip 为唯一基线，无法通过 commit diff 追平原版更新；也意味着"以原版为标准"的兼容目标被固定在本次快照上。

### 2.2 网络朗读相关核心文件（原版路径）

| 职责 | 文件 | 行数 |
| --- | --- | --- |
| 规则数据模型 | `app/src/main/java/io/legado/app/data/entities/HttpTTS.kt` | 107 |
| 规则基类（header/登录/变量） | `.../data/entities/BaseSource.kt` | 400+ |
| 规则解析（URL/UrlOption/JS） | `.../model/analyzeRule/AnalyzeUrl.kt` | 1052 |
| JS 运行时（fork 封装） | `modules/rhino/src/main/java/com/script/rhino/*` | 1589 |
| JS 辅助方法集 | `.../help/JsExtensions.kt` | 1389 |
| 朗读服务（请求+队列+缓存） | `.../service/HttpReadAloudService.kt` | 1196 |
| 音频文件缓存 | `.../service/HttpTtsFileCache.kt` | 54 |
| 流式下载/取消工具 | `.../service/HttpTtsDownload.kt` | 54 |
| 并发限速 | `.../help/ConcurrentRateLimiter.kt` | ~110 |
| 导入（单条/数组/URL/文件） | `.../ui/association/ImportHttpTtsViewModel.kt` | 126 |
| 规则编辑（字段全集） | `.../ui/book/read/config/HttpTtsEditDialog.kt` | ~200 |
| 内置规则样本 | `app/src/main/assets/defaultData/httpTTS.json` | 3 条 |

---

## 三、原版网络 TTS 实际执行流程

### 3.1 单段合成（`HttpReadAloudService.getSpeakStream`，L758-864）

```text
段落文本 speakText
   ↓
AnalyzeUrl(url规则, speakText, speakSpeed = AppConfig.ttsSpeechRate + 5, source = HttpTTS,
           readTimeout = 300s, coroutineContext)
   ├─ initUrl()
   │    ├─ analyzeJs()        : 整条规则是 "@js:..."/"<js>...</js>" → 执行 JS 得到 URL
   │    ├─ replaceKeyPageJs(): 替换 {{...}} 内嵌 JS 表达式
   │    └─ analyzeUrl()       : 逗号后 JSON = UrlOption(method/headers/body/charset/retry/
   │                           timeout/followRedirects/js/bodyJs/webView/dnsIp)
   │                           → 表单/Query 编码（charset、escape、已编码检测）
   ↓
getResponseAwait()
   ├─ setCookie()             : 源 Cookie + loginHeader.Cookie 合并；可选 cookieJar
   ├─ ConcurrentRateLimiter   : 按 concurrentRate 限速
   ├─ okhttp 请求（UA 默认、header 规则可为 @js 生成）
   ↓
loginCheckJs（可选）: evalJS(checkJs, response) → 返回 Response（可重新 getResponse()、putLoginHeader）
   ↓
contentType 校验: 若为 application/json 或 text/* → 判定失败，抛错并展示响应体；
                  若规则声明了 contentType（正则）→ 不匹配即失败
   ↓
首字节非空校验（checkedAudioStream）→ InputStream
```

失败策略（原版实测于源码）：
- 超时/连接失败累计 >5 次 → 抛出并暂停朗读；
- 其他错误累计 >5 次 → 抛出；单次错误 → 用空白音频占位继续（`R.raw.silent_sound`）；
- 脚本异常（`ScriptException`/`WrappedException`）→ 直接抛出（不吞错）。

### 3.2 播放与队列（同文件）

- 播放器：ExoPlayer + `SimpleCache`（128 MB LRU）+ `CacheDataSource` + `Downloader`（`DefaultDownloaderFactory`）。
- 两种模式：`streamReadAloudAudio=false` 先落盘再入队；`true` 走上游 `InputStreamDataSource` 边下边播。
- 队列上限 `MAX_HTTP_TTS_PLAYER_QUEUE_SIZE = 12`；段落间可插入静音（`pauseDuration`，0–10s，代码生成 WAV）。
- 预取：下一章前 10 段（`NEXT_CHAPTER_TTS_PREFETCH_ITEM_LIMIT = 10`）。
- 取消：`playbackSessionId` 代次 + `downloadCancellable`（协程取消时主动 `Downloader.cancel()`，解决阻塞下载不响应中断）。

### 3.3 缓存键（`buildHttpTtsCacheFileName`，L138-160）

```text
章节标题MD5_16 + MD5( sourceUrl | speechRate | sourceVariable | loginHeader | 文本 )
```

注意：原版缓存键**不含** `jsLib/header/loginCheckJs`，规则改动可能复用旧音频（静读侧需补 `ruleHash`，见 §八）。

---

## 四、可直接复用的核心模块与复用方式

| 原版模块 | 复用方式（R3） | 说明 |
| --- | --- | --- |
| `HttpTTS` 字段与 JSON 解析 | **直接引入源码**，追加 `rawJson` 字段保真导出 | 字段：`name/url/contentType/pauseDuration/concurrentRate/loginUrl/loginUi/loginCheckJs/header/jsLib/enabledCookieJar/lastUpdateTime` |
| `ImportHttpTtsViewModel.importSourceAwait` | **直接引入逻辑**（改为静读 UI 调用） | 判定顺序：JSON 对象 → JSON 数组 → URL → `content:`/`file:` URI → 报错；URL 支持 `#requestWithoutUA` |
| `AnalyzeUrl`（URL 部分） | **直接引入源码**，仅移除 `webView`/`dnsIp`/`serverID`/`glide`/`Cronet`/`BackstageWebView` 分支 | 保留 `@js`、`{{}}`、UrlOption、编码、cookie、限速、retry/timeout；被移除的参数显式报"暂不支持" |
| `JsExtensions` | **直接引入并按依赖裁剪** | 保留见 §六；文件/压缩包/字体替换/浏览器/视频等不引入 |
| `BaseSource.getHeaderMap/getLoginHeader/putLoginHeader/getLoginInfoMap/putLoginInfo/getVariable/putVariable` | **直接引入**（登录信息加密改用静读 `CredentialCrypto`） | `header` 支持 `@js` 生成 JSON |
| 登录 JS 扩展（`SourceLoginJsExtensions`） | 裁剪引入 | 保留 `ajax/ajaxAll/ajaxTestAll/connect/get/post/toast/log/clearTtsCache/getCookie`；浏览器能力不引入 |
| `HttpTtsFileCache` / `HttpTtsDownload` | **直接引入源码** | 临时文件 → 校验非空 → 原子发布；`downloadCancellable` 保证取消生效 |
| `ConcurrentRateLimiter` | **直接引入源码** | 支持 `"1500"`（每 1.5s 一次）与 `"3/1000"`（每 1s 最多 3 次） |
| `HttpReadAloudService` 播放/队列 | **直接引入其播放链路**，包装为 `NetworkTtsPlaybackBackend : SpeechPlaybackBackend` | ExoPlayer + SimpleCache + Downloader + 队列上限/停顿/预取逻辑全部照搬，仅把"翻页/阅读进度"耦合点改为静读事件 |
| `TTSReadAloudService`、`ReadAloud`、`ReadBook` 等阅读服务 | 不复用 | 静读有自己的朗读计划与进度体系；只保留网络朗读必需部分 |

---

## 五、规则解析引擎的最小依赖范围

`AnalyzeUrl` 原版直接依赖（R3 下的处理方式：**能搬就搬，只替换"与阅读业务强耦合"的部分**）：

| 原版依赖 | 处理（R3） |
| --- | --- |
| `com.script.rhino.RhinoScriptEngine` / `buildScriptBindings` / `chainTo` | 直接引入 `modules/rhino` 源码 |
| `okhttp`（`newCallResponse/get/postForm/postJson`） | 直接使用（静读已有 okhttp 5.3.0）；同时引入原版 `help/http/*` 中被引用的小工具 |
| `GSON`/`GSONStrict`（UrlOption 解析）、`json-path`（规则导入） | 直接引入 gson + json-path（避免改造原版解析代码） |
| `hutool`（`PercentCodec/RFC3986/HexUtil`） | 直接引入 `hutool-core`（仅该类用到，约 300 KB）；或局部替换为等价编码工具（阶段 A 实测后决定） |
| `ConcurrentRateLimiter`、`CookieStore/CookieManager` | 直接引入 |
| `BackstageWebView`、`Cronet`、`GlideUrl`、`ExoPlayerHelper` | **不引入**：`webView:true`、`dnsIp`、`serverID` 等参数在解析时显式报"暂不支持"（最小改动点） |
| `AppConfig.userAgent` | 用静读 UA 常量（需与原版 UA 相近，避免被服务端拒绝） |
| `AppLog`、`CacheManager`、`SharedJsScope` | 用薄适配层替代（日志转静读 Log、内存缓存用 ConcurrentHashMap），不改业务语义 |

结论：R3 下**不重写规则解析引擎**，只做"依赖瘦身 + 少量适配"，预计适配代码 300–600 行；被裁掉的仅是与阅读界面/浏览器相关的分支。

---

## 六、JavaScript 执行环境与必要辅助方法

### 6.1 引擎（实测取证）

本次用 JDK + `htmlunit-core-js-5.3.0-legado.4.jar` 做了独立验证（`/tmp/jsprobe/JsProbe.java`）：

| 验证项 | 结果 |
| --- | --- |
| Next 引擎 `jsLib`（3 KB，含模板字符串/箭头函数/对象字面量） | ✅ 执行通过 |
| `{{(speakSpeed + 5) / 10 + 4}}` 表达式（speakSpeed=5） | ✅ 返回 `5.0` |
| ES6 语法 `arr.map(x => \`v${x}\`)` | ✅ `v3,v1,v2` |
| `new JavaImporter(Packages.javax.crypto.Mac)` | ✅ 可用（阿里云 HmacSHA1 签名前置条件） |
| 默认 WrapFactory 下 `info["api"]`（Java Map 索引访问） | ❌ 返回 `undefined` → **必须**启用 Legado 定制特性 |

原版引擎初始化（`RhinoScriptEngine.init`）：

```kotlin
cx.languageVersion = VERSION_ES6
cx.setInterpretedMode(true)
cx.setClassShutter(RhinoClassShutter)
cx.wrapFactory = RhinoWrapFactory
cx.instructionObserverThreshold = 10000   // 每 1 万条指令调 ensureActive() → 协程取消可中断脚本
hasFeature: FEATURE_E4X / FEATURE_ENABLE_JAVA_MAP_ACCESS /
            FEATURE_LEGADO_DYNAMIC_DEFAULT_THIS / FEATURE_LEGADO_DYNAMIC_EVAL_REALM
```

后 3 个为 Legado 对 Rhino 的补丁特性（`this.source` 语义、`(0,eval)` 域、Map 索引），**普通 Rhino/官方 core-js 不具备**，因此引擎只能用该 fork。

### 6.2 绑定变量（`AnalyzeUrl.evalJS`）

`java`（AnalyzeUrl 本身）、`baseUrl`、`cookie`、`cache`、`page`、`key`、`speakText`、`speakSpeed`、`book`（TTS 为 null）、`source`（HttpTTS）、`result`、`infoMap`、`extraParams`。

### 6.3 按真实规则依赖确定的辅助方法（最小集）

来自内置 3 条规则的实测统计（`java.*` / `source.*` 调用）：

| 规则 | 依赖方法 |
| --- | --- |
| 1.百度 | `java.encodeURI`、`speakText`、`speakSpeed`、`{{}}` |
| 2.阿里云语音 | `java.timeFormatUTC`、`java.randomUUID`、`java.encodeURI`、`java.getHeaderMap`、`java.getResponse`、`java.ajax`、`source.getLoginInfoMap`、`source.login`、`source.getHeaderMap`、`source.putLoginHeader`、`Packages.javax.crypto.Mac/SecretKeySpec`、`javax.xml.bind.DatatypeConverter`、`loginCheckJs` 中的 `result.headers()/body()/code()` |
| 3.Next 引擎 | `java.ajaxTestAll`、`java.log`、`java.toast`、`java.ruleUrl`、`java.initUrl`、`java.getResponse`、`java.clearTtsCache`、`source.getLoginInfoMap`、`source.putLoginInfo`、`jsLib` 全局函数 `apiurl()`、`loginUi` v2（`@js:` 生成表单） |

据此，阶段 B 的辅助方法清单（共约 40 个）：
`encodeURI(×2)`、`timeFormat`、`timeFormatUTC`、`randomUUID`、`androidId`、`log`、`logType`、`toast`、`ajax`、`ajaxAll`、`ajaxTestAll`、`connect`、`get`、`post`、`head`、`getResponse`、`getHeaderMap`、`initUrl`、`ruleUrl`、`getErrResponse`、`base64Encode/Decode`、`hexEncode/Decode`、`strToBytes`、`bytesToStr`、`getCookie`、`put`、`get`、`clearTtsCache`、`getSource/refresh*`（空实现或以"暂不支持"报错）、`source.getLoginInfoMap/putLoginInfo/getLoginInfo/putLoginHeader/getHeaderMap/getLoginHeader/getVariable/putVariable/evalJS/login`。

`StrResponse` 需暴露 `body()`、`headers()`、`code()`、`callTime()`；`Response`（loginCheckJs 的 `result`）需暴露 `body().string()`、`headers().get()`、`code()`。

### 6.4 登录 / 动态 Token 流程（阶段 B 覆盖）

```text
规则含 loginUrl（JS 函数库）→ 用户点"登录" → 在 JS 作用域执行 loginUrl（定义 login()/checkjk() 等）
  → 运行 login() → 内部 ajax/getResponse/签名 → source.putLoginHeader(JSON) / putLoginInfo(JSON)
  → 后续合成请求自动附加 loginHeader（按同域判定，见 AnalyzeUrl.isLoginHeaderSite）
合成返回非音频（JSON 错误）→ loginCheckJs 收到 result(Response) → 重新签名/重试 → 返回新 Response
```

`loginUi` 两种形态：
- v1：JSON 数组（`{name,type,default,...}`）→ 静读可渲染为通用表单（text/select/password）；
- v2：`@js:` 脚本生成 UI schema → 需先 eval 再解析为同一表单模型，否则 Next 引擎类规则无法录入参数（**阶段 B 必须实现，否则该规则不可用**）。

### 6.5 安全边界（与需求 §7.2/§十 对齐）

| 能力 | 原版行为 | 本方案 |
| --- | --- | --- |
| `Packages.java.*` 任意类 | ClassShutter 屏蔽 `File/Runtime/ProcessBuilder/Class/ClassLoader/Intent/Settings/Looper/...`；**放行** `javax.crypto` 等 | 沿用同一屏蔽表（等价实现），保留 crypto（阿里云签名必需）；记录为"允许的 Java 能力清单" |
| 文件读写 | ClassShutter 屏蔽 `java.io.File`，但 `JsExtensions` 自身提供 `readTxtFile/getFile/deleteFile/unzip` 等 | **不移植**文件类辅助方法 |
| 脚本执行时长 | 无固定超时，靠 `ensureActive()` 随协程取消 | 增加 `withTimeout(10s)` 包裹单次 eval（可取消），超时报"规则脚本执行超时" |
| 明文 HTTP | 允许 | 允许，但导入/试听时提示"该规则使用明文 HTTP，正文将明文发送给第三方" |
| WebView 类型规则 | 支持 | 不支持，导入时标注"含 webView 参数，暂不支持" |

---

## 七、规则兼容范围与限制

### 7.1 支持矩阵（以源码为准）

| 能力 | 阶段 | 说明 |
| --- | --- | --- |
| GET/POST（form、JSON body、自定义 Content-Type） | A | UrlOption `method/body/headers` |
| URL 内 `{{js}}` 与 `@js:` 整条规则 | A/B | A 支持 `{{}}` 常量表达式；B 完整 |
| Header 规则（JSON 或 `@js`） | B | |
| `charset`（UTF-8/GBK/escape）与 Query/Form 编码 | A | |
| `retry`/`timeout`/`followRedirects` | A | |
| `contentType` 校验（正则） | A | |
| `loginUrl` + `loginCheckJs` + `loginUi`(v1/v2) | B | |
| `jsLib` 全局库 | B | 需共享作用域（原版 `SharedJsScope`；静读侧简化为"规则级缓存作用域"） |
| `enabledCookieJar` + Cookie 持久化 | B | |
| `concurrentRate` | B | |
| `pauseDuration` 段间停顿 | C | 静读侧用静音 PCM 插入 |
| `webView`/`dnsIp`/`serverID`/`type`(hex) | 不支持 | 导入即标注，试听报明确原因 |
| 多角色/音色目录/MD3 扩展 | 不支持 | 按需求 §十一 |

### 7.2 语速语义（重要兼容点）

原版：`speakSpeed = AppConfig.ttsSpeechRate + 5`，默认 `ttsSpeechRate=5 → speakSpeed=10`，规则自行换算（百度：`(speakSpeed+5)/10+4`）。

静读：UI 语速 `0.5–2.0`，映射 `speakSpeed = round(rate × 10)`（0.5→5、1.0→10、2.0→20），**只在合成层生效**，播放器不改速，避免双重变速。规则若不使用 `speakSpeed`，UI 语速对其无效 → 试听页需提示"该规则未使用语速参数"。

---

## 八、静读现有朗读模块的接入方式（最小侵入）

### 8.1 现状（已具备的接入点）

| 组件 | 位置 | 作用 |
| --- | --- | --- |
| `SpeechPlaybackBackend` / `SpeechEngineType` | `core/.../speech/SpeechPlaybackBackend.kt` | 引擎端口；当前 `SYSTEM_TTS/KOKORO` |
| `SpeechSynthesizer` | 同上 | 合成端口（返回 `SynthesizedAudio` = Float PCM + 采样率） |
| `SpeechCoordinator` | `speech/.../SpeechCoordinator.kt` | 有界预取（`maxLookahead=2`）、代次过滤、完成驱动、跨章回调 |
| `AudioTrackPcmPlayer` | `speech/.../AudioTrackPcmPlayer.kt` | 单 AudioTrack + marker 完成回调（PCM_FLOAT/单声道，要求同采样率） |
| `SpeechService` | `speech/.../SpeechService.kt` | 前台服务、MediaSession、音频焦点、唤醒锁、看门狗、跨章 |
| `SpeechConfig` | `speech/.../SpeechPlanStore.kt` | 引擎/语速/音色配置 |
| 朗读计划 | `core/.../SpeechTextPlanning.kt` | 非 Kokoro 走 `SentencePlanner`（网络 TTS 直接复用） |

### 8.2 接入方案（已确认：ExoPlayer 独立播放链路）

```text
ReaderActivity（面板：引擎三选一 + 规则选择）
   → ReaderSettings（speechEngine="network" + networkTtsRuleId）
   → SpeechConfig(engine=NETWORK_TTS, networkTtsRuleId, rate)
   → SpeechService.handlePlay()
        ├─ SYSTEM_TTS → TtsEngine（不变）
        ├─ KOKORO     → SpeechCoordinator + AudioTrackPcmPlayer（不变）
        └─ NETWORK    → NetworkTtsPlaybackBackend（新增，实现 SpeechPlaybackBackend）
                          ├─ 规则执行：AnalyzeUrl 移植版 → okhttp 请求（原版 HttpReadAloudService.getSpeakStream 逻辑）
                          ├─ 播放：ExoPlayer + CacheDataSource + SimpleCache(128MB) + Downloader
                          ├─ 队列：MediaItem 队列（上限 12），可选段间静音
                          └─ 事件：Started/Completed/Buffering/Failed → 复用现有 SpeechPlaybackEvent 语义
   → SpeechService 统一承担：状态机、进度落库、跨章、媒体通知、音频焦点、唤醒锁、看门狗
```

- 新增 `SpeechEngineType.NETWORK_TTS`；`SpeechConfig` 增加 `networkTtsRuleId`。
- `NetworkTtsPlaybackBackend` 直接移植 `HttpReadAloudService` 的播放/队列/预取/取消逻辑（含 `MAX_HTTP_TTS_PLAYER_QUEUE_SIZE=12`、`NEXT_CHAPTER_TTS_PREFETCH_ITEM_LIMIT=10`、`normalizeHttpTtsPauseDuration`、`generateSilentWavBytes`、`hasHttpTtsQueueCapacity`、`httpTtsMediaSessionId`）。
- 播放器不与服务抢音频焦点：`setAudioAttributes(attrs, /* handleAudioFocus = */ false)`，焦点仍由 `SpeechService` 统一管理。
- 媒体项 id 约定 `nettts:<sessionId>:<segmentIndex>`（白音频项 `nettts-pause:`），由 id 反解段序号回写进度与高亮。
- 引擎切换（系统↔Kokoro↔网络）：先 `invalidatePlaybackSession()` → `cancelDownloadTask()` → `release()`，再启动新引擎；旧会话 MediaItem 与下载器全部作废。

### 8.3 音频播放路线（已确认：B）

| 方案 | 结论 | 说明 |
| --- | --- | --- |
| **B. Media3 ExoPlayer + SimpleCache + Downloader** | ✅ **采用** | 与原版一致：边下边播（`CacheDataSource` + 上游 `InputStreamDataSource`）、格式覆盖最全、断点缓存；不改动原生 TTS / Kokoro 的 PCM 播放链路 |
| A. MediaExtractor/MediaCodec → PCM | ❌ 不采用 | 需先落盘再解码，首段延迟高，且要自行处理多容器与重采样 |
| C. Android MediaPlayer | ❌ 不采用 | 无法精确控制队列、取消与缓存 |

保留能力：`streamReadAloudAudio` 式的"流式/先下载后播放"两条路径（默认流式，失败回退先下载），二者共用同一缓存与队列。

### 8.4 规则管理层与模块划分

- 新模块 `:nettts`（Android library，依赖 `:core`、okhttp、Media3、JS 引擎 jar、规则引擎源码）：
  - `data/NetworkTtsRule.kt`（字段与 `HttpTTS` 一致 + `rawJson` 保真）、`NetworkTtsRuleDao`、Room 迁移 **v7**
  - `analyze/AnalyzeUrl.kt`、`analyze/RuleAnalyzer.kt` 等**直接引入原版源码**（保留版权头与来源注释）
  - `js/`：原版 `modules/rhino` 与 `help/JsExtensions.kt`、`ui/login/SourceLoginJsExtensions.kt` 的裁剪副本
  - `login/NetworkTtsLoginSession.kt`（loginUrl/loginCheckJs/loginUi v1+v2，登录信息加密存储）
  - `playback/NetworkTtsPlaybackBackend.kt`（ExoPlayer 队列/缓存/预取/取消，移植 `HttpReadAloudService`）
  - `playback/NetworkTtsDataSource.kt`（上游 `InputStreamDataSource` 等价实现 + `HttpTtsFileCache`）
  - `audition/NetworkTtsAudition.kt`（独立试听播放器，不进入正式朗读队列）
- `:app`：规则管理页/编辑页/登录参数页/试听 + 朗读面板扩展（Compose，沿用现有文艺风格）
- `:speech`：仅新增 `SpeechEngineType.NETWORK_TTS`、`SpeechConfig.networkTtsRuleId`、`SpeechService` 的 NETWORK 分支（约 100–200 行），不改动既有引擎代码

### 8.5 预加载、缓存、任务取消（参数，对齐原版）

| 项 | 取值 | 依据 |
| --- | --- | --- |
| 播放队列上限 | 12 个 MediaItem | 原版 `MAX_HTTP_TTS_PLAYER_QUEUE_SIZE` |
| 跨章预取 | 下一章前 10 段 | 原版 `NEXT_CHAPTER_TTS_PREFETCH_ITEM_LIMIT` |
| 段间停顿 | `pauseDuration` 0–10 s，生成静音 WAV | 原版 `normalizeHttpTtsPauseDuration` + `generateSilentWavBytes` |
| 缓存 | `SimpleCache` 128 MB LRU（`LeastRecentlyUsedCacheEvictor`） | 原版一致；另保留落盘音频目录供"先下载后播放"模式 |
| 缓存键 | 原版键 + `ruleHash` | 修复原版"改规则复用旧音频"缺陷 |
| 请求失败 | 单次失败 → 静音占位继续；超时/连接失败 >5 次 → 暂停并提示 | 原版 `getSpeakStream` 语义 |
| 取消 | 会话代次 + `downloadCancellable`（`Downloader.cancel()`）+ 协程取消 | 原版 `downloadCancellable`，解决阻塞下载不响应中断 |
| 重试 | ExoPlayer `DefaultLoadErrorHandlingPolicy(0)`（不自动重试），由规则层按 `retry` 控制 | 原版 `CustomLoadErrorHandlingPolicy` |

---

## 九、数据模型与持久化

```kotlin
// nettts 模块（字段名与 Legado JSON 完全一致，便于互相导入导出）
data class NetworkTtsRule(
  val id: Long, val name: String, val url: String, val contentType: String?,
  val pauseDuration: Int, val concurrentRate: String?, val loginUrl: String?,
  val loginUi: String?, val loginCheckJs: String?, val header: String?, val jsLib: String?,
  val enabledCookieJar: Boolean, val lastUpdateTime: Long,
  val rawJson: String,            // 保真：导出时优先原样输出，未知字段不丢
  val createdAt: Long, val updatedAt: Long,
)
```

- Room v7 迁移：`CREATE TABLE network_tts_rule(...)` + 索引 (name)。
- 登录信息：复用 `reader/.../CredentialCrypto.kt` AES 加密落盘（替代原版 `androidId` 派生密钥）。
- 选中规则与引擎：`ReaderSettings` 新增 `networkTtsRuleId`，`speechEngine` 增加 `"network"`。

---

## 十、UI 方案（沿用静读风格）

| 页面 | 位置 | 内容 |
| --- | --- | --- |
| 网络朗读规则 | 设置 → 语音朗读 → "网络 TTS 规则" | 列表（名称/摘要/选中态/试听/编辑/更多）；统一导入（文件/剪贴板/链接）；批量选择导出 |
| 规则编辑 | 同上二级页 | 常用字段（名称/URL/Content-Type/语速参数/停顿/并发）+ 高级折叠（header/jsLib/loginUrl/loginUi/loginCheckJs/cookieJar）；多行纯文本，不做代码编辑器 |
| 登录/参数 | 编辑页或列表"登录" | 解析 `loginUi` v1/v2 → 通用表单（text/select/password）+ 原始 JSON 视图 |
| 试听 | 列表项 | 默认文本可改；状态：请求中/播放中/完成/规则解析失败/JS 执行失败/网络失败/鉴权失败/音频异常/播放失败；播放时禁止与正式朗读并存（朗读中点击先提示） |
| 朗读面板 | 阅读页 | 引擎三选一（系统/Kokoro/网络）+ 规则下拉 + 语速；其余控制不变 |

---

## 十一、安全与隐私清单（落地点）

1. 导入时弹窗：规则含可执行脚本 + 正文将发送至第三方（明文 HTTP 额外提示）。
2. 导入后不自动执行脚本、不自动合成（仅保存）。
3. 日志脱敏：文本只记长度与哈希，`Authorization/Cookie/loginHeader` 一律掩码。
4. 脚本超时 10 s、指令计数中断、ClassShutter 屏蔽表等价移植。
5. 导出含鉴权字段（loginUrl/loginUi/loginCheckJs/header/jsLib/登录信息）时二次确认，默认不导出登录信息（原版也不导出，需保持）。
6. 不关闭证书校验；仅允许用户显式配置的明文地址。

---

## 十二、依赖、包体积与许可证

| 新增项 | 体积（估） | 许可证 | 结论 |
| --- | --- | --- | --- |
| `htmlunit-core-js:5.3.0-legado.4`（fork jar，1.8 MB） | +约 1.3–1.5 MB dex | MPL 2.0（Rhino 派生）+ Apache 2.0（打包） | 记录到第三方清单 |
| Legado `modules/rhino`（`com.script.*`，1589 行） | 代码量小 | **GPLv3** | 直接引入（R3），保留版权头与来源记录 |
| Legado 规则/播放相关源码（`AnalyzeUrl`、`AnalyzeRule`、`JsExtensions`、`HttpReadAloudService`、`HttpTtsFileCache`、`ConcurrentRateLimiter` 等） | 约 4–5k 行 | **GPLv3** | 直接引入（R3），模块置于 `:nettts` 保持独立 |
| `media3-exoplayer` / `media3-datasource` / `media3-datasource-okhttp` | 预计 +约 1.5–2.0 MB（构建后实测记录） | Apache 2.0 | 按实际需要引入，不引入 UI / session / HLS 无关组件 |
| `gson` + `json-path`（原版 `HttpTTS`/`UrlOption` 解析） | +约 150–250 KB | Apache 2.0 | 为降低改造量，直接沿用原版解析依赖（待阶段 A 实测确认是否能被 `kotlinx.serialization` 替代） |

### 12.2 许可证决策与合规记录（已确认 R3）

**决策**：采用 **R3 — 仅个人使用、不对外分发**。静读无上架、商业化及对外发布计划，本次优先开发效率、原版规则兼容性及源码复用程度。

据此执行的规则：

1. 允许直接复用 Legado 原版 GPLv3 网络 TTS 相关源码，不因许可证问题重写已成熟模块。
2. 移植模块集中在独立 `:nettts` 模块，尽量减少与静读其他业务模块的耦合。
3. 保留原始版权声明、GPLv3 许可证文本与源码来源记录（见下表）。
4. 当前**不**变更静读整个仓库的许可证，也**不**公开整个仓库。
5. 每次引入新的 GPLv3 文件时，同步在 `docs/01-工程与构建/third-party-notices.md` 增加条目。
6. **如后续有对外分发计划，必须重新进行许可证合规评估**（GPLv3 传染性将使整个应用需以 GPLv3 开源并提供源码）。

### 12.3 GPLv3 代码引入清单（随阶段推进更新）

| 引入内容 | 原版路径 | GPLv3 | 本地落点（计划） |
| --- | --- | --- | --- |
| JS 运行时 | `modules/rhino/src/main/java/com/script/**` | 是 | `nettts/src/main/java/com/script/**` |
| 规则 URL 解析 | `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt`、`RuleAnalyzer.kt`、`AnalyzeUrlNetworkOptions.kt` | 是 | `nettts/src/main/java/io/legado/app/model/analyzeRule/**` |
| JS 辅助方法 | `help/JsExtensions.kt`、`ui/login/SourceLoginJsExtensions.kt`（裁剪） | 是 | `nettts/src/main/java/io/legado/app/help/**` |
| 规则实体与登录存储 | `data/entities/HttpTTS.kt`、`BaseSource.kt`（裁剪） | 是 | `nettts/src/main/java/io/legado/app/data/entities/**` |
| 网络朗读播放链路 | `service/HttpReadAloudService.kt`、`HttpTtsFileCache.kt`、`HttpTtsDownload.kt`、`ParagraphPrefetchState.kt`、`ParagraphSpeechQueue.kt` | 是 | `nettts/src/main/java/io/legado/app/service/**` |
| 并发限速 | `help/ConcurrentRateLimiter.kt` | 是 | 同上 |
| 版权与许可证文本 | 仓库根 `LICENSE`（GPLv3） | — | `nettts/src/main/resources/legado/LICENSE` + 每个文件头保留 `io.legado` 包名与版权注释 |
| JS 引擎二进制 | `third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4/` | MPL 2.0 / Apache 2.0 | `nettts/libs/htmlunit-core-js-5.3.0-legado.4.jar` + `SOURCE.md` |

> 说明：保留 `io.legado.app.*` 包名（而非改写为 `com.yuezhou.*`）是最省改动的做法，也让"哪些文件来自 GPLv3 项目"一目了然，便于未来合规评估与二次分发决策。

---

## 十三、真实规则可行性验证结果（必须如实记录）

### 13.1 已验证（有证据）

1. **JS 引擎可执行真实规则脚本**：`jsLib`、`{{speakSpeed}}`、ES6 语法、`Packages.javax.crypto` 均通过（JVM 独立验证，命令见 §六）。
2. **定制引擎特性必需**：默认 WrapFactory 下 `info["api"]` 返回 `undefined`，证明必须使用 fork + `FEATURE_ENABLE_JAVA_MAP_ACCESS` + `RhinoWrapFactory`。
3. **上游源码已不可获取**：`gedoor/legado` 克隆仅得公告页；后续只能以用户 zip 为基线。
4. **内置 3 条规则当前均不可端到端使用**（2026-11-27 实测）：
   - 百度：`err_no=502 "Invalid param pdt"`（去 pdt 后 501 "cuid is empty"）→ 端点已变更；
   - 阿里云：无凭证返回 400 JSON（鉴权路径无法验证，需用户 AppKey/AccessKey）；
   - Next 引擎：全部节点 TCP 不可达（`000`）；且需用户登录信息中的 `api/发言人/风格`。
5. **用户提供的规则集（`legado.aoaostar.com/sources/19db2f5e.json`，84 条）可用**（2026-11-27 补充实测）：
   - 全部为思必驰 `https://dds.dui.ai/runtime/v1/synthesize` 规则，无登录、无 `jsLib`、`concurrentRate="0"`；
   - `curl` 实测：单条规则 URL 中 `{{java.encodeURI(java.encodeURI(speakText))}}` 的双重编码在服务端可解码，返回 `audio/wav`（16 kHz 单声道 PCM），HTTP 200；
   - 注意：同 URL 内重复 `voiceId` 参数会返回 400（规则本身只有一个 voiceId，正常）。
   - 结论：阶段 A 已可用真实规则做基础链路验证；阶段 B 仍需覆盖含 JS/登录的规则。

### 13.2 未验证 / 阻塞

| 项 | 状态 | 阻塞原因 |
| --- | --- | --- |
| 用户真实规则免修改导入/试听/连续朗读（**核心验收目标**） | ⚠️ 部分实现，待人工验收 | 84 条基础规则样本已有；复杂 JS/鉴权规则、登录信息和连续朗读效果未验收 |
| Android 端 JS 引擎集成（APK 内运行） | ⚠️ 代码已接入，待真实规则验收 | JVM 测试覆盖脚本执行/超时；不以此代替 APK 功能验收 |
| 真实音频格式与 ExoPlayer 解封装覆盖 | ⚠️ 基础 WAV 已有样本 | 其他真实返回格式与长时播放未验证 |
| 原版规则执行差异（如 header 为 `@js`、loginUi v2） | ⚠️ 代码已实现，待真实规则验收 | 缺少对应真实规则及登录信息 |

### 13.3 解除阻塞所需材料

1. **至少 2 条已在 Legado 原版成功试听的真实规则 JSON**（覆盖：纯 HTTP 规则 1 条 + JS/鉴权规则 1 条）。
2. 对应登录信息（AppKey/AccessKey/Token 等，可只给测试额度）。
3. 该规则在原版中试听成功的文本样本与返回音频格式（mp3/wav）截图或说明。
4. 若规则依赖特定 UA/设备信息，需说明。

---

## 十四、分阶段实施计划与验收

> 前置：许可证路线已定为 R3（§12.2）；阶段 A 开工前仍需 §13.3 的真实规则材料用于阶段 B 验收（阶段 A 可用本地 mock 服务先行）。

| 阶段 | 交付 | 关键文件 | 验收 |
| --- | --- | --- | --- |
| **A 规则管理+基础合成** | `:nettts` 模块骨架、原版规则实体/导入、Room v7、Media3 播放链路、管理 UI、基础 HTTP（无 JS）、独立试听 | `nettts/data/*`、`nettts/analyze/AnalyzeUrl.kt`、`nettts/playback/NetworkTtsPlaybackBackend.kt`、`nettts/audition/*`、`nettts/libs/*.jar` | 纯 HTTP 规则（GET/POST/表单/header/超时/重试）可导入、可试听；本地 mock 服务与 1 条真实纯 HTTP 规则通过；记录 APK 体积增量 |
| **B 原版规则兼容** | JS 引擎（`com.script.*`）+ 绑定 + 辅助方法 + loginUrl/loginCheckJs/loginUi(v1+v2) + cookie/限速 | `nettts/js/**`、`nettts/help/JsExtensions.kt`、`nettts/login/NetworkTtsLoginSession.kt` | 用户提供的真实 JS/鉴权规则**免修改**通过试听；记录不兼容项与原因 |
| **C 正式朗读** | `NETWORK_TTS` 引擎接入、ExoPlayer 队列/预取/缓存、跨章、后台/锁屏、取消与引擎切换 | `speech/SpeechService.kt`、`core/.../SpeechPlaybackBackend.kt`、`nettts/playback/*`、`app/ReaderActivity.kt`、`app/ReaderSettings.kt` | 整章连续朗读；快速切段/切引擎/停止无串音与旧任务；跨章自动续读；媒体通知/锁屏可控；原生 TTS 与 Kokoro 不受影响 |
| **D 稳定性与交付** | 异常/并发/资源释放测试、回归（系统 TTS + Kokoro）、APK 与依赖体积记录 | — | 无崩溃/无无限重试/无线程与内存增长；原生 TTS 与 Kokoro 行为无回归；测试版 APK 可安装 |

阶段 D 的网络异常、断网恢复、429/5xx、脚本死循环、超长文本、快速切段、播放中切规则等仍需按工作区规范由用户主导验收；开发侧不主动执行设备功能测试，不生成大型证据包。

---

## 十五、风险登记

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| 真实 JS/鉴权规则不足，完整兼容性无法验收 | 高 | 基础 84 条样本只证明基础链路；继续收集含登录/jsLib/loginCheckJs 的真实规则，不得提前宣称完整兼容 |
| GPLv3 传染（R3 仅自用） | 中（已接受） | 代码集中在 `:nettts` 并保留版权头；third-party-notices 逐文件登记；后续对外分发前重新评估 |
| Media3 与现有 PCM 播放器并存 | 中 | 两条链路互不依赖，仅在 `SpeechService` 分支切换；每会话只允许一条链路存活，切换先释放 |
| ExoPlayer 与 SpeechService 重复管理音频焦点 | 中 | ExoPlayer `handleAudioFocus=false`，焦点只由服务管理 |
| SimpleCache 与下载器资源泄漏 | 中 | 每会话 `invalidatePlaybackSession` + `cancelDownloadTask` + `release`；缓存实例应用级单例 |
| 规则被服务端风控/接口失效 | 中 | 明确错误分类提示（鉴权/接口失效/格式不符），不做多引擎自动切换 |
| 明文 HTTP 与隐私 | 中 | 导入提示 + 请求前二次确认（可关闭） |
| 上游已清空，无法跟进更新 | 中 | 固定基线快照 + 版本化文档 |
| loginUi v2 脚本生成表单工作量 | 中 | 阶段 B 单独任务，先支持 text/select，其余降级为原始 JSON 编辑 |

---

## 十六、决策状态与待确认事项

**已确认（2026-11-27）**

1. 许可证路线：**R3** —— 仅个人使用、不对外分发；允许直接复用 Legado GPLv3 源码；保留版权与来源记录；不对整个仓库变更许可证或公开；未来对外分发前重新评估。
2. 音频路线：**B** —— 引入 Media3 ExoPlayer（含 SimpleCache、Downloader），网络 TTS 使用独立播放链路，不改动原生 TTS 与 Kokoro 的播放实现。
3. 暂不引入 Legado-with-MD3 的任何扩展功能。

**待确认**

1. **真实规则材料**：请提供 ≥2 条原版可用规则 JSON + 登录信息（或测试额度）—— 阶段 B 验收硬依赖。
2. **UI 落点**：规则管理放"设置 → 语音朗读"二级页（默认），或单独一级入口。
3. 是否接受"`webView`/`dnsIp`/`serverID` 类规则明确不支持"的兼容边界。

> 本阶段未改动任何业务代码；上述结论均来自源码阅读与本地可复现验证，未验证项已在 §13.2 明确标注。

---

## 十七、评审确认与阶段 A 实施调整（2026-11-27）

**评审结论：阶段 0 方案有条件通过。** 维持 R3（仅个人使用、直接复用 GPLv3 源码）、B（Media3 独立播放链路）、独立 `:nettts` 模块与原版定制 JS 引擎；入口定为"设置 → 语音朗读 → 网络 TTS 规则管理"；接受 `webView`/`dnsIp`/`serverID`/hex 等暂不支持的兼容边界；不引入 Legado-with-MD3 扩展。原文 §1–§16 不再重写，以下 8 条为实施期必须遵守的调整：

1. **失败不静默**：合成失败不得以空白音频跳过正文；有限重试仍失败时暂停朗读并提示，保留当前段落位置（`NetworkTtsSynthesizer` 抛 `SynthesisException`，不再返回 silent 占位）。
2. **rawJson 导出**：保留导入时的未知字段，导出时以当前编辑后的已知字段覆盖；默认剔除登录凭证类字段（`loginInfo/loginHeader/userInfo/cookie/authorization/password/...`）。
3. **脚本与网络超时**：JS 执行、HTTP 请求、落盘均可超时与取消；JS 通过 Rhino 指令观察器中断死循环；危险系统能力（WebView/文件/浏览器）不提供；错误信息经 `NetworkTtsLog.sanitize` 脱敏。
4. **队列与缓存**：保留原版播放队列上限 12；初期实际预合成 2–3 段；默认合成并发 1；落盘音频目录默认 256 MB，超出按最久未用淘汰并保护使用中文件。`SimpleCache` 为原计划，当前本地文件播放未接入。
5. **段间停顿**：统一使用原版参数的静音 WAV 媒体项（`NetworkTtsPlayer.silentWavFile`，24 kHz/单声道/16 bit），不引入独立 PCM 逻辑；静音项不推进阅读位置。
6. **真实规则**：非阶段 A 硬性条件（阶段 A 用本地 mock 服务 + 用户规则集做基础链路验证）；阶段 B 必须用真实规则验收，不得虚报。
7. **流式回退**：流式播放失败回退下载模式时，复用已合成的缓存文件，避免重复合成、重复播放已完成内容与额度浪费（缓存键含 `ruleHash`）。
8. **测试与交付**：遵循工作区 `AGENTS.md`；开发完成后直接构建独立测试版 APK，不主动执行模拟器、真机或自动化功能测试；ADB 可用时直接安装，功能由用户验收，不生成大型证据包。
