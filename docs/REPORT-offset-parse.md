# 判定偏移 + 谱面解析失败可诊断 / 可兼容 报告

两块需求：

1. 设置页加「偏移（Offset）」：-300ms … +300ms，步进 10ms，存 `Profile`，`Session.seconds()` 生效；
2. 「有个谱面解析失败」：先让错误可诊断（路径 / 大小 / 异常 / JSON 开头），再把常见坏数据
   逐条兜底，并给一个 headless 的「解析失败清单」。

---

## 1. 判定偏移

### 1.1 文件:行

| 文件:行 | 改动 |
|---|---|
| `mod/.../survival/Profile.java:107-111` | 新增常量 `OFFSET_LIMIT_MS = 300`；`offsetMs` 字段注释（毫秒、±300、+ = 谱面提前） |
| `Profile.java:344-353` | `offsetMs()` / `offsetMs(double)`：夹到 ±300，变化时立刻 `save()` |
| `Profile.java:411-415` | `load()` 读 `offsetMs` 时也夹到 ±300（老存档缺字段 = 默认 0，什么都不做） |
| `mod/.../client/Session.java:336-347` | **生效点**：`seconds()` 里 `chartTime = (audio - chart.offset) + profile.offsetMs/1000` |
| `mod/.../client/SettingsScreen.java:58-59, 126-144` | 新增「判定偏移」一行：`-` / 当前值 / `+` / `重置`（风格照音量行） |
| `SettingsScreen.java:285-300` | 行 2 的值显示 + 行 3 的符号说明 |
| `SettingsScreen.java:14-49` | 类注释的版式表更新（行 0…6 重新编号）+ 符号语义 |

`Profile.offsetMs` 字段本身是**以前就有的**（`offsetMs = 0`，`load`/`save` 都读写），但从来没被
任何地方用过、也没有 UI —— 这次把它接上，不改存档格式。

### 1.2 单位的说明（选择了「整数毫秒」的语义、double 存储）

`Profile.offsetMs` 是 **double 毫秒**（不是小数秒）：

- 界面步进 10ms、玩家心里的刻度也是毫秒；
- 内部只在 `Session.seconds()` 里 `/1000` 换成秒，**判定窗口 / 谱面时间一律还是秒**；
- 用 double 是为了兼容以前手改过的 `12.5` 这种值（读出来照样能用，点一下 `+` 会按 10ms 刻度归整）。

范围：`-300 … +300ms`，`Profile` 的 setter 和 `load()` 都夹；界面上按 10ms 走。

### 1.3 符号语义（UI 上写清楚了）

```
判定偏移  [-]  +50ms  [+]  [重置]
＋ = 谱面提前（音符更早到判定线）；－ = 谱面延后；步进 10ms，范围 ±300ms
```

**`+50ms` = 谱面提前 50ms**：同一时刻的音频位置对应的谱面时间更大 ⇒ 谱面上的音符更早到达判定线。

代码里就是一行加法（`Session.seconds()`）：

```java
double base = Clock.toChartTime(Math.max(0, pos), chart == null ? 0.0 : chart.offset); // audio - chart.offset
double t = base + profile.offsetMs() / 1000.0;                                        // + = 谱面提前
```

### 1.4 和谱面自带 offset 的关系

**两个都生效，互不覆盖**：

| | 来源 | 谁写 | 符号 |
|---|---|---|---|
| `Chart.offset` | 谱面包里（PGR 的 `offset` / RPE / PEC 第一行） | 谱师 | 正 = 谱面比音乐快（`audio - offset`） |
| `Profile.offsetMs` | `config/phimc/profile.json` | 玩家在设置页调 | 正 = 谱面提前（`+ offset/1000`） |

谱师写死的那个是谱面语义的一部分（不能动）；玩家这个是本机的音画延迟补偿（每台设备不同）。
两者在 `seconds()` 里串起来：`chartTime = audio - chart.offset + playerOffset`。

---

## 2. 谱面解析失败：可诊断 + 尽量能玩

### 2.1 诊断（先让错误可诊断）

