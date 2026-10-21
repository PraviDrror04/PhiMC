# 报告：统一曲库 UI / 锁定状态 + 拷芯片 / pez 曲绘 / 曲师·谱师·画师

> 后续追加的需求见另外两份报告：
> - `docs/REPORT-autoplay-unlock.md`：**自动游玩 AUTO 开关**、**谱面播放器打开时全谱面解锁**。
>   本文件描述的「底部 5 个按钮」现在是 **6 个**（多了「[✓] 自动游玩」，紧挨在「开始」右边），
>   底部坐标以那份为准。
> - `docs/REPORT-meta-fallback.md`：**曲师/谱师/画师的 META 兜底**（RPE 的 info.txt 没有
>   Illustrator，画师名在谱面 JSON 的 META 里）。信息栏那 4 行的**版式没变**，取值的来源变了
>   （现在是 `SongEntry.composer()/charter()/illustrator()/levelText()`）。

四个问题都在 `mod/src/main/java/dev/dsh/phimc/client/`。本报告按交付项的六条组织。
本次改动**没有碰判定 / 计分 / 音符绘制**（`core/` 一行未改，`PlayScreen` 只改了一处构造调用）。

---

## 1. 旧 UI 删了哪些文件 / 方法（行号）

结论：**没有删文件**，`TabletScreen` 从「曲库 + 商店双页签」缩成**只管商店的容器页**；
平板右键不再进它，而是直接进 `SongSelectScreen`。旧的纯文字曲库列表整段删除。

### 1.1 删除清单

| 位置（删除前 `client/TabletScreen.java`，全文 447 行） | 内容 | 现在 |
|---|---|---|
| 81 行 | `private enum Tab { LIBRARY, SHOP }` | 删（只剩商店，不再有页签态） |
| 83–88 行 | `private record Line(String title, SongEntry entry, int songIndex)`（曲库「分组标题 + 谱面行」模型） | 删 |
| 90 行 | `private Tab tab = Tab.LIBRARY;` | 删 |
| 96–97 行 | `private List<Line> pageLines`（曲库页要画的行走缓存） | 删 |
| 62–66 行 | 曲库行专用常量 `LIB_START_W`、`COPY_DX`、`COPY_W` | 删（新的拷芯片按钮常量在 `SongSelectScreen`） |
| 71 行 | `RIGHT_DX`（曲库成绩列起点） | 删 |
| 76 行 | `C_LOCKED`（曲库「???」用色） | 删（`SongSelectScreen.C_LOCKED` 自带） |
| 133–138 行 | 页签「曲库」按钮（本地切 `tab = LIBRARY` + `clearAndInit`） | 改成**跳转**：`client.setScreen(new SongSelectScreen(TabletScreen::new))` |
| 148–174 行 | 曲库内容分支：`libraryLines(...)` 分页 + 每行「难度」按钮 `LIB_START_W` + 行内「拷芯片」按钮 | 删（整段） |
| 228–245 行 | `private static List<Line> libraryLines(List<SongEntry> songs)`（按曲目分组） | 删 |
| 250–253 行 | `hintText(...)` 里的曲库说明分支（谱面数 / 已解锁 / 世界种子） | 删（`hintText` 只剩商店一行） |
| 278–287 行 | `private void startChart(int songIndex)`（曲库行「开始」） | 删（等价逻辑是 `SongSelectScreen.startSelected()`） |
| 289–306 行 | `private void copyToChip(Profile, String)`（曲库行「拷芯片」） | 删（挪到 `SongSelectScreen.copyRow(int)`，见第 2 节） |
| 347–351 行 | `render(...)` 里 `if (tab == LIBRARY) renderLibrary(...) else renderShop(...)` | 删（只调 `renderShop`） |
| 358–393 行 | `private void renderLibrary(DrawContext, Profile, int cx)`（旧的纯文字行渲染：`???` / 商店有售 / 成绩列） | 删 |
| 3、4、17–20 行的 import | `Judgement`、`ChipItem`、`ArrayList`、`LinkedHashMap`、`Map` | 删（不再需要；`Item`/`ItemStack`/`PriceTable`/`Wallet` 商店还用） |

