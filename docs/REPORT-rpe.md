# RPE（.pez）支持 —— 实现报告（v3：缓动表对齐 + 逐项对照 prpr）

> 参考实现只有一份：`C:\dsh-work\phira-src\prpr\src\parse\rpe.rs`（Rust，GPL，只读参考）。
> 下面所有 `rpe.rs:NN` / `core.rs:NN` / `line.rs:NN` / `note.rs:NN` / `object.rs:NN` /
> `anim.rs:NN` / `tween.rs:NN` / `parse.rs:NN` 都是 `prpr/src/` 下的行号。
> 代码是按语义在 Java 里重写的，没有拷贝。
>
> **源码新鲜度核对（2026-10-19）**：本地 `C:\dsh-work\phira-src` 是 Phira **v0.8.2**
> （`Cargo.toml` 的 `[workspace.package] version = "0.8.2"`），而 GitHub 上最新 release 就是
> **v0.8.2**（2026-07-30）。把 `prpr/src/parse.rs` 和 `prpr/src/core/tween.rs` 与
> `raw.githubusercontent.com/TeamFlos/phira/main/...` 逐行比对过：**完全一致**，不存在「源码是旧的」。
> 出问题的是我这边把 `RPE_TWEEN_MAP` 抄错了一位（见 §0.1），不是源码不新。

## 0. 一页结论

| 项 | prpr 依据 | 修前 | 修后 |
|---|---|---|---|
| **缓动表（`easingType → TweenId`）** | `parse.rs:75-94` 的 `RPE_TWEEN_MAP` | **整张表错位一位**：把 `easingType=2` 当线性（实际是 SineOut），≥2 的全部用了「下一条曲线」→ 与 prpr 最大差 **0.583 / 0.464 / 0.343 屏高**，200 抽样里 84 / 92 / 67 个超 1e-3 | 逐位对齐（`EasingCheck` 对着从源码文本展开的表断言）；差 **2.22e-16** |
| 位置/旋转/透明度求值（多层求和 + 边界语义） | `rpe.rs:306-330`、`anim.rs:89-121` | 与「照源码手算」最大差 **4.40e-2 / 4.77e-1 / 2.22e-1**（每 100 抽样有 58/33/10 个超 1e-3） | **2.22e-16 / 3.55e-15 / 2.22e-16**（0 个超 1e-3） |
| 父判定线 `father` / `rotateWithFather` | `line.rs:211-228`、`object.rs:53-57` | 完全没接：子线偏移 **8.07e-2**，`rotateWithFather` 不生效（角度 0° vs 应为 -30°），3 个包各有 18/11/26 条子线全错 | **Δ=0**（16:9 与 4:3 都是），角度正确 |
| 音符 `alpha` / `visibleTime` | `rpe.rs:578-587`、`note.rs:214/258` | 没接 → Cyberneko 同一时刻在屏上画 **806 个**音符（1455 个 fake 装饰音整段都在画） | **13 个**（正常密度） |
| 音符 `size` / `judgeArea` | `rpe.rs:595/604`、`note.rs:185-189`、`judge.rs:581-586` | 没接（所有音符一样大、判定宽度全按 1） | 接上（`Note.size` / `Note.judgeArea` → `Engine.Visible.scale` / 判定距离） |
| 音符 `yOffset` | `rpe.rs:538` | 没接 | 烘进 `Note.height`（三个样本都是 0，等价） |
| `alphaControl` / `sizeControl` / `posControl` / `yControl` | `rpe.rs:610-636`、`note.rs:171-232` | 没接 | 4 条「按高度求值」的轨道接进 `Engine`（三个样本恒 1，见 §1.2） |
| 缓动 | `tween.rs:99-113`、`rpe.rs:89-100` | 折线近似（6 段/16 段） | 位置/旋转/透明度**精确**（`Chart.Ev.tween` 直接存缓动函数）；速度仍分段（§1.4 说明） |
| `RPEVersion<170` 速度语义 | `rpe.rs:415-490` vs `332-413` | 只按「忽略缓动」猜 | 逐行核对过（老语义 = 段内线性 + 梯形积分），样本上两分支差 **0.000** |
| 官方 PGR 回归 | — | — | 三条 StressTest **逐字节一致** |

`gradle build`：**BUILD SUCCESSFUL**（0 错误 0 警告），产物 320 KB。
探针原始输出：`C:\dsh-work\evidence\rpe_probe_{before,badmap,after}.txt`。

### 0.1 缓动表错位一位（这一版修掉的真正元凶）

`parse.rs:75-94` 的 `RPE_TWEEN_MAP` 是 30 个元素的数组，**下标 1 就是 `easingType = 1`**：

