# Z-Library 第三阶段 API 验证及正式集成建议

验证日期为 2026-11-25（北京时间）。第一阶段记录了匿名 WebView 搜索可用及旧搜索 API 的拒绝响应。第二阶段已实测网页搜索、筛选、详情、登录、下载与 EPUB 校验。本轮沿用这些结论，聚焦现有 App API 及接入评审。

**本次测试主站和授权样本的原生 API 链路通过。** 真机完成登录、详情、额度查询、取链、文件下载和 EPUB 校验。网页单段书籍标识需要与 API 的 `id:hash` 建立明确对应关系。本轮从主站公开书单取得同一资源的对应关系。

## 实测环境与范围

真机 SM-S9360，Android 16，测试变体 `ttsTestDebug`。独立 Activity 在 `:apiprobe` 进程运行，使用阶段一、二已验证的主站 `https://zh.z-lib.gd` 和仓库现有的默认 API 端点、客户端及 TLS 校验。主机固定于探针的内存配置，与设备已有服务器候选一致。探针只用手动输入的账号进行原生 API 登录，API 响应产生的会话只在本轮请求期间使用。日志限于状态、分类、耗时、类型、是否有 API Cookie 以及文件校验指标。探针没有调用网页 Cookie 接口、正式凭证库、书库登记或偏好保存。

## 线上 API 结果

| 项目 | 真机结果 |
| --- | --- |
| API 登录 | 通过。`POST /rpc.php` 返回 HTTP 200 JSON；现有客户端未在主响应结束登录，继续调用 `POST /eapi/user/login`，同样收到 HTTP 200 JSON，随后构建了非空会话。两步合计 1,991 ms。HTTP 200 本身不足以判断主端点业务成功。 |
| 额度查询 | 通过。携带本轮原生 API 登录会话调用 `GET /eapi/user/profile`，HTTP 200 JSON，466 ms，返回已使用 6、上限 10。 |
| 书籍详情 | 通过。主站公开书单同一行返回用户所选网页地址及 `18251082:9f4a6c`。主机匿名详情请求与真机原生 API 带会话请求均返回 HTTP 200 JSON，标识一致。`extension=epub`、`filesize=2191400`，真机详情耗时 540 ms。 |
| `getDownloadLink` | 通过。真机带 API 会话调用 `GET /eapi/book/{id}/{hash}/file`，554 ms 返回并解析出 HTTPS 下载链接。日志未记录链接。 |
| 实际文件下载及 EPUB 校验 | 通过。现有 `downloadToFile` 的请求未携带 API Cookie，HTTP 200，类型为 `application/epub+zip`，传输耗时 3,617 ms。落盘 2,191,400 字节，与详情声明及传输计数一致。现有 `EpubValidator` 与 98 个 ZIP 文件条目的 CRC 检查均通过，文件检查合计 23 ms。SHA-256 为 `20b854d3fcc088a16c01d6f1998325056b8ea78734e83be46c559ed27a0a070a`。临时文件在校验后清理。 |
| 取链前后额度 | 取链前后两次均返回已使用 6、上限 10。用户确认当天已经下载过同一本书，因此本轮是当日重复下载，未见额外扣额。首次下载新书的扣额行为本轮未测。 |

### 真机日志摘录

本机完整脱敏日志位于 `.workbuddy/tmp/zlibrary-api-stage-three.log`。

```text
11-25 01:59:02.047 18087 18087 I ZLibraryApiProbe: FORM_INVALID field=book
11-25 02:03:36.071 25763 25763 I ZLibraryApiProbe: SUBMITTED download=false
11-25 02:03:37.600 25763 26310 I ZLibraryApiProbe: HTTP endpoint=rpc_login method=POST status=200 cookiePresent=false type=application/json
11-25 02:03:38.080 25763 26310 I ZLibraryApiProbe: HTTP endpoint=fallback_login method=POST status=200 cookiePresent=false type=application/json
11-25 02:03:38.088 25763 25763 I ZLibraryApiProbe: RESULT step=login outcome=OK ms=1991
11-25 02:03:38.089 25763 25763 I ZLibraryApiProbe: API_SESSION present=true
11-25 02:03:38.553 25763 26310 I ZLibraryApiProbe: HTTP endpoint=quota_before method=GET status=200 cookiePresent=true type=application/json
11-25 02:03:38.555 25763 25763 I ZLibraryApiProbe: RESULT step=quota_before outcome=OK ms=466
11-25 02:03:38.555 25763 25763 I ZLibraryApiProbe: QUOTA before used=6 limit=10
11-25 02:07:49.876 25763 25763 I ZLibraryApiProbe: SUBMITTED download=true
11-25 02:07:52.385 25763 26310 I ZLibraryApiProbe: HTTP endpoint=detail method=GET status=200 cookiePresent=true type=application/json
11-25 02:07:52.392 25763 25763 I ZLibraryApiProbe: DETAIL identityMatched=true epub=true bytes=2191400
11-25 02:07:52.978 25763 25763 I ZLibraryApiProbe: QUOTA before used=6 limit=10
11-25 02:07:53.528 25763 26310 I ZLibraryApiProbe: HTTP endpoint=link method=GET status=200 cookiePresent=true type=application/json
11-25 02:07:53.532 25763 25763 I ZLibraryApiProbe: RESULT step=link outcome=OK ms=554
11-25 02:07:54.798 25763 26310 I ZLibraryApiProbe: HTTP endpoint=download method=GET status=200 cookiePresent=false type=application/epub+zip
11-25 02:07:57.155 25763 26310 I ZLibraryApiProbe: RESULT step=download outcome=OK ms=3617
11-25 02:07:57.178 25763 26310 I ZLibraryApiProbe: RESULT step=validate outcome=OK ms=23
11-25 02:07:57.178 25763 26310 I ZLibraryApiProbe: FILE bytes=2191400 localBytes=2191400 epubValid=true crcEntries=98 sha256=20b854d3fcc088a16c01d6f1998325056b8ea78734e83be46c559ed27a0a070a
11-25 02:07:57.998 25763 25763 I ZLibraryApiProbe: QUOTA after used=6 limit=10
```

