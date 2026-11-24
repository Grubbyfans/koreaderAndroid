# Kokoro 解析开关与回退实现

更新：2026-11-23。基础实现提交：`a3902fd`。

## 用户行为

入口：设置 → Kokoro 朗读模型 → **小说文字解析（实验）**。

- 默认关闭，偏好保存在 DataStore 的 `novel_text_parsing` 键。
- 只对 Kokoro 生效；系统 TTS 即使此偏好开启，也使用旧分句策略。
- 关闭时直接调用当前 `SentencePlanner`，不调用新解析器；不会额外调整语速或停顿。
- 播放中切换会作废旧预生成音频，从当前未完成句的原文锚点重建，允许重读该句。
- 暂停状态切换后保持暂停。
- 开关不修改用户选定的模型、SID、基础语速或原 EPUB。

这里“保持现状”指同一构建版本中 legacy 策略的行为一致；后续独立的引擎缺陷修复可以同时作用于两条路径，不代表永久冻结历史缺陷。

## 调用链与代码位置

```text
ReaderSettings / 设置 Switch
  → SpeechConfig.novelParsing
  → SpeechTextPlanning.plan(units, engine, enabled)
     关闭 / 系统 TTS → SentencePlanner
     开启 + Kokoro → NovelTextParser → SpeechSegment
  → SpeechService / SpeechCoordinator
  → KokoroTextNormalizer → 模型合成 → 实际播放事件
```

关键文件：

- `app/.../ReaderSettings.kt`：偏好、默认值及单键写入。
- `app/.../MainActivity.kt`：设置入口及切换动作。
- `app/.../ReaderActivity.kt`：当前章规划；在 Default 调度器解析，在 IO 查询文件代次。
- `core/.../SpeechTextPlanning.kt`：唯一策略入口、失败回退、锚点重建。
- `core/.../SpeechSegment.kt`：可选 spokenText/novel 元数据、SpeechPlan 的原始节点快照。
- `reader/.../EpubChapterTextSource.kt`：后台下一章沿用同一开关，重新写入正确的分段版本和原始节点。
- `speech/.../SpeechCommands.kt`、`SpeechPlanStore.kt`：切换动作与受保护的计划替换。
- `speech/.../SpeechService.kt`：暂停旧播放、重建、generation 过滤以及恢复暂停状态。

## 位置、队列和持久化

SpeechPlan 保存当前章节 `sourceUnits`，用于服务独立于 Activity 重建；不缓存整本书。重建通过 SourceAnchor 查找新片段，不沿用旧 segmentIndex。

新旧策略分别使用 plannerVersion 1001 和 2，复用已有 Room 位置字段，无新增数据库迁移。播放中先让旧 generation 失效，防止旧 PCM、Started 或 Completed 污染新计划。

`SpeechPlanStore.replacePlan()` 只替换仍匹配当前请求的计划，并保留章节来源对象，防止切书后的旧任务覆盖新书。

内存降级到系统 TTS 时，若有原始章节快照，会重建 legacy 分句并按原文锚点定位；系统引擎不会继续使用小说语气策略。

## 失败与回退

解析器抛出可恢复异常时，当前章改用旧分句器，记录异常类型，不记录正文。语气分析失败则使用中性参数，详见第三份文档。

没有 sourceUnits 的历史/测试计划无法在服务内安全重建，切换保留为后续会话配置；正常阅读和后台章节均会携带快照。

## 验证

- `core/.../SpeechTextPlanningTest.kt`：关闭时与旧结果逐字段相等，系统 TTS 强制旧策略、异常回退、新旧锚点重建、exclusive 句尾边界。
- `reader/.../EpubChapterTextSourceTest.kt`：后台跨章开关及版本继承。
- 原有暂停、恢复、跳句、代次过滤、内存回退和进度测试继续运行。

```sh
./gradlew :core:testDebugUnitTest :speech:testDebugUnitTest :reader:testDebugUnitTest
./gradlew :app:assembleTtsTestDebug :app:compileProductionDebugKotlin
```

实际安装只使用 `com.yuezhou.reader.tts.test`，不覆盖正式版。

## 后续迭代

重点补测快速连续切换、正在初始化时切换、暂停后切换、后台跨章时切换、进程重建与文件覆盖。任何新解析策略都要检查版本、原文映射及缓存失效，不能只改设置 UI。


### 后续兼容修正

数字朗读修复 `ae5af2d` 对 Kokoro 开关开启/关闭均生效：关闭仍走旧分句器，但保留数字之间的小数点以便原生中文数字规则获得完整输入；系统 TTS 不变。SpeechPlan 新增 engine 字段，后台续章沿用对应引擎的分句策略。
