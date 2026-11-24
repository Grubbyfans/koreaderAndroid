# 阶段 G 实施报告（回归 / 安全 / 性能 / 打包）

对应方案：`docs/04-在线书库统一化/静读在线书库统一化与多书源扩展技术方案.md` §12/§13
前置：`docs/04-在线书库统一化/阶段F-实施报告.md`
分支：`feature-zlibrary`　状态：**静态门禁已完成；真机回归因无连接设备未执行**

## 1. 本阶段执行结果

- **全量单元测试**：`./build-local.sh test` → **253 项通过、0 失败、0 错误**（33 个测试文件，覆盖 core / reader / speech / novel-text）。
- **静态检查**：`:app:lintTtsTestDebug` → **0 errors，39 warnings，3 hints**。修复了原有 4 个 error：
  - `MainActivity.kt`：持久目录授权误传 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` 给 `takePersistableUriPermission`（该 API 只接受 READ/WRITE），改为仅 READ；
  - `MainActivity.kt`（2 处）与 `ServerSettingsScreen.kt`（1 处）：Composable 中直接读取 `Locale.getDefault()`，抽到普通格式化函数，输出行为不变。
  - 余下 39 项 warnings / 3 项 hints 为既有版本提示、资源与格式建议，不阻断构建；未在本阶段扩大改动面。
- **双变体打包**：`:app:assembleTtsTestDebug`、`:app:assembleProductionRelease` 均成功：
  - `app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk`（165 MB，包名 `com.yuezhou.reader.tts.test`）；
  - `app/build/outputs/apk/production/release/app-production-release-unsigned.apk`（36 MB，工程未配置正式签名，产物为 unsigned，需发布前补签名流程）。
- **性能相关静态核对**：下载并发上限 2、进度 700ms 节流、封面内存缓存与 2 MiB 上限、搜索结果按 `remoteId` 去重且不写回过期请求；本次未做真机性能采样。

## 2. 验收清单证据

| 方案 §12 项目 | 静态/单测证据 | 真机 |
|---|---|---|
| 本地 EPUB 导入及阅读 | `EpubImportFilesTest`、`EpubChapterTextSourceTest`；阅读器代码零改动 | 待验证 |
| 原生 TTS / Kokoro 离线朗读 | `SpeechCoordinatorTest`、`SpeechStateMachineTest`、`KokoroMemoryPolicyTest` 等 | 待验证 |
| 百度 / WebDAV 上传下载 | `CloudServiceTest`、`BaiduAuthTest`、`DavPathTest`；阶段 F 桥接测试 | 待验证 |
| KOReader 进度双向同步 | `ProtocolCodecTest`、`PositionCodecTest`、`FixtureTest`；协议文件与进度路径零改动 | 待验证 |
| 原有书籍/书签/进度不丢失 | 数据库仅 5→6 非破坏迁移；同名覆盖保留 bookId 与进度（阶段 E） | 待验证 |
| 书源切换与独立页面状态 | `ProviderContractTest`；阶段 D 页面状态按源隔离 | 待验证 |
| 登录退出、凭证隔离 | `OnlineCredentialStore` 按账号派生 Keystore alias 与密文文件；`ServerTrustScopeTest` | 待验证 |
| 搜索/筛选/分页无重复、无过期污染 | `OnlinePageMergerTest`、`ZLibraryApiTest` | 待验证 |
| 详情与封面 | `ZLibraryApiTest`：仅 HTTPS 图片、拒绝非图片响应 | 待验证 |
| 配置导入、校验、回滚 | `ZLibraryConfigTest`：非法地址/凭证字段/路径穿越/越界 schema 均拒绝且不覆盖原配置 | 待验证 |
| 单源故障不影响其他模块 | `ProviderContractTest`；按源独立状态容器 | 待验证 |
| 下载进度/取消/断网 | `DownloadStateMachineTest`、`DownloadRetryPolicy`；仅网络错误自动重试 | 待验证 |
| 退出后任务恢复符合额度约束 | 重启不自动重申请额度，仅标记失败待手动重试（阶段 E） | 待验证 |
| 同名覆盖确认与进度保留 | `OnlineImportCoordinator`（阶段 E）；取消保留原书、确认安全替换 | 待验证 |
| 文件名不同但内容相同视为不同书 | 唯一性按文件名，不使用哈希/元数据（阶段 E） | 待验证 |
| 损坏 EPUB / HTML 错误页拒绝入库 | `EpubValidatorTest` 5 项：有效包、HTML 页、缺 mimetype/OPF、路径穿越、ZIP bomb | 待验证 |
| 导入失败独立重试且幂等 | `IMPORT_FAILED` 复用待导入文件（阶段 E） | 待验证 |
| 手动上传云端及 KOReader 获取 | `CloudUploadBridgeTest` 4 项：双目标独立结果、跳过、异常隔离、本地书缺失 | 待验证 |
| 并发下载不明显卡顿 | 并发 2 + 进度节流（静态核对） | 待真机采样 |
| 凭证不出现在日志/配置 | 全量 `Log.*` 调用静态审阅无 token/password/cookie；配置模型单测断言不含凭证字段 | 待验证 |
| 非可信主机/重定向不接收凭证 | `ZLibraryApiTest`「拒绝重定向到不可信主机」、`ServerTrustScopeTest`「切换主机后旧会话失效」 | 待验证 |
| 非法配置不覆盖可用配置 | `ZLibraryConfigTest` 校验与回滚 | 待验证 |
| 路径穿越、异常 ZIP、空间不足 | `EpubValidatorTest`、`ModelImportSafetyTest`、`EpubImportFilesTest`；下载正文上限 1 GiB、封面 2 MiB | 待验证 |
| 测试版与正式版可共存 | Gradle `applicationIdSuffix = ".tts.test"`，两个 APK 产物均已生成 | 待真机同装 |
| 测试版升级保留已有数据 | Migration(5,6) 仅新增两张表，旧表与协议零改动 | 待真机升级 |

## 3. 本阶段修改文件及原因

| 文件 | 修改原因 |
|---|---|
| `app/.../MainActivity.kt` | 修复 lint 的 WrongConstant；抽出日志时间格式化函数消除 NonObservableLocale |
| `app/.../ui/online/ServerSettingsScreen.kt` | 抽出检测时间格式化函数消除 NonObservableLocale |
| `docs/04-在线书库统一化/阶段G-实施报告.md` | 本报告 |
| `docs/README.md` | 文档索引加入阶段 G |

未修改：业务功能代码、网络/安全协议、数据库 schema、阅读器与 TTS 实现。

## 4. 提交记录

1. `d0708a8 阶段G：修复lint错误并统一日志时间格式化`
2. 本报告与索引更新（`docs/`），作为独立文档提交。

## 5. 真机待办（阻塞项）

本报告编写时 `adb devices` 未列出设备；之后设备已连接并完成安装与部分验证（见第 7 节）。以下仍待补做：
1. 先安装测试版 APK，再覆盖安装一次，确认原有书籍/书签/进度不丢；
2. 本地 EPUB 导入与阅读、原生 TTS、Kokoro 朗读、耳机/蓝牙切换；
3. 百度/WebDAV 登录、上传下载、KOReader 书目与进度双向同步，测试版与正式版同装；
4. Z-Library 登录→搜索→详情→下载→同名覆盖→双云端手动上传（含一侧失败）的真实网络链路；
5. 并发下载期间 UI 流畅度与长时后台稳定性。

## 6. 结论

在线书库统一化项目 A–G 各阶段的**代码与静态门禁**均已完成；本阶段没有再扩展功能。真机验收仍为唯一未关闭项，需在设备可用后按第 5 节清单逐项确认。正式版发布前还需补齐签名配置与发布流程。

## 7. 真机回归发现：Z-Library 搜索被服务端拒绝（2026-11-24）

### 现象与定位
- 设备 SM-S9360 / Android 16 登录后搜索任意关键词均提示「请求失败」。
- 抓包式日志定位：搜索端点返回 `HTTP 400`，响应体 `{"success":0,"error":"拒绝访问"}`；首次还出现过一次 `SSLHandshakeException: connection closed`（瞬时网络故障，非根因）。
- 对照上游参考实现固定 commit `b7eacbab`（版本 1.0.62，commit 日期 2026-11-23）的 `zlibrary/api_books.lua` 源码注释：**Z-Library 自 2026-11-23 起对所有搜索返回 400 Access denied，与会话、UA、请求体无关**；其列表、最热门与下载仍可用。
- **证据性质**：这是上游插件作者写在源码里的实测记录，**不是 Z-Library 官方公告**；该 commit 的 message 仅为 `chore: Bump version to 1.0.62 [skip ci]`，未提及此事。原文注释逐字如下：
  > `-- What a reader sees instead of the server's bare "Access denied", which Z-library has sent for`
  > `-- every search since 2026-11-23 (an HTTP 400, whatever the session, user agent or body). That`
  > `-- reads like a problem with their account or the plugin, and it is neither.`
  >
  > `-- Since 2026-11-23 the server answers every search with "Access denied" while its`
  > `-- own most-popular list still works. The unfiltered list beats an error, so show it`
  > `-- and say the filters were left out.`
