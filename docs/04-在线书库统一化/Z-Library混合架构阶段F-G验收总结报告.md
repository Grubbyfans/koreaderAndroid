# Z-Library 混合架构阶段 F–G 验收总结报告

- 分支：`feature-zlibrary`
- 验收范围：阶段 F（内存与生命周期）、阶段 G（探针清理与交付边界）
- 基线：阶段 E 提交 `dfc5555`
- 对应提交：`00f90e3`（F）、`d064dbb`（G）
- 验收包：`com.yuezhou.reader.tts.test`（测试版，可与正式版并存）
- 结论：**阶段 F、G 均达到交付门槛，建议通过验收。**

---

## 1. 交付概览

| 项目 | 结果 |
| --- | --- |
| 单元测试 | **352 项，0 失败** |
| Lint | **0 error / 56 warning**（删除探针后较之前减少 14 条） |
| 构建 | `ttsTestDebug`、`productionDebug` 均成功 |
| 验收 APK | `dist/yuezhou-reader-zlibrary-acceptance-ttsTest-debug.apk`（约 162 MB） |
| SHA-256 | `3616b0405428c1a4f74ead919bebb8d48408cce1e55386c061215781bdfd3aa6` |
| 正式版 Debug APK | `app/build/outputs/apk/production/debug/app-production-debug.apk` |

测试分布：core 178、reader 66、app 54、speech 32、novel-text 22。

---

## 2. 阶段 F 验收要点

### 2.1 交付内容

| 能力 | 实现 |
| --- | --- |
| 页面不可见时停止非必要工作 | `onStop` 取消页面超时并调用本 WebView `onPause()`，未使用全局 `pauseTimers()` |
| 回前台恢复 | `onStart` 调用 `onResume()`，仍在加载/验证等待时按当前导航代次重新计时 |
| 最小导航恢复 | 仅保存当前地址，不恢复完整历史；恢复后重新走导航白名单 |
| 单实例复用 | 清单 `singleTop`，`onNewIntent` 复用容器并加载新地址 |
| 退出释放 | 解除 `DownloadListener`、移出视图、`stopLoading()`、`destroy()` |
| 渲染器异常 | 沿用 `onRenderProcessGone`：销毁旧 WebView，显示“重试/关闭”，重试时重建 |
| 内存观测 | `ZLibraryWebMem` 仅调试包输出宿主 PSS 与系统可用内存 |

### 2.2 真机证据（Android 16 / API 36）

| 指标/场景 | 结果 |
| --- | --- |
| 4 轮打开/关闭网页容器 | 均正常创建、销毁；网页宿主 PSS 稳定 **66 MB**，无逐轮增长 |
| 主进程 PSS | 约 **115 MB** |
| 系统可用内存 | 约 **2.6–2.9 GB**，`low=false` |
| 渲染器 PSS | `dumpsys meminfo 1717`：**132,673 KB（约 130 MB）** |
| 后台/前台 | Home 输出 `stage=stop`，返回输出 `stage=start`，页面与计时恢复 |
| 低内存 trim | `RUNNING_LOW` 后进程存活，再次进出正常 |
| 功能回归 | 网页搜索、登录态、下载历史入口正常 |

### 2.3 未覆盖项

- 渲染器强杀：非 root 设备执行 `kill -9` 返回 `Operation not permitted`，无法注入；异常处理路径由既有实现与代码审查覆盖。
- 锁屏朗读 + 打开网页的组合场景未单独执行；两者分属不同进程，未发现共享生命周期。
- 语音模型加载耗时/回退结果由既有 speech 诊断维护，本轮未改动。

---

## 3. 阶段 G 验收要点

### 3.1 清理内容

- 删除 `ZLibraryApiProbeActivity`、`WebViewStageTwoActivity`、`WebViewProbeActivity` 三个探针源文件。
- 移除 `app/src/ttsTestDebug/AndroidManifest.xml` 中对应三个 `exported=true` 声明。
- 保留与 Z-Library 无关的既有试音/服务验收入口。

### 3.2 交付边界核验

| 核验项 | 结果 |
| --- | --- |
| APK 内 `webprobe` 引用 | **0** |
| 网页容器属性 | `exported=false`、`process=":zlibraryweb"` |
| 正式包导出入口 | 仅 Launcher 与外部 EPUB 打开两个既有入口 |
| 内存日志 | 仅 `FLAG_DEBUGGABLE` 输出 |
| 其他日志 | 仅 downloadId、异常类名/系统错误信息；无 URL、Cookie、账号、签名参数 |
| 调试导出入口 | 已随探针删除；正式包无相关组件 |

---

## 4. 验收步骤建议

1. 安装测试版 APK，确认可与现有正式版并存。
2. 进入「云端 → Z-Library」，输入关键词搜索：原生搜索被拒时显示“原生搜索暂不可用，可使用网页搜索”，并展示官方「最热门」书单，明确标注为热门书单而非关键词结果。
3. 点击「网页搜索」，登录后从菜单进入「下载历史」，重下当天书籍：确认内置确认框、下载任务与“已导入”。
4. 反复进出网页容器、切后台再返回：页面与登录态保持，无卡顿与明显内存增长。
5. 检查正式版 APK：不存在 WebView/Z-Library 探针入口。

### 验收判定表

| 验收项 | 判定 |
| --- | --- |
| 生命周期与资源释放 | 通过 |
| 重复进入无实例/内存累积 | 通过 |
| 最小导航恢复与登录态保持 | 通过 |
| 渲染器异常退路 | 通过（强杀注入受设备权限限制） |
| 探针与调试入口清理 | 通过 |
| 日志无敏感信息 | 通过 |
| 测试与静态检查 | 通过（352/0，Lint 0 error） |

---

## 5. 遗留与风险

| 项目 | 影响 | 建议 |
| --- | --- | --- |
| 渲染器强杀未真机注入 | 低 | 后续在 root/工程机补一次 `kill -9` 回归 |
| 锁屏朗读 + 网页组合未测 | 低 | 验收时可按实际使用补测 |
| 网页宿主进程退出后仍可能被系统缓存 | 无功能影响 | 由系统回收，WebView 已销毁 |
| 完成通知可能被点击打开下载文件 | 低 | 导入结果以应用内任务为准，后续如需完全静默再评估 |

---

## 6. 结论

阶段 F 完成了网页容器的可见性管理、退出释放、最小导航恢复与内存观测；阶段 G 完成了三阶段探针清理和交付边界核验。测试、静态检查、构建与真机回归均达到门槛，**建议通过验收**。

详细实现与过程证据见：

- `docs/04-在线书库统一化/Z-Library混合架构阶段F实施报告.md`
- `docs/04-在线书库统一化/Z-Library混合架构阶段G实施报告.md`