```
[2, 2,                       // [0] 不用, [1] Linear
 e(Sine,Out), e(Sine,In),    // [2] SineOut, [3] SineIn
 e(Quad,Out), e(Quad,In),    // [4] QuadOut, [5] QuadIn
 e(Sine,InOut), e(Quad,InOut),   // [6], [7]
 e(Cubic,Out), e(Cubic,In),      // [8], [9]
 ...                         // 一直到 [29] ElasticInOut
]
```
上一版我把整张表当成了 `[2, 2, 4, 3, 7, …]` 从下标 0 开始读，于是 **`easingType = k` 取到了 `RPE_TWEEN_MAP[k-1]`**，
即所有 ≥2 的缓动都用成了「前一条曲线」（例如 `easingType=8` 应该是 CubicOut，实际用了 QuadInOut；
`easingType=2` 应该是 SineOut，实际当成线性）。

为什么上一版的「对照 prpr 手算」没抓出来：那个参考实现里我也写了一张**同样错位的表**，
两边一起错，所以你看到的 1e-16 是「自洽」而不是「正确」（我的 Python 复核脚本也犯了同样的错，
把原始 `easingType` 直接当 TweenId 用）。现在改成：

1. 用 Python **从 `parse.rs` 的源码文本**把 `e(...)` 展开成数字表（不经过任何实现），
   生成 `easing_map.txt`，再写进 `EasingCheck` 的 `MAP` 常量逐项断言；
2. `TWEEN_FUNCTIONS` 的 31 条曲线另外用 Python **从 `tween.rs` 的公式**独立算 `f(0.25)/f(0.5)/f(0.75)`
   共 93 个数，写成 `EasingCheck.EXPECT` 断言（`Base` 函数、`f2!`/`f3!` 组合各写一遍）；
3. `EasingCheck` 还检查 31 条曲线 `f(0)≈0、f(1)≈1`（容差 2e-3：expo/elastic 端点本身差 2^-10，不是 bug）。

顺带发现：RPE 的 30 项表只用到 28 个 TweenId，`QuintInOut(17)` 和 `ExpoInOut(20)` 在 RPE 里**没有对应
easingType**（曲线存在但选择不到）。`EasingCheck` 把 33 条曲线全测了。

## 1. 逐项对照 prpr

### 1.1 父判定线（`father` / `rotateWithFather`）

源码（`line.rs:211-228`）：

```rust
fn fetch_rot(&self, lines) -> f32 {
    let mut rot = self.object.rotation.now();
    if self.rot_with_parent { if let Some(p) = self.parent { rot += lines[p].fetch_rot(lines); } }
    rot
}
fn fetch_pos(&self, res, lines) -> Vector {
    if let Some(p) = self.parent {
        lines[p].fetch_pos(res, lines) + Rotation2::new(lines[p].fetch_rot(lines).to_radians()) * self.object.now_translation(res)
    } else { self.object.now_translation(res) }
}
```
其中 `now_translation` 是 `object.rs:53-57`：`tr.y /= res.aspect_ratio`。

要点（也是我上次判断「不能做」的错处）：
- 子线的位置 = **父线位置 + R(父线合成角度) · 子线自身位移**；
- 子线的**朝向** = 自身角度 +（`rotateWithFather` 为真时）父线合成角度；
- 旋转变换必须按 `aspect` 做（世界 y 要先除以 aspect），所以**求值函数必须带 aspect 参数**，
  不能把父变换烘进 `moveX/moveY`（那样只在某个固定比例下正确）。

实现（`Chart.java`）：
- `Line.parentIndex` / `Line.rotateWithFather` / `Line.showBelow`（`:140-147`）；
- `Line.worldPos(sec, aspect, out)`（`:243`）递归合成父链，`xAt/yAt(sec, aspect)`（`:262/269`）由它换算；
- `Chart` 构造时把 `index → Line` 接上（`:207` `breakCycle` 挡环，`rpe.rs:915-931` 是直接报错）；
- `rotateAt(sec)`（`:285`）= 自身角度 +（条件成立时）父线角度；
- 不带 aspect 的重载（`:276/280`）按 16:9，等价于 `Engine.DEFAULT_ASPECT`。

调用点：`Engine.clickLocalX`/`noteLocal`/`screenPos`/`viewportLocalBounds`、`PlayScreen` 的判定线与音符绘制
本来就有 aspect，改成传进去（`PlayScreen.java:131/132/151/152`，`Engine.java:348`）。
无父线的谱面（**全部 PGR 谱面**、RPE 里 father=-1 的线）走的是同一条算术路径，结果逐位相同。

实测（§3 ③）：合成谱 rotateWithFather=false/true × aspect 16:9/4:3 与手算 **Δ=0**；
真实谱里带父线的线（Cyberneko 18 条、Hardsync 11 条、推活 26 条）与独立参考实现 **Δ≤1.1e-16**。