| 位置 | 改了什么 |
|---|---|
| `Session.java:355-372` | `start()` 的解析整段包进 `catch (Throwable)`（**故意**含 `OutOfMemoryError`，大谱面爆内存不能只显示「解析失败」）；把原因存进 `lastFailReason`，并调 `logParseFailure` |
| `Session.java:449-470` | `logParseFailure()`：打印 **谱面包绝对路径、谱面 JSON 字符数、异常类型 + 消息、JSON 开头 200 字符**（OOM 只打前两项，避免二次分配） |
| `Session.java:472-492` | `summarizeParseFailure()`：异常 → 一句中文（`JSON 语法错误：…` / `谱面太大，内存不足（加大 -Xmx 再试）` / `谱面里没有音符（判定线 0 条）` / `读谱面包失败：…`） |
| `Session.java:374-382` | 解析成功但 `noteCount() == 0` 也判失败，原因「谱面里没有音符（判定线 N 条）」 |
| `Session.java:280-282`（`lastFailReason()`） | 给界面读的 getter |
| `SongSelectScreen.java:820-821` | 底部提示变成 `谱面解析失败：<曲名>（<原因摘要>）` |
| `SongEntry.java:359-382` | `chartJson()` 抛 `IOException`/`RuntimeException` 时先打一条日志：`读取谱面 JSON 失败: 曲名 \| path=绝对路径 \| 异常类型: 消息`，再原样抛出 |

**诊断日志样例**（`logs/latest.log` 里实际会看到的样子）：

```
[PhiMC] 读取谱面 JSON 失败: '-1' · AT Lv.16 | path=D:\.minecraft\...\songs\6954_-1.pez | java.io.IOException: 压缩包里没有谱面 JSON
[PhiMC] 谱面解析失败: '-1' · AT Lv.16 | path=D:\.minecraft\...\songs\6954_-1.pez | json=151624 字符 | com.google.gson.JsonSyntaxException: com.google.gson.stream.MalformedJsonException: Use JsonReader.setStrictness(...) ... | 开头: 185 bp 0.000 174.000 n1 1 4.000 -640.000 1 0 # 1.00 & 1.00 n1 1 4.000 -240.000 ...
[PhiMC] 谱面没有音符: xxx | path=... | 格式 pgr | 判定线 0 条
[PhiMC] 谱面 JSON 较大（46.7 MB），解析可能占用较多内存: big song (self insert) | path=...
[PhiMC] 谱面解析失败（内存不足）: xxx | path=... | json=52428800 字符 | 建议：加大 -Xmx 或换更小的谱面
```

界面上（选曲界面底部）会显示成：

```
谱面解析失败：'-1' · AT Lv.16（JSON 语法错误：com.google.gson.stream.MalformedJsonException: ...（不是 JSON 谱面？需要 PEC / 其它格式的解析器））
```

### 2.2 兜底规则表（坏数据跳过，不整体失败）

| 坏数据 | 之前 | 现在 |
|---|---|---|
| `formatVersion` 缺失 / 不是 1、3 | 默认 1（能用，但没读 `format` 别名） | 先读 `formatVersion`，没有再读 `format`，都没有按 1；**任何版本号都不导致失败** |
| `judgeLineList` 为 null / 空 | `new Chart(version, offset, [])`（能过，但后面开局可能 0 音符没提示） | 照旧容忍；另外「0 音符」在 `Session.start` 里给明确原因「谱面里没有音符（判定线 N 条）」 |
| `judgeLineList` 里某条是 null / 不是对象 / 解析抛异常 | `NPE` → 整张谱面失败 | 跳过这一条，继续解析后面的（`ChartParser.lastSkippedData()` 计数） |
| `notes` 里 `type` 是 0 / 5 / 未知 | `default -> TAP`（0 也会变 TAP） | `0` 跳过（导出工具的占位，不是可打音符）；`5`/未知按 `TAP`（宁可多一个能打的，也不要整张打不开） |
| `notes` 数组里混了 null / 字符串 | `NPE` → 整张失败 | 跳过该条，计数 |
| `startTime` / `endTime` / `time` / `holdTime` 是**数组**（`[a,b]`）或**对象**（`{"value":a}` / `{"time":a}` / `{"beat":a}`） | `getAsDouble()` 抛异常 → 用默认值 0（时间全变 0，谱面「能开但全挤在开头」） | `timeOf()` 依次尝试 数字 / 数组第一个数 / 对象的 `value`/`time`/`beat`/`start`，取不到才用默认值 |
| 速度 / 位移 / 旋转事件数组里混 null | `NPE` → 整张失败 | 跳过该条事件 |
| **JSON 根本不是 JSON**（PEC 文本谱面，如 `6954_-1.pez` 的 `-1.pec`） | `MalformedJsonException` → 整张失败 | **新增 `PecParser`**，自动识别并解析（见 2.3） |
| 大文件 >20MB | 只在 OOM 时崩 | `>20MB` 先打一条警告日志；`OutOfMemoryError` 被 `catch` 住，提示「谱面太大，内存不足（加大 -Xmx 再试）」，不崩客户端 |

