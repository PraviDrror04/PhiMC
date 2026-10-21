# 报告：曲师 / 谱师 / 画师的 META 兜底（RPE 的 info.txt 没有 Illustrator）

承接 `docs/REPORT-ui-lock-cover.md`（曲师/谱师/画师第一次上界面）与
`docs/REPORT-autoplay-unlock.md`（AUTO 开关 + 播放器全解锁）。

## 问题（用户指出）

RPE（`.pez`）的 `info.txt` **没有 `Illustrator:` 字段**，画师名只在**谱面 JSON 的 `META`** 里，
而 `Picture:` 只是图片文件名。实测 4 个真实 pez 的 `info.txt` 全是
`Name / Path / Song / Picture / Chart / Level / Composer / Charter / LastEditTime / Length / Group`，
一个 `Illustrator` 都没有；对应的谱面 `META` 里却有：

| 谱面包 | `info.txt` | `META` |
|---|---|---|
| Cyberneko Pulse (2).pez | 无 Illustrator | `"background": "9691407376462442.jpg"`, `"illustration": "keckyx"`, `"charter": "three or four"`, `"composer": "Connexio"` |
| Hardsync.pez | 无 Illustrator | `"illustration": ""`（**真的空**）, `"charter": "母鸡Sama' vs awa_"` |
| 推活天使ビビアンちゃん .pez | 无 Illustrator | `"illustration": "ファル公 "`, `"background": "7876823621765096.png"` |
| 琵琶曲DJ(1.15x).pez | 无 Illustrator | `"illustration": "9191919191919191919191"`, `"level": "Simple Lv.91"` |

所以画师以前永远显示「未知」。

---

## 1. 改动文件 : 行

### 1.1 新增 `client/ChartMeta.java`（168 行，纯 gson 流式读，不碰 Minecraft 类）

| 内容 | 行 |
|---|---|
| 类注释：为什么 RPE 要读 META、人名 vs 文件名的键表 | 1–42 |
| `EMPTY`（什么都没有） | :45 |
| **`scan(String json)`**：流式扫 `META` | :109–152 |
| 曲师键 `composer` / `songBy` / `song_by` | :131 |
| 谱师键 `charter` / `chartBy` / `chart_by` | :133 |
| **画师键** `illustration` / `illustrator` / `cover` / `pictureBy` / `imageBy` / `illustBy` | :135–136 |
| **曲绘文件名键** `background` / `picture` / `image` | :138 |
| 难度 `level` / `difficulty` | :140 |
| 读到 `META` 就 `break`（不 lex 后面的音符数组） | :147 |
| `str(JsonReader)`：读标量 + `trim()`（RPE 常写 `"DJ Myosuke "` 带尾空格） | :156–167 |

### 1.2 `client/SongEntry.java`（525 → 613 行）

| 内容 | 行 |
|---|---|
| `private volatile MetaFallback meta;`（META 兜底，后台线程填） | :82 |
| `record MetaFallback(composer, charter, illustrator, picture, level)` | :85–87 |
| **最终值 getter**：`composer()` :115、`charter()` :120、`illustrator()` :129、`pictureName()` :139、`levelText()` :146 | :110–152 |
| 后台回填 `fillFromMeta(...)` / 状态 `metaFilled()` | :156 / :162 |
| 曲绘查找改用 `pictureName()`（info.txt → META.background） | :409 |

`public final String composer / charter / illustrator / picture` **保留为 info.txt 的原值**
（字段注释里写明「显示请用方法」），这样：
- 列表扫描（`SongEntry.scan`）依旧只读 `info.txt`，零解析成本；
- 谁想拿「最终显示值」就调方法，信息栏不会漏掉 META 兜底。

### 1.3 `client/SongMeta.java`（100 → 126 行）

| 内容 | 行 |
|---|---|
| 类注释改写（不再只是「物量/BPM」两个字段） | :13–31 |
| 后台线程：先 `ChartMeta.scan(json)`，再 `ChartParser.parse(json)` | :66–79 |
| `entry.fillFromMeta(new SongEntry.MetaFallback(...))` | :70–72 |
| 日志 `META 兜底：… 曲师「」谱师「」画师「」曲绘「」` | :73–75 |

