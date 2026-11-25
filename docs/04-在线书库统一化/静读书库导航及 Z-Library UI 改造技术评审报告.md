# 静读书库导航及 Z-Library UI 改造技术评审报告

- 评审基线：`feature-zlibrary` / `15b09f7`（阶段 F、G 及搜索降级修复之后）
- 评审方式：只读源码审计、现有测试与真机行为核对；本次未修改任何代码
- 评审结论：**整体可行，有条件通过**。导航提升与布局压缩的成本可控；需先修正 4 个设计冲突/实现缺陷，再进入开发（见 §5、§10）

---

## 0. 结论摘要

| 需求 | 现状 | 结论 |
| --- | --- | --- |
| 底部导航改为 书库 / 云端 / 设置 | 当前仅 书库 / 设置，云端是书库页内作用域 | 可行，改动集中在 `MainActivity` |
| 移除顶部「本地 / 云端」切换 | 已有 `BookScopeSwitch` | 可行，可直接删除 |
| 云端三数据源紧凑 Tab | 已有书源 `FilterChip` 行 | 可行，样式替换 |
| Z-Library 展示面积增加 | 搜索/语言/排序/额度/提示全部固定 | 可行，需重排头部 |
| 筛选收进底部弹窗、统一提交 | 筛选 chip 直接改状态，不自动请求 | 可行，需引入草稿态与“应用” |
| 账户/额度移入更多菜单 | 常驻额度文本 | 可行，需新增顶栏菜单并调整加载时机 |
| 网格/列表模式并持久化 | 两种模式已实现，仅内存保存 | 列表模式可复用，持久化需新增存储 |
| 搜索被拒展示热门书单 | 已实现，但提示固定、与关键词共用一个结果区 | 需按“数据源”拆分刷新与提示 |
| WebView 与原生下载 | 阶段 C/D 已完成，链路稳定 | 保留，不改实现 |
| 状态在切 Tab/返回时保留 | ViewModel 可保留，但滚动会被重置 | 需修复滚动恢复与重复请求 |

---

## 1. 现有根导航结构及书库页面架构

### 1.1 导航形态

项目没有使用 Navigation Compose / NavHost，而是单 Activity + Compose 状态切换：

- 根入口：[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L188-L201) 的 `BookshelfScreen()`。
- 一级状态只有两个：

```kotlin
private enum class Tab(val label: String, val icon: ImageVector) {
    书架("书库", Icons.Filled.List),
    设置("设置", Icons.Filled.Settings),
}
```

见 [MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L1073-L1082)。

- 「本地 / 云端」不是一级导航，而是书库页内的 `BookScope`：

```kotlin
private enum class BookScope(val label: String) { 本地("本地"), 云端("云端") }
```

- `currentTab`、`bookScope` 通过 `rememberSaveable` + 自定义 `Saver` 保存，跨 Activity 重建/从桌面返回可恢复（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L178-L201)）。

### 1.2 页面组合

```text
MainActivity
└─ Scaffold(topBar, bottomBar, FAB)
   ├─ Tab.书架
   │  ├─ BookScopeSwitch                 // 本地 / 云端
   │  ├─ BookScope.本地 → BookshelfContent
   │  └─ BookScope.云端 → OnlineLibraryScreen
   │        ├─ 书源 FilterChip 行
   │        ├─ CatalogBookProvider → CatalogLibraryScreen（Z-Library）
   │        └─ 其它 → CloudBookshelfContent（百度网盘 / WebDAV）
   └─ Tab.设置 → SettingsPage
        └─ 二级全屏页：同步日志 / Z-Library 设置 / 服务器设置
```

关键位置：

- Tab 内容分支：[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L561-L614)
- 底部导航：[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L2245-L2278)
- 顶栏与动作：[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L474-L495)
- 悬浮按钮：[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L497-L527)

### 1.3 顶栏、底栏、返回栈

