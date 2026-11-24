# 阶段 D 实施报告（检索页：搜索 / 筛选 / 分页 / 详情 / 封面）

对应方案：`docs/04-在线书库统一化/静读在线书库统一化与多书源扩展技术方案.md`
前置：`docs/04-在线书库统一化/阶段C-实施报告.md`、`docs/05-Z-Library服务器管理/服务器设置优化-实施报告.md`
分支：`feature-zlibrary`　状态：**已完成并暂停，等待确认；未进入阶段 E（下载管理）**

## 1. 本阶段实现的功能

- **统一入口启用 Z-Library**：云端作用域顶部书源 Tab 由「百度网盘 / WebDAV / Z-Library（灰显）」改为三项均可用；Z-Library 走独立检索页，原有两项云端书库与本地书库行为不变。
- **检索与筛选**：关键词输入（书名 / 作者 / ISBN）、语言筛选（全部 / 中 / 英 / 日 / 俄 / 法 / 德 / 西）、排序（最佳匹配 / 最热门 / 最近加入 / 年份）、格式固定 EPUB（第一版范围）。书源设置页保存的默认语言作为初始筛选值。
- **分页与状态隔离**：按 `remoteId` 去重追加；新搜索取消旧任务并清空分页；过期请求结果不写回；失败保留旧列表；每书源独立保存关键词、筛选、分页游标、滚动位置、列表/网格模式与详情；切换书源或从详情返回时恢复。
- **详情与封面**：点击进入独立详情页，展示搜索结果字段并异步补全服务端详情；未登录时给出「登录后可查看完整详情并下载」提示但不丢失页面状态。封面按 URL 在内存中缓存，仅 HTTPS、不携带 Cookie、不跟随重定向、限制 2 MiB 与非图片响应；失败回退文字占位。
- **下载按钮保持禁用**：下载队列、EPUB 校验、文件名唯一判断与同名覆盖属于阶段 E，本阶段不产生任何文件写入或额度消耗；详情页明确标注「后续阶段接入」。

## 2. 主动修正的参考接口差异

核对固定 commit `b7eacbab6cd7e64259857424749f0e2f26ff7bc5` 的 `zlibrary/api_books.lua` 与 `zlibrary/config_catalog.lua` 后发现阶段 C 的表单构造与参考实现有偏差，本阶段按实际源码修正（不复制 Lua 代码）：

| 项目 | 阶段 C 原实现 | 阶段 D 修正 | 依据 |
|---|---|---|---|
| 排序字段 | `order[1]=relevance` | `order=bestmatch`（另有 popular/date/year） | 参考实现把 `order[1]` 的值以 `order=` 写入表单；`relevance` 不是有效值，最佳匹配为 `bestmatch` |
| 格式值 | `extensions[0]=epub` | `extensions[0]=EPUB` | `SUPPORTED_EXTENSIONS` 使用大写值 |
| 内容类型 | 固定 `content_type=article` | 不发送 | 文章模式会忽略格式过滤；第一版只检索 EPUB 图书 |
| 语言值 | 配置默认 `zh`（ISO 码） | 检索时映射为 API key，如 `chinese` / `english` | `SUPPORTED_LANGUAGES` 使用服务端语言 key；配置文件保持原 schema 不变 |

该修正只影响 Z-Library 搜索请求构造，不改变配置 schema、凭证存储、KOReader 协议或云端索引。

## 3. 新增文件及用途

| 文件 | 用途 |
|---|---|
| `core/.../online/model/OnlinePageMerger.kt` | 分页追加按 remoteId 去重的纯逻辑 |
| `app/.../ui/online/CatalogLibraryScreen.kt` | 检索页：搜索框、筛选、列表/网格、分页、封面卡片 |
| `app/.../ui/online/BookDetailScreen.kt` | 详情页：元数据、简介、自动导入开关、下载入口占位 |
| `core/src/test/.../online/OnlinePageMergerTest.kt` | 2 项分页去重单测 |
| `docs/04-在线书库统一化/阶段D-实施报告.md` | 本报告 |

## 4. 修改文件及原因

| 文件 | 修改原因 |
|---|---|
| `core/.../online/api/ZLibraryApi.kt` | 修正搜索表单语义；新增受限的 `fetchCover()` |
| `core/.../online/provider/BookProvider.kt` | `CatalogBookProvider` 增加默认 `fetchCover()`，不支持的书源回退占位封面 |
| `reader/.../online/zlibrary/ZLibraryBookProvider.kt` | 排序/格式/分页规范化、未配置服务器返回 `NotConfigured`、转发封面请求 |
| `app/.../ui/online/OnlineLibraryViewModel.kt` | 注册 Z-Library；按源保存检索与详情状态；请求取消/去重/过期结果隔离；封面缓存与额度展示 |
| `app/.../ui/online/OnlineLibraryScreen.kt` | 启用 Z-Library Tab；检索页与云书库分流；详情全屏覆盖与返回键处理 |
| `app/.../MainActivity.kt` | 检索页「配置服务器」入口跳转到 Z-Library 设置页；检索书源刷新走重新搜索而非云端索引同步 |
| `core/src/test/.../online/ZLibraryApiTest.kt` | 更新表单断言，新增封面 HTTPS / 重定向 / 非图片响应测试 |
| `docs/README.md` | 文档索引加入阶段 D |

未修改：`core/cloud/*`、`core/protocol/*`、`core/position/*`、阅读器与朗读代码、`library.json` 与进度协议、Room 数据库、测试版包名与应用名。

## 5. 编译与测试结果

- `./build-local.sh :core:test :reader:compileDebugKotlin :app:compileTtsTestDebugKotlin`：成功（修复了一次 `items` 扩展未导入导致的编译错误）。
- `./build-local.sh test :app:assembleTtsTestDebug`：**成功，240 项测试、0 失败、0 错误**；产物 `app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk`（161 MB）。
- 警告均为既有 deprecated API（进度条、List 图标、Kokoro 导入）与原因明确的存量告警；本次未做无关重构。

## 6. 真机验证状态

- 本次构建时 `adb devices` 未列出设备，**未能执行安装与真机交互验证**；APK 已生成，可直接 `adb install -r app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk`。
- 建议在已配置可信 HTTPS 服务器的设备上验证：检索、语言/排序筛选、加载更多、断网保留列表、详情返回恢复滚动、封面显示、未登录提示、配置入口跳转；并回归本地阅读、TTS、Kokoro、KOReader 同步。

## 7. 已知问题及风险

1. Z-Library 为非公开 API，端点与字段可能变化；未使用合法授权账号做服务端实测，不保证服务端当前可用性。
2. 默认候选服务器列表为空，未配置时检索页提示前往设置页；不内置任何真实镜像地址（沿用安全默认）。
3. 下载按钮禁用，完整详情可能在未登录时不可用；登录后重试详情与下载操作在阶段 E 一并验证。
4. 封面按 URL 内存缓存，退出应用后清空；不新增磁盘缓存表，符合方案 §9。
5. AGPL 参考仅保留设计记录，代码为 Kotlin 独立实现；公开分发/商业化需重新评估许可义务。

## 8. 暂停声明

阶段 D 已完成并暂停，**不会自动进入阶段 E（下载管理、EPUB 校验、同名覆盖导入）**。确认后再继续。
