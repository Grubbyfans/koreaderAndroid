# 内置 Kokoro TTS 改造验收报告

- 适用方案：`docs/内置Sherpa-ONNX_Kokoro_TTS技术改造方案_v3.3.md`
- 主测机型：三星 SM-S9360（Galaxy S25+，Android 16，12 GB）
- 测试包：`com.yuezhou.reader.tts.test`（静读测试版）；正式版 `com.yuezhou.reader` 未被覆盖
- 报告更新：2026-11-23（阶段 A/B/C/D 已验收；阶段 E 起为待验项）

## 1. 阶段 A：依赖与事实核验

| 项目 | 结果 |
|---|---|
| 运行时版本 | sherpa-onnx **1.13.4**，AAR 以文件形式入库 `speech/libs/sherpa-onnx-1.13.4.aar`（48.8 MB） |
| ABI | AAR 含 `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`；Release 计划仅 `arm64-v8a` |
| 依赖隔离 | 仅 `ttsTestImplementation` 引用；正式版不打包 AAR 与试音入口 |
| 安装隔离 | `production` 与 `ttsTest` 并存，`applicationId` 分别为 `com.yuezhou.reader` / `com.yuezhou.reader.tts.test` |
| 模型分发 | int8 / fp32 模型部署在测试版内部 `files/tts-models/`，不进入 APK |

结论：通过。未做 16 KB page size 与正式版 ABI 裁剪的最终校验，留到发布前（方案 §17.3）。

## 2. 阶段 B：最小试音

| 指标 | int8 v1_1 | fp32 |
|---|---|---|
| 固定中文句 sid=3 | 成功 | 成功 |
| 内核初始化 | 896 ms | 1317 ms |
| RTF（0.8×–1.5×） | 0.69–0.77 | 0.50–0.55 |
| 冷启动 PSS | 481 MB | 654 MB |
| 4.8 kHz 窄带比 | 196× | 3.7× |
| 9.6 kHz 窄带比 | 1995× | 79× |
| 主观听感 | 稳定窄带噪声 | 清晰 |

结论：通过（飞行模式中文试音成功，健康检查通过）。int8 窄带噪声作为已知风险进入风险表。

## 3. 阶段 C：基础设施与协调器

### 3.1 交付物

- `SpeechPlaybackBackend` 端口与统一事件（Started / Completed / Buffering / Failed）。
- 状态机双进度：`currentSegmentIndex`（正在读）与 `lastCompletedSegmentIndex`（已读完），只有实际播放完成才推进。
- `SpeechCoordinator`：有界预合成（当前 + 2）、PCM 上限 24 MiB、代次过滤、切速重合成当前未完成句、健康检查失败重试、`release()` 前取消并 join 流水线。
- `AudioTrackPcmPlayer`：单 AudioTrack + 累计帧边界表，marker 通知 + 100 ms 播放头轮询兜底，处理 32 位回绕。
- `AudioHealthCheck`：NaN/Inf、持续 ≥50 ms 削波、空音频为硬失败；4.8k/9.6k 窄带能量比 ≥100× 且持续 ≥50 ms 为**预警**，不判失败。
- `:speech` 补齐 JUnit5 与 `kotlinx-coroutines-test`。

### 3.2 单元测试

| 模块 | 结果 |
|---|---|
| `:core:testDebugUnitTest` | 29 项通过 |
| `:speech:testDebugUnitTest` | 15 项通过 |

覆盖：130 句连续且不提前推进、旧代次/旧计划回调丢弃、暂停/恢复幂等、停止与释放、预取边界、PCM 上限、切速重合成、跨章回调只触发一次、健康检查重试/持续失败、合成长异常、从中间句启动、空计划。

### 3.3 真机连续朗读（adb 自动跑）

| 运行 | 句数 | 完成 | 首声 | 总耗时 | PSS | 健康检查 |
|---|---|---|---|---|---|---|
| fp32 冷进程 | 12 | 12/12 按序 | 1450 ms | 36.8 s | 693 → 702 MB | `ok=true`，无窄带预警 |
| int8（fp32 之后同进程） | 12 | 12/12 按序 | 2129 ms | 37.8 s | ~1040 MB | `ok=true`，4800 Hz 194×/128 ms，9600 Hz 596×/469 ms |
| int8 冷进程 | 12 | 12/12 按序 | 2147 ms | ~38 s | 495 → 497 MB | `ok=true`，4800 Hz 402×/171 ms，9600 Hz 804×/341 ms |
| int8 同模型重载 | 12 | 12/12 按序 | — | — | 503 → 504 MB | 同上 |

