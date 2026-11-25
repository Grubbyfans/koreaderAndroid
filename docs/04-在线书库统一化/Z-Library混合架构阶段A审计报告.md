# Z-Library 混合架构 阶段 A 审计报告

- 日期：2026-11-25
- 对应方案：《静读 Z-Library WebView 搜索与原生下载混合架构技术改造方案》v1.0
- 范围：仅源码审计、构建与测试基线；未修改业务代码
- 结论：方案可实施，但需先确认 5 处与现状的差异（见 §5），确认后进入阶段 B

## 1. 构建与测试基线

| 项 | 实际结果 |
|---|---|
| 命令 | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug` |
| 结果 | BUILD SUCCESSFUL（2m40s，245 tasks） |
| 单测 | 261 个，0 失败 / 0 错误 / 0 跳过（core、reader、speech、app） |
| Lint | 0 error、70 warning（当前基线；方案提到的 53 warning 是旧报告数据，不能作为本次基线） |
| 测试 APK | `app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk`，约 161 MB（含 Sherpa AAR） |
| 设备 | adb 已连接三星 S25+（无线），已安装 `com.yuezhou.reader` 与 `com.yuezhou.reader.tts.test` |
| 差异 | 方案中的 `./build-local.sh` 在仓库中不存在；实际使用 `./gradlew` |

构建日志中仅有既有 Kotlin 警告（`OnlineImportCoordinator` 非空断言、`CloudUploadBridge` 未使用表达式），无新增错误。

## 2. 实际代码结构（审计结果）

### 2.1 模块

- `:core`：纯 Kotlin，在线 API、协议、下载状态机。
- `:reader`：书库、EPUB、Room、在线书源、下载与导入。
- `:speech`：TTS 服务，不依赖 `:reader`。
- `:app`：UI、Application 装配、测试变体。

### 2.2 关键类与现状

| 方案中的类 | 实际路径 | 现状 |
|---|---|---|
| `ZLibraryApi` | `core/.../online/api/ZLibraryApi.kt` | 登录/搜索/详情/取链/下载齐全；`toOnlineBook()` 第 422 行只读 `format`，缺失时默认 `epub`，未处理 `extension` |
| `ZLibraryBookProvider` | `reader/.../online/zlibrary/ZLibraryBookProvider.kt` | 强制 `format=EPUB`；搜索被拒（`SERVER_REJECTED`）时降级为热门书单并返回提示 |
| `ZLibraryConfigStore` | `reader/.../online/config/ZLibraryConfigStore.kt` | 服务器候选、导入导出、回滚；`load()` 返回 `ZLibraryConfig`，可只读取得候选 host |
| `CatalogBookProvider` | `core/.../online/Provider*` | 现有实现为百度、WebDAV、Z-Library 三个；无网页来源 |
| `BookDownloadManager` | `reader/.../online/download/BookDownloadManager.kt` | 并发 2、入队前查同名、Provider 下载、pending、重试、取消；新增外部接收入口是阶段 B 核心 |
| `OnlineDownloadRepository` | `reader/.../db/OnlineDownloadTaskEntity.kt` + `AppDatabase` | `online_download_task` 已存在，字段见 §3 |
| `OnlineDownloadTask` | `core/.../online/download/OnlineDownloadTask.kt` | 领域模型与实体字段一致 |
| `DownloadManagerScreen` | `app/.../ui/online/DownloadManagerScreen.kt` | 任务列表、取消、重试、同名覆盖对话框已具备；无命名确认 |
| `EpubValidator` | `reader/.../online/import/EpubValidator.kt` | ZIP/mimetype/container/OPF/路径穿越/体积校验；`Result` 只有 `valid/reason`，不返回元数据 |
| `EpubImportFiles` | `reader/.../EpubImportFiles.kt` | `fileName()` 规范化、`validate()`、`install()` 原子替换与同内容判断 |
| `OnlineImportCoordinator` | `reader/.../online/import/OnlineImportCoordinator.kt` | 生成 bookId、同名覆盖、`ReaderActivity::isBookOpen` 阅读占用保护、校验、备份回滚、`LibraryStore.registerBook`、写映射 |
| `LibraryStore` | `reader/.../LibraryStore.kt` | `Entry(bookId,fileName,size,...)`；bookId 由规范化文件名派生；同名视为覆盖并保留进度 |
| `LibraryRefreshSignal` | `reader/.../online/download/LibraryRefreshSignal.kt` | 已存在，导入成功刷新书架 |
| Application | `app/.../ReaderApplication.kt`；测试版 `app/src/ttsTestDebug/.../ReaderTtsApplication.kt` | **每个进程**都安装 Room 进度端口与 TTS；测试版还注入 Kokoro |

### 2.3 数据库

- `AppDatabase.version = 6`。
- 迁移链：`isolatePublications`、`addSpeechPosition`、`addSyncLog`、`addSpeechAnchor`、`addOnlineDownloadTables`；无 destructive fallback。
- `online_download_task`：
  `taskId(PK), providerId, accountScope, remoteId, bookTitle, fileName, status, receivedBytes, totalBytes, localPath, errorCode, errorMessage, attempts, overwriteApproved, createdAt, updatedAt, localBookId`。
- `online_book_mapping`：
  `providerId, accountScope, remoteId` 联合主键，另有 `fileName, localBookId, downloadedAt, active`。
- `DownloadStatus`：`QUEUED/PREPARING/DOWNLOADING/VERIFYING/IMPORTING/COMPLETED/IMPORT_FAILED/FAILED/CANCELLED`，无命名等待态。

### 2.4 现有可复用资产

- WebView 探针已实现 `WebView.setDataDirectorySuffix`、HTTPS-only 导航、`DownloadListener` + `DownloadManager`（`app/src/ttsTestDebug/.../WebViewStageTwoActivity.kt`）。
- 下载任务、pending、同名对话框、阅读占用保护、EPUB 校验、安全替换与回滚均可直接复用。
- `CoverExtractor.extractTitle(epubFile)` 可读取 OPF 标题，用于占位文件名解析。
- 正式包目前不含探针；探针仅在 `ttsTestDebug`，且 `:apiprobe`、`:webprobe` 进程声明也只在该变体。

## 3. 与方案不一致或需确认的点

1. **`BookDownloadManager.run` 先解析 Provider，再检查 pending**（方案 7.2 要求反过来）。外部任务没有注册 Provider，当前会直接失败。
2. **`recoverInterrupted()` 会删除所有未完成任务的 `localPath` 并标记 FAILED**（方案 7.3 要求保留外部任务有效 pending）。直接复用会丢文件。
3. **无外部接收接口**：没有 `ExternalDownloadReceipt` / `acceptCompletedDownload()`；`enqueue()` 只接受 Provider 下载。
4. **数据库没有外部来源/下载 ID/阶段字段**。建议不迁移，方案见 §4.1；需要确认。
5. **`OnlineBookMapping` 以 `(providerId, accountScope, remoteId)` 为主键**。网页来源没有 API `id:hash`，必须为每次下载提供稳定的唯一 `remoteId`，建议用 `transferId`，不伪造 API 标识。
6. **Application 在每个进程都会执行**：`:zlibraryweb` 启动时会安装 Room 进度端口和 TTS（测试版还注入 Kokoro），违反“网页进程不启动数据库/同步/朗读”的要求。必须在 `ReaderApplication` 增加进程守卫，测试版子类自动继承。
7. **当前无任何 IPC 组件**：Manifest 中没有 FileProvider/ContentProvider；需要新增非导出的受控 Provider（或 Binder Service）。
8. **`EpubValidator` 不返回书名**：占位名解析需新增解析器，复用 `CoverExtractor.extractTitle`，不改造校验器。
9. **`ZLibraryApi` 只认 `format`，缺失默认 `epub`**（方案阶段 E 要求同时考虑 `extension` 与 `format`，缺失/冲突不得无条件默认 EPUB）。
10. **原生搜索被拒时自动降级热门书单**：需要阶段 E 确认是否保留该行为；UI 已显示“服务端未开放搜索”的提示，不能当作虚假搜索结果。
11. **`downloadManager.recoverInterrupted()` 在 `OnlineLibraryViewModel.init` 中调用**：每个 ViewModel 一份 Manager，互斥/恢复不跨实例；外部接收入口需要主进程单例，否则与 UI 队列并发冲突。
12. **方案构建命令 `./build-local.sh` 不存在**，基线改用 `./gradlew`。

## 4. 建议的改造清单（待确认后执行）

### 4.1 阶段 B：外部接收、恢复、命名覆盖

- 新增 `reader/.../online/download/ExternalDownloadReceipt.kt`（含 `Uri`，放 reader 而非 core）。
- `BookDownloadManager`：
  - 新增 `acceptCompletedDownload(receipt)`；
  - 调整 `run`：先检查有效 pending，再按需解析 Provider；
  - `recoverInterrupted` 区分外部任务，保留 pending 并恢复待确认/失败状态；
  - 接收端做调用方、URI authority、大小上限、实际可读性校验；
  - 稳定 `transferId` → 确定性 `taskId`（UUID v3/5）实现幂等。
- 新增 `WebDownloadHandoffProvider`（非导出、主进程）或等价 Binder 组件，只传 `transferId` + 小元数据；下载完成后主进程按 `downloadId` 查询 `DownloadManager` 并复制。
- 新增 `DownloadNameResolver`：占位名 → `CoverExtractor.extractTitle` → 用户确认名；只做规范化和冲突前确认，不自动加序号。
- `DownloadStatus` 仅新增 `AWAITING_NAME`（字符串枚举，无 DB 迁移）；覆盖确认继续复用 `IMPORT_FAILED` + “同名” + 现有对话框。
- `DownloadManagerScreen`：命名对话框、取消时清理 pending、显示外部任务；`OnlineFiles` 增加交接目录。
- 单测：幂等、重复回调、URI 边界、超限、长度不符、损坏 EPUB、占位名、同名覆盖/取消、进程死亡恢复。

**数据库变更建议：无迁移。** 使用现有字段映射：`providerId="ZLIBRARY_WEB"`、`remoteId=transferId`、`accountScope="web"` 或未知、`localPath=pending`；下载 ID/原始 URI 放在私有交接 JSON（`files/zlibrary_handoff/<transferId>.json`），进程死亡后可恢复。若确认需要显式字段，再评估 6→7 最小迁移（`externalId`、`sourceUri`），并补迁移测试；不做 destructive migration。

### 4.2 阶段 C：WebView 容器

- 新增 `ZLibraryWebActivity`（`android:exported="false"`、`android:process=":zlibraryweb"`）。
- `WebView.setDataDirectorySuffix` 必须在进程内任何 WebView/CookieManager 之前调用；API < 28 关闭入口。
- Application 进程守卫；测试版 `ReaderTtsApplication` 自动不再注入 Kokoro。
- 导航白名单：仅 HTTPS；站点 host 取现有 `ZLibraryConfigStore.load()` 的候选 host，不修改配置；外部域确认或交系统浏览器。
- 禁止 JS bridge；关闭 file/content access；SSL 错误取消；release 关闭 WebView 调试。
- 页面状态机 `Idle/Loading/VerificationOrWaiting/ContentVisible/NetworkError/Timeout/RendererGone` + 导航代次。

### 4.3 阶段 D：网页下载与 UI

- Z-Library 检索页新增“网页搜索”入口；原生拒绝时保留提示。
- `DownloadListener` 只接受用户主动 HTTPS GET；确认框显示候选名/大小/来源；确认后入系统 DownloadManager 并记录 `transferId/downloadId`。
- 主进程交接、校验、命名、冲突、导入、刷新；不自动弹 Activity、不自动覆盖。
- 下载任务页增加“待命名”入口与取消清理。

### 4.4 阶段 E：原生 API 兼容

- `ZLibraryApi.toOnlineBook()` 同时读取 `extension` 与 `format`；缺失/冲突/不支持分别建模；仅改 Z-Library 映射，不动其他书源。
- 搜索拒绝保持独立分类与明确提示；是否保留热门降级由用户确认。
- 补 `ZLibraryApiTest` 格式用例。

### 4.5 阶段 F：内存与生命周期

- 按需创建单个 WebView，不可见停止加载，退出 destroy；处理 `onRenderProcessGone`。
- 不改 Kokoro/系统 TTS；记录网页宿主、渲染器、阅读器 PSS 与朗读并发数据。

### 4.6 阶段 G：回归与发布

- 移除三阶段探针：`WebViewProbeActivity`、`WebViewStageTwoActivity`、`ZLibraryApiProbeActivity` 及测试 Manifest 的 `:apiprobe`/`:webprobe` 声明；`StageDActivity` 属 TTS 测试能力，不在本次移除范围。
- 正式包检查 Manifest/DEX 无探针、无调试导出入口。
- 全量回归百度/WebDAV/阅读器/进度同步/TTS；输出验收报告。

## 5. 主要风险

| 风险 | 等级 | 说明 |
|---|---|---|
| 网页进程初始化 TTS/Room | 高 | 当前 Application 无条件初始化；必须先加进程守卫 |
| pending 被恢复逻辑误删 | 高 | `recoverInterrupted` 现状会删除未完成 `localPath` |
| IPC/URI 越权 | 高 | 新增 Provider 必须校验 UID、authority、路径、大小 |
| 同名覆盖与阅读占用 | 高 | 复用现有串行替换与 `isBookOpen`，但外部任务需走同一入口 |
| 命名确认交互 | 中 | 需新增 `AWAITING_NAME` 与主进程对话框 |
| DownloadManager 回调/进程死亡 | 中 | 广播不可单独信任；需按 downloadId 查询与对账 |
| 多 Manager 实例 | 中 | 需主进程单例，否则互斥与恢复失效 |
| 格式字段映射 | 中 | 阶段 E 修复，避免默认 EPUB 掩盖未知格式 |
| 构建脚本差异 | 低 | `build-local.sh` 缺失，改用 `./gradlew` |

## 6. 待确认事项

1. 数据库：接受“无迁移 + 私有交接 JSON”，还是新增 `externalId/sourceUri` 做 6→7 迁移？
2. 网页任务命名确认：同意新增 `AWAITING_NAME` 状态，并在下载任务页确认？
3. 原生搜索被拒时，保留现有“热门书单 + 提示”降级，还是改为纯提示不降级？
4. IPC：新增非导出 ContentProvider（推荐）还是 Binder Service？
5. 阶段 G 是否移除全部三阶段探针（含测试变体），还是仅保证正式包不含？

确认后进入阶段 B；未确认前不改业务代码。
