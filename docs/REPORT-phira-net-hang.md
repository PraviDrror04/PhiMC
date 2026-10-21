# Phira 在线谱面库「连续搜索卡死」根因与修复

> 现象：**第一次搜索勉强能出结果，之后每次搜索 / 翻页都卡在「正在请求 Phira 谱面库…」。**
> 结论：不是「慢」，是 **一个几十秒的请求 + 只有 1 条线程的池子 + 旧结果被序号丢弃** 三者叠出来的死循环。
> 修法：主域名换成官方在用的那个、请求加**硬截止 4.5 秒**、线程池 2 条、`loading` 无论如何都收回来。
>
> 改的文件：`PhiraApi.java`、`PhiraBrowser.java`（判定 / 计分、`core/` 一行未动）。
> 新增自测：`tools/phira/NetStressCheck.java`（真网络、headless）。

---

## 1. 复现（先复现再改）

`tools/phira/NetStressCheck.java` 走界面真正走的那条路
（`PhiraBrowser.search` → `PhiraApi.runAsync` → `PhiraApi.search`，再像渲染线程那样每 50ms 读一次
`loading()` / `error()` / `charts()` 快照），真网络、不打桩：

```
javac -encoding UTF-8 -cp "<gson>;<slf4j>" -d <out> PhiraApi.java PhiraChart.java PhiraBrowser.java NetStressCheck.java
java -cp "<out>;<gson>;<slf4j>" NetStressCheck 10 20000   # 10 次搜索，单次看门狗 20s
java -cp "<out>;<gson>;<slf4j>" NetStressCheck stale       # 只跑「连点 6 次」
```

修前的代码用同一份（`C:\dsh-work\phira-net\before-src`，取自上一次构建的镜像），只替换编译产物，两边的「修前 / 修后」表是同一台机器、同一时段、同一条网络打出来的。

**单域名探测（关键背景，实测 2026-10-20）**

| 域名 | 8 次单请求耗时（连接 5s / 请求 15s 口径，按发生顺序） |
|---|---|
| `https://phira.5wyxi.com`（官方 `API_URL`） | 32 / 32 / 32 / 33 / 35 / 38 / 46 / 157 ms |
| `https://api.phira.cn`（我们原来的主域名） | **5.0s 连接超时** / 4.9s / 2.7s / 1.7s / 4.3s / 1.7s / 7.6s / **27.2s** |

同一台机器上另两次观测到 `api.phira.cn` 单次 **17.1s** 和 **65.0s**（都在配了 15s 请求超时的前提下）。
65 秒那次出现在下面「10 次搜索」那一轮的开头（单域名探测打的就是它）—— 也就是用户点第一次搜索时，
主域名可能已经先吃掉了一分钟。

---

## 2. 根因（逐条对照「高度怀疑的点」）

