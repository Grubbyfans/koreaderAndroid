# 阶段 A：现有源码分析与架构设计报告

对应方案：`docs/静读在线书库统一化与多书源扩展技术方案.md`（v1.0）
阶段：A（核实源码、接口、许可证、可复用模块，设计抽象）
日期：2026-11-24
分支：`feature-zlibrary`，基线 HEAD `8795ab4`
状态：**仅分析与设计，未修改任何业务源码；完成后暂停等待确认**

---

## 1. 结论摘要

1. **方案整体可实施**，但方案中的 `BookProvider` / `ProviderRegistry` / `RemoteBook` / `BookDownloadManager` 等类型均为**待新增**，当前源码零实现；方案中「已有 CloudBackend 可复用」这一假设成立，但它是**云存储后端抽象**，不是**书源抽象**，不能把 Z-Library 塞进 `CloudBackend`。
2. **现有分层清晰**：`core`（协议 + 云后端，纯 Kotlin）→ `reader`（凭据、缓存、导入、同步编排）→ `app`（Compose UI）。新增书源层应作为**并列的新层**，通过 `CloudBookProvider` 委托现有 `CloudSync`，不改动 `core/cloud/*` 与 `core/protocol/*`。
3. **在线书库现状**不是独立页面，而是书架 Tab 内的「本地 / 云端」二级切换；底部导航只有「书架、设置」两项。方案 §3 文字要求「不新增底部一级入口」，但线框图展示了三个底部入口，**方案自身存在不一致**，需确认（见 §7）。
4. **参考仓库** `ZlibraryKO/zlibrary.koplugin` 固定 commit `b7eacbab6cd7e64259857424749f0e2f26ff7bc5`，许可证 **AGPL-3.0（强 copyleft）**。只能借鉴协议语义与算法思想并干净重写，不得复制 Lua 代码文本；否则分发时会触发 AGPL 同许可证义务。
5. **最小改动路径**：新增 Provider 抽象 + 下载管理 + 在线 UI，复用现有导入/校验/封面/凭据加密/前台服务/云端上传；数据库仅增量新增 2 张表（下载任务、远程映射），配置与缓存用文件而非新表；所有阅读、朗读、KOReader 同步代码保持零改动。
6. **用户新增要求已纳入计划**：后续每完成一个开发节点即做一次本地提交，提交信息使用简体中文；阶段 A 报告单独提交。

---

## 2. 分析范围与方法

- 完整阅读技术方案原文（329 行）。
- 通读项目 Gradle 配置、核心源码目录、测试与文档，重点核对 `core/cloud`、`reader`、`app` 三层。
- 对百度网盘 / WebDAV 的认证、列目录、上传、下载、缓存、索引与同步链路逐层追踪调用关系。
- 通过 `git ls-remote` 固定参考仓库 HEAD，逐文件读取 LICENSE、配置、API、发现、登录、下载、UI 关键实现；检查本地副本有效性。
- 所有结论基于源码事实；方案假设与事实不符处单列，不擅自按方案创建重复模块。

---

## 3. 现有架构基线（事实）

### 3.1 构建与模块

| 项目 | 事实 |
|---|---|
| 模块 | `settings.gradle.kts` 仅包含 `:app`、`:core`、`:reader`、`:speech`、`:novel-text`；`reader-web/`、`tests/`、`docs/` 为普通目录 |
| 依赖方向 | `app → core/reader/speech`；`reader/speech → core`；`core → novel-text`，无循环依赖 |
| 包名 | `com.yuezhou.reader`；`ttsTest` flavor 后缀 `.tts.test`（`com.yuezhou.reader.tts.test`），应用名「静读测试版」；正式版「静读」 |
| SDK | compileSdk 37 / minSdk 26 / targetSdk 36；Kotlin 2.4.20 + AGP 9.3.1（AGP 内置 Kotlin） |
| UI | Jetpack Compose + Material3，单 Activity 手写状态分发；`NavHost` 依赖已声明但未使用；无 Xml layout |
| 网络 / 数据 | OkHttp 5.3.0、kotlinx.serialization 1.9.0、Room 2.8.3、DataStore、coroutines 1.10.2 |
| 已有但未使用 | `androidx.lifecycle.viewmodel-compose`、`lifecycle-runtime-compose`、`navigation-compose` 已在 app 依赖中，源码未使用 → **新增 ViewModel 无需引入新依赖** |
| 无 | Hilt/Koin、WorkManager、Retrofit/Ktor、Coil/Glide、EventBus |
| 构建/测试命令 | `./gradlew :app:assembleTtsTestDebug`（默认测试版）、`./gradlew test`、`:app:lintTtsTestDebug`、`:app:assembleProductionDebug/Release`；`node tests/*.test.mjs` |

### 3.2 页面与入口