- 我们的真机日志（连续三次 `HTTP 400`、`{"success":0,"error":"拒绝访问"}`）独立印证了同一现象。因此这不是账号、登录态或本地配置问题。

### 修复
- `ProviderErrorCode` 新增 `SERVER_REJECTED`；错误解析同时读取 `error` 字段，把 `Access denied`/`拒绝访问` 归类为服务端拒绝，不再误报为「请求失败」或认证失败。
- `ZLibraryConfig.Endpoints` 新增 `mostPopular`（`/eapi/book/most-popular`）；搜索被拒时自动降级到官方「最热门」列表，并通过 `BookPage.notice` 在页面显示明确提示。降级列表只提供一页，不生成下一页游标；详情与下载链路不变。
- 移除了排障用的临时日志，不记录 Cookie 或响应正文。
- 测试新增 2 项：服务端拒绝分类、热门列表降级映射；全量 **255 项通过、0 失败**。

### 真机验证
- 安装修复版后搜索 `Python`：页面显示「Z-Library 服务端暂未开放搜索，当前展示官方「最热门」书单；登录、详情与下载不受影响。」并成功加载带封面的热门书籍；登录状态与额度显示正常。
- 搜索功能本身仍受限于服务端，需等待 Z-Library 恢复；本修复保证用户得到准确说明且仍可浏览书籍，不把服务端问题误判为登录失败。
