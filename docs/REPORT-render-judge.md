# 渲染与判定对齐 prpr（Phira）源码的修复报告

唯一依据：`C:\dsh-work\phira-src`（Phira / prpr，GPL-3.0，只读）。
本报告里每条结论都带 `文件:行号`；凡是源码里没有的东西，明确写「源码无此项」。

---

## 0. 验收工具（都可重跑）

| 工具 | 作用 | 命令 |
|---|---|---|
| `Coverage` | 整首曲子 60fps 步进，统计每个音符是否被 `visible()` 返回过，并归类「从未出现」 | `java -cp "out;lib/gson-2.11.0.jar" dev.dsh.phimc.core.Coverage <chart> [fps] [aspect]` |
| `RotTest` | 旋转判定线（θ=0/45/90）命中用例表，含「修前 vs 现在」两列 | `... dev.dsh.phimc.core.RotTest` |
| `SpecTest` | 源码规格自检（fake、淡出、hold、高度不变量、速度值直通） | `... dev.dsh.phimc.core.SpecTest` |
| `StressTest` | 既有回归（自动播放满分 / 不操作全 Miss） | `... dev.dsh.phimc.core.StressTest <chart>` |

编译（core 纯 Java，不依赖 MC）：

```powershell
$env:JAVA_HOME="C:\dsh-tools\jdk21"
$javac = "C:\dsh-tools\jdk21\bin\javac.exe"
$src = Get-ChildItem -Recurse -File -Filter *.java core\src\main\java, core\src\test\java | % FullName
& $javac -encoding UTF-8 --release 21 -cp "lib\gson-2.11.0.jar" -d out @src
```

证据原文都在 `C:\dsh-work\evidence\*.txt`。

---

## 1. Bug A：谱面中段的 note 消失（核心）

### 现象
不是全部不显示，而是某些区段整段不出现。用 60fps 无头步进量化后：pkg1（1285 音符）有 **384 个音符整首曲子从未被 `visible()` 返回过一次**；pkg2（1418 音符）有 **675 个**。

### 根因（两套高度口径混用）
| 位置 | 旧代码做了什么 | 源码怎么做的 |
|---|---|---|
| `core/.../ChartParser.java:100`（旧） | 音符高度直接读谱面 JSON 的 `floorPosition` 字段 | `pgr.rs:213-217`：**不读该字段**，把 `height` 动画在 `note.time` 处的值当作音符高度 |
| `core/.../Chart.java:94-110`（旧） | 判定线高度 = 自己积分速度事件，且 `saneSpeed()` 把 `0 / 负数 / ≥100` 一律换成 `1.0`（旧 `Chart.java:168-171`） | `pgr.rs:96-100`：`pos += (end_time - start_time) * r * it.value`，**value 原样使用**（负速度=反向滚动、999=导出工具的瞬移哨兵，都是谱面语义） |
| `core/.../Engine.java:271 / 454`（旧） | `n = (note.floorPos - floorNow()) * 0.6 * speed`，然后 `Engine.java:457` 用 `|n| > 4.0` 把音符剔掉 | `note.rs:220-223`：`base = (note.height - line.height) / aspect * speed`，两者同源 ⇒ Δ 在 `note.time` 处必然为 0 |

真实谱面里这两个数就是不一样的：pkg1 的速度事件里有 26 处 `999`、pkg2 有 81 处 `999` 和 `-1.56 / -2.2` 这种负速度。JSON 的 `floorPosition` 带着 999 造成的真实跳变，而旧积分把 999 当 1.0、把负速度当 +1.0，于是两条曲线越跑越偏：pkg1 `|floorPosition - 积分|` 最大 **1761**、pkg2 最大 **21799**。音符在判定时刻的 `|n|` 因此是几十到上万屏高，被 `|n| > 4` 永久剔除。

**一句话：音符高度与判定线高度不是同一条曲线，谱面里一有 999 / 负速度，音符就被甩出屏幕。**