`TabletScreen.java`：447 行 → **295 行**。`SongSelectScreen.java`：409 行 → **622 行**（多了锁定、拷芯片、三个作者字段、提示行）。

### 1.2 现在的入口关系

```
平板右键  TabletItem.use()  →  PhiMCClient.openTablet()   （client/PhiMCClient.java:97）
          → new SongSelectScreen(TabletScreen::new)            （第 100 行）
                「关闭」按钮 = 「商店」→ close() → client.setScreen(new TabletScreen())

P 键 / 谱面物品  →  openSongSelect(false) → new SongSelectScreen()（第 81–88 行）
谱面物品潜行右键 →  openSongSelect(true)  → 直接 session.startLast() 重打上一首（第 84 行）

结算界面「退出」→  new SongSelectScreen()（PlayScreen.java:645，唯一改动的一行）
商店页「曲库」页签 → new SongSelectScreen(TabletScreen::new)（TabletScreen.java:110–113）
```

`SongSelectScreen` 的 `backTo`（`Supplier<Screen>`，第 106 / 126 行）就是「返回时回平板」的实现：
非空时底部第 3 个按钮从「关闭」变成「商店」，`close()` 走 `backTo.get()`；为空时 `setScreen(null)` 回游戏。

顺带清掉一个**死参数**：`SongSelectScreen(boolean autoStartLast)` 的 `autoStartLast` 以前只被存下来、
从来没人读。现在这个语义落到 `PhiMCClient.openSongSelect(boolean)` 里真正生效（潜行右键 = 重打上一首），
构造函数简化为 `SongSelectScreen()` / `SongSelectScreen(Supplier<Screen> backTo)`。

---

## 2. 新 UI：锁定状态与「拷芯片」按钮

### 2.1 选择：未解锁的行**不生成**按钮（不是变灰禁用）

| 做法 | 为什么不用 / 用 |
|---|---|
| 变灰禁用（旧平板界面是这么做的） | 20px 高的行里，56×16 的灰按钮和灰字挤在同一行，**看不清是「按钮禁用」还是「界面坏了」** |
| **不放按钮**（本次采用） | 右侧同一个槽位直接写灰字「未解锁」，状态一眼可见；点了也只有「未解锁：XXX（先去商店解锁）」一行提示 |

### 2.2 代码位置

| 内容 | 位置 |
|---|---|
| 锁定判定（唯一闸门） | `SongSelectScreen.java:283`（`unlocked(int)`）、`:292`（`unlocked(SongEntry)`）→ `Profile.isUnlocked(chartId)` |
| 开始前的拦截 | `startSelected()` `:315–330`：未解锁写 `notice = "未解锁：…（先去商店解锁）"` 并**不**进 `PlayScreen` |
| 「开始」按钮变灰 | `syncButtons()` `:261`：`startBtn.active = … && unlocked(selected)` |
| 行内「未解锁」灰字 | `drawList()` `:498–503`（右对齐到 `width-PAD-4`）、行标签灰 `:516` |
| 「拷芯片」按钮创建 | `init()` `:234–245`（每页 `perPage()` 个，尺寸 56×16，x = `copyX()`） |
| 「拷芯片」显示/隐藏 | `syncButtons()` `:263–270`：`visible = 该行有谱面 && 已解锁` |
| 「拷芯片」点击 | `copyRow(int row)` `:339–355` → `ChipItem.carveHeld(client.player, profile, chartId)` → `CarveResult` → `notice` |
| 详情面板「状态」行 | `drawInfo()` `:571`（值：`已解锁` 绿 / `未解锁` 灰），颜色 `:580–582` |
| 提示行渲染 | `render()` `:475–478`，位置 `noticeY()` `:187` = `listBottom()-12+2` |

提示用的是**界面内底部提示行**（`notice`），不是聊天栏 —— 理由：动作条（`inGameHud.setOverlayMessage`）
在打开 GUI 时被 GUI 盖住，玩家看不到；而且 `ChipItem` 自己的右侧物品提示已经被动作条占了，
这里再抢会互相覆盖。

### 2.3 布局坐标表（1280×720 与 854×480）