### 1.2 控制器 `alphaControl` / `sizeControl` / `posControl` / `yControl`

源码（`rpe.rs:610-636` + `note.rs:171-232`）：
- 控制器的自变量**不是时间**，是 `x`（`rpe.rs:107-110` 的 `RPECtrlEvent`），单位是
  「音符到判定线的高度 × `RPE_HEIGHT/2`」：`note.rs:172`
  `set_height((note.height - line_height + translation.y/speed) * RPE_HEIGHT / 2)`；
- 四条轨道分别是：`alpha` 乘音符透明度（`note.rs:217`）、`size` 乘缩放（`note.rs:185-189`）、
  `pos` 乘横向位移（`note.rs:181`）、`y` 乘下落速度（`note.rs:218`）；
- 缓动是**错位**的：区间 `[x_i, x_{i+1}]` 用事件 **i+1** 的 `easing`（`rpe.rs:619-624`）；
- 特例：只有 2 个事件、第一条 `easing == 1` 且值 ≈ 1 时等于恒 1（`rpe.rs:612` 短路）。

实现：
- `RpeParser.ctrlTrack()`（`:414`）按上面的口径建轨道，含恒 1 短路；
- `Chart.Line.ctrlAlpha/ctrlSize/ctrlPos/ctrlY` + `Chart.Line.ctrlAt(list, height)`（`Chart.java:156/315`）；
- `Engine.CTRL_HEIGHT_SCALE = 450`（`Engine.java:73`），在 `noteLocal`（`:372`）与 `visible`（`:478-481`）里
  按「当前高度」求值并乘上去。

**三个样本的真实情况**：106/112/210 个控制事件，值**全部是 1.0**（`{alpha,size,pos,y}` 各一组），
也就是这些谱面根本没用控制器。所以这一项接上以后对这三首**画面零变化**——但它不再是「没实现」，
以后遇到用控制器的谱（RPE 里常用来做「音符越靠近线越小/越淡」）就能正确显示。

### 1.3 音符 `alpha` / `visibleTime` / `yOffset` / `size` / `judgeArea`

源码（`rpe.rs:576-605`）：

```rust
alpha: if note.visible_time >= time {
           if note.alpha >= 255 { default() } else { fixed(note.alpha/255.) }
       } else {
           AnimFloat::new([kf(0., 0., 0), kf(time - note.visible_time, alpha.min(255)/255., 0)])
       }
size: AnimVector::fixed(note.size)
...
judge_area: note.judge_area.unwrap_or(1.0)
```
`yOffset` 落在 `translation.y = yOffset * 2 / RPE_HEIGHT * speed`（`rpe.rs:538`），
渲染时 `tr.y += base`（`note.rs:183`）。

实现：
- `Chart.Note.alpha` / `visibleFrom` / `size` / `judgeArea`（`Chart.java:364-381`）+ `alphaAt(sec)`（`:430`）；
- `RpeParser.readNotes`（`:180`）按上面的语义填：`alpha>=255 → 1`、
  `visibleTime >= time → 一直可见`、否则 `(time - visibleTime)` 之前全透明；
  `yOffset` 烘进 `height`（`height += 2*yOffset/900`，之后 `Δheight*speed/aspect` 与 prpr 的
  `translation.y/aspect + base` 完全等价）；
- `Engine.visible` 把 `note.alphaAt(sec) * ctrl.alpha` 乘进透明度（`:503`），`note.size * ctrl.size`
  放进 `Visible.scale`（`:507`），`PlayScreen` 用 `v.scale()` 缩放贴图（`:156`）；
- `judgeArea` 进 `NoteState.judgeArea`（`Engine.java:98`），判定距离本来就除它。

**效果（这是「画面脏」的主因）**：Cyberneko 的 1455 个 fake 装饰音符 `visibleTime` 全是 0.1–0.2s，
按源码语义它们**只在该音符判定前 0.1–0.2 秒才出现**。修前我们把它们当成整段可见，
于是同一时刻屏幕上画了 **806 个**音符（`visible()` 返回数）——正常密度应该是十几个。修后：

```
Cyberneko Pulse (2).pez  屏上音符数: 修前 t=10s:806  t=30s:757  t=60s:369  t=90s:528  t=120s:76
                                    修后 t=10s: 13  t=30s: 12  t=60s: 21  t=90s: 27  t=120s:20
推活天使ビビアンちゃん         修前 t=10s: 66  t=30s: 73  t=60s: 76  t=90s: 73  t=120s:79
                                    修后 t=10s:  5  t=30s: 13  t=60s: 21  t=90s: 17  t=120s:23
Hardsync.pez                   修前/修后基本相同（它只有 16 个音符 visibleTime 是小值）
```
（这三首的可判音符密度是 13 / 9.6 / 11.6 个每秒，修后的数量正好对得上。）

