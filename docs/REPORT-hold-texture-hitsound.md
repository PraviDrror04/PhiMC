# hold 贴图渲染 + 打击音截断：改动与验收

对象：`phimc/`（Fabric 1.21.1 mod），参考源码 `C:\dsh-work\phira-src\prpr`（Phira v0.8.2）。

改动只碰「渲染」和「音频」；`Engine` / `Judgement` / `Chart` / `Session` 的判定与计分逻辑一行未动。

---

## 1. hold 贴图：Phira vs 我们

### 1.1 差异表（改前 → 改后）

| 项目 | Phira（prpr） | 我们（改前） | 我们（改后） |
|---|---|---|---|
| `holdAtlas` 归属 | `atlas[0]` = 尾（贴图**顶部**段），`atlas[1]` = 头（贴图**底部**段）（`core/resource.rs:168-182`：`hold_tail_rect` 用 0，`hold_head_rect` 用 1；`note.rs:79` 的 `flip_y` 把 v=0 映射到屏幕上方） | 相同（`ResPack` 里 `holdTailPx=atlas[0]`、`holdHeadPx=atlas[1]`，`drawHoldTexture` 里 tail `v=0`、head `v=texH-headPx`） | 相同，逻辑收进 `core/HoldLayout`，渲染只读它 |
| 头/尾高度 | `hf.y = (capPx/texH) * scale * hold_ratio`，`scale = noteW/2`、`hold_ratio = texH/texW`；`dest_size.y = hf.y*2 = capPx*noteW/texW`（`note.rs:291, 324, 342`） | `headH = round(noteW * capPx / texW)` —— 代数上**就是** `2*hf.y`（这一点改前就是对的） | `HoldLayout.capHalfHeight` 保留 Phira 原式，`capDestHeight = round(2*hf.y)`；整数取整 ≤0.5px |
| 身体 | 取中间段，`dest_size.y = top - bottom`，**竖向拉伸**（`note.rs:294-320`） | 相同（中间段拉伸） | 相同 |
| 身体平铺 | 仅 `holdRepeat: true`：把身体裁出来设 `TextureWrap::Repeat`，按自然比例平铺（`resource.rs:237-254`、`note.rs:307-315`） | **不支持**，永远拉伸 | 支持：每圈高 `noteW*bodyPx/texW`，循环画（最后一圈裁源高），等价于 Repeat |
| 头的位置 | 整块在头端点**外侧**（朝判定线）：world `[bottom-2hf, bottom]`（`note.rs:330`） | **画在身体内部** `[-headH, 0]`，盖住身体末端 | 外侧 `[0, +headH]`（朝判定线）；`holdCompact` 时 `[-h/2, +h/2]` |
| 尾的位置 | 整块在尾端点**外侧**：world `[top, top+2hf]`（`note.rs:348`） | **画在身体内部** `[bodyTop, bodyTop+tailH]` | 外侧 `[-L-tailH, -L]`；`holdCompact` 时 `[-L-h/2, -L+h/2]` |
| 可见长度 | `L + headH + tailH` | `L`（头尾压在身体里，短 hold 会被啃成一块） | `L + headH + tailH` |
| 头何时画 | `res.time < self.time || holdKeepHead`（`note.rs:322`） | **一直画**（命中后头永远挂在判定线上） | `t < n.time || holdKeepHead` |
| `above=false` | 整个模型再转 180°（`note.rs:132-134`） | 只按 `dir` 换身体方向，贴图**没镜像** | `dir>0` 时 `rotateZ(π)`，身体/头/尾/贴图一起镜像 |
| `holdCompact` | 支持（`note.rs:330/348` 的 `- hf.y`） | 不支持 | 支持 |
| `hold_ratio` | `texH/texW` | 隐含在 `noteW*capPx/texW` 里，没有显式量 | `HoldLayout.holdRatio()` + `capHalfHeight()` 显式保留 Phira 公式，可逐项对比 |
| `NOTE_THICK_FRAC`（厚度统一） | 无此概念，全看贴图比例 | hold 贴图路径其实**没用到**它（用的是贴图比例），纯色兜底才用 | 明确只用于**没有贴图**的纯色兜底；有贴图时头/尾高度 = 贴图比例 |

