# Z-Library 混合架构阶段 C 实施报告

- 分支：`feature-zlibrary`
- 实施范围：仅阶段 C（进程守卫、非导出 WebView 容器、隔离数据目录、导航与安全设置、页面状态机），未进入阶段 D/E/F/G。
- 基线：阶段 B 提交 `7f9b35a`，287 项单元测试通过、Lint 0 error / 67 warning + 3 hint。
- 本轮结果：**323 项单元测试通过、0 失败；Lint 0 error / 67 warning + 3 hint（与基线持平）；测试版 APK 构建成功、已安装真机并完成网页加载与错误面板验证。**

---

## 1. 结论与范围

阶段 C 交付一个可嵌入、非导出、独立进程的 Z-Library 网页容器，建立安全边界与页面状态机，并确保网页进程不初始化数据库、同步、阅读器或 TTS。

本轮**不包含**：应用内入口按钮与顶部导航栏、下载监听与外部下载交接、ContentProvider、原生搜索降级与格式修复、探针清理。这些分别属于阶段 D/E/G。

---

## 2. 进程守卫与进程隔离

### 2.1 进程角色判定

新增 `app/.../process/AppProcess.kt`：

```kotlin
object AppProcess {
    const val WEB_PROCESS_SUFFIX = ":zlibraryweb"
    enum class Role { MAIN, WEB, OTHER }
    fun currentName(context: Context): String   // 进程内缓存
    fun role(context: Context): Role
    fun isMainProcess(context: Context): Boolean
    fun isWebProcess(context: Context): Boolean
    fun roleOf(packageName: String, processName: String?): Role
}
```

- API 28+ 使用 `Application.getProcessName()`；minSdk 26 的旧系统回退读取 `/proc/self/cmdline`，避免为取进程名初始化框架组件。
- 空进程名回退为 `packageName`（视为主进程），与 Android 默认行为一致。
- 自动适配 `:tts.test` 变体：网页进程完整名为 `com.yuezhou.reader.tts.test:zlibraryweb`。

### 2.2 Application 守卫

`ReaderApplication.onCreate()` 在任何装配之前判定进程角色：

```kotlin
override fun onCreate() {
    super.onCreate()
    if (!AppProcess.isMainProcess(this)) return
    SpeechRuntime.installProgressSink(RoomSpeechProgressSink(this))
    installSpeechEngines()
}
```

- `RoomSpeechProgressSink` 与 `SpeechRuntime` 只在主进程构造，`:zlibraryweb` 进程不会打开 Room。
- 测试版 `ReaderTtsApplication` 仅覆写 `installSpeechEngines()`，Kokoro 提供者在方法体内构造；守卫生效时不会执行，TTS/Kokoro 不进入网页进程。

### 2.3 提前初始化入口审计

| 来源 | 审计结果 |
| --- | --- |
| Application | 仅 `ReaderApplication`/`ReaderTtsApplication`，均受进程守卫 |
| ContentProvider | 应用自身未声明任何 Provider；阶段 D 新增的非导出 Provider 必须沿用“不初始化 WebView”的约束 |
| AndroidX Startup | 合并清单存在 `androidx.startup.InitializationProvider`，初始化为 `EmojiCompatInitializer`、`ProcessLifecycleInitializer`、`okhttp3 PlatformInitializer`、`ProfileInstallerInitializer`；均不创建 WebView，也不触碰 Room/同步 |
| Room | `androidx.room.MultiInstanceInvalidationService` 仅为服务声明，未启动即不初始化数据库 |
| 调试构建附加项 | 合并清单中的 `androidx.activity.ComponentActivity` 来自 `androidx.compose.ui:ui-test-manifest`（仅 debug 构建，release 不含），与 WebView 无关 |

---

## 3. WebView 容器与安全设置

### 3.1 容器声明

`app/src/main/AndroidManifest.xml`：

```xml
<activity
    android:name=".web.ZLibraryWebActivity"
    android:exported="false"
    android:process=":zlibraryweb"
    android:theme="@style/Theme.YuezhouReader"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density"
    android:windowSoftInputMode="adjustResize" />
```

- 非导出、独立进程；旋转与窗口尺寸变化不重建 Activity，避免重复提交搜索或下载。
- 返回键通过 `OnBackPressedDispatcher` + `OnBackPressedCallback`：优先 `WebView.goBack()`，无历史时结束 Activity。

