# 静读 Z-Library WebView 第二阶段可行性验证报告

验证日期：2026-11-25（北京时间）。第一阶段报告用于确认入口、设备和测试基线。下文以本轮真机日志、构建结果及源代码为证据。

**网页路径已实测走通搜索、筛选、详情、登录、下载及 EPUB 校验。** 现有导入模块具备接收该样本的接口和文件校验能力，正式入库及 Z-Library App API 线上下载仍待验证。连续 20 次搜索全部成功。`python` 连续 10 次页码导航均可完成，第 11 页在匿名和登录状态下均为空结果。

## 环境与范围

- 真机 SM-S9360，Android 16，系统 WebView 134.0.6998.135。主、备域名沿用第一阶段的 `zh.z-lib.gd`、`zh.z-library.sk`。
- 新增 `WebViewStageTwoActivity`，仅由 `ttsTestDebug` 打包。探针运行在 `:webprobe` 进程，使用 `stage_two_probe` WebView 数据目录。启动时保留 Cookie，Activity 停止时执行 `CookieManager.flush()`。该目录与应用默认 WebView 数据目录分开，域名之间按 WebView 的 Cookie 域规则处理。探针要求 Android 9 或更新版本才能使用此隔离机制。[Android WebView API](https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String))
- 使用 WebView 默认 UA 和 TLS 校验，启用 JavaScript 与 DOM Storage。日志仅记录导航标签、主机、耗时、错误状态、页面特征和 Cookie 长度。探针可从 ADB 显式启动，没有应用内导航入口。探针的下载确认将文件存入测试版应用专属目录，没有正式书库写入路径。
- 测试涉及常规 WebView 导航及页面状态采样。网页访问和用户手动下载的结果仅覆盖对应浏览器操作。

## 验证结果

| 项目 | 真机结果 |
| --- | --- |
| 英文搜索 | 通过。 `python` 显示 51 个书卡，首屏 3.214 秒。 |
| 中文搜索 | 通过。 `中国历史` 显示 51 个书卡，首屏 0.927 秒。 |
| 无搜索结果 | 命中空结果提示。随机关键词 `zzstage2kx7p19v403qw` 首屏 1.474 秒，页面仍存在 1 个 `z-bookcard` 元素。结果判定需结合网页提示。 |
| 主站 → 备站 → 主站 | 通过。备站先显示访问验证页，4.816 秒提交第二次可见页面，采样时显示 51 个书卡。切回主站显示 51 个书卡，保留两个域各自的 Cookie。 |
| 后台恢复 | 通过。发出 HOME 键后再次唤起，原 PID 与页面继续使用，采样保留第 1 页 51 个书卡。 |
| 退出 Activity 后进入 | 通过。同一 PID 内观察到 `DESTROY` 和重新 `CREATE`，新 Activity 启动前两站 Cookie 长度仍为 109/134。重新打开搜索页显示 51 个书卡。 |
| 强制停止后重启 | 通过匿名会话验证。PID 从 24502 变为 25802，导航前 Cookie 长度仍为 109/134，搜索返回 51 个书卡。 |
| Cookie 持久化与域隔离 | 匿名状态通过。先访问主站时两站长度分别为 109/0，访问备站后为 109/134，重启前后长度一致。长度仅用于确认该 URL 有 Cookie，登录有效性须以站点界面判断。 |

| 连续搜索 20 次 | 20/20。在同一 WebView 对不同英文词逐次常规导航，20 次均显示 51 个书卡。 |
| 连续翻页 10 次 | 部分通过。使用第一阶段验证过的分页参数，依次请求 `python` 第 2–11 页。10/10 页号匹配，其中第 2、3 页有 51 个书卡，第 4–11 页均呈现空结果。真实有书目的连续翻页只验证了两次，空页原因待确认。 |
| 网页搜索筛选 | EPUB 筛选通过。展开网页搜索设置，选择扩展名 EPUB 并由网页表单提交。页面显示「使用筛选」，文章页签从 `150+` 变为 0，书籍仍有 51 张卡。年份、语言、内容类型和精确对应控件可见，本轮未逐项提交。 |
| 书籍详情 | 通过。从筛选结果点开首张书卡，采样确认进入 `/book/` 详情路由。 |
| 返回上一页 | 通过。第 11 页返回第 10 页并保留该页空结果。从详情返回时仍为 `python` 的筛选结果第 1 页，51 张书卡。详情进入前与返回后的滚动值分别为 345、397 CSS 像素，恢复到相近位置。 |

