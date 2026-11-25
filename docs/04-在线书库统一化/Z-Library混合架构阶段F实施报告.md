# Z-Library 混合架构阶段 F 实施报告

- 分支：`feature-zlibrary`
- 实施范围：仅阶段 F（内存与生命周期）。
- 基线：阶段 E 提交 `dfc5555`。
- 本轮结果：生命周期补全、最小导航恢复、调试内存观测与多轮真机进出验证完成。

---

## 1. 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../web/ZLibraryWebActivity.kt` | `onStart/onStop` 暂停/恢复本 WebView；后台取消超时、回前台重新计时；保存/恢复当前地址；`singleTop` 下 `onNewIntent` 复用；销毁时解除下载监听 |
| `app/.../web/ZLibraryWebMemoryLog.kt` | 仅调试包输出宿主 PSS 与系统可用内存 |
| `app/src/main/AndroidManifest.xml` | 网页容器增加 `launchMode="singleTop"` |

---

## 2. 实现要点

### 2.1 可见性与后台

- `onStop()`：取消页面超时、调用当前 WebView 的 `onPause()`（仅本实例，未使用全局 `pauseTimers()`）、持久化 Cookie。
- `onStart()`：调用 `onResume()`；若仍在加载/验证等待态，则按当前导航代次重新计时，避免后台期间误报超时。
- 网页容器运行在独立进程，主进程的朗读/TTS 不因打开网页被暂停或重建。

### 2.2 最小导航信息与实例复用

- `onSaveInstanceState` 只保存当前地址；恢复时优先使用保存地址，其次 intent 地址，并重新走导航白名单校验。
- 不恢复完整历史，登录态仍依赖独立数据目录中的持久 Cookie。
- 清单增加 `singleTop`，`onNewIntent` 复用同一容器并加载新的合法地址，避免重复点击堆叠多个 WebView。

### 2.3 释放与渲染器异常

- `onDestroy`：取消超时、解除 `DownloadListener`、移出视图、`stopLoading()`、`destroy()`。
- 渲染器退出沿用阶段 C 的 `onRenderProcessGone`：立即销毁旧 WebView、显示“重试/关闭”覆盖层，重试时重建。
- 未依赖已不再可靠分发的旧低内存回调；低内存场景沿用现有加载前守卫与 Activity 生命周期。

### 2.4 内存观测

`ZLibraryWebMemoryLog` 仅在 `FLAG_DEBUGGABLE` 时输出：网页宿主、主进程、朗读进程 PSS 与系统 `availMem/lowMemory`。WebView 沙箱渲染器不属于本应用 UID，应用内不可读，验收时用 `dumpsys meminfo` 记录。

---

## 3. 真机验证

设备：Android 16 / API 36，测试包 `com.yuezhou.reader.tts.test`。

| 场景 | 结果 |
| --- | --- |
| 多轮进出网页容器 | 4 次打开/关闭，均正常创建、销毁；`ZLibraryWebMem` 显示网页宿主 PSS 稳定在 **66 MB**，无逐轮增长 |
| 后台/前台 | Home 后输出 `stage=stop`，返回后 `stage=start`，页面恢复加载与超时计时 |
| 主进程占用 | 全程约 **115 MB**；系统可用内存约 **2.6–2.9 GB**，`low=false` |
| 渲染器 PSS | `dumpsys meminfo 1717`：WebView sandboxed renderer `TOTAL PSS 132673 KB`（约 130 MB） |
| 低内存回调 | `am send-trim-memory ...:zlibraryweb RUNNING_LOW` 后进程存活、再次进出正常 |
| 集成回归 | 网页搜索、登录态、下载入口不受生命周期改动影响 |

### 未测项

- 渲染器强杀：`adb shell kill -9 <renderer>` 返回 `Operation not permitted`，非 root 设备无法注入；`onRenderProcessGone` 逻辑与重建路径由代码审查和阶段 C 既有实现覆盖。
- 锁屏朗读 + 开网页的组合场景未单独执行；网页容器与朗读分属不同进程，未发现共享生命周期。
- 语音模型加载耗时/回退结果由既有 speech 诊断维护，本轮未改动、未重复采集。

---

## 4. 结论

阶段 F 完成：页面不可见时停止非必要工作，退出彻底销毁 WebView，重复进入不累积实例，最小导航信息可恢复，渲染器异常有明确退路。真机多轮验证无内存增长趋势。

下一步进入阶段 G（删除三阶段探针、确认正式包无调试入口与敏感日志）。
