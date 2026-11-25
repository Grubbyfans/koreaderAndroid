# Z-Library 混合架构阶段 B 实施报告

- 分支：`feature-zlibrary`
- 实施范围：仅阶段 B（下载管理器单例化、外部文件接收、任务恢复、命名与覆盖），未进入阶段 C/D/E/F/G。
- 基线：阶段 A 提交 `5aae1b3`，261 项单元测试通过、Lint 0 error / 70 warning。
- 本轮结果：**287 项单元测试通过、0 失败；Lint 0 error / 67 warning + 3 hint；测试版 APK 构建成功并已安装到真机**。

---

## 1. 五项确认事项的落实

| 确认项 | 落实方式 |
| --- | --- |
| 数据库保持 V6、无迁移 | 未改 `AppDatabase.version`、未新增表/列。外部任务复用 `online_download_task` 现有字段，`providerId=ZLIBRARY_WEB`；交接元数据写入应用私有 JSON（Properties 格式） |
| 交接 JSON 原子写入/可恢复/可对账/完成后清理 | `DownloadHandoffStore`：临时文件 + `fsync` + `ATOMIC_MOVE`（不支持时回退 rename/copy）；启动时 `readAll` 对账；任务完成/取消/已有有效暂存时删除；只保存转移标识、本地 content URI、大小、候选名、脱敏来源路径、账号范围、系统 downloadId |
| 禁止认证凭证与签名链接 | 回执不含 Cookie/Token；`remoteRef` 只保留 `scheme://authority/path`，强制去掉 query/fragment，并截断 500 字符；`suggestedFileName` 截断 200 字符 |
| 新增 `AWAITING_NAME` | `DownloadStatus` 新增状态及转换；仅在文件名缺失、无意义或无法安全确定时进入；正常文件名直接导入 |
| 热门书单与原生搜索降级 | 阶段 B 未改动搜索 UI（属于阶段 D/E 范围）；本次改动不触碰 `ProviderUiState`、搜索偏好与热门书单逻辑 |
| 非导出 ContentProvider | 阶段 D 实现；本轮只提供主进程接收端口 `acceptCompletedDownload` 与私有交接存储，IPC 只传任务标识与元数据 |
| 探针清理 | 阶段 G 处理，本轮未删除任何探针或进程声明 |

---

## 2. 代码变更清单

### 2.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `app/.../ui/online/DownloadManagerHolder.kt` | 主进程唯一在线下载运行时：懒加载 `ProviderRegistry` + `ZLibraryBookProvider` + `BookDownloadManager`，首次创建时异步执行一次 `recoverInterrupted()` |
| `reader/.../online/download/DownloadFileStore.kt` | 受控目录端口（pending/temporary/handoff）；生产实现基于 `OnlineFiles`，测试可注入临时目录 |
| `reader/.../online/download/ExternalDownloadReceipt.kt` | 外部交接回执、接收结果、网页来源 ID、单文件 512MB 上限 |
| `reader/.../online/download/DownloadHandoffStore.kt` | 私有回执 JSON 的原子读写、UUID 校验、损坏文件清理、来源脱敏 |
| `reader/.../online/download/ExternalDownloadSource.kt` | 外部 URI 归属校验/长度/打开端口；生产实现校验系统 DownloadManager 任务状态或自有 Provider authority；`ContentUriPolicy` 提供纯 JVM URI 校验 |
| `reader/.../online/download/DownloadImporter.kt` | 校验、标题提取、导入端口；生产实现复用 `EpubValidator`、`CoverExtractor.extractTitle`、`OnlineImportCoordinator` |
| `reader/.../online/download/DownloadNamePolicy.kt` | 文件名规范化与“有无意义”判断（占位名、纯数字、16+ 位十六进制、单 ASCII 字符等回退） |
| `reader/src/test/.../BookDownloadManagerTest.kt` | 阶段 B 的 26 项单元测试（纯 JVM + 临时目录） |

### 2.2 修改文件

