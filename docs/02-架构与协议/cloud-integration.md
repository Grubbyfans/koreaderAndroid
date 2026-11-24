# 云端对接流程（WebDAV / 百度网盘）

> 状态：设计文档，尚未在 Android 端实现或真机验证。
> 对齐对象：BaiduPanSync 0.10.9 的 `schemaVersion=2` 云端协议。
> 阅读本文前先看 `docs/00-项目总览/Android阅读App_技术方案.md` §4（协议）与 §16（实施阶段）。

本文把「与 KOReader 上的 BaiduPanSync 共享云端数据」这件事拆成两条后端各自的对接流程，目标只有一个：Android 端读写出来的 `library.json`、`books/*.epub`、`covers/*.json`、`progress/*.json` 必须与插件产出的数据**逐字节语义等价**，从而可以双向互认、交替使用。

---

## 0. 云端目录约定（两种后端一致）

```
library.json            # 唯一权威书库列表，schemaVersion=2，最大 4 MiB / 10000 本
books/书名.epub          # EPUB 正文，单文件 ≤ 1 GiB
covers/书名.json         # 可选封面缩略图 {"coverPng":"<Base64 PNG>","coverVersion":"<hex>"}
progress/书名.json       # 阅读进度，schemaVersion=2，最大 64 KiB
```

- 书名 = 实际 EPUB 文件名去掉最后一个 `.epub`（扩展名大小写不敏感，书名主体大小写敏感）。
- 同名即同一本书：覆盖正文时保留云端及本机阅读进度；不比较内容、不按摘要判重。
- `books/`、`covers/`、`progress/` 三个子目录在首次上传时按需创建（`coverDirectoryReady` 标记见 §1.2）。
- 目录内不递归：只处理根层条目，子目录不扫描。

---

## 1. WebDAV 对接流程

### 1.1 连接与凭据

- **根目录 URL**：填最终根地址，禁止重定向（301/302/307/308 一律按错误处理，提示配置最终根地址）。
- **认证**：HTTP Basic（`Authorization: Basic base64(user:pass)`）。
- **TLS**：HTTPS 校验 CA 链 + 主机名（SAN），失败即拒绝（fail-closed），可指定 CA 文件；HTTP 仅限可信局域网。
- **能力**：插件侧支持 `mkdir / delete / conditional_create / conditional_replace / conditional_delete`；`move` 与 `atomic_publish` 恒为不支持。Android 端应**探测**服务器条件写入能力（`If-None-Match`/`If-Match`），未验证前不承诺条件写入。

### 1.2 目录与条目结构

| 路径 | 请求方法 | 说明 |
| --- | --- | --- |
| `library.json` | GET/PUT | 列表读写；GET 上限 4 MiB，PUT 上限 4 MiB，`Content-Type: application/json` |
| `books/书名.epub` | GET/PUT/DELETE | 正文；PUT `Content-Type: application/epub+zip`，流式读写到 `.part` 临时文件 |
| `covers/书名.json` | GET/PUT/DELETE | 封面 JSON，≤64 KiB |
| `progress/书名.json` | GET/PUT/DELETE | 进度 JSON，≤64 KiB |
| `books/`、`covers/`、`progress/` | MKCOL | 目录创建（首次上传前按需） |

**上传请求数基准**（无封面、目录已初始化、无重定向/秒传）：`GET library.json` + `PUT books/书名.epub` + `PUT library.json` = **3 次**；有封面再加 1 次 PUT。

### 1.3 关键请求细节

**PROPFIND（stat / 枚举）**：
- stat：`Depth: 0`，请求 `resourcetype`、`getcontentlength`、`getetag`、`getlastmodified`。
- 枚举：`Depth: 1`，请求 `resourcetype`、`getcontentlength`、`getetag`。
- 响应必须为 `207 Multi-Status`，`DAV:` 命名空间 `multistatus`；每条 `response` 校验 `href` 不越出主机与根目录，路径中的 `%2F`/`%5C` 视为非法；非直接子项一律拒绝。
- 资源类型：`collection` 子元素存在即目录，否则文件。

**条件写入（可选）**：
- 仅当服务器已验证条件能力时启用；`pre.unconditional=true` 时跳过条件头。
- `pre.absent=true`（无 ETag）→ `If-None-Match: *`。
- `pre.etag`（强 ETag，形如 `"..."`）→ `If-Match: <etag>`；弱 ETag（`W/`）不能用于 If-Match。

**HTTP 状态码映射**：