### 3.2 数据目录与降级

| 项目 | 实现 |
| --- | --- |
| API 28+ 隔离目录 | `onCreate` 中、创建任何 `WebView`/`CookieManager` 之前调用 `WebView.setDataDirectorySuffix("zlibrary_web")`；`AtomicBoolean` 保证本进程只设置一次 |
| API < 28 | `ZLibraryWebSupport.isSupported()` 为 false：不创建 WebView，交由系统浏览器打开初始链接后 `finish()` |
| Cookie 会话 | 使用隔离数据目录自带的 WebView Cookie 存储；`onStop` 时 `CookieManager.getInstance().flush()`；不读取或注入原生 API 凭证，不写任务表 |
| 销毁 | `onDestroy` 中移除并 `destroy()` WebView，取消超时回调 |

### 3.3 WebSettings 与安全项

| 安全项 | 设置 |
| --- | --- |
| JavaScript / DOM Storage | 开启（站点正常功能所需） |
| UA | 不覆盖，使用系统默认 UA |
| JS Bridge | 不注册任何 `addJavascriptInterface` |
| 文件访问 | `allowFileAccess=false`、`allowContentAccess=false` |
| Mixed Content | `MIXED_CONTENT_NEVER_ALLOW` |
| 多窗口 | `setSupportMultipleWindows(false)`、`javaScriptCanOpenWindowsAutomatically=false` |
| 媒体/定位 | 媒体需用户手势；关闭地理位置 |
| SSL 错误 | `onReceivedSslError` 一律 `handler.cancel()`，不提供 continue/proceed |
| WebView 调试 | release（`FLAG_DEBUGGABLE == 0`）显式 `setWebContentsDebuggingEnabled(false)` |
| 日志 | 生产代码不输出 URL/Cookie/凭证；仅提供经单测的 `WebNavigationPolicy.sanitizeForLog()` 供后续阶段使用 |
| 下载监听 | 阶段 C 未注册 `DownloadListener`（阶段 D 接入） |

### 3.4 导航策略

`WebNavigationPolicy.classify(raw, allowedHosts)`（纯 JVM）：

- 仅 `https`；`userinfo` 非空、端口非 `-1/443` 一律 `BLOCKED`。
- host 归一化（小写、去尾部点）后，`host == allowed` 或 `host.endsWith(".$allowed")` → `ALLOWED`。
- 其它 https → `EXTERNAL`（确认后交系统浏览器）；危险 scheme（`http/file/content/javascript/intent/data` 等）→ `BLOCKED`。
- 允许主机来自 `ZLibraryConfigStore.load()`：`servers.baseUrls`、启用候选 `candidates[].baseUrl`、兼容字段 `manualBaseUrl`；**只读配置，不写回、不修改用户设置**。
- `EXTRA_INITIAL_URL` 必须为 `ALLOWED`，否则直接结束，不加载任意外部地址。
- 外部域使用 AlertDialog 二次确认后才用系统浏览器打开；被拒绝的 scheme 只提示“不支持打开该链接”。

---

## 4. 页面状态机

`WebPageStateMachine`：

```text
IDLE → LOADING → CONTENT_VISIBLE
              ↘ VERIFICATION_OR_WAITING → CONTENT_VISIBLE
              ↘ NETWORK_ERROR（需用户显式重试）
              ↘ TIMEOUT（继续等待 / 重试 / 关闭）
RENDERER_GONE（重建后回到 IDLE → 新导航）
```

- `beginNavigation()` 递增导航代次；旧代次回调被丢弃，避免历史页面覆盖新状态。
- 主文档与子资源分离：`shouldOverrideUrlLoading`/`onReceivedError`/`onReceivedHttpError` 均先判 `isForMainFrame`。
- 首次 503：`markWaiting` 不阻塞页面；页面真实可见（`onPageCommitVisible`/`onPageFinished`）后撤去 Loading，不自行处理验证。
- 网络错误不被迟到的 `onPageFinished` 撤销；SSL 错误、主文档 5xx 归入错误面板。
- 30 秒超时提供「继续等待 / 重试 / 关闭」；继续等待不重新导航、不重复提交，只恢复计时；重试走 `reload()`。
- `onRenderProcessGone` 返回 `true`：移除并销毁旧 WebView，显示“重新加载/关闭”，用户重试时才重建并加载最后一次允许的 URL。
- 加载完成判定依据页面可见回调，不依据书卡数量，也不抽取页面书目数据。