- `app/src/main/java/com/yuezhou/reader/MainActivity.kt`（2172 行）承载全部主界面：`BookshelfScreen`（167）、`BookScopeSwitch`（1118）、`CloudBookshelfContent`（1153）、`CloudBookCard`（1250）、`SettingsPage`（1381）、`SyncLogPage`（1737）、`BottomNavBar`（2140）。
- `Tab{书架,设置}`（1010）、`BookScope{本地,云端}`（1016）；「在线书库」= 书架页选择「云端」，不是一级入口。
- 状态全部为 `remember { mutableStateOf }` 的进程内状态（云端列表、加载、错误、详情、下载任务等），无 ViewModel、无 `rememberSaveable`、无按书源隔离的状态容器；重启不恢复云端列表。
- 云端列表固定 `LazyVerticalGrid(GridCells.Fixed(3))`，**无搜索框、无列表/网格切换、无进度百分比展示**。
- 阅读器 `ReaderActivity.kt`（1798 行）独立 Activity；朗读为 `SpeechService.kt` 前台服务；同步日志为页面内切换。

### 3.3 本地书籍导入与身份规则

- 入口：`MainActivity.pickEpub` → `EpubImporter.import(uri)`；目录扫描 `LocalScanner`；外部 `ACTION_VIEW` → `OpenEpubActivity`。
- 核心：`reader/EpubImporter.kt`（Mutex 串行、`files/epub/` staging → `EpubImportFiles.validate()` → `CoverExtractor.extract()/extractTitle()` → `install()` ATOMIC_MOVE+REPLACE_EXISTING → `LibraryStore.registerBook()`）。
- 校验：`CoverExtractor.validatePackage()` 检查 ZIP 中 `META-INF/container.xml`（≤64 KiB）与 OPF 存在且非空；**不校验 mimetype 首条目、不做 ZIP bomb / 路径穿越限制** → 方案 §7 的校验要求需新增独立校验器。
- 存储：`files/epub/<fileName>`、`files/library/library.json`、`files/library/covers/<bookId>.json`、`files/library/progress/<bookId>.json`、`display_titles.json`；隐藏书籍与下载目录用 SharedPreferences + SAF。
- 身份：`bookId = 文件名去 .epub`（`core/protocol/BookNames.kt` 的 `localId()/cloudId()`），无 trim/归一化/内容摘要；去重仅「同名覆盖 + 逐字节相同跳过」。**无 SHA-256、无账号维度、无 remoteId**。

### 3.4 阅读进度与 KOReader 同步

- 位置算法集中在 `core/position/*`（`XPointerCodec`、`PositionCodec`、`CrengineWhitespace`、`DomNode` 等）与 `app/src/main/assets/reader/xpointer.js`、桥接 `bridge.js`；CFI ↔ XPointer 转换在 `ReaderWebView.kt`（333–337）。**属强制保护范围，不改**。
- 进度文件 `files/library/progress/<bookId>.json`，`ProgressRecords` schemaVersion=2，字段含 `xpointer/percent/size/deviceId/revision/writeId/updatedAt`；上传时 `revision = remote.revision + 1`。
- 触发：进入阅读器首次 Relocated 后 `autoPullRemoteProgress()`（仅一次）；`ON_PAUSE` / `leaveReader()` → `flushProgress()` + `autoPushProgress()`；手动同步为两步交互，不自动裁决冲突。
- 云端目录与索引：`library.json`（schemaVersion=2，books[]：`bookId/title/fileName/path=books/<fileName>/size/uploadedAt/status/coverPath/coverVersion`）、`books/`、`covers/`、`progress/`；`covers/<bookId>.json` 明确禁止 `contentSha256`。**协议不可改**。
- 同步日志表 `sync_log`；冲突不按 percent/updatedAt/revision 自动覆盖。

### 3.5 数据库

`reader/db/AppDatabase.kt`：`@Database(version=5, exportSchema=false)`，库名 `yuezhou_reader.db`，4 张表：`pending_publication`、`diagnostic_event`、`speech_position`、`sync_log`。迁移 1→2（隔离 backendId）、2→3、3→4、4→5（ALTER TABLE 加两列）均为非破坏性。阅读进度刻意保留 JSON 以避免与 KOReader 双数据源。**无任何 online/provider/download/mapping 表**。

---

## 4. 百度网盘 / WebDAV 实现剖析

### 4.1 共用抽象

`core/src/main/java/com/yuezhou/reader/core/cloud/CloudBackend.kt` 定义云存储后端接口：`kind / backendId / root / capabilities() / ensureAuthorized() / list(path, continuation) / stat(path) / mkdir(path) / downloadBytes(path, maxBytes) / downloadTo(path, dest, maxBytes, onProgress) / uploadSmallJson(path, raw, precondition) / upload(path, file, precondition, onProgress) / delete(path, precondition)`。