| # | 怀疑 | 结论 | 证据 |
|---|---|---|---|
| 1 | 单线程池 + 某次请求没有超时 → 线程被永久占用 | **成立（主因）** | 修前 `PhiraApi.java:86-87` 是 `Executors.newSingleThreadExecutor`；卡住时线程栈：`phimc-phira-api-1` → `CompletableFuture.get` ← `HttpClientImpl.send:902`，而 `HttpClient-1-SelectorManager` 在 `WEPoll.wait`（**连接活着，服务端不回**）。更致命的是「请求超时」并不封顶：配 15s 的请求实测跑过 **27.2s / 65.0s** |
| 2 | 缓存 key 冲突（`onlyStable` 拼错 → 命中一个「永远 LOADING」的缓存项） | **不成立** | key 是 5 段 `搜索词 \u0001 排序 \u0001 页 \u0001 每页 \u0001 onlyStable`（`PhiraApi.java:259`）。实测切开关：`开=632 / 关=9737`，切回「开」5ms 命中，没串。缓存里只放 `ok()` 的结果，失败不缓存，不可能出现「缓存里的 LOADING」——`ListResult` 压根没有 LOADING 这个状态 |
| 3 | 请求序号逻辑写错 → 新请求的结果被当成过期丢掉 | **不成立，但它是放大器** | `PhiraBrowser.java:134` `final long mySerial = ++serial;` 在渲染线程自增，回调 `if (mySerial != serial) return;` 判定正确。连点 6 次（每 40ms 一次）修后终态 `query=final`、旧结果没把界面刷回去。**但它和单线程池叠加成真 bug**：被丢弃的旧请求仍然占着那唯一一条线程 → 越点越堵 |
| 4 | 异常被吞 → 状态停在 LOADING | **成立（独立路径）** | 修前 `PhiraBrowser.java:132-145` 的回调里 `loading = false` 是裸语句，`PhiraApi.search` 一旦抛非 `PhiraFail` 的异常，`PhiraApi.runAsync` 只 `LOG.warn`（而 SLF4J 无 provider 时连日志都没有）→ `loading` 永远 true，界面上就是永久「搜索中…」 |
| 5 | `HttpClient` 连接泄漏（`ofInputStream` 没 close） | **不成立** | 搜索走 `ofString` / `ofByteArray`（JDK 自己读完就关）；下载 `PhiraApi.java:513` 是 try-with-resources，非 200 / 超限时 `closeQuietly(resp.body())` |
| 6 | DNS / TLS 握手阻塞（没有 connectTimeout） | **半成立，但方向不同** | `connectTimeout(5s)` 是生效的（实测 `HttpConnectTimeoutException: HTTP connect timed out` 正好 5.0s，DNS 也是毫秒级）。真正卡住的是**「TCP 连上了、HTTP 就是不回」**，`connectTimeout` 管不到这一段，只能靠请求超时——而请求超时不是硬上限（见 #1） |

**串起来就是用户看到的现象**：

1. 第一次搜索：先打 `api.phira.cn`，等 5~65 秒才放弃，再打 `5wyxi` 出结果 → 「第一次访问勉强」。
2. 之后每次搜索：又要在 `api.phira.cn` 上白等一遍；用户等不及再点，每次点击都往**同一条线程**的队列里
   追加一个几十秒的活；旧请求回来时序号已经变了、结果被丢 → 队列永远排不完 →
   **「后续就一直卡在 loading」**。

修前实测最坏一轮：10 次搜索 **10 次全部卡死（单次 > 20s）**，整轮 201.7 秒还没走完；另一轮 10 次里 8 次超过 5 秒、总计 110 秒。

---

## 3. 修法（改动清单）

### 3.1 `PhiraApi.java`

| 位置 | 改动 |
|---|---|
| `:90` | `BASES = {"https://phira.5wyxi.com", "https://api.phira.cn"}` —— 主备对调，理由见 §4（官方客户端 `client.rs:26` 用的就是 5wyxi） |
| `:103` | 新增 `SEARCH_BUDGET_MS = 4_500`：一次搜索（含换备用域名的那一次）的**总预算** |
| `:106` | 新增 `IMAGE_BUDGET_MS = 8_000`：曲绘单次预算 |
| `:114` | 新增 `BASE_COOLDOWN_MS = 60_000`：失败过的域名冷却 60 秒 |
| `:134-136` | `API_THREADS = 2`、`API_QUEUE = 8` |
| `:147-156` | `API_POOL` 从「单线程 + 无界队列」换成 `ThreadPoolExecutor(2, 2)` + `ArrayBlockingQueue(8)`；满了**丢队首**（最老的请求，结果本来就过期）再重试——新请求必须进得来 |
| `:191-207` | `runAsync` 改成返回 `boolean`；任务开头 `Thread.interrupted()` 清掉上一次残留的中断标记（否则池线程里 `HttpClient` 一进去就抛 `InterruptedException`，等于整条线程废掉）；`Throwable` 照旧吃掉 |
| `:253-281` | `search` 用 `try/catch (Throwable)` 兜底：网络 / 解析层任何意外都变成 `ListResult.fail("搜索失败：…")`，**保证调用方一定拿到结果**（cache 键在 `:259`，兜底在 `:266-274`） |
| `:283-319` | `requestSearch` 带 `deadline`；每次尝试前检查剩余预算（< 200ms 就不再起新请求）；失败 `markBaseDown`、成功 `markBaseUp` |
| `:379-401` | 域名健康度：`isDown` / `markBaseDown` / `markBaseUp` / `orderedBases()`（稳定排序，「先健康的、后冷却中的」，**两个都挂时保持原顺序，备用域名照样会被试到**） |
| `:579-600` | `HttpRequest.timeout = min(15s, 剩余预算)`，`connectTimeout(5s)` 保留 |
| `:602-625` | 新增 `sendWithDeadline`：`sendAsync(...).get(remaining)` —— 这一层才是**硬上限**（`HttpRequest.timeout` 不是） |
| `:627-640` | `getString` / `getBytes` 都走 `sendWithDeadline`（曲绘 8s、搜索 4.5s） |
| `:678-684` | `clearCache()` 顺带清域名健康度（自测用） |

