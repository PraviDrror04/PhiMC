# 报告：选曲界面（本地页）搜索 / 排序 / 筛选

仓库：`C:\Users\Administrator\Documents\deepseek-harness\默认工作区\phimc`
主文件：`mod/src/main/java/dev/dsh/phimc/client/SongSelectScreen.java`（1472 → 1896 行）

本地页签新增**一行**四个控件（详情面板与列表之间）：

```
[ 搜索框：曲名 / 曲师 / 谱师 子串 ]  [排序：xx]  [锁定：xx]  [难度：xx]
```

三者**组合生效**：先按搜索 + 两个筛选挑出子集，再按排序排，结果就是列表（分页 / 行号 / 选中项全跟着它走）。

---

## 1. 改动文件 : 行

### 1.1 `client/SongSelectScreen.java`（1472 → 1896 行）

| 内容 | 行 |
|---|---|
| 类注释：新布局块（含筛选行）+ 新增「本地页：搜索 / 排序 / 筛选」一节 | 26–50 / 74–100 |
| 布局常量 `FILTER_H` / `FILTER_GAP_TOP` / `FILTER_GAP_LIST` / `SORT_BTN_W` / `LOCK_BTN_W` / `DIFF_BTN_W` | 182–201 |
| `enum LockFilter { ALL, UNLOCKED, LOCKED }`（带中文 label） | 208–219 |
| `enum SortMode { DEFAULT, TITLE, DIFFICULTY, NOTES, BEST }` | 221–237 |
| 难度循环表 `DIFF_CYCLE = {"", "EZ", "HD", "IN", "AT"}` / 排序档位 `DIFF_ORDER` | 239–242 |
| **状态（静态，见 §3 持久化说明）**：`localQuery` / `sortMode` / `lockFilter` / `diffFilter` | 288–299 |
| **视图（实例）**：`view`（view 下标）/ `viewDirty` / `viewNotesKnown` / `selectedPin` | 302–318 |
| 控件：`localSearchField` / `sortBtn` / `lockBtn` / `diffBtn` | 319–322 |
| 布局方法：`filterY()` / `diffBtnX()` / `lockBtnX()` / `sortBtnX()` / `localFieldW()` | 411–434 |
| `listTop()` 改为「筛选行下面」（原 `detailBottom()+10`） | 436–439 |
| `init()`：选中项改为 `selectedPin`（按路径） | 567–575 |
| `init()`：创建搜索框 + 三个循环按钮 + 若排序=物量则先排队 | 637–652 |
| `syncButtons()`：开头 `ensureView()`，本地页控件可见性、`startBtn` / 拷芯片按钮改成按 **view** 判 | 708–760 |
| **`ensureView(List)`**：搜索 → 锁定筛选 → 难度筛选 → 排序 → 重定位选中项 | 799–837 |
| `selectedSong()` / `matchesLocal()` / `sortLocal()` | 840 / 845 / 860 |
| `difficultyRank()` / `notesOf()` / `knownNotes()` / `bestScoreOf()` / `containsText()` / `nz()` / `indexOfPath()` | 881–928 |
| `requestNotes()`（把全部谱面排进 SongMeta 的后台串行队列） | 930–933 |
| 三个按钮的文字 + 点击（`sortLabel` / `lockLabel` / `diffLabel` / `cycleSort` / `cycleLockFilter` / `cycleDiffFilter`） | 937–985 |
| `setTab()`：进在线页前把本地搜索框焦点放掉（否则隐藏的输入框会吃字符键） | 991–1000 |
| `isLocked(int)` 改成按 **view 下标** | 1148–1151 |
| `move()` 在 view 内 clamp，并同步 `selectedPin` | 1170–1175 |
| `startSelected()`：`Session.indexOf(entry)` 换回 `songs()` 下标 | 1183–1207 |
| `copyRow()`：行槽位 → view 下标 | 1216–1225 |
| `finishDownload()`：下载完选中新谱面；若被搜索/筛选挡住则自动清条件并提示 | 1299–1348 |
| `mouseClicked()` 本地分支改成 view | 1382–1401 |
| `keyPressed()`：本地搜索框有焦点时 ↑↓ / 回车不抢（回车 = 收起焦点） | 1463–1500 |
| `renderBackground()`：本地列表画 `view` | 1520–1527 |
| `render()`：`syncButtons()` 先建 view；副标题显示「筛后 N/M 首」；选中项用 `selectedSong()`；筛空时给专门提示 | 1539–1613 |
| `drawList()`：参数即 view（已过滤 / 已排序） | 1746–1755 |

### 1.2 `client/SongMeta.java`（183 → 265 行）

为了让「排序：物量」能拿到全部音符数，又**不能**在列表初始化时批量解析大 JSON，把后台从
「一个 entry 一个线程」改成**一个 worker + 一条队列**（串行、每包只读一次）：

