# 在线谱面库（Phira）接入报告

需求：在选曲界面加「在线」页签，接 Phira 社区谱面库（搜索 + 下载 + 自动导入），
网络全部后台线程、失败切备用域名、结果缓存、合规声明。

---

## 0. 一句话结论

`SongSelectScreen` 顶部多了 **本地 / 在线** 两个页签；在线页可以按曲名/谱师搜索、按评分/最新排序、
分页浏览，每行有 20px 曲绘缩略图和「下载」按钮；**「仅上架」开关默认开**（只显示 `stable && reviewed`），
关掉后未上架 / 未过审的也返回并灰色标注；下载在后台线程，
落盘 `config/phimc/songs/{id}_{安全化曲名}.pez`，成功后自动 `rescan()`、切回本地页签并选中新谱面。
三个版本（1.20.1 / 1.21.1 / 1.21.11）`build` 全绿，两条 StressTest 仍 `1000000 / 0 Miss`。

---

## 1. 新文件 / 改动文件（:行）

### 新文件

| 文件 | 行数 | 作用 |
|---|---|---|
| `mod/src/main/java/dev/dsh/phimc/client/PhiraChart.java` | 113 | 一条谱面的纯数据（gson 反序列化目标）；`isPublicStable()` 判断「已上架 + 已过审」 |
| `mod/src/main/java/dev/dsh/phimc/client/PhiraApi.java` | 509 | 网络层：`HttpClient` 单例（connect 5s / 请求 15s）、双域名切换、搜索 60 秒缓存、谱面包流式下载（进度 + 中文错误）；`search(..., onlyStable)` 决定请求里带不带 `stable=true&reviewed=true` |
| `mod/src/main/java/dev/dsh/phimc/client/PhiraCovers.java` | 222 | 曲绘缩略图：后台下载 + WebP→PNG 缩放 + LRU（24 张）+ 每帧最多注册 2 张贴图 |
| `mod/src/main/java/dev/dsh/phimc/client/PhiraBrowser.java` | 175 | 在线页状态机：query/order/page/pageSize/loading/error 的 volatile 快照 + 请求序号丢弃过期结果 + **`onlyStable` 开关与过滤** |
| `mod/src/main/java/dev/dsh/phimc/client/PhiraDownloader.java` | 274 | 下载任务（2 条 daemon 线程）、文件命名、判重、进度/失败状态 |
| `tools/phira/PhiraApiCheck.java` | 242 | 离线自测：真网络搜索 / 缓存 / 域名切换 / 缩略图 / 命名 / 下载 / 判重 / 磁盘错误 / **仅上架开关** |
| `tools/phira/ImportCheck.java` | 61 | 离线自测：下载下来的 `.pez` 能不能被 `SongEntry.scan` 认成可玩谱面 |
| `tools/ui/online-layout.ps1` | 261 | 在线页版式核对：两个分辨率打印坐标表 + 断言不重叠 + 出预览 PNG（`C:\dsh-work\ui-check\online-*.png`） |
| `lib/webp-0.2.0.jar`（+ `lib/webp-LICENSE.txt`） | 126 KB | 纯 Java WebP 解码（JWebP，MPL-2.0，无 native、无传递依赖）——见第 6 节「为什么多一个依赖」 |

### 改动的文件

| 文件 | 位置 | 改了什么 |
|---|---|---|
| `client/SongSelectScreen.java` | 1463 行（原 777 行） | 新增页签 + 在线页 + 「仅上架」开关（见下表） |
| `client/Session.java` | `:199` | 新增 `public Path songsDir()`（只读访问器，不碰判定/计分） |
| `client/CoverCodec.java` | `:10-11`, `:50-60`, `:82`, `:105`, `:140`, `:175-205`, `:244` | 新增 `Format.WEBP` / `isWebp` / `toPngThumbnail` 的 WebP 分支 / `decodeWebp` |
| `mod/build.gradle` | `:40-43`（依赖）、`:94-103`（打包） | 引入并摊平 `lib/webp-0.2.0.jar`，附 `LICENSE_jwebp` |
| `docs/REPORT-phira-online.md` | — | 本文件 |

`SongSelectScreen` 里新增的位置（行号按当前文件）：

