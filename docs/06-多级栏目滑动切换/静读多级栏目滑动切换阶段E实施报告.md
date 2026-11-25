# 静读多级栏目滑动切换阶段 E 实施报告

> 提交：`2e0dc40 perf(shelf): 阶段E 封面加载延后到落页，避免切换期间额外网络请求`
> 前置：阶段 D（`f392949`）。

---

## 1. 阶段范围与结论

本阶段是收尾阶段，目标是「切换期间零额外网络请求」与全量回归门禁：

- 封面加载延后到页面落页（`interactionsEnabled = !pagerBusy`），避免 Pager 预组合相邻页时提前请求封面。
- 修正 `CompactSearchField` 可选参数顺序，消除 Lint `ModifierParameter` 告警。
- 全量单测、Lint、编译、安装与真机回归。

结论：全量 348 项单测 0 失败；Lint `0 errors, 52 warnings, 3 hints`；`assembleTtsTestDebug` 通过；APK 安装后冷启动与主要路径无崩溃。

---

## 2. 实际修改文件及职责

| 文件 | 职责 |
| --- | --- |
| `ui/online/CatalogLibraryScreen.kt` | `RemoteCover` 增加 `enabled` 参数，仅在落页后 `loadCover`；网格/列表卡片透传 `interactionsEnabled`；`CompactSearchField` 参数顺序修正 |

网络请求收敛依赖前序阶段已落地的结构，本阶段只补最后一处「预组合即请求」的缺口：

| 触发源 | 是否发起网络 | 说明 |
| --- | --- | --- |
| `committedKey` 变化 | 是 | 唯一业务加载真源（阶段 B） |
| Pager 滑动/动画中间帧 | 否 | 落页观察者不提交，`committedKey` 不变 |
| 相邻页预组合 | 否 | 云端列表由 `committedKey` 驱动；封面由 `enabled` 门控 |
| 请求动画期间点击 | 否 | 顶栏/卡片/下载入口 `enabled = !pagerBusy` |

---

## 3. 封面门控实现

```kotlin
private fun RemoteCover(
    url: String?,
    title: String,
    vm: OnlineLibraryViewModel,
    providerId: String,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    // 只在页面落页后加载封面，避免 Pager 切换动画期间的相邻页预组合触发额外网络请求。
    LaunchedEffect(url, providerId, enabled) { if (enabled) vm.loadCover(url, providerId) }
    ...
}
```

- 切换开始 `pagerBusy = true` → `enabled = false`，预组合的相邻页不请求封面。
- 落页后 `enabled` 变真，`LaunchedEffect` 重新执行并加载。
- `vm.loadCover` 内部已有内存缓存与失败地址去重，重复进入同一页不会重复请求。

---

## 4. 全量回归证据

