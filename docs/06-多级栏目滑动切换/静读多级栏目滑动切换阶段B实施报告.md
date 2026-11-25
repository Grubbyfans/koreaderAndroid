# 静读多级栏目滑动切换阶段 B 实施报告

> 提交：`d2320be feat(shelf): 阶段B 接入单层 HorizontalPager（点击导航 + 落页提交）`
> 前置：阶段 A 已评审通过（`09af5ef`）。
> 本阶段只接入 Pager 与点击导航，`userScrollEnabled=false`，不实现用户滑动手势。

---

## 1. 阶段范围与结论

本阶段把一级栏目（书库/云端/设置）与云端二级书源摊平为一维页面列表，用单层 `HorizontalPager` 承载：

- 页面顺序：`书架 → 各云端书源（或 CloudEmpty）→ 设置`。
- 页面标识使用 `ShelfPage.key` 稳定字符串，不使用列表下标。
- 点击导航只写「导航请求」，Pager 真正落页后才写 `committedKey`。
- 顶栏、底栏、二级 Tab、操作按钮全部由 `committedKey` 派生。
- 相邻页使用原生 Pager 动画；非相邻页直接定位，不逐页展示中间栏目。
- 切换过程中（动画或程序化导航）禁用详情、打开书籍、下载与顶栏业务操作。
- 业务加载仍然只由 `committedKey` 触发，`settledPage` 只负责提交，不触发加载。

阶段 B 验收项已在真机逐条核对，见第 8 节。

---

## 2. 实际修改文件及职责

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/yuezhou/reader/ui/navigation/ShelfNavigator.kt` | 拆分 `requestedKey`（导航意图）与 `committedKey`（已落页）；新增 `commit` / `clearRequest` / `indexOf`；`syncPages` 处理书源增删与目标失效 |
| `app/src/main/java/com/yuezhou/reader/MainActivity.kt` | 接入 `HorizontalPager`；程序化导航与落页提交两个副作用；切换守卫 `pagerBusy`；页面内容按 `ShelfPage` 分支渲染；云端加载显式传 `providerId` |
| `app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryScreen.kt` | 新增 `interactionsEnabled`，切换期间不接管返回键、禁用详情下载入口 |
| `app/src/main/java/com/yuezhou/reader/ui/online/CatalogLibraryScreen.kt` | 新增 `interactionsEnabled`，切换期间书籍卡片不可点、BackHandler 不生效 |
| `app/src/main/java/com/yuezhou/reader/ui/online/BookDetailScreen.kt` | 新增 `interactionsEnabled`，切换期间禁用下载与自动导入开关 |
| `app/src/test/java/com/yuezhou/reader/ui/navigation/ShelfNavigatorTest.kt` | 导航用例由 12 条扩到 19 条 |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | 显式声明 `androidx.compose.foundation`（Pager 所在模块） |

---

## 3. Pager 接入架构

```kotlin
val pageKeys = navigator.pages.map { it.key }
val pagerState = rememberPagerState(
    initialPage = navigator.indexOf(navigator.committedKey).coerceAtLeast(0),
    pageCount = { navigator.pages.size },
)

HorizontalPager(
    state = pagerState,
    modifier = Modifier.fillMaxWidth().weight(1f),
    userScrollEnabled = false,          // 阶段 C 打开
    beyondViewportPageCount = 0,        // 不预组合非可见页
    key = { index -> pageKeys.getOrNull(index) ?: index },
) { index ->
    when (val page = navigator.pages.getOrNull(index)) {
        ShelfPage.LocalShelf -> BookshelfContent(...)
        is ShelfPage.Cloud -> OnlineLibraryScreen(vm, page.providerId, ...)
        ShelfPage.CloudEmpty -> OnlineLibraryScreen(vm, providerId = null, ...)
        ShelfPage.Settings -> SettingsPage(...)
        null -> Unit
    }
}
```

关键点：

1. **页面自洽**：每个 Pager 页面只用自己的 `providerId` 读取 `OnlineLibraryViewModel` 的状态，页面之间不共享选中态。相邻页同时组合时自然各自渲染自己的数据。
2. **稳定 key**：`key` 使用 `ShelfPage.key`，书源增删时 Compose 依据 key 复用/重建页面，不依赖下标。
3. **首屏定位**：`initialPage` 由 `committedKey` 反查下标，进程重建后直接落在原页面。
4. **不预组合**：`beyondViewportPageCount = 0`，静止状态只有当前页参与组合。

---

## 4. 导航状态同步机制

`ShelfNavigator` 的状态模型：

```text
navigateTo(key) ──► requestedKey = 合法目标（只记录意图）
                        │
                        ▼
        LaunchedEffect(requestedKey, pages) 执行滚动
                        │
        ┌───────────────┴────────────────┐
        │ 相邻页：animateScrollToPage     │ 非相邻/下标错位：scrollToPage
        └───────────────┬────────────────┘
                        ▼
              commit(key) → committedKey = key
                        │
                        ▼
        LaunchedEffect(committedKey) → 唯一的业务加载入口
```

- `requestedKey` 只表示「还没落页的程序化导航」，手势拖动不会产生请求。
- `commit(key)` 校验 key 仍存在于页面列表，避免页面列表变化的瞬间写坏状态。
- 落页观察者：

```kotlin
snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
    .distinctUntilChanged()
    .collect { (scrolling, page) ->
        if (scrolling) return@collect
        val landed = pageKeys.getOrNull(page) ?: return@collect
        if (!navigator.isNavigating) navigator.commit(landed)
    }
