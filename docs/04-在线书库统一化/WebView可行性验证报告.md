# 静读 Z-Library 网页搜索：Android WebView 独立可行性验证报告

- 验证日期：2026-11-25
- 验证性质：**独立、临时的技术验证**；按需求「完成测试后汇报结果，暂不进行正式业务集成」
- 结论摘要：**通过**。标准 Android WebView 在启用 JavaScript + DOM Storage、保持默认 TLS 校验与默认 UA、未登录状态下，可以正常完成 Z-Library 网页的首次访问、关键词搜索、分页与刷新；搜索结果以 `z-bookcard` 形式稳定出现在 DOM 中（每页 51 个）。

---

## 1. 验证目标与范围边界

### 目标
验证「标准 Android WebView 浏览器环境」是否具备正常访问 Z-Library SSR 网页搜索的能力，并记录加载耗时、HTTP 错误、重定向与加载结果；验证未登录状态下能否正常搜索。

### 已确认前提（本次采信，不重复论证）
1. 网页首次搜索及分页采用 SSR。
2. 搜索结果直接存在于 HTML 的 `z-bookcard` 元素中。
3. 普通浏览器及无痕浏览器可正常访问搜索页面。
4. 禁用 JavaScript 后网页请求返回 HTTP 503。
5. 原有 App 的 `/eapi/book/search` API 返回 Access denied。

### 严格遵守的边界
| 约束 | 本次做法 |
| --- | --- |
| 独立临时测试页面 | 新建独立 `webprobe` 包 + 独立 Activity，仅存在于 `ttsTestDebug` 变体，通过 ADB 显式启动，**未接入任何应用内导航** |
| 标准 WebView 浏览器环境 | framework `WebView`，默认 UA、默认请求头、`loadUrl` 裸加载；未使用任何自定义拦截注入 |
| 启用 JS 与 DOM Storage | `javaScriptEnabled=true`、`domStorageEnabled=true` |
| 不关闭 TLS 校验 | 使用系统默认证书校验；`onReceivedSslError` 只记录并 `handler.cancel()`，全程未 `proceed`，未自定义 TrustManager |
| 不伪造认证凭证、不绕过网站访问验证 | 未注入 Cookie/Token/账号，未复用 App 登录凭证，未改 UA 伪装，未干预/跳过 challenge；网站自身的 JavaScript 由 WebView 正常执行（等同普通浏览器），验证由页面脚本自然完成 |
| 不修改现有 App API 搜索实现 | 探针不引用 `ZLibraryApi` / `ZLibraryBookProvider` / 在线书库 ViewModel；仅**只读**了一次 `ZLibraryConfigStore` 用于取得域名（实际测试域名由 ADB 显式传入） |
| 不修改服务器配置 / 下载 / 书籍导入 | 未写入任何配置文件，未触碰下载、导入、书库相关类 |

> 本次只验证「网页访问能力」，**不验证**书卡字段解析、下载链路、登录态、额度、稳定性与合规问题，也未做任何业务集成。

---

## 2. 测试环境

| 项 | 值 |
| --- | --- |
| 设备 | SM-S9360，Android 16（SDK 36） |
| WebView | `com.google.android.webview` 134.0.6998.135（Chromium 134） |
| UA（默认，未覆盖） | `Mozilla/5.0 (Linux; Android 16; SM-S9360 Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/134.0.6998.135 Mobile Safari/537.36` |
| WebSettings | JS=true，DOM Storage=true，cacheMode=LOAD_DEFAULT(-1)，mixedContentMode=NEVER_ALLOW(1)，allowFileAccess=false |
| TLS | 系统默认校验；`onReceivedSslError → cancel`；**全程 0 次 SSL 错误** |
| 登录态 | **未登录**：启动前 `COOKIE_BEFORE len=0`，清空 WebView 自身 CookieJar（不影响 App 凭证）；页面顶部显示「捐款 / 登录」；cookie 由网站验证流程自行种下（109→142 / 134），无账号凭证 |
| 测试域名 | 主 `https://zh.z-lib.gd`、备 `https://zh.z-library.sk`（均取自真机 App 已配置的服务器候选，本次由 ADB 显式指定） |
| 关键词 | `python` |
| 搜索 URL 形态 | `/s/<query>`，分页 `?page=2`（首页表单探测得到 `action=https://zh.z-lib.gd/s/`、`method=get`、`names=[token,q,e]`，与所用 URL 一致） |

---

## 3. 探针实现（独立 / 临时 / 零业务改动）