关键日志（fp32）：

```text
C_SUMMARY count=12 totalMs=36761 firstStartMs=1450 completed=12
health=ok=true nan=0 inf=0 peak=0.371 rms=0.0794 clipMs=0
```

**验收结论：通过。** 12/12 句严格按序、每句恰好一次完成；无提前推进；全程仅启动时 1 次 Buffering；`OfflineTts.release()` 后同模型重载内存可复用。

## 4. 阶段 D：服务化、后台跨章与位置落库

### 4.1 交付物

- 进度写入端口 `SpeechProgressSink`（core）与 `SpeechProgressWriter`（speech）：800 ms 防抖、最后写入胜出、`flush()` 立即落库、`reset()` 丢弃旧会话、写失败可诊断；服务使用独立 IO 作用域并在 `onDestroy` 同步 flush。
- 应用级装配：`ReaderApplication` 注入 `RoomSpeechProgressSink` 与 `SpeechRuntime`；`ttsTest` 变体由 `ReaderTtsApplication` 注入 `SherpaKokoroProvider`，正式版不含 AAR 与试音入口。
- `SpeechService` 接入 Kokoro：`SpeechConfig.engine` 选择系统 TTS / Kokoro；Kokoro 走 `SpeechCoordinator`，本次新增 `playGeneration` 防旧初始化污染、章节完成回调驱动跨章、暂停/恢复/前后句/停止、失败与停滞统一停止。
- 唤醒锁：朗读期间持有 `PARTIAL_WAKE_LOCK("yuezhou:SpeechService")`，暂停/停止/错误/销毁释放；超时 2 h 防泄漏。
- `MediaSession.setActive(true)` 与 MediaStyle 前台通知；启动即拉前台，满足 5 s 限制。
- 阅读页新增引擎/模型/音色设置，移除 Activity 侧落库（服务成为唯一写入方）。

### 4.2 单元测试

| 模块 | 结果 |
|---|---|
| `:core:testDebugUnitTest` | 90 项通过 |
| `:speech:testDebugUnitTest` | 20 项通过（协调器 15 + 进度写入器 5） |

新增覆盖：防抖只写最后一次、`flush()` 不重复、`reset()` 丢弃旧代次、写失败回调后可恢复、空 sink 无副作用。

### 4.3 真机后台跨章（Activity 已销毁）

测试入口 `StageDActivity`（仅 `ttsTest`）以 3 段/章运行，Activity 1.5 s 后 `finishAndRemoveTask`，进程内仅剩前台服务；通过 logcat 观察章节推进与 `speech_position` 表落库。

| 场景 | 结果 | 证据 |
|---|---|---|
| Kokoro int8，亮屏后台跨章 | 通过 | 章 0 → 1 → 3（章 2 无有效句）连续完成；`D_CHAPTER_DONE ... completed=2` 正确（修复了事件收集滞后导致的 `completed=1`） |
| Kokoro int8，**锁屏**后台 | 通过 | 16:23 熄屏后章 7 → 8 → 9 → 10 连续完成；位置持续写库（`chapter=10 segment=0`） |
| 系统 TTS，锁屏后台 | 通过 | 熄屏期间章 8 → 9 → 10 → 11 → 12 连续完成；位置 `chapter=12 segment=2` |
| 暂停 / 恢复（媒体键） | 通过 | 两种引擎均输出 `D_WAKELOCK release` → 暂停、位置立即 flush；恢复后 `D_WAKELOCK acquire` 并继续 |
| 停止 | 通过 | 停止动作触发 `D_WAKELOCK release` 与最终位置落库（`chapter=14 segment=2`） |

关键日志（Kokoro 锁屏）：

```text
D_SEG_START index=1 chars=81
D_SEG_DONE index=1
D_SEG_DONE index=2
D_CHAPTER_DONE book=求魔 - 耳根 chapter=9 completed=2
D_CHAPTER_START book=求魔 - 耳根 chapter=10 from=0
```