```

它只做「提交」，不做加载；`commit` 写入相同 key 时 `mutableStateOf` 不通知，因此不会重复触发加载。

- 云端加载：

```kotlin
LaunchedEffect(navigator.committedKey) {
    restoredTab = navigator.tab
    uiPrefs.lastShelfKey = navigator.committedKey
    val active = navigator.providerId
    if (navigator.tab == Tab.云端 && active != null) {
        if (active == onlineVm.selectedProviderId) loadRemote(active)
        else onlineVm.selectProvider(active)   // selectedProviderId 的唯一写入点
    }
}
```

`loadRemote` 与 `refreshRemoteLibrary` 均显式传 `providerId`，不再依赖默认参数。

---

## 5. 动画中断与快速连续点击

- 程序化滚动的协程在 `try/finally` 中收尾：**正常落页才提交**，被新点击或手势取消时只 `clearRequest`，不提交中间页。
- 快速连点时，`requestedKey` 每次覆盖；旧的 `LaunchedEffect` 因 key 变化被取消，`animateScrollToPage` 随之中断，新任务从当前页继续滚动，**最后一次有效导航生效**。
- 手势打断动画时，`finally` 清空请求 → 落页观察者把手势最终停留的页面提交为 `committedKey`，不会出现 `isNavigating` 永久卡死。
- 非相邻点击使用 `scrollToPage`（无动画直接定位），不会出现中间栏目逐页闪过。
- 书源增删导致下标错位时，`syncPages` 先按稳定 key 归位 `committedKey`，再由独立副作用把 Pager 定位到新下标；若待执行目标页被移除，则取消该次导航。

---

## 6. 切换期间交互守卫

```kotlin
val pagerBusy = pagerState.isScrollInProgress || navigator.isNavigating
```

覆盖两种来源：手势/动画进行中（`isScrollInProgress`）与程序化导航尚未落页（`isNavigating`）。

被守卫的入口：

| 入口 | 处理 |
| --- | --- |
| 本地书籍打开 | `BookshelfContent(navigationLocked = pagerBusy)`，管理态下仍可勾选 |
| 云端书籍打开 | `onOpenDetail = { if (!pagerBusy) detailBook = it }` |
| Z-Library 结果卡片 | `clickable(enabled = interactionsEnabled)` |
| 书籍详情 | `interactionsEnabled` 控制下载按钮、自动导入开关 |
| 顶栏操作 | 「已移除」「列表/网格」「下载任务」「更多」均 `enabled = !pagerBusy` |
| 页面返回键 | 切换期间 `BackHandler(enabled = false)`，避免滑出页误清检索 |

底栏与云端二级 Tab **不**禁用，保证连点可以覆盖上一次导航。

---

## 7. 网络请求验证

1. **加载入口唯一**：业务加载只在 `LaunchedEffect(committedKey)` 内触发；落页观察者只提交状态。
2. **重复提交不重复加载**：相同 key 写入不触发 Compose 状态通知。
3. **不预组合**：`beyondViewportPageCount = 0`，静止时只组合当前页；非相邻导航直接定位，不经过中间页。
4. **显式书源**：`loadRemote`/`refreshRemoteLibrary`/`selectProvider` 全部显式传 `providerId`，异步回包不会因为 `selectedProviderId` 变化而串书源。
5. **原有短路面保留**：`loaded/loading` 判断、`jobs`、`requestSeq` 未改动；阶段 A 的额度预热、缓存与请求短路行为保持不变。
6. **真机观察**：连续切换书库 ↔ 云端 ↔ 设置，重复进入同一云端页未产生新的列表请求（页面直接使用 ViewModel 缓存），切换过程中顶栏与页面无异常请求痕迹。

阶段 E 进一步把封面加载推迟到落页之后，见阶段 E 报告。

---

## 8. 测试与真机验证

### 8.1 单元测试

| 模块（ttsTest 变体） | 用例数 |
| --- | --- |
| app | 50 |
| core | 178 |
| reader | 66 |
| novel-text | 22 |
| speech | 32 |
| **合计** | **348**（0 失败 / 0 跳过） |

阶段 A 时点同一口径为 337（app 39 + 其余 298），阶段 B/C 新增 11 条导航与闸门用例。

### 8.2 真机冒烟（Pixel 模拟器，1080×2160）

| 场景 | 结果 |
| --- | --- |
| 点击底栏「云端」 | 落页后进入上次书源（Z-Library），顶栏切换为「云端书库」 |
| 书库 → 设置（跨 4 页） | 直接定位，无中间栏目闪现 |
| 云端返回键 | 回到书库 |
| 阶段 B 期间横滑 | 页面不切换（`userScrollEnabled=false`） |
| 运行期崩溃 | logcat `AndroidRuntime` 无记录 |

---

## 9. 遗留风险与阶段 C 条件

| 风险 | 说明 | 处理 |
| --- | --- | --- |
| 手势提交模型 | 阶段 B 未开启手势，但 `requestedKey` 若可空化会影响手势落页提交 | 阶段 C 改为可空 pending 并在手势落页直接 commit |
| 封面预加载 | 相邻页在动画期间会组合，封面 `LaunchedEffect` 可能提前联网 | 阶段 E 增加落页后才加载的门控 |
| 页面列表变化瞬间 | 下标错位可能短暂显示旧页 | `syncPages` + 独立定位副作用已覆盖；极端并发下仍可能有一帧延迟 |
| 全屏子页动画 | 从云端菜单进入设置并同时打开全屏子页时，遮挡层先于落页出现 | 属阶段 D 交互细节，已记录 |

阶段 C 接入条件：导航请求/提交模型稳定、页面内容按 `providerId` 自洽、切换守卫覆盖 `isScrollInProgress`，均已满足。