- 新增 `app/src/ttsTestDebug/java/com/yuezhou/reader/webprobe/WebViewProbeActivity.kt`
- 修改 `app/src/ttsTestDebug/AndroidManifest.xml`：新增该 Activity 声明（`exported=true`，仅供 ADB 启动，`configChanges` 防止旋转重建）
- `ttsTestDebug` 是测试版专属 sourceSet，**production / release 包完全不含该组件**；`app/build.gradle.kts`、main 源码、依赖均未改动（WebView 为 framework 自带，零新增依赖）
- 页面自带地址栏与「首页 / 搜索 / 下一页 / 刷新 / 自动重跑 / 清屏」按钮，可手动逐项复测
- 诊断输出同时写入 Logcat（`tag=WebViewProbe`）与 `files/webview_probe.log`，记录：`onPageStarted/Finished`、`onPageCommitVisible`、`onReceivedHttpError`、`onReceivedError`、`onReceivedSslError`、`shouldOverrideUrlLoading`（含 `isRedirect`）、进度/标题/JS console，以及每步两次 `evaluateJavascript` DOM 采样（`z-bookcard` 数、标题、URL、bodyLen、challenge 关键词、表单 action、cookie 长度）
- 自动序列：HOME → SEARCH → PAGE2 → RELOAD，每步记录耗时、HTTP 错误、重定向、导航链与 DOM 结果

### 复现命令

```bash
./build-local.sh :app:assembleTtsTestDebug
adb install -r app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk
adb shell am start -n com.yuezhou.reader.tts.test/com.yuezhou.reader.webprobe.WebViewProbeActivity \
  --ez autorun true --es base_url https://zh.z-lib.gd --es query python
adb shell run-as com.yuezhou.reader.tts.test cat files/webview_probe.log
# 可选参数：--es search_path /s/ --es page_param page
#           --ei sample1 10000 --ei sample2 20000 --ei step_gap 23000
```

---

## 4. 测试结果

### 轮次 1：`https://zh.z-lib.gd`（主，采样 10s/20s，步长 23s）

| 步骤 | URL | 首屏可见 | 完成信号 | 主文档 HTTP 错误 | 3xx 重定向 | `z-bookcard` | 结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| HOME | `/` | 1.80s(验证页) → 5.01s(首页) | PROGRESS_100 6.76s | **1×503**（`Checking your browser ...`） | 0 | 0（首页无书卡） | JS 验证通过后自动重载，渲染出中文首页 |
| SEARCH | `/s/python` | 2.10s | PAGE_FINISH 8.31s | 0 | 0 | **51** | 成功，标题「python：在 Z-Library 上搜索」 |
| PAGE2 | `/s/python?page=2` | 1.01s | PROGRESS_100 2.20s | 0 | 0 | **51** | 成功，书名与第 1 页不同（中文 Python 书目） |
| RELOAD | reload(page2) | 0.91s | PROGRESS_100 2.19s | 0 | 0 | **51** | 成功，未回落 503/验证页 |

### 轮次 2：`https://zh.z-library.sk`（备，采样 6s/12s，步长 15s）

| 步骤 | URL | 首屏可见 | 主文档 HTTP 错误 | 3xx 重定向 | `z-bookcard` | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| HOME | `/` | 3.77s | **1×503**（验证页） | **1** | 0 | JS 验证通过后渲染首页 |
| SEARCH | `/s/python` | 1.49s | 0 | 0 | **51** | 成功 |
| PAGE2 | `/s/python?page=2` | 0.68s | 0 | 0 | **51** | 成功 |
| RELOAD | reload(page2) | 0.72s | 0 | 0 | **51** | 成功 |

两轮共 8 个步骤：**除每轮首次访问各 1 次 503 验证页外，所有搜索 / 分页 / 刷新步骤均 0 主文档 HTTP 错误、0 SSL 错误、0 业务重定向**，DOM 采样稳定命中 51 个 `z-bookcard`。

视觉佐证（真机截图）：搜索结果页完整渲染出书籍卡片——封面、书名、作者、出版社、`EPUB / PDF` 格式、大小、语言、年份与评分；页面顶部仍为「捐款 / 登录」未登录态，URL 为 `https://zh.z-lib.gd/s/python?page=2`，搜索框回填 `python`。

---

## 5. 关键观察

