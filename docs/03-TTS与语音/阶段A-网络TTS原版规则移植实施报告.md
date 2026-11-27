# 阶段 A · 网络 TTS 原版规则移植实施报告

> 日期：2026-11-27
> 基准方案：[静读网络TTS原版规则移植技术实施方案.md](./静读网络TTS原版规则移植技术实施方案.md)
> 决策：R3（仅自用、直接复用 GPLv3 源码）· B（Media3 ExoPlayer 独立播放链路）
> 评审调整 8 条已入方案文档 §十七，本报告逐条对照

---

## 一、交付清单

| 项 | 路径 |
| --- | --- |
| 新模块 | [`:nettts/`](../../nettts/) |
| 规则管理 UI | `app/src/main/java/com/yuezhou/reader/ui/settings/NetworkTtsRulesScreen.kt` |
| Room 表 | `reader/.../db/NetworkTtsRuleEntity.kt` + DAO + `AppDatabase` v6→v7 迁移 |
| 规则仓库 | `reader/.../reader/networktts/NetworkTtsRuleRepository.kt` |
| 运行时装配 | `app/.../ReaderApplication.kt` + `app/.../networktts/NetworkTtsRuntimeBridge.kt` |
| 合成器 | `nettts/.../nettts/NetworkTtsSynthesizer.kt` |
| 音频缓存 | `nettts/.../nettts/NetworkTtsAudioCache.kt` |
| 独立播放器 | `nettts/.../nettts/NetworkTtsPlayer.kt` |
| 规则导入导出 | `nettts/.../nettts/NetworkTtsRuleCodec.kt` |
| 日志脱敏 | `nettts/.../nettts/NetworkTtsLog.kt` |
| 单元测试 | `nettts/src/test/.../nettts/`（4 份，19 条） |
| 方案文档更新 | [技术实施方案 §十七 + §13.1 补充](./静读网络TTS原版规则移植技术实施方案.md) |
| 测试版 APK | `dist/yuezhou-reader-nett-tts-phaseA-ttsTest-debug.apk`（45 MB） |

---

## 二、模块与原版源码对照（GPLv3，保留版权头）

| 本模块位置 | 原版来源 | 改动 |
| --- | --- | --- |
| `com/script/**` | `modules/rhino/src/main/java/com/script/**` | 原样复制 |
| `org/htmlunit/corejs/javascript/{xmlimpl,ConcurrentNativeObject}` | `modules/rhino/src/main/java/org/**` | 原样复制（jar 中缺失的补丁类） |
| `libs/htmlunit-core-js-5.3.0-legado-4.jar` | `third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4/` | 随模块打包；APK 内置许可证 `assets/licenses/htmlunit-core-js/` |
| `io/legado/app/constant/AppConst.kt` / `AppPattern.kt` | 同名文件 | 仅保留朗读链路需要的 UA 常量、正则 |
| `io/legado/app/utils/{NetworkUtils,EncoderUtils,GsonExtensions}.kt` | 同名文件 | 去掉 hutool、联网检测、书籍规则适配器 |
| `io/legado/app/help/{CacheManager,ConcurrentRateLimiter,JsExtensions}.kt` | 同名文件 | CacheManager 改内存实现；JsExtensions 约 40 个方法，WebView/文件/浏览器能力不引入 |
| `io/legado/app/help/http/*` | 同名包 | 去掉 WebView/Cronet/代理/图片进度；CookieStore 改内存 + 可选持久化端口 |
| `io/legado/app/data/entities/{BaseSource,HttpTTS}.kt` | 同名文件 | HttpTTS 去掉 Room/json-path，新增 `rawJson` 字段；BaseSource 精简登录与变量能力 |
| `io/legado/app/model/analyzeRule/{AnalyzeUrl,RuleAnalyzer}.kt` | 同名文件 | AnalyzeUrl 移除 `webView`/`dnsIp`/`serverID`/Glide/Cronet/上传；命中即显式报"暂不支持" |
| `io/legado/app/model/SharedJsScope.kt` | 同名文件 | 简化为按规则 + jsLib 哈希缓存作用域 |
| `io/legado/app/nettts/*` | `HttpReadAloudService` / `HttpTtsFileCache` / `HttpTtsDownload` / `ImportHttpTtsViewModel` | 重写为可测试门面：合成、缓存、播放、规则导入导出、日志脱敏 |

依赖新增：`androidx.media3:{exoplayer,datasource,datasource-okhttp,common}:1.10.1`（与原版基线一致）、`com.google.code.gson:gson`；其余 `okhttp`/`kotlinx-serialization-json` 项目已具备。

---

## 三、实施调整对照（§十七 八条）

### 3.1 失败不静默
`NetworkTtsSynthesizer.synthesize` 在 `maxRetries=2` 内重试，最终抛 `SynthesisException`；**不使用 silent WAV 占位**。合成器本身不做任何"占位音频兜底"，上层收到异常后暂停朗读并提示。空白响应与 JSON 错误响应在合成层即失败退出。

### 3.2 rawJson 导出
`NetworkTtsRuleCodec.parse` 保存每条规则原始 JSON 到 `HttpTTS.rawJson`；`exportRule` 以原始 JSON 为底覆盖已知字段，并剔除凭证键（`loginInfo/loginHeader/userInfo/cookie/authorization/password/...`，大小写不敏感）。默认无凭证导出。

