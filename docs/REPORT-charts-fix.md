# 谱面三问题修复报告（卡死 / 特效谱观感 / PEC 缓动）

> 参考实现只有一份：`C:\dsh-work\phira-src\prpr\src\`（Phira **v0.8.2**，GPL-3.0）。
> 本文里 `pec.rs:NN` / `rpe.rs:NN` / `line.rs:NN` / `note.rs:NN` / `anim.rs:NN` / `tween.rs:NN` /
> `chart.rs:NN` / `object.rs:NN` / `pgr.rs:NN` 都是指 `prpr/src/` 下的文件与行号。
> 凡标注「**源码无此项**」的都是 MC/Java 平台特有的优化，会写清理由，不冒充源码依据。
>
> 所有测量都是 headless（纯 Java，不碰 Minecraft）：`tools/chart/PerfProbe.java`（载入 + 每帧开销）、
> `tools/chart/ParseSplit.java`（解析各阶段峰值堆）、`tools/chart/FieldProbe.java`（谱面字段使用率）、
> `tools/chart/PecCompare.java`（PEC 独立参考实现 vs 我们）。原始输出在 `C:\dsh-work\`。

---

## 0. 一页结论

| 问题 | 定位 | 根因 | 修法 | 修前 → 修后 |
|---|---|---|---|---|
| 一打开就卡死 | `77769_big song (self insert).pez`（46.72MB / 741 线 / 306343 事件 / 694s） | 解析走 Gson **整棵树**：树 526MB + 原串 285MB + 模型 155MB ⇒ 峰值堆 **977MB**、客户端线程阻塞 **1469ms** | 流式解析：`JsonReader` 逐个元素读 `judgeLineList`，转成 `Chart.Line` 就丢；格式识别也改成只扫顶层键名 | 峰值堆 **977 → 467MB**；解析 **1245–1315 → 745–851ms**；冷启动 **1469–1474 → 925–1049ms** |
| 一打开就卡（第二类：音符爆炸） | `琵琶曲DJ(1.15x).pez`（**34211 音符全在一条判定线上**） | `Engine.visible()` 每帧从 `states[0]` 扫到末尾，O(全谱音符数) | 加只前进的前缀游标 `visibleStart`（等价 `line.rs:185-208` 的 `above_indices` 推进 + `note.rs:236-241`）；渲染层把「每条线每帧的变换」缓存（等价 `line.rs:240` 的 `with_model`） | 平均帧 **1.29–1.75 → 0.91–1.13ms**；最差 1 秒 **205–294 → 128–132ms**；单帧峰值 **36.6 → 6.0–11.0ms** |
| 特效谱观感不对 | 8 首谱的 zOrder、9 首的 colorEvents、5 首的文字线 | 见 §2 差异表（6 项已修，65 条假横条消失） | 见 §2 | — |
| `6954_-1` 缓动丢失 | PEC 的 `cm/cr/cf/ca` | PEC 事件轨道的**起点值**取自「文件顺序里的上一条事件」，而 `prpr` 取「按结束时刻排序裁切后的前一个关键帧」（`pec.rs:104-120`）。PEC 生成器把 `cp`（瞬移）和 `cm`（插值）交错导出，两种顺序不一致 ⇒ 插值从完全无关的值出发 | 改成和 RPE 一样的 keyframe 序列模型（`sanitizeKfs`） | 全 30 条线 × 1448 采样点，最大偏差 **2.27e-13**（修前 line 18 单点角度差 **211°**、位置差 **0.40**） |

`gradle :1.21.1:build` **BUILD SUCCESSFUL**；`StressTest` 仍 **1000000 / PHI / 0 Miss**。

---

## 1. A：卡住

### 1.1 先量：逐首跑完整曲库（`PerfProbe`）

40 个谱面包全部能解析（本次会话中曲库从 39 涨到 40，新包同样通过）。按「冷启动」和「每帧开销」排出来的重灾区：

| 谱面 | JSON | 线 | 音符 | 事件 | 解析 | 冷启动 | 峰值堆 | avg 帧 | max 帧 | 最差 1s |
|---|---|---|---|---|---|---|---|---|---|---|
| `77769_big song` | 46.72MB | 741 | 7741 | 306343 | 1315ms | **1469ms** | **977MB** | 1.91ms | 14.6ms | 34.7ms |
| `琵琶曲DJ(1.15x)` | 13.32MB | 1 | **34211** | 5 | 179ms | 251ms | 208MB | **1.29ms** | **36.6ms** | **205ms** |
| `10176_Super Nova Project` | 31.38MB | 149 | 1370 | 79315 | 320ms | 459ms | 308MB | 0.17ms | 7.8ms | 13.9ms |
| `PRAGMATISMRESURRECTION_IN` | 11.52MB | 24 | 999 | 210844 | 211ms | 279ms | 238MB | 0.05ms | 0.8ms | 2.5ms |
| `祈-我ら神祖と共に歩む者なり-` | 11.32MB | 24 | 2222 | 199175 | 191ms | 237ms | 220MB | 0.05ms | 0.5ms | 2.5ms |

> **假警报，已排除**：`夢の降る日に_IN.zip` 在第一次抽样里出现过 `max 177.8ms / 最差1s 224.3ms`，
> 全量 60fps 扫（`--full`）后是 `max 2.48ms / 最差1s 14.6ms` —— 那是一次 GC，不是算法问题。
> 这类噪声只能靠 `--full` 复测排除，不能只看单次抽样。

### 1.2 根因 A1：「进游戏瞬间卡死」= 解析期一次 1GB 分配

`ParseSplit` 把 `Session.start()` 那条路上的峰值堆拆开（`big song`）：

| 阶段（`big song`） | 耗时 | 峰值堆增量 |
|---|---|---|
| `SongEntry.chartJson()` | — | **+283.7MB**（`readAllBytes()` 的 49MB byte[] + `new String()` 的 98MB char[] + zip 缓冲） |
| `JsonParser.parseString` 建树（**修前**走的路） | 433ms | **+537.1MB**（306343 个事件 × 每个 `JsonObject`(LinkedTreeMap) + 8 个键值） |
| `RpeParser.parse(root)` 建模型 | 455ms | +226.6MB |
| **修前**端到端 `chartJson + parse` | ~1.2s | **+961.7MB**（采样峰值 977MB） |
| **修后**端到端 `chartJson + ChartParser.parse` | **820ms** | **+370.0MB**（采样峰值 467MB） |

`prpr` 没有这一步：serde 直接反序列化成 `RPEChart`（就是最终模型），没有中间树。
所以在 Java 侧「不建树」是**源码无此项的平台优化**，语义完全等价（还是同一份 `parseLine`）。

**修法**（`RpeParser.parseStreaming`）：

* **格式识别也不再建树**：`RpeParser.looksLikeRpe(String)` 用 `JsonReader` 只扫顶层键名
  （值全部 `skipValue`），`ChartParser.parse(String)` 命中就直接进流式解析。
  以前 `ChartParser` 为了识别格式先把整份 JSON 建树再交给 `RpeParser` —— 那才是 526MB 的来源。
* **单遍流式**：遇到 `judgeLineList` 就 `beginArray()`，循环里 `JsonParser.parseReader(r)` 读**一个**
  判定线对象 → 立刻 `parseLine` → 丢掉。峰值只剩「一条线的事件树」（最大的一条 7046 个事件）。
* 键顺序兜底：折秒要 `BPMList`、速度语义要 `META.RPEVersion`，都在 `judgeLineList` 之前才成立。
  实测 20 个 RPE 包都是 `BPMList, META, judgeLineList`（serde 结构体字段序）；万一不是，
  自动退回 `parseTwoPass`（两遍扫描），行为一致。

**修前/修后（同一个 `-Xmx6G` JVM，`--only "big song"` 单跑）**

| 指标 | 修前 | 修后 | 变化 |
|---|---|---|---|
| `chartJson()` 读包 | 150–224ms | 162–194ms | — |
| `ChartParser.parse` | 1245–1315ms | **745–851ms** | **-35%** |
| `Engine` 构造 | 4–5ms | 3–4ms | — |
| 冷启动合计（客户端线程阻塞时长） | 1469–1474ms | **925–1049ms** | **-30%** |
| 解析期峰值堆 | 977–1008MB | **466–468MB** | **-52%** |
| avg 帧（60fps 全时间轴） | 1.40–1.91ms | **0.60–0.99ms** | **-48%** |
| max 单帧 | 14.6–22.3ms | **6.4–9.0ms** | |
| 最差 1 秒合计 | 30–34.7ms | **10.1–19.9ms** | |
| 音频整包读取（`entry.payload()`） | 292ms | 83–267ms | 受页缓存影响 |

剩下的 466MB 里最大一块仍是 `chartJson()` 返回的 49M 字符 `String`（约 285MB）。
再往下压需要把 `SongEntry.chartJson()` 换成「reader/byte[] 两遍扫」，属于接口级改动，本次没做（见 §5）。

### 1.3 根因 A2：`visible()` 每帧扫全部音符

`琵琶曲DJ(1.15x).pez` 把 **34211 个音符放在一条判定线上**（谱面 144.5s，平均 237 音符/秒，
同屏 200–744 个）。`Engine.visible()` 原来是这样：

```java
for (NoteState s : states) {          // ← 从下标 0 扫到 34211
    ...按当前规则逐个剔除...
}
```

每帧 O(全谱音符数)，而且**越到后面越慢**，永远不会变快。

`prpr` 不是这么干的（`core/line.rs`）：

* `JudgeLineCache`（`line.rs:86-133`）给每条线维护 `above_indices / below_indices`；
* `line.rs:185-208` 每帧把这些下标**向前推进**过已经 `Judged` 的音符；
* 绘制循环从这些下标**开始**（`line.rs:396-428`），碰到「已经飘出视口」的音符就 `break`（`line.rs:406/423`）。

**修法**（`Engine.visible`）：`states` 本来就按时间升序，所以「淡出窗口/尾巴都过去了」的音符**一定**
排在最前面，只需要一个只前进的 `visibleStart` 游标。语义等价于源码的「下标推进 + 剔除」，
可见像素一个都不变。回退（重来 / seek）时游标归零。

> **源码无此项的部分**：`prpr` 的 `JudgeLineCache` 依赖它自己「按
> `(plain, above, speed, (height+yOffset)*speed)` 排序」的音符数组（`line.rs:94-105`）；
> 我们的 `states` 必须按时间排序（判定通路 `find()` / `keyboardTap()` 依赖它），
> 所以不能照抄那套结构，改成「时间前缀游标」——目标相同（每帧只碰还在屏上的音符），手段不同。

**同时修掉的两处「白烧」**（`PlayScreen`，对应 `line.rs:240` `with_model(now_transform)`）：

* 音符循环里**每个音符**都重算 `line.xAt/yAt/rotateAt`（各一次二分查找）；
  现在改成每帧每条线算一次（`LineXform` 懒算缓存），音符只查数组。
* hold 的 `floorPositionAt()` 每个 hold 每帧调两次（线性扫速度事件）；改成每条线每帧一次。

**修前/修后（`琵琶曲DJ`，60fps 全时间轴 8669 帧）**

| 指标 | 修前 | 修后 |
|---|---|---|
| `visible()` avg | 1.21ms | **0.60–0.80ms** |
| `update()` avg（判定，未动） | 0.52ms | 0.29–0.32ms |
| 总计 avg | 1.29–1.75ms | **0.91–1.13ms** |
| 总计 max 单帧 | 16.7–36.6ms | **6.0–11.0ms** |
| 最差 1 秒合计 | 205–294ms | **128–132ms** |
| 同屏可见音符 | 207（峰值 744） | 207（峰值 741） |

**`big song` 同步收益**：`visible` 1.31 → **0.43–0.75ms**，`画线` 0.38 → **0.09–0.13ms**，
总 avg 1.40–1.91 → **0.60–0.99ms**，max 14.6–22.3 → **6.4–9.0ms**，最差 1s 30–34.7 → **10.1–19.9ms**。

### 1.4 顺手修掉的一个真 bug（也在渲染层）

`note.rs:281-283`：

```rust
if matches!(self.judge, JudgeStatus::Judged) { color.a *= 0.5; }   // miss
if res.time >= end_time { return; }                                 // ← 过了尾巴就不画
```

我们缺第二条，于是**判完的 hold 会一直把「头」钉在判定线上**（`PlayScreen` 里 `t >= n.time` 时
`dy0 = 0`），直到它被视口剔除为止 —— 密集合集里就是屏幕上多出一排贴线的方块。
现在 `visible()` 里 `hold && sec >= endTime()` 直接跳过。

### 1.5 量过但**不动**的两处（避免无谓改动）

| 候选 | 结论 | 证据 |
|---|---|---|
| 给 `Chart.Ev` 轨道加「增量游标」（任务书里假设的 O(事件数) 线性扫描） | **不需要**：`Ev.valueAt` 本来就是二分查找（`Chart.java:614-630`），复杂度 O(log 事件数)。`prpr` 用游标（`anim.rs:89-107`）是 O(1) 摊还，但不是「修线性扫描」 | 741 条线 / 306343 事件的 `big song`，「画所有判定线」每帧只要 **0.12ms** |
| `Chart.heightAt` 的线性扫描 | **不动**：它确实线性扫一条线的速度事件，但已按「每条线每帧一次」缓存；最坏贡献约 0.01ms/帧 | `big song` 有 11653 个速度事件、741 条线，`visible()` 里那部分开销被淹没在噪声里 |

---

## 2. B：特效谱观感 —— 「我们 vs Phira」逐行对照表

先用 `FieldProbe` 把整库 21 个 RPE 包的字段使用率量出来（数字是「有多少条线带这个字段」）：

| 字段 | 有值的谱面数 | 备注 |
|---|---|---|
| `inclineEvents` | **21 / 21** | 但 **非零值的线：0 条**（全库都是 0.0） |
| `zOrder` | 21 / 21 | **8 首**的 zOrder **不是按下标递增**：15927 / 39461 / 42810 / 48273 / 52943 / 53092 / 53239 / 78805 |
| `colorEvents` | 9 首 | 最多 286 条线（78805）、118（52943）、115（53239） |
| `textEvents` | 7 首 | 1–27 条线；其中**只有 1 条线同时带音符**（52943） |
| `paintEvents` | **0 首** | 全库没有 |
| `gifEvents` | 1 首（78805，1 条线） | |
| `attachUI` | 多首（4–7 条线） | |
| 负 alpha 事件 | 11 首 | big song 8722 条、10176 1107 条 |

### 2.1 差异表

| # | 项 | `prpr` 依据 | 修前（我们） | 修后 | 结论 |
|---|---|---|---|---|---|
| **B1** | **判定线绘制顺序 = `(zOrder, index)` 升序** | `chart.rs:46-56`：先摘掉 `attach_ui` 的线，再 `order.sort_by_key(\|it\| (lines[*it].z_index, *it))` | 按 `chart.lines` 数组下标画 | `Chart.drawOrder` 存排好序的下标，`PlayScreen` 按它画 | **已修**。8 首谱的叠线前后关系和 Phira 相反 |
| **B2** | **`colorEvents` 覆盖判定线颜色** | `line.rs:238` `let color = self.color.now_opt();` + `line.rs:249` `color.unwrap_or(res.judge_line_color)`；解析见 `rpe.rs:806-810`，值口径 `rpe.rs:122-128`（`RGBColor → rgba(r,g,b,255)`） | 忽略，一律用 FC/AP 指示色 | `Chart.Line.colorR/G/B` 三条轨道；有 `colorEvents` 就用谱面颜色，否则指示色 | **已修**。9 首 / 最多 286 条线 |
| **B3** | **判定线 alpha < 0 ⇒ 整条线（连音符）不画** | `line.rs:365-368`：`if alpha < 0.0 { if !settings.pe_alpha_extension { return; } ... }`；`pe_alpha_extension` 默认 false（`chart.rs:17-21`） | 只跳过线（`alpha <= 0.01`），**音符照画** | `Engine.visible()` 里判 `line.alphaAt() < 0` → 跳过该线所有音符；PEC 按 `floor(-a)==1` | **已修**。11 首谱有负 alpha（`big song` 8722 条事件） |
| **B4** | **text / paint / gif / 贴图线不画成横条** | `line.rs:254-290`（贴图/GIF）、`line.rs:291-298`（文字）、`line.rs:299-356`（paint 全屏绘制）；`rpe.rs:740-806` 决定 `JudgeLineKind` | `drawsAsBar()` 只看 `attachUI` 和 `Texture`，**文字线被画成一条横贯屏幕的亮条** | `Chart.Line.kind`（NORMAL/TEXT/PAINT/TEXTURE），`drawsAsBar()` 只放行 NORMAL | **已修**。5 首共 **65 条**假横条消失：10176 −27、48273 −14、53092 −9、15927 −9、42810 −6 |
| **B5** | **hold 不吃 `posControl`** | `note.rs:178-182`：`tr.x *= if matches!(kind, Hold) { 1. } else { incline_val * ctrl_obj.pos... }` | hold 也乘了 `posControl` | hold 乘 1 | **已修**（只影响渲染；`noteJudgeX` 判定路径没动） |
| **B6** | **hold 过了 `end_time` 不再画** | `note.rs:281-283` | 一直画（头钉在判定线上） | 跳过 | **已修**（见 §1.4） |
| **B7** | `scaleX` 的 0.5 系数条件 | `rpe.rs:704-717`：`texture=="line.png" && text_events 为空 && attach_ui 为空` ⇒ ×0.5 | 多排除了 `paintEvents` | 去掉 `paintEvents` 条件，与源码逐字对齐 | **已修**（本库 0 条 paint 线，无像素变化，纯对齐） |
| **B8** | `inclineEvents`（音符横向按高度收缩/张开） | `note.rs:175-182`、`line.rs:363`；`incline` 解析 `rpe.rs:734-738` | 没接 | **没接** | **不改**：全库 21 个 RPE 包的 `inclineEvents` **非零值 0 条**（全是 0.0），接了也一个像素都不变。见 §5 |
| B9 | `paintEvents` 全屏绘制 | `line.rs:299-356`（render target + `fill_circle`） | 不画 | 不画 | **不做**：要离屏 RT + 全屏 blit；本库 0 条线 |
| B10 | `textEvents` 文字 | `line.rs:291-298`（`ui.text` + `multiline`） | 不画 | 不画（且不再画成横条） | **不做**：要文字排版/字号/锚点，和 MC 的字体度量对不上 |
| B11 | `Texture/GIF` 贴图线 | `line.rs:254-290` | 不画 | 不画（且不再画成横条） | **不做**：要图集 + GIF 解码 |
| B12 | `attachUI` 的 7 个 UI 元素 | `chart.rs:46-55`（`attach_ui[element-1]`）、UI 层渲染 | 不画 | 不画 | **不做**：我们没有 UI 层（记分板是 MC 自己的画面），画不了 |
| B13 | PEC 负 alpha 扩展编码 `2 / 100..1000` | `line.rs:369-384`：`2` ⇒ `draw_below=false`；`100..1000` ⇒ `appear_before=(w-100)/10` | — | 只实现了 `floor(-a)==1`（= 不画） | **部分做**：`2` 对我们等价于默认（我们本来 `draw_below=false`）；`appear_before` 要 BPMList 在运行时的反查，本库 0 次出现，未做 |

**修前 / 修后「真的会被提交的判定线条数」（`PerfProbe` 的 `线 N(画 M)`）**

```
10176_Super Nova Project   149/142 → 149/115   (-27)
48273_Undertale            177/42  → 177/28    (-14)
53092_Cradle your moments   81/61  →  81/52    ( -9)
15927_一番の宝物              60/43  →  60/34    ( -9)
42810_R.I.P.                88/35  →  88/29    ( -6)
```

---

## 3. C：PEC 缓动丢失

### 3.1 `prpr` 到底怎么处理 `cm/cr/cf`（`pec.rs:84-120`）

```rust
fn sanitize_events(events: &mut [PECEvent], ..) {
    events.sort_by_key(|e| (e.end_time, e.start_time));      // pec.rs:85  按「结束时刻」排
    let mut last_end = f64::NEG_INFINITY;
    for e in events.iter_mut() {
        if e.start_time < last_end { e.start_time = last_end; }   // pec.rs:89-98 重叠段裁切
        last_end = e.end_time;
    }
}