| 状态码 | 错误码 | 说明 |
| --- | --- | --- |
| 2xx | 成功 | — |
| 401 | AUTH | 凭据错误 |
| 403 | FORBIDDEN | 越权 |
| 404 | NOT_FOUND | 不存在 |
| 409 / 412 | CONFLICT | 条件冲突 / 目录已存在 |
| 405 | UNSUPPORTED | 方法不支持 |
| 507 | IO | 空间不足 |
| 3xx | NETWORK | 禁止重定向 |
| 其它 | NETWORK | 其它失败 |

**路径安全**：请求路径必须以 `\` 分隔的相对路径形式、位于同步根目录内；禁止 `%`、`\`、控制字符、`?`、`#`；禁止 `.`、`..` 段；禁止 `//`。枚举时遇到字面 `%`/`?`/`#` 的条目跳过并提示，不操作。

---

## 2. 百度网盘对接流程

### 2.1 授权（OAuth 2.0 OOB）

1. 授权 URL：`https://openapi.baidu.com/oauth/2.0/authorize?response_type=code&client_id=<AppKey>&redirect_uri=oob&scope=basic,netdisk&state=<state>&display=mobile`。
2. 用户在授权页获取 OOB 授权码，回填。
3. 换令牌：`POST https://openapi.baidu.com/oauth/2.0/token`，`grant_type=authorization_code&code=<code>&client_id=<AppKey>&client_secret=<SecretKey>&redirect_uri=oob`。
4. 校验返回：`access_token` 非空且 ≤4096；`expires_in` 为整数且在 (60, 315360000]；`scope` 必须含 `netdisk`；`refresh_token` 若存在则保留。
5. 拉取用户信息（`method=uinfo`）得到稳定的 `uk`（用户 ID），与本地已存 `user_id` 比对，不一致即视为账户变化需重新授权。
6. 授权状态判定：`access_token` 缺失/空、`expires_at` 距今 ≤30 秒，均视为过期。

**注意**：百度 `uk`（用户 ID）与 `fs_id`（文件 ID）是超长十进制，可能超过 JavaScript 的 2^53 安全整数范围，解析 JSON 时必须作为字符串保留，不能丢精度。Android 端建议用 `BigDecimal`/字符串处理。

### 2.2 后端能力与路径规则

- **能力**：`mkdir=true, delete=true`；`conditional_*` 与 `move`、`atomic_publish` 均为 `false`；`optimistic_versions=false`；上传内容不整书回读校验。
- **应用目录**：必须是 `/apps/<实际应用目录>`，不能含子目录或上级路径。
- **路径规则**：相对路径以 `\` 分隔、位于应用目录内；禁止 `.`、`..` 段、`\\`、控制字符、`//`。
- **端点**：
  - 元数据/文件操作：`https://pan.baidu.com/rest/2.0/xpan/...`（`/file`、`/nas`、`/multimedia`）。
  - 上传：`https://d.pcs.baidu.com/rest/2.0/pcs/file`（预上传域名查询）+ 各分片 `.../pcs/superfile2`。

### 2.3 通用请求封装

- 所有请求带 `access_token` 参数；默认 `User-Agent: pan.baidu.com`；`Content-Type` 默认 `application/x-www-form-urlencoded`。
- 响应必须是 HTTP 200 + 可解析 JSON；`errno != 0` / `error_code != 0` / `error` 视为业务错误。
- **错误码映射**（关键几类）：

| errno / error | 错误码 | 说明 |
| --- | --- | --- |
| 110, 111, -6, 20016, 20017, 31045, invalid_grant, invalid_client | AUTH | 授权无效或过期 |
| -9 | NOT_FOUND | 不存在 |
| -8 | CONFLICT | 冲突 |
| 其它 | FORBIDDEN | 接口拒绝 |

### 2.4 枚举（list）

- `GET /rest/2.0/xpan/file?method=list&dir=<dir>&start=<start>&limit=1000&order=name`。
- `start` 必须为非负且 1000 的整数倍、≤100000；单页 ≤1000 条；`#v.list == 1000` 表示还有下一页（`continuation = start + 1000`）。
- 每个条目校验：`path` 位于 `dir/` 下且为直接子项；`isdir` ∈ {0,1}；`fs_id` 为数字字符串；`size` ≥ 0。
- 条目版本（用于判重）：`version = json({fs_id, size, server_mtime, md5})`。

### 2.5 上传（预创建 → 分片 → 提交）

