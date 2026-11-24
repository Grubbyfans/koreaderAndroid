# 阶段 C 实施报告（配置与凭证 + 网络层）

分支：`feature-zlibrary`　节点：`f0b4ea6`（C1）、`a43011b`（C2）　状态：已完成并暂停，等待确认。

## 1. 本阶段实现的功能

- **C1 配置与凭证**
  - 新增 `ZLibraryConfig` 配置模型：服务器、网络超时、7 个端点、默认偏好；内置默认 `limit=30`、`order` 数组语义、`POST /eapi/book/search`。
  - 配置校验：仅 HTTPS、禁止 userinfo/查询/路径穿越、端点方法与格式、分页 1–30、格式仅 EPUB；无效配置不覆盖当前可用配置。
  - 配置存储：应用私有 JSON 文件 + `.bak` 备份，支持导入校验、导出（不含凭证）、回滚、恢复默认。
  - 凭证存储：按 provider + 账号派生独立 Keystore alias，AES/GCM 加密落盘；账号、Token、Cookie 不进入配置文件，不匹配日志/导出。
  - 设置页「书源设置」卡片新增 Z-Library 一栏，点击进入独立设置页（服务器、偏好、配置管理、账号）。
  - 新增 HTTPS 连接测试：不跟随重定向；3xx 的 Location 主机必须在可信列表内，否则阻止。

- **C2 网络层**
  - `ZLibraryApi`：登录（主端点凭证拒绝才回退备端点；限流/额度错误不回退）、搜索、详情、下载链接、额度查询、域名发现（仅返回候选，不自动信任）。
  - 搜索表单按参考实现语义构造：`message/page/limit=30/content_type=article/extensions[0]/languages[0]/order[1]`；搜索免登录，详情/下载链接/额度需要会话。
  - 错误分类：`AUTH / QUOTA / RATE_LIMITED / NETWORK / NOT_FOUND / UNKNOWN`；额度错误不按认证错误处理。
  - 下载链接强制 HTTPS，Cookie 只发送给配置的可信主机。
  - `ZLibraryBookProvider` 实现 `CatalogBookProvider`，提供登录/退出/额度/连接状态；下载明确返回 `UNSUPPORTED`（队列与导入属阶段 E）。
  - 设置页账号卡片：登录对话框（密码隐藏）、已登录状态、额度查询、退出登录。

## 2. 新增文件及用途

| 文件 | 用途 |
|---|---|
| `core/.../online/config/ZLibraryConfig.kt` | 配置模型、内置默认值与校验规则 |
| `core/.../online/config/ZLibraryConnectionTester.kt` | HTTPS 连接测试与重定向主机校验；配置导出 |
| `core/.../online/api/ZLibraryApi.kt` | Z-Library 全部网络端点、表单构造、数据映射与错误分类 |
| `core/.../online/ZLibraryConfigTest.kt`（test） | 5 项配置校验单测 |
| `core/.../online/ZLibraryApiTest.kt`（test） | 8 项网络层单测（端点/登录回退/限流/重定向/错误分类/映射/额度） |
| `reader/.../online/config/ZLibraryConfigStore.kt` | JSON 文件存储、导入、回滚、恢复默认 |
| `reader/.../online/config/OnlineCredentialStore.kt` | 独立加密凭证存储（按账号隔离 Keystore alias） |
| `reader/.../online/zlibrary/ZLibraryBookProvider.kt` | 检索型书源适配器 |
| `app/.../ui/online/ZLibrarySettingsScreen.kt` | Z-Library 书源设置页（服务器/偏好/配置管理/账号） |

## 3. 修改文件及修改原因

| 文件 | 修改原因 |
|---|---|
| `reader/.../reader/CredentialCrypto.kt` | 增加带 alias 的加解密重载；无 alias 的旧接口行为不变，原云端凭据不受影响 |
| `reader/.../online/config/OnlineCredentialStore.kt` | C2 增加 `userId` 字段，用于保存登录返回的账号标识 |
| `app/.../MainActivity.kt` | 「书源设置」卡片新增 Z-Library 行与二级页面跳转；未改动阅读/朗读/同步逻辑 |

## 4. 实际页面布局与交互效果

- 设置 →「书源设置」→ WebDAV / 百度网盘下方新增「Z-Library · 检索型书源 · 配置、登录与额度 / 去配置」。
- 二级页从上到下：服务器卡片（占位地址提示、配置版本、编辑地址、连接测试）→ 默认偏好（语言、分页 30、保存）→ 配置管理（导入/导出 JSON、回滚、恢复默认）→ 账号（未登录提示、登录；登录后显示账号、查询额度、退出）。
- 真机截图确认文字完整、无竖排/裁切/重叠；底部导航仍为「书库 / 设置」两项。
- 云端页回归：书源 Tab（百度网盘/WebDAV/Z-Library 灰显）、标题栏右上角下载图标、未配置提示均正常。

## 5. 编译及构建结果

- `./gradlew test`：**213 项通过、0 失败**（阶段 B 为 200 项，新增 13 项）。
- `./gradlew :app:assembleTtsTestDebug`：**成功**；包名 `com.yuezhou.reader.tts.test`，版本 `0.1.0-tts-test`，应用名「静读测试版」。
- Kotlin 编译告警均为既有 deprecated API（进度条、List 图标），本次未做无关重构。

## 6. 功能测试及回归测试结果

- 真机 `adb install -r` 成功（SM_S9360）。
- 真机打开 Z-Library 设置页：四类卡片与全部按钮可见；未触发登录/连接测试（无合法授权账号与服务器）。
- logcat：应用进程无 FATAL/ANR/异常栈；仅 4 条厂商/系统提示（Adreno、ashmem、perf_hint、libpenguin.so）。
- 云端页与下载入口回归正常；本地书库、阅读器、朗读、KOReader 同步代码零改动。

## 7. 已知问题及潜在风险

1. 默认服务器地址为占位 `https://example.com`，未配置真实可信服务器前连接测试与登录不可用；这是刻意的安全默认。
2. Z-Library 为非公开 API，字段/端点可能随服务端变化；本阶段按参考实现语义实现，尚未用合法授权资源做真机接口实测，不保证服务端当前可用性。
3. 下载入口在适配器层返回 `UNSUPPORTED`；实际下载、EPUB 校验、文件名唯一判断与同名覆盖属阶段 E。
4. 动态域名仅返回候选列表，不自动信任、不绕过 bot challenge；用户须确认并写入配置。
5. 二级设置页沿用既有「同步记录」页的 Scaffold 模式，出现上下两个标题栏；与现有页面风格一致，未做无关重构。
6. AGPL 参考仅保留设计记录，代码为 Kotlin 独立实现；后续公开分发/商业化需重新评估许可义务。

## 8. 需要人工验证的事项

1. 配置你有权访问的可信 HTTPS 服务器后，验证连接测试、登录、退出与额度展示。
2. 导入一份非法 JSON，确认提示错误且当前配置未被覆盖；再验证导出 JSON 中不含账号/Token/Cookie。
3. 修改地址后验证回滚与恢复默认；确认 `.bak` 可恢复上一份有效配置。
4. 未登录搜索与「下载需登录」的交互将在阶段 D/E 接入后再验证。
5. 回归本地阅读、原生 TTS、Kokoro 朗读、KOReader 进度同步与百度/WebDAV 云端同步。

## 9. 暂停声明

阶段 C 已完成并暂停，**不会自动进入阶段 D（检索页）**。确认后再继续。
