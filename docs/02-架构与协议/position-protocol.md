# 位置转换协议说明（CFI ↔ XPointer）

本文记录「阅舟」阅读器的精准定位转换实现依据，供后续开发和 oracle 验收参考。

## 1. 定位格式总览

云端（KOReader）权威位置是 **CREngine XPointer**；Android 端（foliate-js）用 **EPUB CFI**。
两者需双向转换，才能实现 KOReader 与阅舟之间的精准进度同步。

| 端 | 格式 | 说明 |
| --- | --- | --- |
| KOReader | CREngine XPointer | 云端权威位置，`progress/*.json` 的 `locator.value` |
| 阅舟 (foliate-js) | EPUB CFI | 本地阅读位置，`epubcfi(...)` 形式 |

## 2. CREngine XPointer 格式

单 EPUB spine 的 XPointer 形态：

```
/body/DocFragment[N]/body/div/p[3]/text().12
```

- `DocFragment[N]`：**1-based** spine 序号；全书仅一个 spine item 时省略 `[N]`。
  - 与 CFI spine step 的关系：CFI 用偶数 `2,4,6...`，换算为 0-based 序号 `(step-2)/2`；
    XPointer `DocFragment[N]` 的 0-based 序号为 `N-1`。
- 元素步：`tag` 或 `tag[1-based 序号]`。同标签唯一时省略序号。
  - 新版 crengine（137cba4 起）会给无同名兄弟的步也写 `[1]`，解析时统一视为唯一。
- 文本定位三种形态：
  1. `/text().N` —— 元素的**唯一**直接文本子节点，折叠后偏移 N。
  2. `/text()[K].N` —— 第 K 个（1-based）直接文本子节点，折叠后偏移 N。
  3. `/tag[idx].N` —— 元素起始处的**累计**偏移 N（无显式 `text()` 步）。

## 3. CREngine 空白折叠规则

CFI 基于原始 DOM 偏移，XPointer 基于 CREngine 折叠后偏移，转换时必须精确还原折叠语义：

- 文本节点内**连续空白**（空格/制表/换行/回车/换页）折叠为**单个空格**。
- **块级元素开头**的纯空白文本节点被**丢弃**（除非在 `pre`/`code` 等保留空白的标签内）。
- 偏移在折叠后的文本上计数。

折叠偏移是**有损**的：连续空白 run 内的多个 raw 位置映射到同一 collapsed 位置；
反向映射落到该 run 的起点。这是 CREngine 的固有语义，不要求 raw offset 精确往返，
只要求折叠偏移语义一致。

保留空白的标签（CREngine 内置 `white-space: pre`）：
`pre code listing plaintext xmp textarea`。

块级容器（丢弃块首空白）：
`body div p h1-h6 ul ol li dl dt dd blockquote pre section article aside header footer nav main figure figcaption table thead tbody tfoot tr td th caption address details summary form fieldset center`。

## 4. 实现结构（独立实现）

代码位于 `core/src/main/java/com/yuezhou/reader/core/position/`：

| 文件 | 职责 |
| --- | --- |
| `PositionModel.kt` | XPointer 数据模型（spine 序号、元素步、文本定位） |
| `XPointerCodec.kt` | XPointer 字符串解析与构建、规范化 |
| `CrengineWhitespace.kt` | CREngine 空白折叠规则（偏移映射） |
| `DomNode.kt` | DOM 导航抽象（解耦具体 DOM 实现） |
| `PositionCodec.kt` | XPointer ↔ DOM 锚点双向转换核心 |
| `TestDom.kt` | 轻量树模型（单元测试与桥接参考） |

**许可证说明**：本实现为独立编写，仅依据 CREngine XPointer 的公开格式与
CREngine 引擎的已知空白处理行为，**不复制** Readest（AGPL-3.0）或 foliate-js 的任何源码，
因此不引入 AGPL 传染。真正的 EPUB CFI 字符串编解码由 foliate-js `epubcfi.js`
在 WebView 内提供（foliate-js 的许可证需单独记录，见方案 §17）。

## 5. 未实现（首版范围外）

- **FB2 / FBZ / MOBI 特殊处理**：CREngine 原生读取这些格式时 XPointer 起点不同
  （`/FictionBook/...` 或 `/html/body/...`）。首版只支持可重排 EPUB 2/3，暂不实现。
- **真实 CREngine oracle 联调**：单元测试只验证字符串与树模型的往返自洽；
  与真实 KOReader 设备的双向校验属于 P1 验收（方案 §14.1），待真机阶段执行。

## 6. 参考来源（仅作协议理解，非代码来源）

- CREngine XPointer 格式：KOReader CREngine 引擎的锚点语义（见 BaiduPanSync 插件 `progress_record.lua` 的 XPointer 字段）。
- CFI spine 与 DocFragment 序号换算：CREngine `epubfmt.cpp` 按 spine 顺序逐个生成 DocFragment，1:1 对应，序号不漂移。
