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

## sherpa-onnx Android 运行时（Kokoro 实验依赖）

- **版本**：`sherpa-onnx-1.13.4.aar`（官方 GitHub Release 产物）
- **来源**：https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar
- **SHA-256**：`03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780`
- **许可证**：Apache-2.0
- **本地位置**：`speech/libs/sherpa-onnx-1.13.4.aar`（仓库保留的实验依赖；测试版和正式版均不引用）
- **核验结果**：
  - ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四套 JNI。
  - 每个 ABI 含 `libonnxruntime.so`、`libsherpa-onnx-c-api.so`、`libsherpa-onnx-cxx-api.so`、`libsherpa-onnx-jni.so`。
  - `classes.jar` 含 `com.k2fsa.sherpa.onnx.OfflineTts`，支持 `newFromFile`（不依赖 AssetManager）。
  - `aar-metadata.properties` 的 `minCompileSdk=1`，不抬高项目 compileSdk 要求。
  - arm64 JNI 的 ELF `PT_LOAD` 对齐为 `0x4000`（满足 16 KB page size）。
  - 临时接入 `:app:assembleTtsTestDebug` 验证可打包，`zipalign -c -P 16` 通过；验证后已移除依赖。

## Kokoro 多语言模型 v1.1（用户导入，不随 APK 分发）

- **首选 int8**：`kokoro-int8-multi-lang-v1_1.tar.bz2`
  - 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2
  - 大小：147,052,523 字节（约 140.2 MiB）
  - SHA-256：`259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c`
- **对照/回退 fp32**：`kokoro-multi-lang-v1_1.tar.bz2`
  - 来源：https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2
  - 大小：364,855,331 字节（约 348.0 MiB）
  - SHA-256：`b78e1ac42b852e839cceaafbe88f66806bf5860992d1d7b9a3f2ad1198427aed`
- **必需文件**：`model.onnx`、`voices.bin`、`tokens.txt`、`espeak-ng-data/`、`lexicon-us-en.txt`、`lexicon-zh.txt`
- **许可证**：Kokoro-82M-v1.1-zh 模型卡标注 Apache-2.0（以发布页为准）；`espeak-ng-data` 属 GPLv3 谱系数据，仅由用户导入、不随 APK 分发。
- **分发约束**：APK 不打包模型权重与 `espeak-ng-data`；模型仅存放于应用私有目录。

## 其他

- Gradle/AGP/Kotlin/Compose/AndroidX 版本锁定见 `gradle/libs.versions.toml`。


## 内置阅读字体

APK 的 `assets/reader/fonts/` 含以下官方常规字重文件及对应的 SIL Open Font License 1.1 文本。字体文件保持上游原样。

| 字体 | 来源版本 | 随附许可证 |
| --- | --- | --- |
| Noto Serif SC | [Noto CJK Serif2.003，SC Subset OTF](https://github.com/notofonts/noto-cjk/tree/Serif2.003/Serif/SubsetOTF/SC) | `OFL-NotoSerif.txt` |
| Noto Sans SC | [Noto CJK Sans2.004，SC Subset OTF](https://github.com/notofonts/noto-cjk/tree/Sans2.004/Sans/SubsetOTF/SC) | `OFL-NotoSans.txt` |
| 霞鹜文楷轻便版 | [LXGW WenKai Lite v1.530](https://github.com/lxgw/LxgwWenKai-Lite/tree/v1.530) | `OFL-WenKai.txt` |