| 正常网页登录及退出 | 通过。用户手动登录后发起授权 EPUB 下载。重启后主站仍显示已登录。通过账户菜单退出后，主站重新显示「登录」。 |
| 正常网页下载 | 通过。用户在网页中触发 EPUB 下载并确认，WebView 收到 HTTPS 下载回调，Android 下载任务 627 生成文件。 |
| 文件名及完整性 | 下载落盘名为 `redirection.epub`，扩展名正确，但文件名未体现书名。回调与实存均为 211,708 字节，真机和主机的 SHA-256 一致，ZIP 18 个条目全部通过 CRC，首条目 `mimetype` 值为 `application/epub+zip`，实际样本通过现有 `EpubValidator`。 |
| 导入衔接及 API 下载 | 下载样本已验证能通过正式导入使用的校验器。其余导入步骤以静态分析确认接入点，本轮未写入书库。API 下载未发起线上取链或传输，见模块分析。 |

| 认证会话持久化与服务器隔离 | 通过本次两站观测。强制停止后 PID 从 25802 变为 31999，主站 Cookie 长度保持 204，并显示已登录界面。切到备站显示「登录」，切回主站仍可打开账户菜单。主站退出后 Cookie 长度降为 133，备站保持 134。匿名 Cookie 仍存在。 |
| 登录后补测分页 | `python` 第 4 页显示 51 张书卡，首屏 1,297 ms；第 11 页仍显示空结果，首屏 811 ms。登录状态影响本轮第 4 页可见结果，网站分页上限仍待确认。 |

### 性能口径

冷启动 `am start -W` 的 Activity 展示耗时 469 ms，探针内部从 `onCreate` 到建立 UI 为 98 ms。首次访问主域，访问验证页 2.336 秒可见，第二次可见回调 6.673 秒，9.409 秒采样确认已离开验证页。该次 `onPageFinished` 为 30.006 秒。应分别看 Activity 展示、真实内容和整页完成信号。

| 指标 | 实测 |
| --- | --- |
| 连续 20 次搜索首屏 | 最快 814 ms，中位数 1,068 ms，最慢 1,962 ms。 |
| 连续 10 次页码导航首屏 | 最快 686 ms，中位数 915.5 ms，最慢 1,687 ms。该统计含 8 次渲染空结果。 |
| WebView 内存及释放 | `dumpsys meminfo` 的单位为 KiB。第 10、20 次搜索后宿主 PSS 分别为 293,458、273,969。翻页及返回后宿主 251,365、关联渲染器 PID 25836 为 155,806。调用 `destroy()` 约 3 秒后，宿主降至 169,357，渲染器降至 108,512。两进程当时仍存活，单轮数据只能确认资源下降，无法确认长期无泄漏。 |
| 加载超时 | 通过受控连接测试。经 ADB 反向转发连接本机 TCP 监听器，监听器接受连接后停在 TLS 握手前。探针于 12,002 ms 停止首次加载并提示重试。 |
| 网络断开及恢复 | 受控连接断开通过。撤除转发后同一目标立即回报 `ERROR_CONNECT (-6)`，转回主站搜索在 2,414 ms 首屏可见，采样获得 51 个书卡。真机断开无线及移动数据后，无线 ADB 掉线，脚本未留存断网期间的 WebView 导航日志。用户恢复 Wi-Fi，重连后确认移动数据也恢复至测试前状态，再次搜索于 4,233 ms 采样到 51 个书卡。真实断网期间的错误表现未验证。 |

### 构建与发布检查

- `:app:assembleTtsTestDebug`、`:app:assembleProductionRelease`、`:app:lintTtsTestDebug` 通过。Lint 汇总为 0 错误、49 警告、3 提示。
- 正式构建产物 `app-production-release-unsigned.apk` 的 Manifest 以及 3 个 DEX 均未检出第一、二阶段探针的 Activity。测试 APK 已覆盖安装到已连接的测试版应用。
- `ZLibraryApiTest` 11 项通过。阅读模块单测任务为 UP-TO-DATE，其已有报告中的 `EpubValidatorTest` 5 项通过。线上下载可用性需用真实账号及文件另测。

