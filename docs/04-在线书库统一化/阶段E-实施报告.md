# 阶段 E 实施报告（下载管理 / 校验 / 去重导入 / 恢复）

对应方案：`docs/04-在线书库统一化/静读在线书库统一化与多书源扩展技术方案.md` §7/§8/§9
前置：`docs/04-在线书库统一化/阶段D-实施报告.md`
分支：`feature-zlibrary`　状态：**已完成并暂停，等待确认；未进入阶段 F（云端上传联动）**

## 1. 本阶段实现的功能

- **E1 下载任务队列**
  - 新增 `online_download_task` 表与 DAO，数据库 5→6 **仅新增两张表**（另一张为 `online_book_mapping`），旧表、旧数据与 KOReader 协议零改动。
  - 状态机：`QUEUED → PREPARING → DOWNLOADING → VERIFYING → IMPORTING → COMPLETED`，失败/取消/导入失败独立状态；非法跳转直接抛错。
  - 并发上限 2；进度每 700ms 节流写入数据库并更新前台通知；取消会中断请求并清理临时文件；任务重启恢复时**不自动重新申请额度**，统一标记失败由用户显式重试。
  - 只对网络错误自动重试最多 2 次；额度、认证、限流错误不自动重试。
  - 新增统一「下载管理」全屏页（顶部下载图标进入）：全部/下载中/已完成/失败筛选，进度、取消、重试、阅读、清空历史；保留原云端下载弹窗入口。
- **E2 校验、去重与安全导入**
  - `EpubValidator`：ZIP 魔数、mimetype 首条目与内容、`container.xml`/OPF、条目数、解压总量/单文件上限、压缩比（ZIP bomb）与路径穿越防护；HTML/JSON 错误页直接拒绝。**仅判断文件有效性，不参与判重。**
  - `OnlineImportCoordinator`：唯一性只看文件名（含扩展名）；下载前检查（避免浪费额度）、导入前再次检查；同名未确认 → `IMPORT_FAILED` 并弹「书籍已存在」确认框；确认后安全替换（备份 + 原子移动 + 失败回滚）；逐字节相同时保留原文件代次；复用 `CoverExtractor` 与 `LibraryStore.registerBook()`，保留 bookId、进度、书签和历史；正在阅读的书拒绝覆盖。
  - `online_book_mapping` 只用于「已下载」标记与阅读入口；本地书籍删除后映射置为失效，不误判已下载；不作为任何判重依据。
  - `IMPORT_FAILED` 的独立重试直接复用已落盘的待导入文件，不重复下载、不重复消耗额度。
- **Z-Library 下载落地**：`getDownloadLink` 获取签名链接后流式下载到应用专属临时目录；签名链接不附带 Cookie，不跟随非 HTTPS 重定向，最多 3 跳，正文上限 1 GiB。

## 2. 新增文件及用途

| 文件 | 用途 |
|---|---|
| `core/.../online/download/DownloadStatus.kt` / `OnlineDownloadTask.kt` / `DownloadRetryPolicy.kt` | 状态机、任务模型与重试策略 |
| `reader/.../db/OnlineDownloadTaskEntity.kt` / `OnlineBookMappingEntity.kt` | Room 两表与 DAO |
| `reader/.../online/download/OnlineFiles.kt` / `OnlineDownloadRepository.kt` / `BookDownloadManager.kt` | 临时目录、持久化映射、队列调度与恢复 |
| `reader/.../online/import/EpubValidator.kt` / `OnlineImportCoordinator.kt` | 文件安全校验与同名覆盖导入 |
| `reader/.../online/mapping/OnlineBookMappingStore.kt` | 隐藏 Room 类型，供 app 层清理失效映射 |
| `app/.../ui/online/DownloadManagerScreen.kt` / `AppDownloadNotifier.kt` | 统一下载管理页与前台通知桥接 |
| `core/src/test/.../DownloadStateMachineTest.kt`、`reader/src/test/.../EpubValidatorTest.kt` | 状态机/重试与 5 项 EPUB 安全测试 |
| `docs/04-在线书库统一化/阶段E-实施报告.md` | 本报告 |

## 3. 修改文件及原因

| 文件 | 修改原因 |
|---|---|
| `core/.../api/ZLibraryApi.kt` | 新增受限的签名链接下载；不携带 Cookie、HTTPS 重定向校验、体积上限 |
| `reader/.../zlibrary/ZLibraryBookProvider.kt` | 从「下载不支持」改为实际流式下载并映射错误码 |
| `reader/.../db/AppDatabase.kt` | 版本 5→6、注册两表 DAO、增加非破坏性 Migration(5,6) |
| `app/.../ui/online/OnlineLibraryViewModel.kt` | 创建下载管理器、恢复中断任务、从详情页入队下载 |
| `app/.../ui/online/BookDetailScreen.kt` | 启用「下载书籍」按钮、请求通知权限 |
| `app/.../ui/online/CatalogLibraryScreen.kt` | 显示映射表提供的「已下载」标记 |
| `app/.../MainActivity.kt` | 顶部下载入口改到统一下载管理页；删除本地书后失效在线映射 |
| `docs/README.md` | 文档索引加入阶段 E |

未修改：`core/cloud/*`、`core/protocol/*`、`core/position/*`、`EpubImporter.kt`、`EpubImportFiles.kt`、`CoverExtractor.kt`、`LibraryStore.kt`、阅读器与朗读代码、`library.json` 与进度协议、测试版包名与应用名。

## 4. 编译与测试结果

- `./build-local.sh test :app:assembleTtsTestDebug`：**成功，249 项测试、0 失败、0 错误**（阶段 D 为 240 项，新增 9 项）。
- 构建过程中修复了 3 个编译问题：ZIP 魔数读取类型、app 层不应直接引用 Room 类型、原子移动不支持时的回退；已全部验证。
- 产物：`app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk`（161 MB），包名 `com.yuezhou.reader.tts.test` 未变化。

## 5. 真机验证状态

- 本次 `adb devices` 仍未列出设备，**未执行安装与真机下载验证**；APK 已生成，连接设备后可直接安装。
- 建议验证：登录 Z-Library → 搜索 → 详情 → 下载（含切后台保活）→ 下载管理进度/取消 → 完成后阅读 → 同名覆盖确认（取消保留原书、确认后进度书签保留）→ 损坏 EPUB/HTML 错误页拒绝入库 → 导入失败独立重试 → 删除本地书后「已下载」标记消失 → 数据库从 v5 升级到 v6 后旧数据完好。

## 6. 已知问题及风险

1. 未使用合法授权账号做真实服务端下载实测；非公开 API 的下载端点可能变化。
2. 进程重启后不会自动续传/重申请额度，需手动重试；这是有意的额度保护策略。
3. 正在阅读的书籍不直接覆盖，提示关闭后重试；未实现"等待阅读器释放"的无限等待。
4. 断点续传未实现（服务端 Range 支持未知），重试会重新下载整本；额度按服务端规则重新计算。
5. AGPL 参考仅保留设计记录，Kotlin 独立实现；公开分发/商业化需重新评估许可义务。

## 7. 暂停声明

阶段 E 已完成并暂停，**不会自动进入阶段 F（云端上传联动与 KOReader 同步兼容）**。确认后再继续。