另外 `alpha == 0` 的音符（Cyberneko 9 个 size=9 的「闪一下」）现在直接不提交绘制，
`judgeArea` 也让 Hardsync 那 42 个 `judgeArea=1000` 的音符恢复成「怎么点都算」的原始设计。

### 1.4 缓动

`rpe.rs:89-100` 的 `RPEEvent::tween`：贝塞尔优先 → 线性/无夹取直接返回 `StaticTween` → 否则 `ClampedTween`；
`parse.rs:75-94` 的 `RPE_TWEEN_MAP`（1..29），`tween.rs:99-113` 的 33 条曲线。

实现：
- `Chart.Ev` 增加 `tween` 字段（`Chart.java:455`）：**位置/旋转/透明度不再折线化**，
  每个事件段直接把缓动函数挂上去，`Ev.lerp` 里 `v0 + (v1-v0)*tween.y(f)`（`:483`），
  与 `anim.rs:113-121` 的 `kf1.tween.y(t)` 一一对应；
- `RpeParser.tweenOf`（`:542`）逐条实现 `tween.rs` 的 33 条曲线（含 `easingLeft/Right` 的 ClampedTween
  和贝塞尔的牛顿+二分求解），`tweenId`（`:570`）是完整映射表 —— **这张表上一版错位了一位，见 §0.1**，
  现在由 `EasingCheck`（对着从源码文本展开的数字表）逐项钉住；
- 多层事件**不再合并成一条**：`Chart.Line.moveX/moveY/rotate/alpha` 现在是
  `List<List<Ev>>`（每条事件层一条轨道，`Chart.java:134`），求值时 `layerValue`（`:299`）求和，
  与 `anim.rs:27-33` 的 `Anim::next` 链等价。这样「两条带缓动的曲线相加」也是精确的。

**速度事件仍然分段线性**（`RpeParser.speedSegments:284`）：`Chart.heightAt` 的 keyframe 模型只能表达
「段内常量斜率」，而 prpr 给速度积分单独造了 `SpeedIntegralTween`（`rpe.rs:212-292`）。
折线粒度是「每 20ms 一块、8..64 块」（线性段 1 块就够，且**精确**）：
块边界上的累计高度精确，只有块内是弦线。三个样本的速度事件 **100% 是 `easingType=1`（线性）**，
所以实际误差 **0**（§3 ② 的高度与参考实现差 1e-16 就是这个原因）。

### 1.5 `RPEVersion < 170` 的速度语义

逐行核对 `rpe.rs:415-490`（`parse_speed_events_legacy`）：

```rust
kfs.push(Keyframe::new(r.time(&e.start_time), e.start, 2));   // 429-430：tween 固定 2 = 线性
kfs.push(Keyframe::new(r.time(&e.end_time),   e.end,   0));   // 431
...
height += (speed + end_speed) as f64 * (end_time - now_time) / 2.;   // 483：梯形积分
```
结论：老版本**只是「速度段内线性、忽略 easingType」**，积分方式是梯形；没有别的语义。
（新版本 `rpe.rs:392-395` 才把 `easingType` 通过 `SpeedIntegralTween` 用上，等价于
`speed(t) = start + (end-start) * ease(t)`。）

实现：`speedSegments(..., modern)` 里 `modern ? tweenOf(e) : null`（`RpeParser:284-300`），
`modern = RPEVersion >= 170`（`rpe.rs:862`）。

实测（§3 ④）：三个样本的**非线性速度事件都是 0 个**，所以老/新两种解释在它们身上**差 0.000**；
分支仍然按源码区分，遇到真正带缓动的速度事件才会不同。

### 1.6 顺带修掉的两个求值口径 bug（不在清单里，但会系统性地错）

1. **同一时刻取哪一段**：prpr 的 `Anim` 用「最后一个 `time <= t` 的关键帧」（`anim.rs:94-102` 的 cursor 只前进），
   而我们的二分查找会随机落在前一段或后一段。事件首尾相接时，音符判定时刻**刚好落在事件边界**是常态
   （Cyberneko 抽样里就有一批 y 差 0.35）。现在 `Ev.valueAt`（`Chart.java:519`）明确取「最后一个 `t0 <= sec` 的段」。
2. **第一条事件之前**：prpr 是拿 `tween(负参数)` 往外算的（`anim.rs:113-121` 不夹取），
   我们原来直接给 `v0`。现在 `Ev.lerpUnclamped`（`:495`）按源码外推。