### 3.2 `PhiraBrowser.java`

| 位置 | 改动 |
|---|---|
| `:136` | `PhiraApi.runAsync(...)` 的返回值要检查：没入队就**同步**把 `loading` 放掉 + 中文原因（线程池已关闭的极端情况） |
| `:138-144` | 回调里 `catch (Throwable)` → 兜成 `ListResult` 失败结果（`PhiraApi` 保证不抛，这是第二道闸） |
| `:146-163` | 写状态整段包在 `try/catch/finally` 里，**`loading = false` 放进 `finally`**：无论成功、失败、数据炸了，状态都收得回来 |
| `:165-169` | 入队失败路径：`error = "搜索请求没能提交（后台线程池已关闭），请重启游戏后再试"` |

保留不变的：**双域名切换（第一个失败切第二个）**、**60 秒缓存**（键仍带 `onlyStable`）、
**请求序号丢过期结果**、失败不缓存、判定 / 计分零改动。

---

## 4. 对照开源源码（`C:\dsh-work\phira-src`）

本地有官方 Phira 仓库的完整克隆（`prpr/` 是核心引擎，`phira/` 是客户端，`phira-monitor/` 是监控工具）。
**网络层不在 prpr 里**——prpr 是纯渲染 / 判定引擎（Cargo.toml 里没有 reqwest），Phira 客户端用 Rust + 官方 HTTP API，
客户端实现就在 `phira/src/`。下面每一条都标了「源码依据」还是「我们自定」。

### 4.1 有源码依据的

| 我们做的事 | 源码依据 | 说明 |
|---|---|---|
| 主域名 `https://phira.5wyxi.com` | `phira/src/client.rs:26` `pub const API_URL: &str = "https://phira.5wyxi.com";` | 官方客户端唯一在用的地址。`api.phira.cn` 只出现在 `phira-monitor/src/scene.rs:42`（官方监控工具）——它是套 CDN 的旧入口，这也是它慢的由来 |
| 搜索路径 `/chart` + `search` / `order` / `page` / `pageNum` / `division` | `client.rs:189-191`（`API_URL + path`）、`client.rs:478-535`（`QueryBuilder`：`order` @493、`search` @503、`pageNum` @508、`page = page.unwrap_or(0)+1` @524 —— 即 **page 从 1 开始**）、`client.rs:530`（`/{QUERY_PATH}`） | 我们的 URL 拼法（`PhiraApi.java:285-291`）与之一致：`page` 1 起、`pageNum` 每页条数 |
| 排序值 `-rating` / `-updated` | `phira/src/page/library.rs:284-298`（`rating`/`updated`/`name`/`difficulty`，倒序加 `-` 前缀） | 一模一样 |
| 响应是 `{ count, results }` | `client.rs:525-534`（`struct PagedResult { count: u64, results: Vec<T> }`） | 我们 `PhiraApi.parseList` 先读 `results`，兼容 `result`（两个镜像的字段名都见过） |
| 非 2xx 时读 body 里的 `{ error, code }` 再报错 | `client.rs:107-123`（`recv_raw`） | 我们只做状态码 → 中文（`httpMessage`），不解析 `code`/`error`：我们没有登录态 / 业务错误码语义，中文提示对玩家更有用 |
| 「新请求作废旧请求」 | `library.rs:862` `self.online_task = None;` + `library.rs:900-911`（每帧 `task.take()` 取结果） | 官方是「丢掉旧的 future」，我们是「序号对不上就丢结果」，等价 |
| 后台跑网络、UI 每帧只读快照 | `prpr/src/task.rs:8-44`（`Task` = `tokio::spawn` + `Mutex<Option<T>>`，UI 侧 `take()`/`get()`）+ `library.rs:324` `self.online_task = Some(Task::new(async move { … }))` | 我们的 `runAsync` + volatile 快照是同一套思路的 Java 版 |
| `stable` 过滤的语义（「仅上架」= 已上架 + 已过审） | `library.rs:327-338`（官方用 `stableRequest=true` / `reviewed=false` + `stableRequest=false`） | 见 4.3：参数名不同但语义对齐，我们额外在界面层再筛一遍 |