| 行 | 内容 |
|---|---|
| `:151` | `ONLINE_PAGE_MAX = 30`（接口能接受的最大页大小；一页铺满一屏） |
| `:156` | `private enum Tab { LOCAL, ONLINE }` |
| `:369` | `stableBtnX()` —— 「仅上架」按钮左边界（右边界贴 `W-PAD`） |
| `:387` | `onlinePerPage()` —— **页大小 = 一屏能放几行**（480→14，720→23），翻页不出滚动条 |
| `:500-540` | `init()` 里建页签 / 搜索框 / 搜索·排序·仅上架按钮 / 每行「下载」按钮 |
| `:544` | `syncButtons()` —— 每帧按「页签 + 当前页」同步按钮可见性/可用性（不读盘、不发请求） |
| `:613` | `setTab()` —— 切页签，不重建界面；回本地时放掉搜索框焦点 |
| `:639` / `:648` | `doSearch()` / `toggleOrder()` |
| `:673` / `:685` | `stableLabel()` / `toggleOnlyStable()` —— 「仅上架」文案与切换（切完立刻重搜） |
| `:703` / `:714` | `prevPage()` / `nextPage()` —— 本地翻页 / 在线翻页共用底部按钮 |
| `:851` | `downloadRow()` —— **唯一下载入口**（按钮 / 再点选中的行 / 回车都走这里） |
| `:886` | `pollDownload()` —— 渲染线程轮询任务终态 |
| `:913` | `finishDownload()` —— `rescan()` + 切回本地 + 选中新谱面 |
| `:1009` / `:1027` | `clickOnlineRow()` / `scrollOnline()` |
| `:1206` / `:1236` | `drawOnlineOverlay()`（状态 + 版权声明）/ `drawOnlineList()`（缩略图 + 曲名 + 难度 + 评分 + 谱师 + 下载槽位 + 未上架灰色标记） |

「仅上架」相关的改动（本轮新增）：

| 文件:行 | 改了什么 |
|---|---|
| `PhiraApi.java:153` | 保留 4 参 `search(...)`（= 仅上架开），内部转调 5 参重载 |
| `PhiraApi.java:169` | 新增 `search(q, order, page, pageSize, onlyStable)`：缓存键带上 onlyStable；只有开的时候请求里带 `stable=true&reviewed=true` |
| `PhiraApi.java:191` | `requestSearch(..., onlyStable)` 拼 query；关掉时不带 stable/reviewed |
| `PhiraApi.java:237-239` | `parseList` **不再硬过滤** `isPublicStable()`（只丢 `id <= 0` 的坏数据），是否显示交给界面层 |
| `PhiraBrowser.java:45` | `private static volatile boolean onlyStable = true`（默认开，本局有效） |
| `PhiraBrowser.java:48` / `:53` | `onlyStable()` / `setOnlyStable()` 读写开关 |
| `PhiraBrowser.java:154` | `keepStable()` —— 开的时候在快照里再筛一遍（服务端忽略参数也拦得住） |
| `SongSelectScreen.java:509` | 建「[✓] 仅上架」按钮（和「排序」同尺寸） |
| `SongSelectScreen.java:673/685` | `stableLabel()` / `toggleOnlyStable()`：切开关 → 从第 1 页重搜 → 底部提示 |
| `SongSelectScreen.java:1236` | `drawOnlineList()` 里未上架行：曲名/元信息灰一档 + 难度评分后挂 `[未上架]` |
| `SongSelectScreen.java:1155` | 副标题按开关显示「只显示稳定且已审核」/「含未上架 / 未过审，灰色标注」 |

**判定 / 计分 / 音符绘制一行没改**：`core/` 零改动，`PlayScreen` / `Engine` / `Judgement` / `Session.tick` 都没碰；
`Session` 只多了一个只读的 `songsDir()`。

---

## 2. 线程模型（网络线程 ⟷ UI 更新 的分界）

```
 ┌────────────────────────────── 渲染线程（客户端主线程） ──────────────────────────────┐
 │ SongSelectScreen.render()                                                            │
 │   pollDownload()          ← 只读 Job.state / received（volatile）                     │
 │   syncButtons()           ← 只读 PhiraBrowser.charts/total/loading/error（volatile）   │
 │   PhiraCovers.beginFrame()                                                           │
 │   super.render() → renderBackground() → drawOnlineList()                             │
 │        PhiraCovers.draw(url)  ← 命中缓存就画；未命中只「发起下载」并画占位块（不等待）  │
 │        ↑ PhiraCovers.get() 从 READY 取走后台缩好的 64px PNG，注册贴图（每帧 ≤2 张）    │
 │   finishDownload()        ← 任务变 DONE 时：s.rescan() + 切页签 + 选中               │
 └───────────────┬───────────────────────────────┬──────────────────────────────────────┘
                 │ ① 发起（非阻塞）               │ ② 读 volatile 快照（无锁、无 join）
 ┌───────────────▼───────────────┐   ┌───────────▼──────────────────────────────────────┐
 │ PhiraApi.API_POOL（1 daemon） │   │ PhiraBrowser：volatile charts/total/loading/error │
 │   GET /chart?...              │──▶│ 每次请求自增 serial，晚到的旧结果直接丢弃          │
 │   失败 → 备用域名重试一次      │   └──────────────────────────────────────────────────┘
 └───────────────────────────────┘
 ┌───────────────────────────────┐   ┌──────────────────────────────────────────────────┐
 │ PhiraCovers POOL（2 daemon）  │   │ READY: ConcurrentHashMap<url, 64px-PNG 字节>      │
 │   下载曲绘 → WebP 解到 64px    │──▶│ （只放字节，不碰 GL；贴图注册留给渲染线程）        │
 │   → PNG 字节                  │   └──────────────────────────────────────────────────┘
 └───────────────────────────────┘
 ┌───────────────────────────────┐   ┌──────────────────────────────────────────────────┐
 │ PhiraDownloader POOL（2 daemon）│  │ Job：volatile state/received/total/message/file   │
 │   GET 谱面包 → .part → 校验    │──▶│ 渲染线程 pollDownload() 轮询它                     │
 │   → 原子改名 → DONE/FAILED     │   └──────────────────────────────────────────────────┘
 └───────────────────────────────┘
```