- `BaiduBackend`（`backendId = "baidu:$root:$userId"`）与 `WebDavBackend`（`backendId = "webdav:$root:$username"`）实现之；`CloudService(backend)` 负责 `index/uploadBook/uploadProgress/downloadProgress/deleteBook/repairPublication/discoverExternalBooks`，协议路径硬编码。
- `CloudSync.backend()` 是硬编码工厂：按 `CloudCredentials.activeBackend` 选择，自动模式**百度优先**。
- 新增书源不要改 `BackendKind`、`CloudCredentials` 既有字段语义、`CloudSync.backend()` 的选择逻辑；`CloudBookProvider` 直接委托 `CloudSync`。

### 4.2 百度网盘

- OAuth 2.0 **OOB**：`https://openapi.baidu.com/oauth/2.0/authorize`（`response_type=code&redirect_uri=oob&scope=basic,netdisk&display=mobile&state=UUID`），外部浏览器授权后回填授权码；换 token 校验 scope 与 `expires_in`；`uinfo` 取 `uk` 作稳定 userId。
- **现状无 refresh_token 使用**：`TokenResult.refreshToken` 被解析但丢弃；过期即要求重新授权。新书源若需刷新令牌，必须独立存储，不能塞进现有 `CloudCredentials` 改语义。
- API：`https://pan.baidu.com/rest/2.0/xpan/{file,multimedia,nas}`；动态分片上传 host 限定 `*.pcs.baidu.com`；下载 dlink 只允许 `*.baidu.com / *.baidupcs.com / *.baidubce.com` 的 HTTPS，含重定向与 stale 重试。
- 错误映射见 `BaiduErrors.kt`；`CloudException` 区分 `Failed / Uncertain`。

### 4.3 WebDAV

- `Authorization: Basic`，URL/账号密码存 `CloudCredentials`，经 `CredentialCrypto`（Android Keystore + AES/GCM，别名 `yuezhou_cloud_credentials`）加密写入 `files/cloud_credentials`。
- `PROPFIND Depth:0/1`、`PUT`、`GET`、`MKCOL`、`DELETE`；禁止重定向；路径经 `DavPath.safe()/encode()` 处理；`DavXml` 用 `XmlPullParser` 解析 multistatus；`continuation != null` 抛 `UNSUPPORTED`。
- 全局 `network_security_config.xml` 设 `cleartextTrafficPermitted=true`（为局域网 HTTP WebDAV）；**新书源必须自行强制 HTTPS，不得依赖该宽松配置**。

### 4.4 云端书籍映射与下载

- 身份 = 云端文件名去 `.epub`；`CloudSync.listBooks()` 用 `LibraryStore.snapshot()` 的 bookId 集合判断「已下载」（右上角 ✓），**无 SHA-256、无本地删除回写**。
- `RemoteBook`（`CloudSync.kt:56`，6 字段）是云端列表 UI 模型，**不是**方案中的富模型；新增模型应另起名（如 `OnlineRemoteBook` 或放在 `core/online/model` 同名包，禁止改 `CloudSync.RemoteBook`）。
- `CloudSync.downloadBook()`：`stat()` 校正大小 → `TempFileCleanup.clean()` → `download-*.part` 流式下载 → `CoverExtractor.validatePackage()` → 可选 `DownloadDirectory.publish()` → `LibraryStore.registerBook()` → `invalidateListCache()`；`downloadMutex` 串行；取消靠 `ensureActive()`。无队列持久化、无断点续传、无自动重试。
- 封面：`CloudSync.fetchCover()` 三级缓存（进程内 `ConcurrentHashMap` → `CloudCache` 按 `coverVersion` 磁盘 → 后端下载 `covers/<bookId>.json`），失败写占位防抖。
- 上传：`BookUploadQueue` → `CloudSync.pushLocal()` → `CloudService.uploadBook()`，失败写 `pending_publication` 回执。

### 4.5 可复用 UI 组件

`CoverImage()` / `CoverPlaceholder()` / `CloudBookCard()` / `EmptyState()` / `BookScopeSwitch()` / `BookUploadQueue` 的 `MutableStateFlow` 任务态 / 下载进度弹窗 / `DownloadForegroundService` 前台通知 / 诊断导出。新书源需要的「按来源独立状态、搜索、分页、布局切换」需新建状态容器，现有组件只能复用卡片与封面管线。

---

## 5. 参考项目核实（zlibrary.koplugin）

### 5.1 固定版本与许可证

- 完整 commit：`b7eacbab6cd7e64259857424749f0e2f26ff7bc5`（`git ls-remote https://github.com/ZlibraryKO/zlibrary.koplugin HEAD` 核实）。
- LICENSE：**GNU Affero General Public License v3（AGPL-3.0）**。分发衍生作品须同许可证授权、提供完整源码并保留版权/修改声明；网络交互服务亦触发源码提供义务。
- 结论：**不得复制 Lua 源码文本到 Android 工程**；仅参考端点路径、请求语义、字段映射、错误分类、发现策略，以 Kotlin 干净重写。商业闭源场景需法务确认。
- 本地 `/Users/zhou/Downloads/zlibrary.koplugin` 为旧版（52 文件、旧式单文件模块），与固定 commit 文件树不一致，**不可作为接口依据**。