### 修法（文件:行 + 源码依据）
| 文件:行 | 改动 | 依据 |
|---|---|---|
| `Chart.java:73-99` | 新增 `Chart.heightAt(speed, sec)`：按 keyframe 语义求高度曲线，段内线性插值、最后一段线性外推，结果 `/ HEIGHT_RATIO` | `pgr.rs:96-107`（尤其 `pgr.rs:105` 的 `kf.value /= HEIGHT_RATIO`） |
| `Chart.java:154-157` | `Line.floorPositionAt()` 直接调 `heightAt` | `pgr.rs:233-235`（先算 speed，再算 notes） |
| `ChartParser.java:99-122` | `readNotes(...)` 先拿速度事件，音符高度 = `Chart.heightAt(speed, note.time)`；hold 的 speed 恒为 1 | `pgr.rs:213-217`、`pgr.rs:213`（`if pgr.kind == 3 { 1. }`） |
| `ChartParser.java:126-142` | 删掉 `saneSpeed`：`value` 原样进事件（只兜底非有限数） | `pgr.rs:96-100` |
| `Chart.java:176-193` | `Note.floorPos` 更名为 `Note.height`，语义改为「高度曲线的取值」 | `note.rs:36`（`pub height: f64`） |
| `Engine.java:347-360` | `noteLocal()`：`dh = note.height - line.floorPositionAt(sec)`，`y = dh*spd/aspect`，below 取反 | `note.rs:218-223`、`chart.rs:159`（对 notesBelow 做 y 翻转） |
| `Engine.java:37` | `HEIGHT_TO_SCREEN = 0.5`：`n = Δheight * speed / 2` | `note.rs:220-223` + `resource.rs:507`（`camera.zoom.y = -aspect`）换算 |

### 验证数据
**修前 vs 修后（同一份 Coverage 工具、60fps、aspect=16:9）**

| 谱面 | 修前 seen / never-seen | 修后 seen / never-seen |
|---|---|---|
| pkg1 `Chart_IN.json`（1285） | **901 / 384** | **1285 / 0** |
| pkg2 `Chart.json`（1418） | **743 / 675** | **1337 / 81** |

修前「从未出现」的原因归类（旧代码的两个剔除条件：`Engine.java:438-439` `dt > lead`、`Engine.java:457` `|n| > 4`）：

| 谱面 | 窗口剔除（dt>lead） | 距离剔除（\|n\|>4） | 合计 |
|---|---|---|---|
| pkg1 | 1\* | 383 | 384 |
| pkg2 | 126 | 549 | 675 |

\* 这 1 条是分类器把 hold 的特例算歪了；独立复算（`diag2`）给出 pkg1 恰好 **384 个音符在自身判定时刻 `|n| > 4`**，与 384 一一对应。

**关键不变量：音符在它自己的判定时刻必须落在判定线上**

| 谱面 | 修前 |n|>0.5 屏高的音符 / 最大 |n| | 修后 |
|---|---|---|
| pkg1 | 383 / **1188.794** | **0 / 0.000** |
| pkg2 | 597 / **14714.541** | **0 / 0.000** |

**修后 pkg2 剩下的 81 个为什么不是 bug**（逐条查过，两类，都不是渲染缺陷）：

| 类别 | 数量 | 证据 |
|---|---|---|
| 帧间掠过 | 50（全在 L9） | 这些音符穿过屏幕的停留时长（dwell）= **0.0017s ≈ 1.7ms**，小于 60fps 的 16.7ms 一帧；所在区段谱面速度事件是 `999`（瞬移），任何 60fps 渲染器（包括 Phira 自己）都采不到 |
| 线在屏幕外 | 31（全在 L8） | 该判定线在此区段 `moveY` 值到 **2.19**（谱面 JSON 原文），即线被搬到屏幕上沿之外 1.19 屏高；`pgr.rs:141-146` 只做 `-1+2v` 映射，Phira 同样算到屏幕外、同样剔除（`line.rs:406` 的 `limit` 会变成负数） |

---

## 2. Bug B：垂直判定不像源码

### 现象
点在音符上方的同一水平位置却打不中，尤其判定线旋转后。

### 根因
旧 `Engine.hit(sec,x,y,aspect)`（旧 `Engine.java:226-246`）在 `judge.rs` 的本地 x 比较之后，**自己加了一条纵向容差** `Y_DIFF_MAX = X_DIFF_MAX * 100/998 = 0.0237` 世界单位（旧 `Engine.java:70`）——换算到 1080p 只有 ±23px。而源码**根本没有纵向判定**：