### 4.2 源码里**没有**、属于 Java / 我们的实现细节

| 我们做的事 | 源码里没有的原因 / 我们的理由 |
|---|---|
| `connectTimeout(5s)` + 每请求 `timeout(15s)` + 4.5s 总预算 | `client.rs:28-41` 的 `basic_client_builder()` **只设了 redirect policy，一个超时都没设**（reqwest 默认也不超时）。官方不需要：`phira/Cargo.toml:80` 是 `tokio` 的 `rt-multi-thread`，每个请求是独立 task，慢请求不会挡住别人。我们的搜索线程池是**有界**的，必须自己给请求设上限，否则一条慢连接 = 整个搜索功能停摆 |
| 2 条线程 + 8 格队列 + 丢最老的请求 | 官方是 tokio 多线程 + 无界 task，不存在「排队」。Java 版要有界，就得有排队策略；旧请求的结果反正会被序号丢掉，所以丢它不丢新的 |
| 域名冷却 60 秒 | 官方只有一个域名，不存在主备。冷却只是把「白等一个超时」摊薄（60s 内只踩一次），不是拉黑 |
| 60 秒搜索结果缓存 | 官方是**按对象 id 的 `ObjectMap` 缓存**（`client.rs:193-251`，`load`/`fetch`），没有 TTL、也不缓存「列表页」。60s 搜索缓存是需求（翻来翻去不重复打接口），键里带 `onlyStable` 是我们自己的取舍 |
| `HttpClient` 单例 / 连接复用 / 响应体 try-with-resources | Java 侧的常识（`HttpClient` 线程安全、连接池复用），源码是 reqwest，没有对应写法 |
| 429 只提示中文、不做自动重试 | 源码里没有 HTTP 重试 / 退避逻辑（只有 `client.rs:89` 把 `RATE_LIMITED` 映射到 429 这个错误码）。我们照它：**不自动重试**，只切一次备用域名 + 中文提示，避免把限流踩得更狠 |
| 双域名（主备切换） | 官方没有；`api.phira.cn` 只被官方监控工具用。这是需求 3，我们自定，并保留「第一个失败切第二个」 |

### 4.3 一处「参数名不同、语义对齐」的说明（`stable` vs `stableRequest`）

官方「稳定」页签用的是 `stableRequest=true`（`library.rs:335`），显示未过审用 `reviewed=false&stableRequest=false`（`:337`）。
我们界面上的开关叫「仅上架」，含义是 `stable && reviewed`，请求里带 `stable=true&reviewed=true`。
实测服务端两个参数都认（`https://phira.5wyxi.com/chart?...&division=regular`）：

| 参数 | count |
|---|---|
| 无 | 9737 |
| `stable=true&reviewed=true`（我们在发的） | **632** |
| `stableRequest=true`（官方「稳定」页签在发的） | 200 |
| `reviewed=true` | 9420 |
| `type=0` | 582 |

所以保持现状：**请求参数照旧**（改了会动到界面语义和「仅上架」的总数口径），
并在 `PhiraBrowser.keepStable` 里再筛一遍兜底。这一条属于「服务端参数实测对齐，不是照抄源码」。

---

## 5. 修前 / 修后「连续 10 次搜索」

同一台机器、同一条网络、同一个自测程序（`NetStressCheck 10 20000`），关键词每次不同
（空 / R.I.P. / eicateve / Aoharu / IN / Kyouki / piano / shadow / Lyrith / Sekai），每次之间 150ms。

### 5.1 修前（主域名 `api.phira.cn`，单线程池）

**run A —— 10 次全部卡死（看门狗 20s 到点仍未返回）**

