# 静读 Android：Kokoro 轻量情绪朗读技术方案

> 版本：1.0 · 2026-11-23  
> 目标：保留现有 Sherpa-ONNX + `kokoro-multi-lang-v1_1` 离线引擎，在不引入额外大型模型、不改变 EPUB 正文的前提下，利用轻量文本分析与保守的语音参数调节，改善小说对白、叙述和段落停顿的听感。

## 0. 核心结论与边界

- **本方案不是“让 Kokoro 原生支持情绪标签”**。Kokoro v1.1 的常见 Sherpa-ONNX 接口没有通用的 `emotion=angry/sad` 控制参数。情绪识别只产出建议的朗读风格，最终通过**保留标点、分句、有限的语速变化、段落间停顿**等现有可实现手段表达。
- **优先修复原有断句不自然问题**：如果底层 TTS 文本前端或句子切分不正确，情绪分析会放大问题。必须先做基线测量。
- **不强行改音高**：播放端实时改变 pitch 可能破坏声线和中文声调；默认关闭。也不为每个角色频繁切换 SID。
- **规则优先、离线优先、可回退**：不引入 LLM、在线 API、情感分类大模型；不能可靠判断时输出 neutral。
- **开发前检查实际代码**：本文中的接口、目录和阈值为建议设计，不代表项目已经具备这些实现。Codex 应先定位现有 Android 模块、阅读位置映射、TTS 队列和设置存储，再落地。

## 1. 背景、现状和验收目标

现状：静读 Android 已集成 Sherpa-ONNX + `kokoro-multi-lang-v1_1`；音频播放连续，但小说朗读断句、语调不自然。设备负载未测试。

### 1.1 MVP 目标

1. 自动识别**叙述、对白、疑问、感叹、低声、急促、悲伤线索**等文本语境。
2. 为每个朗读片段输出 `styleHint`、`confidence`、`evidence`、`deliveryPlan`，并保留原文位置。
3. 默认只启用 **标点保留 + 合理分句 + 保守的停顿/语速策略**；低置信度不调整。
4. 支持总开关、强度选择（关闭/轻微/标准）、对照试听、诊断日志与回退。
5. 不影响阅读高亮、定位、暂停/恢复、跳转、后台播放及已有 TTS 设置。

### 1.2 非目标

- 不训练/微调 Kokoro；不承诺悲伤、愤怒等情绪可被真实合成。
- 不做人物身份长期追踪、声纹克隆、角色自动配音。
- 不做云端情感分析、在线翻译、整书预处理或新格式解析。
- 不修改原始 EPUB 内容或阅读进度持久化格式。

### 1.3 成功标准（建议验收门槛，非现有实测数据）

- 100 条中文小说样本：标点/引号保留正确率 ≥ 99%；正文原文到朗读片段的偏移映射无丢字、重字。
- 规则判断在人工标注集上：高置信度风格误判率 ≤ 10%；达不到则提高阈值或减少类别。
- 盲听对照：至少 10 位听者或在资源不足时先 3–5 位内部评测，整体自然度不低于基线；对白停顿自然度优于基线。记录每条样本结果，不仅汇总平均。
- 与基线相比，TTS 生成失败率、播放 underrun 不上升；音频总时长增长 ≤ 10%（可配置）。
- 关闭情绪功能后，在相同模型与配置下恢复旧路径，不引入额外断句行为。

## 2. 推荐参考资料及可借鉴边界

| 资源 | 地址 | 可参考内容 | 不应误解为 |
|---|---|---|---|
| Sherpa-ONNX Kokoro v1.1 官方样例 | https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/kokoro-multi-lang-v1_1.html | Kotlin/Java 模型配置、`GenerationConfig`、`sid`、`speed`、`silenceScale`、生成回调 | Kokoro 支持任意情绪标签 |
| Sherpa-ONNX C API 配置 | https://k2-fsa.github.io/sherpa/onnx/c-api/html/structSherpaOnnxOfflineTtsConfig.html | `max_num_sentences`、`rule_fsts`、`silence_scale` 的含义 | 所有参数都能逐句动态更改 |
| Sherpa-ONNX 源码 | https://github.com/k2-fsa/sherpa-onnx | 模型接口、中文前端、内部句子切分、Android JNI | 直接复制最新主分支接口即可适配项目版本 |
| WeTextProcessing | https://github.com/wenet-e2e/WeTextProcessing | 中文数字、日期、符号文本规范化 | 必须引入整套依赖 |
| ESPnet | https://github.com/espnet/espnet | TTS 文本前端、韵律研究与实验方法 | 可以直接移植大模型到手机 |
| GPT-SoVITS | https://github.com/RVC-Boss/GPT-SoVITS | 中文文本切分、文本前端、语音风格研究 | 其情绪机制可直接移植到 Kokoro |
| Azure Speech 情绪风格 | https://learn.microsoft.com/en-us/azure/ai-services/speech-service/high-definition-voices | 风格标签体系、语义与情绪分离的产品设计 | Azure 标签可直接作为 Kokoro 参数 |
| Android AudioTrack | https://developer.android.com/reference/android/media/AudioTrack | 播放参数与缓冲安全、采样率及流式播放 | 修改播放速度没有音质副作用 |