### 1.4 `client/SongSelectScreen.java`（727 → 731 行）

信息列取值改成**最终值 getter**（以前直接读字段，所以 pez 的画师永远是「未知」）：

| 内容 | 行 |
|---|---|
| 难度 `e.levelText()`（info.txt → META.level） | :662 |
| 曲师 `e.composer()` | :664 |
| 谱师 `e.charter()` | :665 |
| 画师 `e.illustrator()` | :666 |

### 1.5 `client/CoverTextures.java`（205 → 218 行）

`info.txt` 没写 `Picture:` 时，图名只可能在 `META.background`（后台读）；
这种「暂时不知道图名」的失败不能永久缓存，否则 META 回来也不会再试：

| 内容 | 行 |
|---|---|
| 新增 `NO_PICTURE` 集合（≠ `FAILED`） | :62 |
| `get()` 开头：META 到位（`pictureName()` 非空）就允许重试一次 | :77–81 |
| 失败分流：名字还不知道 → `NO_PICTURE`；知道名字也找不到 → `FAILED` | :88–89 |
| `clear()` 一并清 | :154 |

---

## 2. 字段对照表（修前 / 修后，`C:\dsh-work\payload` 全 19 个包）

**修前** = `info.txt` 原值（字段）；**修后** = 最终值（方法，META 兜底）。
证据：`C:\dsh-work\evidence\meta-fallback.txt`。

### 2.1 4 个 `.pez`（RPE，info.txt 无 Illustrator）

| 谱面包 | 曲师 | 谱师 | **画师（修前 → 修后）** | 曲绘图文件名 |
|---|---|---|---|---|
| Cyberneko Pulse (2).pez | Connexio | three or four | **未知 → `keckyx`** ✅ | `9691407376462442.jpg` |
| Hardsync.pez | hirihiri & Yaca feat. 桔梗 小夜曲 | 母鸡Sama' vs awa_ | 未知 → 未知（`META.illustration` **本身就是空串**，包里没写画师） | `1939818528944948.jpg` |
| 推活天使ビビアンちゃん .pez | DJ Myosuke | 殇儿打不动 | **未知 → `ファル公`** ✅ | `7876823621765096.png` |
| 琵琶曲DJ(1.15x).pez | DJ筱轩 | 殇儿打不动 | **未知 → `9191919191919191919191`** ✅ | `3430094183784295.png` |

> `Hardsync.pez` 的 `META` 里 `"illustration": ""`，所以修后仍是「未知」——这是**数据本身没有**，
> 不是读取缺口。其余 3 个 pez 的画师都补上了（Cyberneko = `keckyx`，符合验收要求）。

### 2.2 15 个官方 PGR `.zip`（info.txt 有 Illustrator，必须**一字不变**）

| 谱面包 | 曲师 | 谱师 | 画师（修前 = 修后） | 曲绘图文件名 |
|---|---|---|---|---|
| AvataarReincarnationofKalpa_IN.zip | ScarletteakaCrYmson | Unknown | Unknown | defaultImage.png |
| CROSSSOUL_IN.zip | HyuNfeatSyepias | Unknown | Unknown | defaultImage.png |
| DeadSoul_IN.zip | SoundSouler | Unknown | Unknown | defaultImage.png |
| DistortedFate_IN.zip | Sakuzyo | Unknown | Unknown | defaultImage.png |
| ERABYECONNEC10N_IN.zip | かめりあ | Unknown | Unknown | defaultImage.png |
| iLArtifact_IN.zip | Link0 | Unknown | Unknown | defaultImage.png |
| PANICPARADISE_IN.zip | DJSHIONY | Unknown | Unknown | defaultImage.png |
| PRAGMATISMRESURRECTION_IN.zip | Laur | Unknown | Unknown | defaultImage.png |
| ReEndofaDream_IN.zip | umavsモリモリあつし | Unknown | Unknown | defaultImage.png |
| Verruckt_Raimukun_IN.zip | Raimukun | Unknown | Unknown | defaultImage.png |
| volcanic_IN.zip | DETROakaルゼ | Unknown | Unknown | defaultImage.png |
| 夢の降る日に_IN.zip | seatrus | Unknown | Unknown | defaultImage.png |
| 尊師TheGuru_IN.zip | rider | Unknown | Unknown | defaultImage.png |
| 玩具狂奏曲終焉_IN.zip | きくお | Unknown | Unknown | defaultImage.png |
| 祈-我ら神祖と共に歩む者なり-_IN.zip | 光吉猛修VS穴山大輔… | Unknown | Unknown | defaultImage.png |