### 5.2 关键实现事实（供 Kotlin 重写参考）

| 能力 | 事实 |
|---|---|
| 搜索 | `POST /eapi/book/search`；表单 `message/page/limit(=30)/languages[i]/extensions[i]/order[1]/content_type=article`；**无需登录** |
| 详情 | `GET /eapi/book/{id}/{hash}` |
| 下载链接 | `GET /eapi/book/{id}/{hash}/file` → `data.file.downloadLink`（成功标志 `success=1`） |
| 额度 | `GET /eapi/user/profile` → `downloads_today / downloads_limit`（按日） |
| 登录 | `POST /rpc.php`（`action=login&gg_json_mode=1&...`）失败回退 `POST /eapi/user/login`；凭证拒绝才回退，限流不回退；Cookie `remix_userid/userkey` |
| 动态域名 | `GET /eapi/info/domains/singlelogin`；从 jsdelivr / raw.githubusercontent / CDN 拉 `assets/domains.json` |
| 发现策略 | 种子 + 动态域名；3 并发竞速；缓存 600 秒；屏蔽 bot challenge 镜像；过滤 `.onion` |
| 错误分类 | 区分凭证拒绝、限流、封禁、bot challenge、额度耗尽（额度错误**不能**当认证错误清会话） |
| 数据映射 | `id/hash/title/author/year/format/filesize/lang/cover/description/publisher/identifier` |
| 下载 | `.downloading` 临时文件 + 重试 + `_uniqueDestPath()`；**无 Range 断点续传、无 EPUB 结构校验** |
| 运行时依赖 | Lua/LuaJIT、socket/ltn12、UIManager/Trapper/fork 子进程、luasettings、lfs、gettext —— 全部需重写为 Kotlin 协程/OkHttp/Compose/DataStore/Room/Keystore/Android resources |

### 5.3 与技术方案假设的差异

1. 方案 `pageSize=20`；参考实现固定 `limit=30`。
2. 方案 `sort="relevance"` 字符串；参考实现为 `order[1]` 数组。
3. 方案 JSON 配置结构（`schemaVersion/servers/network/endpoints/preferences`）在参考仓库**不存在**，属全新设计，需自建校验与转换层。
4. 方案 `RemoteBook.isbn/resourceHash` 在参考仓库无直接对应（`identifier` 可能是 ISBN，`hash` 是书 hash 非文件 SHA-256）；SHA-256 三级去重需自研。
5. 方案要求 EPUB ZIP/mimetype/container.xml/OPF 校验、ZIP bomb 与路径穿越防护；参考仓库无此能力，现有 `CoverExtractor.validatePackage()` 也不完整。
6. 方案要求断点续传需确认服务支持；参考实现未体现 Range 语义，**不可假设支持**。
7. 文案需明确：未登录可搜索，**下载才需登录且计额度**。
8. 方案 `assets/providers/zlibrary.default.json`；参考仓库只有 `assets/domains.json`（域名列表）。
9. 非公开 API 不保证可用；bot challenge 不得绕过；仅测试/获取具有合法授权的资源。

---

## 6. 技术方案兼容性核对表

| 方案假设 / 要求 | 实际项目 | 结论 |
|---|---|---|
| `CloudBackend` 可复用 | 存在且完整，两实现 + `CloudService` | ✅ 一致，但语义是云存储后端，非书源 |
| `BookProvider` / `ProviderRegistry` / `ProviderCapability` / `CloudBookProvider` / `CatalogBookProvider` | Kotlin 零命中 | ❌ 全部新增 |
| `RemoteBook` | 仅 `CloudSync.RemoteBook`（6 字段 UI 模型） | ⚠️ 名称冲突，另建模型，不改原类 |
| `BookDownloadManager` / `DownloadTask` / `DownloadRepository` / `OnlineBookCache` | 零实现；只有 UI 内存 `downloadTask` | ❌ 全部新增 |
| `remoteId` / `accountScope` / `fileHash` 映射 | 无；身份为文件名去扩展名 | ❌ 新增映射表，原 bookId 规则不动 |
| 「不新增底部一级入口」 | 底部仅「书架/设置」，云端为二级切换 | ⚠️ 与线框图三入口矛盾，需确认（§7） |
| 云端搜索 / 布局切换 / 进度展示 | 无 | ❌ 新增 UI 状态与组件，复用卡片 |
| 数据库增量迁移 | Room v5、4 表、非破坏迁移 | ✅ 可加 6→7（或 5→6）增量表 |
| 复用现有认证 | 百度 OAuth OOB、WebDAV Basic + Keystore 加密 | ✅ 不改；新源独立凭证存储 |
| 复用现有导入 | `EpubImporter` + `CoverExtractor.validatePackage()` | ⚠️ 需补 EPUB 安全校验与去重前置 |
| 后台下载机制 | 前台 Service + 协程，无 WorkManager | ✅ 复用 `DownloadForegroundService` 模式 |
| 测试版包名 | `com.yuezhou.reader.tts.test`，名称「静读测试版」 | ✅ 保持 |
| 强制保护范围 | foliate-js、Sherpa/Kokoro、TTS、位置算法、KOReader 协议与云端索引 | ✅ 全部零改动 |
| ViewModel | 依赖已声明但未使用 | ✅ 新增在线页面可直接用，无需新依赖 |