规则（实现层）：

1. **所有网络调用只在后台线程**：`PhiraApi.search` / `download` / `fetchBytes` 都是阻塞方法，只在
   `PhiraApi.API_POOL`、`PhiraCovers` 的池、`PhiraDownloader` 的池里被调用。渲染线程只调
   `PhiraBrowser.search()`（内部 `PhiraApi.runAsync`）和 `PhiraDownloader.download()`（内部丢池）。
2. **失败如何回传**：后台线程把结果写进 `PhiraBrowser`（`error` 中文文案）或 `Job`
   （`state=FAILED` + `message`），渲染线程**下一帧**读出来画到状态行。没有回调跨线程、没有 `Future.get()`。
3. **过期结果**：`PhiraBrowser` 每次 `search` 自增 `serial`，后台回来发现序号变了就丢弃，
   避免「后发先至」把界面刷回上一页。
4. **贴图注册**只能在渲染线程（`TextureManager` 约束），所以曲绘拆成「后台下载+缩放」与
   「渲染线程注册」两段，中间用 `READY`（只有字节）交接；每帧最多注册 2 张（`PhiraCovers.BUILD_PER_FRAME`），
   一页 20 张会摊到约 10 帧，不会卡一帧。
5. **唯一一次可能的重活**：`finishDownload()` 里的 `session.rescan()`。它和 `init()` 里那次是同一个动作
   （只读每个包的 `info.txt`），且**只在一次下载完成时发生**，不是每帧。

超时与切换（需求 3）：

> **2026-10-20 后续修订：本节有几处已过时，见 [`REPORT-phira-net-hang.md`](REPORT-phira-net-hang.md)。**
> ① 域名顺序反了：现在 `phira.5wyxi.com` 是主、`api.phira.cn` 是备（官方客户端用的就是前者，
> `phira/src/client.rs:26`），原因是 `api.phira.cn` 实测单次 1.7~65 秒；
> ② 「单线程池」改成了 2 条线程 + 8 格队列（满了丢最老的请求）；
> ③ 光有 `connectTimeout(5s)` + `.timeout(15s)` 不够 —— JDK 的请求超时**不是硬上限**，
> 现在外面还套了一层 4.5 秒的总预算（`PhiraApi.sendWithDeadline`）；
> ④ 失败过的域名会被「冷却」60 秒并排到最后，正常搜索不再先去踩它。
> 下面的文字保留当时的记录，**以新报告为准**。

- `HttpClient.newBuilder().connectTimeout(5s)` + 每个请求 `.timeout(15s)`（`PhiraApi.CONNECT_TIMEOUT` / `REQUEST_TIMEOUT`）。
- 搜索：`api.phira.cn` 失败（连接失败 / 超时 / 5xx / 解析失败）→ 立刻换 `phira.5wyxi.com` **重试一次**，
  还失败才把中文原因交给界面。
- 下载：先试接口给的 CDN 绝对 URL，再试两个镜像的 `/file/{文件ID}`，最后试 CDN 的镜像域名。
- 缓存：搜索结果按「搜索词 + 排序 + 页 + 每页条数」缓存 **60 秒**（`CACHE_TTL_MS`），命中直接返回（实测 0ms）；
  失败**不缓存**，点「搜索」会重试。

---

## 3. 下载：文件名、落盘位置、重复检测

```
config/phimc/songs/{id}_{安全化曲名}.pez
  6882_R.I.P..pez                          ← 曲名里的点保留（Windows 允许）
  24101_RE Aoharu.pez
  77769_big song (self insert) MY LIFETIME.pez
```

