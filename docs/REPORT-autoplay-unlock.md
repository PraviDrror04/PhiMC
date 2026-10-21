# 报告：自动游玩（AUTO）开关 + 谱面播放器全解锁

用户追加的两条需求。承接上一份 `docs/REPORT-ui-lock-cover.md`（旧 UI 删除 / 锁定 + 拷芯片 /
pez 曲绘 / 曲师·谱师·画师）。本次同样**没有碰判定与计分公式**（`core/` 一行未改；
`Session` 只是多了一条「每帧走 `Engine.autoplay(t)`」的通路，`Engine.autoplay` 本来就是
`StressTest` 在用的那个方法）。

---

## 1. 自动游玩（Autoplay）开关

### 1.1 「开始」旁边的勾选按钮

| 内容 | 位置 |
|---|---|
| 按钮字段 | `client/SongSelectScreen.java:140` `autoBtn`；勾选状态 `:142` `autoplayOn` |
| 文字 | `:339` `autoplayLabel()` → `[✓] 自动游玩` / `[ ] 自动游玩` |
| 点击 | `:349` `toggleAutoplay()`：翻转 → `Session.setAutoplay()` → `autoBtn.setMessage(...)` → 底部提示「自动游玩：开（引擎自动判 Perfect；这一局不给成绩、不发奖励）」/「关（恢复正常手动判定）」 |
| 创建 | `init()` 里紧跟「开始」之后（`xAuto = xStart + bw + BTN_GAP`，宽 `AUTO_W = 100`） |
| 初始值 | `init()` 读 `profile.autoplay()`（跨局 / 跨重启保持） |

### 1.2 `Profile` 字段（默认 false）

| 内容 | 位置 |
|---|---|
| 字段 | `survival/Profile.java:87` `private boolean autoplay;`（默认 false） |
| 读 / 写 | `:287` `autoplay()`、`:291` `autoplay(boolean)`（变了就 `save()`） |
| 载入 | `:367` 复位为 false；`:387` `if (o.has("autoplay")) …`（老存档缺字段 = false） |
| 落盘 | `:453` `o.addProperty("autoplay", autoplay);`（`:31` 的类注释 JSON 样例也补了） |

### 1.3 `Session.tick()` 的调用点

`client/Session.java`：

| 内容 | 位置 |
|---|---|
| 字段 | `:161` `private boolean autoplay;` |
| 初值 / 每次开局同步 | `:179`（构造）、`:334`（`start()` 里 `this.autoplay = profile.autoplay()`） |
| 读 / 写 | `:210` `autoplay()`、`:220` `setAutoplay(boolean)`（同时写 `profile.json`） |
| **每帧分支** | `:497–503`：`if (autoplay) engine.autoplay(t); else { pushPointer(); engine.update(t); }` |
| 不吃输入 | `:652` `pressKey`、`:666` `pressFlickKey`、`:682` `releaseKey`、`:714` `hitAt`、`:739` `dragAt` |

为什么这么调（照源码语义，`core/Engine.java:235–243`）：

```java
/** 自动播放：把到点的音符全部判成 Perfect，再推进时钟。fake 音符不判（judge.rs:307）。 */
public void autoplay(double sec) {
    for (NoteState s : states) {
        if (s.note.time > sec) break;
        if (s.judged || s.note.fake) continue;
        judge(s, Judgement.Grade.PERFECT, 0, s.note.time);
        s.hit = true;
    }
    update(sec);      // ← 内部已经调了 update，所以 Session 里不要重复调
}
```

- **不会和 Miss 扫描冲突**：`autoplay()` 先把 `note.time <= sec` 的音符整批判成 Perfect，
  再调 `update(sec)` 做 Miss 扫描；那些音符已经是 `judged`，扫描会 `continue` 跳过。
- **Hold 也不会掉尾巴**：`autoplay` 只判头（`judge(..., PERFECT, ...)`），不置 `holding`，
  所以 `maintainHold` / `completeHolds` 都不会碰它（`StressTest` 一直就是这么满分 0 Miss 的）。
- 自动局**不推「手指」**（不调 `pushPointer()`）：没有意义，而且会多出一条 Drag/Flick 预判通路。
- 输入在自动局被闸掉，防止「按一下键把未来的音符提前判成 Good/Bad」破坏满分。

### 1.4 游玩界面 HUD 的「AUTO」标记

`client/PlayScreen.java:338–345`（画在 HUD 的段位字母之后）：

