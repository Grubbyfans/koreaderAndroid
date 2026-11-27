# :nettts 网络朗读模块

Legado 原版网络 TTS 能力的移植模块，仅为静读（yuezhou-reader）内部使用。

## 许可证与来源（R3 决策）

- 来源项目：Legado（`gedoor/legado`），用户提供的 `legado-master.zip` 快照（基线 commit `9639f027fbc8cfdf57ce136a9e0c98829a235aef`，versionCode 37540）。
- 移植代码保留原文件版权头与包名（`io.legado.app.*`、`com.script.*`），整体按 **GPLv3** 使用，全文见 [LEGADO-LICENSE.txt](LEGADO-LICENSE.txt)。
- 使用范围：仅个人自用，**不对外分发**；若未来对外分发，须重新进行许可证合规评估。
- JS 引擎：`libs/htmlunit-core-js-5.3.0-legado.4.jar`（Rhino 派生，MPL 2.0 / Apache 2.0），许可证与来源记录随 APK 分发于 `src/main/assets/licenses/htmlunit-core-js/`。

## 已移植文件（相对原版路径）

| 本模块位置 | 原版来源 | 改动 |
| --- | --- | --- |
| `com/script/**` | `modules/rhino/src/main/java/com/script/**` | 原样复制 |
| `org/htmlunit/corejs/javascript/{xmlimpl,ConcurrentNativeObject}` | `modules/rhino/src/main/java/org/**` | 原样复制（jar 中缺失的补丁类） |
| `io/legado/app/constant/AppConst.kt`、`AppPattern.kt` | 同名文件 | 仅保留朗读链路使用的常量 |
| `io/legado/app/utils/{NetworkUtils,EncoderUtils,GsonExtensions}.kt` | 同名文件 | 去掉联网检测、书籍规则适配器、hutool 依赖 |
| `io/legado/app/help/{CacheManager,ConcurrentRateLimiter,JsExtensions}.kt` | 同名文件 | CacheManager 改内存实现；JsExtensions 仅保留朗读需要的约 40 个方法 |
| `io/legado/app/help/http/*` | 同名包 | 去掉 WebView/Cronet/代理/图片进度；CookieStore 改内存 + 可选持久化端口 |
| `io/legado/app/data/entities/{BaseSource,HttpTTS}.kt` | 同名文件 | HttpTTS 去掉 Room/json-path，新增 `rawJson`；BaseSource 精简登录与变量能力 |
| `io/legado/app/model/analyzeRule/{AnalyzeUrl,RuleAnalyzer}.kt` | 同名文件 | AnalyzeUrl 去掉 webView/dnsIp/serverID/Glide/Cronet/上传；`webView`/`dnsIp`/`serverID` 命中即显式报"暂不支持" |
| `io/legado/app/model/SharedJsScope.kt` | 同名文件 | 简化为按规则 + jsLib 哈希缓存作用域 |
| `io/legado/app/nettts/*` | `HttpReadAloudService`、`HttpTtsFileCache`、`HttpTtsDownload`、`ImportHttpTtsViewModel` | 重写为可测试的门面：合成、缓存、播放、规则导入导出、日志脱敏 |

## 依赖

- `androidx.media3:media3-exoplayer / datasource / datasource-okhttp / common`（1.10.1，与原版基线一致）
- `com.google.code.gson:gson`
- `com.squareup.okhttp3:okhttp`
- `org.jetbrains.kotlinx:kotlinx-serialization-json`
- `libs/htmlunit-core-js-5.3.0-legado.4.jar`（本地文件依赖）

## 设计边界

- 与 app/reader 无反向依赖：UA、缓存目录、凭证存储均由 app 层通过 `NetworkTtsRuntime` 注入。
- 阶段 A 只提供"单段合成 + 试听"；播放队列、跨章预取在后续阶段接入，播放器本体已按 ExoPlayer + SimpleCache(128 MB) 搭建。