| # | 关键词 | 开始(ms) | 结束(ms) | 用时 | 终态 | 总条数 | 本页 |
|---|---|---|---|---|---|---|---|
| 1 | (空) | 0 | 20031 | 20031ms | **STUCK** | 0 | 0 |
| 2 | R.I.P. | 20184 | 40229 | 20045ms | **STUCK** | 0 | 0 |
| 3 | eicateve | 40379 | 60382 | 20003ms | **STUCK** | 0 | 0 |
| 4 | Aoharu | 60534 | 80571 | 20037ms | **STUCK** | 0 | 0 |
| 5 | IN | 80722 | 100756 | 20034ms | **STUCK** | 0 | 0 |
| 6 | Kyouki | 100907 | 120941 | 20034ms | **STUCK** | 0 | 0 |
| 7 | piano | 121093 | 141103 | 20010ms | **STUCK** | 0 | 0 |
| 8 | shadow | 141255 | 161289 | 20034ms | **STUCK** | 0 | 0 |
| 9 | Lyrith | 161440 | 181446 | 20006ms | **STUCK** | 0 | 0 |
| 10 | Sekai | 181597 | 201598 | 20001ms | **STUCK** | 0 | 0 |

小计：**卡死 10 次，整轮 201.7 秒**。同一轮的单域名探测里，`api.phira.cn` 的单次请求 **65.0 秒**才回来。

**run B —— 8 次超过 5 秒（同一份代码，网络状况稍好时）**

| # | 用时 | 终态 | | # | 用时 | 终态 |
|---|---|---|---|---|---|---|
| 1 | 19449ms | OK | | 6 | 2926ms | OK |
| 2 | 8287ms | OK | | 7 | 6511ms | OK |
| 3 | 11711ms | OK | | 8 | 13373ms | OK |
| 4 | 16361ms | OK | | 9 | 2875ms | OK |
| 5 | 11870ms | OK | | 10 | 15127ms | OK |

小计：**8/10 超过 5 秒，整轮 110.0 秒**。

**修前「连点 6 次」**（每 40ms 一次，`NetStressCheck stale`）：

```
终态：query=final loading=true total=0 err=
✗ 连点之后 loading 收不回来        ← 用户报的「一直卡在 loading」原样复现
```

### 5.2 修后（主域名 `phira.5wyxi.com`，2 线程，4.5s 硬预算）

| # | 关键词 | 开始(ms) | 结束(ms) | 用时 | 终态 | 总条数 | 本页 | 错误 |
|---|---|---|---|---|---|---|---|---|
| 1 | (空) | 0 | 203 | **203ms** | OK | 632 | 20 | |
| 2 | R.I.P. | 354 | 405 | **51ms** | OK | 7 | 7 | |
| 3 | eicateve | 556 | 607 | **51ms** | OK | 1 | 1 | |
| 4 | Aoharu | 758 | 809 | **51ms** | OK | 2 | 2 | |
| 5 | IN | 961 | 1012 | **51ms** | OK | 63 | 20 | |
| 6 | Kyouki | 1162 | 1214 | **52ms** | OK | 0 | 0 | |
| 7 | piano | 1365 | 1416 | **51ms** | OK | 0 | 0 | |
| 8 | shadow | 1568 | 1620 | **52ms** | OK | 6 | 6 | |
| 9 | Lyrith | 1772 | 1823 | **51ms** | OK | 0 | 0 | |
| 10 | Sekai | 1973 | 2024 | **51ms** | OK | 0 | 0 | |

小计：**卡死 0 次、超过 5s 0 次，整轮 2.17 秒**（第 1 次 203ms 是类初始化 + TLS 握手，之后稳定 50ms 左右）。

同时验证（同一轮里）：

| 项 | 结果 |
|---|---|
| 60 秒缓存 | 同一搜索词第二次 **5ms**（不走网络） |
| 「仅上架」开关 | 开=632 / 关=9737（34ms），切回开 **5ms 命中缓存**，没串 key |
| 连点 6 次（每 40ms） | 终态 `query=final loading=false total=3`，旧结果没把界面刷回去 |
| 线程池满（12 个占用任务） | 搜索 **1670ms** 收尾，state=OK，之后恢复 50ms |