| 源码 | 内容 |
|---|---|
| `judge.rs:417` | `X_DIFF_MAX = 0.21 / (16./9.) * 2. = 0.23625` |
| `judge.rs:473-474` + `judge.rs:533` | 点击点 → 世界坐标（`x/w*2-1`、`y/h*2-1`，再取 `-y`） |
| `judge.rs:526-534` | 用**判定线变换的逆**把点击点搬进判定线本地系（只用来取本地 x） |
| `judge.rs:581-586` | `dist = |note_local_x - touch_local_x| / judge_area`，`dist > X_DIFF_MAX` 才跳过 —— 全程没有 y |
| `judge.rs:587-595` | 时间窗分种类：Click 用 `LIMIT_BAD - LIMIT_PERFECT*max(dist-0.9,0)`，其余用 `LIMIT_GOOD` |
| `judge.rs:580` | 早/晚 70ms 折回：`if dt < 0 { (dt + EARLY_OFFSET).min(0).abs() }`（注意 `dt = (note.time - t)`，所以这条**宽恕的是晚打 70ms**） |
| `judge.rs:601-606` | 候选排序 `key = dt + max(dist/NOTE_WIDTH_RATIO_BASE-1,0)*DIST_FACTOR`，取最小，不是「取最早」 |

旧实现还有两处偏离：1 维路径用固定 `0.08` 屏宽容差（旧 `Engine.java:202-203`）、候选是「最早的一个」而不是 key 最小。

### 修法
- `Engine.java:231-272` `hit(...)`：点击 → 世界坐标 → 线本地系 → 只比本地 x → 分种类时间窗 → key 最小候选；Drag/Flick 不接受点击（`judge.rs:609-612`、`judge.rs:616-618`）。
- `Engine.java:295-330` `find(...)`：完全照 `judge.rs:563-606`，含 `EARLY_OFFSET`（304 行）与排序 key。
- `Engine.java:274-293` `flick(...)`：划动判定（`judge.rs:554-558`、`judge.rs:596-600`、`judge.rs:776-793` 结算为 Perfect）。
- `Judgement.java:17-27`：窗口默认值改成源码值 `0.08 / 0.16 / 0.22`（旧的是 `0.08/0.16/0.20`）。
- 删除 `Engine.Y_DIFF_MAX`、`Engine.GRACE`、旧 2 维几何。

### 验证数据（`C:\dsh-work\evidence\rot.txt`，本地 x = +0.3 世界单位）
θ = 0°（45°/90° 三张表逐行相同，见原文）：

| 用例 | 期望 | 现在（judge.rs） | 修前（旧 Engine.hit） |
|---|---|---|---|
| 点中音符本体 | PERFECT | PERFECT | PERFECT |
| 沿线上偏 0.20 世界单位 | PERFECT | PERFECT | PERFECT |
| 沿线上偏 0.50（超 X_DIFF_MAX） | 不命中 | 未命中 | 未命中 |
| **垂直偏 0.10** | PERFECT | **PERFECT** | **未命中** |
| **垂直偏 0.50** | PERFECT | **PERFECT** | **未命中** |
| **垂直偏 2.00** | PERFECT | **PERFECT** | **未命中** |
| 晚 60ms（EARLY_OFFSET 折回） | PERFECT | PERFECT | PERFECT |
| 晚 100ms | PERFECT | PERFECT | GOOD |
| 晚 190ms | GOOD | GOOD | BAD |
| 晚 250ms | BAD | BAD | 未命中 |
| 早 100ms | GOOD | GOOD | GOOD |
| 早 190ms | BAD | BAD | BAD |
| 早 250ms | 不命中 | 未命中 | 未命中 |

13 个用例 × 3 个角度，**期望与实测 100% 一致**；旋转正确性由「垂直偏 2.00 在 θ=90° 时仍然是 PERFECT」体现——θ=90° 时屏幕水平方向才是「垂直判定线」的方向，这条用例通过说明本地系变换/取反都对。