**参考资料使用方式**：先阅读官方配置与现有源码，再选择性借鉴规则与数据结构；对外部项目检查许可证，不直接拷贝未经审查的代码、词典和音频素材。

## 3. 端到端架构

```text
EPUB DOM / 阅读位置
    │  原文 + chapterId + nodeId + startOffset/endOffset
    ▼
ExistingTextExtractor（复用现有）
    ▼
TextNormalizer（已有规则优先，避免重复规范化）
    ▼
SentenceSegmenter（保留引号/标点、长度限制兜底）
    ▼
LightEmotionAnalyzer（新增：规则、上下文、置信度）
    ▼
DeliveryPlanner（新增：neutral fallback、参数限幅、停顿预算）
    ▼
Existing KokoroAdapter / Sherpa-ONNX generate
    ▼
Existing audio queue / AudioTrack
    ▼
Existing position mapping / highlight
```

**职责分离**：Analyzer 不调用 TTS、不改 EPUB、不播放音频；Planner 不负责情绪分类；KokoroAdapter 不理解小说角色。已有阅读位置映射必须作为输入/输出字段传递。

### 3.1 建议的数据结构（Kotlin 伪代码）

```kotlin
enum class EmotionHint { NEUTRAL, CALM, HAPPY, SAD, ANGRY, FEARFUL, SURPRISED }
enum class DeliveryHint { NARRATION, DIALOGUE, QUESTION, EXCLAMATION, WHISPER, URGENT }

data class SourceSpan(
    val chapterId: String,
    val nodeId: String,
    val startOffset: Int,
    val endOffset: Int
)

data class SpeechSegment(
    val id: String,
    val originalText: String,
    val normalizedText: String,
    val sourceSpans: List<SourceSpan>,
    val paragraphBoundaryAfter: Boolean,
    val isDialogue: Boolean
)

data class EmotionAnalysis(
    val emotion: EmotionHint,
    val delivery: DeliveryHint,
    val confidence: Float, // 0..1
    val evidence: List<String>, // 仅调试时记录脱敏摘要
    val ruleVersion: String
)

data class DeliveryPlan(
    val speedMultiplier: Float, // 相对于用户设置的倍速
    val boundaryPauseMs: Int,   // 由播放器控制，非模型内置情绪
    val useOriginalPunctuation: Boolean = true,
    val fallbackNeutral: Boolean = false
)
```

生产代码应沿用项目命名规范，确保 `SourceSpan` 能准确映射回原有 EPUB 节点，不能把标准化后的字符索引直接用于高亮。

## 4. 文本处理与智能分句

### 4.1 输入清洗原则

- 保留 `。！？；：，、……“”‘’` 及段落边界；清理 HTML 标签、零宽字符、异常连续空白。
- 原始文本不变；另存 `normalizedText`。数字、日期和英文混排优先复用 Sherpa-ONNX 已启用的 FST/lexicon，避免应用层二次转换。
- 对多音字不做未经验证的手写拼音替换；若要处理，另建可回滚词典。
- 对括号、章节标题、脚注、诗歌、列表、单字对白设置独立测试用例。

### 4.2 分句策略

1. 以段落为基本范围；优先在 `。！？` 后切分，连同闭合引号归入前句。
2. 只有超过引擎允许的长度时才考虑 `；：`，再考虑 `，`；不按逗号一律切分。
3. 不能在数字小数点、英文缩写、书名号、引号嵌套中间误切。
4. 尽量让“说话提示语 + 对白”共享分析上下文，但生成片段是否合并由长度与听感实验决定。
5. 建议初始片段目标 40–100 个中文字符，**不是模型硬限制**；需按实际 tokens、内存和音质测试调整。
6. 分句输出记录 `splitReason`，且保证拼接文本与原文等价（允许规范化空白但不能丢标点）。