| 文件 | 变更 |
| --- | --- |
| `core/.../download/DownloadStatus.kt` | 新增 `AWAITING_NAME`；补充 `PREPARING/VERIFYING/IMPORT_FAILED` 的合法转换；`active` 不含待命名 |
| `reader/.../db/OnlineDownloadTaskEntity.kt` | `unfinished()` 纳入 `AWAITING_NAME`、`IMPORT_FAILED`；`clearFinished()` 保留 `AWAITING_NAME` |
| `reader/.../download/OnlineDownloadRepository.kt` | 抽出 `DownloadTaskStore` 端口，Room 实现改为 `override`，便于替换为内存实现 |
| `reader/.../download/BookDownloadManager.kt` | 重构为依赖注入；新增外部接收、命名确认、待命名/导入失败恢复、孤儿清理、网页任务 pending 优先 |
| `app/.../ui/online/OnlineLibraryViewModel.kt` | 改用 Holder 的 registry / zLibraryProvider / 单例 manager；删除每个 ViewModel 各自 `recoverInterrupted()` |
| `app/.../ui/online/DownloadManagerScreen.kt` | 增加“待命名”状态与命名对话框；同名冲突改为“确认覆盖 / 取消并清理”；失败筛选包含待命名 |
| `reader/build.gradle.kts` | 测试新增 `kotlinx-coroutines-test` |

---

## 3. 关键接口设计

### 3.1 外部接收

```kotlin
data class ExternalDownloadReceipt(
    val transferId: String,          // UUID，交接幂等键，不是远端书籍身份
    val contentUri: String,          // 本地 content:// URI
    val expectedBytes: Long? = null,
    val suggestedFileName: String? = null,
    val remoteRef: String? = null,   // 仅 https(s) origin+path，去 query/fragment
    val accountScope: String = "",
    val downloadId: Long? = null,    // 系统 DownloadManager 任务 ID
)

sealed interface ExternalAcceptResult {
    data class Accepted(val taskId: String, val awaitingName: Boolean) : ExternalAcceptResult
    data class Duplicate(val taskId: String) : ExternalAcceptResult
    data class Rejected(val reason: String) : ExternalAcceptResult
    data class Failed(val taskId: String?, val reason: String) : ExternalAcceptResult
}

suspend fun acceptCompletedDownload(receipt: ExternalDownloadReceipt): ExternalAcceptResult
```

- **幂等键**：`taskId = UUID.nameUUIDFromBytes("ZLIBRARY_WEB:$transferId")`，同一 `transferId` 重复交接只会命中同一任务，返回 `Duplicate`。
- **不阻塞 IPC**：回执校验与归属检查很轻；复制、EPUB 校验、书名提取、导入全部在 `Dispatchers.IO` 完成。阶段 D 的 Provider 只需调用本方法后立即返回。
- **远端身份**：`remoteId` 优先取脱敏后的 `remoteRef`，缺失时用最终规范化文件名兜底；从不使用 `transferId`，避免映射表按每次下载累积无意义行。
- **校验顺序**：transferId → content URI scheme/authority/路径 → 大小上限 → 预期大小 → 归属（系统下载成功状态或自有 authority）→ 实际长度 → 长度一致性 → 回执落盘 → 受控临时文件复制（512MB 上限、`fsync`）→ 原子移动到 `pending_import` → EPUB 校验。

### 3.2 任务状态

```text
QUEUED → PREPARING → DOWNLOADING → VERIFYING → IMPORTING → COMPLETED
   ↘ FAILED / CANCELLED
PREPARING → AWAITING_NAME / IMPORT_FAILED / QUEUED（外部交接落盘后的分支）
VERIFYING → AWAITING_NAME / IMPORT_FAILED / QUEUED
AWAITING_NAME → QUEUED（用户命名）/ FAILED / CANCELLED
IMPORT_FAILED → QUEUED（重试/确认覆盖）/ AWAITING_NAME / FAILED / CANCELLED
FAILED → QUEUED / CANCELLED
```

- `AWAITING_NAME`：暂存文件已校验通过，但文件名与 OPF 标题都不可用。
- `IMPORT_FAILED`：暂存文件保留，可重试导入或确认覆盖；不会自动重新下载。

### 3.3 命名与覆盖

