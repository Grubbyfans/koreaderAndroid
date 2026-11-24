# Z-Library 服务器发现与候选列表管理：阶段 A 核对报告与实施计划

版本：1.0｜日期：2026-11-24｜性质：增量补充方案阶段 A（只读核对 + 计划纳入）

依据文档：`docs/05-Z-Library服务器管理/静读_Z-Library服务器发现与候选列表管理技术方案.md`（下称「增量方案」），
原方案：`docs/04-在线书库统一化/静读在线书库统一化与多书源扩展技术方案.md`（下称「原方案」）。

## 1. 当前项目进度

- 分支 `feature-zlibrary`，HEAD `3074a03`，工作区原有改动仅限新增的增量方案文档（未跟踪）。
- 原方案阶段 A（分析设计）、B（Provider 抽象 + 统一书源入口）、C（C1 配置凭证 + C2 网络层）已完成并各自提交、汇报、暂停确认过；两份 UI 修复（配色、连接测试结果位置、`3074a03`）已在本节点前完成。
- **原方案尚未实施**：阶段 D（检索页 + 详情 + 状态恢复）、E（下载管理 + EPUB 校验 + 文件名唯一判断/同名覆盖导入）、F（云端上传联动）、G（全量回归）。
- 用户已确认执行顺序：**先完成本增量方案 A–E，再继续原方案剩余阶段（D/E/F/G）**。增量 A–E 串行优先实施，原方案后续阶段顺延。每个阶段编译、测试、汇报、提交、暂停确认。

## 2. 现有实现核对（源码位置）

| 能力 | 现有文件 | 现状 |
|---|---|---|
| 配置模型 | `core/.../online/config/ZLibraryConfig.kt` | `servers.mode: String`（auto/manual）+ `servers.baseUrls: List<String>`；无 `manualBaseUrl`、无候选对象、无 `discovery` 配置节；`schemaVersion=1` 固定校验 |
| 配置校验 | 同上 `ZLibraryConfigValidator` | HTTPS、禁 userinfo/查询/片段/路径穿越；**无**私有/环回/链路本地拒绝、无规范化判重、无条数与大小上限 |
| 持久化/导入导出/回滚 | `reader/.../online/config/ZLibraryConfigStore.kt` | JSON 文件 + `.bak` 回滚 + 临时文件原子写入；`importText()` 整份校验，无预览/合并/替换、无大小上限 |
| 连接测试 | `core/.../online/config/ZLibraryConnectionTester.kt` | 单次 HEAD，仅判 HTTP 200；无延迟、状态枚举、并发/取消、响应结构校验 |
| 网络层 | `core/.../online/api/ZLibraryApi.kt` | `baseUrl = baseUrls.first()`；`buildClient()` 不跟随重定向 + `verifyRedirect()` 主机白名单；`discoverDomains()` 仅返回候选、不自动信任 |
| 书源适配 | `reader/.../online/zlibrary/ZLibraryBookProvider.kt` | 每次调用 `ZLibraryApi(config)`；无「最近可用地址」选择、无会话与主机信任绑定 |
| 凭证 | `reader/.../online/config/OnlineCredentialStore.kt` | 按账号独立 Keystore alias 加密；**无**「可信服务范围」绑定，切换服务器不清会话 |
| 设置页 UI | `app/.../ui/online/ZLibrarySettingsScreen.kt` | 「服务器」卡片：地址展示、编辑弹窗、连接测试（结果在按钮下方）；无候选列表管理 |

参考仓库：固定 commit `b7eacbab6cd7e64259857424749f0e2f26ff7bc5`、AGPL-3.0、发现机制（种子 + 动态域名、3 并发竞速、缓存 600 秒、屏蔽 bot challenge、过滤 `.onion`）与许可证结论已在 `docs/04-在线书库统一化/阶段A-源码分析与架构设计报告.md §5` 核实记录，本次直接沿用，不重复联网取证。

## 3. 增量方案 vs 现有实现：差异清单

| 增量方案要求 | 现有实现 | 差异处理 |
|---|---|---|
| `server.mode` + `server.manualBaseUrl` + `server.candidates[]`（对象含 id/enabled/priority） | 仅 `mode` + 字符串数组 | **需升级 schema**：新增候选对象列表与 manualBaseUrl；旧 `baseUrls` 迁移为启用候选（priority 按序） |
| `discovery{timeoutMs, cacheTtlSeconds, maxConcurrentProbes}` | 仅 `network{connectTimeoutMs, readTimeoutMs}` | 新增 discovery 节，探测超时与业务超时分离 |
| `ProbeStatus/ProbeResult/DiscoveryState` 运行状态**不写入导出配置** | 无运行状态 | 新增独立运行状态文件（如 `files/providers/zlibrary_discovery_state.json`），导出 JSON 不含此内容 |
| 50 项 / 256 KiB 上限、规范化判重（主机小写、默认端口、尾斜杠） | 无 | 扩展校验器；判重按规范化 URL，不改写路径大小写 |
| 拒绝私有/环回/链路本地/本地目标 | 允许所有 HTTPS 主机 | 仅在 **Z-Library 校验器** 补该限制；不动 WebDAV 局域网能力 |
| JSON + 纯文本逐行导入、预览新增/重复/无效、合并/替换确认 | 仅 JSON 整份导入 | 扩展 Store 解析 API（预览 + 合并/替换在 UI 确认），无效配置不覆盖 |
| 无认证探测、HTTP 200 不够需校验响应结构 | HEAD + HTTP 200 | 新增独立 `ServerProbeClient`（与认证 client 隔离），探测轻量健康检查端点并校验预期结构 |
| 并发 3、优先级顺序提交、可取消、single-flight、TTL 缓存、配置版本防竞态 | 全部缺失 | 新增 `ServerDiscovery`；以配置版本号作废旧任务；低优先级不抢先提交 |
| 最近可用地址复用、重新选择、配置变更清缓存 | 固定 `baseUrls.first()` | `ZLibraryApi` 支持注入选中 baseUrl；`ZLibraryBookProvider` 通过发现层取地址 |
| 地址可用 ≠ 身份可信、切换未授权地址需重新登录 | 凭证无主机绑定 | `OnlineCredentials` 增加作用域主机记录；新地址未确认前不发送既有 Cookie |
| UI 候选列表（增删改、启停、排序、单项/全部检测、重新选择） | 无 | 新增 `ServerSettingsScreen`，从现有 Z-Library 设置页「服务器」卡片进入 |