### 4.3 分句示例

```text
原文：韩立皱眉，低声说道：“别出声！他们就在门外。”她点了点头。
推荐分析单元：
  [韩立皱眉，低声说道：“别出声！他们就在门外。”]
  [她点了点头。]
不推荐：
  [韩立皱眉，] [低声说道：] [别出声！] [他们就在门外。]
```

## 5. 轻量情绪分析算法

### 5.1 类别与设计取舍

- **情绪**：`NEUTRAL / CALM / HAPPY / SAD / ANGRY / FEARFUL / SURPRISED`。
- **表达方式**：`NARRATION / DIALOGUE / QUESTION / EXCLAMATION / WHISPER / URGENT`。
- 情绪与表达方式分开：`“你来了？”她冷冷地问` 可是 QUESTION + CALM，而非自动 HAPPY。
- MVP 只让 `QUESTION/EXCLAMATION/WHISPER/URGENT` 影响有限参数；其他情绪先用于诊断与未来扩展，防止“检测出悲伤就把语速降很多”。

### 5.2 规则优先级

1. 明确说话方式：`低声/轻声/耳语/喃喃/吼道/怒喝/尖叫/急促地说`。
2. 近邻叙述：`她哭着说/他愤怒地说/他惊恐地望着`，只在相邻对白上下文中生效。
3. 直接对白标点：`？` => QUESTION，`！` => EXCLAMATION；**标点不能单独确定情绪**。
4. 局部词汇：`太好了/可恶/救命/对不起` 等，须结合否定、反讽和引用上下文。
5. 不确定或冲突时：`NEUTRAL`。

### 5.3 规则表样例

| 模式 | 输出建议 | 置信度初值（待校准） | 风险 |
|---|---|---:|---|
| `低声(说|道|问)` 紧邻对白 | WHISPER + CALM | 0.90 | “低声”不等于真正气声 |
| `(怒吼|怒喝|咆哮)` 紧邻对白 | ANGRY + EXCLAMATION | 0.90 | 不能用音量突增模拟怒吼 |
| `哭着(说|问)` 紧邻对白 | SAD + DIALOGUE | 0.85 | 情绪可能为愤怒或喜极而泣 |
| `救命！` | FEARFUL + URGENT | 0.80 | 引用或玩笑语境 |
| 只有 `！` | NEUTRAL + EXCLAMATION | 0.55 | 感叹不必然愤怒 |
| 只有 `？` | NEUTRAL + QUESTION | 0.65 | 反问与疑问差异 |

置信度是**启发式分数，不是统计概率**；必须用人工标注集校准。推荐启用阈值 `>=0.80`，低于阈值保持 neutral，仅做记录。

### 5.4 否定、反讽和归属范围

- `并没有生气`、`假装愤怒`、`虽然在笑，却很悲伤` 不能仅靠情绪关键词判定。
- 只从当前句及紧邻前后各一句获取上下文；避免将一段叙述的情绪错误传给整章。
- 说话方式优先归属于最近且语法上合理的引号对白；无法判断归属则不自动调节。
- 对古文、诗歌、戏剧对白和大量嵌套引号采用 neutral 兜底。

### 5.5 伪代码

```kotlin
fun analyze(segment: SpeechSegment, context: ContextWindow): EmotionAnalysis {
    val matches = mutableListOf<RuleMatch>()
    matches += explicitDeliveryRules(segment, context)
    matches += nearbyNarrationRules(segment, context)
    matches += punctuationRules(segment)
    matches += lexicalCueRules(segment, context)

    val filtered = suppressNegatedOrQuotedFalsePositives(matches, context)
    val best = resolveByPriorityAndScope(filtered)
    return if (best == null || best.confidence < 0.80f) {
        EmotionAnalysis(EmotionHint.NEUTRAL, DeliveryHint.NARRATION,
            0f, emptyList(), RULE_VERSION)
    } else best.toAnalysis(RULE_VERSION)
}
```

实现时优先使用编译后的正则、有限状态扫描和固定大小上下文窗口；不做全章 O(n²) 重复扫描。`RULE_VERSION` 必须进入诊断日志与缓存键。

## 6. 朗读表达规划（Kokoro 可落地的部分）

### 6.1 参数映射：先保守后实验

所有值均为**建议初始实验值，不是官方最优参数**。假设用户当前设置速度为 `userSpeed`：