- 规范化统一复用 `EpubImportFiles.fileName`（补 `.epub`、替换非法字符、空名回退 `book.epub`）。
- 有意义判断：过滤 `download/file/book/redirect/index/untitled/下载/未命名` 等通用名、纯数字、16 位以上十六进制、单 ASCII 字符；单个汉字视为有效。
- 同名冲突只提供“确认覆盖 / 取消并清理”：
  - 覆盖：`retry(taskId, overwriteApproved = true)` → 复用暂存文件 → 导入协调器在替换前再次检查目标文件与阅读占用，失败自动回滚。
  - 取消：`cancel(taskId)` → 删除受控暂存与对应回执 → 任务置 `CANCELLED`，不自动加序号、不保留两份。
- 书库唯一性仍由规范化完整文件名决定；未引入 SHA-256 书库判重。

---

## 4. 恢复与清理

`recoverInterrupted()` 的顺序与规则：

1. 清空 `temporary/`（下载中临时文件一律不可复用）。
2. 对账 `handoff/*.receipt`：
   - 无任务 → 幂等走 `acceptCompletedDownload`；失败/拒绝则删除回执。
   - 已完成/已取消 → 删除回执。
   - 已有有效 pending → 删除回执。
   - 其它 → 再尝试接收，失败则删除回执。
3. 逐个检查未完成任务：
   - `AWAITING_NAME` / `IMPORT_FAILED`：暂存失效才置 `FAILED`（“暂存文件已失效，请重新下载”），否则保留原状态。
   - 进行中状态：有效暂存 → `IMPORT_FAILED`（“任务被中断，可重试导入（不会重新下载）”）；无效暂存 → `FAILED`（“任务被中断，请重新下载”）。
   - **不会自动重新申请下载链接，也不会自动重复导入。**
4. `cleanupOrphanPending()`：只删除未被未完成任务引用、且最后修改时间超过 1 小时的 `pending_import` 文件；受控目录外的路径既不当作暂存，也不会被删除。

---

## 5. 测试结果