统一流程（EPUB 与 JSON 通用）：
1. **预创建**：`POST /rest/2.0/xpan/file?method=precreate`，`path / size / isdir=0 / autoinit=1 / rtype=3 / block_list=<json(块 MD5 列表)>`。
   - 返回 `uploadid`、`block_list`（待传分片序号列表）、`return_type`。
   - `return_type=1`（正常，需上传）→ 继续分片上传；`return_type=2`（秒传）→ 直接提交。
   - `return_type=1` 且 `block_list` 为空时视为 `{0}`（单分片）。
2. **上传域名**：`GET /rest/2.0/pcs/file?method=locateupload&appid=250528&path=<path>&uploadid=<uploadid>&upload_version=2.0`，从 `servers[].server` 中选**仅 HTTPS 且主机以 `.pcs.baidu.com` 结尾、无用户信息/端口/路径/查询**的域名。
3. **分片上传**：对 `block_list` 每个序号，`POST <host>/rest/2.0/pcs/superfile2`，`multipart/form-data`，`partseq=<index>`，正文为该分片；每个分片 ≤4 MiB（`BLOCK_SIZE=4MiB`），响应 `md5` 必须与本地该分片 MD5 一致，否则 CONFLICT 不提交。
4. **提交**：`POST /rest/2.0/xpan/file?method=create`，`path / size / isdir=0 / uploadid / rtype=3 / block_list=<json>`；返回 `path`、`size` 必须与预期一致，否则 UNCERTAIN（需重新查询）。
5. 上传前后校验源文件 size 与 mtime 未变，变了则 CONFLICT 不提交。

**单分片 JSON 上传**（`uploadSmallJson`）：块列表 = `{md5(json)}`，单分片，`filename="record.json"`，`Content-Type: application/octet-stream`，响应 `md5` 需一致。

**请求数基准**（无封面、目录已初始化、无重定向/秒传、上传 10 MiB 新书）：
- 核对账户 1 + 读列表 1 + EPUB(预创建+域名+3分片+创建=6) + 列表 JSON(预创建+域名+1分片+创建=4) = **12** 次；冷缓存（列表文件 ID 与下载链接都未缓存）为 14；只缓存文件 ID 为 13；上传封面再加 4。

### 2.6 下载（动态链接 + 7 小时缓存）

- 下载链接在**进程内**按后端/账户/令牌/文件缓存 7 小时（`expires_at = now + 7*3600`），适配器重建仍可复用；重启后按需重新获取；覆盖/删除/更换授权时失效对应缓存。
- 获取下载链接：先 `stat` 拿 `fs_id`，再 `GET /rest/2.0/xpan/multimedia?method=filemetas&dlink=1&fsids=[<fs_id>]`，取 `list[0].dlink`。
  - 校验：`item.path` 等于目标、`fs_id` 一致、`size` 合法、`dlink` 为可信 HTTPS 地址（主机为 `baidu.com` / `*.baidu.com` / `baidupcs.com` / `*.baidupcs.com` / `baidubce.com` / `*.baidubce.com`，无用户信息、无 fragment、端口 443）。
  - 同名覆盖导致 `fs_id` 变化时，`filemetas` 返回 NOT_FOUND 后清缓存、重新 stat 再取一次。
- 下载：对 `dlink` 追加 `access_token` 参数，`GET`，允许最多 4 次重定向（301/302/307/308 跟随 `Location`，用绝对化），每次校验目标仍是可信 HTTPS 地址。
  - HTTP 200 且 `size == link.size` 才算成功；否则标记 stale，清缓存刷新链接重试一次（共 2 次大循环）；401/403/404/410 同样 stale 重试；超过重定向次数或持续失败则报 NETWORK。
  - 下载上限：JSON ≤ 64 KiB（library.json 4 MiB），EPUB ≤ 1 GiB。

### 2.7 删除

- `POST /rest/2.0/xpan/file?method=filemanager&opera=delete`，`async=0`，`filelist=<json([path])>`。
- 返回 `info[0].errno == 0` 视为成功；`errno == -9` 视为已不存在（幂等）；否则 UNCERTAIN 需重新查询。
- 删除路径必须匹配 `books/*.epub` / `progress/*.json` / `covers/*.json`，否则 FORBIDDEN。

---

## 3. 两后端共用的云端业务流程（cloud_service 层）

这一层是**与后端无关**的编排逻辑，Android 端应复刻同样语义，否则数据虽然格式一致、行为却会分叉。

### 3.1 核心不变量

- 单用户、单设备交替写入；**没有**跨设备并发保护、双遍全库扫描、写入前后版本复查、发布后额外回读。
- 上传前总是先读最新 `library.json`；成功上传/删除后同步保存本机列表。
- 写入结果未知（UNCERTAIN）时**不报告成功**，提示用户重新查询。

