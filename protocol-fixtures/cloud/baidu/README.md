# baidu（百度网盘接口响应样例）

对齐 BaiduPanSync `modules/baidu_api.lua` + `baidu_upload_plan.lua` + `baidu_auth.lua`，
以及本仓库 `core/cloud/baidu/BaiduBackend.kt` 实际调用的接口。

## 文件清单

| 文件 | 对应接口 | 说明 |
| --- | --- | --- |
| `uinfo.json` | `xpan/nas?method=uinfo` | 授权就绪校验，取 `uk`（大整数，字符串承载） |
| `precreate.json` | `xpan/file?method=precreate` | 预创建，返回 `uploadid`、`block_list`、`return_type` |
| `locateupload.json` | `xpan/file?method=locateupload` | 动态上传域名，取 `server` |
| `superfile2-upload.json` | `pcs/superfile2?method=upload` | 单分片上传，返回 `md5` |
| `create-commit.json` | `xpan/file?method=create` | 分片提交，返回 `fs_id`、`path`、`size`、`md5` |
| `list-files.json` | `xpan/file?method=list` | 枚举目录，返回 `list[]`（含文件与目录） |
| `filemetas-dlink.json` | `xpan/multimedia?method=filemetas` | 取动态下载链接 `dlink`（进程内 7 小时缓存） |
| `error-auth-expired.json` | — | 授权失效错误样例（errno=-6 / error_code=31045） |
| `error-not-found.json` | — | 文件不存在错误样例（errno=-9） |

## 关键警示

- **`uk`、`fs_id` 是可能超过 2^53 的大整数**，样例里以字符串原文呈现，解析层必须用
  `BaiduJson.strField` 读字符串，**严禁**转 Long/Double（会精度丢失导致身份/文件判重错误）。
- `return_type=2` 表示秒传（rapid upload）；`return_type=1` 表示需正常分片上传。
- 上传流程：`precreate` → `locateupload`（取动态域名）→ `superfile2`（逐片，4MiB）→ `create`（提交）。
- 下载流程：`filemetas`（取 `dlink`）→ 跟随重定向下载，`dlink` 进程内缓存 7 小时，过期/stale 需重取。

## 错误码映射（对齐 BaiduErrors.kt）

| errno / error_code | 语义 | 映射 ErrorCode |
| --- | --- | --- |
| -6 / 31045 / 110 / 111 / 20016 / 20017 / invalid_grant / invalid_client | 授权失效 | AUTH |
| -9 | 文件不存在 | NOT_FOUND |
| -8 | 已存在/冲突 | CONFLICT |