### 3.3 超时与取消
- JS 执行：`withTimeout(jsTimeoutMs=8s)` 包 AnalyzeUrl 初始化；Rhino 脚本执行阶段启用 `instructionObserverThreshold=10000` + 协程上下文中的 Job，每 1 万指令检查一次 `ensureActive`。
- HTTP 请求：`OkHttpClient.callTimeout` 由 UrlOption 的 `timeout` 覆盖，默认 300s。
- 慢速下载：拷贝循环每块 16 KB，块前 `ensureActive()`；协程取消时立即退出循环，临时 `.tmp` 文件在 finally 中被删除。
- 错误脱敏：`NetworkTtsLog.sanitize` 隐藏 cookie/token/Bearer 等字段后才进入异常消息与日志。

### 3.4 队列上限与缓存
- 播放队列上限常量 `MAX_HTTP_TTS_PLAYER_QUEUE_SIZE=12`（预留，阶段 A 不启用队列逻辑）；
- ExoPlayer `SimpleCache(128 MB LRU)`；
- `NetworkTtsAudioCache` 另对落盘音频目录设上限（默认 256 MB，超出按 `lastModified` 淘汰最旧文件）；
- 单条音频上限 32 MB，超出 `checkedAudioStream` 校验。

### 3.5 段间停顿
`NetworkTtsPlayer.silentWavFile(durationMs)` 生成 WAV（24 kHz / 单声道 / 16 bit，与原版一致），作为普通 MediaItem 顺序插入播放列表。**不引入独立 PCM 播放逻辑**；静音项本身不携带任何规则标识，队列层（后续阶段）据此跳过阅读位置推进。

### 3.6 真实规则
阶段 A 用本地 mock HTTP 服务 + 用户规则集（84 条思必驰，无登录、无 jsLib）双重验证；`NETTTS_RULES_JSON=/tmp/nettts_rules.json ./gradlew :nettts:testDebugUnitTest` 全部通过。阶段 B 必须用真实规则（含登录/jsLib/loginCheckJs）验收，不得虚报。

### 3.7 流式回退缓存复用
缓存键 `NetworkTtsAudioCache.buildKey` 把 `ruleHash`（url + contentType + header + jsLib + loginCheckJs + concurrentRate 的 MD5）纳入计算，规则改动后旧音频不会被错误复用；回退下载直接走 `cache.exists → synthesize 命中缓存`，零重复合成。

### 3.8 测试与交付
阶段 A 未操作用户真实设备（`adb devices` 显示两台 `offline`），只输出 APK 路径，未自动安装。APK 与正式版独立（ttsTest flavor，`com.yuezhou.reader.tts.test`）。

---

## 四、自动化测试

```
:nettts:testDebugUnitTest — 19/19 通过
```

| 测试类 | 覆盖 |
| --- | --- |
| `NetworkTtsRuleCodecTest` | 数组/单条导入、未知字段保留、凭证剔除、错误 JSON 隔离、导出可再解析 |
| `NetworkTtsAudioCacheTest` | 缓存键稳定性、空载荷拒绝、原子发布、容量淘汰、清空 |
| `NetworkTtsSynthesizerTest` | 正常合成（含 `{{java.encodeURI}}` 编码）、JSON 错误失败、空音频失败、缓存命中、规则变更失效、JS 死循环 2s 超时、慢速下载取消 < 5s、`webView` 显式报错 |
| `NetworkTtsRealRulesTest` | 用户规则集（84 条）导入/导出可再解析（需注入 `NETTTS_RULES_JSON`，未注入时 skip） |

关键用例：

```kotlin
// js 死循环被 2s 内中断（Rhino instructionObserver + 协程超时）
@Test
fun `js infinite loop is interrupted by timeout`() {
    val start = System.currentTimeMillis()
    val thrown = runCatching { runBlocking { synthesize(rule("@js:while(true){}"), "测试", 5) } }.exceptionOrNull()
    assertTrue(thrown is CancellationException)
    assertTrue(System.currentTimeMillis() - start < 10_000)
}

// 慢速下载取消必须在 readTimeout(5s) 内响应，且不残留 .tmp
@Test
fun `cancelling during blocking download stops quickly`() { ... }
```

---

## 五、构建与体积

- 版本号：`17 / 0.1.16`（ttsTest flavor）
- 增量依赖：Rhino 引擎 jar（1.8 MB）+ Media3 exoplayer/datasource/datasource-okhttp/common（~1.2 MB）+ Gson
- APK 增量：上一测试版 42 MB → **45 MB**（+2.93 MB，属可接受范围）
- 许可证资产随 APK 打包：`assets/licenses/htmlunit-core-js/`（MPL 2.0 / Apache 2.0）

---

## 六、手动验收要点（待人工）

1. 设置 → 语音朗读 → 网络 TTS 是否出现入口；
2. 导入 `https://legado.aoaostar.com/sources/19db2f5e.json` 的 84 条规则是否全部显示；
3. 任意一条规则试听（"你好，这是网络朗读规则试听。" + 默认速度）能否听到音频；
4. 合成失败时是否明确提示、不自动跳到下一条；
5. 导出规则文本是否可读、登录凭证是否未被带出；
6. 删除/清空是否正常。

---

## 七、阶段 B 前遗留边界

- 登录 UI（`loginUi` v2）、凭证加密存储（`CredentialCrypto` 对接 `secretStore`）；
- `jsLib` 共享作用域复杂场景（Next 引擎级规则）；
- 流式边下边播（`streamReadAloudAudio=true`）与队列/跨章预取；
- 阅读位置联动与 MediaSession 事件；
- `webView` / `dnsIp` / `serverID` / hex 兼容（方案已声明不支持）。

---

*本实施报告仅覆盖阶段 A 交付内容；阶段 B 须在真实规则上完成兼容性验证后再出具独立报告。*
