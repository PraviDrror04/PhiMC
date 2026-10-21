# 三连修：打击音响度 / 底部署名位置 / Hold 提前消失

> 判定 / 计分一行未改。改动只涉及音频增益、HUD 坐标、`Engine.visible()` 的**渲染**剔除条件。

---

## 1. 打击音压过音乐

### 改了什么

| 位置 | 改前 | 改后 |
|---|---|---|
| `survival/Profile.java:93`（字段默认）+ `:380`（`reset()`） | `hitSoundVolume = 1.0` | **`0.35`** |
| `core/audio/HitSoundClips.java:147-186` | `MASTER_GAIN = 20*log10(volume)` | 新增 **`HIT_TRIM = 0.5`**，`effectiveGain(v) = v * HIT_TRIM`，再取 dB |
| `client/HitSoundPlayer.java:281`（内置 ogg）/ `:327`（原版音效） | `volume` 直接下发 | 也乘 `HitSoundClips.effectiveGain(volume)`，三条通路一致 |

### 相对响度目标与实测数值

把音乐当作 **0 dB 参考**（音乐走 `SourceDataLine`，不经任何衰减）：

| 旋钮 `hitSoundVolume` | 实际线性增益 | dB（相对音乐） | 说明 |
|---|---|---|---|
| 1.0（改前的默认） | 1.000 | **0.0 dB** | 和音乐同响度 ⇒ 这就是「打击音压过音乐」 |
| 1.0（改后拉满） | 0.500 | **−6.0 dB** | 即使拉满也低于音乐 |
| 0.5 | 0.250 | **−12.0 dB** | |
| **0.35（新默认）** | **0.175** | **−15.1 dB** | 目标区间 12~15 dB 的下沿 |
| 0.25 | 0.125 | −18.1 dB | |

`20*log10` 的计算都在 `HitSoundClips.effectiveGain` 里，headless 可直接算（上表即该函数的输出），
听感留给实机。之所以除了降默认值还额外让 6 dB：打击音是**瞬态**、音乐是**持续**信号，
同为 0 dB 峰值时瞬态在听感上仍然更响。

**平台差异**：`prpr` 的打击音和音乐过同一套 mixer / 同一条音量总线，素材本身录得也偏低；
我们是 **JavaSound 独立通路**（`MASTER_GAIN` 直接对设备满刻度），没有任何总线衰减，
所以必须在代码里让出这段余量 —— 这不是照搬源码常数，是为了对齐听感。

### 没做
「资源包通路跟 MC 音量滑条」这次**没做**（按你的要求优先降音量）。它需要把
`SoundCategory` 的音量乘进 `effectiveGain`，但音乐本身也不走 MC 音量 ⇒ 乘了之后
「打击音/音乐」的相对关系会随 MC 主音量漂移，得先给音乐也接一条统一总线才有意义。

---

## 2. 标题 / 难度位置

`PlayScreen.java` 常数 `BOTTOM_LABEL_PAD`（`:173`，= **文字底边距屏幕底边的留白**），
`bottomLabelY = h - BOTTOM_LABEL_PAD - 9`（`:538`，5 处绘制点全部改用它）。

| 分辨率 | 最早（留白 25） | 留白 36（太高） | 留白 29 | **本版（留白 4，贴底边）** |
|---|---|---|---|---|
| 1280×720 | y=686，686..695 | y=675，675..684 | y=682，682..691 | **y=707，707..716** |
| 854×480 | y=446，446..455 | y=435，435..444 | y=442，442..451 | **y=467，467..476** |

* 4 px 是用户明确要求的「贴着底边」；用「离底边固定像素」而不是比例，
  所以两种窗口大小留白一致）。
* 底部只有这一行文字（`PlayScreen` 里没有别的底部元素），x 不变：
  左 = `mgBar`，右 = `w - mgBar - 难度文字宽`，两者不会重叠。
* 想再调只改 `BOTTOM_LABEL_PAD` 一个常数（**越大越靠上**）。

---

## 3. Hold 提前消失

### 3.1 根因：视口剔除只判「端点」

`Engine.visible()` 照 `line.rs:406/423` 做剔除。身体在本地坐标里是 **`[头, 身体末端]` 这条线段**；
如果只判两个端点是否落在视口内，那么「hold 比一屏还长、头已飞出上沿、末端还在屏幕下方很远」时，
**两端都在视口外、线段却横穿屏幕** ⇒ 整根被剔掉 ⇒ 画面「闪一下没了、下一帧又出来」。

