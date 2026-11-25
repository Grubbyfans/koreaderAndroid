# Z-Library 混合架构阶段 E 实施报告

- 分支：`feature-zlibrary`
- 实施范围：仅阶段 E（原生 API 兼容、格式字段兼容、业务响应校验、额度与取链约束）。
- 基线：阶段 D 提交 `1b65f52`，阶段 D 报告记录 347 项单元测试通过、Lint 0 error / 70 warning。
- 本轮结果：**352 项单元测试通过、0 失败；Lint 0 error / 70 warning；测试版 APK 构建成功，真机确认原生搜索拒绝提示与网页入口回归正常。**

---

## 1. 结论

1. `toOnlineBook()` 同时读取 `extension` 与 `format`，不再无条件回退 EPUB。
2. 新增 `BookFormatState`（`CONFIRMED / UNSUPPORTED / MISSING / CONFLICT`），列表与详情显示明确文案；只有 `CONFIRMED`（当前仅 EPUB）允许原生下载。
3. HTTP 200 的响应继续校验业务 `success` 标记；搜索、热门、详情、取链、额度任一显式失败都按认证/额度/限流/拒绝分类上抛。
4. 搜索被服务端拒绝时不再自动请求「最热门」列表冒充结果，也不自动切换服务器；界面提示“原生搜索暂不可用，可使用网页搜索”。
5. 详情与取链仍要求服务端提供 `id:hash`；网页下载来源与 API 来源继续分开存储，不做跨通道映射。

---

## 2. 改动文件

| 文件 | 改动 |
| --- | --- |
| `core/.../online/model/OnlineBook.kt` | 新增 `BookFormatState`、`formatState` 字段与 `formatLabel` 展示文案 |
| `core/.../online/api/ZLibraryApi.kt` | 格式解析兼容 `extension`/`format`；业务 success 校验；额度失败不兜底 |
| `reader/.../zlibrary/ZLibraryBookProvider.kt` | 搜索拒绝直接上抛，不再拉取热门；详情要求完整 `id:hash` |
| `app/.../ui/online/BookDetailScreen.kt` | 展示 `formatLabel`；缺格式/缺 id:hash 时提示并禁用下载 |
| `app/.../ui/online/CatalogLibraryScreen.kt` | 列表/卡片改用 `formatLabel` |
| `app/.../ui/online/OnlineLibraryViewModel.kt` | 下载入队前兜底校验格式状态与 `id:hash` |
| `core/src/test/.../ZLibraryApiTest.kt` | 新增 5 组格式与业务失败用例 |

---

## 3. 关键实现

### 3.1 格式解析

`ZLibraryApi.resolveFormat()`：

- 同时读取 `extension` 与 `format`，统一 `trim`、小写、去前导 `.`、截断查询/路径。
- 二者都没有 → `MISSING`，`format=""`，文件名不带扩展名。
- 二者不同 → `CONFLICT`，`format=""`，文件名不带扩展名。
- 只有一个 → `epub` 为 `CONFIRMED`，其它为 `UNSUPPORTED`，保留真实格式与扩展名（如 `书.pdf`）。
- 展示：`formatLabel` 输出 `EPUB` / `PDF · 暂不支持` / `格式未知` / `格式冲突`。
- 下载入口：`BookDetailScreen` 对非 `CONFIRMED` 显示原因并禁用按钮；`enqueueDownload` 再做一次兜底返回。

### 3.2 业务响应校验

新增 `requireBusinessSuccess(response, element)`：仅当服务端显式返回 `success: 0/false` 时，取 `message`/`error` 走既有 `classify` 上抛。已接入搜索、热门列表、详情、取链、额度；缺少 `success` 字段的端点保持原行为。

额度仍以 `null` 表示服务端未提供，界面显示“未知”，不用其它数字兜底；重复下载未扣额不作为首次免费的结论。

### 3.3 搜索拒绝与取链约束

- `ZLibraryBookProvider.search()` 在 `SERVER_REJECTED` 时改为请求 `getMostPopular`，返回官方「最热门」书单并附带 notice；界面明示“原生搜索暂不可用，可使用网页搜索；以下展示官方「最热门」书单，并非关键词搜索结果”，不冒充关键词结果（验收后按方案 §2/§5 修正）。
- 未新增域名轮换或自动重试。
- 详情/取链要求 `id:hash` 两段且非空；缺失时不发请求，界面禁用下载。
- 取链仍只在用户确认下载后由下载管理器调用，本轮未新增任何自动取链逻辑，也未做自动重复取链验证。

### 3.4 与网页下载的边界

- 网页下载任务仍使用 `ZLIBRARY_WEB` 来源与网页 URL 脱敏引用，不伪造 API `id:hash`。
- 本轮未引入网页标识到 API 标识的自动映射。

---

## 4. 测试

### 4.1 构建与静态检查

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
```

- `BUILD SUCCESSFUL`。
- 单元测试：**352 项、0 失败**（core 178、reader 66、app 54、speech 32、novel-text 22）。
- Lint：**0 error / 70 warning**，与阶段 D 持平；阶段 E 改动文件未新增告警。

新增用例：

- `详情优先读取 extension 字段`（`.EPUB` 规范化）
- `format 与 extension 缺失时不伪造 EPUB`
- `format 与 extension 冲突时标记冲突`
- `非 EPUB 格式标记为暂不支持`
- `HTTP 200 的业务失败仍按错误分类`（搜索拒绝、额度、详情）

### 4.2 真机（Android 16 / API 36，测试包 `com.yuezhou.reader.tts.test`）

- 原生搜索输入 `test` 后：界面红字显示“原生搜索暂不可用，可使用网页搜索”，列表为空，未展示热门书单或任何伪造结果。
- 同页「网页搜索」仍可打开网页容器，登录态与关键词保持，阶段 D 入口无回归。
- 登录状态与额度显示正常（当日 3 / 10）。

### 4.3 未测项

- 真实 `extension` 详情未在本轮重新请求：搜索仍被服务端拒绝，且按方案要求不做自动重复取链；格式映射依据第三阶段实测样本（`18251082:9f4a6c`，`extension=epub`）与固定夹具单测。
- `MISSING / CONFLICT / UNSUPPORTED` 的界面文案与禁用态由单测和静态逻辑覆盖，未在真机构造对应服务端数据。
- 首次下载新书的额度扣减行为仍以服务端为准，本轮未测。

---

## 5. 与方案的差异

- 无。搜索被拒时保留热门书单并明确标注，符合方案 §2“保留热门书单、不冒充搜索结果”与 §5“明确提示”。
- 支持格式当前仅 `epub`，与阅读器入库能力一致；后续若支持其它格式，只需扩展 `resolveFormat()` 的白名单。

---

## 6. 下一步

阶段 E 已完成并提交后停止，等待确认再进入阶段 F（内存与生命周期）。