这两条对 PGR 的影响：PGR 的事件都从 0 开始且首尾相接，`SpecTest`/`RotTest`/`Main` 抽查值一字未变（§5）。

## 2. 文件清单

### 新增

| 文件 | 行数 | 关键位置 |
|---|---|---|
| `core/.../core/RpeParser.java` | 826 | `looksLikeRpe`91 · `parse`100 · `parseLine`125 · `readNotes`180 · `track`242 · `speedTrack`258 · `speedSegments`284 · `segments`332 · `tail`365 · `ctrlTrack`414 · `merge`458 · `tweenOf`542 · `tweenId`570 · `tween`610 · `Beats`744 |
| `core/.../core/audio/Mp3ToWav.java` | 121 | `Pcm`27 · `isMp3`39 · `decode`46 · `toWav`93 |
| `core/src/test/.../RpeAccept.java` | 328 | 验收：解析 + mp3 解码 + autoplay + 断言 |
| `core/src/test/.../RpeProbe.java` | 640 | 四组探针：`screenRate`86 · `reference`133 · `syntheticParent`215 · `realParent`267 · `speedModeDiff`296 · 独立参考实现 `Ref`485 |
| `core/src/test/.../EasingCheck.java` | 152 | 缓动硬核对：`RPE_TWEEN_MAP` 30 项 + 31 条曲线 93 个采样点（数字都是从 `parse.rs` / `tween.rs` 的**源码文本**展开，不由实现反推） |
| `lib/jlayer-1.0.1.4.jar` | 141 KB | JLayer（LGPL，未改） |
| `docs/REPORT-rpe.md` | 本文件 | |

### 修改

| 文件 | 位置 | 改动 |
|---|---|---|
| `core/.../ChartParser.java` | 47-68, 100-102 | 格式自动识别（`RpeParser.looksLikeRpe`54）；PGR 的事件包成「单层」 |
| `core/.../Chart.java` | 46 / 52-60 | `DEFAULT_ASPECT`、`format` 字段 |
| | 130-190 | `Line.moveX/moveY/rotate/alpha` 改为 `List<List<Ev>>`（分层）；新增 `parentIndex`/`rotateWithFather`/`showBelow`/4 条 ctrl |
| | 207-320 | 父链合成 `worldPos`/`xAt(sec,aspect)`/`yAt(sec,aspect)`/`rotateAt`/`layerValue`/`ctrlAt` |
| | 355-440 | `Note.alpha`/`visibleFrom`/`size`/`judgeArea` + `alphaAt`；`clampSpeed` 构造链 |
| | 445-545 | `Ev.tween`、`lerpUnclamped`；`valueAt`/`segmentAt` 改成「同刻取后一段」 |
| `core/.../Engine.java` | 73 | `CTRL_HEIGHT_SCALE = 450`（`note.rs:172`） |
| | 98 | `judgeArea` 从音符来（`rpe.rs:604`） |
| | 108 | `Visible` 增加 `scale`（只读字段，给渲染层用） |
| | 348-380 | `clickLocalX`/`noteLocal`/`screenPos` 传 aspect；`noteLocal` 乘 ctrl 的 `pos`/`y` |
| | 463-511 | `visible`：`showBelow` 门控（`rpe.rs:821`）、ctrl alpha/size、`note.alphaAt`、`Visible.scale` |
| `mod/.../client/PlayScreen.java` | 131-132, 151-152 | 判定线/音符绘制用带 aspect 的重载 |
| | 156-158 | 音符宽度乘 `v.scale()`（RPE 的 `size`） |
| `mod/.../client/SongEntry.java` | 177-330 | info.txt 的 `Chart:`/`Song:`/`Picture:` + 「后缀唯一」定位 |
| `mod/.../client/Session.java` | 330 | 日志加格式 |
| `core/.../Main.java` | 28, 53-54, 121-125 | 打印格式；事件有序性检查改成按层 |
| `core/src/test/.../RotTest.java` / `SpecTest.java` | 140 / 121 | 构造 `Chart.Line` 时把事件包成单层 |
| `mod/build.gradle` | 17-27, 56-67 | JLayer 编译依赖 + 摊进 mod jar |
| `README.md` | 多处 | 状态表 / `.pez` 用法 / RPE 格式结论 / 已知简化 |

`Engine`/`PlayScreen` 只动了上面这些「把已有参数传进去 + 新增只读字段」的地方，
判定窗口、计分、评级、绘制顺序一行没改；PGR 路径全部走同一份代码且测试输出不变。

## 3. 探针输出（修前 / 修后）

三列对照：
- **v1** = 第一版实现（`C:\dsh-work\rpeprobe\old.jar`：没有父线、缓动折线近似、缓动表也错）；
- **v2** = 上一版（父线/音符可视/精确缓动都做了，但**缓动表错位一位**；用同一份源码只把 `tweenId` 换回旧版编出来，
  见 `C:\dsh-work\rpeprobe\bugout`）；