（`Unknown` 是这些包 `info.txt` 里的**字面值** `Illustrator: Unknown`，不是我们的兜底。）
汇总行：`19 首里：曲师被 META 补上 0、谱师 0、画师 3、曲绘文件名 0；补完仍无画师 1 首`。

### 2.3 人名 / 文件名不许串（踩过的坑）

`ChartMeta` 的两组键**没有任何交集**：

| META 键 | 落到 | 例 |
|---|---|---|
| `illustration` / `illustrator` / `cover` / `pictureBy` / `imageBy` / `illustBy` | **画师人名** → `SongEntry.illustrator()` | `keckyx` |
| `background` / `picture` / `image` | **文件名** → `SongEntry.pictureName()` | `9691407376462442.jpg` |

自测里还有一条硬断言：

```
  ✔ 人名 / 文件名没有互相污染（画师不长得像文件名，图名 ≠ 画师）
```

---

## 3. 惰性读取：什么时候读、缓存多久

**列表初始化（`SongEntry.scan`，界面 `init()` 里调）只读 `info.txt`**：
每个包开一次 `ZipFile`、只取 `info.txt`（几百字节），**不碰谱面 JSON**。
实测 19 个包（15 zip + 4 pez）合计 **43.9 ms**（`meta-fallback.txt` 第一行）。

**谱面 JSON 只在「选中那一首、要显示详情」时读**，走既有 `SongMeta` 的通道：

1. `SongSelectScreen.drawInfo()` 每帧调 `SongMeta.get(selectedEntry)`（`SongSelectScreen.java:641`）。
   - 命中缓存 → 直接返回；已经失败过 → 直接返回；**已经在解析 → 直接返回**（`LOADING` 集合去重，
     不会因为每帧调用而重复起线程）；
   - 都没命中 → 起**一个** daemon 线程（`phimc-songmeta`），本次返回 `null`，界面显示「…」。
2. 线程里做两件事（一次性）：
   - `ChartMeta.scan(json)`：流式扫 `META`（**读到就 break**）；
   - `ChartParser.parse(json)`：物量 / BPM（本来就要跑的那一趟）；
   - 完后 `entry.fillFromMeta(...)` 写回，下一帧信息栏就是真人名。
3. **缓存**：`SongMeta.CACHE`（`ConcurrentHashMap<包绝对路径, Meta>`）永久保留；
   `SongEntry.meta` 是 `volatile` 字段，跟着 `SongEntry` 活（`Session.rescan()` 重建列表时自然一起换）。
   失败进 `FAILED`/`LOADING`，不会重试、不会每帧重读。

**成本实测**（`meta-fallback.txt`）：

| 情况 | 谱面 JSON | `ChartMeta.scan` 耗时 |
|---|---|---|
| RPE（`META` 在文件前部，读到就停） | 13.5 MB | **0.05 ms** |
| 官方 PGR（压根没有 `META`，只能整份 `skipValue`）| 5.1 MB | **10.76 ms**（都是后台线程，不建对象） |

也就是说：这条通路的主要成本仍然是**本来就要跑的** `ChartParser.parse`（为物量/BPM），
META 兜底几乎不额外花钱；而且它**绝不在 `init()` / 扫描列表时发生**
（`chartJson()` 的全部调用点只有两处：`Session.start()` 真开局时、`SongMeta` 后台线程）。

---

## 4. 编译输出 + 两条 StressTest

```
> Task :compileJava
> Task :classes
> Task :jar
> Task :check UP-TO-DATE
> Task :remapJar
> Task :assemble
> Task :build

BUILD SUCCESSFUL in 23s
7 actionable tasks: 5 executed, 2 up-to-date
```

0 error / 0 warning（`C:\dsh-work\evidence\build.txt`）。