```java
if (session.autoplay()) {
    String auto = "AUTO";
    int autoW = mc.textRenderer.getWidth(auto);            // A/U/T/O 各 6px → 24
    ctx.fill(margin - 3, margin - 3, margin + autoW + 4, margin + 12, 0x90000000);   // 深底
    ctx.fill(margin - 3, margin - 3, margin + autoW + 4, margin - 2, 0xFFFFEC9F);    // 金色顶线
    ctx.drawText(mc.textRenderer, auto, margin, margin, 0xFFFFEC9F, true);
}
```

`margin = max(8, round(W × 0.03))`（`HUD_MARGIN = 0.03`，`PlayScreen.java:117`）。

### 1.5 结算：自动局**不给成绩、不发奖励**

用户已定：**自动游玩不发奖励**。除此之外，写成绩也会让「用 AUTO 刷最佳」成立，
所以本次选择 **两者都不写**：

| 内容 | 位置 |
|---|---|
| 结论 | `client/Session.java:571` `boolean auto = autoplay;` → `:572–589` 只对**非自动局**执行 `records.submit(...)` + `profile.record(...)` + `Rewards.grantOnFinish(...)`；自动局只打一行日志 `:574` |
| 结算界面奖励行 | `client/PlayScreen.java:629–630`：`session.autoplay()` 时写死 `奖励：无（自动游玩）`，**不读** `Rewards.lastRewardText()`（否则会显示上一局残留） |
| 判定 / 计分 | 完全照常跑（同 `StressTest`），只是「结算落盘」这一步被跳过 |
| 不受影响 | 平板赠送、商店解锁、`records.tsv` 里已有的历史记录 |

取舍说明：自动局每局都是 1000000 / PHI，如果写进 `records.tsv` 或 `profile.json`，
「最佳成绩 / FC」这些数据立刻失去意义；奖励同理（AUTO 一开就能稳定刷最高档物资）。
所以自动局**只显示成绩、不落盘**。结算界面的分数 / 评级 / 判定数 / 连击 / ACC 都是真实计算出来的。

### 1.6 坐标表（两个分辨率）

底部现在 6 个槽位：`5×70 + 自动游玩 100`，间距 8，整体居中；`y = H - 26`，高 20。
校验脚本 `tools/ui/select-layout.ps1`（跑一次打印下面两张表 + 断言不重叠 + 出预览图）。

| 槽位 | 1280×720 | 854×480 |
|---|---|---|
| ← 上一页 | 395..465 | 182..252 |
| 开始 | 473..543 | 260..330 |
| **`[✓] 自动游玩`** | **551..651**（宽 100） | **338..438**（宽 100） |
| 商店 / 关闭 | 659..729 | 446..516 |
| 设置 | 737..807 | 524..594 |
| 下一页 → | 815..885 | 602..672 |
| 整行（宽 / 左起） | 490 / 395 | 490 / 182 |
| 按钮 y | 694..714 | 454..474 |

断言（`Test-Layout`）：6 个按钮都在 `[PAD, W-PAD]` 内、两两间距 ≥ 8、
「自动游玩」左边界 = 「开始」右边界 + 8（**紧挨着**，用户要求）、
提示行底 ≤ 按钮顶、最后一行列表底 ≤ 提示行顶。

游玩 HUD 的 AUTO 标记框：

| | 1280×720 | 854×480 |
|---|---|---|
| `margin` | 38 | 26 |
| AUTO 框 | (35,35)..(66,50) | (23,23)..(54,38) |

断言：框右边界 < W/2（不压居中的连击数字）、框底 < H/2（不进判定线区域）、不接近右上角分数。

预览（仓库外）：`C:\dsh-work\ui-check\select-layout-{1280x720,854x480}.png`（选曲界面）、
`hud-auto-{1280x720,854x480}.png`（游玩 HUD）。

### 1.7 自动游玩自测（按帧，不是按音符）

`tools/autoplay/AutoplayFrameTest.java`：照 `Session.tick()` 那样**每帧**调一次
`engine.autoplay(t)`，验证「满分 0 Miss」「与帧率无关」「关掉后无残留」。