### 3.2 身体长度的语义（先确认）

`prpr/src/core/note.rs:284-289`：

```
top    = (end_height - line_height) * spd / aspect    ← 身体末端
bottom = (height     - line_height) * spd / aspect    ← 头
dest_size.y = top - bottom
```

`end_height` = `rpe.rs:542-547` 里 `height.set_time(end_time)` 的值，**就是尾判定时刻的高度**。
所以源码的身体是「从头一路连到 endTime 的高度」，随时间线性收缩，**不是**按剩余时长画一段定长。

我们的 `tailPx` 与之同源：
`tailLen = (floorPositionAt(time+hold) - floorPositionAt(t)) * 0.5 * h - |dy0|` —— 也是**到 endTime**。

### 3.3 修法（`Engine.java:951-971`）

```java
if (hold && !heldHold) {
    double endH = line.floorPositionAt(s.note.endTime());          // prpr 的 end_height
    double tailLocalY = (endH - floorNow) * spd * cY / aspect;
    if (!s.note.above) tailLocalY = -tailLocalY;
    double lo = Math.min(localY, tailLocalY), hi = Math.max(localY, tailLocalY);
    if (hi < boundMin[slot] || lo > boundMax[slot]) continue;      // 整段在同一侧才剔
} else if (localY > boundMax[slot] || localY < boundMin[slot]) continue;
```

按住时（`heldHold`）头钉在判定线上，仍走单点判定。**只改「画不画」，`tailMiss` / 计分没动。**

### 3.4 逐帧表（`tools/chart/HoldProbe`，1920×1080，视口本地范围 [−0.563, 0.563]）

| t | 在绘制里 | 头本地Y | 身末本地Y | 旧规则(只判端点) | 新规则(整段相交) | 说明 |
|---|---|---|---|---|---|---|
| 9.50 | 否 | +7.514 | +37.571 | 剔 | 剔 | 还没到，整根在屏外（正常） |
| 10.00 | **是** | 0.000 | +30.057 | 画 | 画 | 头在判定线上 |
| 10.50 | **是** | −7.514 | +22.543 | **剔** | **画** | ← 旧规则闪断 |
| 11.00 | **是** | −15.029 | +15.029 | **剔** | **画** | ← 旧规则闪断 |
| 11.50 | **是** | −22.543 | +7.514 | **剔** | **画** | ← 旧规则闪断 |
| 12.00 | 否 | −30.057 | 0.000 | 画 | 画 | 尾判定，`sec>=endTime` 先剔（正确） |

「在绘制里」是**真调 `Engine.visible()`** 的结果：三帧闪断现在都变成「是」。

### 3.5 全库实测（`tools/chart/HoldAudit`，9324 个 hold，0.1s 采样）

| 版本 | 采样点 | 缺失 | 占比 | 受影响谱 |
|---|---|---|---|---|
| 原始（只判头） | 24218 | 1767 | 7.30% | 46 首 |
| 上一版（判两个端点） | 25806 | 1075 | 4.17% | 41 首 |
| **本版（整段相交）** | **30284** | **679** | **2.24%** | **25 首** |

「其它（头尾都在视口外）」这一类 **144 → 28**。剩余缺失全是源码同样不画的情形：
线 alpha<0（406，`line.rs:365-368`）、线 alpha≈0（229，`line.rs:257`）、
音符 `visibleTime` 未到（16，`rpe.rs:578-587`）、整根真在屏外（28）。
## 4. 编译与回归

| 项 | 结果 |
|---|---|
| `gradle :1.21.1:build` | **BUILD SUCCESSFUL**，0 错误 |
| `StressTest` | **1000000 / PHI / P=2500 G=0 B=0 M=0 / 连击 2500** ✔ |
| `SpecTest` | 全部通过 ✔ |
| `PecCompare` | 最大偏差 **2.274e-13** ✔ |
| jar | 已覆盖 `mods\phimc-1.21.1.jar`（661 088 字节，sha256 `800544E8F6E26606636334FF8CE2950960577F5BF01A847753D3566F29F8A990`），`mod/dist` 同步 |

`tools/survival/SettingsSelfTest` 里两条「默认音量 1.0」的断言已同步改成 0.35；
它依赖 MC 类（`PhiMCRegistry`），只能在 Gradle 的 mod 环境里跑，本次未单独执行。