---

## 3. Bug C：FC/AP 应该是「线」

### 根因
源码里 **FC/AP 指示器不是独立图形，也不是文字**——它就是把**判定线整条染色**：

| 源码 | 内容 |
|---|---|
| `judge.rs:20-22` 顺序 | `Judgement { Perfect=0, Good=1, Bad=2, Miss=3 }` |
| `game.rs:1029-1037` | `counts[2]+counts[3]==0 && ap_fc_indicator` 时：`counts[1]==0 → color_perfect()`，否则 `color_good()`；否则 `WHITE` |
| `game.rs:165` | 开局/重开时 `res.judge_line_color = color_perfect()` |
| `line.rs:238/249` | `let color = self.color.now_opt(); let mut color = color.unwrap_or(res.judge_line_color);` —— 没有自带颜色事件的线就用这个颜色 |
| `line.rs:250` | `color.a *= alpha.max(0.0)` |
| `resource.rs:40-47` | 默认 `color_perfect = 0xe1ffec9f`、`color_good = 0xebb4e1ff` |
| `config.rs:57 / 97` | `ap_fc_indicator` 默认 `true` |

也就是说：全 Perfect 时判定线是金色（`#FFEC9F`，α=0.882），出过 Good 变蓝（`#B4E1FF`，α=0.922），一旦有 Bad/Miss 变白。整条线（`info.rs:81` `line_length = 6`，即每侧 3 个屏宽）都是指示器。

### 修法
- 删掉 `PlayScreen.drawFcApLine`（46×3 横线）和 `HudRenderer.drawFcAp`（右上角文字）。
- `Session.java:judgeLineColor()`：`bad+miss+尾Miss > 0 → 白`，否则 `good == 0 ? 0xE1FFEC9F : 0xEBB4E1FF`。
- `PlayScreen.render`：判定线颜色 = `judgeLineColor()`，并与判定线自身 alpha、颜色自带 alpha 相乘（`line.rs:250`）。
- `HudRenderer` 同规则。

---

## 4. 顺手修的（都有源码依据）

| 项 | 修成 | 依据 |
|---|---|---|
| fake 音符是否渲染 | 渲染；到 `note.time` 即隐；不参与判定、不进 total | `note.rs:237`（`fake && res.time >= self.time` 才不画）、`judge.rs:307`（建索引时过滤 fake）、`judge.rs:319`（num_of_notes 只数非 fake）。PGR 谱面本身没有 fake（`pgr.rs:221` 恒 `fake: false`），字段来自 RPE（`rpe.rs:603`） |
| 音符贴图比例 | 按各自贴图：click 998×100、flick 998×172、drag 948×50；hold 头/尾高 = 0.05 × 音符宽 | `note.rs:114-128`（`draw_center` 用 `tex.height()/tex.width()`）、`assets/respack/*.png` 实际尺寸、`assets/respack/info.yml` `holdAtlas: [50,50]` + `note.rs:322-356` |
| 判定线长度 | 每侧 3 个屏宽（`line_length = 6`） | `info.rs:81`、`line.rs:251-252` |
| 判定线宽度 | 0.01 世界单位（= 0.005 屏宽） | `line.rs:252`（被缩放的贴图线才用 0.0076，`line.rs:239`） |
| 非 hold 音符淡出 | 过 `note.time + 0.16s` 不再画，最后 0.16s 线性淡出 | `note.rs:10` `FADEOUT_TIME`、`note.rs:236-241/258` |
| 按住的 hold | 继续画、透明度不折半；已判定（miss/松手）的 hold 身体 α×0.5 | `note.rs:195-197`、`note.rs:277-280` |
| 计分权重 | Good 0.65、Bad 0、Miss 0；全 Perfect 直接 1000000 | `judge.rs:215-217`、`judge.rs:227-235` |

---

## 5. 打击特效（追加任务）

### 源码规格（逐项）