### 5.1 自动化测试

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
```

- 结果：`BUILD SUCCESSFUL`，**287 项测试通过 / 0 失败 / 0 错误 / 0 跳过**（阶段 A 261 + 本轮 26）。

新增 26 项测试与要求的 13 类对应关系：

| 要求类别 | 测试用例 |
| --- | --- |
| 下载管理器单例及恢复幂等 | `稳定 transferId 生成确定性 taskId`、`恢复幂等且不重复导入已完成任务` |
| 外部任务重复交接 | `同一 transferId 重复交接只保留一条任务`、`并发重复交接只产生一次导入` |
| 非法 URI 和任务归属校验 | `非 content URI 被拒绝`、`不受信任的 content authority 被拒绝`、`下载任务归属校验失败被拒绝` |
| 文件超限及长度不一致 | `声明大小超过上限被拒绝`、`实际长度与交接信息不一致被拒绝` |
| 损坏 EPUB | `损坏 EPUB 失败且清理暂存文件` |
| 占位文件名处理 | `占位文件名回退到 OPF 标题`、`占位文件名且无有效标题进入待命名`、`待命名确认后继续导入` |
| 待命名状态恢复 | `待命名任务恢复后保留有效暂存文件`、`待命名任务暂存文件失效时标记失败` |
| 同名覆盖和取消 | `同名交接进入覆盖确认且不自动导入`、`确认覆盖后复用暂存文件导入`、`取消同名任务清理暂存与交接文件` |
| 并发覆盖保护 | `并发同名交接不会静默覆盖`、`并发重复交接只产生一次导入` |
| 进程死亡后的 pending 恢复 | `中断任务保留有效暂存并允许重试导入`、`中断任务无有效暂存标记失败且不删除受控目录外的文件`、`交接回执可在进程死亡后重建任务` |
| 导入失败重试 | `导入失败重试复用暂存且不重新下载` |
| 已完成任务防止重复导入 | `已完成任务的重复交接不再次导入`、`网页任务无暂存时不重新申请下载链接` |
| 孤儿暂存文件安全清理 | `孤儿清理只删除超时且未被引用的受控文件` |

### 5.2 Lint

- `:app:lintTtsTestDebug`：**0 error**。
- Warning 67 条 + Hint 3 条（Hint 均为既有 `mutableStateOf` 拆箱建议，位于 `MainActivity` 等原有文件）。与阶段 A 的 70 条相比没有新增；本轮新增代码无 Lint 告警。

### 5.3 构建与真机

- `:app:assembleTtsTestDebug` 成功，产物 `app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk`，约 161 MB。
- `adb install -r` 安装测试版成功；冷启动进程正常（`pidof com.yuezhou.reader.tts.test` 有值），`logcat -b crash` 无崩溃记录。正式版 `com.yuezhou.reader` 未被覆盖。
- 受阶段 D 尚未实现影响，网页下载 → ContentProvider 交接 → 主进程接收的真机链路本轮无法端到端验证；接收逻辑由单元测试覆盖。

---

## 6. 已知问题与限制

1. **单例验证方式**：`DownloadManagerHolder` 是进程内单例，但 reader 模块单测无法构造 Android Context，因此“唯一实例”通过代码结构保证；单元测试覆盖的是其核心契约（确定性 taskId、恢复幂等、重复交接不重复导入）。
2. **外部接收入口尚未暴露**：阶段 D 的非导出 ContentProvider 尚未实现，`acceptCompletedDownload` 目前只能由主进程内部调用。
3. **`remoteId` 兜底为文件名**：当回执没有 `remoteRef` 时，网页来源映射以规范化文件名作为 `remoteId`；这与原生书源的 `id:hash` 语义不同，但避免了 `transferId` 造成的映射增长。若后续需要区分同一书名的不同版本，应在阶段 D/E 补充稳定来源信息。
4. **恢复时逐个做 EPUB 校验**：启动对账会对每个未完成任务执行一次 EPUB 校验，任务很多时会带来一次性 IO 开销；当前任务量级可接受，未做额外优化。
5. **孤儿清理延迟 1 小时**：为避免与正在复制的文件竞争，孤儿 pending 文件需超过 1 小时才清理；这属于有意保留的保守策略。
6. **`Message` 文案**：待命名与中断恢复文案为新增产品文案，未在阶段 A 的方案原文中逐字定义。

---

## 7. 与技术方案的差异

| 方案描述 | 实际实现 | 原因 |
| --- | --- | --- |
| 外部交接可能由 ContentProvider 直接触发导入 | `acceptCompletedDownload` 先落盘/校验，再异步队列导入；Provider 仅负责调用 | 保证 IPC 调用线程不阻塞，且复制/解析可恢复 |
| 交接文件保存任务元数据 | 仅保存转移标识、content URI、大小、候选名、脱敏来源、账号范围、downloadId | 满足“不保存认证凭证及签名链接” |
| `remoteId` 使用远端书籍身份 | 优先脱敏 `remoteRef`，缺失时用规范化文件名 | `transferId` 不能作为永久远端身份 |
| `PREPARING` 仅用于准备下载 | 外部任务在 `PREPARING` 完成复制/校验后可能直接进入 `QUEUED/AWAITING_NAME/IMPORT_FAILED` | 复用同一状态机，避免新增状态 |
| 进程重启后恢复队列 | 不自动重下，进行中有暂存→`IMPORT_FAILED`；待命名/导入失败保留；无暂存→`FAILED` | 遵循 B3“不自动重新获取下载链接、不重复导入已完成任务” |

---

## 8. 后续阶段衔接

- **阶段 C**：在 `Application` 层增加进程守卫，检查 `ReaderTtsApplication` 子类逻辑，确保 `:zlibraryweb` 进程不初始化 Room、同步服务、TTS/Kokoro；`DownloadManagerHolder` 届时只在主进程可取。
- **阶段 D**：实现非导出 ContentProvider，调用 `acceptCompletedDownload`，并按 B2 要求校验调用 UID、任务归属与数据边界。
- **阶段 E**：修复 `ZLibraryApi.toOnlineBook()` 对 `extension`/`format` 的兼容，缺失/冲突/不支持格式不得默认 EPUB。
- **阶段 G**：删除三个 Z-Library 临时探针及临时进程声明，保留验证报告、正式测试与 TTS `StageDActivity`。

本轮完成即停止，等待阶段 C 确认。
