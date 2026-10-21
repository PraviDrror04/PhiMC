# 文字判定线渲染（`JudgeLineKind::Text`）——实现报告

> 参考实现：`C:\dsh-work\phira-src\prpr\src\`（Phira **v0.8.2**，GPL-3.0）。
> 下文 `line.rs:NN` / `rpe.rs:NN` / `tween.rs:NN` / `ui/text.rs:NN` 都是 `prpr/src/` 下的行号。
>
> 量具（headless）：`tools/chart/TextProbe.java`（每首的文字线数量/内容/位置）、
> `tools/chart/TextTweenCheck.java`（`String` 缓动逐条对照 + 真实谱面打字机采样）。
> 原始输出：`C:\dsh-work\text-before.txt`、`C:\dsh-work\text-tween.txt`。

---

## 0. 一页结论

以前「有 textEvents 的线」我们只做了两件事：**不画成横条**（消除假亮条）、让 `hasText` 参与
`scaleX` 的 0.5 系数判定。**文字本身从来没有画过** —— `Chart.Line` 里连文本字段都没有。

现在按 `line.rs:291-298` 补上了：文字画在**判定线原点**、**居中**、字号 **em = 4% 屏宽**、
颜色**白**（无 `colorEvents` 时）、alpha 跟判定线、**跟着线旋转和缩放**，并实现了
`Tweenable for String`（`tween.rs:582-631`）的**打字机效果**。

| 项 | 修前 | 修后 |
|---|---|---|
| 全库带文字的谱面 | 9 首 / 87 条文字线（能解析出「有 textEvents」，但没有内容） | 同样 9 首 / 87 条，**每条都能取到当前时刻的文字** |
| 文字内容 | 无（`Chart.Line` 没有字段） | 完整解析成 `Chart.TextEv` 关键帧序列，按 `tween.rs` 求值 |
| 打字机动画 | 无 | 有（`48273_Undertale` 实测「很」→「很久以前，地球由两个种族统治着：人类和怪物」逐字长出来，见 §3） |
| 绘制 | 不画 | 画（27 条文字线的 `10176_Super Nova Project` 是曲库里最多的） |

`gradle :1.21.1:build` **BUILD SUCCESSFUL**；`StressTest` 仍 **1000000 / PHI / 0 Miss**。

---

## 1. 读源码：文字到底画在哪、怎么取参数

### 1.1 解析侧：`rpe.rs:746-748`

```rust
} else if let Some(extended) = rpe.extended.as_ref() {
    if let Some(events) = extended.text_events.as_ref() {
        JudgeLineKind::Text(parse_events(r, events, Some(String::new()), bezier_map)?)
    } else { JudgeLineKind::Normal }
}
```

* `textEvents` 的类型是 `Option<Vec<RPEEvent<String>>>`（`rpe.rs:134`）——
  **值是字符串**，`start`/`end` 就是文本本身；
* 它走的是**和 moveX/rotate/alpha 完全同一个** `parse_events`，也就是
  「关键帧序列 + 段内 `Tweenable::tween`」；
* JSON 里还有 `font` 键（实测 `"font": "cmdysj"`），但 `RPEEvent<String>` **没有这个字段**，
  serde 直接忽略 ⇒ **Phira 不使用谱面指定的字体**；
* **没有**位置 / 锚点 / 字号 / 颜色字段。

### 1.2 绘制侧：`line.rs:291-298`

```rust
JudgeLineKind::Text(anim) => {
    let mut color = color.unwrap_or(WHITE);
    color.a = alpha.max(0.0);
    let now = anim.now();
    res.apply_model_of(&Matrix::identity().append_nonuniform_scaling(&Vector::new(1., -1.)), |_| {
        ui.text(&now).pos(0., 0.).anchor(0.5, 0.5).size(1.).color(color).multiline().draw();
    });
}
```

逐项对应到我们的实现：

| 参数 | Phira 依据 | 含义 | 我们的做法 |
|---|---|---|---|
| **画在哪** | `line.rs:296` `pos(0., 0.)` | 判定线<b>原点</b>（在 `now_transform` 里，所以已经是线的世界坐标） | `ms.translate(xf.cx[slot], xf.cy[slot])` |
| **锚点** | `line.rs:296` `anchor(0.5, 0.5)` | 文本块<b>水平+垂直居中</b> | 每行 `-width/2`，整块 `-总高/2` |
| **字号** | `line.rs:296` `size(1.)` + `ui/text.rs:95-97` `get_scale = 0.04 * size * 视口宽` | em 高度 = **0.04 × 屏宽**像素 | `TEXT_EM_FRAC = 0.04`，`scale = 0.04*w/fontHeight` |
| **颜色** | `line.rs:292` `color.unwrap_or(WHITE)` | 有 `colorEvents` 用谱面色，**否则纯白**（注意：普通判定线用的才是 `judge_line_color`，`line.rs:249`） | `hasColor() ? rgbOf(...) : 0xFFFFFFFF` |
| **alpha** | `line.rs:237,293` `object.alpha * res.alpha` → `color.a` | 跟判定线 alpha；负 alpha 时整条线直接 `return`（`line.rs:365-368`） | `alpha = line.alphaAt(t)`；`<= 0` 跳过 |
| **旋转** | `line.rs:240` `with_model(now_transform)` | 文字**跟着判定线一起转** | `ms.rotate(xf.rotRad[slot])` |
| **缩放** | `line.rs:246` `with_model(now_scale)` | 文字**跟着 `scaleX/scaleY` 一起缩放** | `ms.scale(scale*sx, scale*sy)` |
| **多行** | `line.rs:296` `.multiline()` | 按 `\n` 切行 | `s.split("\n", -1)` 逐行居中 |
| **y 翻转矩阵** | `line.rs:295` `append_nonuniform_scaling(1,-1)` | 给 Phira 的 **y 轴向上**坐标系补正 | **不做**：我们的屏幕系 y 向下且角度已取反（和音符/贴图线同一套），正着画就是正的；再翻一次字就倒了 |

> `apply_model_of` 是 `push_model_matrix`（`resource.rs:645-649`），也就是**叠在当前模型栈之上**——
> 所以文字确实在 `now_transform ∘ now_scale` 里面，旋转/缩放都吃。

### 1.3 文本缓动：`tween.rs:582-631`（打字机）

```rust
impl Tweenable for String {
    fn tween(x: &Self, y: &Self, t: f32) -> Self {
        if x.contains("%P%") && y.contains("%P%") { /* 数字插值，整数 %.0f / 否则 %.3f */ }
        else if x.is_empty() && y.is_empty() { "" }
        else if y.is_empty() { Self::tween(y, &x, 1.-t) }              // 逐字消失
        else if x.is_empty() { chars[..round(t*len)] }                  // 逐字出现
        else if y.starts_with(x) { x + y[x_len..][..floor((y_len-x_len)*t)] }
        else if x.starts_with(y) { y + x[y_len..][..round((x_len-y_len)*(1-t))] }
        else if x.contains("%P%") { x.replace("%P%","") }
        else { x }
    }
}
```

要点：**没有公共前缀时不会「打补丁」，整段保持 `x`，到段末直接跳成 `y`**。
所以很多谱面的文字是「整块切换」而不是打字机——这跟我们实现的行为一致。

---

## 2. 改动清单（文件:行 ← Phira 依据）

| 文件 | 改动 | Phira 依据 |
|---|---|---|
| `core/Chart.java:275-317` | `Line.text`（`List<TextEv>`）+ `Line.textAt(sec)`（二分找段、段内走缓动） | `rpe.rs:746-748`、`anim.rs:89-121` |
| `core/Chart.java:735-756` | `TextEv`（t0/t1/v0/v1/tween），与 `Ev` 同构 | `anim.rs:5-19` `Keyframe<T>` |
| `core/Chart.java:758-836` | `Chart.tweenText(x, y, t)`：`impl Tweenable for String` 的逐条移植（按 **Unicode 码点** 计数） | `tween.rs:582-631` |
| `core/RpeParser.java:347-349` | 解析 `extended.textEvents` 并传进 `Chart.Line` | `rpe.rs:746-748` |
| `core/RpeParser.java:359-410` | `textTrack()`：排序 → 空档保持 → 段内挂缓动（和其它轨道同一套 `segments()` 模型） | `rpe.rs:306-330` |
| `mod/.../client/PlayScreen.java:48-77` | `TEXT_EM_FRAC = 0.04` + `Text` 对象缓存（LRU 2048，打字机中间串也缓存） | `ui/text.rs:95-97` |
| `mod/.../client/PlayScreen.java:262-266` | `drawOrder` 循环里分派 `KIND_TEXT` → `drawTextLine` | `chart.rs:56`（z 序） |
| `mod/.../client/PlayScreen.java:1109-1180` | `drawTextLine()`：位置/锚点/字号/颜色/alpha/旋转/缩放/多行 | `line.rs:240,246,291-298` |
| `mod/.../client/LineTextures.java:150-158` | 贴图线改用**双线性过滤**（`newTextureLinear`） | `line.rs:784-803`（macroquad 默认 GL_LINEAR + `with_mipmap()`） |

`Chart.Line` 的完整构造多了一个 `List<TextEv> text` 参数；短的构造链默认 `List.of()`（PGR / PEC 没有文字轨道）。

---

## 3. 验收

### 3.1 每首「有多少条 text 线、内容是什么、落在哪」（`TextProbe`）

位置 = 判定线原点在那一刻的屏幕比例（`line.rs:295-296` 的 `pos(0,0)`），
所以下表 `x/y` 就是文字中心会落在哪里（0..1，可以超出屏幕）。

| 谱面 | 文字线 | 代表内容 | x / y 采样 | 角度 |
|---|---|---|---|---|
| `10176_Super Nova Project` | **27** / 149 | `■` `●`（装饰）＋ `绿:我不会写表演(`、`喵卡:你又在卖若！`（对话） | 对话在 (0.099, 0.067) / (0.107, 0.033)（左下）；装饰 0.5~0.83 分布在 y≈0.92~0.96 | 0° |
| `14143_「僕は…」` | 10 / 60 | 每行一个假名：`君` `が` `僕` `に` `見` `せ` `て` `く` `れ` `た` | x 0.426→1.033 逐个排开，y=0.611/0.5 → **一条横排歌词** | 0° |
| `15927_一番の宝物` | 9 / 60 | `ひ` `と` `り` `で` `も` `ゆ` `く` `よ` | x=0.5，y 0.500→−0.122 **竖排** | 0° |
| `19508_Ours` | 9 / 62 | `長い` `夢を` `何もない退屈` `Because  this  is  Ours` | (0.5, 0.5) / (0.722, 0.278) 等 | 0° |
| `42810_R.I.P.` | 6 / 88 | `00:23`（倒计时）、`COUNTDOWN`、`雑踏の中で孤独に揺れる`、`在人群中孤独地摇摆` | (0.5, 0.95) / (0.5, 0.90) / (0.5, 0.52) / (0.5, 0.46) | 0° |
| `48273_Undertale` | **14** / 177 | 中文旁白 + 英文原文：`很久以前，地球由两个种族统治` / `Long ago, two races ruled over earth: H` … | 中文 y≈0.72、英文 y≈0.67（上下两行字幕），第二段在 y 0.17~0.34 | 0° |
| `52943_Bismuth` | 1 / 121 | `10`（倒计时）—— 但这条线 `Texture != line.png`，`kind` 是 **TEX**（`rpe.rs:755-792` 决定），Phira 也画图不画字 | (0.504, 0.960) | −3° |
| `53092_Cradle your moments` | 10 / 81 | `蝉` `の` `鳴` `く` `時` `を` `待` `ち` ＋ `待蝉鸣之时` | 全部 (0.5, 0.5)（重叠在屏幕中心） | 0° |
| `53239_IF = Infinity` | **1** / 116 | **空串** `""`（0.00~0.30s，1 个事件） | (0.5, 0.5) | 0° |
| **合计** | **87 条**（9 首） | | | |

### 3.2 `tweenText` 与 `tween.rs:582-631` 逐条对照（`TextTweenCheck`）

期望值是照 Rust 源码**手算**的，不是拿我们的实现反推：

| 用例 | t=0 | t=0.5 | t=1 | 结果 |
|---|---|---|---|---|
| `""` → `"abc"` | `""` | `"ab"` | `"abc"` | ✔ |
| `"abc"` → `""` | `"abc"` | `"ab"` | `""` | ✔ |
| `"a"` → `"abc"` | `"a"` | `"ab"` | `"abc"` | ✔ |
| `"abc"` → `"a"` | `"abc"` | `"ab"` | `"a"` | ✔ |
| `"君"` → `"が"`（无公共前缀） | `"君"` | `"君"` | `"君"` | ✔ |
| `"0%P%"` → `"100%P%"` | `"0"` | `"50"` | `"100"` | ✔ |
| `"1.5%P%"` → `"2.5%P%"` | `"1.5"` | `"2.000"` | `"2.5"` | ✔ |
| `""` → `""` | `""` | `""` | `""` | ✔ |

**全部通过。**

> 注：`%P%` 的端点是「去掉标记的原串」（`t=0` 得 `"1.5"` 而不是 `"1.500"`），中间才是 `%.3f`。
> 全库 9 首谱**没有任何一处用 `%P%`**（用脚本扫过 231+182+114+78+38+224+92+104+1 条文本），
> 这一支纯属为兼容而实现。

### 3.3 打字机实测（`48273_Undertale` 线 25，`[5.30s → 6.77s]`）

```
t=5.30  「很」
t=5.45  「很久以」
t=5.59  「很久以前，」
t=5.74  「很久以前，地球」
t=5.89  「很久以前，地球由两」
t=6.04  「很久以前，地球由两个种」
t=6.18  「很久以前，地球由两个种族统」
t=6.33  「很久以前，地球由两个种族统治着」
t=6.48  「很久以前，地球由两个种族统治着：人」
t=6.62  「很久以前，地球由两个种族统治着：人类和」
t=6.77  「很久以前，地球由两个种族统治着：人类和怪物」
```

同时这条线的 y 从 0.611 漂到 0.737（谱面用 `moveYEvents` 让字幕上升），说明
「文字跟着判定线走」也接对了。

---

## 4. `53239_IF = Infinity.pez` 的「贴图异常」和文字线无关

结论：**无关**。

* 它的 `textEvents` 只有 **1 条线 / 1 个事件 / 内容为空串**（见 §3.1 最后一行），
  0.00~0.30s 就结束，画出来的像素数为零；
* 它的贴图线是 **53 条 / 6 个图名**，`SongEntry.asset()` 命中 **6/6**，没有缺图：

```
Texture    事件  scaleX@0    手算默认   画面尺寸(@1920)
光线.png    有   0.001235   0.001481   3556 × 742
```

最大的一张 `光线.png` 被画成 **3556 × 742 px**（约 1.85 个屏宽）——这是谱面本意
（「光线」本来就该横穿/超出屏幕），但也是最可能被看成「贴图异常」的地方，原因是我们这一侧的两个
实现细节：

1. **采样过滤**：修前用点采样（NEAREST），3000px 的大图缩放后边缘会成块、看起来发亮/毛刺。
   `prpr` 那边是 macroquad 的 GL_LINEAR（`line.rs:784-803` 还带 `with_mipmap()`）。
   **本次已改成双线性**（`LineTextures` → `RenderCompat.newTextureLinear`）；mipmap 在 MC 的
   动态贴图上开不了，记为差异。
2. **与音符的层叠顺序**（上一轮已记录）：`prpr` 是「一条线画完自己的图 + 自己的音符，再下一条线」，
   我们是「先画所有线、再画所有音符」⇒ 大贴图永远不会盖住音符。刻意保留（可玩性更好）。

如果实机看 `IF = Infinity` 仍觉得贴图不对，最可能的两点是：**朝向**（我从 `line.rs` 的 `flip_y`
推导的，没法 headless 验像素）和 **mipmap**（见上）。

---

## 5. 编译与回归

| 项 | 结果 |
|---|---|
| `gradle :1.21.1:build`（Gradle 9.8.2 + JDK 25） | **BUILD SUCCESSFUL**，0 错误 |
| **全部 19 个版本**（`:1.20 … :1.21.11`） | **BUILD SUCCESSFUL**（`ctx.drawText(TextRenderer, Text, …)` 这个重载在 1.20 ~ 1.21.11 都存在；分批各批 4 个版本跑，逐批通过） |
| `StressTest`（2500 音符 / 24 线 / 141.1s） | 自动播放 **1000000 / PHI / P=2500 G=0 B=0 M=0 / 连击 2500** ✔；不操作 0 分；±60ms 抖动 395690（三条与修前逐字一致） |
| `SpecTest` | 全部通过 ✔ |
| `PecCompare`（PEC 缓动回归） | 最大偏差 **2.274e-13**，0 个超 1e-3 ✔ |
| `TextTweenCheck` | 8 项全过 ✔ |
| jar | 已覆盖 `D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`（653 822 字节，sha256 `7B597877…47E22D6`，同时更新 `mod/dist`）；逐类校验过 jar 内确实含 `Chart$TextEv` / `textAt` / `textTrack` / `drawTextLine` / `TEXT_EM_FRAC` / `textOf` / `newTextureLinear` |

> 注意：全 19 版本**一次性** `gradle build` 会在 **`remapJar` 阶段 OOM**（`java.lang.OutOfMemoryError: Java heap space`
> 出在 Gradle 守护进程的 Mercury 反混淆器里，不是编译错误；`compileJava` 都过了）。
> 根因是 `C:\dsh-gradle-home\gradle.properties` 的 `org.gradle.jvmargs=-Xmx2G`，
> 19 个版本连续跑会把守护进程堆吃光。分批跑（每批 4 个）没有这个问题。

**判定 / 计分一行没改**：改动只在 `Chart`（文本模型）、`RpeParser`（解析）、
`PlayScreen`（多画一类线）、`LineTextures`（采样过滤）。`Engine` / `Judgement` 完全没动。

---

## 6. 没做与原因

| 项 | 为什么 |
|---|---|
| **字体** | Phira 用 glyph_brush + 自己的字体集（谱面里的 `font` 键它也不解析）。MC 只有位图字体，我们按「em = 4% 屏宽」把 MC 字体放大到 8.5 倍左右（1080p），**字形会比 Phira 明显更粗糙**——这是没有 TTF 管线导致的，不是尺寸算错 |
| **mipmap** | `line.rs:789` 的 `with_mipmap()` 让 Phira 的大贴图缩小时更干净；MC 的 `NativeImageBackedTexture` 不能给动态贴图生成 mipmap，只做到双线性 |
| **`paintEvents` 全屏绘制** | 要离屏 render target + 全屏 blit（`line.rs:299-356`）。全库 **0 条线** |
| **`gifEvents`** | `enchanted love` 1 条线；`CoverCodec` 只吃 PNG/JPEG/WebP，那条线现在不画（不是画错） |
| **`attachUI` 的 7 个 UI 元素** | `chart.rs:46-55` 把它们从判定线列表摘出来交给 UI 层；我们没有 UI 层 |
| **文字与音符的层叠** | 同 §4 第 2 点：`prpr` 按线交错画「图/字 + 音符」，我们先把所有线画完再画音符 ⇒ 音符永远在最上层。刻意保留 |
| **`52943_Bismuth` 那条 `10`** | 它的 `Texture != line.png`，`rpe.rs:755-792` 会把它判成 `Texture` 而不是 `Text`，Phira 同样画图不画字。我们是**照源码行为**，不是漏做 |
| **空文本**（`RPE = Infinity` 那条、`R.A.P.` 两条 `""`） | `textAt` 返回空串，直接不提交绘制（画空串本来就是零像素） |
