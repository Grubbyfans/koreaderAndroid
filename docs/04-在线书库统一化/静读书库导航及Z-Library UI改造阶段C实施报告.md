# 静读书库导航及 Z-Library UI 改造 — 阶段 C 实施报告

- 阶段：C（数据源状态、降级提示及刷新机制）
- 基线：阶段 B 提交 `66539e2`
- 范围：`resultSource` 数据源标记、热门单页、提示条入列表、按来源刷新、检索页下拉刷新、云端后端下拉刷新
- 未包含：滚动位置恢复与偏好持久化（阶段 D）、自动化补测与总体验收（阶段 E）

## 1. 实际修改文件

| 文件 | 改动 |
| --- | --- |
| `app/.../ui/online/OnlineLibraryViewModel.kt` | 新增 `CatalogSource { NONE, KEYWORD, POPULAR }`；`ProviderUiState.resultSource`；`startSearch()` 清零来源；`doSearch()` 按是否降级写入来源；`refreshSelected()` 按来源分流；新增 `refreshCurrent()` 与 `refreshPopular()` |
| `app/.../ui/online/CatalogLibraryScreen.kt` | 移除列表上方固定提示行；新增 `SearchNoticeBar` 作为网格首项；结果区包 `PullToRefreshBox`；空态补充错误原因、「重新搜索」「网页搜索」 |
| `app/.../ui/online/OnlineLibraryScreen.kt` | 云书源后端内容包 `PullToRefreshBox(state.refreshing, onRefresh)` |
| `reader/.../zlibrary/ZLibraryBookProvider.kt` | 新增 `mostPopular(limit)`，只重拉官方热门单页，不改搜索拒绝判定 |

## 2. 实现说明

### 2.1 数据源标记

- `CatalogSource` 明确区分「关键词结果」「官方热门」「无结果」三种来源，避免用 `notice` 文本反推状态。
- `startSearch()` 发起新请求时置 `NONE`，响应成功后在 `doSearch()` 写入 `KEYWORD` 或 `POPULAR`。
- 旧响应由 `requestSeq` 拦截，不会覆盖新请求的来源与列表。

### 2.2 搜索被拒的降级链路

```text
ZLibraryApi.search() 返回 SERVER_REJECTED
  → ZLibraryBookProvider.search() 调 getMostPopular()
  → BookPage.notice 标注热门口径，nextCursor = null，hasMore = false
  → 视图层展示热门单页 + 提示条（含「重新搜索」「网页搜索」）
```

热门书单固定一页，不做分页，不冒充关键词结果。

### 2.3 提示条随列表滚动

- 提示条从 Lazy 容器外移入 `LazyVerticalGrid` 首项，整行 span，滚动时随内容移动，不再挤压首屏。
- 热门来源额外显示「官方热门书单 · 非关键词结果」标识。
- 有已提交关键词时显示「重新搜索」；「网页搜索」始终保留。

### 2.4 刷新按来源分流

| 当前来源 | 刷新行为 |
| --- | --- |
| `POPULAR` | 调 `mostPopular(30)` 重拉固定一页；失败保留旧列表并提示错误 |
| `KEYWORD` | 按**已提交关键词**重搜，`refreshing = true` 驱动下拉指示器 |
| `NONE`（未搜索） | 返回「请先输入关键词搜索」，不发请求 |

- 输入框未提交的文本不会参与刷新，避免用户改字后刷新变成隐式搜索。
- 检索页结果区与云端书源后端均接入 Material3 `PullToRefreshBox`。

### 2.5 空态与失败入口

- 无结果时展示：标题、失败原因（若有）、建议文案、`重新搜索`（有提交词时）、`网页搜索`。
- 云端后端下拉刷新与「更多」菜单刷新共存，外部上传发现能力保留。

## 3. 与评审方案的差异

| 项 | 处理 | 说明 |
| --- | --- | --- |
| 提示条位置 | 已入列表首项 | 与方案 §5 一致 |
| 热门刷新 | 只重拉单页 | 官方端点无游标 |
| 云端后端下拉刷新 | 复用已有刷新回调 | 未改外部上传发现逻辑 |
| 滚动与偏好持久化 | 阶段 D | 本阶段仅保证刷新不重置滚动基础行为 |

未删除搜索、降级、WebView、下载、导入、同步等任何业务能力。

## 4. 验证

### 4.1 自动化

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
```

- 单元测试：**352 项，0 失败**
- Lint：**0 error**，53 warning + 3 hint（均为既有项）
- 构建成功并安装测试版

### 4.2 真机验证（Android 16 / API 36）

| 项 | 结果 |
| --- | --- |
| 搜索框输入与键盘提交 | 通过（三星输入法需先提交候选词，再按搜索键） |
| 网络不可达时的空态 | 通过：显示失败原因 + 「重新搜索」+「网页搜索」 |
| 降级热门书单与提示条 | 复用阶段提交 `15b09f7` 已验证链路；本阶段改为列表首项渲染，代码路径一致 |
| 下拉刷新 | 已接入；当前网络不可达，仅验证指示器与失败保留旧列表 |
| 云端书源下拉刷新 | 复用原刷新回调，外部上传发现不受影响 |

> 说明：验证机当前无法直连 `zh.z-lib.gd`，热门书单在线路径需在可访问网络下复验；离线路径（错误提示、重试、网页入口）已验证通过。

## 5. 未解决项与风险

1. 热门书单与关键词结果共用 `items` 槽位，切换来源时以整表替换，旧列表不保留（符合“不冒充结果”的要求）。
2. 热门刷新失败只保留旧列表并提示错误，不自动回退关键词搜索。
3. 滚动位置仍会在重新搜索时归零，阶段 D 统一处理。
4. 网络受限环境无法在线验证热门接口，需在 E 阶段回归时补充可访问网络下的复验记录。