### 4.1 单元测试

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
# BUILD SUCCESSFUL in 35s，EXIT=0
```

| 模块 / 任务 | 用例数 |
| --- | --- |
| app `testTtsTestDebugUnitTest` | 50 |
| core `testDebugUnitTest` | 178 |
| reader `testDebugUnitTest` | 66 |
| novel-text `testDebugUnitTest` | 22 |
| speech `testDebugUnitTest` | 32 |
| **ttsTest 口径合计** | **348** |

- test-results XML 共 53 个文件，合计 `tests=398, failures=0, errors=0, skipped=0`。
- 398 与 348 的差额来自 `test` 任务同时执行 app 的 production 变体（50 项，与 ttsTest 变体同一套用例），`398 = 348 + 50`。

### 4.2 历史统计口径核对（352 / 12 / 354）

阶段 A 报告中的「352 项、导航 12 条、354」无法从任何一次现有执行结果复现，且 `352 + 12 ≠ 354`，数学上不自洽。核对结论：

- 阶段 A 提交 `09af5ef` 时 `ShelfNavigatorTest` 为 12 条（`git show` 核对）。
- 当前 app 用例 50 条中，`ShelfNavigatorTest` 19 条、`SwipeBlockRegistryTest` 4 条，其余 27 条。
- 反推阶段 A 的 ttsTest 口径 = 348 − 50 + (12 + 27) = **337**，与「352/354」不一致。
- 现行口径以可复核数字为准：**348（ttsTest）**、**398（含 production app 变体）**、53 个 XML 且 0 skipped，无遗漏执行的测试类。

### 4.3 Lint

`app/build/reports/lint-results-ttsTestDebug.txt`：`0 errors, 52 warnings, 3 hints`，无新增告警（`ModifierParameter` 已在本阶段消除）。

### 4.4 打包与安装

| 项 | 值 |
| --- | --- |
| APK | `app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk` |
| 包名 / 版本 | `com.yuezhou.reader.tts.test` / `0.1.0-tts-test` |
| 安装 | `adb install -r` 成功，`lastUpdateTime=2026-11-25 16:43:14` |
| 启动 | 冷启动进入书架，`logcat` 无 `AndroidRuntime` 崩溃记录 |

### 4.5 真机回归清单（1080×2160）

| 场景 | 结果 |
| --- | --- |
| 底部导航点击书库/云端/设置 | 页面、顶栏、二级标签一致 |
| 书库 → 设置（点击） | 直接定位，无中间页闪现 |
| 横滑逐页切换云端书源 | 每次一页，顶栏与落页一致 |
| 快速连滑 | 最后一次落页生效 |
| 搜索框聚焦 / 筛选弹层 / 详情展开 | 横滑被闸门阻断 |
| 关闭「左右滑动切换栏目」 | 横滑完全无效，重启后仍无效 |
| 搜索态（关键词、失败提示） | 切走再回来保留 |
| 进程重建 | 恢复 `cloud.zlibrary` |
| 下载管理页、同步记录、Z-Library 设置 | 打开/返回正常，期间横滑关闭 |
| 本地书籍进入 ReaderActivity 并返回 | 正常，书架滚动位置保留 |
| 切换期间点击书籍卡片/下载 | 被 `pagerBusy` 拦截 |
| 切换期间网络请求 | 无新增请求；仅落页后加载云端列表/封面 |

环境说明：真机验证时 Z-Library 检索返回「安全连接失败，请检查网络或代理设置」，为当前网络/代理环境限制，与本次改动无关；百度网盘账号已授权，云端列表加载路径正常。

---

## 5. 性能与稳定性

- `beyondViewportPageCount = 0`：仅保留当前页与手势所需相邻页，减少组合与内存占用。
- 封面请求延后到落页，快速连续切换不会产生成串封面请求。
- `committedKey` 唯一业务触发，配合 `distinctUntilChanged`，避免重复加载与请求风暴。
- 列表/网格/设置滚动位置均持久化，页面销毁重建不丢状态。

---

## 6. 遗留风险

| 风险 | 说明 | 现状 |
| --- | --- | --- |
| 网络环境依赖 | Z-Library 检索受代理/证书影响 | 提示明确，可走网页入口；非回归 |
| 封面首次落页延迟 | 门控使封面在落页后才开始加载 | 缓存命中后无感知，未命中为首屏加载 |
| 极长列表恢复 | 设置页/列表滚动恢复为偏移量而非锚点 | 与既有 `scroll.*` 口径一致 |
| 历史统计口径 | 阶段 A 的 352/12/354 无法复现 | 已在 §4.2 说明，后续统一按 348/398 口径 |

---

## 7. 评审后修复（P0 修复，`fbc4d00`）

### 7.1 问题

原始阶段 B 把 `CloudSourceTabs` 渲染在每个 `OnlineLibraryScreen` Pager 页面内部，导致：

- 云端书源相互切换时，两条二级导航栏随着相邻页同步横向移动，滑动中途帧出现重复的「书源切换条」。
- 一级顶部标题栏虽已在 Scaffold 外固定，但云端二级导航没有对齐为固定 UI。

### 7.2 修复方向

- 保留单层 `HorizontalPager` 架构，不引入嵌套 Pager，不改 `ShelfNavigator` 模型。
- 把 `CloudSourceTabs` 从 `OnlineLibraryScreen` 内部抽离为公开组件，由 `MainActivity` 在 Scaffold 的 content Column 中、Pager 之外统一渲染**一份**。
- 视觉选中态由 `pagerState.currentPage` 派生，胶囊选中背景/文字色/字重用 `animateColorAsState` 与 `animateFloatAsState` 做平滑过渡，不引入独立业务状态。
- 一级栏目进出（书库 / 设置 ↔ 云端）使用 `AnimatedVisibility`（渐入渐出 + 纵向收缩），避免布局跳动。

### 7.3 实际修改文件

| 文件 | 改动 |
| --- | --- |
| `ui/online/OnlineLibraryScreen.kt` | 删除内部 `CloudSourceTabs` 调用与 `providers` 变量；`CloudSourceTabs` 改为公开顶级 Composable，参数 `selectedId: String?` 可空；选中背景/颜色/字重加入 animate*AsState |
| `MainActivity.kt` | 在 status row 与 HorizontalPager 之间插入一段：根据 `pagerState.currentPage` 派生 `visualCloudProviderId`、`showCloudTabs`，用 `AnimatedVisibility` 承载固定的 `CloudSourceTabs`；点击回调走 `navigator.navigateTo("cloud.$id")` 且 `pagerBusy` 期间禁用 |

核心片段：

```kotlin
val visualIndex = pagerState.currentPage.coerceIn(0, navigator.pages.lastIndex)
val visualPage = navigator.pages.getOrNull(visualIndex)
val visualCloudProviderId = when (visualPage) {
    is ShelfPage.Cloud -> visualPage.providerId
    else -> null
}
val showCloudTabs = visualPage?.tab == Tab.云端 &&
    onlineVm.registry.enabled().isNotEmpty()