常量在 `SongSelectScreen.java:67–92`，核对脚本 `tools/ui/select-layout.ps1`（跑一次会打印这两张表并断言不重叠）。

| 项目 | 1280×720 | 854×480 |
|---|---|---|
| `PAD` / `HEADER_H`（标题 y=8、副标题 y=20） | 12 / 34 | 12 / 34 |
| 曲绘方框 `S = max(48, round(H×3/16))` | **135** | **90** |
| 信息列所需高度 `infoNeed = 18+7×12+9+4` | 115 | 115 |
| 详情区高度 `detailH = max(S, infoNeed)` | 135 | **115**（窄屏由信息列决定） |
| 详情区 y（`dTop` … `dBottom`） | 34 … 169 | 34 … 149 |
| 信息列 `x` / 右边界 | 161 / 1268 | 116 / 842 |
| 曲名（1.5 倍）y | 34 | 34 |
| 信息 8 行 y（步长 12） | 52 … 136 | 52 … 136 |
| 信息最后一行底 | 145 ≤ 169 ✔ | 145 ≤ 149 ✔ |
| 列表 y（`lTop` … `lBottom = H-30`） | 179 … 690 | 159 … 450 |
| 每页行数 / 最后一行底 | 20 / 617 | 12 / 421 |
| 列表行 i：`y = lTop + i×22`，高 20 | 179 + i×22 | 159 + i×22 |
| **「拷芯片」按钮** `x = W-PAD-4-56` | **x=1208..1264**, y = 行顶+2, 56×16 | **x=782..838**, y = 行顶+2, 56×16 |
| 行内「未解锁」/ 最佳成绩 右边界（`copyX-8`） | 1200 | 774 |
| 提示行 y（`lBottom-12+2`） | 680 … 689 | 440 … 449 |
| 底部 5 按钮（70×20，间距 8，居中） | x0=449, y=694..714 | x0=236, y=454..474 |

不重叠断言（`select-layout.ps1` 全过）：
详情面板底 175 < 列表顶 179（720）/ 155 < 159（480）；最后一行底 617/421 ≤ `lBottom-NOTICE_H` = 678/438；
提示行底 689/449 ≤ `lBottom` 690/450 且 < 底部按钮顶 694/454；拷芯片按钮最后一行底 ≤ 提示行顶；
信息列最后一行在详情区内；曲绘底 ≤ 详情区底。

预览图（脚本生成，不进仓库）：`C:\dsh-work\ui-check\select-layout-1280x720.png`、
`select-layout-854x480.png`。

> 实现细节：列表底色画在 `renderBackground()`（`:423–427`）里，而不是 `render()` 末尾。
> `Screen.render` 的顺序是 `renderBackground → 子控件`，行底色画晚一点就会把行内的「拷芯片」按钮盖住；
> 画早一点又会被模糊背景盖住。`SongSelectScreen:418–421` 有注释说明。

---

## 3. pez 读不到曲绘（真 bug）

### 3.1 根因

`NativeImage.read` 里的 stb_image 在 Minecraft 中**只编进了 PNG 解码器**：

```
java.io.IOException: Bad PNG Signature
    at net.minecraft.util.PngMetadata.validate(PngMetadata.java:44)
    at net.minecraft.client.texture.NativeImage.read(NativeImage.java:139)
```

RPE（`.pez`）包的曲绘是 **JPEG**，而且**后缀不可信**：实测 4 个 pez 里 3 个曲绘是 JPEG，
其中一个（`推活天使ビビアンちゃん .pez` 的 `7876823621765096.png`）**名字是 .png、内容是 JPEG**。

### 3.2 修法（按魔数判格式，JPEG→PNG 转码）

新文件 `client/CoverCodec.java`（163 行，纯 JDK：`javax.imageio` + `java.awt.image`，不碰 Minecraft 类）：