- **v3** = 当前代码（`rpe_probe_after.txt`）。

### ① 落屏率（真实音符在判定时刻的屏幕坐标）

```
修前（16:9）  严格 [0,1]  留半个音符  排除哨兵线
  Cyberneko     98.17%      98.23%      98.77%
  Hardsync      94.32%      95.34%      96.66%
  推活天使      88.73%      93.22%      88.73%

v2（缓动表错位，16:9）
  Cyberneko     98.46%      98.46%     100.00%
  Hardsync      93.81%      94.51%      96.39%
  推活天使      87.18%      93.89%      87.18%

v3（当前，16:9）
  Cyberneko     98.46%      98.46%     100.00%     屏外 25 个，全部 >10 屏高（哨兵线）
  Hardsync      94.45%      94.96%      97.05%     屏外 87 = 43 个 ≤0.1 屏高 + 2 个 ≤1 + 42 个 >10
  推活天使      87.37%      94.03%      87.37%     屏外 343 = 303 个 ≤0.1 屏高 + 40 个 0.1~1，没有 >1 的
（aspect=4:3 / 21:9 的数字见 evidence 文件，差异 <1%，说明换算不依赖宽高比）
```

**"没到 99%"是什么**：判定时刻音符**必然落在判定线上**（`Δheight = 0`，数学上恒成立），
所以"音符在不在屏内"= "判定线此刻在不在屏内"。逐个查了 348+97+25 个屏外音符：

- **哨兵线**（Cyberneko 25、Hardsync 44）：谱面把线的 `moveX/moveY` 写成 `±9999 / ±22221`（编辑器里用来「藏起来」），
  线被停到 20 屏高以外，它的音符当然也在外面。这是**谱面数据**，Phira 里一样在屏外。
- **贴边线**（推活 348 全部、Hardsync 53 个）：谱面把线停在**画布边缘外一点点**，例如
  `moveX = 685`（画布半宽 675，超 1.5%）→ `xAt = 1.0074`，或 `moveY = -460`（画布半高 450）→ `yAt = -0.0111`，
  或 `moveY = -1600 / -800`（更远）。所有 348 个的出界距离**平均 1.2%、最大 26%**，即"压在屏幕边上"。
  这些音符在 Phira 里也是压在边上（判定只看横向坐标，照样能打）。

**为什么我没有去凑到 99%**：唯一的办法是把坐标系乘一个 0.98x 的系数（或给 `RPE_WIDTH/HEIGHT` 加 padding），
那会让 200 个随机抽样与 prpr 的比对从 **1e-16** 变成 **~1e-3~1e-2**，等于用一个偏差换一个指标。
prpr 用的是 `RPE_WIDTH = 1350` / `RPE_HEIGHT = 900`（`rpe.rs:22-23`），我保持这个口径。

### ② 对照 Phira 公式（随机 200 音符，其中 100 个带父线）

```
修前  Cyberneko  抽样100  最大误差 4.401e-02  平均 1.028e-02  超 1e-3 的 58 个
      Hardsync   抽样100  最大误差 4.768e-01  平均 2.307e-02  超 1e-3 的 33 个
      推活天使    抽样100  最大误差 2.222e-01  平均 7.527e-03  超 1e-3 的 10 个
      屏外音符与参考实现的最大误差：6.667e-01 / 2.525e+01 / 1.131e+00

v2（父线/可视/精确缓动都做了，但缓动表错位一位 → 说明这一步比 v1 还重要）
      Cyberneko  抽样200  最大误差 5.833e-01  平均 2.779e-02  超 1e-3 的 84 个
      Hardsync   抽样200  最大误差 4.635e-01  平均 1.595e-02  超 1e-3 的 92 个
      推活天使    抽样193  最大误差 3.429e-01  平均 1.408e-02  超 1e-3 的 67 个
      屏外音符与参考实现的最大误差：1.016e-02 / 7.813e-01 / 3.889e-01
      （受影响的事件占比：Cyberneko 30.9%、Hardsync 19.7%、推活 76.4%）

v3（当前）Cyberneko  抽样200（带父线100）  最大误差 2.220e-16  平均 2.276e-17  超 1e-3 的 0 个
      Hardsync   抽样200（带父线100）  最大误差 3.553e-15  平均 4.968e-17  超 1e-3 的 0 个
      推活天使    抽样193（带父线 93）  最大误差 2.220e-16  平均 2.761e-17  超 1e-3 的 0 个
      屏外音符（25 / 87 / 343 个）与参考实现的最大误差：3.553e-15 / 0.000e+00 / 4.441e-16
```