| 表达提示 | `speedMultiplier` | 额外边界停顿 | 说明 |
|---|---:|---:|---|
| NARRATION / NEUTRAL | 1.00 | 0 ms | 基线 |
| QUESTION | 0.98–1.00 | 0–80 ms | 主要靠原始问号和完整句子 |
| EXCLAMATION | 1.00–1.03 | 0–60 ms | 不放大音量 |
| WHISPER | 0.95–0.98 | 40–100 ms | 只能模拟慢一点，不是真耳语 |
| URGENT | 1.02–1.05 | 0–30 ms | 不强制变调 |
| SAD / ANGRY / HAPPY | 默认 1.00 | 默认 0 ms | 仅打标签，先不做情绪伪装 |

**限幅规则**：单片段相对用户速度变化默认不超过 ±5%；绝对速度仍需符合现有引擎合法范围。用户设置优先于自动策略。不要同时在生成端和 AudioTrack 端重复变速。

### 6.2 停顿控制

- 优先保留模型自然停顿；只对片段间或段落间停顿进行有限补偿。
- `silenceScale` 作用于引擎内部句间静音，具体行为依赖项目绑定版本；不能把它当作“情绪强度”。
- 额外停顿由播放器统一调度，避免“模型静音 + 手工静音”双重叠加。
- 建议总停顿预算：一般句界额外 0–80ms；段落边界额外 80–180ms；仅作为可调实验范围，先实测原始 WAV 的首尾静音。
- 长时间连续阅读时不得通过阻塞主线程 `sleep()` 插入静音，应采用队列中的静音 PCM 或定时调度。

### 6.3 平滑策略

- 相邻片段不同风格时，不应突然频繁改变速度；仅在完整句子边界切换。
- 片段过短（例如少于 6 字）默认不单独调整速度。
- 同一段落内限制速度档位变化次数（建议不超过 2 次），必要时合并为 neutral。
- SID 保持用户当前选定音色，不因情绪改变 SID；音量与 pitch 默认不变。

## 7. Android 工程实现建议

建议新增模块（实际路径按仓库调整）：

```text
reader/tts/emotion/
  EmotionModels.kt
  EmotionRuleSet.kt
  LightEmotionAnalyzer.kt
  ContextWindowBuilder.kt
  DeliveryPlanner.kt
  EmotionTtsSettings.kt
  EmotionDiagnostics.kt
  EmotionFeatureFlag.kt
  tests/
    SegmenterTest.kt
    EmotionAnalyzerTest.kt
    DeliveryPlannerTest.kt
    PositionMappingTest.kt
```

### 7.1 对现有调用链的接入

```kotlin
val segments = existingSegmenter.segment(sourceText)
val planned = segments.mapIndexed { index, segment ->
    val context = contextBuilder.around(segments, index, radius = 1)
    val analysis = if (settings.enabled) analyzer.analyze(segment, context) else null
    val plan = if (analysis != null) planner.plan(analysis, settings, userSpeed)
               else DeliveryPlan(1.0f, 0)
    PlannedSegment(segment, analysis, plan)
}
existingTtsQueue.enqueue(planned)
```

- **不能在渲染线程执行模型生成**；分析本身也建议在现有 TTS 后台调度器执行。
- 变更设置、切书、跳章节、拖动进度时，取消尚未播放的旧 generation queue；用 `sessionId` 避免旧音频串入新章节。
- 暂停/恢复保持当前音频帧与原始 `SourceSpan`；不要按重新分析后的字符串长度推算进度。
- 预生成缓存键建议包含：`bookId/chapterId/sourceSpan/textHash/modelVersion/sid/userSpeed/emotionSettings/ruleVersion`；不记录书籍正文到持久日志。
- 如用户已经有“静读测试版”包名/应用名隔离，保持现状；如没有，先核实 applicationId、数据目录、签名与迁移策略，禁止覆盖正式版。

### 7.2 设置 UI

```text
朗读设置 > Kokoro > 实验功能
[ ] 智能语气辅助（默认关闭）
    强度：轻微 / 标准（默认轻微）
    [试听原版] [试听优化版]
    [查看当前片段分析（仅调试构建）]
```

- 文案明确提示：**“基于文本规则调整语速与停顿，不保证生成真实情绪声线”**。
- 关闭开关即完整回退原始播放路径；不修改既有用户音色和语速。
- 仅开发构建显示规则命中、置信度、原始文本和导出 WAV；生产构建避免暴露阅读隐私。

## 8. 日志、指标和诊断

### 8.1 建议日志字段