---

## 7. 需用户确认的修订点（保持兼容优先）

1. **导航入口**（重要）：方案 §3 文字说「不新增底部一级入口」，线框图却画了「本地书库｜在线书库｜设置」三个底部入口。建议**保持现有两项底部导航**，在书架页「云端」作用域内新增书源横条（百度网盘 / WebDAV / Z-Library），「下载管理」「书源管理」作为在线页顶部按钮或设置页子入口；不改动 `BottomNavBar`。请确认。
2. **模型命名**：为避免影响 `CloudSync.RemoteBook`，新增模型置于 `core/online/model` 并命名 `RemoteBook`（不同包），或统一叫 `OnlineBook`。建议后者，减少歧义。请确认。
3. **数据库范围**：方案建议 4 张新表；建议先只新增 `online_download_task`、`online_book_mapping` 两张（下载恢复与去重需要），书源配置用 `files/providers/configs/*.json`（天然支持导入/回滚），搜索/详情缓存用内存 + 现有磁盘封面缓存。请确认。
4. **Z-Library 默认值**：按参考仓库修正为 `limit=30`、`order` 数组、`POST /eapi/book/search`，并在 UI 明示「未登录可搜索，下载需登录」。请确认。
5. **许可与合规**：参考仓库 AGPL-3.0，采用干净重写；是否需要对「借鉴协议语义」留存设计记录以备法务审查。请确认。
6. **EPUB 文件名冲突**：现有 `EpubImportFiles.install()` 为同名覆盖。方案要求「不同译本不得自动合并、不得静默覆盖」，需由新导入协调器在调用现有导入器**之前**生成唯一文件名（如追加短 hash），并建立 `remoteId → localBookId` 映射；原导入器保持不变。请确认该策略。

---

## 8. 可复用模块清单（优先复用，避免重构）

| 模块 | 路径 | 复用方式 |
|---|---|---|
| 云后端抽象与实现 | `core/.../cloud/CloudBackend.kt`、`baidu/*`、`webdav/*` | `BaiduBookProvider` / `WebDavBookProvider` 通过 `CloudSync` 间接委托；不改接口 |
| 云端协议编排 | `core/.../cloud/CloudService.kt` | 仅云书源复用；协议路径/索引结构保持原样 |
| 云端业务编排 | `reader/.../CloudSync.kt` | `listBooks/refreshLibrary/fetchCover/downloadBook/progressInfo/pushLocal` 委托 |
| 凭据加密 | `reader/.../CredentialCrypto.kt`、`CloudCredentialStore.kt` | 复用加密模式；新书源用**独立文件与独立 Keystore 别名** |
| 云端缓存 | `reader/.../CloudCache.kt` | 封面磁盘缓存与按 backend 隔离思路 |
| 本地导入 | `reader/.../EpubImporter.kt`、`EpubImportFiles.kt`、`CoverExtractor.kt` | 下载完成后调用；**只在前面加唯一文件名与安全校验** |
| 书库登记 | `reader/.../LibraryStore.kt` | `registerBook()/snapshot()`，语义不改 |
| 文件发布 | `reader/.../DownloadDirectory.kt`、`TempFileCleanup.kt` | SAF 发布与临时文件清理 |
| 上传回执 | `reader/.../PendingPublicationStore.kt`、`BookUploadQueue.kt` | 手动上传云端与失败回执复用 |
| 前台通知 | `app/.../DownloadForegroundService.kt` | 后台下载通知与进度更新模式 |
| ID 规则 | `core/.../protocol/BookNames.kt` | 本地 bookId 规则不动；remoteId 独立映射 |
| OkHttp 请求 / 路径安全 | `core/.../cloud/baidu/BaiduHttp.kt`、`webdav/DavPath.kt` | Z-Library 网络层参考其超时、重定向、主机白名单与路径编码 |
| Compose 组件 | `CoverImage/CloudBookCard/EmptyState/BookScopeSwitch` | 在线 UI 直接复用或轻量包装 |
| ViewModel 依赖 | `libs.androidx.lifecycle.viewmodel.compose`（已声明） | 在线页面状态容器，零新增依赖 |
| 测试基建 | `core/src/test/.../FakeBackend.kt`、`CloudServiceTest.kt` | 新 Provider/下载状态机单测仿此结构 |

---

## 9. 新增 / 修改文件清单（按实际模块路径映射方案目录）