### 实际运行日志

以下为 `adb logcat -d -s WebStageTwo:I` 摘录。连续搜索和翻页的逐次记录，以及会话测试的完整输出，存于本机 `.workbuddy/tmp/webview_stage_two.log`。

```text
11-25 01:31:27.173 24502 24502 I WebStageTwo: CREATE pid=24502 uiMs=98 webview=134.0.6998.135
11-25 01:31:27.182 24502 24502 I WebStageTwo: COOKIES event=create primaryLength=0 secondaryLength=0
11-25 01:31:29.480 24502 24502 I WebStageTwo: HTTP operation=home status=503 ms=2295
11-25 01:31:29.521 24502 24502 I WebStageTwo: VISIBLE operation=home host=zh.z-lib.gd ms=2336
11-25 01:31:33.858 24502 24502 I WebStageTwo: VISIBLE operation=home host=zh.z-lib.gd ms=6673
11-25 01:32:16.270 24502 24502 I WebStageTwo: SAMPLE operation=no_results ms=5172 {"cards":1,"challenge":false,"detail":false,"empty":true,"page":1,"ready":"interactive","scroll":0,"search":true}
11-25 01:32:35.571 24502 24502 I WebStageTwo: COOKIES event=create primaryLength=109 secondaryLength=134
11-25 01:32:42.184 25802 25802 I WebStageTwo: COOKIES event=create primaryLength=109 secondaryLength=134
11-25 01:34:14.286 25802 25802 I WebStageTwo: SAMPLE operation=search_20 ms=4085 {"cards":51,"challenge":false,"detail":false,"empty":false,"page":1,"ready":"complete","scroll":0,"search":true}
11-25 01:34:27.027 25802 25802 I WebStageTwo: SAMPLE operation=page_3 ms=4138 {"cards":51,"challenge":false,"detail":false,"empty":false,"page":3,"ready":"interactive","scroll":0,"search":true}
11-25 01:34:31.226 25802 25802 I WebStageTwo: SAMPLE operation=page_4 ms=4138 {"cards":0,"challenge":false,"detail":false,"empty":true,"page":4,"ready":"interactive","scroll":0,"search":true}
11-25 01:35:20.289 25802 25802 I WebStageTwo: TIMEOUT operation=timeout_fixture ms=12002
11-25 01:35:21.421 25802 25802 I WebStageTwo: NETWORK operation=disconnected_fixture code=-6 ms=6
11-25 01:36:31.876 25802 25802 I WebStageTwo: SAMPLE operation=recovery ms=68328 {"cards":0,"challenge":false,"detail":true,"empty":false,"page":1,"ready":"interactive","scroll":0,"search":false}
11-25 01:36:34.521 25802 25802 I WebStageTwo: SAMPLE operation=back ms=2125 {"cards":51,"challenge":false,"detail":false,"empty":false,"page":1,"ready":"interactive","scroll":397,"search":true}
11-25 01:38:13.901 25802 25802 I WebStageTwo: DOWNLOAD_CALLBACK host=dln1.ncdn.ec epubName=true mime=application/epub+zip length=211708
11-25 01:38:15.712 25802 25802 I WebStageTwo: DOWNLOAD_ENQUEUED id=627
11-25 01:39:15.905 25802 25802 I WebStageTwo: COOKIES event=command primaryLength=204 secondaryLength=109
11-25 01:39:16.404 31999 31999 I WebStageTwo: CREATE pid=31999 uiMs=67 webview=134.0.6998.135
11-25 01:39:21.847 31999 31999 I WebStageTwo: COOKIES event=command primaryLength=204 secondaryLength=109
11-25 01:40:41.581 31999 31999 I WebStageTwo: COOKIES event=command primaryLength=133 secondaryLength=134
11-25 01:43:24.420 31999 31999 I WebStageTwo: SAMPLE operation=device_network_restored ms=4233 {"cards":51,"challenge":false,"detail":false,"empty":false,"page":1,"ready":"interactive","scroll":0,"search":true}
```

网页内点击没有重置探针标签和计时。`operation=recovery` 下的详情采样耗时是距前一次显式导航的时间，不能作为详情首屏性能。

