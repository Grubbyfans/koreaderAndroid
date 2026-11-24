# cloud（云端对接响应样例）

存放 P4（WebDAV）与 P5（百度网盘）对接时后端返回的**脱敏响应样例**，用于：
- 离线编写/校准 `core/cloud` 下的解析器（`DavXml`、`BaiduJson`、`BaiduErrors`）。
- 后续 mock 服务器回放测试与端到端验证的基准。

与 `library/`、`progress/` 的「协议数据夹具」不同，这里放的是**传输层/接口层响应**，不是 `library.json`/`progress/*.json` 的业务数据。

## 目录

- `webdav/`：WebDAV `PROPFIND` 的 `207 multistatus` 响应样例（Depth:0 / Depth:1、含错误项、ETag）。
- `baidu/`：百度网盘上传四阶段（预创建 → 分片 → 提交）、动态下载链接、枚举、错误码样例。

## 约定

- 所有响应均脱敏：不含真实 access_token、uk、fs_id、真实文件内容。
- 字段名与插件 `baidupansync.koplugin/modules/`（`webdav.lua`、`dav_transport.lua`、`baidu_api.lua`、`baidu_upload_plan.lua`）实际使用一致。
- 大整数（`uk`、`fs_id`）以字符串原文形式呈现，警示解析层不得转 Long/Double。

## 来源

对照 BaiduPanSync 0.10.9 插件实现梳理（见 `docs/02-架构与协议/cloud-integration.md`）。