---

## 5. 代码变更清单

### 5.1 新增文件

| 文件 | 作用 |
| --- | --- |
| `app/.../process/AppProcess.kt` | 进程角色判定（主/网页/其它），API 28+ 与旧系统回退 |
| `app/.../web/ZLibraryWebActivity.kt` | 非导出网页容器：隔离目录、安全设置、导航策略、状态机、超时与渲染器退出 |
| `app/.../web/ZLibraryWebSupport.kt` | API 28 支持判定、外部浏览器打开（含 scheme 校验） |
| `app/.../web/WebNavigationPolicy.kt` | HTTPS 白名单导航策略、host 归一化、日志脱敏 |
| `app/.../web/WebPageStateMachine.kt` | 页面状态与导航代次 |
| `app/src/test/.../process/AppProcessTest.kt` | 4 项进程角色测试 |
| `app/src/test/.../web/WebNavigationPolicyTest.kt` | 6 项导航策略测试 |
| `app/src/test/.../web/WebPageStateMachineTest.kt` | 8 项状态机测试 |

### 5.2 修改文件

| 文件 | 变更 |
| --- | --- |
| `app/.../ReaderApplication.kt` | 增加主进程守卫；抽出 `installSpeechEngines()` 扩展点 |
| `app/src/ttsTestDebug/.../ReaderTtsApplication.kt` | 改为覆写扩展点注入 Kokoro；仅主进程执行 |
| `app/src/main/AndroidManifest.xml` | 新增非导出 `:zlibraryweb` 容器 Activity |

临时用于真机截图的 `ttsTestDebug/AndroidManifest.xml` 导出 overlay **已删除**，最终产物保持非导出。

---

## 6. 测试结果

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew test :app:lintTtsTestDebug :app:assembleTtsTestDebug
```

- 结果：`BUILD SUCCESSFUL`，**323 项测试通过 / 0 失败 / 0 错误 / 0 跳过**（阶段 B 287 + 本轮 18 项 × 2 变体 = 36）。
- 新增 18 项测试按要求的覆盖类别：

| 要求类别 | 测试用例 |
| --- | --- |
| 网页进程识别 | `主进程识别为 MAIN`、`网页进程识别为 WEB`、`其它进程不视为主进程`、`空进程名回退为主进程` |
| 导航白名单与外部域 | `配置服务器及其子域的 HTTPS 允许`、`仿冒域名与外部域名交给系统浏览器` |
| 危险 scheme 与伪装 | `非 HTTPS 与危险 scheme 一律拒绝`、`伪装 userinfo 与非标准端口拒绝` |
| 日志脱敏 | `日志脱敏去掉查询片段与 userinfo`、`hostOf 只返回规范化主机` |
| 状态机初始与代次 | `初始为空闲且不阻塞`、`每次主导航产生新代次`、`旧代次回调不会覆盖新状态` |
| 503 等待态 | `503 等待态在内容可见后撤去` |
| 网络错误与超时 | `网络错误不会被已完成页面回调撤销`、`内容可见后超时不再生效`、`超时后可继续等待且不产生新代次` |
| 渲染器退出 | `渲染器退出后忽略旧回调并可重置` |

### Lint

- `:app:lintTtsTestDebug`：**0 error / 67 warning + 3 hint**，与阶段 B 基线一致；`web/`、`process/` 新增文件无告警。
- 过程中修复：`GestureBackNavigation`（改用 `OnBackPressedDispatcher`）、`UseKtx`（`toUri()`）、`RedundantLabel`（删除重复 label），并移除 compileSdk 37 已不存在的 `onReceivedTitle` 覆写。

---

## 7. 真机验证

设备：API 36（Android 16）真机，测试版 `com.yuezhou.reader.tts.test` 安装，正式版 `com.yuezhou.reader` 未被覆盖。

| 验证项 | 证据 |
| --- | --- |
| 容器可运行 | `am start` 后 `mCurrentFocus=com.yuezhou.reader.tts.test/.web.ZLibraryWebActivity`，`pidof ...:zlibraryweb` 有值，`logcat -b crash` 无记录 |
| 独立进程 | 网页进程 pid 独立存在；`/proc/<pid>/fd` 出现 `app_webview_zlibrary_web/webview_data.lock`，确认数据目录后缀生效 |
| 未初始化数据库/同步 | 网页进程 fd 中无应用 Room 数据库、`files/providers` 配置、EPUB 或阅读器文件句柄（仅有 WebView 自身 `app_webview_zlibrary_web/Default/databases/Databases.db`） |
| HTTPS 页面加载 | 连接代理后加载 `https://zh.z-lib.gd/`，完整渲染 Z-Library 首页（搜索框、Black Friday 横幅、最受欢迎书单），Loading 覆盖层已撤去 |
| 安全错误面板 | 加载允许域下不可解析子域触发证书异常，显示「安全连接失败 / 站点证书校验未通过，已取消连接」及「重试 / 关闭」，确认 SSL 错误一律取消且面板可用 |
| 非导出 | 最终 APK `aapt2 dump xmltree`：`exported=false`、`process=":zlibraryweb"`；`adb shell am start` 返回 `SecurityException: ... not exported from uid` |