**入口差异（需确认）**：增量方案 §2 写「云端 → Z-Library → 书源设置 → 服务器配置」，但当前实际入口是「设置页 → 书源设置 → Z-Library」，且云端页 Z-Library 目前为灰显占位（阶段 D 才启用）。按原方案 §15 第 1 项「书源设置放在设置页」的最高优先级，本次按实际结构实现：**设置页 → 书源设置 → Z-Library → 服务器配置**，不改变此前决策。

## 4. 可复用模块（避免重复开发）

- `ZLibraryConfigStore` 的 JSON 文件、`.bak` 备份、原子写入模式 → 直接扩展，不新建配置仓库。
- `ZLibraryConfigValidator.validateBaseUrl()` → 扩展而非重写，保留 HTTPS/userinfo/路径校验。
- `ZLibraryConnectionTester.test()` 的重定向主机校验 → 迁移到独立无认证探测客户端复用其安全语义。
- `ZLibraryApi.buildClient()` / `verifyRedirect()` 的「不自动跟随重定向 + 白名单」→ 探测客户端复用同一原则，但**另建 client 实例**确保无 Cookie。
- `OnlineCredentialStore` 的 Keystore + 按账号 alias 加密 → 仅增加作用域字段，不换加密方案。
- `ZLibrarySettingsScreen` 现有服务器卡片 → 改为「服务器配置」入口，不必重做整个设置页。
- 项目主题 `YuezhouColors` / `Surface` 卡片样式（已在 `1054b89` 统一）→ 新页面直接沿用。
- 无数据库表新增；不新增底部导航；不触碰阅读内核/TTS/同步协议。

## 5. 主要风险

1. **配置迁移**：旧 `baseUrls` 字符串数组 → 候选对象列表，须保证升级不丢用户地址、不被内置默认列表覆盖；保留上一份有效配置可回滚。
2. **探测误判**：HTTP 200 非预期内容（bot challenge、HTML 错误页）不得判为可用；须校验响应结构。
3. **凭证泄漏**：探测客户端必须无认证；新地址默认未授权接收凭证，切换须重新登录；日志与导出均脱敏。
4. **竞态**：配置版本变化时旧探测结果须作废；single-flight 共享任务；页面关闭取消手动探测。
5. **API 稳定性**：非公开接口与域名可能变化；不绕过 bot challenge；仅用合法授权资源测试。
6. **与原方案并行**：阶段 D（检索页）接入时需使用发现层选出的 baseUrl，避免在 `ZLibraryApi` 中形成第二套地址选择逻辑。

## 6. 纳入后续开发计划

**用户确认的执行顺序：先完成增量 A–E，再回到原方案 D/E/F/G（顺延，不再并行）。** 每阶段编译、测试、汇报、提交、暂停确认。

| 节点 | 内容 | 与原方案关系 | 验收要点 |
|---|---|---|---|
| **增量 A**（本次） | 核对现有实现与差异、纳入计划 | 只读；原 A–C 已完成 | 本报告 + 计划纳入 |
| **增量 B** | 配置模型升级、持久化、迁移、导入导出、运行状态分离 | 独立于原 D/E；先行 | schema 升级不丢配置；非法导入可回滚；导出无运行状态 |
| **增量 C** | 无认证探测、响应结构校验、优先级/并发/取消/TTL/版本防竞态、最近可用地址选择 | 为原 D 提供 baseUrl 来源 | 无凭证泄漏；优先级正确；HTTP 200 非预期内容不判可用 |
| **增量 D** | 服务器配置页 UI：候选列表增删改、启停、排序、单项/全部检测、重新选择、手动模式 | 设置页子页，不改导航 | 布局按增量方案 §2；状态/耗时/当前使用展示；全部禁用提示 |
| **增量 E** | 接入 `ZLibraryApi`/`ZLibraryBookProvider`，会话信任范围，回归 | 与原 D（检索页）衔接；原 E/F/G 仍按原计划 | 手动模式不切换；额度/认证错误不切换；百度/WebDAV/KOReader/阅读/TTS 回归 |
| 原 D/E/F/G | 检索页、下载管理、云端联动、全量回归 | 保持原计划不变 | 见原方案验收清单 |

原方案 §15 的六项最终决策（文件名唯一判断、同名确认覆盖、两表数据库、两项底部导航、`OnlineBook` 命名、Z-Library 默认参数、AGPL 干净重写）全部继续有效；本增量方案不改变其中任何一项。

## 7. 阶段 A 暂停声明（用户已确认顺序，阶段 B 启动）

本次仅完成只读核对、差异分析与计划纳入，**未编写业务代码**。

用户确认：先实现服务器发现这一块（增量 A–E），再实现后续计划（原方案 D/E/F/G）。阶段 A 的三项待确认事项按报告结论执行（入口按实际结构调整、运行状态独立文件、不新增数据库表）。现进入增量阶段 B。