1. **禁用 JS 返回 503 的对照面得到复现并被 WebView 自然化解**：首次访问必先收到 `503 Service Unavailable` + 标题 `Checking your browser ...`。启用 JS 后，页面自身脚本完成校验并以**同一 URL 二次加载**（`STEP_CHAIN` 显示 `https://zh.z-lib.gd/ -> https://zh.z-lib.gd/`，`isRedirect=false`），约 4~7s 后渲染出真实首页。**未做任何人为干预或绕过**，与「普通浏览器可访问」表现一致。
2. **未登录即可搜索**：`COOKIE_BEFORE len=0`、页面显示「登录」，搜索、分页、刷新全部返回完整结果；全程未提交账号、未注入凭证。
3. **SSR 结果可直接从 DOM 取得**：`z-bookcard` 在 `COMMIT_VISIBLE` 后约 1~2s 即可统计到（每页 51 个），`document.title` 同步变为搜索页标题，无需等待额外 XHR。第 1 页与第 2 页 `bodyLen` 与书目内容不同，证明分页确实生效而非缓存同一页。
4. **重定向记录**：主域名 0 次 3xx；备选域名首次访问记录到 1 次 3xx 重定向（`NAV isRedirect=true`），WebView 按默认行为处理后不影响结果。challenge 的跳转是 JS 重载而非 3xx，二者已在日志中区分。
5. **TLS 与安全性**：全程 0 次 `SSL_ERROR`；未 `proceed()` 任何证书错误；子资源仅有 `counter.yadro.ru` 统计脚本出现 `NET_ERROR_SUB code=-6`（被网络拦截，与业务无关），无主文档网络错误。
6. **刷新不回落验证页**：首轮 challenge 通过后网站自行种下验证 cookie（109→142 字节），后续搜索/分页/刷新及 `reload()` 均直接返回内容，无需重复通过验证。
7. 轮次 1 的 PAGE2/RELOAD 出现 `PAGE_FINISH ≈22s`，是统计类长连接资源拖尾；`COMMIT_VISIBLE ≈1s`、`PROGRESS_100 ≈2.2s`，且 10s DOM 采样已拿到完整 51 张书卡，**不应解读为页面加载耗时 22 秒**。

---

## 6. 结论

**Android WebView 网页访问能力验证通过，具备可行性。**

在「标准 WebView + JS + DOM Storage + 默认 TLS 校验 + 默认 UA + 匿名未登录」这一完全干净的浏览器环境下，网站自身的 JavaScript 访问验证能够正常完成；SSR 搜索页、分页页与刷新页均可稳定加载，搜索结果以 `z-bookcard` 元素存在于 DOM，可被页面内 JavaScript 直接统计与读取。原有 App API 搜索返回 Access denied 的限制，**在网页通道上未被复现**。

按需求，本次**到此为止，不做任何正式业务集成**，现有 API 搜索实现保持原状（仍为「搜索被拒 → 降级官方最热门书单」）。

---

## 7. 局限与后续若考虑集成时的注意事项

- 本次验证的是「页面访问与 DOM 可读取性」，**未验证**：书卡字段抽取、详情页/下载链接、登录与额度、封面加载、反爬策略稳定性、长期可用性、法务与版权合规、性能与耗电。
- 网站域名与反爬策略会变动；结论仅代表 2026-11-25 当时的 `zh.z-lib.gd` / `zh.z-library.sk` 与 WebView 134。
- 若未来集成，需注意：
  1. **首次访问必然出现 503 challenge 页**，不能把主文档首个 HTTP 状态码当作失败判据；应以 `onPageFinished` 后 DOM 探测 `z-bookcard` / 标题变化作为成功判据，并预留约 5~8s 的验证等待窗口。
  2. 需容忍同 URL 的 JS 重载与偶发 3xx 重定向（保持 WebView 默认处理即可）。
  3. 保持同一 WebView CookieJar 的持久化，避免每次冷启动重复 challenge；但**不得注入任何认证 cookie 或凭证**。
  4. 网页解析与现有 `OkHttp + eapi` 数据模型差异较大，需独立设计解析层与错误码，不能简单替换现有 Provider。
  5. 需评估该通道的合规性、用户告知与稳定性降级方案。

---

## 8. 变更文件清单

| 文件 | 变更 |
| --- | --- |
| `app/src/ttsTestDebug/java/com/yuezhou/reader/webprobe/WebViewProbeActivity.kt` | 新增：独立临时 WebView 探针（仅测试版变体） |
| `app/src/ttsTestDebug/AndroidManifest.xml` | 新增探针 Activity 声明（仅 `ttsTestDebug` 合并生效） |
| `docs/04-在线书库统一化/WebView可行性验证报告.md` | 新增本报告 |

**未改动**：`app/src/main/**`、`app/build.gradle.kts`、任何 `core`/`reader` 业务代码、现有 API 搜索实现、服务器配置、下载与书籍导入逻辑。探针为临时验证代码，可随时移除，且不会进入正式发布包。

原始完整日志归档于 `.workbuddy/tmp/webview_probe_run1.log` 与 `webview_probe_run2.log`；真机截图归档于 `.workbuddy/tmp/webview_probe_screen.png`、`webview_probe_cards.png`。

**回归校验**：`./build-local.sh test :app:lintTtsTestDebug` 通过（BUILD SUCCESSFUL；各模块单测任务 UP-TO-DATE；lint 0 error）。测试版 APK `app/build/outputs/apk/ttsTest/debug/app-ttsTest-debug.apk` 已按约定 `adb install -r` 覆盖安装到真机，签名一致、覆盖成功。