```
=== Chart_IN.json ===
谱面：1285 音符 / 24 判定线 / 117.7s
   20 fps（2395 帧） 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 连击=1285/1285  ✔
   60 fps（7184 帧） 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 连击=1285/1285  ✔
  144 fps（17242 帧） 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 连击=1285/1285  ✔
  240 fps（28736 帧） 分数=1000000 评级=PHI P=1285 G=0 B=0 M=0 连击=1285/1285  ✔
  关掉自动游玩后 2s：判定数 1285 → 1285，Miss 0 → 0，分数 1000000 → 1000000  ✔

=== Chart.json ===
谱面：1418 音符 / 24 判定线 / 150.9s
   20 fps（3059 帧） 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 连击=1418/1418  ✔
   60 fps（9176 帧） 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 连击=1418/1418  ✔
  144 fps（22021 帧） 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 连击=1418/1418  ✔
  240 fps（36702 帧） 分数=1000000 评级=PHI P=1418 G=0 B=0 M=0 连击=1418/1418  ✔
  关掉自动游玩后 2s：判定数 1418 → 1418，Miss 0 → 0，分数 1000000 → 1000000  ✔

结论: ✔ 全部通过（按帧自动游玩 = 满分 0 Miss；关掉后无残留）
```

`Profile` 的 autoplay 落盘也加了断言（`tools/survival/SurvivalSelfTest.java`）：

```
  ✓ autoplay 默认 false
  ✓ autoplay(true) 立刻生效
  ✓ autoplay=true 已写进 profile.json
  ✓ 重载后 autoplay 仍为 true（跨局保持）
  ✓ 关掉自动游玩后落盘为 false（不残留）
```

---

## 2. 谱面播放器打开时全谱面解锁

### 2.1 实现：构造参数 `unlockAll`，闸门统一走 `isLocked()`

| 内容 | 位置 |
|---|---|
| 字段 | `client/SongSelectScreen.java:132` `private final boolean unlockAll;` |
| 构造 | `:151` `SongSelectScreen(boolean unlockAll)`、`:158` `SongSelectScreen(Supplier<Screen> backTo)`（= false）、`:166` `SongSelectScreen(boolean, Supplier<Screen>)`（主构造） |
| **唯一闸门** | `:375` `isLocked(int)` / `:383` `isLocked(SongEntry)`：`unlockAll` → 一律可玩；否则看 `Profile.isUnlocked` |
| 真解锁（只给拷芯片） | `:390` `trulyUnlocked(SongEntry)`：**不受 `unlockAll` 影响** |
| 开始闸门 | `:413` `startSelected()` → `isLocked(selected)` 时只给「未解锁：…」提示 |
| 开始按钮变灰 | `:321` `startBtn.active = … && !isLocked(selected)` |
| 列表灰字 / 「未解锁」 | `drawList()` 里 `boolean locked = isLocked(index)` |
| 详情「状态」行 | `:664–666`：真解锁 → `已解锁`；`unlockAll` 且存档没解锁 → `已解锁（播放器）`；否则 `未解锁` |
| 拷芯片按钮显隐 | `:326` 起：只看 `trulyUnlocked(...)`（播放器模式**不给**拷芯片，`ChipItem.carveHeld` 还会再查一次存档） |

**绝不写 `Profile.unlocked`**：`unlockAll` 只在内存里当「能不能开始」的开关，
没有任何 `profile.unlock(...)` 调用（全仓库搜 `unlock(` 只有 `TabletScreen.buy`、
`ChipItem.use`、`TabletGift` 三处）。所以平板那边不受影响。

### 2.2 两个入口分别传什么

| 入口 | 代码 | `unlockAll` |
|---|---|---|
| **谱面播放器** `chart_player` 右键 / **P 键** | `PhiMCClient.java:86` `openSongSelect(boolean)` → `:94` `new SongSelectScreen(true)` | **true** |
| 谱面播放器**潜行**右键（重打上一首） | `:91` `new PlayScreen(session, true)` | 局内 true，退出回「全部可玩」曲库 |
| **平板**右键 | `:106` `openTablet()` → `:109` `new SongSelectScreen(TabletScreen::new)` | **false** |
| 商店页「曲库」页签 | `TabletScreen.java:112` `new SongSelectScreen(TabletScreen::new)` | **false**（平板这一侧） |
| 结算界面「退出」 | `PlayScreen.java:679` `new SongSelectScreen(unlockAll)` | 沿用进来时的模式（`PlayScreen` 多了 `unlockAll` 字段 `:127`、构造 `:151/:159/:162`） |

`PlayScreen` 的 `unlockAll` **只决定退出后回哪个形态的曲库**，不参与判定 / 计分 / 绘制。

---

## 3. 编译输出 + 两条 StressTest

### 3.1 `gradle build`