| 内容 | 位置 | 说明 |
|---|---|---|
| PNG 魔数 `89 50 4E 47` | `CoverCodec.java:80` `isPng` | 前 4 字节，忽略 `0D 0A 1A 0A` |
| JPEG 魔数 `FF D8` | `CoverCodec.java:86` `isJpeg` | SOI，后面通常是 `FF E0` JFIF |
| 判定 | `CoverCodec.java:72` `detect` → `NONE/PNG/JPEG/OTHER` | **只看内容，不看后缀** |
| 转码 | `CoverCodec.java:115–135` `toPng` | PNG 原样返回（同一数组，零拷贝）；JPEG/其它 → `ImageIO.read` → `ImageIO.write(img,"png",…)`；`TYPE_CUSTOM`（CMYK 之类）先拷进 `TYPE_INT_ARGB` 再写一次；失败返回 `null` |
| 魔数日志格式 | `:91` `magicHex` | 失败时把魔数打进日志 |

接入点 `client/CoverTextures.java:180–196` `readImage(byte[])`：

```java
CoverCodec.Format format = CoverCodec.detect(bytes);
byte[] png = CoverCodec.toPng(bytes);
if (png == null || png.length == 0) { LOG.warn(...); return null; }   // → 占位色块
ByteBuffer buf = MemoryUtil.memAlloc(png.length);
try { buf.put(png).flip(); return NativeImage.read(buf); }
finally { MemoryUtil.memFree(buf); }                                   // 直接缓冲一定释放
```

`NativeImage.read(byte[])` 仍然不用（会走 LWJGL `MemoryStack` 64 KB，2.6 MB 的曲绘必炸），
`read(ByteBuffer)` + `memAlloc/memFree` 的写法保持不变。

### 3.3 验证：`tools/cover/CoverDecodeCheck.java`（跑真代码，不是复制品）

它调 `CoverCodec`（生产代码）+ **真的 `NativeImage.read`**（Minecraft 的裁过的 stb_image），
对每个包分别测「修复前」（原样喂）和「修复后」（先转 PNG）。

命令（classpath 用 loom 缓存的 named MC jar + LWJGL；脚本里附了完整路径）：

```
java -cp "<out>;<minecraft-merged-named.jar>;<lwjgl>-<lwjgl-stb>-<natives>-<mojang-logging>-<slf4j>" \
     CoverDecodeCheck C:\dsh-work\payload
```

`C:\dsh-work\payload` 里的全部 19 个谱面包（**15 个 .zip + 4 个 .pez**）：

| 谱面包 | 曲绘条目 | 魔数 | 判定 | 修复前 `NativeImage.read` | 修复后（转 PNG 再读）尺寸 |
|---|---|---|---|---|---|
| AvataarReincarnationofKalpa_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| CROSSSOUL_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| **Cyberneko Pulse (2).pez** | `9691407376462442.jpg` | **FF D8 FF E0** | **JPEG** | **失败 Bad PNG Signature** | **OK 1600×900** |
| DeadSoul_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| DistortedFate_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| ERABYECONNEC10N_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| **Hardsync.pez** | `1939818528944948.jpg` | **FF D8 FF E0** | **JPEG** | **失败 Bad PNG Signature** | **OK 1672×941** |
| iLArtifact_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| PANICPARADISE_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| PRAGMATISMRESURRECTION_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| ReEndofaDream_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| Verruckt_Raimukun_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| volcanic_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| 夢の降る日に_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| 尊師TheGuru_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| **推活天使ビビアンちゃん .pez** | `7876823621765096.png`（**内容是 JPEG**） | **FF D8 FF E0** | **JPEG** | **失败 Bad PNG Signature** | **OK 2560×1440** |
| 玩具狂奏曲終焉_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |
| **琵琶曲DJ(1.15x).pez** | `3430094183784295.png` | 89 50 4E 47 | PNG | OK 1401×871 | OK 1401×871 |
| 祈-我ら神祖と共に歩む者なり-_IN.zip | defaultImage.png | 89 50 4E 47 | PNG | OK 2048×1080 | OK 2048×1080 |

```
合计 19 个包（有曲绘 19 个；其中 .pez 4 个，曲绘是 JPEG 的 3 个）
修复前（裸 NativeImage.read）成功 16/19 —— JPEG 全部失败，就是用户看到的占位色块
修复后（CoverCodec.toPng → NativeImage.read）成功 19/19
结论: ✔ 有曲绘的包全部解码成功
```

