# 「还有一批特效谱跑不了」——定位与修复报告

> 参考实现：`C:\dsh-work\phira-src\prpr\src\`（Phira **v0.8.2**，GPL-3.0）。
> 本文里的 `line.rs:NN` / `rpe.rs:NN` / `pec.rs:NN` / `chart.rs:NN` 都是 `prpr/src/` 下的文件与行号。
> 标「**源码无此项**」的是 Java/MC 平台特有的处理，写清了理由。
>
> 量具（全部 headless，纯 Java）：
> `tools/chart/RunnableScan.java`（全库「能否跑」标签）、`tools/chart/PlaybackProbe.java`
> （音频修前/修后逐首对比）、`tools/chart/OggProbe.java`（OGG 解码与 JavaSound 判定）、
> `tools/chart/ZipProbe.java`（坏 ZIP 的 CPL/ZIP 头诊断）、`tools/chart/RunnableTex.java`
> （贴图名命中率 + scale 口径）、`tools/chart/PerfProbe.java`（每帧/载入开销）。
> 原始输出在 `C:\dsh-work\{runnable-before,runnable-after,playback-after}.txt`。

---

## 0. 一页结论

用户点名的两首**都不是解析失败、也不是卡顿**，而是「能进游戏但一动不动」：

| | `39417_Sphalerite.pez` | `78805_enchanted love.pez`（用户记成 "enhanced heart"，包里确实没有这个名字，应是它） |
|---|---|---|
| 谱面解析 | rpe，2069 音符 / 41 线，100ms | rpe，700 音符 / 287 线，120ms |
| **「跑不了」的类型** | **音频不支持**（音乐是 `.ogg`） | **音频不支持**（音乐是 `.ogg`） |
| 表现 | 音乐放不出来 → **谱面时间恒为 -0.15s**，音符永远不来、按什么也没反应 | 同左 |
| 日志实证 | `音频播放失败: Sphalerite · IN Lv.16` + `UnsupportedAudioFileException: Stream of unsupported format`；随后 `暂停 @ -0.150s（音频 0.000s）` | 同左（`enchanted love · STORY Lv.14`） |

全库 46 个谱面包，修前 **42 可玩 / 4 首音频不支持 / 2 个包根本读不出来**，修后 **46/46 可玩**：

| 类别 | 数量 | 谱面 |
|---|---|---|
| **音频不支持**（`.ogg` 音乐） | **4** | `14143_「僕は…」`、`39417_Sphalerite`、`48273_Undertale`、`78805_enchanted love` |
| **包读取失败**（ZIP 条目名非 UTF-8） | **2** | `iLArtifact_IN.zip`、`volcanic_IN.zip`（选曲界面里<b>看不到</b>） |
| 解析失败 / 卡顿 | 0 | — |

本次修了三类：

1. **OGG 音乐**（4 首「跑不了」的真凶）；
2. **ZIP 条目名编码**（2 个包在列表里消失了）；
3. **贴图判定线**（`Texture != line.png`）——这两首是「特效谱」，`enchanted love` 287 条线里
   **250 条**、`Undertale` 177 条里 **129 条**是贴图线，以前一条都不画 ⇒ 就算音频修好，画面也是空的。

`gradle :1.21.1:build` **BUILD SUCCESSFUL**；`StressTest` 仍 **1000000 / PHI / 0 Miss**。

---

## 1. 全库「能否跑」标签表（修前 → 修后）

判定方式（`RunnableScan`）：按 `Session.start()` 的真实顺序走一遍 ——
① 用默认 charset 开 ZIP、② 读谱面并 `ChartParser.parse`、③ 看音频魔数、④ 建 `Engine` 并按 60fps 抽样跑一遍
—— 再打标签。

修前（`--before`）：

```
合计: 可玩 42 / 音频不支持 4 / 包读取失败 0 / 解析失败 0 / 卡顿 0
```

> 表里「包读取失败 0」是因为工具有 charset 回退、能看到包里面；而**运行时**没有回退，
> 所以那 2 个包在游戏里是「读不出来 → 从列表消失」（见 §3 的日志）。修前运行时的真实情况是
> **可玩 42 / 音频不支持 4 / 包读取失败 2**。

关键行（修前）：

```
14143_「僕は…」.pez        rpe 928/60    ogg/OggS   OK   音频不支持 0.024ms/帧  ← 进游戏后时钟停在 -0.15s  [贴图线0 文字线10 gif0 color22]
39417_Sphalerite.pez      rpe 2069/41   ogg/OggS   OK   音频不支持 0.048ms/帧  ← 进游戏后时钟停在 -0.15s
48273_Undertale.pez       rpe 1307/177  ogg/OggS   OK   音频不支持 0.022ms/帧  ← 进游戏后时钟停在 -0.15s  [贴图线129 文字线14 gif0 color49]
78805_enchanted love.pez  rpe 700/287   ogg/OggS   OK   音频不支持 0.019ms/帧  ← 进游戏后时钟停在 -0.15s  [贴图线250 文字线0 gif1 color286]
iLArtifact_IN.zip         pgr 1279/24   wav/WAV    GBK  可玩（运行时读不出来，见 §3）
volcanic_IN.zip           pgr 1178/15   wav/WAV    GBK  可玩（运行时读不出来，见 §3）
```

修后：

```
合计: 可玩 46 / 音频不支持 0 / 包读取失败 0 / 解析失败 0 / 卡顿 0

