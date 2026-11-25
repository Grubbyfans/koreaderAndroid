# Z-Library 混合架构阶段 G 实施报告

- 分支：`feature-zlibrary`
- 实施范围：仅阶段 G（删除三阶段探针、清理调试入口与日志边界）。
- 基线：阶段 F 改动。
- 本轮结果：**352 项单元测试通过、0 失败；Lint 0 error / 56 warning（删除探针后减少 14 条）；测试版与正式版 Debug APK 均构建成功，正式包无 WebView/Z-Library 探针入口。**

---

## 1. 清理内容

| 项目 | 处理 |
| --- | --- |
| `app/src/ttsTestDebug/.../webprobe/ZLibraryApiProbeActivity.kt` | 删除 |
| `app/src/ttsTestDebug/.../webprobe/WebViewStageTwoActivity.kt` | 删除 |
| `app/src/ttsTestDebug/.../webprobe/WebViewProbeActivity.kt` | 删除 |
| `app/src/ttsTestDebug/AndroidManifest.xml` | 移除上述三个 `exported=true` 探针声明；保留与 Z-Library 无关的既有试音/服务验收入口 |
| 生产源集 | 从未声明探针，正式包仅保留 Launcher 与外部 EPUB 打开两个既有导出入口 |

验证：

- `grep webprobe` 仅剩 `AppProcessTest` 中用于进程角色判定的字符串，不再有可执行组件。
- 合并后的 `ttsTestDebug` 清单中 `webprobe` 出现次数为 0。

---

## 2. 日志与调试边界

| 日志 | 内容 | 处理 |
| --- | --- | --- |
| `ZLibraryWebMem` | 仅 PSS/availMem/lowMemory | 仅 `FLAG_DEBUGGABLE` 输出 |
| `ZLibraryWebDL` | downloadId、异常类名与系统错误信息 | 不含 URL、Cookie、账号或签名参数，保留用于排障 |
| 网页/下载流程 | 不打印页面 URL、Cookie、下载链接或账号字段 | 沿用阶段 C/D 的脱敏策略 |

正式包不输出内存观测日志；无调试导出入口。

---

## 3. 构建与验证

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew :app:assembleProductionDebug
```

- 单元测试：**352 项、0 失败**（core 178、reader 66、app 54、speech 32、novel-text 22）。
- Lint：**0 error / 56 warning**，较阶段 F 前减少 14 条，均来自被删除的探针文件。
- `ttsTestDebug` 与 `productionDebug` APK 均构建成功。

真机回归（测试包）：网页入口、登录态、原生搜索拒绝提示、下载历史入口均正常；阶段 F 的多轮进出与内存观测结论继续有效。

---

## 4. 交付物

- 验收 APK：`dist/yuezhou-reader-zlibrary-acceptance-ttsTest-debug.apk`（`com.yuezhou.reader.tts.test`，可与正式版并存）
- 正式版 Debug APK：`app/build/outputs/apk/production/debug/app-production-debug.apk`（`com.yuezhou.reader`）
- 全流程报告：阶段 D/E/F/G 四份实施报告位于 `docs/04-在线书库统一化/`

验收建议：

1. 安装测试包，确认原生搜索被拒时只提示网页搜索，不出现热门伪结果。
2. 进入网页搜索，登录后从「下载历史」重下当天书籍，确认进入统一下载任务并显示已导入。
3. 反复进出网页容器、切后台再回来，确认页面与登录态保持且无明显卡顿。
4. 在正式包中确认不存在 WebView/Z-Library 探针入口。