**结论**：`atlas` 切口和 `hf.y` 公式改前就是对的；真正坏的是
①头/尾画在身体里面（短 hold 长度少 `headH+tailH`，头尾互相盖）
②头命中后不消失（一直挂在判定线上）
③`above=false` 没镜像
④`holdRepeat/holdKeepHead/holdCompact` 没实现。

### 1.2 改动文件

- 新增 `phimc/core/src/main/java/dev/dsh/phimc/core/HoldLayout.java`（1-106）
  —— atlas 三段换算的唯一实现：`of`(36) / `bodyPx`(45) / `holdRatio`(65) / `capHalfHeight`(72) /
  `capDestHeight`(82) / `bodyTileHeight`(93)。纯 Java，headless 验收和渲染共用。
- `phimc/mod/src/main/java/dev/dsh/phimc/client/respack/ResPack.java`
  - 79-88：新增 `holdRepeat / holdKeepHead / holdCompact` 字段（注释写明 atlas 归属）
  - 177-192：三个 getter
  - 226-230：从 `info.yml` 解析三个开关；251 日志带上
  - 363-366：`bool()` 解析
- `phimc/mod/src/main/java/dev/dsh/phimc/client/PlayScreen.java`
  - 39-57：`NOTE_THICK_FRAC` 的注释改成「只用于纯色兜底」，并说明与贴图比例的取舍
  - 270-312：hold 分支 —— 身体长度、`dir`、`above=false` 的 180°、头的显示时机
  - 490-563：`drawHoldTexture` 重写 —— 三段切口、身体拉伸/平铺、头尾放身体外侧、
    `holdCompact` 半叠、`holdKeepHead`
- `phimc/README.md`：第 10 条改成「有贴图 / 纯色兜底」两套说明（避免文档和实现打架）

### 1.3 贴图像素区域对比（真实资源包，headless）

资源包：`C:\Users\Administrator\.dsh\attachments\v1\files\42\42b8...\Phigros官方资源包.zip`（只读，不进仓库）
`hold.png` = **989 × 2000 px，161946 B（158 KB）**；`info.yml` = `holdAtlas: [50, 50]`，
`holdRepeat/holdKeepHead/holdCompact` 全缺省 false。

| 段 | Phira 公式（`resource.rs:168-182`） | 我们的 `HoldLayout`（渲染实际调用） | Δ |
|---|---|---|---|
| tail | `x=0 y=0 w=989 h=50`，贴图行 `[0..50)` | `x=0 y=0 w=989 h=50` | 0 / 0 / 0 / 0 |
| body | `x=0 y=50 w=989 h=1900`，行 `[50..1950)` | `x=0 y=50 w=989 h=1900` | 0 |
| head | `x=0 y=1950 w=989 h=50`，行 `[1950..2000)` | `x=0 y=1950 w=989 h=50` | 0 |

覆盖检查：`[0..50) + [50..1950) + [1950..2000) = [0..2000)`，无重叠、无空洞。
`hold_ratio = 2000/989 = 2.022244692`。

头/尾 dest 高度（`2*hf.y`）：

| 屏幕宽 | noteW | Phira（float） | 我们（int） | Δ |
|---|---|---|---|---|
| 854 | 113 | 5.712841 | 6 | +0.287159 |
| 1280 | 169 | 8.543984 | 9 | +0.456016 |
| 1920 | 253 | 12.790698 | 13 | +0.209302 |

`holdRepeat` 身体一圈高（1280 宽）= `169*1900/989 = 325 px`。

改前/改后几何（L=100px、1280 宽、headH=tailH=9px）：