```
robocopy "…\phimc" C:\dsh-work\phimc /MIR /XD out .gradle build fixtures dist run
$env:JAVA_HOME="C:\dsh-tools\jdk21"; $env:GRADLE_USER_HOME="C:\dsh-gradle-home"
gradle -p C:\dsh-work\phimc\mod build --no-daemon --console=plain --offline

> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :jar
> Task :compileTestJava NO-SOURCE
> Task :check UP-TO-DATE
> Task :remapJar
> Task :remapSourcesJar
> Task :assemble
> Task :build

BUILD SUCCESSFUL in 11s
7 actionable tasks: 5 executed, 2 up-to-date
```

0 error / 0 warning（`C:\dsh-work\evidence\build.txt`）。

### 3.2 StressTest ×2（判定 / 计分没动）

| 谱面 | 音符 / 判定线 | 自动播放 | 不操作 | ±60ms 抖动 |
|---|---|---|---|---|
| `C:\dsh-work\pkg1\Chart_IN.json` | 1285 / 24 / 117.7s | **1000000 PHI，P=1285 G=0 B=0 M=0，连击 1285/1285** | 0 分，M=1285/1285 | 509829 F |
| `C:\dsh-work\pkg2\Chart.json` | 1418 / 24 / 150.9s | **1000000 PHI，P=1418 G=0 B=0 M=0，连击 1418/1418** | 0 分，M=1418/1418 | 341844 F |

证据：`C:\dsh-work\evidence\stress-pkg1.txt` / `stress-pkg2.txt`。

### 3.3 其他自测

| 自测 | 结果 |
|---|---|
| `tools/autoplay/AutoplayFrameTest.java`（按帧自动游玩） | ✔ 20/60/144/240 fps 全 1000000 / 0 Miss；关掉后无残留 |
| `tools/survival/SurvivalSelfTest.java`（Profile 读写） | autoplay 相关 5 条全 ✔；**另有 1 条既存失败**：`✗ 价格物品落在对应难度的池里`（`PriceTable`/`Shop` 本次一行未改，属既有问题，见第 4 节） |
| `tools/ui/select-layout.ps1`（版式） | ✔ 1280×720 / 854×480 都通过（含 6 底部按钮 + HUD AUTO） |

---

## 4. 没做与原因

1. **没在真客户端里点一遍**（本机没有可交互客户端，同上一次交付）：自动游玩的正确性用
   `AutoplayFrameTest`（真的按帧调 `Engine.autoplay`）证明；界面用坐标表 + 不重叠断言 + 预览图核对。
   真机验锁定依旧可以用 `config/phimc/unlockall.txt`。
2. **自动局不写成绩**（不只是不发奖励）：需求里写的是「如果写进去会导致用 AUTO 刷最佳成绩，
   就不要写 record」——AUTO 每局 1000000，必然会导致，所以 `records.tsv` 与 `profile.json`
   两条记录都不写，`Rewards.grantOnFinish` 也不调；结算界面写「奖励：无（自动游玩）」。
3. **没做「局中切换 AUTO」的入口**：勾选按钮只在选曲界面（`PlayScreen` 是模态的，暂停面板
   里没有加）。`Session.setAutoplay` 本身支持局中切，但 UI 不暴露，避免出现「半首歌自动、
   半首歌手动」的成绩。
4. **播放器模式不给「拷芯片」**：`unlockAll` 只是「能玩」，把没解锁的谱面复制进芯片会绕过
   生存解锁；所以拷芯片仍然只看 `trulyUnlocked`（`ChipItem.carveHeld` 也会再查一次）。
5. **`SurvivalSelfTest` 的 1 条既存失败没修**：`✗ 价格物品落在对应难度的池里`
   —— 它在 `priceTableTests()` 里，查的是 `PriceTable.of(...)` 的物品是否落在该难度池中；
   本次没有改动 `PriceTable` / `Shop`，属于之前就存在的问题（`玩具狂奏曲終焉#IN Lv.?` 这种
   带 `Lv.?` 的难度文本会被判成 AT 档）。留给后续单独修，避免把「定价」这类玩法数值改动
   混进这次 UI/自动游玩交付里。
6. **`docs/DESIGN-survival.md` §3.1 仍描述旧的平板曲库页**（历史设计稿），本次同样没有改文档正文，
   当前实现以 `docs/REPORT-ui-lock-cover.md` + 本文件为准。
7. 仓库里新增的都是源码 / 自测脚本，没有提交任何图片（预览图在 `C:\dsh-work\ui-check\`）。