- **安全化**（`PhiraDownloader.safeBaseName`）：
  ① `\ / : * ? " < > |` 和全部控制字符 → `_`；
  ② 连续空白压成一个空格、首尾 trim；
  ③ 首尾的点和空格去掉（Windows 会悄悄吃掉，导致「文件找不到」）；
  ④ 按**码点**截到 60 个字符（不会把 emoji 的代理对劈成半个）；
  ⑤ 空名退成 `chart`。
  自测覆盖：`"  ..名字..  "` → `名字`、`a/b\c:d*e?f"g<h>i|j` → `a_b_c_d_e_f_g_h_i_j`、
  emoji 原样保留、超长名字截到 60 码点。
- **落盘**：先写 `…​.pez.part`，校验「长度 > 4 且 zip 魔数 `50 4B 03 04`」，再
  `Files.move(..., ATOMIC_MOVE)` 改名成正式文件名（不支持原子移动的系统退回普通 move）。
  所以半截文件不会被 `SongEntry.scan` 扫成一首歌，也不会覆盖已经下好的同名包。
- **重复检测**：`songs/` 下任何 `{id}_*.pez`（也认 `.zip`）都算「已下载」，正则
  `^(\d+)_.*\.(pez|zip)$`（`PhiraDownloader.OWN_FILE`）。
  - 判重**只看 id 前缀**，不看曲名：同一首谱面在 Phira 改名后 id 不变、文件名会变；
  - 界面上按钮直接变「已下载」并禁用，`download()` 里也会再挡一次（重复点不会发第二遍请求）；
  - `PhiraDownloader.refresh(dir)` 只在进界面 / 切在线页 / 下载完成后列一次目录，成本可忽略。
- **成功后**（需求 2 的最后一步，`SongSelectScreen.finishDownload`）：
  `PhiraDownloader.refresh()` → `session.rescan()` → 用 `job.file().toAbsolutePath()` 和
  `SongEntry.path` 逐一比对找到新谱面的下标 → `tab = LOCAL`、`selected = 新下标`、底部提示
  「已下载并导入：R.I.P. · IN Lv.14（共 N 首）」。
- **失败提示**（全部中文，`PhiraApi`）：
  | 情况 | 文案 |
  |---|---|
  | 连接超时 5s | `连接失败：…` |
  | 请求超时 15s | `请求超时（15 秒）：<host>` |
  | HTTP 4xx/5xx | `请求过于频繁（HTTP 429）…` / `服务器错误（HTTP 500），稍后再试` / `资源不存在（HTTP 404）` |
  | 两个域名都失败 | 最后一次的中文原因（日志里记了每个域名各自的失败） |
  | 下载内容不是 zip | `下载到的不是谱面包（可能是错误页 / 被拦截），已删除临时文件` |
  | 磁盘（权限/空间/占位） | `磁盘写入失败：<原因>（检查 config/phimc/songs/ 的权限和剩余空间）` |
  | 超过 128 MB | `谱面包超过 128 MB 上限，已中断` |

---

## 4. 界面与版式

### 4.1 页签放哪（为什么没有「商店」页签）

页签放在**标题左侧**（`(12,3,48,18)` 与 `(64,3,48,18)`），副标题改成从页签右边左对齐
（`x = tabsEnd + 8`），所以两者永远不重叠；标题仍然居中。

**没有把「商店」做成第三个页签**：商店是独立界面 `TabletScreen`，只能从平板进，底部那个
「商店 / 关闭」按钮本来就承担了这条导航；再做一个页签会和「来源界面」语义打架（从平板进来时
它本来就是商店，从 P 键进来时它是关闭）。需求里这个问号（「本地 / 在线 / 商店？」）的答案是：
**两个页签 + 保留底部商店按钮**。

### 4.2 在线页版式（1280×720 / 854×480 逐像素核对）

```
PAD = 12                 左右留白
HEADER_H = 34            标题 y=8（居中）、副标题 y=20（从 tabsEnd+8 左对齐）
页签       (12,3,48,18) / (64,3,48,18)            右边界 112
搜索框     (12, 36, W-24-56-84-84-24, 20)          854→582、1280→1008
「搜索」   (搜索框右 + 8, 36, 56, 20)               854→602..658、1280→1028..1084
「排序」   (搜索右 + 8, 36, 84, 20)                 854→666..750、1280→1092..1176
「仅上架」 (排序右 + 8, 36, 84, 20)                 854→758..842、1280→1184..1268
在线列表顶 = 36 + 20 + 6 = 62
listBottom = H - 30                                480→450、720→690
每页行数   = (listBottom - 24 - 62) / 26           480→14、720→23（上限 30 = 接口 pageNum 上限）
行 i       (12, 62 + i×26, W-24, 24)               缩略图 20×20 在 x=16，文字从 x=42 起
行内「下载」(W-12-4-56, 行顶 + 4, 56, 16)          854→782、1280→1208
状态行     y = listBottom - 24 + 2                 480→428、720→668
版权声明   y = 状态行 + 11                         480→439、720→679
底部按钮   y = H - 26                              480→454、720→694
```