| 项 | 源码值 | 依据 |
|---|---|---|
| 寿命 | `hitFxDuration` 默认 **0.5s** | `resource.rs:35-37`、`resource.rs:324/336` |
| 透明度曲线 | `start=白(α1) → mid=白(α0.7) → end=白(α0)`，按 `lived/lifetime` 两段线性 | `resource.rs:311-318`、`particle.rs:668-677` |
| 精灵粒子 | 每次判定 **1 颗**，静止（`initial_velocity=0`、`spread=0`），尺寸系数 `hit_fx_scale*note_scale/5` | `resource.rs:357`（`emit(pt,1)`）、`resource.rs:321-333`、`resource.rs:368-371`；`assets/respack/info.yml` `hitFxScale: 0.9` |
| 方粒子 | **4 颗**，尺寸系数 `hit_fx_scale*note_scale/44` | `resource.rs:359`（`emit(pt,4)`）、`resource.rs:370` |
| 方粒子初速 | `2.5 * note_scale`，随机衰减比例 `1/10` | `resource.rs:341-342` |
| 方向 | `initial_direction_spread = 2π`（全向） | `resource.rs:338` |
| 加速度 | `linear_accel = -6.`，且 `particle.rs:664` 是 `v += v*accel*dt` ⇒ **指数衰减** `v(t)=v₀e^{-6t}`，位移 `v₀(1-e^{-6t})/6` | `resource.rs:343`、`particle.rs:664` |
| 寿命/旋转随机 | `lifetime_randomness = 0`、`initial_rotation_randomness = 0` | `resource.rs:325-326` |
| 挂点 | 音符模型原点（`emit_at_origin` = `world_to_screen(Point::default())`） | `resource.rs:577-587`、`note.rs:262` |
| 触发等级 | **只有 Perfect / Good** 发粒子；**Bad** 只留一个 `BadNote`；**Miss 什么都不发** | `judge.rs:821-833`（P/G → `emit_at_origin`）、`judge.rs:834-855`（Bad → `bad_notes.push`）、`judge.rs:857`（其余 false） |
| BadNote | 音符本体重画，颜色 `(0.4235,0.2627,0.2627)`=`#6C4343`，寿命 0.5s，α 从 1 线性到 0 | `note.rs:369-396`（`BAD_TIME=0.5`，`note.rs:11`） |
| Hold 期间 | 每 `HOLD_PARTICLE_INTERVAL(0.15) / config.speed` 秒发一次精灵粒子，颜色 Perfect/Good 色 | `note.rs:9`、`note.rs:143-163` |
| 颜色 | Perfect `0xe1ffec9f`、Good `0xebb4e1ff`（含 α） | `resource.rs:40-47` |

### 改前 vs 改后

| 项 | 改前（自创） | 改后 |
|---|---|---|
| 形状 | **16 点扩散环** + 4 个点 | **1 颗原地精灵（方形近似，无贴图）+ 4 颗全向飞散小方块**，不再有环 |
| 运动 | `2.5t − 3t²`（把 `linear_accel` 当匀减速），0.42s 后速度反向往回飞 | `v₀e^{-6t}` 指数衰减，永不反向 |
| 尺寸 | 环半径 `0.05w√k` | 精灵半宽 `0.18` 世界单位（= 0.09 屏宽），方块半宽 `0.02045` 世界单位 |
| 颜色曲线 | 1→0.7→0（对） | 不变 |
| 颜色 | P/G 色对 | 不变，并补上资源包自带 α（0.882 / 0.922） |
| Bad | 画文字 "Bad" | 原位重画音符本体 `#6C4343` 淡出 0.5s |
| Miss | 画文字 "Miss" | 不发特效（源码如此） |
| Hold | 无 | 按住期间每 0.15s 一颗 |

**没做到的部分**：`hit_fx.png` 是 8×7 图集（`info.yml` `hitFx: [8,7]`），精灵粒子在 0.5s 内按 `lived/lifetime` 播完 56 帧（`particle.rs:691-699`）。本 mod 没有资源包/贴图，只能用**同尺寸、同寿命、同颜色曲线的方形**近似 —— 形状无源码依据，尺寸/寿命/颜色/位置都有。

---

## 6. 编译 + 回归测试原文

编译（`C:\dsh-work\evidence\gradle_build.txt`）：