---

## 6. 失败路径的处理

| 场景 | 现在的行为（实测） | 代码 |
|---|---|---|
| 主域名超时 / 连不上 | 立刻切备用域名（最多切一次）；两个都挂才失败。失败的域名冷却 60 秒、期间排到最后 | `PhiraApi.java:292-316`、`:379-401` |
| 单次请求超时（黑洞地址） | **4.52 秒**返回，state=FAILED，`error=请求超时（4.5 秒）：10.255.255.1` | `:602-625` |
| HTTP 错误 | 4xx / 5xx / 429 都变中文：`资源不存在（HTTP 404）` / `服务器错误（HTTP 500），稍后再试` / `请求过于频繁（HTTP 429），等一分钟再试`。实测 404 分支 **101ms** FAILED | `:642-648`（`httpMessage`） |
| 线程池满 | 丢队首（最老的请求）让新请求进来；新请求照样跑完、状态照样收尾（实测 1670ms） | `:147-156` |
| 线程池已关闭 / 拒绝 | `runAsync` 返回 false → `PhiraBrowser` **同步**把 `loading` 放掉并写中文原因 | `PhiraBrowser.java:165-169` |
| 回调里抛异常 | `catch (Throwable)` → 失败结果 + `finally { loading = false; }` | `PhiraBrowser.java:138-163` |
| 解析失败 / 返回不是 JSON | `ListResult.fail("接口返回解析失败：…")`，失败**不缓存**，再点搜索会重试 | `PhiraApi.java:266-274`、`:321-350` |
| 失败之后 | 下一次搜索不受影响：实测失败后立刻 `state=OK total=632`、**50ms** | §5.2 表 |

设计口径：**一次失败只影响这一次搜索**——不重试、不缓存失败、不污染域名健康度之外的状态。

---

## 7. 编译 + 回归

### 7.1 编译（Gradle 9.8.2 + JDK 25）

```
gradle -p C:\dsh-work\phimc\mod :1.21.1:build --no-daemon --console=plain
> Task :1.21.1:compileJava
> Task :1.21.1:remapJar
> Task :1.21.1:build
BUILD SUCCESSFUL (首轮全量 1m 21s；改完注释后的增量 18s)
```

0 error。（构建脚本要求先把工程镜像到纯 ASCII 路径 `C:\dsh-work\phimc` 再编，`mod/build.ps1` 里就是这个流程；
本机 PowerShell 执行策略禁止跑 `.ps1`，这次是等价的内联命令跑的同一步。）

产物：`mod/dist/phimc-1.21.1.jar`（641,374 字节，SHA256 `2E8106CC…E060C05`）
已覆盖到 `D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`（**哈希与构建产物一致**）。

反向确认过 jar 里是新代码：`javap` 能看到 `sendWithDeadline` / `orderedBases` / `SEARCH_BUDGET_MS`，
`PhiraBrowser.search` 的 `Exception table` 里有 `any`（catch-all）处理器 —— 也就是 `finally { loading = false; }` 那一层。

### 7.2 StressTest（判定 / 计分没动）

**本任务**一个字节都没碰 `core/`（只改了 `PhiraApi` / `PhiraBrowser` / `tools/phira/*` / 文档）。
用**当前仓库的** `core/src` 重新编译后跑两条官方谱面：

```
谱面：1285 音符 / 24 判定线 / 117.7s
[自动播放] 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 最大连击=1285/1285
[不操作]   分数=0 P=0 M=1285/1285
[±60ms]    分数=509829 评级=F P=484 G=358 B=45 M=398 最大连击=101/1285

谱面：1418 音符 / 24 判定线 / 150.9s
[自动播放] 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 最大连击=1418/1418
[不操作]   分数=0 P=0 M=1418/1418
[±60ms]    分数=341844 评级=F P=451 G=131 B=21 M=815 最大连击=22/1418
```

自动播放 **1000000 / 0 Miss**（两条谱面），与 `REPORT-ui-lock-cover.md` 里记录的数值逐字节一致（509829 / 341844）。

### 7.3 `PhiraApiCheck`（原有离线自测，9 段）