不重叠断言（`tools/ui/online-layout.ps1` 跑出来的）：

- 480：最后一行底 `62+13×26+24 = 424` < 状态行 428；版权行底 448 < 底部按钮 454；页签右 112 < 副标题左 120。
- 720：23 行底 `62+22×26+24 = 658` < 状态行 668；版权行底 688 < 底部按钮 694；页签右 112 < 副标题左 120。
- 搜索栏三个按钮：`搜索 56 + 间距 8 + 排序 84 + 间距 8 + 仅上架 84`，右边界正好 `W-12`；
  搜索框宽度 = `W-24-56-84-84-24`（854 剩 582、1280 剩 1008，都够输入 64 字符的曲名）。
- 曲名 / 难度 / 评分 / 谱师这一段文字右边界让开「下载中 N%」状态与「下载」按钮：
  `limit = downloadX - 8 - 状态宽度 - textX - 4`，不会压到按钮。

脚本对两个分辨率各打一张预览图：`C:\dsh-work\ui-check\online-1280x720.png`、`online-854x480.png`
（脚本里的常量/算式和 `SongSelectScreen` 一一对应，是版式副本，不是另写一套 UI）。

行内信息：`曲名（白）  · IN Lv.15  · 评分 0.96  · 谱师`，右侧状态列
`排队中 / 下载中 61% / 失败`，按钮文字 `下载 / 下载中 / 已下载`。
「仅上架」打开时服务端带 `stable=true&reviewed=true` 省流量，客户端 `keepStable()` 再筛一遍
（服务端忽略参数也拦得住）。

在线页底部永远显示（需求 4）：

> 谱面来自 Phira 社区，版权归原作者所有（只下载到本地，不打包进 mod）

**没有把任何谱面包打进 mod**：`lib/` 里只有两个第三方解码库和一个许可证文件；下载目录是
玩家自己的 `config/phimc/songs/`。

### 4.3 「仅上架」开关（本轮新增）

搜索栏最右边（挨着「排序」，同宽 84、同高 20）：`[✓] 仅上架` / `[ ] 仅上架`。

| | 打开（默认） | 关掉 |
|---|---|---|
| 请求 | `…&division=regular&stable=true&reviewed=true` | `…&division=regular`（不带 stable/reviewed） |
| 服务端总数 | 632（regular 的稳定谱） | 9737（regular 全部） |
| 列表 | 只有 `stable && reviewed` | 全部；**未上架 / 未过审的行**：曲名灰一档（`0xFF9C9C9C`）、
难度 / 评分 / 谱师灰（`0xFF7C7C7C`），并在谱师后面挂一个 ` [未上架]`（`0xFF6B6B6B`） |
| 副标题 | 「（只显示稳定且已审核）」 | 「（含未上架 / 未过审，灰色标注）」 |

实现要点（对应需求 1~4）：

1. **过滤可配置**：`PhiraApi.search(..., onlyStable)` 只决定「请求里带不带 stable/reviewed」，
   `parseList` 不再硬过滤（只丢 `id <= 0` 的坏数据）；「显示哪些」在
   `PhiraBrowser.onlyStable`（`static volatile`，默认 `true`）这一层做，开的时候 `keepStable()` 再筛一遍。
   UI 读 `PhiraChart.isPublicStable()` 决定灰不灰。
2. **按钮与重搜**：`[✓] 仅上架` 和「排序」同尺寸，坐标见 4.2 表；点一下 → `toggleOnlyStable()`：
   改开关 → **从第 1 页重搜**（总数 632 ⇄ 9737，停旧页码会跳页）→ 底部提示
   「仅上架：开（只显示已上架 + 已过审的谱面）」/「仅上架：关（…灰色 + [未上架]）」。
   两种模式在缓存里是**不同的键**（键里带 onlyStable），来回切不会串；切回上一次的模式 60 秒内直接命中（0ms）。
3. **只影响在线页**：开关只被 `drawOnlineList` / 副标题 / `PhiraBrowser.search` 读，
   本地页签 / 商店 / 平板都读不到；`downloadRow` / `PhiraDownloader` 一行没改（下载、落盘、判重完全复用）。
4. **不持久化**：`static volatile` 表示「这次游戏进程里记住」（关掉界面再进来还是上次的选择），
   重启游戏回到默认「开」。没有写 `profile.json` —— 那是生存玩法存档（解锁 / 成绩 / 商店），
   不该混一个界面浏览偏好。想持久化的话在 `Profile` 加一个 `showUnstable` 字段即可，这次没做（下面第 7 节）。