```json
{
  "sessionId": "debug-session",
  "chunkId": "c012",
  "ruleVersion": "emotion-rules-v1",
  "textLength": 28,
  "splitReason": "sentence_end",
  "emotion": "ANGRY",
  "delivery": "EXCLAMATION",
  "confidence": 0.91,
  "ruleIds": ["explicit_angry_speech"],
  "speedMultiplier": 1.02,
  "extraPauseMs": 20,
  "analysisMs": 0.6,
  "generationMs": 620,
  "audioDurationMs": 3600,
  "rtf": 0.172,
  "playbackGapMs": 0,
  "fallbackReason": null
}
```

数值均为示例。默认仅记录长度、规则 ID 和指标；原文、完整输入和 WAV 只允许用户主动打开诊断后本地导出，提供一键删除，禁止自动上传。

### 8.2 重点指标

- `analysisMs`：单段分析耗时及 P50/P95。
- `ruleHitRate`、`neutralFallbackRate`：规则覆盖率与回退率。
- `styleSwitchesPerMinute`：风格切换是否过频。
- `generationMs`、`audioDurationMs`、`RTF`：推理成本。
- `playbackGapMs`、`underrunCount`：是否影响连续播放。
- `leadingSilenceMs`、`trailingSilenceMs`：判断模型自带停顿与手工停顿是否叠加。
- `positionMismatchCount`：高亮和原文位置是否错位。
- 设备温度、CPU/内存：可作为第二轮性能测试，不能凭空断言当前存在瓶颈。

## 9. 测试数据集与评估方法

### 9.1 最小样本矩阵（建议 100 条）

| 类别 | 数量 | 样例 |
|---|---:|---|
| 中性叙述 | 20 | 他推开门，走进了院子。 |
| 疑问/反问 | 15 | “你真的相信他？” |
| 感叹/惊讶 | 10 | “怎么会这样！” |
| 愤怒/急促 | 15 | “快走！”他怒喝道。 |
| 悲伤/低声 | 15 | 她哽咽着说：“我知道了。” |
| 否定/反讽 | 10 | 他并没有生气，只是笑了笑。 |
| 嵌套引号/长句 | 10 | “他说‘别怕’，你信吗？” |
| 数字/中英混排 | 5 | 2026年，他花了39.8元。 |

建议自行编写测试句或使用获授权文本，避免直接分发受版权保护的小说章节。

### 9.2 A/B 设计

- A：现有静读原始实现。
- B：只修复分句与标点（无情绪分析）。
- C：B + 规则分析，但只记录标签，不改变声音（排除分析模块副作用）。
- D：C + 轻微语速/停顿映射。
- E：C + 标准映射（可选）。

固定模型、音色 SID、用户语速、设备和文本；随机打乱样本播放顺序，避免先入为主。评分 1–5：自然度、停顿、角色对白适配度、可懂度、疲劳感；另记录明显“假情绪”和突兀变速次数。

### 9.3 必须覆盖的回归场景

- 章节切换、跳转目录、暂停恢复、后台锁屏、蓝牙耳机切换。
- 书签和高亮位置映射；长段落、空段落、特殊标点。
- 同一句重复朗读缓存、设置切换后旧缓存失效。
- 低端设备、30/60/120 分钟连续朗读、系统回收与重新打开。
- 模型加载失败、规则解析异常、音频队列取消：必须安全回退到原有 Kokoro 路径。

## 10. 分阶段开发计划（严格顺序）

### 阶段 A：审计与基线（先做，禁止进入 B）

1. 找出当前 EPUB 文本提取、标准化、分句、Sherpa-ONNX 初始化/生成、AudioTrack 播放、高亮映射、设置持久化的真实代码位置。
2. 确认 `kokoro-multi-lang-v1_1` 模型文件、`sid`、`speed`、`silenceScale`、`maxNumSentences` 及当前依赖版本。
3. 保存 20 条基线样本的最终 TTS 输入、WAV、停顿和播放指标（诊断模式）。
4. 输出问题清单、现有接口复用建议和风险清单；不改变正式朗读行为。
5. 运行项目已有测试、类型/静态检查与 Android debug 构建，报告环境限制。

**交付**：`docs/tts-emotion-phase-a-audit.md` + 基线结果 + 建议代码落点。等待用户确认。

### 阶段 B：文本与规则模块

1. 先补足分句/标点和 SourceSpan 映射测试；只修正已确认的问题。
2. 实现 `LightEmotionAnalyzer`、`ContextWindowBuilder`、规则优先级和 neutral 回退。
3. 规则表与实现分离，允许版本化；无额外模型依赖。
4. 使用 100 条标注集计算混淆矩阵、精确率、误判率；对误判高的类别禁用。