> 方案中的 `data/online/`、`ui/online/` 映射到现有 `core`、`reader`、`app` 三个模块下的 `com.yuezhou.reader.*` 包；**不新建 Gradle 模块**。

### 9.1 新增（core，纯 Kotlin，无 Android Context）

| 文件 | 职责 |
|---|---|
| `core/src/main/java/com/yuezhou/reader/core/online/model/OnlineBook.kt` | 远程书富模型（providerId/remoteId/title/authors/coverUrl/...） |
| `.../online/model/SearchQuery.kt` / `BookPage.kt` / `ConnectionState.kt` / `ProviderError.kt` / `BookDownloadRequest.kt` / `DownloadResult.kt` / `UploadResult.kt` | 方案 §5 模型与结果类型 |
| `.../online/provider/BookProvider.kt` / `CloudBookProvider.kt` / `CatalogBookProvider.kt` / `ProviderCapability.kt` | 能力声明与接口；**不得让检索书源实现 CloudBackend** |
| `.../online/download/DownloadTask.kt` / `DownloadState.kt` | 任务字段与状态机（QUEUED→...→COMPLETED/FAILED/CANCELLED/IMPORT_FAILED） |
| `.../online/zlibrary/ZLibraryConfig.kt` | 配置模型、schema/版本/HTTPS 主机校验、优先级合并 |
| `.../online/zlibrary/ZLibraryApi.kt` | 端点请求封装（搜索/详情/下载链接/额度/登录/动态域名） |
| `.../online/zlibrary/ZLibraryAuth.kt` | 登录、会话 Cookie、凭证拒绝与限流分类（不复制参考实现代码） |
| `.../online/zlibrary/ZLibraryDiscovery.kt` | 种子 + 动态域名、并发探测、缓存、主机校验 |
| `.../online/zlibrary/ZLibraryMapper.kt` | 响应 JSON → `OnlineBook` 字段映射 |
| `.../online/zlibrary/ZLibraryProvider.kt` | `CatalogBookProvider` 实现 |
| `.../online/security/EpubValidator.kt` | HTTP 状态/文件类型/大小/ZIP/mimetype/container.xml/OPF、ZIP bomb 与路径穿越防护 |
| `app/src/main/assets/providers/zlibrary.default.json` | 内置默认配置（真实端点，非占位） |

### 9.2 新增（reader，Android Context）

| 文件 | 职责 |
|---|---|
| `reader/src/main/java/com/yuezhou/reader/reader/online/provider/ProviderRegistry.kt` | 书源注册、启停、默认源、能力查询 |
| `.../online/cloud/BaiduBookProvider.kt` / `WebDavBookProvider.kt` | 委托 `CloudSync`，补 `listBooks/getBookDetail/download/upload` |
| `.../online/credential/OnlineCredentialStore.kt` | 新书源凭证独立加密存储（Keystore 新别名） |
| `.../online/cache/OnlineBookCache.kt` | 搜索结果内存 TTL、详情 TTL、服务器探测短缓存 |
| `.../online/mapping/OnlineBookMappingStore.kt` | remoteId ↔ localBookId ↔ fileHash 查询与更新 |
| `.../online/download/BookDownloadManager.kt` / `DownloadRepository.kt` | 并发 2、进度节流、重试策略、取消、重启恢复、幂等 |
| `.../online/import/OnlineImportCoordinator.kt` | 三级去重、唯一文件名、调用现有导入、导入失败独立重试 |
| `.../online/upload/CloudUploadBridge.kt` | 复用 `CloudSync.pushLocal()`，两云端分别记录结果 |
| `reader/.../db/OnlineDownloadTaskEntity.kt` / `OnlineDownloadTaskDao.kt` / `OnlineBookMappingEntity.kt` / `OnlineBookMappingDao.kt` | Room 增量表与 DAO |
| `reader/src/main/java/com/yuezhou/reader/reader/online/OnlineFiles.kt` | `files/downloads/temporary`、`pending_import`、`files/providers/configs` 目录 |