**排序与 rating=0**：客户端<b>完全不排序</b>，顺序就是服务端给的 `-rating`（降序）/ `-updated`（按更新时间降序）。
实测关掉「仅上架」后 `order=-rating` 第一页仍是 0.98 / 0.97 / 0.96…，rating=0 的未上架谱面排在最后，
<b>不会排到最前</b>；`pageNum` 上限仍是 30，没有改。

---

## 5. 编译与回归

### 5.1 三个版本 `build`（0 错误）

```powershell
robocopy "…\phimc" C:\dsh-work\phimc-phira /MIR /XD out .gradle build fixtures dist run
$env:JAVA_HOME="C:\dsh-tools\jdk25"; $env:GRADLE_USER_HOME="C:\dsh-gradle-home"
gradle -p C:\dsh-work\phimc-phira\mod ":1.20.1:build" ":1.21.1:build" ":1.21.11:build" `
       --no-daemon --console=plain --offline
```

> 注：任务里给的 `gradle-8.10.2 + jdk21` 是旧（单工程）时代的命令；现在的工程是 Stonecutter
> 多版本，`settings.gradle` 要求 **Gradle 9.8.2 + JDK 25**（`mod/build.ps1` 的口径），节点任务是
> `:1.20.1:build` 而不是 `build`。

```
> Task :1.21.11:compileJava
> Task :1.20.1:compileJava
> Task :1.21.1:compileJava
> Task :1.21.1:jar
> Task :1.20.1:jar
> Task :1.21.11:jar
> Task :1.20.1:remapJar
> Task :1.21.1:remapJar
> Task :1.21.11:remapJar
> Task :1.21.1:build
> Task :1.20.1:build
> Task :1.21.11:build

BUILD SUCCESSFUL in 18s
```

（三个版本的 `compileJava` 每次都真跑；`jar` / `remapJar` 因为产物字节没变而显示 `UP-TO-DATE`，这是 Gradle 的内容哈希，
不是「没编」。另有一次把三个节点的 `build/classes` 全删掉的干净编译：三个节点都完整跑了
`compileJava → jar → remapJar → build`，`BUILD SUCCESSFUL in 18s`，`gradle_exit=0`，证据 `build-clean.txt`。）

0 `error:`、0 `warning:`（只有 Gradle 自己的 deprecation 提示与 javac 的「过时 API」注记，改动前就有）。

产物：`versions/<ver>/build/libs/phimc-<ver>.jar`，1.20.1 = 597 KB / 1.21.1 = 598 KB /
1.21.11 = 600 KB（多出来的 ~127 KB 是 JWebP）。证据：`C:\dsh-work\phira-check\build3.txt`、`build-clean.txt`、`build-final3.txt`。

**新代码和版本分支**：网络层是纯 Java（`java.net.http` + gson + JWebP），一行 `//?` 都不用；
界面侧只用 `RenderCompat.drawTexture`（画缩略图）和 `TextFieldWidget` / `ButtonWidget`
（三个版本签名一致），所以三个版本编译走的是同一份代码。

### 5.2 StressTest ×2（判定/计分一行没动）

```
java -cp "<core-out>;gson.jar;jlayer.jar" dev.dsh.phimc.core.StressTest <chart>
```

| 谱面 | 自动播放 | 不操作 | ±60ms 抖动 |
|---|---|---|---|
| `pkg1\Chart_IN.json`（1285 音符 / 24 线 / 117.7s） | **1000000 PHI，P=1285 G=0 B=0 M=0，连击 1285/1285** | 0 分，M=1285/1285 | 509829 F（P484 G358 B45 M398） |
| `pkg2\Chart.json`（1418 音符 / 24 线 / 150.9s） | **1000000 PHI，P=1418 G=0 B=0 M=0，连击 1418/1418** | 0 分，M=1418/1418 | 341844 F（P451 G131 B21 M815） |

与 `docs/REPORT-ui-lock-cover.md` 里的数字逐位一致（`core/` 零改动）。

### 5.3 离线自测（真代码、真网络）

`tools/phira/PhiraApiCheck.java`（**不是 mock**，调的就是 mod 里那 4 个类）：