| 内容 | 行 |
|---|---|
| 队列 `QUEUE` + `RUNNING`（保证只有一个 worker） | 65–67 |
| `get()`：命中缓存 → 回填并返回；否则 `enqueue` | 84–99 |
| **`peek()`**：只查缓存、不排队（排序用） | 102–110 |
| **`requestAll(List)`**：批量排队（每包一次） | 117–127 |
| `enqueue()` / `dispatch()` | 132–148 |
| **`drain()`**：串行消费；退出前再抢一次锁（避免清标志瞬间丢任务） | 153–171 |
| `parseInto()`：① 流式 META → ② 全量解析物量/BPM → ③ 一起进缓存 | 174–200 |
| `clear()` 顺带清 `QUEUE` | 245 |

### 1.3 `client/Session.java`

| 内容 | 行 |
|---|---|
| **`indexOf(SongEntry)`**：按谱面包路径在 `songs()` 里查下标（找不到 -1） | 333–345 |

> 为什么需要：`selected` 现在是 **view 的下标**，而 `Session.start(int)` 要的是 `songs()` 的下标；
> 中间必须换算一次。按路径而不是引用，因为 `rescan()` 会把 `SongEntry` 全部重建。

### 1.4 其他

- `tools/ui/select-layout.ps1`：加入筛选行的坐标模型、10 组不重叠断言、预览图（见 §3）。
- 新增本文档 `docs/REPORT-local-filter-sort.md`。

---

## 2. 搜索 / 排序 / 筛选的交互与组合逻辑

### 2.1 管线（`ensureView`，只在脏的时候跑）

```
songs() ──① 搜索词（曲名/曲师/谱师 子串，大小写不敏感）
        ──② 锁定筛选（全部 / 仅已解锁 / 仅未解锁，走 isLocked()）
        ──③ 难度筛选（全部 / EZ / HD / IN / AT，比 grade）
        ──④ 排序（默认 / 曲名 / 难度 / 物量 / 最佳成绩，稳定排序）
        ──► view（列表 = view，分页 = view.size()，行号 = view 下标，选中 = view[selected]）
```

- **组合生效**：①②③ 同时满足才进列表，④ 只决定顺序；三者改任何一个都只置
  `viewDirty = true`，下一帧重建一次（不重建界面、不丢选中、不重开界面）。
- **选中项跨条件保持**：`selectedPin` 记的是「哪一首」，重建后按**路径**重新定位；
  被过滤掉了才落到第 1 行（避免「选中项在看不见的地方，回车开始了一首没显示的歌」）。
- **搜索是实时的**：每帧比一次 `TextFieldWidget.getText()` 与 `localQuery`（不依赖
  `setChangedListener` 回调，跨 19 个 MC 版本更稳）。清空搜索词立刻恢复全量。
- **搜索字段**：`title()` / `composer()` / `charter()`（后两个用 `SongEntry` 的**最终值**，
  所以 RPE 包靠 META 兜底补上的曲师 / 谱师也能搜到）。

### 2.2 排序（循环按钮，顺序固定）

| 档 | 键 | 说明 |
|---|---|---|
| 默认 | 无（不排序） | 就是 `songs/` 扫描顺序 = 文件名 / 添加顺序 |
| 曲名 | `title()` 小写 → 难度档 | 同曲不同难度按 EZ&lt;HD&lt;IN&lt;AT 排在一起 |
| 难度 | EZ(0)&lt;HD(1)&lt;IN(2)&lt;AT(3)&lt;其它(4) → 曲名 | 认不出的难度（如 `Simple Lv.91`）排最后 |
| 物量 | `SongMeta.peek().notes()` 升序 → 曲名 | 还没解析到的用 `MAX_VALUE` 排最后，**随着后台读完自动重排** |
| 最佳成绩 | `records.tsv` 分数**降序** → 曲名 | 没打过的 -1，排在最后 |

- 用的是 `List.sort`，Java 的排序**稳定**，所以「相同键」永远保持添加顺序 —— 「默认」这一档
  连排序都不做。
- **「排序：物量」的成本**：物量只能解析谱面（`info.txt` 没有），点这一档时把全部谱面排进
  `SongMeta.requestAll(...)` 的**单线程队列**（一次解析一首、每包只读一次、进缓存）。
  列表初始化（`SongEntry.scan` / `init()`）**不会**触发它；`ensureView` 里那个 `requestAll`
  只在「已知物量数变了」时调一次，解析失败的谱面（进 `FAILED`）不会让它每帧重排。

### 2.3 筛选（两个循环按钮）