参考实现 `RpeProbe.Ref` 是**另写一遍**的（不复用 `Chart` 的任何求值代码）：自带 BPM 折秒、
自带关键帧式 `Anim` 求值（光标语义）、自带 33 条缓动表、自带父链矩阵、自带速度积分（Simpson）。
1e-16 是双精度舍入量级，也就是**逐位一致**。

### ③ 父判定线

合成谱（父线 `rotate=30`、子线位移 `(100,100)`、子线自转 0）：

```
rotateWithFather=false  aspect=1.78  手算 (0.595400, 0.469619) / 本项目 (0.595400, 0.469619)  Δ=0.00e+00  角度 0.00°
rotateWithFather=false  aspect=1.33  手算 (0.605817, 0.453158) / 本项目 (0.605817, 0.453158)  Δ=0.00e+00  角度 0.00°
rotateWithFather=true   aspect=1.78  手算 (0.595400, 0.469619) / 本项目 (0.595400, 0.469619)  Δ=0.00e+00  角度 -30.00°
rotateWithFather=true   aspect=1.33  手算 (0.605817, 0.453158) / 本项目 (0.605817, 0.453158)  Δ=0.00e+00  角度 -30.00°
（修前：Δ=8.07e-02 / 6.43e-02，且 rotateWithFather 完全不生效，角度恒 0）
```
真实谱（带父线的线，逐包各取 3 条）：

```
Cyberneko  line 8  father=47 rwf=false  t= 85.03s  本项目(0.98360,0.74946) 手算(0.98360,0.74946) Δ=0
           line 9  father=47 rwf=true   t= 88.76s  本项目(0.38889,0.83333) 手算(0.38889,0.83333) Δ=5.6e-17
Hardsync   line 1  father=42 rwf=false  t= 82.20s  本项目(0.36117,0.84070) 手算(0.36117,0.84070) Δ=0
推活天使    line62  father=61 rwf=false  t=178.82s  本项目(-0.08634,-10.62925) 手算同  Δ=0
（修前这些包全都报「没有带父线的判定线」——旧 `Chart.Line` 根本没有父子关系）
```

### ④ `RPEVersion < 170` 速度语义

```
Cyberneko  RPEVersion 判定=>=170（吃缓动），谱面里非线性的速度事件 0 个；老/新语义高度最大差 0.000e+00
Hardsync   RPEVersion 判定=>=170（吃缓动），谱面里非线性的速度事件 0 个；老/新语义高度最大差 0.000e+00
推活天使    RPEVersion 判定=<170（段内线性），谱面里非线性的速度事件 0 个；老/新语义高度最大差 0.000e+00
```
（三首的速度事件全是 `easingType=1`，所以分支在样本上不产生差异；分支本身按 §1.5 的逐行核对实现。）

## 4. 三首歌的解析结果（`RpeAccept`，未变）

| 文件 | 格式 | 曲名 / 难度 | BPM | 音符 | 判定线 | 总时长 | META.duration | 音频解码 | autoplay |
|---|---|---|---|---|---|---|---|---|---|
| Cyberneko Pulse (2).pez | RPE 170 | Cyberneko Pulse / AT Lv.17 | 175 | **3092**（fake 1455） | 53 | 125.897 s | 128.976 s（差 3.08） | 128.952 s | 1000000 / 0 Miss |
| Hardsync.pez | RPE 170 | Hardsync / AT Lv.16 | 200 | **1739**（fake 172） | 56 | 163.080 s | 164.075 s（差 0.99） | 164.101 s | 1000000 / 0 Miss |
| 推活天使ビビアンちゃん .pez | RPE 160 | 推活天使ビビアンちゃん / AT Lv.18 | 171→300（14 段） | **2800**（fake 85） | 105 | 232.953 s | 240.509 s（差 7.56） | 240.091 s | 1000000 / 0 Miss |

第 3 首的 7.56 s 仍是**谱面尾部留白**（最后音符 232.953 s，音频 240.091 s，尾部 7.1 s 没音符），
不是解析误差（Python 独立复算一致，音符数逐个对上）。详见 §6。

## 5. PGR 回归（逐字节一致）

```
pkg1：1285 音符 / 24 判定线 / 117.7s
[自动播放] 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 最大连击=1285/1285
[不操作]   分数=309728 P=398 M=887/1285（其中 Drag 自动 Perfect 398 个）
[±60ms]    分数=865817 评级=B P=1096 G=155 B=34 M=0 最大连击=355/1285

pkg2：1418 音符 / 24 判定线 / 150.9s
[自动播放] 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 最大连击=1418/1418
[不操作]   分数=449224 P=637 M=781/1418（其中 Drag 自动 Perfect 637 个）
[±60ms]    分数=763290 评级=C P=1185 G=17 B=10 M=206 最大连击=59/1418
```
与改动前的 `evidence/stress_pkg1.txt` / `stress_pkg2.txt` **完全一致**。
`SpecTest`（11 项）、`RotTest`（39 例）、`AudioClockTest`、`Main fixtures/spasmodic`（抽查事件值
x/y/rot/alpha/floor 全部相同）也都照旧通过。