### 4.4 本轮修复的问题

1. **Kokoro 流水线跑在主线程导致卡死**：`AudioTrack.write(WRITE_BLOCKING)` 遇到暂停/缓冲满会阻塞，主线程被占后媒体键、看门狗、通知全部失效。已改为专用 IO 作用域，跨章回调经 `withContext(Dispatchers.Main)` 回主线程串行处理。
2. **停滞看门狗误杀长句**：原先以「60 s 无事件」判断，单句音频可超过 60 s。现以播放头 `positionMs()` 推进作为进展信号，阈值 120 s，暂停恢复时重置。
3. **章节完成序号竞态**：服务事件收集器可能晚于协调器回调，改为读取协调器权威 `lastCompletedSegmentIndex` 后再 flush。
4. **锁屏被厂商冻结**：新装应用未授予 `POST_NOTIFICATIONS` 时，FGS 通知不可见，三星 Freecess 将进程判为 cached 并冻结（`FZ ... cached`，同时禁用唤醒锁）。授予权限并 `setActive(true)` 后，锁屏连续多章不再被冻结。
5. **测试入口**：新增 `StageDActivity`、`KokoroModels`、`SherpaKokoroProvider`，试音入口复用同一模型工厂。

**验收结论：通过（阶段 D 硬门槛）。** Activity 销毁 / 锁屏下跨 3 章以上持续朗读，位置持续落库；跨章无高亮按约定接受。耳机/蓝牙路由、来电打断、杀进程后回界面续读尚未真机验证（见风险与待验项）。

## 5. 已知风险（截至阶段 D）

| # | 风险 | 等级 | 实测/证据 | 缓解 | 待验证阶段 |
|---|---|---|---|---|---|
| R1 | **int8 v1.1 稳定窄带噪声**：4.8 kHz / 9.6 kHz 持续能量，健康检查可检出但按设计只预警不中断 | 高 | 阶段 B/C 真机复现：4800 Hz 194–402×、9600 Hz 596–804×，持续 128–469 ms | 默认 int8 + 健康预警 + 对话诊断；主观不可接受时切 fp32 | D/E 服务场景复测、发布前选型 |
| R2 | **跨模型切换内存叠加**：同进程 fp32→int8 时 PSS 峰值约 1.04 GB（冷启动仅 495 MB，同模型重载 503 MB），`release()` 后 native 内存不立即归还 | 高 | 阶段 C 真机 PSS 序列 | 切换前停止推理并 release；必要时延迟回收或进程隔离；切换入口先做内存检查 | E 模型切换、低内存场景 |
| R3 | **int8 首声超过 2 s 目标**：实测 2129–2147 ms，fp32 1450 ms；长句（256 字）服务场景下首次出声更晚 | 中 | 阶段 C/D 真机 | fp32 回退；预生成加深；首句二次切块 | E 长时与冷启动统计 |
| R4 | **音频路由变化未测**：暂停/恢复、锁屏已在阶段 D 真机通过；耳机插入/拔出、蓝牙切换、来电打断尚未真机验证 | 中 | 暂停/恢复已验证；路由未测 | 阶段 E 结合 `AudioManager` 回调与音频焦点事件补测 | E |
| R5 | **native 释放只有同模型重载证据**：未验证「推理进行中快速停止/切模型」的安全性，协调器只保证 cancel+join，无超时隔离 | 中 | 阶段 C 同模型重载成功 | 释放前停止新任务并等待流水线结束；超时只报告不硬 free | E |
| R6 | **跨章无高亮**：已拍板接受，只要求继续读、不丢句、位置可恢复 | 低 | 阶段 D 真机跨章通过 | 当前章句级高亮；后台使用原生锚点 | E 回界面高亮 |
| R7 | **健康检查为启发式**：窄带预警阈值 100× 基于单机型样本，可能误报/漏报；硬失败仅覆盖 NaN/Inf/削波/空音频 | 中 | 单测 + 真机样本 | 预警不中断；诊断保留最近报告 | E 诊断页 |
| R8 | **模型未做正式导入器**：当前依赖开发期内部目录部署，无校验/哈希/删除保护 | 中 | 阶段 B 方案约定 | 正式 SAF 导入器与哈希校验推迟到 E | E |
| R9 | **WebView 与 TTS 同进程内存占用**：真机 fps 阅读 + int8 合成共存下的峰值未测 | 中 | 未测 | 缓存上限、OOM 释放模型并回退系统 TTS | F 长时回归 |
| R10 | **厂商冻结后台进程**：三星 Freecess 会在锁屏后把进程判为 cached 并冻结，禁用应用唤醒锁；若 FGS 通知不可见（未授予 `POST_NOTIFICATIONS`）尤其明显 | 高 | 16:18 复现 `FZ ... cached`，播放停滞；授予通知权限并 `setActive(true)` 后锁屏跨多章通过 | 运行时申请通知权限；媒体会话活跃；部分机型仍需用户将电池设为「不受限制」，需产品层提示 | E 发布前机型矩阵 |
| R11 | **唤醒锁可能被厂商禁用**：`dumpsys power` 显示 `yuezhou:SpeechService` 被标记 `DISABLED`，实际依赖系统 `AudioMix` 锁维持播放 | 中 | 阶段 D 多轮真机 | 不把自有唤醒锁作为唯一保障；无音频输出时避免长空档；持续推进合成 | E 低电量场景 |
| R12 | **Kokoro 朗读中实时调速未接线**：`SpeechCoordinator.setRate` 已实现，但服务未暴露动作；当前仅引擎/音色/模型变更会重启朗读 | 低 | 代码检查 | 阶段 E 在设置面板接入后补单测与真机 | E |
| R13 | **章间合成空档**：长句（≤256 字）int8 合成可达数十秒，期间无音频输出；若此时进程被冻结会表现为停滞 | 中 | 阶段 D 日志（81 字句约 18 s；更长句更久） | 看门狗按播放头判断；锁屏冻结风险见 R10；后续可加静音填充或提高预取 | E |

