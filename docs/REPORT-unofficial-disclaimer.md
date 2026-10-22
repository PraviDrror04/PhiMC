# 报告：主要使用界面加「非官方项目」声明

仓库：`C:\Users\Administrator\Documents\deepseek-harness\默认工作区\mcphigros`
主文件：`mod/src/main/java/dev/dsh/phimc/client/SongSelectScreen.java`（1896 → 1977 行）、
`mod/src/main/java/dev/dsh/phimc/client/SettingsScreen.java`（385 → 450 行）

选曲界面（本地 / 在线两个页签）与设置页底部各**常驻一行**页脚声明：

```
非官方同人项目 · 与鸽游（Pigeon Games）及《Phigros》官方无授权、合作或运营关系
```

做法是「底部一条极淡半透明底条 + 条内居中灰字」：条子贴屏幕底边（`0x66000000`，13px 高），
文字 `0xFF9AA0A8`、9px（设置页再缩到 0.75 倍），视觉上是**页脚**，不是浮在内容上的字。

---

## 1. 改动文件 : 行

### 1.1 `client/SongSelectScreen.java`（1896 → 1977 行，+81）

| 内容 | 行 |
|---|---|
| 类注释：布局表改成 `listBottom = H - 26 - 4 - 13`，新增「页脚声明」两行与层次说明 | 38 / 52–60 |
| `DISCLAIM_H = 13`（页脚声明条高度，含为什么是 13 的算式） | 160–170 |
| `C_DISCLAIM = 0xFF9AA0A8` / `C_DISCLAIM_BG = 0x66000000` | 275 / 277 |
| `KEY_DISCLAIMER = "text.phimc.disclaimer"`（lang key 常量） | 284 |
| `listBottom()` = `height - BOTTOM_H - 4 - DISCLAIM_H`（480→437，720→677） | 466–469 |
| `disclaimerY()` / `disclaimerTextY()`（条顶 / 条内居中文字 y） | 496–504 |
| `init()`：底部 6 个按钮 `bottomY = height - BOTTOM_H - DISCLAIM_H`（480→441，720→681） | 615–616 |
| `render()`：在线页不再提前 `return`，两个页签都走到底 → 末尾统一 `drawDisclaimer(ctx)` | 1632–1640 |
| `drawLocalOverlay(...)`：原来 `render()` 里的详情 / 曲绘 / 信息列 / 提示行原样搬出来（纯搬移） | 1642–1675 |
| **`drawDisclaimer(DrawContext)`**：底条 + `Text.translatable(KEY_DISCLAIMER).getString()` + 居中 / 太窄缩字兜底 | 1677–1706 |
| `onlinePerPage()` 注释里的每页行数（480→13，720→22，各少 1 行） | 567 |

### 1.2 `client/SettingsScreen.java`（385 → 450 行，+65）

| 内容 | 行 |
|---|---|
| 类注释：底部布局（FOOTER/提示行/面板底让位）+ 「页脚声明」一节 | 39–50 |
| `DISCLAIM_H = 12` / `DISCLAIM_SCALE = 0.75f` / `DISCLAIM_PAD = 12` | 68 / 70 / 72 |
| `KEY_DISCLAIMER` / `C_DISCLAIM` / `C_DISCLAIM_BG`（与选曲界面同一条 key、同色） | 74 / 86 / 88 |
| `init()`：`[返回]` 改成 `footerY()`（= `H - FOOTER_H - DISCLAIM_H`，480→440，720→680） | 190–192 |
| `footerY()` / `disclaimerY()` / `noticeY()`（提示行 = `footerY() - 14`） | 195–208 |
| `render()`：面板底改成 `Math.max(28, disclaimerY())`（条子在面板外） | 340 |
| `render()`：没存档的分支也画声明（`drawDisclaimer` 后 `return`） | 350–353 |
| **`drawDisclaimer(DrawContext)`**：0.75 倍字号（`RenderCompat.transform` 缩放）+ 居中 | 412–431 |
| `render()`：提示行改用 `noticeY()`；末尾调用 `drawDisclaimer(ctx)` | 405 / 409 |

### 1.3 语言文件（新 key `text.phimc.disclaimer`）

| 文件 | 行 | 文案 |
|---|---|---|
| `mod/src/main/resources/assets/phimc/lang/zh_cn.json` | 20 | `非官方同人项目 · 与鸽游（Pigeon Games）及《Phigros》官方无授权、合作或运营关系` |
| `mod/src/main/resources/assets/phimc/lang/en_us.json` | 20 | `Unofficial fan project. Not affiliated with, endorsed by, or operated by Pigeon Games or Phigros.` |

### 1.4 版式脚本（新元素 + 断言）

