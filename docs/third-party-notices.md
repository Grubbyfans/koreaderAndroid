# 第三方依赖来源记录

本文件记录项目引入的第三方库的来源、版本（固定提交）与许可证，满足方案 §17 的
"记录来源、提交和许可证"要求。

## foliate-js

- **仓库**：https://github.com/johnfactotum/foliate-js
- **固定提交**：`78914aef4466eb960965702401634c2cb348e9b1`（2026-05 附近，main 分支）
- **许可证**：MIT（见 `app/src/main/assets/reader/../LICENSE`，或上游 LICENSE）
- **用途**：EPUB 解析（`epub.js`）、CFI 编解码（`epubcfi.js`）、分页渲染（`paginator.js`/`view.js`）、进度（`progress.js`）、搜索（`search.js`）、高亮（`overlayer.js`）
- **引入方式**：源码直接打包进 APK assets（`app/src/main/assets/reader/`），
  WebView 通过 `WebViewAssetLoader` 加载，不依赖外部 CDN。
- **依赖库**（vendored，均随上游源码附带）：
  - `vendor/zip.js`：`@zip.js/zip.js`，BSD-3-Clause
  - `vendor/fflate.js`：fflate，MIT

## Readest xcfi.ts（仅作协议参考，未采用）

- **仓库**：https://github.com/readest/readest
- **文件**：`apps/readest-app/src/utils/xcfi.ts`
- **许可证**：AGPL-3.0
- **采用方式**：**不采用**。本项目位置转换（`core/.../position/`）为独立实现，
  仅参考其揭示的 CREngine XPointer 格式与空白折叠行为（属协议事实，非代码），
  避免 AGPL 传染。参考副本保存在 `.workbuddy/ref/`（不入库）。

## 其他

- Gradle/AGP/Kotlin/Compose/AndroidX 版本锁定见 `gradle/libs.versions.toml`。