```
> Task :compileJava
> Task :jar
> Task :remapJar
> Task :build
BUILD SUCCESSFUL in 10s
7 actionable tasks: 5 executed, 2 up-to-date
phimc-0.1.0.jar  110155 bytes
```

StressTest（`evidence/stress_pkg1.txt` / `stress_pkg2.txt`）：

```
谱面：1285 音符 / 24 判定线 / 117.7s
[自动播放] 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 最大连击=1285/1285
[不操作]   分数=309728 P=398 M=887/1285（其中 Drag 自动 Perfect 398 个）
[±60ms]    分数=865817 评级=B P=1096 G=155 B=34 M=0 最大连击=355/1285

谱面：1418 音符 / 24 判定线 / 150.9s
[自动播放] 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 最大连击=1418/1418
[不操作]   分数=449224 P=637 M=781/1418（其中 Drag 自动 Perfect 637 个）
[±60ms]    分数=763290 评级=C P=1185 G=17 B=10 M=206 最大连击=59/1418
```

两条既有契约都保持：自动播放 1000000 / 0 Miss；不操作时 `Missing = total - Drag`、Perfect 只有 Drag（398=1285−887、637=1418−781）。

SpecTest（`evidence/spectest.txt`）11 项全过，覆盖 fake 渲染/不判定、0.16s 淡出、按住 hold 仍绘制、含 999/负速度时的「判定时刻必在判定线上」不变量、速度值直通积分。

---

## 6.5 界面改动（第二轮，按源码布局）

| 项 | 做法 | 依据 |
|---|---|---|
| 分数位置 | **右上角**，右对齐，`margin = 0.03`、字号 0.8 | `prpr/src/scene/game.rs:423`（`margin`）、`game.rs:431-444`（`score_right = 1 - margin`、`anchor(1.,0.)`、`size(0.8)`） |
| 连击位置 | **正上方居中**，数字大、下面一行 `COMBO`，且 `combo >= 3` 才显示 | `game.rs:468`（`if self.judge.combo() >= 3`）、`game.rs:492-513`（`anchor(0.5,0.)`，数字 `size(1.0)`、`COMBO` `size(0.4)`；autoplay 时那行写 `AUTOPLAY`） |
| 判定详情文字 | **删掉**（左上角那行 `PERFECT/GOOD/…` + 误差） | 源码 HUD 只有 score / combo / 曲名 / 难度 / acc（`game.rs:428-532`），游戏内没有任何判定文字；`HudRenderer` 里那行也一并删了 |
| 打击特效「大正方形」 | 实心方块 → **空心方框**：半边长 = 屏宽 × **0.05**（沿用旧扩散环的 0.05 尺度），线宽 = 屏宽 × 0.004 | 方框本身**源码无此项**（源码是 `hit_fx.png` 图集精灵，`resource.rs:321-333`），尺寸按 0.05 是使用方指定；寿命/颜色/透明度曲线仍是源码值 |
| Esc 菜单 | Esc **不再直接退出**，改为暂停：铺 0.6 黑底 + **退出 / 重来 / 继续** 三个按钮（横排居中），再按 Esc 继续 | `game.rs:557-559`（`tm.paused()` 时 `draw_rectangle(..., Color::new(0.,0.,0.,0.6))`）、`game.rs:561-586`（三个按钮 `i ∈ {-1,0,1}` 横排居中，半间距 `s*2+w`：back=退出、retry=重来、resume=继续）、`game.rs:622-629`（三个分支的动作） |
| 结算界面 | 一局结束后显示：大号分数、段位、`ACC xx.xx%`、`MAX COMBO x / n`、P/G/B/M 数量、此前最佳，外加 **重来 / 退出** 按钮 | `ending.rs:281-300`（曲名/难度）、`ending.rs:368-374`（accuracy `{:.2}%`）、`ending.rs:406-425`（各档 `counts[id]`）、`ending.rs:437-470`（`max-combo` + `combo / num_of_notes`）、`ending.rs:520-533`（`proceed` / `retry` 两个按钮） |
| 选曲显示最佳成绩 | 每行右侧显示 `段位 7位分数 xx.xx%`（FC 用蓝色，否则灰色）；存档在 `config/phimc/records.tsv` | `phira/src/scene/song.rs:2671-2681`（`icon_index(score, full_combo)` + `format!("{score:07}")` + `format!("{:.2}%", accuracy*100.)`）；字段 = Phira 的 `SimpleRecord { score, accuracy, full_combo }`（`prpr/src/scene/game.rs:966-970`），只在分数更高时覆盖 |
| 暂停/继续的时钟 | `AudioSource` 增加 `pause()/resume()`；WavSource 用 `SourceDataLine.stop()/start()`（位置冻结），墙钟实现累计已播时长；`Session.seconds()` 在暂停时冻结 | 源码 `game.rs:1020`（暂停时不跑判定）、`game.rs:653/862`（resume）、`game.rs:655`（`pause_rewind`）——我们不做 0.2s 回退，只做冻结 |
| 重来 | 复用已解析谱面与已解码音频负载，`Engine.reset()` + 重新开音频，不重新读盘/解码 | `game.rs:628-629`（`reset!` 宏：清 bad_notes、judge.reset()、chart.reset()、seek 0、状态回 Starting） |