### 2.3 新增：PEC 文本谱面解析（`core/PecParser.java`，467 行）

用户曲库里唯一解析不了的 `6954_-1.pez` 里其实**不是 JSON**：`-1.pec` 是 **PEC 纯文本谱面**
（151KB、7345 行、1250 个音符）。Phira 自己支持它（`prpr/src/parse/pec.rs` 386 行），
所以按那份源码移植了一个：

- 命令：`bp` / `n1`~`n4` / `# speed` / `& size` / `cv` / `cp` / `cd` / `ca` / `cm` / `cr` / `cf`；
- 坐标：判定线 `x/2048`、`y/1400`；音符 `x/115.2`（PEC 的 -1024…1024 世界坐标 → 我们的
  PGR `positionX` 单位，换算依据 `prpr pgr.rs:207` 的 `positionX × 0.1125`）；
- 时间：`bp` 的 BPM 表分段积分（`prpr core.rs:112-145` 的 `BpmList`），所有 beat → 秒；
- 高度：速度事件积分（和 PGR 同一条 `Chart.heightAt`）；
- 透明度 `/255`、旋转取负、缓动走 `RpeParser.tweenId/tween` 同一张表；
- 容错：**单行解析失败只跳过那一行**（未知命令 / 参数不足 / 数字格式错），
  跳过条数在 `PecParser.lastSkippedLines()`。

解析结果与源文件对得上：

```
format=pec offset=0.035 dur=144.7 lines=30 notes=1250
line 1: notes=1112 speedEv=16 moveX=402 moveY=402 rot=219 alpha=24
  TAP   t=1.379 x=-5.556 h=4.96   ← PEC 原文 n1 1 4.000 -640.000 1 0（-640/115.2 = -5.556）
  TAP   t=1.379 x=-2.083 h=4.96   ← -240/115.2
  FLICK t=1.379 x= 5.556 h=4.96   ← 640/115.2
```

（1250 = PEC 里 n1 906 + n2 161 + n3 66 + n4 117，一个不多一个不少；
30 条判定线 = 最大 line id 29 + 1。）

`ChartParser.parse(String)` / `parse(Path)` 现在先判 PEC 再走 JSON（`parse(Path)` 只偷看前 4KB，
不会把 46MB 的 JSON 整份预读）。

### 2.3.1 顺手修的一处：PEC 谱面的「物量 / BPM」

选曲界面右侧的物量 / BPM 走 `SongMeta`（后台线程：先 `ChartMeta.scan` 读 META，再 `ChartParser.parse`）。
PEC 不是 JSON，`ChartMeta.scan` 会抛 `JsonSyntaxException`，**以前会把后面的全量解析一起拖死**
（PEC 那首的物量 / BPM 会永远显示 `-`）。现在把 META 那一步单独 `try/catch`（`SongMeta.java:83-105`），
PEC 照样能算出 `音符 1250 / 判定线 30 / BPM 174`，只是没有 RPE/PGR 才有的 META 兜底字段
（曲师 / 谱师 / 画师 仍来自 `info.yml`）。
### 2.4 兜底自测（两个合成样本，跑的就是工具那条链路）

```
== 扫描 C:\dsh-work\chart-check\badpkg ==
  ✗ 坏JSON                       坏JSON
      路径   : C:\dsh-work\chart-check\badpkg\坏JSON
      JSON   : 0.00 MB
      异常   : JsonSyntaxException：java.io.EOFException: End of input at line 2 column 1 path $.judgeLineList[0]
      JSON 头 : {"name":"坏 JSON 测试","offset":0,"judgeLineList":[
  ✓ 数组时间戳与format别名         [pgr] 音符 3 / 判定线 1 / 0.0s / JSON 0.00 MB / 0ms（跳过坏数据 1 条）

合计 2 首：成功 1，失败 1
失败清单：
  - 坏JSON → JsonSyntaxException：java.io.EOFException: End of input at line 2 column 1 path $.judgeLineList[0]
```

- 「坏JSON」：截断的 JSON → 失败时打出**路径 / 大小 / 异常 / JSON 开头**（就是需求要的四件套）；
- 「数组时间戳与format别名」：`{"format":3, "startTime":[0], "endTime":1, "startTime":{"value":0},
  "type":0 和 type:5 混在 notes 里}` → **解析成功**，`type: 0` 被跳过（`跳过坏数据 1 条`），
  数组 / 对象时间戳都读成了正确时间，`format` 别名当 `formatVersion` 用。

---
## 3. 解析失败清单（headless）

