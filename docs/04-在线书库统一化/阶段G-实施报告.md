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

本次 `adb devices` 仍未列出设备，以下只能等连接后补做：
1. 先安装测试版 APK，再覆盖安装一次，确认原有书籍/书签/进度不丢；
2. 本地 EPUB 导入与阅读、原生 TTS、Kokoro 朗读、耳机/蓝牙切换；
3. 百度/WebDAV 登录、上传下载、KOReader 书目与进度双向同步，测试版与正式版同装；
4. Z-Library 登录→搜索→详情→下载→同名覆盖→双云端手动上传（含一侧失败）的真实网络链路；
5. 并发下载期间 UI 流畅度与长时后台稳定性。

## 6. 结论

在线书库统一化项目 A–G 各阶段的**代码与静态门禁**均已完成；本阶段没有再扩展功能。真机验收仍为唯一未关闭项，需在设备可用后按第 5 节清单逐项确认。正式版发布前还需补齐签名配置与发布流程。
