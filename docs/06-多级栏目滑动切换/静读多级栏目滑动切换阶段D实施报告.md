# 静读多级栏目滑动切换阶段 D 实施报告

> 提交：`f392949 feat(shelf): 阶段D 状态恢复、动态书源与栏目滑动开关`
> 前置：阶段 C（`ee91ef8`）。

---

## 1. 阶段范围与结论

本阶段补齐「状态与设置」相关能力：

- 进程重建后按稳定 key（`last_shelf_key`）恢复到上次落页，而不是退回一级栏目默认页。
- 上次书源已被禁用时，`selectedProviderId` 回退到首个可用书源，避免初始化到不可见页。
- 设置页新增「左右滑动切换栏目」开关，修改即时生效并持久化。
- 设置页返回键回到「书库」，与云端一级页保持一致。
- 设置页滚动位置跨页切换 / 进程重建保留，Pager 销毁页面前立即落盘。

已真机验证：重启后恢复到 `cloud.zlibrary`、开关关闭后横滑无效并持久化、设置页返回书库。

---

## 2. 实际修改文件及职责

| 文件 | 职责 |
| --- | --- |
| `ui/LibraryUiPrefs.kt` | 新增 `lastShelfKey`（`last_shelf_key`）与 `tabSwipeEnabled`（`tab_swipe_enabled`，默认 `true`） |
| `MainActivity.kt` | 初始 key 优先取 `lastShelfKey`；committed 变化写回；新增 `tabSwipeEnabled` 状态与设置项；设置页返回书库；设置页滚动状态恢复 |
| `ui/online/OnlineLibraryViewModel.kt` | `selectedProviderId` 初值过滤已禁用书源，回退首个可用书源 |

---

## 3. 状态恢复机制

### 3.1 导航页恢复

```kotlin
val navigator = remember {
    ShelfNavigator(
        initialKey = uiPrefs.lastShelfKey ?: when (restoredTab) {
            Tab.书架 -> ShelfPage.LocalShelf.key
            Tab.云端 -> "cloud.${onlineVm.selectedProviderId}"
            Tab.设置 -> ShelfPage.Settings.key
        },
        providerIds = ...,
    )
}
```

```kotlin
LaunchedEffect(navigator.committedKey) {
    restoredTab = navigator.tab
    uiPrefs.lastShelfKey = navigator.committedKey
    ...
}
```

- 保存的是**已提交**的稳定 key，不保存拖动中的中间页。
- 书源集合变化后 key 失效时，由 `ShelfNavigator.resolve` 逐级回退（原 key → 首个云端页 → `cloud.empty` → 书库），不会停留在不存在的页面。
- `rememberSaveable` 的 `restoredTab` 仅作无 `lastShelfKey` 时的兜底，运行期真源仍是 `committedKey`。

### 3.2 书源回退

```kotlin
var selectedProviderId: String by mutableStateOf(
    prefs.lastCloudProvider?.takeIf { id -> registry.enabled().any { it.id == id } }
        ?: registry.enabled().firstOrNull()?.id
        ?: "baidu",
)
```

- 原来只校验「书源存在」，禁用后仍可能恢复到被隐藏的书源。
- 现在校验「书源已启用」，禁用即回退；全部禁用时再落到 `baidu` 兜底。
- 该值仍为 `private set`，写入权限收敛在 ViewModel 内部，页面只通过 committed 页驱动 `selectProvider`。

---

## 4. 栏目滑动开关

设置页新增卡片：

```kotlin
SettingsCard("栏目切换") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("左右滑动切换栏目", ...)
            Text("关闭后仅能点击底部导航与书源标签切换", ...)
        }
        Switch(checked = tabSwipeEnabled, onCheckedChange = onTabSwipeChange)
    }
}
```

```kotlin
onTabSwipeChange = {
    tabSwipeEnabled = it
    uiPrefs.tabSwipeEnabled = it
}
```

```kotlin
HorizontalPager(
    userScrollEnabled = tabSwipeEnabled && !swipeBlocker.blocked,
    ...
)
```

- 开关写入内存状态后立即影响 `userScrollEnabled`，无需重建页面。
- 同步写入 `SharedPreferences`，下次启动沿用。
- 与阶段 C 的闸门是「与」关系：即使开关开启，详情/筛选/输入聚焦期间仍不可滑。

---

## 5. 返回层级与滚动位置

### 5.1 设置页返回

```kotlin
BackHandler(enabled = currentTab == Tab.设置 && !fullscreenSubPage && !showOnlineDownloads) {
    navigator.navigateTo(ShelfPage.LocalShelf.key)
}
```

设置与云端同属一级页，返回统一回到「书库」；全屏子页（同步记录、Z-Library 设置等）优先由子页自身消费返回键。

### 5.2 设置页滚动位置

```kotlin
val settingsScroll = remember { ScrollState(settingsPrefs.scroll("settings").offset) }
LaunchedEffect(settingsScroll) {
    snapshotFlow { settingsScroll.value }
        .distinctUntilChanged()
        .collectLatest { value ->
            delay(150)
            settingsPrefs.saveScroll("settings", 0, value)
        }
}
DisposableEffect(settingsScroll) {
    onDispose { settingsPrefs.saveScroll("settings", 0, settingsScroll.value) }
}
```

- 滚动偏移沿用 `LibraryUiPrefs.scroll.*` 既有键位，与其它列表/网格的恢复口径一致。
- Pager 会销毁离屏页，`onDispose` 立即落盘，避免最后一次滚动因防抖未触发而丢失。

---

## 6. 验证记录

### 6.1 单元测试

- 全量 348 项（ttsTest 变体：app 50 + core 178 + reader 66 + novel-text 22 + speech 32）0 失败 0 跳过。
- 阶段 A 的 `ShelfNavigatorTest` 覆盖 key 失效回退、稳定 key 定位，是本阶段恢复逻辑的基础保障。

### 6.2 真机（1080×2160）

| 场景 | 结果 |
| --- | --- |
| 停留在 Z-Library 页后杀进程重启 | 直接恢复 `cloud.zlibrary`，顶栏/底栏/二级标签一致 |
| 读取 `library_ui.xml` | `last_shelf_key=cloud.zlibrary`，写入时机与落页一致 |
| 设置页关闭「左右滑动切换栏目」 | 横滑立即无效，仅点击可用 |
| 重启后再次横滑 | 仍不可滑，说明开关已持久化 |
| 重新开启开关 | 横滑恢复 |
| 设置页按返回键 | 回到书库页（非退出应用） |

设置页滚动位置的保留由代码路径保证（`ScrollState` 初值读取 + `onDispose` 落盘），本阶段未单独做滚动位置的截图级真机比对，列入阶段 E 回归项。

---

## 7. 遗留风险

| 风险 | 说明 | 现状 |
| --- | --- | --- |
| `lastShelfKey` 与书源集合不同步 | 用户禁用上次书源后重启 | `resolve` 逐级回退，不停留在无效 key |
| 设置页滚动与业务加载 | 设置页无网络加载，恢复不会触发请求 | 无额外请求 |
| `rememberSaveable` 兜底路径 | 首次安装无 `lastShelfKey` | 按 `restoredTab` 兜底到书库 |