工具：`tools/chart/ChartScan.java`（新增）。

```
java -Xmx3g -cp "<out>;gson.jar;jlayer.jar;slf4j-api.jar;webp.jar" ChartScan <目录> [目录2 …]
```

对每个 `.pez / .zip / 目录形态` 调真代码（`SongEntry.chartJson()` → `ChartParser.parse()`），
成功打印 `格式 / 音符 / 判定线 / 时长 / JSON 大小 / 耗时 / 跳过坏数据条数`，
失败打印 `路径 / JSON 大小 / 异常类型+消息 / JSON 开头 200 字符`，最后给「失败清单」并 `exit 1`。

### 3.1 跑 `C:\dsh-work\payload`（4 个 pez）

```
== 扫描 C:\dsh-work\payload ==
  ✓ Cyberneko Pulse (2).pez      Cyberneko Pulse · AT Lv.17   [rpe] 音符  3092 / 判定线  53 / 125.9s / JSON 10.87 MB
  ✓ Hardsync.pez                 Hardsync · AT Lv.16          [rpe] 音符  1739 / 判定线  56 / 163.1s / JSON 12.82 MB
  ✓ 推活天使ビビアンちゃん .pez      推活天使ビビアンちゃん · AT Lv.18 [rpe] 音符  2800 / 判定线 105 / 233.0s / JSON 13.55 MB
  ✓ 琵琶曲DJ(1.15x).pez           琵琶曲DJ(1.15x) · Simple Lv.91 [rpe] 音符 34211 / 判定线   1 / 144.5s / JSON 13.32 MB
合计 4 首：成功 4，失败 0
```

### 3.2 跑用户曲库 `D:\.minecraft\versions\1.21.1-fabric-0.20.0\config\phimc\songs`（26 个包）

| 谱面 | 格式 | 音符 / 判定线 | 时长 | JSON | 结果 |
|---|---|---|---|---|---|
| 10176_Super Nova Project.pez | rpe | 1370 / 149 | 177.1s | 31.38 MB | ✓ |
| 17799_HELP!!.pez | rpe | 856 / 24 | 187.2s | 1.54 MB | ✓ |
| 30220_モニタリング.pez | rpe | 1006 / 24 | 175.3s | 2.18 MB | ✓ |
| 54065_Leave it to Fate.pez | rpe | 1439 / 41 | 201.9s | 8.63 MB | ✓ |
| 55205_Alea jacta est!.pez | rpe | 1361 / 44 | 133.7s | 3.04 MB | ✓ |
| **6954_-1.pez** | **pec** | **1250 / 30** | **144.7s** | **0.14 MB** | **✓（本次修复）** |
| 77769_big song (self insert).pez | rpe | 7741 / 741 | 694.1s | 46.72 MB | ✓ |
| 81150_宿命邀约 (NEMESIS).pez | rpe | 1326 / 1 | 117.8s | 0.41 MB | ✓ |
| 9668_The Last Page.pez | rpe | 1189 / 74 | 141.9s | 6.30 MB | ✓ |
| AvataarReincarnationofKalpa_IN.zip | pgr | 979 / 24 | 169.3s | 5.09 MB | ✓ |
| CROSSSOUL_IN.zip | pgr | 1305 / 6 | 132.0s | 1.03 MB | ✓ |
| Cyberneko Pulse (2).pez | rpe | 3092 / 53 | 125.9s | 10.87 MB | ✓ |
| DeadSoul_IN.zip | pgr | 787 / 10 | 140.0s | 1.25 MB | ✓ |
| DistortedFate_IN.zip | pgr | 1449 / 24 | 157.2s | 8.15 MB | ✓ |
| ERABYECONNEC10N_IN.zip | pgr | 1530 / 24 | 160.7s | 6.73 MB | ✓ |
| Hardsync.pez | rpe | 1739 / 56 | 163.1s | 12.82 MB | ✓ |
| PANICPARADISE_IN.zip | pgr | 881 / 23 | 127.2s | 4.99 MB | ✓ |
| PRAGMATISMRESURRECTION_IN.zip | pgr | 999 / 24 | 155.2s | 11.52 MB | ✓ |
| ReEndofaDream_IN.zip | pgr | 1186 / 22 | 140.5s | 4.59 MB | ✓ |
| Verruckt_Raimukun_IN.zip | pgr | 1285 / 24 | 117.7s | 7.92 MB | ✓ |
| 夢の降る日に_IN.zip | pgr | 1243 / 22 | 125.4s | 4.19 MB | ✓ |
| 尊師TheGuru_IN.zip | pgr | 738 / 5 | 123.2s | 0.45 MB | ✓ |
| 推活天使ビビアンちゃん .pez | rpe | 2800 / 105 | 233.0s | 13.55 MB | ✓ |
| 玩具狂奏曲終焉_IN.zip | pgr | 1418 / 24 | 150.9s | 7.15 MB | ✓ |
| 琵琶曲DJ(1.15x).pez | rpe | 34211 / 1 | 144.5s | 13.32 MB | ✓ |
| 祈-我ら神祖と共に歩む者なり-_IN.zip | pgr | 2222 / 24 | 172.9s | 11.32 MB | ✓ |