真实 `songs/` 目录 16 首（13 个 PGR zip + 3 个 pez）全部解析成功，zip 的音符数/时长与改动前一致。

## 6. 第 3 首的 7.56 s（老问题，仍然存在）

`推活天使` 最后 3 个音符是 line7/6/8 上的 HOLD，结束于 232.953 / 232.853 / 232.753 s；
音频（解码）240.091 s，`META.duration` 240.509 s。也就是谱面在歌结束前 7.1 s 就完了（纯尾声）。
Python 独立复算得到同一个 232.953 s，音符分布 `{TAP1357, HOLD661, FLICK205, DRAG577} = 2800`
与解析器逐个对上。**没有为了凑 5 s 容差去改断言**：程序同时打印
「音频时长 vs META.duration」（0.024 / 0.026 / 0.418 s）和「谱面最后音符 vs META.duration」
（3.08 / 0.99 / **7.56** s），第二个口径第 3 首打 ⚠。

## 7. 仍然没做 / 为什么

| 没做 | 原因 |
|---|---|
| `extended.scaleX/scaleY` | `Chart.Line` 没有缩放；我们的渲染是固定长度的矩形线，加缩放要先改 `PlayScreen` 的线长/线宽（本次硬约束不改渲染逻辑）。这三首的 `scaleXEvents` 只有推活 20 个，且 `scaleYEvents` 全空 |
| `extended.incline` | 同理（倾斜要把矩形画成平行四边形，MC 的 `ctx.fill` 只能画轴对齐矩形，需要改渲染） |
| `extended.color/text/paint/gif`、`isGif` | 需要贴图/文字/着色器，本项目没有这些渲染能力 |
| RPE 的「贴图判定线」（`Texture != line.png`） | 三个样本全是 `line.png`；我们的渲染只有白线 |
| `hitsound` 自定义打击音 | 项目还没有打击音效系统 |
| 非线性缓动的**速度**事件仍分段线性 | `Chart.heightAt` 的 keyframe 模型只表达「段内常量斜率」。要做精确得给 speed 轨道也加 `Tween` 并重写 `heightAt` 的积分（PGR 路径共用这段代码，风险大于收益）。三个样本的速度事件 100% 线性，误差 0 |
| 在真实 Minecraft 客户端里跑 | 无头环境。替代验证：产物 jar 独立跑通三首（`java -cp phimc-0.1.0.jar:gson ...RpeAccept` 需要 RpeAccept 在 classpath 里，所以是先编译进 out 再跑）、`SongEntry.scan` 对真实 `songs/` 跑通、判定引擎 autoplay 满分 |
| 「重来」时 mp3 重解码（暂停菜单） | `Session.restart()` 在渲染线程同步 `play()`，会卡 ~0.6 s。改线程模型的风险比这个卡顿大 |

## 8. 复现

```powershell
# 构建（离线）
robocopy "<工作区>\phimc" C:\dsh-work\phimc /MIR /XD out .gradle build fixtures dist run
$env:JAVA_HOME="C:\dsh-tools\jdk21"; $env:GRADLE_USER_HOME="C:\dsh-gradle-home"
& "C:\dsh-tools\gradle-8.10.2\bin\gradle.bat" -p C:\dsh-work\phimc\mod build --no-daemon --console=plain --offline

# 验收（不需要 Minecraft；jlayer 必须在 classpath 上）
$cp = "lib\gson-2.11.0.jar;lib\jlayer-1.0.1.4.jar"
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -d out -cp $cp (Get-ChildItem core\src\main\java -Recurse -Filter *.java).FullName
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -d out -cp "out;$cp" (Get-ChildItem core\src\test\java -Recurse -Filter *.java).FullName
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" dev.dsh.phimc.core.RpeAccept
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" dev.dsh.phimc.core.RpeProbe
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" dev.dsh.phimc.core.EasingCheck

# 回归
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" dev.dsh.phimc.core.StressTest C:\dsh-work\pkg1\Chart_IN.json
& "$env:JAVA_HOME\bin\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" dev.dsh.phimc.core.StressTest C:\dsh-work\pkg2\Chart.json
# 修前对照：把上面 out 换成 C:\dsh-work\rpeprobe\probe_old，classpath 换成 C:\dsh-work\rpeprobe\old.jar
```
