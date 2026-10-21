# attachUI 回退 + 贴图线 UV 写死（应急修复）

> 参考实现 `C:\dsh-work\phira-src\prpr\src\`（Phira v0.8.2）。
> 目标：先让画面能用。判定 / 计分一行未改。

---

## 1. `attachUI` 已回退（`ENABLE_ATTACH_UI = false`）

`PlayScreen.java:170`

```java
private static final boolean ENABLE_ATTACH_UI = false;
```

关掉之后分数 / 连击 / 曲名 / 难度回到本 mod 原本的固定 HUD 位置，暂停仍是 Esc（不是谱面里的
「暂停线」），进度条不再画。**和接 attachUI 之前逐像素一致**（`pushUi` 在 `UiElem.none()` 时
只 push/pop 一层空变换，颜色回落到原来的字面量）。

实现本身保留在 `uiElem()` / `pushUi()` / `uiColor()` 里，重开只需要把这一个布尔值改成 `true`，
但**坐标口径要先解决下面的问题**。

### 1.1 源码到底怎么换算坐标（回答「是不是漏了 [0,1]→屏幕 或 aspect」）

`chart.rs:81-92`：

```rust
let scale_point = scale_point.unwrap_or(rotation_point);
if let Some(id) = self.attach_ui[element as usize - 1] {
    let line = &lines[id];
    let mut tr = line.fetch_pos(res, lines);   // ① 世界坐标，含父线合成
    tr.y = -tr.y;                              // ② 翻 y（UI 空间 y 与屏幕同向）
    let color = lines[id].color.now_opt().unwrap_or(WHITE);
    let scale = obj.now_scale(Vector::new(scale_point.0, scale_point.1));   // ③ 以 scale_point 为支点
    let ro = Object::new_rotation_wrt_point(Rotation2::new(-rot.to_radians()), Vector::new(rotation_point.0, rotation_point.1));
    ui.with(Matrix::new_translation(&tr) * ro * scale, |ui| ui.alpha(obj.now_alpha().max(0.), |ui| f(ui, color)))
}
```

而 `ui.rs` 的 `with` 是**相乘叠加**，不是替换：

```rust
pub fn with<R>(&mut self, transform: Matrix, f: impl FnOnce(&mut Self) -> R) -> R {
    let old = self.transform;
    self.transform = old * transform;     // ← old * transform
    let res = f(self);
    self.transform = old;
    res
}
```

**结论（三个要点，都不是「漏了换算」）**：

1. **`tr` 的换算是「世界坐标 → UI 空间」**。`fetch_pos` 给的是 `x∈[-1,1]`、`y∈[-1/aspect, 1/aspect]`
   的世界坐标（`now_translation` 里已经除过 aspect），`tr.y = -tr.y` 再翻成 y 向下的 UI 空间。
   **这一步我们的 `xf.cx/cy`（`(x+1)/2*w`、`(1-y)/2*h`）是等价的** —— 判定线和音符用的就是它，
   画得对。所以「没做 [0,1]→屏幕」这个猜测不成立。
2. **`T(tr)` 是「加在默认位置上」，不是「搬到 tr 位置」**。`ui.with` 把矩阵乘在根变换上，
   元素本身的 `pos` 仍是它自己的默认位置 ⇒ 最终位置 = **默认位置 + 线位置**（再乘上 ro/scale）。
   我们 `pushUi` 的合成顺序和它一致，但这意味着「线的位置」只是**位移增量**，
   线的位置一动画、HUD 就跟着飘 —— 这正是「乱飞」的来源之一。
3. **旋转 / 缩放的支点单位不同**。prpr 的 `rotation_point` / `scale_point` 是**它自己的 UI 布局常量**
   （例如 score 的 `(score_right, score_top)`、bar 的 `(-1., top+height/2)`），而我把支点取成了
   **本 mod 的 HUD 像素位置**。支点只影响「绕哪一点转/缩放」，但 attachUI 线若带
   `scaleXEvents/scaleYEvents`（这些线常常是给横条用的，倍数可以到几十上百），
   `ms.scale(sx, sy)` 会把 HUD 文字直接放大到飞出去 —— 这是「乱飞」的第二个来源。

### 1.2 重开前要先做的三件事

1. 把 7 个元素的 `rotation_point` / `scale_point` 换成 phira 的那组**UI 空间常量**（不是像素）；
2. HUD 元素**不套线的 scale**（只吃 translate + alpha + color），先在实机确认能跟线走；
3. 拿一首谱手算一次 `T(tr)·ro·scale` 的最终落点，和实机截图对上，再加回 rotate/scale。

---

## 2. 贴图线：UV 显式写死成 0→1

### 2.1 新写法（`RenderCompat.drawTextureFull`，1.21.1 实际编译进去的那一条）

```java
// 归一化 UV 重载：u=v=0，texW/texH 取目标宽高 ⇒ UV 恒为 0→1
ctx.drawTexture(id, x, y, 0f, 0f, w, h, w, h);
```

`1.21.1` 的这个重载是 `drawTexture(Identifier, int x, int y, float u, float v, int w, int h, int texW, int texH)`，
`javap -c` 显示它转调 11 参版本时传 `(id, x, y, w, h, u, v, uW=w, vH=h, texW, texH)`，
而 11 参版本内部算的是：

```
u1 = (u + 0) / texW      u2 = (u + uW) / texW
v1 = (v + 0) / texH      v2 = (v + vH) / texH
```

我们传 `u=v=0`、`uW=texW=w`、`vH=texH=h` ⇒ **u1=0, u2=w/w=1, v1=0, v2=h/h=1**。
关键点：**这里出现的 `w/h` 是「目标宽高」，和贴图的真实像素数完全无关**。
所以不管图是 100×100 还是 6000×4000，采样范围永远整张贴图 ——
「只采到一个纹素 → 整块纯色」这条路被从构造上堵死了（原来是靠参数巧合）。

1.21.2+ 的重载签名多了 RenderLayer / RenderPipeline 参数，那两支仍走原参数化路径（行为不变），
已在代码里注明。

### 2.2 同一版顺手修掉的几何 bug

`quadH` 原来用 `texH * sy * h * 0.5`。1 世界单位在**两个轴上都等于半个屏宽**
（相机把 y 乘了 `aspect = w/h`，所以 `aspect * h/2 == w/2`），16:9 下所有贴图线
**高度被压成 0.5625 倍**、垂直范围也跟着错。已改成 `w * 0.5`。

### 2.3 新增日志（每首谱各打一次）

```
[PhiMC] 画贴图线 #1 heart.png id=phimc:linetex/<hash>_<n> 贴图320×320 → 455×455px @(960, 540) α=1.000 rgb=(1.00,1.00,1.00) rot=0.0°
[PhiMC] 贴图线诊断: 共 129 条 imageLine，首帧取不到贴图 0 条（attachUI 2 条 / kind=TEXTURE 1 条）
[PhiMC] 判定线贴图 heart.png → 320×320，1098 字节，id=phimc:linetex/...
```

- 第一行：**实际画出来的** `quadW×quadH`、屏幕位置、`alpha`、颜色、角度 —— 正是这次要看的数据；
- 第二行：本首谱的贴图线总数与「取不到贴图」数量（区分「没加载」和「加载了但画错」）；
- 第三行：每张贴图解码后的真实尺寸。

`alpha` 也在第一行里：若它被算成 0 或极小值，`setShaderColor` 会让整块变成纯色底 ——
这行日志能直接看出来（这次代码路径里 `alpha <= 0.01` 会提前 `return`，不会再画半透明块）。

---

## 3. 编译与回归

| 项 | 结果 |
|---|---|
| `gradle :1.21.1:build` | **BUILD SUCCESSFUL**，0 错误 |
| `StressTest` | **1000000 / PHI / P=2500 G=0 B=0 M=0 / 连击 2500** ✔ |
| `SpecTest` | 全部通过 ✔ |
| `PecCompare` | 最大偏差 **2.274e-13** ✔ |
| jar | 已覆盖 `mods\phimc-1.21.1.jar`（659 763 字节，sha256 `8C1B2D33…17DA1ACE`），`mod/dist` 同步 |

**判定 / 计分一行未改**：改动只在 `PlayScreen`（HUD 开关 + 贴图线绘制）、`RenderCompat`（多一条绘制入口）、
`LineTextures`（贴图注册路径 + 日志）。