```
合计 30 首：成功 30，失败 0
```

**修复前 → 修复后**：`6954_-1.pez` 从
`✗ JsonSyntaxException: MalformedJsonException …`（失败清单 1 条）变成 `✓ [pec] 1250 音符`。

---

## 4. 编译与回归

```
gradle -p C:\dsh-work\phimc-phira\mod ":1.21.1:build" --no-daemon --console=plain --offline
> Task :1.21.1:compileJava
> Task :1.21.1:jar
> Task :1.21.1:remapJar
> Task :1.21.1:assemble
> Task :1.21.1:build
BUILD SUCCESSFUL in 18s          # gradle_exit=0，0 error / 0 warning
```

产物 `phimc-1.21.1.jar` = 629486 字节，已覆盖
`D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`（两边 SHA-256 一致：
`C48A2FCB…51497707`）。

StressTest（`ChartParser` 改过，必须回归）：

| 谱面 | 自动播放 | 不操作 |
|---|---|---|
| pkg1（1285 音符 / 24 线） | **1000000 PHI，P=1285 G=0 B=0 M=0** | 0 分，M=1285/1285 |
| pkg2（1418 音符 / 24 线） | **1000000 PHI，P=1418 G=0 B=0 M=0** | 0 分，M=1418/1418 |

±60ms 抖动 509829 / 341844，与改动前逐位一致。

---

## 5. 没做与原因

1. **偏移没有做「自动校准」**（跟着音乐打几拍自动算）。做的话要新增一个校准界面 + 采集判定偏差 +
   统计，属于新功能；这次只做手动 ±300ms。
2. **偏移是全局的**（`profile.json` 一份），不做「每首歌单独偏移」。需求只要求一个设置项；
   要按曲存的话把 key 换成 `chartId` 即可，但存档结构要改。
3. **偏移只作用于谱面时间轴**（`Session.seconds()`），不改打击音 / 音频播放本身 —— 音画同步补偿的
   标准做法就是挪时间轴，挪音频反而会让打击音也跟着偏。
4. **PEC 的缓动是近似的**：`cm/cr/cf` 的 tween 编号走了和 RPE 同一张表，但 PEC 的事件在 prpr 里是
   「keyframe 序列 + 段内 easing」，我们映射成 `Chart.Ev` 的单段缓动；多条事件重叠时的裁剪照
   prpr 的 `sanitize_events`（起点裁到上一段结尾）。阶数高的缓动（elastic/back）可能与 prpr 有
   细微差别，**音符时间与判定不受影响**（只影响判定线动画）。
5. **PEC 的 `#`/`&` 只修饰「上一条音符」**（和 prpr 一样）；如果某个编辑器把 `#`/`&` 单独写在
   别的行、且顺序被打乱，那一条会落到前一个音符上 —— 这是格式本身的语义，不是我们的偏差。
6. **PEC 的 hold 结束高度没有单独存**：我们的 `Chart.Note` 没有 `endHeight` 字段，hold 尾高度在
   渲染时按 `heightAt(speed, endTime)` 求（和 PGR 同一条路），PEC 走同一条，不影响判定。
7. **大谱面（>20MB）没做流式解析**：`JsonParser` 仍会建完整对象树（46.7MB 的谱面实测 171ms、
   Xmx3g 下没问题）。要做流式得重写解析器，收益只在极少数超大谱面上，这次只加警告 + OOM 兜底。
8. **没在真客户端里点设置页 / 玩 PEC 那首**：本机没有可交互客户端。偏移的链路是
   「Profile 字段 → Session 公式 → 设置页三处读写」，代码级核对 + 编译；PEC 是 headless 用真代码
   解析出的音符数 / 时长 / 坐标与源文件对账（见 2.3），加上与 PGR 同一条 `heightAt` / 判定路径。
9. **解析失败清单只有命令行工具，没有做进游戏界面**：需求写的是「headless 跑一遍」；
   游戏里失败时给的是「原因摘要 + 日志」。