说明：容器尚未接入应用内入口（阶段 D），真机截图使用临时导出 overlay 启动，验证完成后已移除并重建最终 APK。

---

## 8. 已知问题与限制

1. **入口未接线**：阶段 C 只交付容器与安全边界；用户可见入口、顶部导航栏、刷新/关闭按钮属于阶段 D。
2. **未验证 API < 28 真机**：逻辑为“直接外部浏览器”，无对应设备可测；已由 `ZLibraryWebSupport` 分支与代码审查覆盖。
3. **外部域确认与超时面板**：外部域确认流程与 30 秒超时面板由单元测试与代码审查覆盖，未在真机逐一触发；真机已验证网络/SSL 错误面板。
4. **WebView 自身数据库**：隔离目录内存在 Chromium 的 `Databases.db` 属 WebView 内部存储，与应用 Room 数据库无关。
5. **未注册下载监听**：`DownloadListener`、CDN 校验与外部下载交接按计划留到阶段 D。
6. **调试构建附加 Activity**：`androidx.activity.ComponentActivity` 来自 Compose UI 测试清单，仅 debug 构建存在，release 不受影响。

---

## 9. 与技术方案的差异

| 方案描述 | 实际实现 | 原因 |
| --- | --- | --- |
| 建议独立 Activity | 使用 `ComponentActivity` + `OnBackPressedDispatcher` | 满足 Lint `GestureBackNavigation`，返回键行为不变 |
| 30 秒超时提供继续等待 | 继续等待只恢复 LOADING 并重新计时，不重新导航 | 遵循“不得自动高频刷新、不重复提交” |
| 状态机含 VerificationOrWaiting | 503 仅切换状态，不阻塞、不注入验证逻辑 | 遵循“首次 503 允许页面继续展示” |
| WebSettings 默认 UA | 不设置 UA，沿用系统默认 | 避免站点按自定义 UA 降级或拒绝 |
| 导航白名单来源于配置 | 只读 `ZLibraryConfigStore.load()`，同时纳入启用候选与兼容字段 | 不改配置、不写入用户设置 |
| 仅描述 503 与网络错误 | 额外把主文档 5xx 映射为错误面板 | 服务端异常时避免长期遮挡页面 |
| 页面状态观察 | 不注入脚本、不读取页面内容，仅依据导航回调 | 不抽取搜索书目、不采集敏感数据 |

---

## 10. 后续阶段衔接

- **阶段 D**：接入网页入口与顶部导航栏；注册 `DownloadListener`，使用系统 DownloadManager 下载到受控暂存；新增非导出 ContentProvider 调用阶段 B 的 `acceptCompletedDownload`，并校验调用方、URI authority、任务归属、大小上限与可读性；确保 Provider 不初始化 WebView。
- **阶段 E**：修复 `ZLibraryApi.toOnlineBook()` 对 `extension`/`format` 的处理，缺失/冲突/不支持格式不得默认 EPUB。
- **阶段 G**：删除三个 Z-Library 临时探针及临时进程声明，保留阶段验证报告、正式测试与 TTS `StageDActivity`。

本轮完成即停止，等待阶段 D 确认。