```
== 1. 搜索（空搜索词，按评分）==            ok=true count=632 本页=5
  #6882 R.I.P.  IN Lv.14  评分 0.96（32102 人）谱师 W.H.C.
  ✓ 只返回 stable && reviewed（5/5）
== 2. 按谱师搜索 ==                        谱师「W.H.C.」：count=6 ✓
== 3. 60 秒缓存 ==                         第二次用时=0ms（首次 1720ms）✓
== 4. 主域名失败 → 切备用域名 ==            127.0.0.1:1 → ok=true count=6 用时=143ms ✓
== 5. 曲绘缩略图（WebP）==                  原图 161550 字节 → 缩略图 5352 字节，64×34 ✓
== 6. 文件名安全化 ==                       6 组用例全过 ✓
== 7. 下载 + 落盘 + 判重 ==
   下载 #6882 R.I.P. → 6882_R.I.P..pez
   进度 61%（4243402 / 6882155 字节）→ DONE 6882155 字节
   ✓ 落盘路径 = {id}_{安全化曲名}.pez    ✓ 内容是 zip（.pez）
   ✓ 下载后标记已下载                    ✓ 重复下载被拦下（标「已下载」）
== 8. 磁盘错误的中文提示 ==
   失败原因：磁盘写入失败：…\blocker（检查 config/phimc/songs/ 的权限和剩余空间）✓
== 9. 「仅上架」关掉：未上架 / 未过审也返回 ==
   ok=true count=9737 本页=10
   ✓ 关掉「仅上架」后搜索成功
   ✓ 总数变大（含未上架）：9737 > 632
   本页 10 条，其中未上架 / 未过审 9 条（界面上灰色 + [未上架]）
   ✓ 本页确实出现了未上架的谱面
   切回「仅上架」：count=632 本页=10 未上架=0
   ✓ 切回「仅上架」后一条未上架都没有
   ✓ 「仅上架」总数更小：632 < 9737
全部通过 ✓
```

`tools/phira/ImportCheck.java`（下载下来的包能不能被曲库认出来 —— 也就是「自动导入」那一步）：

```
扫描到 1 首：
  6882_R.I.P..pez → R.I.P. · IN Lv.14
    曲名「R.I.P.」难度「IN Lv.14」段位「IN」
    谱师「W.H.C.」曲师「eicateve」画师「TOHRU MiTSUHASHi」
    谱面 JSON：7518157 字符
    音频：31717599.mp3 6054208 字节
    曲绘：607163 字节
全部通过 ✓
```

证据：`C:\dsh-work\phira-check\run3.txt`、`import.txt`、`run-toggle.txt`（第 9 节是「仅上架」开关）。

**本轮（仅上架开关）的编译与交付**：

```
gradle -p C:\dsh-work\phimc-phira\mod ":1.21.1:build" --no-daemon --console=plain --offline
> Task :1.21.1:compileJava
> Task :1.21.1:jar
> Task :1.21.1:remapJar
> Task :1.21.1:build
BUILD SUCCESSFUL in 27s          # gradle_exit=0，0 error / 0 warning
```

产物 `phimc-1.21.1.jar` = 613649 字节（2026-10-20 06:31:57），已复制到
`D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`（两边 SHA-256 一致：
`36579D2E…AAD73B`）。另外 1.20.1 / 1.21.11 也各编了一遍，均 `BUILD SUCCESSFUL`（`build-toggle-other.txt`）。

`tools/ui/online-layout.ps1`（版式副本：常量/算式和 `SongSelectScreen` 一一对应）：

```
== 在线页 1280×720 ==
  尺寸 1280×720：每页 23 行，列表 62..658，状态行 y=668，版权行 y=679，底部按钮 y=694
    页签 (12,3,48×18) / (64,3,48×18)  右边界 112
    搜索框 (12,36,1100×20)  搜索 (1120,36,56×20)  排序 (1184,36,84×20)
    下载槽位 x=1208..1264  文字右边界 1200  缩略图 x=16..36  文字 x=42 起
  OK   页签右边界 112 < 副标题左 120
  OK   最后一行底 580 <= 状态行 668      （23 行时是 658）
  OK   版权行底 688 <= 底部按钮 694
  OK   排序按钮右边界贴 1268 / 底部按钮不溢出
  预览: C:\dsh-work\ui-check\online-1280x720.png
== 在线页 854×480 ==
  尺寸 854×480：每页 14 行，列表 62..424，状态行 y=428，版权行 y=439，底部按钮 y=454
  OK   最后一行底 424 <= 状态行 428     OK   版权行底 448 <= 底部按钮 454
  预览: C:\dsh-work\ui-check\online-854x480.png
全部通过 ✓
```

证据：`C:\dsh-work\ui-check\online-layout.txt` 与两张预览 PNG。

---

## 6. 为什么多了一个依赖（JWebP）

Phira 的曲绘 URL（`https://phira.5wyxi.com/files/<id>`）返回的是 **WebP**
（实测 8/8，`RIFF....WEBP`，2048×1080，VP8 有损），而：

- JDK 的 `ImageIO` 没有 WebP 解码器；
- Minecraft 的 `NativeImage.read` 用的是被裁剪过的 stb_image，只认 PNG（连 JPEG 都要先转，见 `CoverCodec`）；
- 问过服务端能不能换格式：`?format=png` / `?w=64` / `Accept: image/jpeg` 全部忽略，永远返回原 WebP；
  也没有缩略图接口；
- 试过 JCodec 的 VP8 解码器：这批判的流是**多 token 分区**，JCodec 直接
  `IllegalStateException`（它只支持单分区），不能用。

