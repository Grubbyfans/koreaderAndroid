# 阶段 B 实施报告

对应方案：`docs/静读在线书库统一化与多书源扩展技术方案.md`（阶段 A 最终确认修订）
分支：`feature-zlibrary` | 节点提交：`dca20fe`（B1）、`3d65ebb`（B2）
状态：**阶段 B 已完成并暂停，等待确认；未进入阶段 C**

---

## 1. 本阶段实现的功能

- **B1 书源抽象与云适配**
  - 新增统一在线书籍模型 `OnlineBook`（不与 `CloudSync.RemoteBook` 冲突）、检索条件/分页/连接状态/错误分类/下载与上传结果模型。
  - 新增 `BookProvider / CloudBookProvider / CatalogBookProvider / ProviderCapability`，能力驱动 UI；检索型书源不实现 `CloudBackend`。
  - 新增 `ProviderRegistry`：注册、启停、默认书源、能力查询、连接状态汇总；纯逻辑、无 Android 依赖。
  - 新增 `BaiduBookProvider`、`WebDavBookProvider`，委托现有 `CloudSync`，负责 `OnlineBook ↔ CloudSync.RemoteBook` 映射与错误分类。
  - `CloudSync` 为后端选择/列表/刷新/发现/上传/下载/删除增加可选 `forced: BackendKind?`；默认参数保持原有行为，解决「选了 WebDAV 却操作百度」的串源风险。
- **B2 统一入口与书源切换**
  - 书架页「云端」作用域内新增统一在线书库入口：顶部书源 Tab（百度网盘 / WebDAV，Z-Library 禁用占位）+ 下载、书源设置统一入口。
  - 每个书源独立保存列表、加载、刷新、错误、缓存时间与来源状态；切换书源不重复加载已加载数据；下拉/按钮主动刷新才强制重拉。
  - 沿用原有云端卡片、空态、封面、详情弹窗、下载与删除弹窗、前台服务通知；`CloudSync.RemoteBook` 与 KOReader 协议零改动。
  - 按最终确认：底部导航仍为「书架 / 设置」两项；本地书库页面不变。

## 2. 新增文件及用途

| 文件 | 用途 |
|---|---|
| `core/.../core/online/model/OnlineBook.kt` | 在线书源统一书籍模型 |
| `core/.../core/online/model/SearchQuery.kt` | 检索条件与分页结果（`pageSize=30`，含缓存字段） |
| `core/.../core/online/model/ProviderModels.kt` | 连接状态、错误码、下载/上传请求与结果 |
| `core/.../core/online/provider/BookProvider.kt` | 书源接口与云/检索两类子接口 |
| `core/.../core/online/provider/ProviderCapability.kt` | 能力枚举 |
| `core/.../core/online/provider/ProviderRegistry.kt` | 书源注册/启停/默认源/状态汇总 |
| `core/src/test/.../online/ProviderContractTest.kt` | 4 项 Provider 契约单测 |
| `reader/.../reader/online/cloud/CloudBookProviderBase.kt` | 云书源适配器基类（映射、错误分类、委托） |
| `reader/.../reader/online/cloud/BaiduBookProvider.kt` | 百度网盘书源 |
| `reader/.../reader/online/cloud/WebDavBookProvider.kt` | WebDAV 书源 |
| `app/.../reader/ui/online/OnlineLibraryViewModel.kt` | 统一入口状态容器，按书源隔离状态 |
| `app/.../reader/ui/online/OnlineLibraryScreen.kt` | 书源 Tab + 复用现有云端书库组件 |

## 3. 修改文件及原因

| 文件 | 修改原因 |
|---|---|
| `reader/.../reader/CloudSync.kt` | 增加可选 `forced` 后端定向，供书源适配器准确操作所选云端；清理因该参数产生的多余非空断言；原有调用行为不变 |
| `core/.../core/online/model/SearchQuery.kt`（B2 微调） | `BookPage` 增加 `fromCache/fetchedAt`，沿用现有云端缓存展示 |
| `reader/.../online/cloud/CloudBookProviderBase.kt`（B2 微调） | 透传缓存信息 |
| `app/.../reader/MainActivity.kt` | 云端作用域接入统一入口；ViewModel 状态同步到既有云端 UI 状态；详情下载/删除传入所选后端；`CloudBookshelfContent` 改为 internal 供新页面复用 |
| `app/.../reader/BookUploadControls.kt` | `pushLocal` 增加可选参数后，改为命名参数调用，行为不变 |

