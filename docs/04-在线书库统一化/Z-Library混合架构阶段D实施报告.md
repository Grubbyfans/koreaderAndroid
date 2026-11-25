# Z-Library 混合架构阶段 D 实施报告

- 分支：`feature-zlibrary`
- 实施范围：仅阶段 D（应用内网页入口、网页顶部导航栏、DownloadListener + 用户确认、系统 DownloadManager 下载到受控暂存、非导出 ContentProvider 交接、主进程观察与入库、失败/取消/重启恢复）。
- 基线：阶段 C 提交 `b7d4207`，阶段 C 报告记录 323 项单元测试通过、Lint 0 error / 67 warning。
- 本轮结果：**347 项单元测试通过、0 失败；Lint 0 error / 70 warning；测试版 APK 构建成功并在真机完成网页搜索、登录态复用、下载历史重下、确认、系统下载、自动入库、进程重启恢复与失败任务闭环验证。**

---

## 1. 结论与范围

阶段 D 把网页下载接入现有统一下载任务：

1. Z-Library 原生搜索页新增「网页搜索」入口；原生搜索被服务端拒绝时只提示“原生搜索暂不可用，可使用网页搜索”，不展示降级后的热门结果。
2. 网页容器新增顶部导航栏（返回 / 当前站点 / 后退 / 刷新 / 关闭），保留系统返回键的网页历史优先行为。
3. `DownloadListener` 只负责收集候选信息并弹出确认；用户确认后由网页进程把 HTTPS 地址交给系统 `DownloadManager`，落到应用私有外部目录。
4. 主进程通过非导出 `ContentProvider`（仅 `call("watch")`，校验调用 UID）与完成广播感知下载，**完成判定只以 `DownloadManager` 查询为准**，再调用阶段 B 的 `acceptCompletedDownload` 完成校验、命名与入库。
5. 失败、取消、文件被移除、进程死亡后重启均能收敛为“失败任务 + 重试”或继续入库。

本轮**不包含**：ZLibraryApi 的 extension/format 修复（阶段 E）、探针删除（阶段 G）、原生搜索协议改造。

---

## 2. 改动文件

### 2.1 新增

| 文件 | 职责 |
| --- | --- |
| `app/.../web/ZLibraryWebEntry.kt` | 生成网页搜索地址并启动非导出网页容器 |
| `app/.../web/ZLibraryWebDownloads.kt` | 下载 URL 策略、候选名/大小解析、网页侧下载启动器、交接协议常量 |
| `app/.../web/ZLibraryDownloadHandoffProvider.kt` | 非导出 Provider，仅接受 `watch` 调用，校验调用方 UID |
| `app/.../web/ZLibraryWebDownloadWatcher.kt` | 主进程侧：完成广播、Provider 登记、30s 兜底轮询、重启对账、幂等 resolve |
| `reader/.../online/download/DownloadManagerGateway.kt` | 系统 `DownloadManager` 端口（enqueue/query/remove）与状态映射 |
| `reader/.../online/download/WebDownloadRegistry.kt` | 网页进程与主进程共享的待观察下载登记（应用私有 JSON/Properties 原子写） |
| `app/src/test/.../web/ZLibraryWebEntryTest.kt` | 网页搜索地址构造 |
| `app/src/test/.../web/DownloadRequestPolicyTest.kt` | 下载 URL、候选名、大小与占位名 |
| `reader/src/test/.../download/WebDownloadRegistryTest.kt` | 登记读写、非法/损坏记录清理 |

### 2.2 修改

| 文件 | 改动 |
| --- | --- |
| `app/.../ui/online/CatalogLibraryScreen.kt` | 搜索区新增「网页搜索」按钮 |
| `app/.../ui/online/OnlineLibraryViewModel.kt` | 原生搜索被拒时清空结果并提示网页搜索；提供 `webBaseUrl()` |
| `app/.../ui/online/DownloadManagerHolder.kt` | Runtime 增加 `webDownloads`，创建时先启动观察器再恢复中断任务 |
| `app/.../web/ZLibraryWebActivity.kt` | 顶部导航栏、`DownloadListener`、确认框、下载启动与 Toast |
| `app/.../web/WebNavigationPolicy.kt` | 新增 `Decision.DOWNLOAD` 与已验证下载 CDN 白名单 |
| `app/src/main/AndroidManifest.xml` | 注册非导出 `ZLibraryDownloadHandoffProvider` |
| `reader/.../download/BookDownloadManager.kt` | 新增 `recordExternalDownloadFailure`，把失败/取消落为可重试任务 |
| `reader/.../download/DownloadNamePolicy.kt` | 将 `redirection` 视为占位名，交给 EPUB 标题/用户确认 |
| 对应测试 | 导航策略 DOWNLOAD、失败任务幂等、已完成任务不被覆盖等 |

