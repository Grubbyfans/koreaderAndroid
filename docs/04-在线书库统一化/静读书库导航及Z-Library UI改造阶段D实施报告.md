# 静读书库导航及 Z-Library UI 改造 — 阶段 D 实施报告

- 阶段：D（状态恢复及偏好持久化）
- 基线：阶段 C 提交 `a7c1251`
- 范围：书库/云端/Z-Library 滚动位置（索引 + 偏移量）、Z-Library 展示模式、上次云端书源、切页不重复请求

## 1. 实际修改文件

| 文件 | 改动 |
| --- | --- |
| `app/.../ui/LibraryUiPrefs.kt`（新增） | 轻量 UI 偏好存储：`scroll.<key>.index/offset`、`last_cloud_provider`、`catalog_grid_mode`；`rememberRestoredGridState()` 负责恢复并延迟落盘 |
| `app/.../ui/online/OnlineLibraryViewModel.kt` | 构造时同步恢复上次书源；`selectProvider()` 记录选择；`setGridMode()` 持久化；移除内存 `scrollIndex` 与 `saveScroll()` |
| `app/.../ui/online/CatalogLibraryScreen.kt` | 使用 `rememberRestoredGridState("catalog.<id>")`；关键词变化才回到顶部，切页/详情返回不重置 |
| `app/.../ui/online/OnlineLibraryScreen.kt` | 云书源列表传入 `scrollKey = "cloud.<providerId>"` |
| `app/.../MainActivity.kt` | `CloudBookshelfContent` 按书源恢复滚动；`BookshelfContent` 使用 `"local"` 键恢复滚动 |

## 2. 实现说明

### 2.1 滚动位置（索引 + 偏移量）

- 位置由 `firstVisibleItemIndex + firstVisibleItemScrollOffset` 共同表示，仅存索引会在不等高卡片下偏移。
- 切换底部页签会销毁列表组合，重建时按偏好恢复，避免“切回就回到顶部”。
- 滚动中延迟 150ms 落盘（`collectLatest` + `delay`），避免每帧写 SharedPreferences。
- 覆盖范围：本地书库 `local`、百度网盘 `cloud.baidu`、WebDAV `cloud.webdav`、Z-Library `catalog.zlibrary`。

### 2.2 关键词与滚动的解耦

- 旧逻辑 `LaunchedEffect(submittedKeyword)` 在每次进入组合时都会把列表拉回顶部，导致切页或详情返回丢失位置。
- 新逻辑记录上次已处理的关键词，只有**真正提交了新关键词**才回到顶部；恢复位置由偏好决定。

### 2.3 展示模式与上次书源

- `catalog_grid_mode`：网格/列表切换即持久化，重启后保持。
- `last_cloud_provider`：切换书源即记录，重启后直接恢复，不再固定为百度网盘。
- 上次书源在 ViewModel 构造时**同步**读取，避免恢复前先按默认书源发一次请求。

### 2.4 避免切页重复请求

- 云书源：`loadSelected()` 命中 `loaded` 且非强制时直接复用内存列表。
- Z-Library：检索型书源不因切页触发搜索，只有提交关键词、应用筛选、主动刷新才发请求。

## 3. 与评审方案的差异

| 项 | 处理 | 说明 |
| --- | --- | --- |
| 滚动位置存储位置 | SharedPreferences | 方案只要求“记住位置”，未限定介质 |
| 搜索结果持久化 | 未做 | 方案仅要求展示模式与书源；搜索结果继续只存在内存 |
| 内存 `scrollIndex` | 移除 | 偏好成为唯一来源，避免双写不一致 |

未删除任何业务能力，未改动下载/导入/同步架构。

## 4. 验证

### 4.1 自动化

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
```

- 单元测试：**352 项，0 失败**
- Lint：**0 error**
- 构建成功并安装测试版

### 4.2 真机验证（Android 16 / API 36，VPN 可用）

| 项 | 结果 |
| --- | --- |
| Z-Library 关键词搜索 | 通过；服务端拒绝原生搜索后展示官方「最热门」单页 |
| 降级提示条 | 通过：说明文案 + 「官方热门书单 · 非关键词结果」+「重新搜索」「网页搜索」 |
| 提示条随列表滚动 | 通过：滚动后提示条随内容移出视口 |
| 下拉刷新（热门来源） | 通过：刷新后仍为热门单页，提示条保留 |
| Z-Library 滚动恢复 | 通过：滚动后切到「书库」再回「云端」，位置保持 |
| 展示模式持久化 | 通过：切为列表后强制停止并重启，仍为列表（按钮显示「网格」） |
| 上次书源持久化 | 通过：重启后仍停留在 Z-Library |
| 切页不重复请求 | 通过：切回云端保留列表，未重新搜索 |
| 本地书库滚动 | 验证机仅 1 本，无法构造多页场景；与云端/检索页共用同一实现 |

## 5. 未解决项与风险

1. 本地书库滚动恢复未在多书场景实测，需在书籍较多的设备上复验。
2. 搜索结果与筛选条件不跨进程持久化，重启后需重新搜索（符合方案范围）。
3. 滚动位置按书源分别保存，若云端列表在别处发生大幅增删，恢复位置会按索引近似定位。
4. 应用被杀前 150ms 内的最后一次滚动可能未落盘。