```
改前: 身体 y[-100..0]，头 y[-9..0]（压在身体里），尾 y[-100..-91]（压在身体里）
      → 可见总长 = 100px
改后: 身体 y[-100..0]，头 y[0..9]（外侧），尾 y[-109..-100]（外侧）
      → 可见总长 = 118px
```

命令（核心纯 Java，无需 Minecraft）：

```
javac -encoding UTF-8 --release 21 -cp "lib\gson-2.11.0.jar;lib\jlayer-1.0.1.4.jar" -d out-holdaudio @(core main+test 全部 .java)
java -cp "out-holdaudio;lib\gson-2.11.0.jar;lib\jlayer-1.0.1.4.jar" dev.dsh.phimc.core.HoldAtlasCheck <资源包.zip>
```

输出存档：`C:\dsh-work\evidence\holdaudio_holdatlas.txt`。

---

## 2. 打击音：哪条通路截断、怎么改

### 2.1 三条通路的结论

| 通路 | 会不会截断 | 依据 |
|---|---|---|
| ① 资源包 `click/drag/flick.ogg`（JavaSound） | **会** —— 旧实现每个 `(包, 槽位)` 只解码一个 `Clip`，每次命中 `setFramePosition(0) + start()`，正在响的那一遍被从头重启，前一声剩下的部分直接丢掉 | `HitSoundPlayer` 旧 96-122 行；对照实验见 2.3 |
| ② mod 内置 ogg（`SoundManager` + `PositionedSoundInstance.master`） | **不会** —— 每次 `master()` 都 `new PositionedSoundInstance(..., SoundInstance.createRandom(), ...)`；`SoundSystem.play` 每次 `Channel.createSource()` 新开 source 再 `sources.put(实例, ...)`，全程没有按 sound id / pitch 查旧实例并 stop | 1.21.1 yarn `javap -c`：`SoundSystem.play` 偏移 371-478（`Channel.createSource` → `sources.put`）；`PositionedSoundInstance.master(SoundEvent,float,float)` 偏移 0-25（`new` + `SoundInstance.createRandom`）。存档 `C:\dsh-work\evidence\holdaudio_mc_sound.txt` |
| ③ 原版兜底（音符盒） | **不会** —— 和 ② 同一条 MC 路径 | 同上 |

MC 静态音源池：`SoundEngine.init` 里
`streaming = clamp(sqrt(monoSourceCount), 2, 8)`、`static = clamp(monoSourceCount - streaming, 8, 255)`；
非流式打击音走 **大的那组**（典型设备 247 个），所以密集段能同时响很多个。
唯一失败模式是**新**音符拿不到 source（`createSource` 返回 null → 丢弃它），**不会**去 stop 旧音符。

`stop()` 审计：打击音路径里没有 `clip.stop()`；唯一会停的是切包/关包时的
`HitSoundPlayer.clear()`（只在界面初始化/设置页切包调用，`applyResPack` 幂等，不会在曲中触发）。
`Session.finish()` 里的 `audio.stop()` 停的是歌曲音频，不是打击音。

### 2.2 改法

- 新增 `phimc/core/src/main/java/dev/dsh/phimc/core/audio/HitSoundClips.java`（纯 Java，零 MC 依赖）
  - `put`(57) 缓存解码后的 PCM（不再缓存可复用的 Clip）
  - `play`(89/119)：**每次新建 `Clip`** → `open(PCM)` → 设增益 → 注册 listener →
    `STOP`/`CLOSE` 时 `close()` 并从 live 集合移除（`LIVE.remove` 保证只 close 一次）
  - `warmup`(127)：第一次 `getClip()` 实测 ~50ms，提前初始化 mixer
  - `clear`(139)：切包时清 PCM + 关掉还在响的