### 发现的问题

- 主、备域名首访均出现 HTTP 503 验证页，随后由网页自身脚本继续加载。首个 `onPageCommitVisible` 可能对应验证页或网络错误页。探针 12 秒计时仅针对显式导航等到首次可见的情况。正式容器需继续识别内容是否可操作，并对网页内点击、重定向和验证页等待分别覆盖。
- 空结果页仍可能带有书卡元素。页面访问、查询成功和存在可下载书目需要各自的判定依据。
- 本轮匿名 `python` 的第 4–11 页可打开但没有结果。筛选和返回可在同一 Activity 内复用网页状态。Activity 销毁后本轮重新导航到目标页，未验证冷启动自动恢复浏览栈。
- 下载样本不是以书名命名。正式接入时，可在用户确认后用详情标题与扩展名生成安全、可辨认的文件名，并保留已有的同名冲突处理。样本 SHA-256 为 `c421f5dbc8c111402c7ad479e96d1f6e99915b8d97a0fa38ab24ef44ea56f011`，该校验确认本机取得的文件与真机文件一致。

## 可复用模块与接口限制

| 现有模块 | 可复用范围及约束 |
| --- | --- |
| `ZLibraryConfigStore` | 正式设计可只读取得服务器候选。探针使用显式测试地址。网页会话与 API 凭证应分别处理。 |
| `EpubValidator`、`EpubImportFiles` | 对落盘后的 EPUB 复用文件名规范化、ZIP 结构、mimetype、container.xml、OPF 路径及解压限额检查。传输完整性还应核对预期字节数或可信摘要。 |
| `OnlineImportCoordinator`、`LibraryStore` | 可复用校验、封面和标题提取、同名冲突确认、正文放置与登记、导入失败后的文件处理。传入受控暂存文件、来源、账号范围及远程标识。 |
| `OnlineDownloadRepository`、`OnlineDownloadTask`、`DownloadManagerScreen`、`LibraryRefreshSignal` | 可复用任务记录、状态展示及导入后的书库刷新。任务归属需能表示网页来源与账号范围。 |
| `BookDownloadManager` | 可复用状态机、失败处理及有暂存文件时的导入重试。目前正常入口需要注册的 `CatalogBookProvider` 提供下载，并按自己的任务路径生成暂存文件。网页或系统下载结果若由外部先落盘，需要新增接收并校验完成文件的入口，或提供满足现有契约的网页下载适配器。 |
| `ZLibraryBookProvider.download`、`ZLibraryApi` | 现有 API 路径依赖 `AuthSession`、`id:hash`、`getDownloadLink` 返回的 HTTPS 下载地址及后续流式落盘。`getDownloadLink` 使用 API 会话，后续下载请求不携带该会话 Cookie。第一阶段的搜索端点拒绝记录仅覆盖搜索。API 单测覆盖本地协议和错误分类，线上结论需要合法账号及有权限的样本分别验证取链和文件传输。 |

## 正式集成建议

1. 完成结果表中尚无证据的项目后再决定是否开放入口。网页模式先以用户在 WebView 内操作为边界，将网页的搜索、筛选、详情、返回和登录会话保持在同一导航栈。
2. 新增网页容器和页面状态管理，分别识别正常内容、访问验证页、连接错误和超时。保留用户重试入口，区分首个可见页面和真实内容首屏。服务器切换时展示当前域和会话状态。
3. 网页下载先验证目标站点实际下发的链接类型。探针从 `DownloadListener` 接到 HTTPS EPUB 下载事件时，经用户确认交给 Android `DownloadManager`，请求未添加 WebView Cookie。若站点要求网页会话、POST 或 Blob，需要另行确认网站支持的正常交付方式。将已落盘文件接到现有校验、任务显示和导入链路，保留覆盖确认与失败重试。[Android WebView 下载回调](https://developer.android.com/reference/android/webkit/WebView#setDownloadListener(android.webkit.DownloadListener))
4. API 下载继续按原路径评估。网页走通后仍需单独取证 API 取链和传输能力，避免把网页搜索结果直接映射为 API 下载凭据。

第二阶段工作止于探针和报告。业务目录、搜索偏好、服务器配置、正式下载、百度网盘、WebDAV、阅读器和 TTS 代码均未改动。