---

## 3. 关键实现

### 3.1 应用内入口与原生搜索降级

- 「网页搜索」使用当前配置的第一个 `servers.baseUrls`，只允许 HTTPS；有关键词时拼 `<base>/s/<encode>`，无关键词进入站点首页。
- 原生搜索 `SERVER_REJECTED` 时统一提示 `原生搜索暂不可用，可使用网页搜索`；`doSearch` 在出现该提示时清空 `items`、`hasMore` 与 `nextCursor`，**不把热门降级结果当作搜索结果**。

### 3.2 网页顶部导航栏

- `LinearLayout` + `FrameLayout` 承载 WebView，顶栏固定 48dp，处理系统栏 insets。
- 项目：`返回`（结束容器）、`host`（脱敏站点名）、`后退`（`canGoBack` 才可用）、`刷新`、`关闭`。
- 系统返回键仍由 `OnBackPressedDispatcher` 优先 `goBack()`，无历史再结束 Activity。
- 临时验证用「测试」按钮与 `startDownloadTest()` 已删除，最终包不再包含临时入口。

### 3.3 下载确认与 URL 策略

`DownloadListener` 触发后：

- 非 HTTPS → Toast“暂不支持该下载方式，请在浏览器中下载后手动导入”。
- 确认框展示：来源域名（脱敏）、已知大小（未知则显示未知）、候选文件名。
- 仅 `https`、无 userinfo、端口 `-1/443`、host 非空的地址可入队；超过 `MAX_EXTERNAL_DOWNLOAD_BYTES` 直接拒绝。
- 候选名优先 `Content-Disposition` 的 `filename*`/`filename`，其次 URL 末段（末段必须含 `.`）；占位名（如 `redirection.epub`）不采用，交给 EPUB 标题或后续确认。

### 3.4 系统 DownloadManager 与受控暂存

- 每次下载生成独立 `transferId`（UUID），落盘名 `$transferId.download`，目录为 `setDestinationInExternalFilesDir(..., Environment.DIRECTORY_DOWNLOADS, ...)` 的应用私有外部目录。
- 不转发 Cookie/Authorization；仅按需附加网页 UA，便于 CDN 识别。
- **偏差**：`VISIBILITY_HIDDEN` 在真机（Android 16/API 36）抛 `SecurityException: Invalid value for visibility: 2`，改为 `VISIBILITY_VISIBLE_NOTIFY_COMPLETED`。下载期间系统通知可见；导入结果仍以应用内下载任务为准。

### 3.5 登记、交接与主进程观察

- 网页进程入队后写入 `files/downloads/web_downloads/<downloadId>.webdl`（含 transferId、downloadId、候选名、期望大小、脱敏 remoteRef、时间），再 `contentResolver.call` 通知主进程；通知失败不回滚，靠磁盘登记与重启对账收敛。
- `ZLibraryDownloadHandoffProvider`：`exported=false`、`grantUriPermissions=false`、authority `${applicationId}.zlibrary.web`；仅实现 `call("watch")`，要求 `Binder.getCallingUid() == Process.myUid()`；其余 CRUD 为空实现。
- `ZLibraryWebDownloadWatcher`：
  - 动态注册 `ACTION_DOWNLOAD_COMPLETE`；**偏差**：Android 14+ 用 `RECEIVER_NOT_EXPORTED` 收不到系统 DownloadProvider 的完成广播，改为 `RECEIVER_EXPORTED`。广播只含 downloadId，完成与否一律 `gateway.query` 复核，伪造广播最多触发一次无害查询。
  - 广播丢失/进程重启时按 30s 低频轮询，待处理清空自动停止。
  - `resolve` 以 downloadId 去重；`Ready` → 构造 `ExternalDownloadReceipt` 调 `acceptCompletedDownload`；`Failed/Unknown` → `recordExternalDownloadFailure`；成功/失败后均 `gateway.remove` 并删除登记。
  - 重复广播、重复 Provider 登记、进程死亡后重启均幂等。