- `phimc/mod/src/main/java/dev/dsh/phimc/client/HitSoundPlayer.java`
  - 100-125 `playFromPack`：PCM 缓存 + `HitSoundClips.play(key, volume)`；播放失败不标记 BROKEN（下次还能试），交给下一级
  - 128-131 `clear`：`HitSoundClips.clear()` + 清 BROKEN
  - 134-155 `prepare`：切包后预解码 3 个音 + 预热 mixer（不让第一个音符付 ~50ms 初始化）
  - 157-203 `decode`/`decodeOgg`：OGG 仍用 MC 的 `OggAudioStream`，但只解一次，产出 16-bit PCM（按声道整帧对齐）
- `phimc/mod/src/main/java/dev/dsh/phimc/client/PhiMCClient.java` 151-175：
  `ResPack.select()` 之后调 `HitSoundPlayer.prepare()`
- ②③ 两条 MC 通路**不需要改**（本来就每实例独立、无 stop）；只补了注释与证据。

### 2.3 5 连击实测时间线

真实音源：资源包 `click.ogg`，用 mod 同款 `OggAudioStream` 解出
**44100 Hz / 2ch / 16bit，4452 帧 = 100.952 ms**（ffprobe 的 ogg 时长 0.100952s 与之一致；
ffmpeg 直接解会多出最后一个 Vorbis block 的 padding，所以验收用 MC 解码的 PCM）。
5 次触发、间隔 50ms（`Thread.onSpinWait` 精确定时，避免 Windows `sleep` 的 15.6ms 取整）：

```
序号  请求(ms)  开始(ms)  结束(ms)  实测时长(ms)  clip 长度(ms)  STOP 时播放头(ms)  完整?
  0       11.2      64.5     220.7        156.2          101.0           101.0  是  (与下一次重叠)
  1       64.0      80.6     220.5        139.8          101.0           101.0  是  (与下一次重叠)
  2      100.1     101.2     251.7        150.6          101.0           101.0  是  (与下一次重叠)
  3      150.0     151.1     298.8        147.7          101.0           101.0  是  (与下一次重叠)
  4      200.0     201.0     346.3        145.3          101.0           101.0  是
峰值同时 5 个 Clip；最后 0 个残留
总时间轴：第一声开始 64.5ms → 最后一声结束 346.3ms，跨度 281.8ms
对比：5 段首尾相接 = 5×101.0 = 504.8ms；本实现 281.8ms（≈ 4×50 + 101.0 = 301.0）
```

**关键证据**：每一行「STOP 时播放头 = 101.0ms」= clip 全长（`getMicrosecondPosition()` 在 STOP 事件里读，
此时还没 close）—— 说明每一遍都播到了结尾，不是被截在 50ms。实测墙钟时长 140~156ms
比 101ms 长，是声卡/设备输出缓冲的 drain 延迟（本机设备固定 +40ms 左右），不影响「完整播完」的结论。
第 0 声的 START 晚约 55ms 是 JVM 内第一次 `AudioSystem.getClip()` 的 mixer 初始化；
游戏里 `applyResPack` 已调 `HitSoundPlayer.prepare()` 预热，这一下不会落在第一个音符上。

对照（旧写法，同一个 Clip 播到一半再 `setFramePosition(0)+start()`）：

```
第一声在 30.7ms / 101.0ms（只播了 30.5%）时被第二次命中重启 → 播放头回到 0 → 剩下的 70.2ms 被丢掉
```

即：旧实现确实「后一声开始 = 前一声被打断」；新实现里后一声开始时前一声仍在响，两者都完整。

命令与存档：

```
java -cp "out-holdaudio;lib\gson-2.11.0.jar;lib\jlayer-1.0.1.4.jar" \
  dev.dsh.phimc.core.HitSoundOverlapTest C:\dsh-work\respack_probe\click_mc.wav 5 50
→ C:\dsh-work\evidence\holdaudio_overlap.txt
```

（`click_mc.wav` 是用 MC `OggAudioStream` 从真实包 `click.ogg` 解出来的 PCM 容器，运行时生成，不在仓库里。）