| 按钮 | 循环顺序 |
|---|---|
| 锁定 | `全部 → 仅已解锁 → 仅未解锁 → 全部` |
| 难度 | `全部 → EZ → HD → IN → AT → 全部` |

- 锁定筛选复用**唯一的锁定闸门** `isLocked(SongEntry)`，所以「播放器模式（unlockAll）」
  下「仅未解锁」必然是空列表 —— 语义一致（那种模式下所有谱面都算已解锁）。
- 难度筛选比的是 `SongEntry.grade`（`info.txt` 的 `Level:` 规范化出来的 EZ/HD/IN/AT）。

### 2.4 键盘 / 鼠标

| 操作 | 行为 |
|---|---|
| 点搜索框 → 打字 | 实时过滤（本地页签） |
| 本地搜索框有焦点时按 ↑/↓ | **不切歌**（避免打拼音时光标一动选中项乱跑） |
| 本地搜索框有焦点时按回车 | **收起焦点**，不开始游戏（过滤本来就是实时的） |
| 回车（没在输入） | 开始选中的那一首（锁定则只提示） |
| 点未选中行 / 再点已选中行 | 选中 / 开始（原有行为不变） |
| 滚轮 / ↑↓ | 在 view 内移动选中项（`move()` clamp 到 view） |
| 点「排序 / 锁定 / 难度」 | 切换 + 立刻重排 + 底部提示当前档位（不重建界面） |

### 2.5 持久化（需求允许不持久化）

- 四个条件（搜索词 / 排序 / 锁定 / 难度）是 **`static` 字段**：跨「界面重建」保持
  （打完一局回来、从设置页回来、窗口缩放都还是上次的条件），**但不写 `profile.json`**，
  重启游戏恢复默认（默认 / 全部 / 全部 / 空搜索词）。
- 理由：写 profile 要动存档格式（还要考虑老存档缺字段），而这是纯 UI 偏好；
  用户明确说「可以不持久化」。代码里注释写明了这一点（`SongSelectScreen:288–299`）。

### 2.6 边界情况

- 筛成 0 首：详情面板显示「没有匹配的谱面（搜索 / 筛选后 0 首）：清掉搜索词或把「锁定 / 难度」
  调回全部」，「开始」按钮变灰，`view` 空 → 列表不画任何行。
- 下载完成（在线页 → 本地页）：自动选中新下载的那一首；如果它正好被搜索 / 筛选挡住，
  会**自动清掉四个条件**并提示一句（否则用户会以为下载失败）。
- 在线页 ↔ 本地页切换：进在线页时把本地搜索框**焦点放掉**（控件 `visible=false` 了，
  但 `Screen` 仍会把字符键派发给「当前焦点控件」）。

---

## 3. 坐标表（1280×720 / 854×480）

`tools/ui/select-layout.ps1` 打印的就是这两张表（跑一次同时做 30 余条不重叠断言 + 出预览图）。

| 项目 | 1280×720 | 854×480 |
|---|---|---|
| 顶部页签 y / 两个页签 | 3..21 / 本地 12..60，在线 64..112 | 同左 |
| 详情区 y | 34 … 169 | 34 … 149 |
| 详情面板 fill 底（`detailBottom()+6`） | 175 | 155 |
| **筛选行 y（高 20）** | **177 … 197** | **157 … 177** |
| 　搜索框 x（宽） | 12..1008（**996**） | 12..582（**570**） |
| 　「排序：xx」x（宽 84） | 1016..1100 | 590..674 |
| 　「锁定：xx」x（宽 76） | 1108..1184 | 682..758 |
| 　「难度：xx」x（宽 76） | 1192..1268（贴 W-PAD） | 766..842（贴 W-PAD） |
| 列表 y | 203 … 690 | 183 … 450 |
| 每页行数 / 最后一行底 | 20 / 641 | **11**（原 12）/ 423 |
| 拷芯片按钮 x | 1208..1264 | 782..838 |
| 提示行 y | 680 … 689 | 440 … 449 |
| 底部 6 按钮 y | 694..714（395..885） | 454..474（182..672） |
| HUD AUTO 框（游玩界面） | (35,35)..(66,50) | (23,23)..(54,38) |

**断言（脚本输出 `✔ 无重叠`）**：页签底 < 详情区顶；详情面板 fill 底（175/155）≤ 筛选行顶
（177/157）；筛选行四个控件两两相隔 ≥ 8 且「难度」右边界正好贴 `W-PAD`；搜索框 ≥ 40；
筛选行底 ≤ 列表顶；最后一行底 ≤ `listBottom-12`；提示行在最后一行与底部按钮之间；
信息列 / 曲绘 / 拷芯片按钮 / 底部 6 按钮（含「自动游玩」紧挨「开始」）全部不重叠。