**关于「6 个 pez」**：`C:\dsh-work\payload` 里实际是 **4 个 `.pez` + 15 个 `.zip`（共 19 个包）**，
不是 6 个。机器上第 5 个 `.pez` 是 `C:\dsh-work\phimc\mod\run\config\phimc\songs\Verruckt_Raimukun_IN.pez`
（Phira 形态：`chart.json` + `music.wav` + `info.yml`，**包里没有曲绘**，正确走占位色块）。
所以「全部跑一遍」= 19 个有曲绘的包 **19/19 成功**（其中 JPEG 3/3 从失败变成功），另外单独跑了那个无曲绘的 pez。
证据：`C:\dsh-work\evidence\cover-decode.txt`、`cover-decode-pez.txt`。

---

## 4. 显示曲师 / 谱师 / 画师

### 4.1 数据的来源

`SongEntry`（`client/SongEntry.java`）新增 `illustrator`（第 57 行字段，第 69/77 行构造，
第 196 行 `Info` 记录，第 250 行解析）：

| 键 | 落到 | 备注 |
|---|---|---|
| `Composer:` / `SongBy:` | `composer` | 曲师 |
| `Charter:` / `ChartBy:` | `charter` | 谱师 |
| `Illustrator:` / `PictureBy:` / `ImageBy:` | `illustrator`（**新**） | 画师**人名** |
| `Picture:` / `Illustration:` / `Image:` / `Cover:` | `picture` | 曲绘**文件名**（不认 `Illustrator:`，否则会拿人名当文件名找图） |

真谱包实测（`tools/ui/SongEntryMetaCheck.java`，19 个包）：

```
19 首里有：曲师 19、谱师 19、画师 15、曲绘声明 19
```

官方 PGR 包（`.zip`）的 `info.txt` 里 `Illustrator: Unknown` 是**字面值**，所以显示 `Unknown`；
4 个 `.pez` 没有 `Illustrator:` 键 → 显示「未知」。这正是「缺失显示未知」的两种情形。

### 4.2 详情面板版式（坐标表）

信息列 8 行，行距 `INFO_STEP = 12`，第一行 `y = dTop + 18`（让开 1.5 倍字号曲名），标签列宽 `LABEL_W = 34`：

| 行 | 标签 | 1280×720 | 854×480 | 值（缺失时） | 颜色 |
|---|---|---|---|---|---|
| — | 曲名（1.5 倍） | x=161, y=34 | x=116, y=34 | `title()` | 白 / 未解锁时灰 |
| 1 | 难度 | y=52 | y=52 | `level`（没有 `—`） | 亮蓝 `C_ACCENT` |
| 2 | 物量 | y=64 | y=64 | `SongMeta.notes()` | 白 |
| 3 | BPM | y=76 | y=76 | `SongMeta.bpmText()` | 白 |
| 4 | **曲师** | y=88 | y=88 | `composer` → **`未知`** | 白；`未知` 时灰 |
| 5 | **谱师** | y=100 | y=100 | `charter` → **`未知`** | 白；`未知` 时灰 |
| 6 | **画师** | y=112 | y=112 | `illustrator` → **`未知`** | 白；`未知` 时灰 |
| 7 | 状态 | y=124 | y=124 | `已解锁` / `未解锁` | 绿 `0x7CFC8A` / 灰 `0x6B6B6B` |
| 8 | 最佳 | y=136 | y=136 | `Records.Rec` → `未游玩` | 金 `C_GOLD`（FC）/ 亮蓝 |

值列起点 = `x + LABEL_W` = 195（720）/ 150（480），值列右边界 = `W - PAD` = 1268 / 842；
最后一行底 145，详情区底 169（720）/ 149（480），**都不越界**。
`1280×720` 与 `854×480` 的完整不重叠断言见第 2.3 节的表（同一个脚本一起验的）。

---

## 5. 编译输出 + 两条 StressTest

### 5.1 `gradle build`（0 错误）

```
robocopy "…\phimc" C:\dsh-work\phimc /MIR /XD out .gradle build fixtures dist run
$env:JAVA_HOME="C:\dsh-tools\jdk21"; $env:GRADLE_USER_HOME="C:\dsh-gradle-home"
gradle -p C:\dsh-work\phimc\mod build --no-daemon --console=plain --offline
```