14143_「僕は…」.pez        rpe 928/60    ogg/OggS→PCM OK  可玩 0.023ms/帧
39417_Sphalerite.pez      rpe 2069/41   ogg/OggS→PCM OK  可玩 0.036ms/帧
48273_Undertale.pez       rpe 1307/177  ogg/OggS→PCM OK  可玩 0.016ms/帧
78805_enchanted love.pez  rpe 700/287   ogg/OggS→PCM OK  可玩 0.016ms/帧
iLArtifact_IN.zip         pgr 1279/24   wav/WAV      GBK  可玩（openZip 自动回退）
volcanic_IN.zip           pgr 1178/15   wav/WAV      GBK  可玩（openZip 自动回退）
```

---

## 2. 根因 A：OGG 音乐 —— 时钟永远停在 -0.15s

### 2.1 链条（三处都对不上）

1. `SongEntry.payload()` 把谱面包里的音乐**原样**返回（`<id>.ogg`，7.5MB）；
2. `Session.start()` 把它贴个 `"wav"` 标签交给 `WavSource.play(...)`；
3. `WavSource.play`（`core/audio/WavSource.java:68`）只特判了 **MP3**（`Mp3ToWav.isMp3`），
   其余一律走 `AudioSystem.getAudioInputStream` —— **JavaSound 没有任何 Ogg provider**，
   于是抛 `UnsupportedAudioFileException: Stream of unsupported format`。

而 `Session.start()` 里那个后台线程的 `catch` 只打了一行日志：

```java
} catch (Throwable t) {
    LOG.warn("[PhiMC] 音频播放失败: {}", entry.display, t);   // ← 就到这里为止
}
```

`audio` 字段还留着那个**从没 `start()` 过**的 `WavSource`，它的
`positionSeconds()` = `baseOffset` = **0**（`WavSource.java:241`），于是
`Session.seconds()` = `max(0,0) + chart.offset(-0.15) + profile.offset` = **-0.15 恒定**，
`lastSeconds` 只增不减 ⇒ **谱面永远停在 -0.15s**：音符不来、判定不发生、按键没反应。
日志里的 `暂停 @ -0.150s（音频 0.000s）` 就是现场证据。

> 讽刺的是 `mod/.../client/audio/OggToWav.java` **本来就是为此写的**（stb_vorbis 解码 + 包 WAV），
> 但全项目**没有任何地方调用它**（`grep OggToWav` 只有它自己）——纯死代码。

### 2.2 修法

| 文件 | 改动 |
|---|---|
| `client/audio/OggToWav.java` | 新增 `isOgg(byte[])`；`decode()` 改成**按块循环解码直到读不出东西**，不再信任 `stb_vorbis_stream_length_in_samples`（实测 `「僕は…」` 首个 Ogg page 的 granule 是垃圾值 `0xE5FC000000000000`，按它分配缓冲会解出 0 帧）；解出 0 帧时抛明确异常 |
| `client/Session.java` | 新增 `toPlayable(SongEntry.Audio)`：`OggS` → `OggToWav.decode` 成 WAV，其它原样透传；**转换结果存进 `cachedPayload`**（`restart()` 复用，不重复解码）；播放失败 → **墙钟兜底**（`WallClock(chart.duration)`），并把 `audio` 换成它（字段改成 `volatile`，让渲染线程看见） |
| `core/audio/WavSource.java` | 非 MP3 且魔数是 `OggS` 时直接抛<b>能定位</b>的异常（`"OGG 音频需要先经 OggToWav.decode() 解成 WAV"`），不再只是一句 `Stream of unsupported format` |

> 「失败就退化成墙钟」是**平台策略**（`prpr` 不存在音频解码失败这条路）：
> 宁可无声也要让谱面继续走 —— 冻结时间轴是所有表现里最糟的一种。

### 2.3 修前 / 修后（`PlaybackProbe`，逐首把音乐按生产逻辑处理一遍）

```
谱面                                    包打开   音频      修前(裸字节→AudioSystem)        修后(转换→AudioSystem)
14143_「僕は…」.pez                      UTF-8    OggS/ogg  挂:UnsupportedAudioFileException  OK(ogg→wav 178.5s)
39417_Sphalerite.pez                    UTF-8    OggS/ogg  挂:UnsupportedAudioFileException  OK(ogg→wav 158.3s)
48273_Undertale.pez                     UTF-8    OggS/ogg  挂:UnsupportedAudioFileException  OK(ogg→wav 382.4s)
78805_enchanted love.pez                UTF-8    OggS/ogg  挂:UnsupportedAudioFileException  OK(ogg→wav 141.5s)
--- 其余 42 首（mp3 / wav）两列都是 OK，未受影响 ---
修前能播 42 / 修后能播 46 / 共 46 个包
```

解出来的时长与谱面时长对得上（偏差都在「音频比谱面长」的正常范围内）：

| 谱面 | 谱面时长 | 解出音频 | 采样率/声道 |
|---|---|---|---|
| `14143_「僕は…」` | 177.3s | 178.5s | 44100 / 2 |
| `39417_Sphalerite` | 154.2s | 158.3s | 48000 / 2 |
| `48273_Undertale` | 377.1s | 382.4s | 44100 / 2 |
| `78805_enchanted love` | 127.6s | 141.5s | 44100 / 2 |

`OggProbe` 的三段证据（以 `Sphalerite` 为例）：

```
① 裸 OGG → AudioSystem: UnsupportedAudioFileException: Stream of unsupported format  ← 修前就是这么挂的
② stb_vorbis: 2 声道 / 48000 Hz / stream_length=7598080 帧 / 实得 7598080 帧 → 158.293s
③ 包成 WAV: 30392364 字节 → AudioSystem 成功 PCM_SIGNED 48000.0 Hz, 16 bit, stereo, 4 bytes/frame
```

---

## 3. 根因 B：两个包整个读不出来（ZIP 条目名不是 UTF-8）

### 3.1 现象与诊断

日志里（每次进选曲界面都刷一遍）：

```
[PhiMC] 谱面包读取失败: …\songs\iLArtifact_IN.zip
java.util.zip.ZipException: invalid CEN header (bad entry name or comment)
    at java.base/java.util.zip.ZipFile$Source.zerror(ZipFile.java:1781)
    at dev.dsh.phimc.client.SongEntry.fromZip(SongEntry.java:247)