### 3.2 列表（list / 主动更新）

- 日常进入：只读 `library.json`；当天首次进入才拉取，其余走本机缓存。
- 主动更新：读列表 → 分页枚举 `books/` 根层 → 按书名合并外部上传的 EPUB → 有变化才回写列表。
- 枚举 `books/` 时：非空且 ≤1 GiB 的 `.epub` 才登记；重名不自动选取；不支持/超长名称逐本跳过并提示原因；列表损坏保留原文件不重建。
- 更新结果汇总「新增 / 更新 / 未变化 / 跳过 / 缺失」数量。

### 3.3 上传书籍（upload_book）

1. `identify`：校验本地文件可读、≤1 GiB，按文件名去扩展名得到 `book_id`。
2. 读最新 `library.json`；不存在则 `mkdir books`、`mkdir covers` 并置 `coverDirectoryReady=true`。
3. 上传 EPUB（同名覆盖，`precondition={unconditional=true}`）。
4. 构造列表条目（schemaVersion=2）：`{bookId, title=bookId, fileName, path="books/<fileName>", size, uploadedAt, [coverPath, coverVersion]}`。
   - 覆盖已有条目时**保留**原 `coverPath` / `coverVersion`。
5. （可选）提取并上传封面 `covers/<bookId>.json`；封面失败仅告警，不阻断正文与列表。
6. 回写 `library.json`。
7. 任一写步骤失败且结果未知 → 留「待发布记录」供「补全书库列表」重试，**不重传正文**。

### 3.4 进度（progress_operation）

- 进度文件路径：`progress/<book_id>.json`。
- **上传**：读远端进度；`revision = (远端 revision 或 0) + 1`；生成新 `writeId`（随机 16 字节 hex）、稳定 `deviceId`、`updatedAt`（ISO-8601 带显式 UTC 偏移）。远端 schemaVersion≠2 或 book.id≠当前 book_id 时报 CONFLICT。
- **下载/应用**：读远端进度 → 应用到阅读器（跳转后核对实际位置，失败不伪报成功）。
- 应用前保留本机恢复点，可撤销。

### 3.5 下载书籍（download_book）

1. 定位本机下载根目录；同名本机书籍只允许覆盖**已登记位置**，不另存制造重复。
2. 空间检查、下载到同目录 `.part` 临时文件。
3. 下载后校验文件 size 与列表 `record.size` 一致，不一致则提示先主动更新书库。
4. 覆盖前保留旧 EPUB 备份、不移动原阅读设置、不覆盖当前打开的文档。

### 3.6 删除书籍（delete_book）

- 明确确认后删除所选书籍的 `books/*.epub`、`covers/*.json`（若有）、`progress/*.json`，再更新列表。
- **多文件操作不是事务**：失败可能部分完成，需核对提示并主动更新；本机文件不删除。

---

## 4. Android 端实现建议（待办映射）

| 关注点 | 建议 |
| --- | --- |
| HTTP 客户端 | OkHttp 5.3.0（已在 `libs.versions.toml`）；WebDAV 流式上传/下载、百度分片均需流式，避免整书进内存 |
| JSON 大数 | 百度 `uk`/`fs_id` 用字符串/`BigDecimal`，防 2^53 精度丢失 |
| 协议编解码 | 复用 `core/protocol` 已有的 `LibraryCatalog` / `ProgressRecord` / `CoverFile` / `BookNames` |
| 后端隔离 | 抽象 `CloudBackend` 接口（`stat/list/download/upload/uploadSmallJson/mkdir/delete`），WebDAV 与百度各自实现 |
| 流程编排 | 抽象 `CloudService`（对应插件 `cloud_service.lua`），复用列表/上传/进度/下载/删除语义 |
| 缓存 | 列表与封面按后端/账户隔离持久缓存；百度下载链接进程内 7 小时缓存 |
| 失败语义 | 区分 `failed` 与 `uncertain`（写入已发出但结果未知），不确定时禁止报成功 |
| 待发布恢复 | 实现 `publication_recovery` 等价物：正文成功但列表失败时保留待发布记录，供补全 |

---

## 5. 待确认 / 风险

- 百度开放平台需真实测试应用（AppKey/SecretKey + `/apps/` 目录 + `basic,netdisk` scope）。
- WebDAV 服务器条件写入能力需实测探测（不同服务商支持度不同）。
- 百度分片协议的 `rtype=3`、`appid=250528` 等参数以官方文档为准，接入时逐项核对。
- 所有请求数均为插件 HTTP 夹具计数，非真实服务或真机耗时承诺。