```
> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :jar
> Task :compileTestJava NO-SOURCE
> Task :processIncludeJars UP-TO-DATE
> Task :sourcesJar
> Task :validateAccessWidener NO-SOURCE
> Task :check UP-TO-DATE
> Task :remapJar
> Task :remapSourcesJar
> Task :assemble
> Task :build

BUILD SUCCESSFUL in 20s
7 actionable tasks: 4 executed, 3 up-to-date
```

无 `error`、无编译 `warning`（`C:\dsh-work\evidence\build.txt`）。

### 5.2 StressTest ×2（autoplay 仍 1000000 / 0 Miss）

```
java -cp "out-stress;gson;jlayer" dev.dsh.phimc.core.StressTest <chart>
```

| 谱面 | 音符 / 判定线 | 自动播放 | 不操作 | ±60ms 抖动 |
|---|---|---|---|---|
| `C:\dsh-work\pkg1\Chart_IN.json`（官方 PGR 回归） | 1285 / 24 / 117.7s | **1000000 PHI，P=1285 G=0 B=0 M=0，连击 1285/1285** | 0 分，M=1285/1285 | 509829 F（P484 G358 B45 M398） |
| `C:\dsh-work\pkg2\Chart.json`（官方 PGR 回归） | 1418 / 24 / 150.9s | **1000000 PHI，P=1418 G=0 B=0 M=0，连击 1418/1418** | 0 分，M=1418/1418 | 341844 F（P451 G131 B21 M815） |

证据：`C:\dsh-work\evidence\stress-pkg1.txt`、`stress-pkg2.txt`。`core/` 本次一行未改。

### 5.3 其他自测（顺手跑的）

- `tools/ui/select-layout.ps1`：两个分辨率版式核对 + 断言 + 预览 PNG → **都通过**。
- `tools/cover/CoverDecodeCheck.java`：19/19 曲绘解码成功（第 3 节表）。
- `tools/ui/SongEntryMetaCheck.java`：19 个包的曲师/谱师/画师解析（第 4 节）。

---

## 6. 没做与原因

1. **没有在真客户端里点一遍界面**。本机没有可交互的 Minecraft 客户端（前几次交付也是靠
   `tools/ui/*.ps1` 出坐标表 + 预览图核对）。版式的证据是「常量 → 坐标表 → 不重叠断言 → 预览图」，
   真机行为（点击 / 翻页 / 拷芯片的成功提示）只做了代码级核对。要真机验证锁定状态，在
   `config/phimc/unlockall.txt` 存在时全部视为已解锁（`Profile.devUnlockAll()`），
   没有这个文件时只有 `profile.json` 里解锁过的谱面能开始 —— 这与旧平板界面的规则一致。
2. **没有给「未解锁行」加变灰禁用的拷芯片按钮**（选了「不放」，理由见 2.1）。
3. **没有在详情面板里再放一个「拷芯片」按钮**。需求写的是「已解锁的**行**加一个拷芯片按钮」，
   行内已经每行一个；详情面板再放一个会有两个入口对同一操作，反而容易点到没选中的那首。
4. **没有改 `docs/DESIGN-survival.md`**。它 §3.1 描述的「平板曲库页」是历史设计稿，
   按需求这次只删代码；报告（本文件）才是当前实现的说明。
5. **没有提交任何图片**。曲绘只从用户自己的谱面包读；预览图写在 `C:\dsh-work\ui-check\`
   （仓库外）。仓库里新增的两个文件都是源码/脚本：
   `tools/cover/CoverDecodeCheck.java`、`tools/ui/SongEntryMetaCheck.java`、`tools/ui/select-layout.ps1`。
6. **`Picture:` 声明了但包里找不到图**时仍然走「后缀唯一 → 关键字」的兜底（沿用原有逻辑），
   没有额外报错；这种情况只会在日志里留一条 `曲绘解码失败：…`。
7. **BMP / GIF 曲绘**没有单独做样本验证（payload 里没有这两种），代码走的是同一条
   `ImageIO` 兜底路径；失败也只是占位色块，不会崩。