- 顶栏标题按 Tab 切换：`if (currentTab == Tab.书架) "书库" else "设置"`；右上角仅在书库显示下载入口（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L474-L495)）。
- 管理态（多选）会整条替换顶栏，条件是 `currentTab == 书架 && bookScope == 本地`（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L406-L467)）。
- 底栏在 `fullscreenSubPage` 时隐藏；该状态只包含三个设置二级页，**不存在“搜索后强制全屏”的现有逻辑**（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L388-L399)、[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L529-L533)）。
- 返回键按顺序消费：管理态 → 服务器设置 → Z-Library 设置 → 同步日志 → 在线下载页 → 书籍详情（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L383-L399)、[OnlineLibraryScreen.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryScreen.kt#L70-L78)）。

---

## 2. 本地书库与云端书库的组件、状态及业务耦合

### 2.1 本地书库

- 数据：`LibraryStore` + `LocalScanner`，`books` 是 `remember` 状态，`refreshKey` 触发重载（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L204-L217)）。
- 展示：`BookshelfContent` 内部 `LazyVerticalGrid`，没有托管 `LazyGridState`，切 Tab 离开组合后滚动位置会丢失（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L1395-L1439)）。
- 与云端仅共享外层 Scaffold、顶栏、底栏；数据层无耦合。

### 2.2 云端书库