```

`SongEntry.scan` 对每个包 `try { fromZip } catch { warn }` —— 整个包被<b>跳过</b>，
所以这两首在选曲界面里<b>根本看不到</b>。`ZipProbe` 诊断：

```
== iLArtifact_IN.zip ==
   ① ZipFile(File): ZipException: invalid CEN header (bad entry name or comment)
   ② ZipFile(UTF-8): 同左
   ② ZipFile(GBK): OK, 8 条目
        defaultImage.png / Chart_IN.json / music.wav / music_#0.wav / info.txt
        Cyberneko Pulse (2).pez / Hardsync.pez / 推活天使ビビアンちゃん .pez   ← 这一条的名字是 GBK/Shift-JIS
   ② ZipFile(ISO-8859-1): OK（名字显示成乱码，但条目齐全）
   ③ ZipInputStream: ZipException: invalid LOC header (bad entry name)     ← 更宽松的读法也救不了
```

结论：包的 CEN 里有一个**日文条目名**（`推活天使ビビアンちゃん .pez`，这是个「谱面包合集」式压缩包），
作者没设 ZIP 的 UTF-8 标志位（general purpose flag bit 11），JDK 的 `ZipFile` 在**构造阶段**就严格校验
并整包拒绝。

### 3.2 修法

`SongEntry.openZip(Path)`：默认 UTF-8，失败后依次回退 **GBK → Shift_JIS → ISO-8859-1**
（最后一个是字节 1:1 映射，永不失败）。四处 `new ZipFile(...)` 全部改走它。
**只有默认路径抛异常时才会走回退**，正常包的行为一个字节都没变；回退成功会打一行 INFO
（`谱面包条目名不是合法 UTF-8，已用 GBK 打开: xxx`）便于以后排查。

> 这两个包的谱面/音频/曲绘条目名都是纯 ASCII，所以只有那一个多余的内嵌 `.pez` 条目的名字会乱，
> 不影响读取。修后 `iLArtifact_IN`（pgr 1279 音符 / 24 线）、`volcanic_IN`（pgr 1178 / 15）都能正常进谱。

---

## 4. 根因 C：贴图判定线一条都没画（这两首是「特效谱」）

音频修好之后还有一个问题：这两首的画面基本是**空的**。

| 谱面 | 判定线 | 其中贴图线（`Texture != line.png`） | 以前画出来的 |
|---|---|---|---|
| `78805_enchanted love` | 287 | **250**（heart / Circle / 字母 / arc / stripe …，共 100 个不同图名） | 只有 30 条 `line.png` 横条 |
| `48273_Undertale` | 177 | **129**（spear / bone / flowey / sans / story …，57 个图名） | 只有 28 条横条 |

`prpr` 里这些线画的是**那张图本身**：

```rust
// core/line.rs:254-272
JudgeLineKind::Texture(texture, _) => {
    let mut color = color.unwrap_or(WHITE);
    color.a = alpha.max(0.0);
    if color.a == 0.0 { return; }
    let hf = vec2(texture.width(), texture.height());   // 像素尺寸
    draw_texture_ex(**texture, -hf.x / 2., -hf.y / 2., color,
        DrawTextureParams { dest_size: Some(hf), flip_y: true, ..Default::default() });
}
```

以前我们为了「不出现横贯屏幕的假亮条」，把所有非 `line.png` 的线都**整条跳过**（上一轮修的），
对纯装饰线是对的，但对这两首「整首曲子都是贴图线」的特效谱就等于把画面删了。

### 4.1 修法

| 文件 | 改动 |
|---|---|
| `core/Chart.java` | `Line.textureName`（`Texture` 原值）；修 `scaleXAt/scaleYAt` 的**默认值**（见 4.2） |
| `core/RpeParser.java` | 把 `Texture` 名字传进 `Chart.Line` |
| `client/SongEntry.java` | 新增 `asset(String name)`：按文件名在包里找任意资源（先精确匹配 basename，再退化成「去后缀同名」），目录形态同样支持；找不到返回 `null` |
| `client/LineTextures.java`（新） | 贴图 → 动态贴图的缓存：`CoverCodec` 解格式（只看魔数）→ `NativeImage` → `NativeImageBackedTexture` 注册（和 `CoverTextures` 同一套）；换曲目整批销毁（`beginFor`），失败也缓存 |
| `client/PlayScreen.java` | 新增 `drawTextureLine()`，在 `chart.drawOrder` 的那个循环里画（**和横条共用同一个 z 序**），照 `line.rs:254-290` |

尺寸换算（`px = 图片像素 × scale × 屏宽/2`）：

* `scale` 的单位见 `rpe.rs:695-696`：非 `line.png` 的 `factor = 2/RPE_WIDTH = 2/1350`，即「1 像素 = 2/1350 世界单位」；
* 1 世界单位 = 半个屏宽（相机 `zoom.x = 1`，`resource.rs:507`），所以 `world × 屏宽/2 = px`；
  屏幕 y 侧同理（相机把 y 乘了 `aspect`）。

### 4.2 顺手修掉的一个必然踩到的坑：`scale` 默认值

`rpe.rs:695-696`：

```rust
let factor = if rpe.texture == "line.png" { 1. } else { 2. / RPE_WIDTH };
let default = if rpe.texture == "line.png" { 1. } else { factor };
```

也就是说**贴图线的 scale 默认值是 `2/1350`，不是 1**。我们的 `scaleXAt/scaleYAt` 一律按 `1.0` 兜底，
对贴图线偏大 **675 倍**——一旦开始画贴图线，那几条没写 `scaleXEvents` 的线会变成铺满全屏的巨图。
曲库里 `Undertale` 有 3 条这样的线，已修（`Chart.Line.scaleDefault()`）。

### 4.3 验证（headless，`RunnableTex`）

| 谱面 | 不同图名 | `SongEntry.asset()` 命中 | 带 `scaleXEvents` 的线 |
|---|---|---|---|
| `78805_enchanted love` | 100 | **100 / 100**（共 2 481 899 字节） | 250 / 250 |
| `48273_Undertale` | 57 | **57 / 57**（共 289 206 字节） | 126 / 129 |

尺寸抽查（1920×1080 屏，按 `像素 × scale × 屏宽/2` 算出来的实际画面尺寸）：

```
heart.png         → 455 × 256 px
flowey1.png       → 597 × 352 px
dialog.png        → 364 × 102 px
Crown_fill.png    → 1138 × 307 px
Crown_stroke.png  → 1180 × 331 px
默认 scale 误差（无事件的线，应为 0）：0.000e+00
```

量级检查通过：既没有 675 倍爆掉（铺满全屏），也没有缩到看不见（0×7 那种是 `line_half.png`
在 t=0 真的把 `scaleX` 动画到 0，属谱面本意）。

### 4.4 每帧新建贴图限量（平台优化，`prpr` 无此项）

`enchanted love` 有 100 个不同图名，若在进谱第一帧全解 = 一次性 100 × 几 ms ≈ 几百毫秒卡顿
（用户体感又是「一打开就卡」）。`LineTextures` 加了一个**每帧最多新建 6 张**的额度
（`NEW_PER_FRAME`），代价是头几帧少画几条装饰线（肉眼几乎不可见），好处是没有任何一帧超预算。
`prpr` 没有这个概念——它在开谱前就把图全载进显存了（`rpe.rs:780-805` 的 `line_texture_map` + `SafeTexture`）。

---

## 5. 每帧开销（确认没有引入新的卡顿）

`PerfProbe`，60fps 抽样全时间轴，`-Xmx5G`：

| 谱面 | 解析 | 冷启动 | 峰值堆 | avg 帧 | p99 | max 帧 | 最差 1s |
|---|---|---|---|---|---|---|---|
| `39417_Sphalerite` | 100ms | 127ms | 113MB | **0.062ms** | 0.24 | 0.69 | 4.1ms |
| `78805_enchanted love` | 120ms | 148ms | 112MB | **0.033ms** | 0.14 | 0.53 | 3.4ms |
| `48273_Undertale` | 131ms | 168ms | 121MB | **0.040ms** | 0.17 | 0.55 | 1.8ms |
| `14143_「僕は…」` | 67ms | 89ms | 89MB | **0.034ms** | 0.13 | 0.72 | 2.9ms |

（这里统计的是 CPU 侧求值：判定线变换 / 音符筛选 / 音符落点。贴图线的 `drawTexture` 是 GPU 提交，
每条线一次，250 条/帧和 `big song` 的 741 条横条同一量级。）

---

## 6. 编译与回归

| 项 | 结果 |
|---|---|
| `gradle :1.21.1:build`（Gradle 9.8.2 + JDK 25，`--offline`） | **BUILD SUCCESSFUL**，0 错误 |
| `StressTest`（2500 音符 / 24 线 / 141.1s） | 自动播放 **1000000 / PHI / P=2500 G=0 B=0 M=0 / 连击 2500** ✔；不操作 0 分；±60ms 抖动 395690（三条都与修前逐字一致） |
| `SpecTest`（fake / fadeout / hold / 速度积分 13 项） | 全部通过 ✔ |
| `PecCompare`（PEC 缓动回归） | 6954 全 30 线 × 1448 采样，最大偏差 **2.274e-13**，0 个超 1e-3 ✔（与上一轮一致，本次没碰 PEC） |
| 全库 46 个包 | 修后 **46/46 可玩**，音频 **46/46 可播** |
| jar | 已覆盖 `D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`（649 509 字节，sha256 `0BCBECE7…CB8C0F`，同时更新 `mod/dist`）；逐类校验过 jar 内确实含 `LineTextures` / `openZip` / `asset` / `isOgg` / `textureName` |

**判定 / 计分一行没改**：改动只在 `SongEntry`（包里读资源）、`Session`（音频装配 + 兜底）、
`WavSource`（一句更清楚的报错）、`OggToWav`（解码）、`Chart/RpeParser`（`textureName` + `scale` 默认值）、
`LineTextures`（新）、`PlayScreen`（多画一类线）。
`Engine.update()/keyboardTap()/find()/judge()`、`Judgement` 完全没动。

---

## 7. 没做与原因

| 项 | 为什么不做 |
|---|---|
| **`gifEvents`（GIF 贴图动画）** | `enchanted love` 有 1 条（`scan.line_45deg.gif`）。`CoverCodec` 只吃 PNG/JPEG/WebP，要支持得引入 GIF 帧解码 + 按 `gifEvents` 切帧。**那条线现在不画**（不是画错）。全库仅 1 条 |
| **`paintEvents`（全屏绘制）** | 要离屏 render target + 全屏 blit（`line.rs:299-356`）。全库 **0 条线** |
| **`textEvents`（文字线）** | `line.rs:291-298` 用 `ui.text` 排版，和 MC 字体度量对不上。`enchanted love` 0 条、`Undertale` 14 条、全库最多 27 条（`10176_Super Nova Project`）。这些线现在不画横条（上一轮修过），也不会画文字 |
| **`attachUI` 的 7 个 UI 元素** | `chart.rs:46-55` 把它们从判定线列表摘出来交给 UI 层；我们没有 UI 层 |
| **贴图线的**朝向**没能在真机目视校正** | 报告里的画法是从 `line.rs:254-272` 的 `flip_y: true` + 我们的坐标系（y 向下、角度已取反，和音符同一套）推导出来的，headless 无法验证像素。若真机上发现某几张图上下颠倒，只需要在 `drawTextureLine` 里翻转一次 v（一处改动） |
| **贴图线与音符的层叠顺序** | `prpr` 是「一条线画完自己的图 + 自己的音符，再下一条线」（`chart.order` 里 `JudgeLine::render` 一趟做完，`line.rs:396-429`）。我们是「先画所有线、再画所有音符」——**音符永远在最上层**。这是本实现早就有结构（两趟循环），改成按线交错要把音符按线分桶重排。刻意保持：音符在贴图之上对可玩性更好（背景图不会挡住音符），偏差已记录 |
| **PEC 负 alpha 的 `2` / `100..1000` 编码** | 上一轮遗留，本次未涉及（本库 0 次出现） |
