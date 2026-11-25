# 静读多级栏目滑动切换 阶段 A 实施报告

- 阶段范围：统一导航状态、页面模型、书源内容解耦（**未接入 HorizontalPager，未实现滑动手势**）
- 基准方案：《静读多级栏目滑动切换技术实施方案》V2（已评审通过）
- 结论：阶段 A 已完成，编译、Lint、单元测试、真机冒烟均通过，可进入阶段 A 评审

---

## 1. 实际修改文件及职责

### 1.1 新增

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/yuezhou/reader/ui/navigation/ShelfPage.kt` | 定义一级栏目 `Tab`、扁平页面模型 `ShelfPage`（LocalShelf / Cloud / CloudEmpty / Settings）与 `build(providerIds)`；`Tab` 与 Cloud 图标由 MainActivity 迁出，导航层不再反向依赖 Activity |
| `app/src/main/java/com/yuezhou/reader/ui/navigation/ShelfNavigator.kt` | 唯一已提交导航状态 `committedKey`，派生 `tab` / `providerId`；提供 `navigateTo`、`syncPages`、`cloudKey` 与回退链 |
| `app/src/test/java/com/yuezhou/reader/ui/navigation/ShelfNavigatorTest.kt` | 页面模型与导航状态单元测试（12 条） |

### 1.2 修改

| 文件 | 职责变化 |
| --- | --- |
| `MainActivity.kt` | 删除本地 `Tab` 枚举与 `cloudTabIcon()`，改用导航包；`currentTab` 改为 `navigator.tab` 派生；底部导航、返回键、顶栏更多菜单、账户管理入口统一走 `navigator`；页面切换后的加载/书源同步由 `LaunchedEffect(navigator.committedKey)` 驱动 |
| `OnlineLibraryScreen.kt` | 由「读取 ViewModel 全局选中」改为显式 `providerId` 入参；新增 `CloudEmptyState`；详情页覆盖层改为 `Box` 内兄弟节点（避免被 `fillMaxSize` 内容挤出可视区） |
| `CatalogLibraryScreen.kt` | 新增 `providerId` 入参并透传到 `CatalogResults` / 卡片 / 封面；所有 ViewModel 调用显式传 id；网格状态按 `catalog.$providerId` 独立恢复 |
| `BookDetailScreen.kt` | 新增 `providerId` 入参；下载、自动导入、返回均按显式 id 调用 |
| `OnlineLibraryViewModel.kt` | 新增 `isCatalog(providerId)`、`backendKindOf(providerId)`；`loadSelected/refreshSelected/refreshCurrent/openPopular/setKeyword/applyFilters/setGridMode/clearSearch/search/retrySubmittedSearch/loadMore/openDetail/closeDetail/enqueueDownload/setAutoImport/loadQuota/loadCover` 全部支持显式 `providerId`（默认值仍为当前选中，兼容旧调用） |

---

## 2. 核心架构实现

### 2.1 扁平页面与稳定 key

```text
[shelf.local] + [cloud.<providerId> …] or [cloud.empty] + [settings]
```

- key 是唯一稳定标识，不使用下标；书源增删、顺序变化都不影响定位。
- 全部书源被禁用时保留 `cloud.empty`，一级「云端」入口仍可达。

### 2.2 唯一已提交导航状态

- `ShelfNavigator.committedKey` 是运行期唯一导航真源，`currentTab` / 当前书源均由它派生，UI 中不再存在第二个导航状态。
- `restoredTab`（`rememberSaveable`）只用于**进程重建**时构造初始 key，运行期由 `LaunchedEffect(committedKey)` 回写，不再直接驱动界面。
- 回退链：原 key → 首个可用书源 → `cloud.empty` → 首个页面 → `shelf.local`；`navigateTo` 对无法识别的非云端 key 保持当前页，避免误跳。

### 2.3 书源内容解耦

- `OnlineLibraryScreen` / `CatalogLibraryScreen` / `BookDetailScreen` 全部改为 `providerId` 入参，读取 `vm.stateOf(providerId)`，不再依赖 `vm.selectedState`。
- `onlineVm.selectedProviderId` 仍保留，但只作为「上次书源持久化」与旧弹窗兼容字段，由 committed 页同步，不再是页面数据源。
- 云端详情/下载/额度/热门等操作的 providerId 均来自 committed 页，避免滑动/切换瞬间误操作到其他书源。

---

## 3. 原有业务行为对比

| 场景 | 阶段 A 前 | 阶段 A 后 | 结论 |
| --- | --- | --- | --- |
| 底部一级导航 | `currentTab` 状态赋值 | `navigator.navigateTo` | 行为一致 |
| 云端进入 | 切到云端即 `loadRemote()` | committed 为云端页时 `loadRemote()`；切换书源走 `selectProvider` | 行为一致 |
| 书源 Tab 切换 | 直接 `selectProvider`，检索源每次切页都查额度 | 仍切换并加载；检索源仅首次（`quotaText == null`）预热额度 | 请求更少，展示一致 |
| 云端返回键 | 云端 → 书库 | committed 云端页 → `shelf.local` | 行为一致 |
| 检索页返回层级 | 详情 > 筛选弹窗 > 清空检索 > 回书库 | 未改动 | 行为一致（真机已验证） |
| 详情页 | 覆盖在云端内容之上 | 同样覆盖在整页之上（修正了重构中一度放入 Column 的层级错误） | 行为一致 |
| 账户管理 | 切到设置并打开 Z-Library 设置 | `navigateTo(settings)` + 打开设置子页 | 行为一致 |
| 全部书源禁用 | 云端 Tab 仍显示但无内容 | 显示「暂无启用的书源 + 去设置」 | 新增空态，无业务回归 |
| 本地书库 / 阅读器 | 不变 | 不变 | — |

---

## 4. 网络请求变化及验证证据

### 4.1 变化点（唯一一处）

- 原 `selectProvider` 对检索型书源**无条件** `loadQuota()`，即每次点击 Z-Library（含每次回到云端页）都会查询一次额度。
- 现改为 `if (stateOf(providerId).quotaText == null) loadQuota(providerId)`：仅首次进入预热；打开「更多」菜单、点击额度项、主动刷新仍会查询。

审计结论：`loadQuota` 的调用场景仅为 ①进入检索书源 ②打开「更多」菜单 ③点击额度项 ④刷新检索结果（`refreshSelected`）。②③④全部保留，仅去掉了每次落页的重复查询。

### 4.2 其余请求保持不变

- `loadSelected` / `refreshSelected` 的 `loaded`、`loading`、`jobs`、`requestSeq` 短路逻辑未改，仅参数化 providerId。
- `discoverExternalBooks(forced = backendKindOf(id))` 语义与原来 `selectedBackendKind()` 一致。
- 书源切换、下拉刷新、热门书单、分页、详情补全的触发条件均未改变。

### 4.3 验证证据

- 真机（SM 系列，Android 15）多次切换百度网盘 / WebDAV / Z-Library，列表与状态各自独立、无重复加载。
- 进入 Z-Library 后反复切走再切回，额度文案保持上次结果，仅在打开菜单点击额度时刷新。
- 百度网盘进入即显示「后端：百度网盘 · 13 本 · 缓存 13:58」，与阶段 A 前一致。

---

## 5. 测试结果

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 单元测试（全模块） | `./gradlew test` | 43 个测试套件 / 354 条用例，0 失败 |
| 新增导航测试 | `:app:testTtsTestDebugUnitTest --tests "com.yuezhou.reader.ui.navigation.*"` | 12 条，0 失败 |
| Lint | `:app:lintTtsTestDebug` | 通过（仅既有 `Icons.Filled.List`、进度指示器等 deprecated 告警） |
| 打包 | `:app:assembleTtsTestDebug` | 成功，APK 已安装真机 |

新增用例覆盖：页面顺序与 key、空书源仅 CloudEmpty、进程恢复合法 key、恢复失效书源回退首个、全部禁用回退空态、书库/设置不派生书源、动态增删后按稳定 key 重定位、禁用后再启用、`cloudKey` 优先上次书源、无书源时指向空态、无效 key 保持当前页、合法 key 更新派生值。

真机冒烟结论：

- 一级导航（书库 / 云端 / 设置）正常，底部导航文案与图标不变。
- 二级书源 Tab（百度网盘 / WebDAV / Z-Library）切换正常，数据互不干扰。
- Z-Library：搜索（当前服务端拒绝原生搜索时按既有逻辑降级为官方热门并提示）、热门书单、详情页、额度查询（「已登录：… 今日下载 3 / 10」）、「账户管理」「网页搜索」「刷新云端书库」均可用。
- 百度网盘列表加载正常；WebDAV 未配置时仍显示原有「尚未配置云后端」提示与重试。
- 返回层级：详情 → 列表 →（检索态）清空检索 → 书库，与阶段 A 前一致。

---

## 6. 尚未解决的问题及风险

1. **检索页返回层级会"吃掉一次返回"**：有检索内容时，从云端返回需先清空检索再次返回才回到书库。这是阶段 A 前既有设计（`CatalogLibraryScreen` 的返回层级），本次未改动，需在阶段 B 与 Pager 返回语义一起确认。
2. **页面内容切换晚一帧**：书源 Tab 点击后，先更新 committed key，再由 `LaunchedEffect` 同步 `selectProvider`，业务状态比高亮晚一帧生效。阶段 A 无可见异常，但接入 Pager 后需要改为「落页即加载」。
3. **书源启停没有运行时入口**：当前 UI 不存在启用/禁用书源操作，`syncPages` 已按动态变化实现并有单测，但缺少真机验证场景。
4. **`selectedProviderId` 仍是 ViewModel 公共状态**：阶段 A 保留它用于持久化与旧弹窗兼容；阶段 B 需要收敛为「只由 committed 页写入」，避免出现第二数据源。
5. **进程死亡恢复仅覆盖一级选择**：恢复依赖 `restoredTab` + `prefs.lastCloudProvider`，未恢复二级滚动位置与检索条件（属阶段 D 范围）。

---

## 7. 与 V2 方案存在的差异

| 差异点 | V2 方案 | 实际实现 | 原因 |
| --- | --- | --- | --- |
| `ShelfPage.build` 入参 | `List<BookProvider>` | `List<String>`（providerId） | 导航层不依赖 provider 接口，可纯 JVM 单测；书源顺序仍由注册表决定 |
| 检索书源额度查询 | 未细化 | 仅 `quotaText == null` 时预热，菜单/点击/刷新仍查询 | 避免 Pager 每次落页重复请求，符合"不新增非必要请求"约束 |
| `navigateTo` 无效 key | 按回退链落到最近合法页 | 未知的**非云端** key 保持当前页；未知云端 key 仍回退 | 防止错误 key 把用户强行带回书库 |
| `CloudEmpty` | 方案中为占位页 | 落地为「暂无启用的书源 + 去设置」 | 满足"全部禁用时云端仍可达"的验收要求 |
| 设置页返回键 | 本次评审补充项曾提及 | **未新增**设置页返回书库 | 约束要求"保持现有界面及点击交互不变"，既有行为为设置页直接退出，改动会改变交互 |
| 详情页层级 | 未涉及 | 明确为 `Box` 覆盖层 | 重构过程中发现放入 `Column` 会被内容挤出，已修正并真机验证 |

---

## 8. 阶段 B 的接入条件

满足以下条件即可进入阶段 B（单层 HorizontalPager 接入）：

1. 阶段 A 评审通过，`ShelfNavigator` / `ShelfPage` API 不再调整（`pages`、`committedKey`、`navigateTo`、`syncPages`、`cloudKey` 已稳定）。
2. Pager 以 `pages` 为数据源、`committedKey` 为 settledPage 真源：滑动结束写 `committedKey`，点击 Tab 通过 `navigateTo` 驱动 Pager 动画。
3. 落页触发的加载改为「settled 后加载」，替代当前 `LaunchedEffect(committedKey)` 的一次性触达，避免快速滑动重复请求。
4. 滑动/动画期间禁用详情、书籍、下载等业务点击——需基于 `PagerState.isScrollInProgress || 动画中` 的显式门闸，不能只用 `isNavigating`。
5. 真机验证 `PagerSnapDistance.atMost(1)` 对快速 fling、长距离拖动、动画中断的实际限制，并记录结论。
6. 统一二级检索页返回语义（是否保留"先清空检索"），明确与一级 Pager 返回的关系。
7. 阶段 A 遗留的 `selectedProviderId` 收敛为 committed 页单向写入。

---

## 附：验证命令

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug

adb install -r app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk
```