---

## 3. 编译 + StressTest

编译（按委托给的命令，robocopy 到 `C:\dsh-work\phimc` 后）：

```
gradle -p C:\dsh-work\phimc\mod clean build --no-daemon --console=plain --offline
…
> Task :compileJava
> Task :jar
> Task :remapJar
> Task :remapSourcesJar
> Task :build
BUILD SUCCESSFUL in 24s
8 actionable tasks: 8 executed
```

产物：`C:\dsh-work\phimc\mod\build\libs\phimc-0.1.0.jar`（431902 B），
jar 内已含 `dev/dsh/phimc/core/HoldLayout.class`、`core/audio/HitSoundClips.class`、
新的 `PlayScreen` / `ResPack` / `HitSoundPlayer` / `PhiMCClient`。

两条 StressTest（`C:\dsh-work\pkg1\Chart_IN.json`、`C:\dsh-work\pkg2\Chart.json`）：

```
谱面：1285 音符 / 24 判定线 / 117.7s
[自动播放] 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 最大连击=1285/1285
[不操作]   分数=0 P=0 M=1285/1285
[±60ms]    分数=509829 评级=F P=484 G=358 B=45 M=398

谱面：1418 音符 / 24 判定线 / 150.9s
[自动播放] 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 最大连击=1418/1418
[不操作]   分数=0 P=0 M=1418/1418
[±60ms]    分数=341844 评级=F P=451 G=131 B=21 M=815
```

两条都是 **1000000 / 0 Miss**。（「不操作」/「±60ms」两行和旧存档里的数字不同，是因为
当前 `StressTest` 的契约已经是「Drag 也必须有输入、不操作全 Miss」，与本次改动无关；
本次只碰绘制/音频。）

存档：`C:\dsh-work\evidence\holdaudio_stress_pkg1.txt` / `holdaudio_stress_pkg2.txt`、
`holdaudio_build.txt`。

---

## 4. 没做的、以及为什么

1. **没有上机截图 / 实机渲染**：环境里没有可自动化的 MC 客户端。hold 的验收用「三段源区域逐项对拍 +
   改前/改后几何」代替；真机上唯一没法自动验证的是观感（头/尾的半像素取整）。
2. **`holdAtlasMH` / `_mh` 贴图**：本 mod 没有 RPE 的 `double_hint` 双押提示，用不到 MH 素材。
3. **RPE `note.size` 对 hold 头尾的缩放**：Phira 的 `hf.y` 只用 `res.note_width`（符号宽），
   `note.size` 走模型矩阵的 `scale.x`，所以头尾在 Phira 里会随 size 轻微变形；我们统一按
   `noteW`（含 `v.scale`）算。PGR 谱 `size` 恒 1，不受影响；RPE 谱会有亚像素差异。
4. **RPE `yControl` × hold 长度**：尾巴长度仍用 `floorPositionAt(end)`（PGR 语义），
   没有把 `ctrlY` 乘进尾巴；PGR 的 `ctrlY` 恒 1，不受影响。这是既有实现的边界，不在本次问题范围内。
5. **`holdRepeat` 已实现但官方包不触发**（`info.yml` 没有该键，默认 false），只做了公式与自测，
   没在实机看平铺效果。
6. **MC 两条通路没有 headless 运行**：`SoundManager` 需要客户端/OpenAL；结论用 1.21.1 yarn 字节码 +
   设计（每实例独立 source、无 stop 查询）说明。真正被修的是 JavaSound 资源包通路。
7. **JavaSound line 耗尽时没有软件混音兜底**：极端情况下 `getClip()` 失败，代码会退到内置打击音
   （能听到、不截断，但音色不同），并打 warn。没有实现 `SourceDataLine` 自混音。
8. **判定/计分/判定逻辑、谱面解析、UI**：一行没改（硬约束）。