## 6. 阶段 C 验收清单

- [x] 100+ 句连续朗读无错序、无提前进度（单测 130 句）
- [x] 每句 Completed 恰好一次
- [x] 暂停 / 跳句 / 停止无错序（单测）
- [x] 旧代次、旧计划回调被丢弃
- [x] PCM 上限与预取上限生效
- [x] 健康检查拦截 NaN/Inf/削波/空音频
- [x] int8/fp32 真机连续 12 句通过
- [x] `release()` 后同模型可重载
- [x] 锁屏真机验证（阶段 D：Kokoro 与系统 TTS 均跨多章）
- [ ] 耳机 / 蓝牙 / 来电路由变化真机验证（阶段 E）
- [ ] 跨模型切换内存峰值治理（阶段 E）

## 7. 阶段 D 验收清单

- [x] Activity 销毁 / 锁屏跨 ≥3 章持续朗读，位置持续落库（Kokoro 7→10，系统 TTS 8→12）
- [x] 服务独立于 Activity 获取进度写入端口；写失败可诊断（单测 + 日志）
- [x] 唤醒锁在朗读期间持有，暂停 / 停止 / 错误立即释放（厂商禁用另有 R11）
- [x] 系统 TTS 与 Kokoro 配置切换，旧会话回调不污染新会话（`playGeneration` / `sessionId`）
- [x] 锁屏下暂停 / 恢复（媒体键）两引擎均通过，位置即时 flush
- [ ] 锁屏通知、耳机控制、来电、拔耳机（阶段 E）
- [ ] 返回阅读界面当前章句级高亮（阶段 E）
- [ ] 杀进程后从当前未完成句恢复且不自动出声（阶段 E）

## 8. 阶段 E 起待验项

- 耳机插入/拔出、蓝牙切换、来电打断与音频焦点恢复。
- 杀进程后重开应用，从 `speech_position` 恢复且不自动播放。
- 阅读界面返回后的句级高亮对齐（后台跨章期间无高亮）。
- 朗读中实时调速接线（`SpeechCoordinator.setRate`）。
- 长时锁屏（>30 min）与低电量场景下的厂商冻结策略（R10/R11）。
- 正式版通知权限引导与「不受限制」电池设置提示。