**交付**：可测试的纯 Kotlin 分析模块，不影响音频。

### 阶段 C：DeliveryPlanner 与功能开关

1. 实现保守的参数映射和总停顿预算；用户语速优先。
2. 集成到现有队列，加入 `sessionId` 防旧音频串流；保留关闭即回退。
3. 添加开发试听对照与日志；默认关闭。
4. 验证 A/B/C/D 试听和位置同步。

**交付**：可试用的“智能语气辅助”功能。

### 阶段 D：评估、优化和发布

1. 完成盲听评分与 30/60/120 分钟压力测试。
2. 调整规则阈值、参数幅度；删除无法证明有效的策略。
3. 检查 APK 体积、CPU/内存、耗电和隐私日志。
4. 测试版灰度；功能默认关闭，确认稳定后再讨论是否默认开启。

**交付**：评测报告、性能对照、回滚说明和最终代码变更清单。

## 11. 风险清单与回滚

| 风险 | 预防/回退 |
|---|---|
| 规则误判让声音更假 | 置信度阈值、neutral 兜底、总开关 |
| 高频变速造成机械感 | 速度 ±5% 限幅、句界切换、限制切换次数 |
| 额外停顿导致拖沓 | 先测原始 WAV 静音，设置总停顿预算 |
| EPUB 高亮错位 | 保存原始 SourceSpan，禁止按规范化字符串反推 |
| 内存/CPU 开销增加 | 小上下文、编译正则、线性扫描、性能指标 |
| 旧音频在跳章节后继续播放 | sessionId 与取消令牌 |
| 规则缓存导致旧音频复用 | 缓存键包含规则/设置版本 |
| 正式版被覆盖 | 保持测试版 applicationId 和数据目录隔离 |
| 阅读内容泄露 | 默认不记正文、不上传，诊断导出需主动授权 |

**回滚路径**：关闭功能标志 → 直接走原有 TTS 调用链；不删除用户书籍、设置、进度。若需卸载测试版，不影响正式版。

## 12. Codex 执行规范与首轮任务

### 强制约束

- 严格按 A→B→C→D 实施；**本次只做 A**，完成后停止等待确认。
- 先读代码再给出文件路径和调用链，不要臆造项目目录或类名。
- 不替换 Kokoro、不接入云端、不引入 LLM、不大规模重构播放器。
- 不改动 EPUB 原始内容和正式版数据，不改变现有用户设置。
- 诊断功能应为 debug-only 或显式开关；不得上传小说原文。
- 修改必须附带测试、构建结果；不能运行的项目要写明原因。

### 可直接复制给 Codex 的指令

```text
请完整阅读《静读_Kokoro轻量情绪朗读技术方案.md》，基于当前 Android 项目实施“阶段 A：审计与基线”。

背景：现有 Sherpa-ONNX + kokoro-multi-lang-v1_1 能连续朗读，但中文小说断句和语调不自然。目标是保留 Kokoro，未来增加轻量、完全离线的规则情绪分析。注意 Kokoro 不具有通用 emotion 参数，不能宣称实现真实情绪合成。

请先：
1. 找到真实的 EPUB 文本→清洗→分句→Kokoro generate→音频队列→高亮位置映射的调用链和文件路径。
2. 检查模型资源、中文 sid、speed、silenceScale、maxNumSentences、Sherpa-ONNX 版本及应用/引擎双重分句问题。
3. 在不改变朗读效果的前提下，增加可开关的诊断日志和本地 WAV 导出能力（仅 debug 模式），记录实际模型输入、生成耗时、RTF、静音与播放间隔。
4. 准备至少 20 条基线样本和可复现测试步骤。
5. 输出 docs/tts-emotion-phase-a-audit.md，列出发现、证据、风险、推荐代码落点、后续阶段预估。
6. 运行已有测试、静态检查和 debug 构建，报告结果。

完成阶段 A 后停止，不要进入阶段 B，不要自行引入模型或修改正式版包名、应用数据。
```

## 13. 关键技术判断

本项目的**第一收益点**很可能仍是“完整句上下文、标点保留、合理停顿”，而不是情绪词典。情绪识别模块可以提供可解释的标签和实验能力，但只有盲听证实改善后，才应该开启语速/停顿映射。若最终发现完整句生成仍显著机械，应把它记录为 Kokoro 模型能力边界，而不是继续无限增加规则。