| 文件 | 内容 |
|---|---|
| `tools/ui/select-layout.ps1` | `DISCLAIM_H`；`lBottom` / `bY` 减去它；新增 `disclaimY` / `disclaimTextY` / `disclaimTextBottom` / 中英文**估宽**（`Get-McTextWidth`：CJK 9px、空格 4px、ASCII 6px 的保守上界）；`Test-Layout` 加 9 条断言；坐标表打印声明条；预览 PNG 画出底条与文字；**另加设置页模型**（`Get-SettingsGeometry` / `Test-SettingsLayout` / `Show-SettingsTable` / `Draw-Settings`，13 条断言 + 预览） |
| `tools/ui/online-layout.ps1` | 同一套 `DISCLAIM_H` / 估宽；`lBottom` / `bY` 同步；`Assert-Geometry` 加 7 条声明断言；坐标表 + 预览同步 |

两个脚本原本是 **UTF-8 带 BOM**（`powershell -File` 才会按 UTF-8 解析）；编辑过程中 BOM 丢过一次，
已写回（`EF BB BF`），现在 `powershell -NoProfile -ExecutionPolicy Bypass -File` 可直接跑。

---

## 2. 两分辨率坐标表 + 断言结果

`tools/ui/select-layout.ps1`（1280×720 / 854×480，两个都跑）：

| 元素 | 1280×720 | 854×480 |
|---|---|---|
| 列表 y（top…bottom） | 203 … 677 | 183 … 437 |
| 每页行数 / 最后一行底 | 20 / 641 | 11 / 423 |
| 提示行 y | 667 … 676 | 427 … 436 |
| 底部 6 按钮 y | 681 … 701 | 441 … 461 |
| **页脚声明条 y（高 13）** | **707 … 720** | **467 … 480** |
| **声明文字 y（9px）** | **709 … 718** | **469 … 478** |
| 声明估宽（中 / 英，可用） | 378 / 554（1256） | 378 / 554（830） |

层次（自上而下）：**列表（最后一行底 641）→ 提示行（667…676）→ 底部按钮（681…701）→ 声明条（707…720）**，
即 `最后一行 < 提示行 < 按钮 < 声明`，每道缝 5–6px（文字本身又在条内，离按钮再远 8px）。

断言（`Test-Layout` 第 11 组，9 条，两个分辨率各跑一遍）：

```
✔ 声明条在屏幕内、底边贴住 H
✔ 声明文字顶 >= 条顶、文字底 <= H
✔ 底部按钮底 (701 / 461) <= 声明条顶 (707 / 467)
✔ 提示行底 (676 / 436) <= 声明条顶
✔ 声明文字 y (709 / 469) >= 按钮底 (701 / 461)
✔ 中文估宽 378 <= 内容区 (1256 / 830)
✔ 英文估宽 554 <= 内容区 (1256 / 830)   ← 所以 fit() / 缩字兜底都不会动到它
```

原有的 10 组断言（页签 / 详情面板 / 筛选行 / 列表 / 提示行 / 6 按钮 / 拷芯片 / 信息列 / HUD AUTO）
**全部仍然通过**；结论行 `✔ 两个分辨率都通过`。

设置页（`Test-SettingsLayout`，两个分辨率）：

| 元素 | 1280×720 | 854×480 |
|---|---|---|
| 面板 x / 底 | 440…840 / 708 | 227…627 / 468 |
| 最后一行文字 y | 231 … 240 | 231 … 240 |
| 提示行 y | 666 … 675 | 426 … 435 |
| `[返回]` y | 680 … 698 | 440 … 458 |
| 页脚声明条 y（高 12） | 708 … 720 | 468 … 480 |
| 声明文字 y（0.75 倍 → 284px 宽） | 711 … 718 | 471 … 478 |

`✔ 无重叠（最后一行 / 提示行 / [返回] / 页脚声明条）`，13 条断言全过。

在线页（`tools/ui/online-layout.ps1`，`Assert-Geometry` 新增 7 条）：

| 元素 | 1280×720 | 854×480 |
|---|---|---|
| 每页行数 / 最后一行底 | 22 / 632 | 13 / 398 |
| 状态行 / 版权行 y | 655 / 666 | 415 / 426 |
| 底部按钮 y | 681（底 701） | 441（底 461） |
| 页脚声明条 / 文字 y | 707…720 / 709 | 467…480 / 469 |

`全部通过 ✓`（21 项 OK，含 7 条声明断言）。

预览图（仓库外）：`C:\dsh-work\ui-check\select-layout-{1280x720,854x480}.png`、
`settings-layout-*.png`、`online-*.png`、`hud-auto-*.png`。

**取舍**：本地页每页条数没变（480 仍是 11 行、720 仍封顶 20 行），代价落在**在线页**：
每页各少 1 行（23→22 / 14→13，`ONLINE_PAGE_MAX=30` 不变），因为在线列表底
=`listBottom - ONLINE_NOTICE_H`。要不要保 23 行就得把声明条压到按钮上，不划算。

---

## 3. 编译 + StressTest

### 3.1 `gradle :1.21.1:build`（Gradle 9.8.2 + JDK 25，offline）