### 3.6 安全边界

| 项目 | 处理 |
| --- | --- |
| Cookie/认证头 | 不自动转发至 CDN |
| 签名链接 | 不持久化 query/fragment；登记只保留脱敏 remoteRef |
| Provider | 非导出、仅同 UID 调用、无文件读取能力 |
| 下载来源 | 仅配置站点（浏览）与 `ncdn.ec` 下载域放行，其余 HTTPS 确认后交系统浏览器 |
| SSL | 沿用阶段 C 一律取消 |
| 覆盖 | 不自动覆盖已有书籍；命名冲突走阶段 B 策略 |

---

## 4. 测试与真机验证

### 4.1 构建与静态检查

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
```

- 结果：`BUILD SUCCESSFUL`。
- 单元测试：347 项、0 失败（app 27 × 2 flavor、reader 66、core 173、speech 32、novel-text 22）。
- Lint：0 error / 70 warning。阶段 D 新增文件无新增告警；`ZLibraryWebActivity` 的品牌名文案已用 `@SuppressLint("SetTextI18n")` 说明，其余两条告警（ViewModel context、滚动位置读取）在 `b7d4207` 已存在。

### 4.2 真机（Android 16 / API 36，测试包 `com.yuezhou.reader.tts.test`）

| 场景 | 证据与结果 |
| --- | --- |
| 网页入口与登录态 | 「网页搜索」打开 `zh.z-lib.gd`，独立数据目录保留登录态；页面显示“今日下载 1 / 10” |
| 下载历史重下 | 菜单 →「下载历史」→ 第一本书重新下载，确认框显示 `dln1.ncdn.ec`、`2.1 MB`、候选名含“(兰小欢)…epub” |
| 首次入队失败修复 | `VISIBILITY_HIDDEN` 导致 `SecurityException`，改可见通知后 `downloadId=629` 入队成功 |
| 系统下载 → 自动入库 | 629 于 10:48:50 SUCCESS，10:49 `files/epub` 出现新 EPUB，下载任务显示“已导入” |
| 完成广播链路 | 630（207 KB）：10:52:05 入队、10:52:07 SUCCESS，随即登记目录清空、EPUB 落盘 |
| 进程死亡 + 重启恢复 | 631（明朝那些事儿，约 40 MB）：确认后立即 `force-stop`，系统下载先 `WAITING_TO_RETRY`；重启应用进入在线书库后由对账继续观察，10:54:51 SUCCESS，随后导入成功、登记清空 |
| 取消/文件移除 | 写入不存在的 `downloadId=999999` 登记并重启，自动生成失败任务“下载已取消或文件已被移除，请重新发起下载”，带「重试」 |
| 重复/伪造广播 | 对已完成的 631 补发 `ACTION_DOWNLOAD_COMPLETE`，无崩溃、无重复任务 |
| 临时入口清理 | 最终 APK 顶栏仅“返回 / 站点 / 后退 / 刷新 / 关闭”，无「测试」 |

说明：测试版设备保留本轮导入的测试书籍与一条 `fake-cancel-test` 失败任务，均为测试数据，不影响正式包。

---

## 5. 未测项与风险

- 磁盘不足（`ERROR_INSUFFICIENT_SPACE`）未在真机造出，仅有状态映射与失败任务单测覆盖。
- 系统通知栏手动取消未单独复现，其最终状态与 `downloadId=999999` 的 `Unknown` 路径一致，已通过后者验证。
- 大文件/断点续传仅观察了系统自身重试，未做弱网长时压测。
- 完成通知可能被用户点击打开文件，导入结果仍以应用内任务为准；如后续要求完全静默，需要重新评估通知可见性。
- Provider 仅同 UID 可用，跨进程依赖同一 APK；若未来拆包需重新设计交接授权。

---

## 6. 下一步

阶段 D 已完成并提交后停止，等待确认再进入阶段 E（`ZLibraryApi` extension/format 修复）。