预览图（仓库外）：`C:\dsh-work\ui-check\select-layout-{1280x720,854x480}.png`、
`hud-auto-*.png`。

**布局取舍**：筛选行放在**详情面板下面**而不是页签正下方 —— 页签那一行（y=3..21）与副标题
（y=20）都在标题区，而详情面板从 y=34 开始；筛选行若放在 y=36 会直接压住详情面板
（曲绘 / 信息列）。所以按需求「页签下方、列表上方」的意图，放在「详情面板与列表之间」，
并且让开面板 fill 底 6px 的余量（`FILTER_GAP_TOP = 8`）。代价是 854×480 每页少 1 行（12 → 11）。

---

## 4. 编译 + 回归

### 4.1 `gradle :1.21.1:build`（Gradle 9.8.2 + JDK 25，offline）

```
$env:JAVA_HOME="C:\dsh-tools\jdk25"; $env:GRADLE_USER_HOME="C:\dsh-gradle-home"
gradle -p <repo>\mod :1.21.1:build --no-daemon --console=plain --offline

> Task :1.21.1:compileJava
> Task :1.21.1:processResources
> Task :1.21.1:classes
> Task :1.21.1:jar
> Task :1.21.1:remapJar
> Task :1.21.1:assemble
> Task :1.21.1:build

BUILD SUCCESSFUL in 19s
```

0 error / 0 warning（`C:\dsh-work\evidence\build-1.21.1.txt`）。
产物 `mod/versions/1.21.1/build/libs/phimc-1.21.1.jar`（668406 字节）已
**覆盖到 `D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`**，
jar 里能看到新增的 `SongSelectScreen$LockFilter.class` / `$SortMode.class`。

**顺带验了另外两个版本**（同一份源码，Stonecutter 按 `//?` 展开）：

| 版本 | 结果 |
|---|---|
| `:1.21.1:build` | ✔ BUILD SUCCESSFUL（**交付目标**） |
| `:1.20.1:build` | ✔ BUILD SUCCESSFUL（最老的节点，Java 17） |
| `:1.21.9:build` / `:1.21.11:build` | ✗ **既有问题，与本次改动无关**：报错全在 `RenderCompat.java:93/106/107/109/110` 与 `LineTextures.java:196`（`DrawContext.drawTexture` / `setShaderColor` 签名、`NativeImage.getColor` 变 private）——这两个文件最后修改时间 2:04–2:05，晚于它们上次构建成功的 1:24；报错里**没有** `SongSelectScreen` / `SongMeta` / `Session` 任何一行 |

### 4.2 StressTest（判定 / 计分未改）

```
pkg1（1285 音符 / 24 判定线）  自动播放 1000000 PHI  P=1285 G=0 B=0 M=0  连击 1285/1285
pkg2（1418 音符 / 24 判定线）  自动播放 1000000 PHI  P=1418 G=0 B=0 M=0  连击 1418/1418
```

不操作仍 0 分全 Miss、±60ms 抖动仍 509829 / 341844（与上次一致）。
`core/` 一行未改；`Session` 只多了一个 `indexOf()` 查询方法（不参与判定）。

---

## 5. 没做与原因

1. **不持久化到 `profile.json`**：四个条件是 `static` 字段（跨界面重建保持、重启游戏恢复默认）。
   写硬盘要动 `Profile` 的 JSON 格式和老存档兼容，而这只是 UI 偏好 —— 用户也明确说可以不做。
2. **筛选行没有放到页签正下方**：那一带是标题区（y=3..21 页签、y=20 副标题），再往下 34 就是
   详情面板；放在 y=36 会把曲绘 / 信息列压掉。改成「详情面板与列表之间」，并在报告里写清坐标。
3. **难度筛选是全/单选，没有做成多选**：需求写「多选或循环：全部→EZ→HD→IN→AT→全部」，
   这里选了循环（一个按钮，窄屏也放得下）。
4. **没有单独的 headless 单测覆盖筛选 / 排序逻辑**：这段逻辑在 `SongSelectScreen` 里，
   依赖 `Profile` / `Records` / MC 的 `Screen`，拿不到无头运行环境（同前几次交付）。
   验收靠：编译（1.21.1 / 1.20.1 两个版本）、版式脚本的坐标 + 断言、以及代码里的
   `ensureView` 单一入口（过滤→排序只有这一处，改条件只置脏）。
5. **没在真客户端点一遍**（本机无可交互客户端）：交互正确性靠「所有入口都收敛到
   `ensureView` / `selectedSong` / `Session.indexOf` 三个方法」+ 上面坐标表核对。
6. **`:1.21.9` / `:1.21.11` 的既有编译失败没修**：那是 `RenderCompat` / `LineTextures`
   的渲染 API 适配问题，属于另一条工作线，混进来会把这次 UI 改动变得不可审。