自检：`Records` 的读写/覆盖逻辑用临时程序跑过（只在更高分时覆盖、重新加载后 `rank/score/acc/fc` 正确），
见下方输出；其余为编译期检查（本机无 MC 客户端可交互验证）。

```
初始 best = null
submit a(950k)=true a(900k)=false b(1M)=true
a.zip -> S 0950000 97.12% fc=false combo=900/1000
b.zip -> PHI 1000000 100.00% fc=true combo=1200/1200
```

---

## 7. 没做的、以及为什么

1. **Drag 的「手指在场」判定**（`judge.rs:742-757` + `judge.rs:776-793`）没做。源码里 Drag 需要触摸落点在自己的 x 范围内才判 Perfect，我们保留了「到点自动 Perfect」——因为这是委托方明确要求保持的回归契约（StressTest 的「不操作时 Drag 全 Perfect」）。偏差已知。
2. **多点触摸**没做。源码 `judge.rs:524-545` 是 `pos[line][touch]` 的多指模型，MC 鼠标只有单指。Flick 用 `Screen.mouseDragged` 的速度判（阈值照 `judge.rs:19/99-103/122`），但一次拖动只对应一个触点。
3. **Hold 的完整状态机**没做。源码 `JudgeStatus::Hold(perfect, at, diff, pre_judge, up_time)` + `UP_TOLERANCE` 的逐帧追踪（`judge.rs:699-760`）与 pre-judge 结算（`judge.rs:762-796`）只用简化模型替代（头判定 + 到点收尾 + 提前松手断连）。行为面：`releaseHolds` 用了源码的 `UP_TOLERANCE = 0.05`。
4. **键盘判定**（`judge.rs:649-698`）没做。
5. **RPE 谱面整条链路**没做：`rpe.rs` 的 speed 缓动（`speed_segment_tween` / `SpeedIntegralTween`、`rpe.rs:212-292`）、`judgeArea`（`rpe.rs:599`）、`fake`、`visible_time`、`tint`、`colorEvents`、`incline`、`ctrl` 事件、`parent`/`rot_with_parent`、`attachUI`。本 mod 只解析 PGR 风格 JSON；`rpe.rs` 只用于交叉验证（例如 fake 字段的来源）。
6. **判定文字**：源码游戏内 HUD 只有 score / combo(≥3) / 曲名 / 难度 / acc（`game.rs:428-532`），没有任何判定文字。我把打击特效里的 Perfect/Good/Bad/Miss 文字删了，但保留了 HUD 右上/左上那一行「最近判定」文本（`lastGrade`），属于本实现追加，不属于特效系统。
7. **音符/判定线贴图**：没有资源包，全部用纯色矩形；颜色值不是源码的（源码用 `note_style` 贴图）。
8. **`hit_fx` 精灵的图集动画**：见第 5 节的说明，用方形近似。
9. **`info.txt` 的 `line_length`**：谱面包的 info.txt 没有这个字段，所以用 `info.rs:81` 的默认 6.0，没有做 per-chart 覆盖。
10. **`Judgement.Config.wide`（宽判）**是源码没有的开关，保留、默认关闭。