```
$env:JAVA_HOME="C:\dsh-tools\jdk25"; $env:GRADLE_USER_HOME="C:\dsh-gradle-home"
gradle -p <repo>\mod :1.21.1:build --no-daemon --console=plain --offline

> Task :1.21.1:stonecutterPrepare
> Task :1.21.1:stonecutterGenerate
> Task :1.21.1:compileJava
> Task :1.21.1:processResources
> Task :1.21.1:classes
> Task :1.21.1:jar
> Task :1.21.1:processIncludeJars
> Task :1.21.1:sourcesJar
> Task :1.21.1:stonecutterPrepareTest
> Task :1.21.1:stonecutterGenerateTest NO-SOURCE
> Task :1.21.1:compileTestJava NO-SOURCE
> Task :1.21.1:processTestResources NO-SOURCE
> Task :1.21.1:testClasses UP-TO-DATE
> Task :1.21.1:test NO-SOURCE
> Task :1.21.1:validateAccessWidener NO-SOURCE
> Task :1.21.1:check UP-TO-DATE
> Task :1.21.1:remapJar
> Task :1.21.1:remapSourcesJar
> Task :1.21.1:assemble
> Task :1.21.1:build

BUILD SUCCESSFUL in 1h 8m 21s
10 actionable tasks: 10 executed
```

**0 error / 0 warning**。`compileJava` 是**真跑**的（不是 UP-TO-DATE），所以这次改动确实编过。

> `1h 8m` 不是这次改动造成的：这个 checkout 之前**从没构建过**，Stonecutter 要把 19 个版本节点的
> loom 依赖（fabric-api 那一百来个 jar）各 remap 一遍源码，Gradle 在**配置阶段**把
> `:1.20` … `:1.21.9` 全部配置完（每个约 2–6 分钟，日志里一串 `Fabric Loom: 1.19.2 / :remapping sources`），
> 之后才执行 `:1.21.1:build` 本身（几秒）。缓存已落地，再跑同一条命令是秒级。

产物：`mod/versions/1.21.1/build/libs/phimc-1.21.1.jar`（695066 字节，01:44:38），
内含 `assets/phimc/lang/{zh_cn,en_us}.json`（各 19 key，含 `text.phimc.disclaimer`）与
`SongSelectScreen.drawDisclaimer` / `SettingsScreen.drawDisclaimer`（`javap -p` 核对）。

### 3.2 StressTest（判定 / 计分一行没改）

`core/` 与判定 / 计分（`Engine` / `Judgement` / `Session.tick`）**一行未动**，只改了客户端 UI。
用当前 `core/src` 重编重跑（`fixtures/spasmodic/chart.json`，2500 音符 / 24 判定线 / 141.1s）：

```
谱面：2500 音符 / 24 判定线 / 141.1s
[自动播放] 分数=1000000 评级=PHI P=2500 G=0 B=0 M=0 最大连击=2500/2500
[不操作]   分数=0 P=0 M=2500/2500（Drag 不再自动判，1002 个 Drag 一并 Miss）
[±60ms]    分数=395690 评级=F P=934 G=225 B=30 M=1311 最大连击=170/2500
```

自动播放仍 **1000000 / PHI / 0 Miss**；不操作 / 抖动两条与修前**逐字一致**。

### 3.3 覆盖到客户端

```
mod/versions/1.21.1/build/libs/phimc-1.21.1.jar  (695066 B, 01:44:38)
  → D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar   （已覆盖，大小/时间一致）
```

证据（仓库外）：`C:\dsh-work\evidence\select-layout.txt`、`online-layout.txt`（两次脚本完整输出）。

---

## 4. 没做与原因

1. **没把声明做成可点击链接 / 弹窗**：需求只要「一行常驻声明」。加交互要动 `mouseClicked`
   与页面状态，收益低；现在它只是页脚，不抢输入（在线页搜索框 / 列表点击都不受影响）。
2. **没有在真客户端截图核对**（本机无可交互客户端）：文档里一直是这个限制。
   对齐靠「版式脚本 = 代码常量副本 + 断言 + PNG 预览」这套既有办法；
   文字宽度用**保守上界**（CJK 9px / ASCII 6px）估，两档分辨率余量都在 400px 以上。
3. **没有改 `lang` 之外的语言文件**：仓库只有 `zh_cn` / `en_us` 两套，其它语言回落英文。
4. **没有把声明放进 `PlayScreen`（游玩 HUD）**：需求点名的是「主要使用界面 = 选曲 + 设置」；
   游玩时铺满判定线，加常驻文字会干扰读谱，也违背「不能压住内容」。
5. **没有为了保在线页 23 行去动 `ONLINE_NOTICE_H` / `ONLINE_ROW_STEP`**：那是既有版式，
   改它会连带动到「状态行 / 版权行」的层次；这次只做「底部让出 13px」这一处最小改动。