AnimatedVisibility(
    visible = showCloudTabs,
    enter = fadeIn() + slideInVertically { it / 2 },
    exit  = shrinkVertically() + fadeOut() + slideOutVertically { it / 2 },
    label = "cloudTabs",
) {
    CloudSourceTabs(
        providers = onlineVm.registry.enabled(),
        selectedId = visualCloudProviderId,
        onSelect = { providerId ->
            if (!pagerBusy) navigator.navigateTo("cloud.$providerId")
        },
    )
}
```

### 7.4 视觉状态与业务状态分离

- **业务状态**：`navigator.committedKey` 仍然是唯一真源，驱动业务加载、顶栏标题、底栏选中、下载/搜索入口。
- **视觉选中态**：完全由 `pagerState.currentPage` 派生。拖动过程中 `currentPage` 跟随，胶囊背景由 animateColorAsState 平滑过渡。
- **手势取消**：Pager 会 snap 回原页，`currentPage` 自然回退到原下标，选中胶囊随之恢复，无需额外逻辑。
- **无副作用**：点击二级 Tab 只调用 `navigateTo`，不直接触发网络请求；仍由 `committedKey` 监听链路驱动。

### 7.5 验证记录（Samsung SM_S9360 / Android 14）

#### 7.5.1 代码门禁

| 项目 | 结果 |
| --- | --- |
| 单测 | 398 / 398 通过，0 failed，0 skipped（ttsTest 口径 348） |
| Lint | `0 errors, 52 warnings, 3 hints` |
| 编译 | `BUILD SUCCESSFUL` |
| APK 安装 | `Success`（`lastUpdateTime=2026-11-25 16:52:xx`） |
| 冷启动 | MainActivity 正停，无 `AndroidRuntime` 崩溃 |

#### 7.5.2 滑动中途帧像素级验证

Pixel 分析方法：抓取 10+ 帧（基线 + 慢速左滑 1/4、mid、3/4 + 慢速右滑 mid + 快速连滑 + 回到书库），对 nav bar 顶行（y ≈ 281）逐像素计算与静止参考帧的 MAE，同时扫描 content 区（y=500–1500）查找 nav bar 顶行特征。

| 指标 | 结果 |
| --- | --- |
| nav bar 顶行 MAE（mid-swipe） | 0.00 – 5.70（参考帧自身 MAE=0.00） |
| content 区重复 nav bar 匹配 | **0**（假阳性为全白背景行，已排除） |
| 如 nav bar 随页面平移的 MAE | 应 > 100，实际 ≤ 5.7 |

结论：**二级导航条固定在顶部，滑动中间帧无重复副本**。

#### 7.5.3 关键交互回归

| 场景 | 结果 |
| --- | --- |
| 云端内慢速左滑（baidu → webdav → zlibrary） | 二级导航固定，选中胶囊平滑过渡 |
| 云端内慢速右滑（zlibrary → webdav → baidu） | 同上 |
| 快速连滑 2 页 | 最后落页生效，nav bar 始终一条 |
| 云端 → 书库（向右滑） | 二级 nav 平滑收缩淡出，Pager 剩余空间自然承接，无跳动 |
| 书库 → 云端 | 二级 nav 渐入，顶栏/底栏正确切换 |
| 云端 → 设置 → 云端 | 二级 nav 随云端页进出正确显隐 |
| 连续点击二级 Tab + 手势打断 | requestedKey/clearRequest 链路已覆盖（阶段 A 单测 19 条） |
| 全部书源禁用（CloudEmpty） | `registry.enabled().isNotEmpty()` 为 false → 二级 nav 不渲染，空态页独立呈现 |
| 切换期间点击二级 Tab | `pagerBusy` 拦截，不产生重复导航 |

#### 7.5.4 未验证项（环境/账号限制）

| 项 | 原因 |
| --- | --- |
| WebDAV 真实后端连接 | 当前账号未配置可用 WebDAV 服务 |
| Z-Library 额度查询 & 下载 | 受代理/网络环境限制（返回「安全连接失败」） |
| Z-Library 网页搜索降级路径 | 同网络环境限制 |
| 设置页滚动位置恢复截图级比对 | 代码路径已覆盖（ScrollState + onDispose），未做截图级验证 |
| 长时重复切换内存增长采样 | 未单独跑长时间压测 |

上述未验证项已按评审要求明确标记，不列为通过。

### 7.6 修复后提交链

| 提交 | 说明 |
| --- | --- |
| `fbc4d00` | **P0 修复提交**：抽离 CloudSourceTabs + 视觉态派生 + AnimatedVisibility |
| `2e0dc40` → `09af5ef` | 阶段 A–E 核心提交链保持不变 |

---

## 8. 阶段交付状态

| 阶段 | 提交 | 状态 |
| --- | --- | --- |
| A 导航状态与解耦 | `09af5ef` | 已评审通过 |
| B Pager 骨架与点击导航 | `d2320be` | 已完成 |
| C 手势与滑动闸门 | `ee91ef8` | 已完成 |
| D 状态恢复、动态书源与开关 | `f392949` | 已完成 |
| E 网络收敛与全量回归 | `2e0dc40` | 已完成 |
| E 评审后 P0 修复 | `fbc4d00` | **已完成，像素级验证通过，APK 已重新安装** |
