# 静读多级栏目滑动切换阶段 C 实施报告

> 提交：`ee91ef8 feat(shelf): 阶段C 开启横滑手势与滑动闸门`
> 前置：阶段 B（`d2320be`）。

---

## 1. 阶段范围与结论

本阶段在阶段 B 的 Pager 骨架之上开启用户横向滑动：

- `userScrollEnabled` 由「栏目滑动开关」与「滑动闸门」共同决定。
- 单次 fling 最多跨一页（`PagerSnapDistance.atMost(1)`）。
- 详情、筛选弹层、输入框聚焦、本地管理态、下载管理页、全屏子页期间禁止横滑。
- 手势落页直接提交 `committedKey`，不再依赖程序化导航请求。

已真机验证：滑动逐页切换、快速连滑每次只走一页、搜索框聚焦时滑动无效、关闭开关后完全不可滑。

---

## 2. 实际修改文件及职责

| 文件 | 职责 |
| --- | --- |
| `ui/navigation/SwipeBlockRegistry.kt`（新增） | 以 token 集合维护滑动闸门，`blocked = tokens.isNotEmpty()` |
| `ui/navigation/ShelfNavigator.kt` | `requestedKey` 改为可空 pending；手势落页不产生请求，`isNavigating` 不再被手势污染 |
| `MainActivity.kt` | 开启手势；`PagerSnapDistance.atMost(1)`；宿主级 token（下载页/全屏子页/管理态） |
| `ui/online/OnlineLibraryScreen.kt` | 详情展开时持有 `detail:<providerId>` token |
| `ui/online/CatalogLibraryScreen.kt` | 筛选弹层 / 搜索聚焦时持有 `catalog:<providerId>` token |
| `SwipeBlockRegistryTest.kt`（新增） | 4 条闸门用例 |
| `ShelfNavigatorTest.kt` | 增加「手势落页不产生请求」用例，适配可空请求 |

---

## 3. 手势与点击的联动

### 3.1 打开条件

```kotlin
userScrollEnabled = tabSwipeEnabled && !swipeBlocker.blocked
flingBehavior = PagerDefaults.flingBehavior(
    state = pagerState,
    pagerSnapDistance = PagerSnapDistance.atMost(1),
)
```

- `tabSwipeEnabled`：用户设置（阶段 D 提供 UI，默认开启）。
- `swipeBlocker.blocked`：任一页面/宿主持有阻塞 token。

### 3.2 手势落页提交

阶段 B 的模型用「requestedKey ≠ committedKey」判断是否正在导航。开启手势后暴露出问题：手势切换不经过 `navigateTo`，若仍以两者是否相等判断，会把手势落页误判为「还有未完成导航」，导致 `committedKey` 永不更新（真机表现为：页面已经滑到设置，顶栏仍是云端）。

修复方式：把 `requestedKey` 改成可空 pending。

```kotlin
var requestedKey: String? by mutableStateOf(null)   // null = 没有程序化导航
val isNavigating: Boolean get() = requestedKey != null

fun commit(key: String) {
    if (pages.none { it.key == key }) return
    committedKey = key
    if (requestedKey == key) requestedKey = null
}

fun clearRequest(key: String) { if (requestedKey == key) requestedKey = null }
```

- 点击导航：`navigateTo` 写 pending，滚动完成/取消后清理。
- 手势导航：`requestedKey` 始终为 null，落页观察者直接 `commit`。
- 手势打断程序化动画：滚动协程 `finally` 清理 pending，落页观察者提交用户最终停留的页面。

---

## 4. 滑动闸门

```kotlin
class SwipeBlockRegistry {
    private var tokens by mutableStateOf<Set<String>>(emptySet())
    val blocked: Boolean get() = tokens.isNotEmpty()
    fun hold(token: String); fun release(token: String); fun isHeld(token: String): Boolean
}
```

| token | 持有者 | 时机 |
| --- | --- | --- |
| `host:overlay` | MainActivity | 下载管理页、全屏子页（同步记录/Z-Library 设置/服务器设置）、本地管理态 |
| `detail:<providerId>` | OnlineLibraryScreen | 当前页详情展开 |
| `catalog:<providerId>` | CatalogLibraryScreen | 筛选弹层打开、搜索框聚焦、详情展开 |

用 `DisposableEffect` 持有/释放：

```kotlin
DisposableEffect(swipeBlocker, providerId, blocked) {
    val token = "catalog:$providerId"
    if (blocked) swipeBlocker?.hold(token)
    onDispose { swipeBlocker?.release(token) }
}
```

- 页面被 Pager 销毁时 `onDispose` 自动释放，不会残留全局阻塞。
- 搜索提交时已有 `keyboardController.hide()` 与 `focusManager.clearFocus()`，提交后闸门自动解除。

---

## 5. 验证记录

### 5.1 单元测试

- `SwipeBlockRegistryTest`：默认不阻塞、单 token 阻塞、多 token 分别释放、重复持有/释放安全。
- `ShelfNavigatorTest`：手势落页直接提交且不产生导航请求、取消请求不影响新目标。
- 全量 348 项（ttsTest 变体）0 失败。

### 5.2 真机（1080×2160）

| 场景 | 结果 |
| --- | --- |
| 书库 → 云端 | 滑动落页后顶栏/底栏/二级 Tab 同步为云端与百度网盘 |
| 云端内连续 3 次快速横滑 | 每次只前进一页（baidu → webdav → zlibrary），顶栏始终与落页一致 |
| 反向滑动 | 逐页回退，无跨页跳跃 |
| Z-Library 搜索框聚焦后横滑 | 页面不动；提交搜索（清焦点）后恢复可滑 |
| 设置页关闭「左右滑动切换栏目」 | 横滑完全无效，仅点击可用；重新开启后恢复 |
| 进入下载管理页后横滑 | 被 Pager 覆盖层遮挡且闸门关闭，无异常 |

---

## 6. 遗留风险

| 风险 | 说明 | 现状 |
| --- | --- | --- |
| 长距离拖动 | `atMost(1)` 限制 fling 跨页数，但超长慢速拖动理论上仍受单页限制 | 真机快滑/慢滑均只跨一页 |
| 手势取消边界 | 用户拖动后快速松手回到原页 | 落页观察者提交实际落页，`isNavigating` 无残留 |
| 闸门与手势竞争 | 输入框聚焦瞬间开始拖动 | `onFocusChanged` 即时写入 state，下一帧即可阻断 |
