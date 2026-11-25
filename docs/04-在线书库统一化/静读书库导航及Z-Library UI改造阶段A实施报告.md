# 静读书库导航及 Z-Library UI 改造 — 阶段 A 实施报告

- 阶段：A（根导航及顶栏改造）
- 基线：`15b09f7`
- 范围：底部导航、云端一级页、顶栏动作、云端刷新入口、移除本地/云端切换
- 未包含：Z-Library 页面布局与筛选弹窗（阶段 B）、数据源/刷新语义（阶段 C）、状态恢复与持久化（阶段 D）

## 1. 实际修改文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/yuezhou/reader/MainActivity.kt` | 新增 `Tab.云端` 与云图标；底栏改为三项；删除 `BookScope`/`BookScopeSwitch`/`BookScopeSaver`；顶栏按一级页显示标题与动作；新增云端「更多」菜单（刷新、网页搜索）；移除云端悬浮刷新按钮；切换加载改为 `Tab.云端` |
| `app/src/main/java/com/yuezhou/reader/ui/online/OnlineLibraryScreen.kt` | 仅更新入口注释，说明其已是底部一级「云端」页；业务逻辑未改 |
| `docs/04-在线书库统一化/静读书库导航及 Z-Library UI 改造技术评审报告.md` | 评审报告随本阶段一并入库（此前未提交） |

未新增依赖；云端图标用标准 Material Cloud 24dp 路径在 `MainActivity` 内构建，避免引入 `material-icons-extended`。

## 2. 实现说明

### 2.1 底部导航

- 一级入口：**书库 / 云端 / 设置**。
- `BottomNavBar` 改为遍历 `Tab.entries`，新增项自动渲染，避免三份重复代码。
- 内部枚举仍保留名称 `Tab.书架`，仅显示文案为「书库」；这样把改动限制在导航与条件判断，不触碰本地书库内部命名。
- 删除 `BookScope`、`BookScopeSwitch`、`BookScopeSaver` 及 `bookScope` 状态。

### 2.2 云端一级页

- `Tab.云端` 直接渲染 `OnlineLibraryScreen`，书源仍由原有 `selectedProviderId` 管理。
- 进入 `Tab.云端` 时执行一次 `loadRemote()`；内部 `loadSelected(force = false)` 命中缓存时不重复请求。
- 从设置或书库切回云端时，`OnlineLibraryViewModel` 为 Activity 级，书源选择、关键词与列表状态保留。

### 2.3 顶栏

| 页面 | 标题 | 右侧动作 |
| --- | --- | --- |
| 书库 | 书库 | 已移除（有隐藏书籍时）、下载任务 |
| 云端 | 云端书库 | 下载任务、更多 |
| 设置 | 设置 | 无 |

- 云端「更多」菜单：
  - **刷新云端书库**：调用原 `refreshRemoteLibrary()`，云盘模式仍会刷新列表并发现外部上传；Z-Library 模式使用已提交关键词刷新。
  - **网页搜索**：仅 Z-Library 显示；复用 `ZLibraryWebEntry`，只传服务器与当前输入关键词，登录沿用 WebView 自身会话。
- 检索书源没有已提交关键词时，「刷新云端书库」置灰，避免用输入框草稿触发请求。
- 设置页不显示下载与更多动作。

### 2.4 悬浮按钮

- 移除云端刷新 FAB。
- 书库保留导入 FAB，仅在 `Tab.书架` 显示。
- 云端刷新能力由「更多 → 刷新云端书库」承接；下拉刷新在阶段 C 实现。

### 2.5 条件收敛

所有原 `currentTab == 书架 && bookScope == 本地/云端` 的判断改为按一级 Tab 判断：

- 管理态顶栏：仅 `Tab.书架`。
- 「已移除」：仅 `Tab.书架`。
- 下载任务：`Tab.书架` 与 `Tab.云端`。
- 下载/详情/设置跳转：保持原逻辑，仅入口位置变化。

## 3. 与评审方案的差异

| 项 | 处理 | 原因 |
| --- | --- | --- |
| 云端顶栏账户/额度菜单项 | 本阶段未加入 | 属阶段 B「账户菜单改造」；当前额度仍显示在 Z-Library 页面 |
| 下拉刷新 | 本阶段未加入 | 属阶段 C；先用「更多 → 刷新」保证能力不丢失 |
| 书源 Tab 紧凑化 | 本阶段未改 | 属阶段 B；当前仍为原 `FilterChip` 行 |
| 内部枚举名仍为 `Tab.书架` | 保留 | 减少无关改动；用户可见文案已是「书库」 |

未删除任何现有业务功能；刷新、外部上传发现、WebView、下载入口均保留。

## 4. 验证

### 4.1 编译与检查

命令：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
```

结果：

- 单元测试：**352 项，0 失败，0 跳过**
- Lint：**0 error**，53 warning + 3 hint（上一基线 56 warning）
- `assembleTtsTestDebug`：成功

### 4.2 真机验证（测试版 `com.yuezhou.reader.tts.test`）

| 项 | 结果 |
| --- | --- |
| 底部导航三项 | 书库 / 云端 / 设置，选中态正常 |
| 书库页移除本地/云端切换 | 已移除，直接显示本地书籍 |
| 书库页 FAB | 导入按钮正常显示 |
| 云端页标题 | 「云端书库」 |
| 云端页顶栏 | 仅下载任务 + 更多 |
| 云端页 FAB | 已移除 |
| 云端书源 | 百度网盘 / WebDAV / Z-Library 均显示并可切换 |
| 更多菜单（云盘） | 显示「刷新云端书库」，点击无崩溃 |
| 更多菜单（Z-Library） | 显示「刷新云端书库」「网页搜索」 |
| 切换 设置 → 云端 | 仍停留在 Z-Library，筛选与输入状态保留 |

## 5. 未解决项与风险

1. 阶段 B 前，Z-Library 页面仍固定显示语言、排序、格式、额度与提示，展示面积尚未改善。
2. 云端刷新目前只有「更多」菜单入口；下拉刷新在阶段 C 补齐。
3. Z-Library 刷新仍走 `refreshSelected()`，阶段 C 会按 `resultSource` 区分关键词/热门。
4. 滚动位置恢复、展示模式与书源持久化在阶段 D 处理；本阶段未引入相关逻辑。
5. 云端页顶栏「更多」菜单目前只有刷新与网页搜索，账户/额度在阶段 B 迁入。
6. 管理态多选时切换到云端，返回书库会保留原多选状态；未在本阶段改变该行为。
7. 未新增 UI 自动化测试；本阶段为导航结构调整，采用编译、单测、Lint 与真机检查。