所以引入 **JWebP**（`org.glavo:webp:0.2.0`，纯 Java、只依赖 `java.base`、无 native、无传递依赖，
HMCL 在用，MPL-2.0）。落地方式照仓库既有惯例（JLayer 也是这样）：

- jar 放 `lib/webp-0.2.0.jar`，`build.gradle` 用 `implementation files(...)`（离线可编）；
- `jar` 任务把类摊进 mod jar（Fabric 只加载一个 jar），同时把 `lib/webp-LICENSE.txt` 以
  `LICENSE_jwebp` 打进产物（MPL-2.0 要求随分发保留许可证）；
- 只在 `CoverCodec.decodeWebp` 里用；如果类缺失（例如离线工具没带那个 jar），
  `catch (Throwable)` 会退回「没有曲绘」的占位块，不会崩。

缩略图策略：JWebP 支持**解码时就缩到目标尺寸**（`WebPImageLoadOptions.requestedWidth/Height`），
所以 2048×1080 的图解码出来直接是 64px 级（实测约 70~160ms/张，在后台线程），
显存里只放 64×64 贴图（约 16 KB/张 × 24 张）。

---

## 7. 没做 / 已知取舍

1. **没在真客户端里点一遍界面**。本机没有可交互的 Minecraft 客户端（前几次交付也是靠坐标表 + 离线自测）。
   界面层的证据是「常量 → 坐标表 → 不重叠断言 + 三版本编译」；网络 / 下载 / 判重 / 导入是
   `PhiraApiCheck` + `ImportCheck` 用真代码真网络跑的。
2. **「商店」没做成页签**（理由见 4.1）。
3. **在线页不分页内滚动**：一页 = 一屏（请求的 `pageNum` 就是屏幕能放的行数，480→14、720→23），
   换页靠底部「上一页 / 下一页」。这样不用维护「页内滚动偏移 + 翻页 + 选中」三套状态；
   代价是窗口缩放时会按新行数重取一次当前页（命中 60 秒缓存，不会重复打接口）。
4. **没有做 API 限流的主动退避**：429 只会显示「请求过于频繁（HTTP 429），等一分钟再试」，
   不会自动 sleep 重试（不想在后台线程里挂 60 秒）。60 秒搜索缓存已经把「翻来翻去」的请求压掉了。
5. **没有做断点续传 / 取消下载**：`.part` + 原子改名保证了「半截文件不会变成一首歌」，但断了要从头下。
   并发上限 2（`PhiraDownloader.POOL`），一次点很多首会排队（行内显示「排队中」）。
6. **没有做曲绘磁盘缓存**：缩略图只在内存（LRU 24 张），重进游戏会重新下。曲绘本身不大
   （实测 30KB ~ 6MB，均值约 1MB），但一首 6MB 的曲绘下载还是有点浪费 —— 如果要加，
   建议落在 `config/phimc/cache/`，这次没做（需求只要求 LRU 缓存）。
7. **没有做 `preview` 音频试听、tags / 难度 / 区间筛选、谱面详情页**：接口里都有
   （`preview`、`tags`、`rating` 区间），但需求只要「搜索 + 排序 + 分页 + 下载」，没扩。
8. **`rating` 显示成两位小数**：接口给的是 0~1 的小数（不是 5 分制），如 `0.96`；
   排行榜的百分比口径没换算（换算规则没找到权威定义，宁可不猜）。
9. **「仅上架」开关没持久化**：`static volatile` = 这次游戏进程里记住，重启回默认「开」。
   没写 `profile.json`（那是生存玩法存档：解锁 / 成绩 / 商店，不该混一个浏览偏好）。
   要持久化的话：`Profile` 加 `showUnstable` 字段 + 两个存取方法，`PhiraBrowser.onlyStable` 初值从
   `profile.showUnstable()` 读、`toggleOnlyStable()` 里写回即可，改动约 15 行；这次按需求
   「本局有效即可」没做。
10. **未上架谱面没有区分「未过审」和「未上架」**：`stable` / `reviewed` 两个标志在界面上统一标
    `[未上架]`（需求给的例子就是这个文案）。如果要细分，`PhiraChart` 已经两个字段都有，改文案即可。
11. **关掉「仅上架」后下载行为不变**：未上架谱面一样能下载、落盘、判重（复用同一条链路），
    没有额外拦截 —— 需求说「下载/落盘/判重逻辑完全复用」，所以没加闸门。
9. **下载的谱面包没有做「内容校验」（谱面 JSON / 音频能不能解析）**：只校验了 zip 魔数 +
   `SongEntry.scan` 能认出来。真正解析失败会在本地页签开局时按既有逻辑提示「谱面解析失败」
   （`startSelected()` 里那一条），不会静默。
