# reader-web

TypeScript/JS 阅读适配层：foliate-js 封装、CFI/XPointer 位置转换、DOM 锚点与句子计划。

## 当前状态（P1）

- `vendor/`：foliate-js 核心源码（MIT 许可证，固定 commit `78914aef`），作为构建源。
  构建时复制到 `app/src/main/assets/reader/` 随 APK 打包。
- 位置转换核心（CFI↔XPointer）**独立实现**在 `core/src/main/java/com/yuezhou/reader/core/position/`，
  不在本目录。见 `docs/position-protocol.md`。
- 桥接脚本 `app/src/main/assets/reader/bridge.js` 连接 foliate-js 与 Android Kotlin 层。

## 许可证

- foliate-js：MIT（见 `vendor/LICENSE`）。
- Readest xcfi.ts：AGPL-3.0，**未采用**（仅作协议参考）。
- 完整来源与许可证记录见 `docs/third-party-notices.md`。

## 引入/更新方式

foliate-js 无正式 release，API 不稳定。更新时须固定 commit，记录来源，
重新复制核心文件到 assets，并回归桥接脚本（见方案 §15.2）。
