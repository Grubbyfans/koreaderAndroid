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

## 7. 阶段交付状态

| 阶段 | 提交 | 状态 |
| --- | --- | --- |
| A 导航状态与解耦 | `09af5ef` | 已评审通过 |
| B Pager 骨架与点击导航 | `d2320be` | 已完成 |
| C 手势与滑动闸门 | `ee91ef8` | 已完成 |
| D 状态恢复、动态书源与开关 | `f392949` | 已完成 |
| E 网络收敛与全量回归 | `2e0dc40` | 已完成，APK 已安装 |