用户提交的详情路由为 `/book/BAjkZOqjPy/…`。此路由只含单段书籍标识及书名，当前 `ZLibraryApi.getBookDetail` 和 `getDownloadLink` 都要求独立的 `id` 与 `hash`。表单首次提交在请求前被拦下。随后使用主站公开 `/eapi/book/most-popular?page=1&limit=30`，以该网页路径与书单行的 `url` 精确比对，取得服务直接返回的 `18251082:9f4a6c`。书单返回 100 行，不能据请求参数推断服务端一定限制为 30 行。此结果只建立该样本的映射。

本次详情 JSON 使用 `extension`，现有 `toOnlineBook` 读取 `format` 并默认为 `epub`。所选样本的 EPUB 格式以原始详情字段确认。正式改造时可以兼容两字段，并把缺失或不支持的格式作为明确状态处理。

复现入口如下。账号、密码及已授权书籍标识由操作者在手机上输入，`getDownloadLink` 只在已拿到 API 会话且详情返回对应 EPUB 时调用。夹具或旧网页下载样本不会计入本轮 API 结果。

```powershell
adb shell am start -W -n com.yuezhou.reader.tts.test/com.yuezhou.reader.webprobe.ZLibraryApiProbeActivity
adb logcat -d -s ZLibraryApiProbe:I
```

## 本地测试及包隔离

| 既有单元测试 | 本轮结果 | 证据范围 |
| --- | ---: | --- |
| `ZLibraryApiTest` | 11/11 | 假响应覆盖协议、登录和回退、权限错误分类、搜索映射、额度字段等。 |
| `EpubValidatorTest` | 5/5 | 本地文件结构与安全检查。 |
| `EpubImportFilesTest` | 5/5 | 本地文件命名及安装行为。 |
| `KokoroMemoryPolicyTest` | 4/4 | 本地内存守卫和模型槽位行为。 |

本轮共 25 项单测全部通过，本地结果不用于判定线上接口可用性。测试 APK 构建成功，`:app:lintTtsTestDebug` 为 0 错误、53 警告、3 提示。已检查正式 unsigned APK 的 Manifest 和 3 个 DEX，均无第三阶段探针。探针入口仅在 `ttsTestDebug`，需要 Android 的 `DUMP` 权限显式启动。新增的 OkHttp 编译引用限于 `ttsTest` 风味，复用核心模块已使用的版本。

## 外部已下载文件的最小接入范围

| 代码 | 现状 | 建议改造 |
| --- | --- | --- |
| `BookDownloadManager` | `enqueue` 以规范化文件名预检查书架冲突。`run` 在判断 pending 文件前解析 `CatalogBookProvider`。已有 pending 时，现有逻辑可以跳过远端传输；导入失败可保留 pending 文件，显式重试时复用。启动恢复会清理未完成任务的 `localPath`。 | 增加接收已完成下载的入口，接受受控文件句柄或 URI、预期字节数、建议文件名和来源标识。在主进程将外部文件有界复制到应用暂存目录，完成后原子改名到按 task ID 生成的 `pending_import` 路径，再持久化任务。仅在缺少 pending 且任务属于现有网络下载时解析 Provider 并调用 `download`。外部任务的 pending 丢失后提示重新选择原文件，恢复时保留能核对归属和完整性的 pending，向现有任务页提供导入重试。 |
| `OnlineImportCoordinator` | 已接收 pending 文件，执行 `EpubValidator`、同名覆盖确认、正在阅读保护、封面和标题提取、正文安全替换、书库登记和来源映射。 | 首版沿用导入入口与校验器。确认后的最终文件名、来源、账号作用域和远程 ID 由新入口传入。同最终文件名的更改串行化，提交前复查正文及书库冲突，避免下载确认后文件已被其他任务替换。覆盖失败仍复用当前回滚路径。 |
| `LibraryStore` | `registerBook` 要求正文已落盘，根据 `BookNames.localId(fileName)` 得到本地 ID，更新标题、封面及目录，显示名可独立于文件名。 | 直接复用登记。读取、写入及刷新由主进程负责。沿用现有 ID、进度和书签规则。网页进程只交接文件和元数据，避免并发更新 `library.json` 或目录。 |