| 谱面 | 自动播放 | 不操作 | ±60ms |
|---|---|---|---|
| `pkg1\Chart_IN.json`（1285 音符 / 24 线） | **1000000 PHI，P=1285 G=0 B=0 M=0，连击 1285/1285** | 0 分，M=1285 | 509829 F |
| `pkg2\Chart.json`（1418 音符 / 24 线） | **1000000 PHI，P=1418 G=0 B=0 M=0，连击 1418/1418** | 0 分，M=1418 | 341844 F |

判定 / 计分 / 音符绘制一行未改（本次只动了「读 info.txt / META」和「信息栏取值」）。

---

## 5. 没做与原因

1. **`META.name` 不做兜底**（需求里列了 `name`）：`name` 决定 `display` → `title()` → **`chartId()`**
   （`曲名#难度`，是解锁 / 成绩 / 价格的键）。开局后按 META 改它会让存档里的解锁记录对不上，
   所以 `SongEntry.scan` 已有的「info.txt 没有 Name 就用文件名」继续充当兜底，META 的 `name`
   一个字节都没用。
2. **`META.level` 只用于「显示」**（`levelText()`），不参与 `grade` / `chartId`：同理，`grade`
   在扫描时就定了（`findGrade(level)`），改它等于改谱面 id。实测 4 个 pez 的 info.txt 都有 Level，
   这条兜底目前没有被触发。
3. **`Hardsync.pez` 的画师仍是「未知」**：它的 `META.illustration` 是空串，包里确实没写画师
   （不是读取失败）。不做「用别的包/文件名猜画师」这种臆造。
4. **没有把 META 读进 `Chart`**：那要改 `core/ChartParser`（给 `Chart` 加字段或开 `parse(JsonElement)`）。
   按「判定/计分一行不改」的硬约束，这次完全不碰 `core/`，改为在 `client/` 里流式扫一遍
   （代价 0.05 ms / 10.76 ms，见上表）。
5. **没有在真客户端点一遍**（本机无可交互客户端，与前几次一致）：字段正确性用 headless 检查
   跑真实谱包证明；界面上「未知 → 真人名」的显示逻辑是把 `SongSelectScreen:664–666` 从读字段
   改成调方法，没有改版式（坐标不变，仍是 `docs/REPORT-ui-lock-cover.md` 那两张表）。
6. **`songBy` / `chartBy` 等别名是照 `info.txt` 的解析口径顺手加的**，没有真实样本验证；
   4 个 pez 用的都是 `composer` / `charter` 两个标准键。

---

## 6. 时序 bug：重建后画师变回「未知」（已修）

用户报：**画师一开始显示正常，打了一局（或换了资源包）回来就变回「未知」**。

### 6.1 修前是怎么丢的（根因在代码里）

1. `SongEntry` 是 `SongEntry.scan()` **每次重建的新对象**（`SongEntry.java:204`）；
   META 兜底存在实例字段 `private volatile MetaFallback meta`（`:82`）上，重建就丢。
2. `SongMeta.CACHE` 按「包绝对路径」缓存，键没变、值还在（原来只存 `Meta(notes,bpm)`）。
3. **致命的一行**：`SongMeta.get(entry)` 命中缓存时
   ```java
   Meta hit = CACHE.get(key);
   if (hit != null) return hit;      // ← 命中了就直接返回，没有 entry.fillFromMeta(...)
   ```
   缓存里也**没有**兜底字段可回填（只存了物量/BPM），所以新对象永远填不上 → 退回
   info.txt 原值；RPE 的 info.txt 没有 `Illustrator:` ⇒ **「未知」**。
4. 走这条路径的入口：`SongSelectScreen.init()` 每次都 `session.rescan()`
   （`SongSelectScreen.java:244`）—— 打完一局点「退出」回曲库、从设置页回来、
   换资源包回来，全都是「新开界面 → init → rescan → 新对象」。

修前的 headless 复现（`tools/ui/MetaRescanTest.java`，证据 `C:\dsh-work\evidence\meta-rescan-before.txt`）：