### 9.3 新增（app，Compose UI）

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryScreen.kt` / `OnlineLibraryViewModel.kt` | 统一入口、书源横条、按源独立状态 |
| `.../online/CloudLibraryScreen.kt` | 云存储型书源页（复用 `CloudBookCard`） |
| `.../online/CatalogLibraryScreen.kt` | 检索型书源页（搜索/筛选/分页/布局切换） |
| `.../online/BookDetailScreen.kt` | 详情、自动导入开关、下载入口 |
| `.../online/DownloadManagerScreen.kt` | 下载任务列表、取消/重试/阅读/上传 |
| `.../online/ProviderSettingsScreen.kt` | 书源管理与通用设置、Z-Library 子配置、JSON 导入 |
| `app/src/main/res/values/strings.xml`（修改） | 新增文案；测试版 strings 保持包名与名称不变 |

### 9.4 修改（严格最小）

| 文件 | 修改内容 |
|---|---|
| `app/.../MainActivity.kt` | 仅在「云端」作用域内接入 `OnlineLibraryScreen`，保留 `BookScopeSwitch`、`CloudBookshelfContent` 原行为；新增下载/书源设置的页面切换入口；不重构现有 Composable |
| `reader/.../db/AppDatabase.kt` | `version 5 → 6`，`entities` 增加 2 个 Entity，`addMigrations(Migration(5,6))` 仅 `CREATE TABLE IF NOT EXISTS`；不改既有表 |
| `app/src/main/AndroidManifest.xml` | 仅当采用独立下载前台服务类型时需要；现有 `DownloadForegroundService` 复用则不改 |
| `app/build.gradle.kts`、`reader/build.gradle.kts`、`core/build.gradle.kts` | 原则上不改；如新增依赖需先确认版本目录已有条目 |

### 9.5 明确「不改」

`core/cloud/*`、`core/protocol/*`、`core/position/*`、`ReaderActivity.kt`、`ReaderWebView.kt`、`EpubImporter.kt`、`EpubImportFiles.kt`、`CoverExtractor.kt`、`LibraryStore.kt`、`speech/*`、`novel-text/*`、`app/src/main/assets/reader/*`、`settings.gradle.kts`、`network_security_config.xml`、既有迁移与所有测试版配置。

### 9.6 新增测试

- `core/src/test/.../online/`：`ZLibraryConfigTest`（校验/回滚/优先级）、`ZLibraryMapperTest`、`ZLibraryErrorClassificationTest`、`DownloadStateMachineTest`、`EpubValidatorTest`（ZIP bomb、路径穿越、HTML 错误页）。
- `reader/src/test/.../online/`：`OnlineImportCoordinatorTest`（去重、唯一文件名、幂等）、`OnlineBookMappingStoreTest`（如用 Room 需 Robolectric 或纯逻辑抽取）。
- `tests/` 现有 Node 测试不改；阶段 G 追加在线 UI 关键交互的手工验收脚本/清单。

---

## 10. 风险与缓解

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 参考仓库 AGPL-3.0 强 copyleft | 分发/商用触发同许可证义务 | 只借鉴协议语义，Kotlin 干净重写；不复制代码文本；必要时法务确认 |
| R2 | Z-Library 非公开 API 变动、域名封锁、bot challenge | 书源随时不可用 | 配置化端点 + 动态域名 + 发现缓存；失败不影响其他书源；不绕过反爬；不承诺稳定性 |
| R3 | 内容合法性 | 法律风险 | 仅测试/获取具有合法授权的资源；UI 提示合规责任 |
| R4 | `CloudBackend` 与 `BookProvider` 语义混淆 | 错误抽象导致大改 | 检索源只实现 `CatalogBookProvider`；云源通过 `CloudSync` 委托，不改 `cloud/protocol` |
| R5 | 同名 EPUB 静默覆盖、译本合并 | 原有书籍/进度丢失 | 新协调器前置唯一文件名 + 映射表；三选一去重弹窗；保留旧书进度 |
| R6 | `bookId` 规则被改动 | KOReader 同步与进度错乱 | 保持 `BookNames.localId/cloudId` 与 `library.json` 完全不变；remoteId 独立存储 |
| R7 | 现有 EPUB 校验不足 | 损坏文件/HTML 错误页入库 | 新增 `EpubValidator`，限制解压体积与路径，拒绝错误页；校验在导入前 |
| R8 | 全局允许明文 HTTP | 新书源凭证被中间人窃取 | Z-Library 强制 HTTPS；重定向校验 Location 主机；凭证不向未知主机转发 |
| R9 | 凭证等价物泄露（Cookie、签名 downloadLink） | 账号被盗 | 独立 Keystore 存储；日志与配置导出脱敏；与现有 `CredentialCrypto` 同级别保护 |
| R10 | 下载恢复重复消耗额度 | 用户额度损失 | 重启只恢复队列；`IMPORT_FAILED` 只重试导入不重新下载；额度/认证/限流错误不自动重试 |
| R11 | Room 迁移破坏旧数据 | 原有书籍/进度丢失 | 仅 `CREATE TABLE IF NOT EXISTS` 增量；升级路径实测；`exportSchema` 现状保持 |
| R12 | `MainActivity` 2172 行大文件 | 接入新 UI 易引入回归 | 只增不改原 Composable；新页面独立文件；每节点编译 + 冒烟测试 |
| R13 | 现有云端无搜索/进度展示 | 方案线框图与现状差距 | 新 UI 状态容器承载；不修改 `CloudBookshelfContent` 的原有行为 |
| R14 | 百度无 refresh_token | 新源若需刷新无现成承载 | 新凭证模型独立字段；不改 `CloudCredentials` 语义 |
| R15 | 文档滞后于代码 | 按文档实施会重复开发 | 以源码为准（`功能完成情况.md`、`sync-model.md` 部分待办已实现） |

---

## 11. 详细实施计划（每节点完成即本地提交，提交信息简体中文）

| 节点 | 对应阶段 | 主要工作 | 涉及文件 | 验收 / 提交 |
|---|---|---|---|---|
| **A**（本次） | A | 源码与参考项目核实、兼容性设计、风险与计划 | 本报告 | 报告落盘 → 本地提交 → 暂停等待确认 |
| **B1** | B | Provider 抽象、模型、能力枚举；`ProviderRegistry`；`BaiduBookProvider`/`WebDavBookProvider` 委托 `CloudSync` | §9.1 模型/接口、§9.2 provider 与 registry | `./gradlew test` + `:app:assembleTtsTestDebug`；云书库回归截图；提交 |
| **B2** | B | 统一入口：书源横条、按源独立状态容器；保留原云端作用域行为 | `MainActivity.kt`（最小）、`OnlineLibraryScreen/ViewModel`、`CloudLibraryScreen` | 切换书源/配置变化不崩溃；本地/云端原行为一致；提交 |
| **C1** | C | Z-Library 配置模型、内置 JSON、校验与导入回滚、独立凭证存储 | `ZLibraryConfig.kt`、`zlibrary.default.json`、`OnlineCredentialStore.kt`、`ProviderSettingsScreen.kt` | 非法配置不覆盖、凭证不落明文；配置相关单测；提交 |
| **C2** | C | 网络层：发现、登录、搜索/详情/链接/额度 API、错误分类；单测 | `ZLibraryApi/Auth/Discovery.kt` | 单测覆盖端点构造、重定向主机校验、错误分类；提交 |
| **D** | D | 检索页：搜索、语言/格式/排序筛选、分页去重、列表/网格、详情与封面；状态恢复 | `CatalogLibraryScreen.kt`、`BookDetailScreen.kt`、`OnlineBookCache.kt` | 分页无重复/过期请求污染；断网保留列表；提交 |
| **E1** | E | 下载任务表、状态机、并发 2、进度节流、取消、通知、重启恢复 | `DownloadTask/State`、`BookDownloadManager`、`DownloadRepository`、`AppDatabase(5→6)`、`DownloadManagerScreen.kt` | 并发下载不卡 UI；退出重进恢复队列；提交 |
| **E2** | E | `EpubValidator`、三级去重、唯一文件名、导入协调器、导入失败独立重试 | `EpubValidator.kt`、`OnlineImportCoordinator.kt`、`OnlineBookMapping*` | 损坏/HTML 页拒绝入库；不静默覆盖；幂等重试；提交 |
| **F** | F | 完成后手动上传百度/WebDAV，复用 `CloudSync.pushLocal()` 与索引；KOReader 同步兼容验证 | `CloudUploadBridge.kt`、下载完成 UI | 两云端分别记结果；进度双向同步与原有测试通过；提交 |
| **G** | G | 全量回归、安全测试（凭证/重定向/ZIP/空间）、性能、打包；修订文档 | 测试清单与打包产物 | `./gradlew test`、`:app:lintTtsTestDebug`、`:app:assembleTtsTestDebug`、`:app:assembleProductionRelease`；逐主题提交 |

**每节点通用门禁**：编译通过 → 单元测试通过 → 测试版 APK 可安装且启动 → 原有阅读/朗读/同步冒烟通过 → 简体中文 commit。出现阻塞则保留节点未完成状态并报告，不跨节点合并提交。

---

## 12. 验收方式

按方案 §12 逐条执行，并补充：

- **回归基线**：节点 B1 后即跑一次「本地 EPUB 导入阅读 / 原生 TTS / Kokoro 朗读 / 百度上传下载 / WebDAV 上传下载 / KOReader 进度双向同步 / 原有书籍书签进度无丢失」，每节点结束复跑关键项。
- **书源隔离**：断开 Z-Library 服务后，百度/WebDAV 仍可用；反之亦然。
- **下载安全**：用构造的 HTML 错误页、超限 ZIP、路径穿越 ZIP、同名不同内容 EPUB 验证拒绝/去重行为。
- **额度约束**：模拟重启，确认不自动重新申请下载、`IMPORT_FAILED` 只重试导入。
- **迁移**：从当前 v5 数据库升级到 v6，确认 4 张旧表数据与本地书籍文件完好。
- **打包**：测试版与正式版共存，测试版升级保留数据，应用名与包名不变。
- **命令**：`./gradlew test`；`./gradlew :app:lintTtsTestDebug`；`./gradlew :app:assembleTtsTestDebug`；`./gradlew :app:assembleProductionRelease`；`node tests/*.test.mjs`。

---

## 13. 阶段 A 暂停声明

阶段 A 仅完成分析、设计、风险识别与计划输出，**未编写任何业务代码、未改动现有源码**。请确认 §7 的 6 个修订点与 §11 的节点划分；确认后再进入阶段 B1。每完成一个节点，我会立即做一次本地提交（简体中文提交信息）。