**全部通过 ✓**：搜索 / 按谱师搜 / 60 秒缓存（第二次 0ms）/ 主域名失败切备用 / 曲绘缩略图 /
文件名安全化 / 下载落盘判重 / 磁盘错误中文提示 / 「仅上架」开关（632 ↔ 9737）。

> 只改了它的第 4 段：原来把期望写死成「`BASES[0]` 挂了就靠 `BASES[1]` 成功」，
> 现在主备对调后 `BASES[1]` 是慢的那个，这条断言会变成测网络运气。
> 改成显式把 `[0]=127.0.0.1:1`（必挂）、`[1]=phira.5wyxi.com`（官方地址），
> 测的仍然是「第一个失败会切第二个」这条逻辑本身。

### 7.4 `NetStressCheck`（新增，5 段）

**全部通过 ✓**（§5.2 的表就是它的第 1 段输出）。

---

## 8. 没做与原因

1. **没做自动重试 / 指数退避**：官方源码里也没有（只有 `RATE_LIMITED` 错误码）。有 60 秒缓存 +
   切一次备用域名已经够；自动重试只会在 429 上踩得更狠。
2. **没把 `api.phira.cn` 从 `BASES` 里删掉**：需求要保留双域名。现在的做法是「备胎 + 60 秒冷却」，
   它真挂了不影响正常路径（正常一次都不打它），它好了也能被用上（两个都挂时仍会试它）。
3. **没做 DNS 预解析 / 强制 IPv4 / 自定义 Resolver**：DNS 本身是毫秒级（实测 `api.phira.cn` 解析正常，
   4 个 A/AAAA 记录都在），真正的问题是「TCP 连上、HTTP 不回」，加 DNS 侧手段不解决问题。
4. **`future.cancel(true)` 不保证立刻掐断底层连接**：Java 的 `HttpClient` 没有「中断交换」的公开 API，
   到点后调用方撒手，底层交换等它自己的定时器收尾（它自带的请求超时 ≤ 我们的预算，所以不会越积越多）。
   要做到「物理掐断」只能换 HTTP 实现，超出这次范围。
5. **没在真实游戏客户端里点一遍**：headless 复现用的是界面同一条代码路径（`PhiraBrowser` → `runAsync` → `PhiraApi`），
   UI 侧只读 volatile 快照（这次没改渲染代码）；jar 已覆盖安装，实机验收需要启动客户端，这次没做。
6. **只编了 1.21.1**（要求的验证版本）：改动全在 `Phira*` 这几个**纯 Java、零 MC 依赖**的类里，
   没有 `//?` 版本条件注释，19 个版本共用同一份源码，其他版本只是重新走一遍 Stonecutter。
7. **没动界面的按钮文案 / 布局**：`syncButtons` 里「搜索中禁用翻页」的既有行为保留（`loading` 现在收得回来了，
   不会再出现「翻页按钮永远灰着」）。

---

## 附注：这个工作区当时有别的任务在并发改文件

做完之后核对 mtime 发现，本次会话期间工作区里还有**别的**改动（不是本任务做的）：
`core/Engine.java`（23:35:41）、`core/RpeParser.java`（23:24）、`core/ChartParser.java`（23:23）、
`mod/.../PlayScreen.java`（23:22）、`mod/stonecutter.gradle`（23:25）、`tools/chart/*`（23:11~23:36）、
`setup/PhiMCSetup.exe`（23:16）、`docs/REPORT-multiversion.md`（23:18）。

对本报告的影响与处置：

- `gradle :1.21.1:build` 的产物是 **23:37:26** 那个树状态编出来的（包含上面的并发改动，编译 0 error）。
- 为了确认判定 / 计分没受影响，**在并发改动之后**又用当前 `core/src` 重编重跑了一遍 StressTest：
  仍然是 **1000000 / 0 Miss**，±60ms 抖动的分数（509829 / 341844）与旧报告**逐位一致** → 判定 / 计分契约没被这一波并发改动破坏。
- 我改动的文件只有 4 个：`PhiraApi.java`、`PhiraBrowser.java`、`tools/phira/NetStressCheck.java`（新增）、
  `tools/phira/PhiraApiCheck.java`（只改第 4 段的数据准备），加 2 份文档。