```
=== 1) 首次 scan → SongMeta.get（触发后台解析） ===
  解析耗时 288 ms，物量=3092
  ✔ 首次：画师 = keckyx
=== 2) 再 scan（模拟打完一局返回）→ 对新对象【不调 get】直接读 ===
  重建耗时 6.0 ms；新对象 isLoading=false（说明没有偷偷解析）
  ✗ 重建后（不调 get）画师仍是 keckyx（实得「」）        ← 用户看到的就是这个
=== 3) SongMeta.clear()（模拟换资源包那条最坏路径）→ scan → get → 再 scan ===
  ✔ 清缓存后 scan：先回到「未知」（保持惰性，不解析）
  ✔ 清缓存后 get：画师恢复 keckyx
  ✗ 再次重建（不调 get）：画师仍 keckyx（实得「」）
结论: ✗ 2 项失败 —— 画师又变回「未知」了
```

### 6.2 修后（三处，都在 `client/`）

| 改动 | 位置 |
|---|---|
| **缓存里连兜底一起存**：`record Cached(Meta meta, SongEntry.MetaFallback fallback)` | `SongMeta.java:49` |
| **命中缓存必须回填**：`entry.fillFromMeta(hit.fallback())` 再 `return hit.meta()` | `SongMeta.java:75–78` |
| 解析完成写缓存时把兜底一起写进去 | `SongMeta.java:99` |
| **`SongEntry.scan()` 扫完立刻回填**：`SongMeta.refillAll(out)`（**只查缓存，不解析**） | `SongEntry.java:235` + `SongMeta.refillAll` `:119–127` |
| `fillFromMeta` 幂等（同实例直接返回，挡住每帧的 volatile 写） | `SongEntry.java:162` |
| `SongMeta.clear()`（清 CACHE/LOADING/FAILED，清完下次 `get` 会重新解析并重新回填） | `SongMeta.java:136–145` |
| `applyResPack(...)` 换包时调 `SongMeta.clear()`（唯一「重新加载一切」的钩子） | `PhiMCClient.java:157–159` |
| `init()` 里注明顺序依赖：`rescan()`（从缓存回填）必须早于 `ensureResPack()`（可能 clear） | `SongSelectScreen.java:240–244` |

**惰性没有被破坏**：`refillAll` 只做 `CACHE.get(path)` + 一次 volatile 赋值；
缓存里没有的条目什么都不做，仍然等玩家选中那一首时由 `get` 去后台解析。
重建 19 个包的实测耗时 **6–7 ms**（就是扫 info.txt 的时间，没有任何谱面 JSON 解析）。

`CoverTextures` 的 `NO_PICTURE` 也顺带核过：它按**包绝对路径**做键，重建不变；
而重建后的对象现在在 `scan()` 里就带上了 `META.background`（`pictureName()` 非空），
所以「info.txt 没写 `Picture:` 的包」在重建后**不会错过重试**，不需要额外改动。

### 6.3 修后时序断言（同一个程序，证据 `C:\dsh-work\evidence\meta-rescan-after.txt`）

```
=== 1) 首次 scan → SongMeta.get（触发后台解析） ===
  解析耗时 253 ms，物量=3092
  ✔ 首次：画师 = keckyx
=== 2) 再 scan（模拟打完一局返回）→ 对新对象【不调 get】直接读 ===
  重建耗时 7.2 ms；新对象 isLoading=false（说明没有偷偷解析）
  ✔ 重建后（不调 get）画师仍是 keckyx
  ✔ 重建后没有起后台解析（保持惰性）
  ✔ 重建后是新对象（对照：确实换了实例）
=== 3) SongMeta.clear()（模拟换资源包那条最坏路径）→ scan → get → 再 scan ===
  ✔ 清缓存后 scan：先回到「未知」（保持惰性，不解析）
  ✔ 清缓存后 get：画师恢复 keckyx
  ✔ 再次重建（不调 get）：画师仍 keckyx
结论: ✔ 时序断言全部通过（重建 / 清缓存 / 再重建，画师都不丢）
```

19 个包的字段表重跑，结果与第 2 节一致（`keckyx` / `ファル公` / `9191919191919191919191` 都还在，
15 个官方 zip 一字未变）；`SongEntry.scan` 34.2 ms，`META` 流式扫描 0.04 ms（RPE）/ 11 ms（无 META 的 PGR）。