若正式首版需要下载历史，建议走管理器入口以复用任务状态、冲突确认、失败重试和 `LibraryRefreshSignal`。若范围仅为一次性手动导入，可把受控暂存文件直接交给 `OnlineImportCoordinator`，同样复用覆盖试算与确认。交接时给稳定的任务标识做幂等去重，回收孤儿暂存文件。失败恢复依赖本地 pending，避免自动重取链接消耗额度。网页登录态、Cookie 和签名链接均无需出现在任务表。

## 文件命名及覆盖

第二阶段服务端交付的 `redirection.epub` 被证实为有效文件，但语义不足且容易造成同名冲突。建议将 HTTP 文件名视为候选，仅当其可辨认时作为默认名。对 `redirection.epub`、`download.epub` 等占位名，优先用已校验 EPUB 的 OPF 标题或当前详情页书名，必要时提供可编辑候选。最终统一由 `EpubImportFiles.fileName` 规范化非法字符和扩展名，同时将空名、系统保留名和过长名称纳入命名入口的校验。

用户确认后，以**规范化的完整文件名**执行唯一性检查。不同内容同名时复用现有覆盖确认或让用户明确选择新名称。相同内容只在现有覆盖授权范围内复用，并保留正文代次与进度。进入导入前重新检查冲突、阅读占用和覆盖确认是否仍对应当前目标。远程 ID、摘要和暂存文件名可用于定位及任务去重，书库唯一性和本地 ID 仍由现有文件名规则决定。

## WebView、阅读器与 Kokoro 内存管理

第二阶段同一设备上，翻页及返回后，网页宿主与对应渲染器 PSS 分别为 251,365 和 155,806 KiB。销毁 WebView 约 3 秒后分别降至 169,357 和 108,512 KiB，两进程仍存活。这组数值覆盖网页宿主和对应渲染器的单轮观察，阅读器与 Kokoro 同时运行的峰值本轮未测。

- 网页模式建议维持独立同 UID 进程，在首次调用 WebView 或 CookieManager 前设置专属 WebView 数据目录。Android 9 以下可保留现有模式作为兼容路径。分进程隔离会话、生命周期和故障，但整机内存仍共享。网页协议应只交接受控文件与元数据，阅读器、书库登记和媒体朗读继续由现有进程负责。[Android WebView API](https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String))
- 网页容器只保留一个活动 WebView。失去可见性时停止非必要工作，进入阅读器且无需保留页面时停止加载、从视图移除并销毁 WebView。保留最小导航状态供用户重开，Cookie 继续由独立数据目录管理。处理 `onRenderProcessGone` 后销毁旧实例，用户回到网页时再重建。阅读器的 `ReaderWebView` 由当前阅读界面管理，网页进程的释放操作不应控制阅读 WebView 或调用全局计时器暂停。[Android WebView 资源管理](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview)
- 现有 Kokoro 模型槽位在进程内由互斥租约管理，释放 native 引擎后才归还。加载前根据整机可用内存、系统阈值和模型大小判定，取 256 MiB、系统阈值、两倍模型权重加 64 MiB 三者的最大值。保留现有语音服务和系统 TTS 回退流程，网页进程启动时避免初始化 Kokoro，单纯切到网页或锁屏时继续活跃朗读。
- 当前 `SpeechService` 依赖的 `TRIM_MEMORY_RUNNING_CRITICAL`、`TRIM_MEMORY_COMPLETE` 和 `onLowMemory` 在 API 34 起已不分发。Android 16 上应验证模型加载前的内存守卫、合成失败时的既有回退，以及网页不可见时可重建资源的释放。网页 UI 可以响应 `TRIM_MEMORY_UI_HIDDEN` 与 `TRIM_MEMORY_BACKGROUND`。阅读器 `AndroidView` 当前未显式声明 `onRelease`，正式改造可单独验证阅读界面永久移除时释放阅读 WebView，并保留前台语音服务独立运行。[Android 内存管理](https://developer.android.com/topic/performance/memory/manage-app-memory) [Android ComponentCallbacks](https://developer.android.com/reference/android/content/ComponentCallbacks#onLowMemory())
- 正式改造后验收应同时记录网页宿主、阅读器与语音宿主、各自 Chromium 渲染器的 PSS，以及 `availMem`、Kokoro 模型加载耗时和回退结果。覆盖先朗读后进网页、先网页后打开阅读器、锁屏朗读、网页销毁、渲染器终止恢复及低内存设备，判断真实并发峰值。

## 集成决策

有明确 API 标识的资源可以继续沿用现有原生下载。正式设计应分别保留网页资源标识与 API `id:hash`，只有服务明确提供对应关系时才从网页结果进入 API 下载。未获得对应关系的资源可沿用第二阶段的网页下载，由主进程接收已落盘文件并复用现有导入流程。这次结论仅覆盖一个账号、一个授权样本和一个主站。

测试前后设备服务器配置文件的 SHA-256 一致。测试退出后探针缓存中已无本次临时 EPUB。本阶段交付独立测试探针和本报告，正式改造待确认。