fn parse_events(mut events: Vec<PECEvent>, ..) -> Result<AnimFloat> {
    sanitize_events(&mut events, ..);
    let mut kfs = Vec::new();
    for e in events {
        if e.start_time == e.end_time {
            kfs.push(Keyframe::new(e.start_time, e.end, 0));                    // 瞬移
        } else {
            if kfs.is_empty() { bail!(..); }
            kfs.push(Keyframe::new(e.start_time, kfs.last().unwrap().value, e.easing));  // ← 关键
            kfs.push(Keyframe::new(e.end_time,   e.end,            0));
        }
    }
    Ok(AnimFloat::new(kfs))
}
```

关键字是 **`kfs.last().value`**：插值段的**起点值 = 排序裁切之后的前一个关键帧的终值**。
缓动挂在起点关键帧上（`anim.rs:113-121` `kf1.tween.y(t)`），终点关键帧用 tween 0（Hold）。

### 3.2 我们修前错在哪

`PecParser.addSegment` 在**解析时**就定了起点值：

```java
double prev = track.isEmpty() ? def : track.get(track.size() - 1).v1;   // ← 文件顺序
track.add(new Seg(min(t0,t1), max(t0,t1), prev, value, tween));
```

`track` 是「文件里出现顺序」的事件列表，**不是**排序裁切后的顺序。
PEC 生成器经常把 `cp`（瞬移）和 `cm`（插值）交错导出，两种顺序不一致就中招。

`6954_-1.pec` 的 line 18 是活例子（文件顺序 vs 按 `end_time` 排序）：

```pec
cp 18 148.000 …      cp 18 162.000 …      cp 18 164.000 …
cp 18 187.000 …      cp 18 191.250 784 -200        ← 文件里在这
cm 18 164.000 170.000 1180  306 20                 ← 按 end_time 应排在 164 那段
cm 18 171.000 176.000 1724 1600 19
cm 18 191.250 194.000  784 1170  1                 ← 起点值必须是 cp@191.25 的 784，修前拿到的是上一条 cm 的 1724
```

全库统计：line 18 的 9 个 `cp` 全部在前、6 个 `cm` 全在后 ⇒ 每个 `cm` 的起点值都是错的。

### 3.3 修法

`PecParser` 换成和 RPE 一样的 keyframe 序列模型（`sanitizeKfs`）：

1. `Seg` 只记「起点时刻 / 终点时刻 / 终点值 / tween」，起点值置 `NaN` 占位；
2. 按 `(t1, t0)` 排序（`pec.rs:85`）；
3. 重叠段裁到上一段结尾（`pec.rs:89-98`）；
4. 逐段生成 `Chart.Ev(t0, t1, prev, v1, tween)`，`prev` 取**上一段（排序后）的终点值**；
   第一段之前用 `def`（move 0.5 / rotate 0 / alpha 1，对齐 `prpr` 的 `AnimFloat::default()`）。

顺带修掉同一段代码里的另外三处不一致：

| # | 项 | `prpr` 依据 | 修前 | 修后 |
|---|---|---|---|---|
| C2 | `ca` / `cf` 的 alpha 换算 | `pec.rs:154-158`：**只对 `>= 0` 的值** `/255`，负数是扩展编码 | 无条件 `/255` | `raw >= 0 ? raw/255 : raw`；`Chart.peAlphaExtension = true`（`pec.rs:380`） |
| C3 | 速度 `cv` 的高度单位 | `pec.rs:122-138` 直接积分 `value/5.85`，**不做 `HEIGHT_RATIO` 换算**（对比 `pgr.rs:105` 除了、`rpe.rs:24` 的 `SPEED_RATIO` 里除了） | 我们的 `Chart.heightAt` 统一在末尾除 `HEIGHT_RATIO` ⇒ PEC 的「音符离判定线距离」被放大 `1/0.83175 = 1.2023` 倍 | 速度事件值先乘 `HEIGHT_RATIO`，除完正好抵消 |
| C4 | `max_time` 之后高度恒定 | `pec.rs:136` 在 `max_time` 补一个 tween 0 的关键帧；`max_time = 最大事件/音符时刻 + 1`（`pec.rs:351-367`） | `endGuard = maxTime + 5` 且之后仍然线性外推 | `endGuard = maxTime + 1`，并补一条零速度段 |

### 3.4 验证：独立参考实现 vs 我们

`tools/chart/PecCompare.java` 里有一份**独立**的参考实现（`pec.rs` 逐行移植，
缓动表直接抄 `tween.rs:34-113` 的 `TWEEN_FUNCTIONS` 与 `parse.rs:75-94` 的 `RPE_TWEEN_MAP`，
**不调用** `RpeParser.tween`），用来和 `ChartParser` 的结果逐点对比。

**全判定线偏差（每 100ms 采样一次，阈值 1e-3）**

| 谱面 | 线数 | 采样点数 | 超差采样 | 最大偏差 |
|---|---|---|---|---|
| `6954_-1.pez` | 30 | 1448 × 30 | **0** | **2.274e-13**（line 1） |
| `28167_DX超性能フルメタル少女.pez` | 30 | 1141 × 30 | **0** | **1.819e-12**（line 5） |

分通道最大偏差（`6954_-1`）：Δx ≤ 2.22e-16、Δy ≤ 2.22e-16、Δrot = 0、Δalpha = 0、Δheight ≤ 2.27e-13。

**`6954_-1.pec` line 18 采样表（每 100ms；只列有意义的行）**

`x` = `cp/cm` 的 world 坐标（`v/2048*2-1`），`rot` = `cr` 的角度（度，源码取负），`α` = `ca/cf`。

| t(s) | x 参考 | x 修前 | x 修后 | Δ修前 | Δ修后 | rot 参考 | rot 修前 | rot 修后 | α 参考 | α 修前 | α 修后 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 35.7 | -0.33416 | -0.36649 | **-0.33416** | 3.2e-02 | **0** | -77.588 | 133.410 | **-77.588** | 1.0000 | 1.0000 | **1.0000** |
| 35.8 | -0.63192 | -0.64979 | **-0.63192** | 1.8e-02 | **0** | -83.139 | 33.502 | **-83.139** | 1.0000 | 1.0000 | **1.0000** |
| 35.9 | -0.77389 | -0.78487 | **-0.77389** | 1.1e-02 | **0** | -85.785 | -14.133 | **-85.785** | 0.8851 | 0.8851 | **0.8851** |
| 36.0 | -0.86736 | -0.87380 | **-0.86736** | 6.4e-03 | **0** | -87.527 | -45.494 | **-87.527** | 0.5933 | 0.5933 | **0.5933** |
| 36.1 | -0.93105 | -0.93440 | **-0.93105** | 3.3e-03 | **0** | -88.715 | -66.866 | **-88.715** | 0.3387 | 0.3387 | **0.3387** |
| 36.2 | -0.97233 | -0.97367 | **-0.97233** | 1.3e-03 | **0** | -89.484 | -80.714 | **-89.484** | 0.1446 | 0.1446 | **0.1446** |
| 41.3 | -0.59908 | **-1.00000** | -0.59908 | **4.0e-01** | **0** | 82.527 | **0.319** | 82.527 | 1.0000 | **0.0000** | 1.0000 |
| 41.4 | -0.75374 | **-1.00000** | -0.75374 | **2.5e-01** | **0** | 85.410 | **34.915** | 85.410 | 0.9372 | **0.0000** | 0.9372 |
| 41.5 | -0.85367 | **-1.00000** | -0.85367 | **1.5e-01** | **0** | 87.272 | **57.268** | 87.272 | 0.6416 | **0.0000** | 0.6416 |
| 41.6 | -0.92181 | **-1.00000** | -0.92181 | **7.8e-02** | **0** | 88.542 | **72.509** | 88.542 | 0.3789 | **0.0000** | 0.3789 |
| 41.7 | -0.96663 | **-1.00000** | -0.96663 | **3.3e-02** | **0** | 89.378 | **82.535** | 89.378 | 0.1729 | **0.0000** | 0.1729 |
| 41.8 | -0.99208 | **-1.00000** | -0.99208 | 7.9e-03 | **0** | 89.852 | 88.227 | **89.852** | 0.0427 | **0.0000** | 0.0427 |

> **line 18 最大偏差：修前 2.110e+02，修后 1.110e-16**（阈值 1e-3）。
> 「修前」那一列不是猜的：`PecCompare` 里保留了一份复刻旧逻辑的 `parseEventsLegacy`
> （起点值取文件顺序里的上一条），每次与参考实现逐点比。

**数值解释**：`t=41.3s` 附近是 `cm 18 191.250 194.000 784 1170 1`（tween 1 = 线性）。
修前它的起点值取的是文件里上一条 `cm` 的终点 `1724/2048`，而正确值是 `cp@191.25` 的 `784/2048`
⇒ 位置从 -1.0（夹到最左）一路走到正确值，`cr` 也跟着用错起点 ⇒ 角度差 82°。
「不平滑 / 缓动丢了」的体感就是这么来的：**缓动曲线本身没错，是起点错了**。

### 3.5 PEC 侧的其余对照（都是核对过、没改的）

| 项 | `prpr` | 我们 | 结论 |
|---|---|---|---|
| 排序键 | `(end_time, start_time)` | 同 | 一致 |
| 重叠裁切 | `start = last_end` | 同 | 一致 |
| `cf` 的 tween | 硬编码 `2`（线性，`pec.rs:335`） | 同 | 一致 |
| PEC tween 编号 → 曲线 | `RPE_TWEEN_MAP[t]`（`parse.rs:75-94`，`0`/`1` 都是线性） | `RpeParser.tweenId(t)`，`tweenId(0)=tweenId(1)=2` | 一致 |
| `cp` 坐标 | `end/2048*2-1`、`end/1400*2-1`（`pec.rs:152-153`） | `end/2048` → 求值时 `*2-1` | 等价 |
| `cd` 角度 | 取负（`pec.rs:317`） | 同 | 一致 |
| `cv` 单位 | `value/5.85`（`pec.rs:308`） | 同 | 一致 |
| `bp` 分段折秒 | `BpmList::new`（`core.rs:116-130`），第一段 `time = 0` | 同 | 一致（见 `PecCompare` 的独立实现） |
| `cm` 是第一个事件 | `bail!`（整谱失败） | 用默认值（move 0.5 / rot 0 / α 1） | **有意比源码宽松**：宁可能玩也不整谱失败 |
| 判定线 `scaleX` | 固定 `3.91/6`、`scaleY` 默认 1（`pec.rs:166`） | 同（之前已修） | 一致 |

---

## 4. 编译与回归

| 项 | 结果 |
|---|---|
| `gradle :1.21.1:build`（Gradle 9.8.2 + JDK 25） | **BUILD SUCCESSFUL**，0 错误 |
| `StressTest`（`fixtures/spasmodic/chart.json`，2500 音符 / 24 线 / 141.1s） | 自动播放 **分数 1000000 / 评级 PHI / P=2500 G=0 B=0 M=0 / 连击 2500/2500** ✔ |
| `StressTest` 不操作 | 0 分 / M=2500（与修前一致） |
| `StressTest` ±60ms 抖动 | 395690 / F（与修前一致） |
| `SpecTest`（fake / fadeout / hold / 速度积分 13 项） | 全部通过 ✔ |
| `EasingCheck`（30 项缓动表 + 采样点） | 全部通过 ✔ |
| `RotTest`（旋转判定线用例表） | 与 `judge.rs` 一致 ✔ |
| 曲库谱面包解析 | 40/40 成功，0 失败 |
| `PecCompare` | 2 个 PEC 包 × 30 条线，0 个超差采样 |

**判定 / 计分：一行没改。** 所有改动都在解析、`Chart` 模型、`Engine.visible()`（纯渲染筛选）、
`PlayScreen`（绘制）这四层；`Engine.update()` / `keyboardTap()` / `find()` / `judge()` / `Judgement` 完全没动。

---

## 5. 没做与原因

### 5.1 没做（附理由与证据）

| 项 | 为什么不做 |
|---|---|
| **`inclineEvents`（音符横向按高度收缩）**（`note.rs:175-182`、`rpe.rs:734-738`） | 实测全库 21 个 RPE 包的 `inclineEvents` **非零值 0 条**（`FieldProbe`：`incline 21/21，非零 0`）。接了也是乘 1.0，一个像素都不变。实现成本不高（`Engine.visible()` 的 x 上多一个乘子），但**没有可验证的对象**，改上去只是风险 |
| **`paintEvents` / `textEvents` / 贴图线 / GIF 线的实际渲染**（`line.rs:254-356`） | `paint` 要离屏 render target + 全屏 blit（本库 0 条线）；`text` 要文字排版/多行/锚点，和 MC 的字体度量对不上；贴图/GIF 要图集与 GIF 解码。**已做到「不画错的横条」**，这就是本库能拿到的那部分收益 |
| **`attachUI` 的 7 个 UI 元素**（`chart.rs:46-55`） | Phira 把它们从判定线列表里摘出来交给 UI 层（记分板、曲名、进度条…）。我们根本没有 UI 层（这些信息由 MC 自己画），画不了 |
| **PEC 负 alpha 的 `2` / `100..1000` 编码**（`line.rs:369-384`） | `2` 对我们的渲染等价于默认（我们本来 `draw_below=false`）；`100..1000` 要 `appear_before`，需要运行时的 `BpmList` 反查（`bpm_list.beat()`），而我们的 `Engine` 只有秒。本库出现次数 0（`28167` 的 160 条负 alpha 全是 `-1`） |
| **音符绘制数量上限** | Phira 没有（`line.rs:396-428` 只靠视口 `break` 剔）。加上去就是「改了观感」，与「不要为了像去碰」相悖。`琵琶曲DJ` 同屏 207–744 个音符是谱面本身的样子 |
| **`Chart.heightAt` 改成前缀和 + 二分** | 实测不是瓶颈（缓存后约 0.01ms/帧，见 §1.5），改了反而要重新验证 RPE 速度积分语义 |
| **`SongEntry.chartJson()` 换成 byte[]/Reader 两遍扫** | 能把 `big song` 的峰值堆再压约 285MB（那块 49M 字符的 `String`）。属于接口级改动（`ChartParser` 要多一个 `parse(byte[])`，`RpeParser` 要多一个双 `InputStreamReader` 入口，PEC 判断要改成「偷看 4KB + 重开」），风险/收益比不如已做的两刀，留作下一步 |
| **`visible()` 的「未来音符」上界（`break`）** | 前缀游标只能切掉「已经过去的」音符；`琵琶曲DJ` 剩余成本主要来自「把 3 万多个未来音符逐个 `continue`」。`prpr` 能 `break` 是因为它按 `(above, speed, height*speed)` 重排了音符（`line.rs:94-105`），并在 `line.rs:406/423` 靠这个单调性提前跳出。我们的 `states` 必须按时间排序（判定通路依赖），要复刻就得再维护一套「只给渲染用的第二索引」——本次没做，属于下一步 |

### 5.2 已知的、有意保留的偏差

| 偏差 | 我们的行为 | `prpr` 的行为 | 理由 |
|---|---|---|---|
| PEC 首个事件就是插值段 | 用默认值起步 | `bail!`（整谱加载失败） | 宁可能玩；本库 0 次触发 |
| 父判定线成环 | 摘掉父线继续 | 直接报错（`rpe.rs:915-931`） | 同类：宁可能玩 |
| 判定 Drag/Flick 的输入通路 | 见 `Engine` 里的「有意偏离 judge.rs:742」注释 | 源码用 `key_down_count` | **用户明确要求过的手感**，与本次三问题无关，一行未动 |

---

## 6. 复现命令

```powershell
# 1) 编译 headless 工具（javac + gson/jlayer/slf4j）
pwsh -File tools\chart\build.ps1

$cp = "out\dev;lib\gson-2.11.0.jar;lib\jlayer-1.0.1.4.jar;<slf4j-api.jar>"

# 2) 全库：解析耗时 / 峰值堆 / 首帧 / 每帧（--full 用 60fps 扫全时间轴）
java -cp $cp PerfProbe "D:\.minecraft\versions\1.21.1-fabric-0.20.0\config\phimc\songs" --full

# 3) 解析各阶段的峰值堆拆分（单首）
java -cp $cp ParseSplit "big song"

# 4) 谱面字段使用率（给差异表供数）
java -cp $cp FieldProbe

# 5) PEC 缓动：独立参考实现 vs 我们（含修前口径）
java -cp $cp PecCompare "D:\.minecraft\versions\1.21.1-fabric-0.20.0\config\phimc\songs\6954_-1.pez" 18

# 6) 回归
java -cp $cp dev.dsh.phimc.core.StressTest fixtures\spasmodic\chart.json
```