未修改：`core/cloud/*`、`core/protocol/*`、`core/position/*`、阅读器与朗读代码、`library.json` 与进度协议、数据库、测试版包名与应用名。

## 4. 实际页面布局及交互效果

- 底部导航保持「书架 / 设置」。书架页选择「云端」后，页面顶部为书源 Tab：`百度网盘`、`WebDAV`（按配置启用）与灰显的 `Z-Library` 占位；右侧为「下载」「书源设置」文字按钮。
- 下方沿用原云端书库网格（3 列）、封面角标、最后更新时间/缓存标识、空态与错误重试；「更新书库」按钮仍可用，主动刷新时显示顶部进度条。
- 点击书籍沿用原详情弹窗：显示文件名、大小、后端、是否已下载；可下载/打开/删除云端书籍；下载使用前台服务通知。
- 切换书源：已加载书源直接显示原列表，不发重复请求；未加载书源首次进入才请求。网络错误保留旧列表并显示错误提示。
- 「书源设置」跳转现有设置页的云端配置区；「下载」打开现有下载任务弹窗（暂无任务时显示提示）。Z-Library 检索页尚未接入，Tab 仅占位不可点击。

## 5. 编译及构建结果

| 命令 | 结果 |
|---|---|
| `./gradlew :core:test :reader:compileDebugKotlin` | 成功 |
| `./gradlew :app:compileTtsTestDebugKotlin :core:testDebugUnitTest` | 成功 |
| `./gradlew test` | 成功，200 项测试、0 失败 |
| `./gradlew :app:assembleTtsTestDebug` | 成功，产物 `app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk`（161 MB） |
| `./gradlew :app:lintTtsTestDebug` | **失败**：3 个既有错误（见第 7 节） |

> 本机 PATH 无 Java，构建使用 `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`。

## 6. 功能测试及回归测试结果

- 新增 `ProviderContractTest` 4 项：注册顺序与默认书源、禁用默认后回退、注销后状态更新、能力区分云/检索书源 —— 全部通过。
- 全量 JVM 单测 200 项通过（core / reader / speech / novel-text），无失败。
- 测试版 APK 构建成功，包名 `com.yuezhou.reader.tts.test`、应用名「静读测试版」未变化。
- 未做真机/模拟器人工交互验证（见第 8 节）。

## 7. 已知问题及潜在风险

1. **lint 既有错误 3 个**：`MainActivity.kt` 第 250 行 `WrongConstant`（`takePersistableUriPermission`）、第 1699/1767 行 `NonObservableLocale`。三者均不在本次 diff 内，按「不做无关重构」原则未修改；下次可单独处理。
2. **Z-Library 未接入**：阶段 B 仅显示禁用 Tab；检索、登录、下载在阶段 C/D/E 实现。
3. **下载管理为既有单任务弹窗**：多任务队列、重启恢复在阶段 E 实现。
4. **同名覆盖尚未实现**：阶段 B 未新增下载/导入路径；文件名唯一判断与覆盖确认将在阶段 E 落地，现有云端下载仍沿用原同名覆盖行为。
5. **书源配置入口复用设置页**：独立书源设置页在阶段 C 随 JSON 配置导入一起实现。
6. `CloudSync` 新增参数均带默认值，但调用方如使用位置参数需确认参数顺序；已修正 `BookUploadControls` 的调用。

## 8. 需要人工验证的事项

- 在已配置百度网盘和/或 WebDAV 的测试版设备上：进入书架→云端，确认书源 Tab 显示、切换后列表正确且无重复加载、「更新书库」可用。
- 分别只配置单一后端时，切换 Tab 后下载/删除是否作用于所选云端（forced 后端定向）。
- 未配置任何云端时，错误提示与重试入口是否正常。
- 本地书库、原生 TTS、Kokoro 离线朗读、KOReader 进度双向同步的回归冒烟。
- 测试版与正式版共存、测试版升级保留数据。

---

阶段 B 完成后暂停，等待用户确认后再进入阶段 C。