- 统一 ViewModel：`OnlineLibraryViewModel` 在 Activity 级创建（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L221)），内部以 `states: Map<providerId, ProviderUiState>` 保存每个书源状态（[OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt#L31-L85)）。
- `ProviderUiState` 同时承载云盘列表与 Z-Library 检索字段：`keyword`、`submittedKeyword`、`language`、`sort`、`searched`、`nextCursor`、`hasMore`、`notice`、`gridMode`、`scrollIndex`、`quotaText` 等（[OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt#L31-L61)）。
- 书源注册表与下载管理器共用单例：`DownloadManagerHolder.runtime()`，注册顺序为百度网盘、WebDAV、Z-Library（[DownloadManagerHolder.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/DownloadManagerHolder.kt#L42-L85)）。
- 现有页面：`OnlineLibraryScreen` 顶部书源 `FilterChip`，Z-Library 走 `CatalogLibraryScreen`，其余走 `CloudBookshelfContent`（[OnlineLibraryScreen.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryScreen.kt#L33-L67)）。

### 2.3 耦合点

1. **旧云端弹窗状态镜像**：Activity 把 `selectedState.items` 转成 `CloudSync.RemoteBook`，驱动既有详情/下载弹窗（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L223-L241)、[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L337-L356)）。
2. **刷新入口**：云端 FAB 调用 `refreshRemoteLibrary()`，对 Z-Library 走 `refreshSelected()`，对云盘走 `CloudSync.refreshLibrary()` + 外部书籍发现（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L358-L372)、[OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt#L182-L204)）。
3. **下载任务入口**：顶栏下载按钮与全屏 `DownloadManagerScreen`（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L481-L488)、[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L618-L636)）。
4. **书源配置跳转**：`onOpenSettings` 直接 `currentTab = Tab.设置` 并打开 Z-Library 设置（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L596-L600)）。

本地与云端之间没有共享数据表或同步状态；耦合主要是 UI 容器与 Activity 级状态。

---

## 3. 将云端书库提升为一级导航的可行性

### 3.1 结论

**可行，且属于本次改造中风险最低的部分。** 不需要引入 Navigation Compose，也不需要改数据层。

### 3.2 最小改动点

| 位置 | 改动 |
| --- | --- |
| `Tab` 枚举（L1073） | 增加 `云端("云端", ...)` |
| `BottomNavBar`（L2245） | 增加第三个 `NavigationBarItem`；图标需用 core 图标或自绘 `ImageVector`，避免引入 `material-icons-extended` |
| `BookshelfScreen`（L200） | 删除 `bookScope` 与 `BookScopeSaver`；`Tab.书架` 固定本地 |
| `BookScopeSwitch`（L1181） | 删除 |
| `when (currentTab)`（L561） | 新增 `Tab.云端 -> OnlineLibraryScreen(...)` |
| 顶栏（L474） | 标题改为「书库 / 云端书库 / 设置」；右上角按 Tab 显示更多菜单/下载入口 |
| 管理态顶栏条件（L406） | `currentTab == 书架 && bookScope == 本地` → `currentTab == 书库` |
| FAB（L497） | 仅本地导入保留；云端改为下拉刷新/顶栏刷新，不再悬浮 |
| 切换加载（L376） | `LaunchedEffect` 改为进入 `Tab.云端` 时 `loadRemote()` |
| 详情/下载/设置跳转 | 保持现有 overlay 与全屏二级页 |

### 3.3 风险点

- 顶栏条件散落在管理态、普通态、动作区、FAB 四处，必须一起改，否则会出现「云端页仍显示已移除/多选」等串态。
- `Tab.书架` 改名/改语义后，`TabSaver` 保存的旧字符串仍可恢复；但 `BookScopeSaver` 需一并删除，避免残留状态。
- 删除云端 FAB 会丢掉「云盘外部上传发现」的唯一手动入口，必须由下拉刷新或顶栏刷新承接（见 §5）。

---

## 4. 现有 Z-Library 搜索、降级、WebView 与下载调用链

### 4.1 搜索与降级

```text
CatalogLibraryScreen 搜索按钮
  → OnlineLibraryViewModel.search()
  → doSearch()
  → ZLibraryBookProvider.search()
  → ZLibraryApi.search()
       └─ SERVER_REJECTED
            → ZLibraryApi.getMostPopular()
            → BookPage(notice=..., nextCursor=null, hasMore=false)
  → ViewModel 按 notice 判定 fallback，写入 items/notice
```

关键位置：

- 搜索入口与请求代次：[OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt#L253-L277)
- 分页与旧响应防护：[OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt#L279-L286)、[OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt#L288-L331)
- 降级判定：[ZLibraryBookProvider.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/reader/src/main/java/com/yuezhou/reader/reader/online/zlibrary/ZLibraryBookProvider.kt#L101-L125)
- 热门接口与提示文案：[ZLibraryApi.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/core/src/main/java/com/yuezhou/reader/core/online/api/ZLibraryApi.kt#L103-L127)

现状特点：

- 关键词结果与热门书单共用 `items`、`nextCursor`、`hasMore`；仅靠 `notice != null` 区分。
- 热门书单固定一页，无分页；降级时 `hasMore=false`、`nextCursor=null`。
- `requestSeq` 能防止旧搜索/旧分页覆盖新结果。
- 降级请求失败时会抛出原始 `SERVER_REJECTED`，界面显示“原生搜索暂不可用”，但没有热门加载失败与网页搜索的专用空态。

### 4.2 WebView 与下载

```text
ZLibraryWebEntry.launch(baseUrl, keyword)
  → ZLibraryWebActivity（:zlibraryweb 独立进程、独立数据目录）
  → DownloadListener
  → 用户确认
  → ZLibraryWebDownloadStarter → 系统 DownloadManager
  → ZLibraryDownloadHandoffProvider 登记
  → 主进程 ZLibraryWebDownloadWatcher
  → BookDownloadManager.acceptCompletedDownload() 校验/命名/导入
```

关键位置：

- 入口只接受 HTTPS 与关键词：[ZLibraryWebEntry.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/web/ZLibraryWebEntry.kt#L13-L30)
- WebView 隔离与下载监听：[ZLibraryWebActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/web/ZLibraryWebActivity.kt#L168-L195)
- 下载完成后的任务状态与书库刷新：[BookDownloadManager.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/reader/src/main/java/com/yuezhou/reader/reader/online/download/BookDownloadManager.kt#L74-L77)、[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L315-L317)

这条链路不建议在本阶段改动；UI 重排只改变入口位置与关键词传递。

### 4.3 当前 Z-Library 页面空间占用

`CatalogLibraryScreen` 是 `Column` + 固定头部 + `LazyVerticalGrid`：

| 区域 | 位置 | 约占用 |
| --- | --- | --- |
| 搜索框 + 搜索 + 网页搜索 | L100-L123 | 64dp |
| 语言 chips | L125-L140 | 48dp |
| 排序 chips + 格式 + 模式切换 | L142-L166 | 52dp |
| 额度文本 | L168-L173 | 20dp |
| 错误/提示文本 | L175-L191 | 0–44dp |
| 书籍网格 | L245-L280 | 剩余空间 |

再叠加外层标题栏、本地/云端切换、书源 chips、底部导航，首屏书籍区仅约 250dp；键盘弹出时更低。这是用户反馈“展示区域太小”的直接原因。

---

## 5. 本方案与现有代码的冲突及遗漏

| # | 问题 | 说明 | 建议 |
| --- | --- | --- | --- |
| 1 | **“继承已有登录会话”不可按字面实现** | 原生 API 使用加密凭证；WebView 使用独立数据目录与会话（[ZLibraryWebActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/web/ZLibraryWebActivity.kt#L168-L195)）。阶段 D 明确禁止转发 Cookie/认证头去 CDN。 | 改为：WebView 沿用**自身**已保存的登录态；原生账号仅传递关键词，不复制凭证。若未登录，由网页内登录。 |
| 2 | **筛选条件继承到 WebView 缺少验证** | 网页入口目前只支持 `/s/<keyword>`；语言/排序的网页参数未验证。方案 §3.2 也要求“不新增未经验证的接口筛选参数”。 | 只继承关键词；语言/排序不带入网页，避免构造未验证 URL。 |
| 3 | **热门书单无分页，不能做“独立分页”** | `getMostPopular()` 固定 `page=1`，返回 `nextCursor=null`。 | 独立的是“数据源与刷新状态”，不是分页；热门只保留一页。文档需修正。 |
| 4 | **百度网盘/WebDAV 没有目录层级** | `CloudSync` 提供扁平远端书籍列表，`CloudBookshelfContent` 无目录浏览/浏览历史。 | “保留目录位置”改为“保留列表、已加载状态与滚动位置”；目录能力如未来新增再单独设计。 |
| 5 | **删除云端 FAB 会丢失刷新/外部发现入口** | FAB 当前调用 `refreshRemoteLibrary()`；云盘模式下会 `CloudSync.refreshLibrary()` 并发现外部上传（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L358-L372)）。 | 下拉刷新必须调用同一逻辑；同时建议云端顶栏保留刷新图标，避免仅靠手势。 |
| 6 | **滚动恢复会被现有 Effect 重置** | `LaunchedEffect(state.submittedKeyword)` 在页面每次重新进入组合时都会执行 `scrollToItem(0)`，覆盖 `rememberLazyGridState` 的恢复值（[CatalogLibraryScreen.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/CatalogLibraryScreen.kt#L92-L97)）。 | 改为“一次性待滚动标记”：`search()` 时置位，UI 消费后清除；返回页面不置位。 |
| 7 | **额度每次进入页面都会请求** | `LaunchedEffect(Unit) { vm.loadQuota() }` 在重新进入组合时重复执行（L97）。 | 移到“更多菜单打开时”或按时间节流（如 60s）。 |
| 8 | **展示模式未持久化** | `gridMode` 仅存在 `ProviderUiState`（L54），进程死亡后回到网格默认。 | 新增 DataStore/SharedPreferences 键；切换只改状态，不请求接口。 |
| 9 | **云盘书籍滚动未保留** | `CloudBookshelfContent` 的 `LazyVerticalGrid` 没有 `state`（L1289-L1295）。 | 复用 `ProviderUiState.scrollIndex`，按书源分别保存；用 `key(providerId)` 隔离。 |
| 10 | **列表/网格切换后滚动索引语义不同** | 网格是 3 列，列表是 1 列，同一个 `scrollIndex` 不完全等价。 | 切换模式时回到顶部（不请求），或分别保存两套位置。 |
| 11 | **提示条不随列表滚动，且无操作** | 当前 `notice` 是网格上方的 `Text`（L184-L191）。 | 改为 `LazyVerticalGrid` 的整行 header，带「重新搜索」「网页搜索」。 |
| 12 | **热门加载失败没有专用空态** | 失败时抛原始拒绝错误，列表为空时显示“没有找到相关书籍”，网页入口只在顶部。 | 空态/错误态增加“网页搜索”按钮与失败原因。 |
| 13 | **下载入口归属需要重新定义** | 顶栏下载图标只在 `Tab.书架` 显示（L482）。云端成为一级后，若仍只在书库显示，云端页没有任务入口。 | 云端顶栏保留下载图标，或放入更多菜单；两者不要重复堆叠。 |
| 14 | **“底栏始终显示”与设置二级页冲突** | 设置二级页目前全屏隐藏底栏（L391、L529）。 | 保留设置二级页全屏；该要求只针对搜索页，不要把设置页改成常驻底栏。 |
| 15 | **无 UI/ViewModel 自动化测试** | 现有 352 项测试集中在 core/reader；`OnlineLibraryViewModel` 与 Compose 页面没有测试。 | 至少为“数据源切换/刷新语义/旧响应”提取纯状态逻辑并补 JUnit；UI 走真机验收。 |
| 16 | **热门书单缺少格式字段** | 热门条目通常没有 `extension`，列表显示“格式未知”。 | 属于信息真实性问题，不建议猜测；可在列表模式隐藏单独格式行，或在详情中补全。 |

---

## 6. 需要新增、修改或复用的具体文件清单

### 6.1 新增

| 文件 | 作用 |
| --- | --- |
| `app/.../ui/online/ZLibraryFilterSheet.kt` | 语言/排序筛选底部弹窗（草稿态 + 应用/重置），格式仅展示 EPUB |
| `app/.../ui/online/OnlineUiPreferences.kt` | 轻量持久化：网格/列表模式；可选保存上次书源 |
| `app/src/test/.../online/CatalogSourceStateTest.kt`（建议） | 若把数据源/刷新判定抽成纯逻辑，覆盖关键词/热门/失败三态 |

### 6.2 修改

| 文件 | 改动 |
| --- | --- |
| [MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt) | 三 Tab、删除 `BookScope`、顶栏更多菜单、FAB 条件、加载触发、详情/下载/设置跳转条件 |
| [OnlineLibraryScreen.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryScreen.kt) | 书源 chips → 紧凑 Tab；透传刷新/详情；保持 `BackHandler` |
| [CatalogLibraryScreen.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/CatalogLibraryScreen.kt) | 紧凑工具栏、筛选摘要、弹窗宿主、提示条入列表、下拉刷新、滚动恢复修复、列表/网格切换位置 |
| [OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt) | `resultSource`、按源刷新、筛选统一应用、模式持久化、额度加载时机、待滚动标记 |
| [ZLibraryBookProvider.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/reader/src/main/java/com/yuezhou/reader/reader/online/zlibrary/ZLibraryBookProvider.kt) | 暴露 `mostPopular()` 供“刷新当前热门数据源”复用；不改拒绝判定 |
| [CloudBookshelfContent](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L1216-L1303)（同文件） | 接收并保存 `LazyGridState`；按书源恢复滚动 |

### 6.3 复用，不改

- 列表模式 UI：`ListBookCard` 已实现书名、作者、格式、大小（[CatalogLibraryScreen.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/CatalogLibraryScreen.kt#L330-L361)），无需新增组件。
- 下拉刷新：Compose BOM `2026.09.00` 的 Material3 已提供 `PullToRefreshBox`，无需新依赖。
- 下载与导入：`BookDownloadManager`、`ZLibraryWebDownloadWatcher`、`ZLibraryWebEntry`、WebView 容器全部保持现状。
- 本地书库、阅读器、朗读、CloudSync、上传队列、数据库、同步协议：不改动。

---

## 7. 页面状态保留及生命周期管理方案

### 7.1 状态归属

`OnlineLibraryViewModel` 是 Activity 级，切换底部 Tab 不会销毁；`states` 按书源保存。因此“切 Tab / 从详情、WebView 返回”天然可保留数据，问题主要在两个 Effect 与未持久化字段。

| 状态 | 当前存放 | 切 Tab | 返回 | 进程死亡 | 目标 |
| --- | --- | --- | --- | --- | --- |
| 关键词 / 已提交关键词 | ViewModel | 保留 | 保留 | 丢失 | 保持现状 |
| 语言 / 排序 | ViewModel | 保留 | 保留 | 丢失 | 保持现状 |
| 当前书源 | ViewModel `selectedProviderId` | 保留 | 保留 | 重置为 baidu | 可选持久化 |
| 关键词结果 / 分页 | ViewModel | 保留 | 保留 | 丢失 | 保持现状，增加数据源标记 |
| 热门书单 | 与 `items` 共用 | 保留 | 保留 | 丢失 | 增加 `resultSource`，刷新按源 |
| 滚动位置 | ViewModel `scrollIndex` | 保留 | **当前会被重置** | 丢失 | 修复 Effect；可选保存首项 key |
| 网格/列表 | ViewModel | 保留 | 保留 | 丢失 | DataStore 持久化 |
| 云盘列表 | ViewModel | 保留 | 保留 | 丢失 | 保持现状 |
| 云盘滚动 | 未保存 | 丢失 | 丢失 | 丢失 | 复用 `scrollIndex` |
| 详情 | ViewModel `detail` | 离开 Tab 后重新进入会再次显示 | 保留 | 丢失 | 明确策略：切 Tab 时关闭或保留 |
| 额度文本 | ViewModel | 保留 | 每次进入重查 | 丢失 | 菜单打开时按需刷新 |

### 7.2 防旧请求

- 现有 `requestSeq[providerId]` 已覆盖搜索与分页；新搜索会 `jobs[id]?.cancel()`（[OnlineLibraryViewModel.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryViewModel.kt#L253-L331)）。
- 新增“按源刷新”时必须继续走同一 `requestSeq`，否则热门刷新可能覆盖新关键词结果。
- 切换书源不需要取消旧请求：结果按 `id` 写回各自状态，不会串源。

### 7.3 生命周期

- WebView 独立进程，返回主界面不重建 Activity；`OnlineLibraryViewModel` 不受影响。
- 设置二级页全屏期间，云端页面离开组合，返回后 `LaunchedEffect(Unit)`、`LaunchedEffect(submittedKeyword)` 会重跑，需按 §5 修正。
- 从桌面返回（Activity 重建）：Tab/书源作用域由 `rememberSaveable` 恢复；列表数据会重新加载，但缓存命中时不发网络请求。

---

## 8. 对百度网盘、WebDAV、进度同步等模块的影响

| 模块 | 影响 | 结论 |
| --- | --- | --- |
| 百度网盘 / WebDAV 列表 | 入口从「书库 → 云端」变为一级「云端」，数据仍由 `OnlineLibraryViewModel` + `CloudSync` 提供 | 行为不变 |
| 云盘刷新 / 外部上传发现 | 现由 FAB 触发；改为下拉刷新或顶栏刷新后调用同一 `refreshRemoteLibrary()` | 需保证入口存在 |
| 云盘详情 / 下载弹窗 | 仍由 Activity 的 `detailBook` / `remoteBooks` 驱动 | 不改 |
| 上传队列 / 删除 | 不涉及导航状态 | 无影响 |
| 阅读进度同步 | 在 CloudSync / ReaderActivity 内，与书库导航无关 | 无影响 |
| 本地书库 | 仅移除顶部作用域切换条，`BookshelfContent` 与导入逻辑不变 | 布局略微上移 |
| 阅读器 / 朗读 | 不经过本次页面 | 无影响 |
| 下载任务与本地刷新 | `libraryVersion` 已联动本地书库刷新（[MainActivity.kt](file:///Users/zhou/workcode/studyCode/koreaderAndroid/app/src/main/java/com/yuezhou/reader/MainActivity.kt#L315-L317)） | 保持 |

唯一需要回归的重点是：云端页的一键刷新是否仍能发现外部上传，以及下载完成后的“已下载”标记是否仍随 `activeRemoteIds` 更新。

---

## 9. 改造风险、工作量及推荐实施顺序

### 9.1 风险

| 风险 | 等级 | 说明 | 缓解 |
| --- | --- | --- | --- |
| 顶栏/底栏条件遗漏 | 中 | 管理态、下载入口、更多菜单分散在四处 | 一次性收敛为 `when (currentTab)` 的页面配置 |
| 状态被 Effect 重置 | 中 | 已确认滚动会被重置 | 用待滚动标记替代按 keyword 的 Effect |
| 下拉刷新与分页冲突 | 中 | 刷新期间触发 loadMore 可能产生双请求 | 复用 `requestSeq`，刷新时禁用 loadMore |
| 删除 FAB 丢失云盘刷新 | 中 | 现 FAB 是唯一手动入口 | 下拉刷新 + 顶栏刷新双入口 |
| 热门/关键词混淆 | 中 | 共用 `items` | 增加 `resultSource`，刷新按源分支 |
| DataStore 键迁移 | 低 | 新增键有默认值 | 默认网格模式，读取失败回退 |
| 图标依赖 | 低 | 只有 icons-core | 用 core 图标或自绘 24dp path |
| WebView 登录预期 | 低 | 用户可能以为会继承原生登录 | 在 UI/文档明确“网页内登录” |

### 9.2 工作量（按改动面评估，不含时间）

- 导航改造：中等，集中在 `MainActivity` 约 5 处条件与 1 个枚举/底栏。
- Z-Library 布局与筛选弹窗：中等，`CatalogLibraryScreen` 头部整体重写，新增 1 个弹窗文件。
- 状态与刷新语义：中等，`OnlineLibraryViewModel` 增加数据源标记与按源刷新，保持现有请求防护。
- 持久化与滚动修复：小到中等，新增 1 个偏好存储文件与 2 处 Effect 调整。
- 云盘滚动保留：小，`CloudBookshelfContent` 增加 state 参数。
- 测试与验证：中等，新增纯逻辑单测 + 真机回归（导航、三书源、搜索降级、WebView 下载）。

### 9.3 推荐顺序

1. **导航与顶栏**：三 Tab、移除作用域切换、更多菜单、FAB/下载入口、加载触发。
2. **Z-Library 布局**：紧凑工具栏、筛选摘要 + 弹窗、额度移入菜单、提示条入列表。
3. **状态与刷新**：`resultSource`、按源刷新、下拉刷新、旧请求防护回归。
4. **持久化与滚动**：展示模式持久化、滚动恢复修复、云盘滚动保留。
5. **验收与回归**：三书源、WebView 全链路、下载入库、切 Tab/返回/进程重建。

---

## 10. 基于实际源码修订后的最终技术方案

### 10.1 导航

- 底部导航固定三项：**书库**（本地）、**云端**、**设置**。
- `Tab.书架` 语义收敛为本地书库；删除 `BookScope`、`BookScopeSwitch` 与 `BookScopeSaver`。
- `Tab.云端` 标题为「云端书库」，右上角依次：下载任务图标、更多菜单。
- 底栏仅在设置二级页全屏时隐藏；不存在“搜索后全屏”，无需新增沉浸逻辑。
- 顶栏条件统一为 `when (currentTab)`，避免管理态、动作区、FAB 各自判断导致串态。

### 10.2 云端页面结构

```text
云端书库（标题栏 + 下载 + 更多）
├─ 紧凑数据源 Tab：百度网盘 / WebDAV / Z-Library
├─ Z-Library 专属：
│  ├─ 紧凑搜索工具栏（输入 + 搜索 + 网页）
│  ├─ 筛选摘要（全部语言 · 最相关 · EPUB） + 网格/列表切换
│  └─ 书籍区（下拉刷新）
│       ├─ [滚动] 提示条（热门/错误，含 重新搜索 / 网页搜索）
│       └─ 网格 3 列 或 紧凑列表
└─ 云盘专属：现有 CloudBookshelfContent + 下拉刷新
```

`下拉刷新` 与 `顶栏刷新` 对云盘都调用现有 `refreshRemoteLibrary()`；对 Z-Library 调用新增的“按当前数据源刷新”。

### 10.3 搜索工具栏

- 采用 40–44dp 紧凑输入框，去掉浮动 label，使用 placeholder。
- `KeyboardOptions(imeAction = ImeAction.Search)`，`KeyboardActions(onSearch = { vm.search(); keyboardController.hide(); focusManager.clearFocus() })`；搜索按钮同样收起键盘。
- 保留「网页」入口；未配置服务器时提示到设置。
- 传参：仅 `baseUrl + keyword`；登录沿用 WebView 自身会话，不复制原生凭证。

### 10.4 筛选

- 语言/排序/格式统一收进 `ModalBottomSheet`；格式只展示 EPUB（不新增参数）。
- Sheet 内使用草稿态，点「应用」后：
  - 未搜索过：只更新条件，不发请求；
  - 已搜索：调用一次 `search()`（内部取消旧请求、重置分页）。
- 固定区只保留一行摘要，点击打开 Sheet；右侧为网格/列表切换。

### 10.5 账户与更多菜单

- 移除常驻额度文本。
- 更多菜单内容：
  - 账号与额度（展示 `quotaText`，打开菜单时按需刷新）；
  - 账户管理 / 登录设置（跳现有 Z-Library 设置页）；
  - 网页搜索；
  - 下载任务（可选，与顶栏入口二选一，避免重复）。
- 非 Z-Library 书源只显示后端信息与书源设置。

### 10.6 热门降级

- `ProviderUiState` 增加 `resultSource: NONE | KEYWORD | POPULAR`。
- 搜索成功：`KEYWORD`，保留分页；`notice=null`。
- 搜索被拒且热门成功：`POPULAR`，`nextCursor=null`、`hasMore=false`，保留关键词与筛选，`items` 为热门。
- 下拉刷新：
  - `KEYWORD` → 按当前关键词/条件重新搜索；
  - `POPULAR` → 重新请求热门；
  - 失败保留旧列表并提示。
- 提示条作为列表 header，含「重新搜索」（重试当前关键词）与「网页搜索」；热门失败时列表空态同样提供网页入口。
- `getMostPopular()` 固定一页，不实现热门分页；关键词分页仍由 `nextCursor` 管理。
- 不修改 `SERVER_REJECTED` 判定与 `getMostPopular` 的降级触发条件。

### 10.7 展示模式与刷新

- 网格/列表复用现有 `GridBookCard` / `ListBookCard`。
- 默认网格；切换仅改状态并写入偏好，不请求接口；切换后回到顶部，避免两种布局的索引错位。
- 下拉刷新使用 Material3 `PullToRefreshBox`，刷新期间禁用“加载更多”。
- 移除云端悬浮刷新按钮；云盘刷新能力由下拉/顶栏承接。

### 10.8 状态与请求

- 保留 Activity 级 `OnlineLibraryViewModel` 与按书源 `states`。
- 修复滚动：
  - 删除按 `submittedKeyword` 的 `LaunchedEffect` 重置；
  - `search()` 设置 `pendingScrollToTop=true`，UI 消费后清除；
  - 重新进入页面沿用 `scrollIndex`。
- 额度：菜单打开时刷新，设置 60s 节流。
- 云盘：`CloudBookshelfContent` 接收 `LazyGridState`，按书源保存 `scrollIndex`；用 `key(providerId)` 保证切换书源时使用各自位置。
- 所有新请求继续使用 `requestSeq`；刷新、分页、筛选应用共享同一序列来源。

### 10.9 明确不纳入本次范围

- 标题栏随列表折叠、手动沉浸式、搜索历史、卡片进一步压缩（方案 §7 可选）。
- 原生凭证/Cookie 注入 WebView。
- 未验证的网页筛选参数。
- 热门书单分页。
- 云盘目录层级（当前架构没有该能力）。

### 10.10 验收对应

| 验收标准 | 落点 |
| --- | --- |
| 底部三项、移除本地/云端切换 | `Tab`、`BottomNavBar`、删除 `BookScope` |
| 三数据源可用 | `OnlineLibraryScreen` 紧凑 Tab + 原 registry |
| Z-Library 展示面积明显增加 | 头部重排 + 额度/提示移出固定区 |
| 筛选/账户不长期占位 | 筛选摘要 + 底部弹窗；额度入菜单 |
| 被拒仍展示热门 | 保留 provider 降级 + `resultSource` |
| 网页搜索/原生下载正常 | 入口迁移，链路不动 |
| 网格/列表切换 | 复用现有卡片 + 持久化 |
| 切 Tab/返回恢复状态 | ViewModel + 滚动修复 + 云盘 state |
| 其他功能无回归 | 本地库、CloudSync、阅读器、朗读不动 |

---

## 附录：评审依据与限制

- 已核对：`MainActivity` 导航/顶栏/底栏/FAB、`OnlineLibraryScreen`、`CatalogLibraryScreen`、`OnlineLibraryViewModel`、`ZLibraryBookProvider`、`ZLibraryApi`、`ZLibraryWebEntry`、`ZLibraryWebActivity`、`BookDownloadManager`、`DownloadManagerHolder`。
- 未做：代码修改、UI 自动化测试、性能基准。Compose 页面与 ViewModel 目前没有自动化测试，结论基于源码与既有真机行为。
- 评审基线提交：`15b09f7`；如后续代码变化，需按本报告行号重新核对。
