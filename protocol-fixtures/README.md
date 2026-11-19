# protocol-fixtures（协议夹具）

存放与 BaiduPanSync 0.10.8/0.10.9（schemaVersion=2）兼容的脱敏测试数据，用于节点2的双向编解码验证和后续 P1 精准定位验收。

## 内容约定

- 只放脱敏 JSON、公开结构的测试 EPUB 和锚点样本；**不放**百度令牌、WebDAV 密码、用户正文库或发布私钥（方案 §18）。
- `library/`：`library.json` 样本，覆盖 ready 书籍、可选封面字段缺失、大小写文件名等。
- `covers/`：Base64 PNG 封面样本（≤32 KiB、≤240×320）。
- `progress/`：`schemaVersion=2` 进度样本，**不含** `contentSha256`，`updatedAt` 用 `+08:00` 显式偏移。
- `samples/`：位置转换测试 EPUB 与真实锚点（P1 阶段补充）。

## 来源

字段结构对照 `D:\code\baiduPanSync\baidupansync.koplugin\modules\` 下的 `library_catalog.lua`、`book_metadata.lua`、`book_cover.lua`、`progress_record.lua`，开发前自检该文件齐全且插件为 0.10.9。JSON 值为本人工造的脱敏数据，不含真实账户内容。
