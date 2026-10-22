# REPORT：把 PhiMC 改造成双端模组（同一个 jar 装专用服务端）

> 目标：`phimc-1.21.1.jar` 既能装客户端，也能装服主开的专用服务端；物品 / 配方 / 战利品 /
> 开箱赋谱 / 存档在服务端真正生效。先只做 1.21.1，验证通过后再铺 19 版。

## 结论（先看这个）

| 项 | 结果 |
|---|---|
| 专用服务端启动 | ✅ `Done (1.797s)`，模组 `phimc 0.1.0` 以 `Env=SERVER` 加载 |
| 入口 | ✅ `environment: "*"` + `main: ["dev.dsh.phimc.PhiMCMod"]`，`client` 入口保留 |
| 物品 / 音效 / 创造页签 | ✅ 6 个物品 + `phimc:main` 页签在服务端注册成功 |
| 芯片战利品 | ✅ `[PhiMC] 芯片战利品已注册`，43 张原版箱子表注入成功（试炼场按设计跳过） |
| 开箱赋谱 | ✅ 服务端曲库扫描 `找到 2 首谱面`，`END_SERVER_TICK` 赋谱链路可跑 |
| 服务端存档 | ✅ `world/phimc/profile.json`（含 worldSeed）落盘，`world/phimc/records.tsv` 就位 |
| 崩溃 / NoClassDefFoundError | ✅ 0 次（日志 0 条 `] ERROR]`，0 次 `NoClassDefFoundError`） |
| 客户端编译 | ✅ `:1.21.1:build --offline` BUILD SUCCESSFUL |
| 客户端回归 | ✅ StressTest：1000000 / PHI / 0 Miss；不操作 2500/2500 Miss |
| 判定 / 计分 | ✅ 一行未改（`Engine` / `Judgement` / `Session` 的判定路径未动） |

---

## 1. 入口拆分后的文件结构

### 1.1 新增

| 文件 | 行数 | 作用 |
|---|---|---|
| `mod/src/main/java/dev/dsh/phimc/PhiMCMod.java` | 59 | **`main` 入口**（`ModInitializer`）。`:51 onInitialize` → `:57 PhiMCRegistry.register()`、`:65 ChartPool.scanServer()`、`:67 ServerSurvival.attach()`；零客户端依赖 |
| `mod/src/main/java/dev/dsh/phimc/ChartPool.java` | 135 | 全进程共用的谱面 id 候选集。`:58 setSource`（客户端装 `Session::chartIds` / 服务端装扫描快照）、`:63 ids()`、`:89 scanServer()`、`:117 serverSongsDirs()` |
| `mod/src/main/java/dev/dsh/phimc/ItemHooks.java` | 66 | 物品右键的「客户端动作」桥。物品类只调这里，真正界面动作由客户端入口装进来 |
| `mod/src/main/java/dev/dsh/phimc/survival/ServerSurvival.java` | 104 | 专用服务端玩法存档（`<服务端目录>/world/phimc/`）。`:51 attach`、`:89 profile()` |
| `mod/src/main/java/dev/dsh/phimc/client/ClientLocalPlayer.java` | 38 | `SurvivalEnv.LocalPlayer` 的客户端实现（本机玩家 UUID → 集成服务端玩家；`server()` 兜底 = `mc.getServer()`） |
| `mod/src/main/java/dev/dsh/phimc/client/ClientItemActions.java` | 83 | 物品右键的客户端实现（开曲库 / 平板赠品 / 重打上一首 / 芯片解锁 + 动作条提示） |

### 1.2 修改

| 文件 | 位置 | 改了什么 |
|---|---|---|
| `mod/src/main/resources/fabric.mod.json` | `:10-14` | `"environment": "client"` → `"*"`；新增 `"main": ["dev.dsh.phimc.PhiMCMod"]`，保留 `"client"` |
| `mod/src/main/java/dev/dsh/phimc/PhiMCRegistry.java` | `:81` / `:124` / `:128` | `register()` 幂等保护；候选集从 `client.Session` 改成 `ChartPool::ids`（**删掉对 client 包的唯一引用**）；物品注册日志标注「客户端 / 专用服务端通用」 |
| `mod/src/main/java/dev/dsh/phimc/client/PhiMCClient.java` | `:49` / `:51` / `:53` / `:68` | 不再调 `PhiMCRegistry.register()`；改为安装双端「能力」：`ItemHooks.install`、`SurvivalEnv.installLocalPlayer`、`ChartPool.setSource(session::chartIds)` |
| `mod/src/main/java/dev/dsh/phimc/survival/SurvivalEnv.java` | 整文件（161 行） | 去掉 `MinecraftClient`。改为服务端门面：`bindServer/unbindServer`（`SERVER_STARTED` 时绑）、`server()`（绑定值优先，取不到时问 `LocalPlayer` 桥 → 单人等价于旧的 `mc.getServer()`）、`serverPlayer()`、`nowTicks()`、`saveRoot()` |
| `mod/src/main/java/dev/dsh/phimc/survival/Rewards.java` | `:129` / `:144` | `SurvivalEnv.server()` 取代 `MinecraftClient.getServer()`；发奖仍在 `server.execute(...)` 的服务端线程完成，客户端只显示结算 |
| `mod/src/main/java/dev/dsh/phimc/survival/Wallet.java` | `:35` / `:104` / `:134` | 新增 `authoritative(player)`：服务端玩家 → 自己；单人 → 集成服务端玩家；否则本地背包。`pay` / `countFor` 的口径统一走它 |
| `mod/src/main/java/dev/dsh/phimc/survival/ChipLoot.java` | `:89-92` / `:122-123` / `:133` | 候选集改 `ChartPool.ids()`；类注释从「只在客户端」改成「双端」 |
| `mod/src/main/java/dev/dsh/phimc/item/ChartPlayerItem.java` | `:39` | `PhiMCClient.openSongSelect` → `ItemHooks.openChartPlayer`（去 `MinecraftClient`） |
| `mod/src/main/java/dev/dsh/phimc/item/TabletItem.java` | `:48` / `:50` | `PhiMCClient.replayLast/openTablet` → `ItemHooks.*`；赠品逻辑移到 `ClientItemActions` |
| `mod/src/main/java/dev/dsh/phimc/item/ChipItem.java` | `:265` / `:268` | 客户端分支走 `ItemHooks.useChartChip`；**新增服务端分支**：专用服务端右键芯片把解锁写进 `ServerSurvival.profile()`（单人时该 profile 为 null，行为不变） |
| `mod/src/main/java/dev/dsh/phimc/egg/FusionEgg.java` | `:27-32` | 注释改成双端现实（原来写「专用服务器根本不加载」） |
| `README.md` | `:74-75` / `:99-117` / `:131-132` | 「在别人的服务器」口径更新；新增「方式三：服主开服」章节 |

### 1.3 没动的

- `build.gradle` / `stonecutter.gradle` / `settings.gradle`：构建链一行没改。
- 判定与计分：`core/Engine.java`、`core/Judgement.java`、`core/ChartParser.java`、`Session` 的判定/结算路径未改。
- `mod/lite-resources/fabric.mod.json`（打歌版）：仍是 `environment: "client"`、**没有** `main` 入口 →
  `phimcplayer-1.21.1.jar` 依旧一个物品都不注册（jar 内描述文件已复核）。
- `mod/versioned-resources/**`：配方 / 战利品表 JSON 未改。

### 1.4 入口调用顺序（为什么客户端行为不变）

Fabric Loader 先跑 `main` 入口、后跑 `client` 入口：

1. `PhiMCMod.onInitialize()` → 注册物品 / 音效 / 页签 / 战利品注入 / 彩蛋 tick（客户端以前也是这样注册的，只是位置换了）；
2. `PhiMCClient.onInitializeClient()` → 配置目录迁移、`Session`（曲库/音频/进度）、键位、HUD、材质包、在线库；
   并把 `ItemHooks` / `LocalPlayer` / `ChartPool` 三个「能力」装给双端代码。

所以单人路径上「谁在什么时候被注册、被调用」与改造前一一对应。

---

## 2. `PhiMCMod` 依赖清理证据

### 2.1 javap：开发期 classes（yarn 名，`net/minecraft/client/` 直接可读）

对 25 个双端/服务端类逐个 `javap -v -p`，统计常量池里 `net/minecraft/client/*` 的出现次数：

```
dev.dsh.phimc.PhiMCMod                             mc-client-refs=0
dev.dsh.phimc.PhiMCRegistry                        mc-client-refs=0
dev.dsh.phimc.ChartPool                            mc-client-refs=0
dev.dsh.phimc.ItemHooks                            mc-client-refs=0
dev.dsh.phimc.egg.FusionEgg                        mc-client-refs=0
dev.dsh.phimc.survival.SurvivalEnv                 mc-client-refs=0
dev.dsh.phimc.survival.ServerSurvival              mc-client-refs=0
dev.dsh.phimc.survival.ChipLoot                    mc-client-refs=0
dev.dsh.phimc.survival.Profile                     mc-client-refs=0
dev.dsh.phimc.survival.Rewards                     mc-client-refs=0
dev.dsh.phimc.survival.Wallet                      mc-client-refs=0
dev.dsh.phimc.survival.Shop                        mc-client-refs=0
dev.dsh.phimc.survival.TabletGift                  mc-client-refs=0
dev.dsh.phimc.survival.PriceTable                  mc-client-refs=0
dev.dsh.phimc.survival.RewardTable                 mc-client-refs=0
dev.dsh.phimc.item.ChartPlayerItem                 mc-client-refs=0
dev.dsh.phimc.item.TabletItem                      mc-client-refs=0
dev.dsh.phimc.item.ChipItem                        mc-client-refs=0
dev.dsh.phimc.item.BlankChipItem                   mc-client-refs=0
dev.dsh.phimc.client.SongEntry                     mc-client-refs=0
dev.dsh.phimc.client.SongMeta                      mc-client-refs=0
dev.dsh.phimc.client.ChartMeta                     mc-client-refs=0
dev.dsh.phimc.client.Records                       mc-client-refs=0
dev.dsh.phimc.client.WorldStore                    mc-client-refs=0
dev.dsh.phimc.client.ConfigDir                     mc-client-refs=0
```

`PhiMCMod` 的常量池里只有（`javap -v` 原样摘录）：

```
#106 = Utf8  Lnet/minecraft/server/MinecraftServer;
#32  = Class dev/dsh/phimc/PhiMCRegistry
#59  = Class dev/dsh/phimc/survival/ServerSurvival
#64  = Class dev/dsh/phimc/survival/SurvivalEnv
#79  = Class dev/dsh/phimc/ChartPool
```

### 2.2 javap：发布 jar（intermediary 名，更接近服主实际加载的东西）

对 `phimc-1.21.1.jar` 里同一批 22 个类检查 `net/minecraft/client/*` 与客户端 intermediary id
（`class_310` = MinecraftClient、`class_437` = Screen、`class_332` = DrawContext、`class_746` = ClientPlayerEntity，
正则带 `(?!\d)` 边界，避免 `class_3324` 这种前缀误命中）：

```
checked=22  client-ref-hits=0
--- 对照：dev.dsh.phimc.client.PhiMCClient（客户端入口，预期命中）---
net/minecraft/class_310, net/minecraft/class_332, net/minecraft/class_437
```

即：**服务端路径上的每一个类**（含放在 `client` 包里但纯 `java.nio` 的
`SongEntry` / `SongMeta` / `ChartMeta` / `Records` / `WorldStore` / `ConfigDir`）都没有客户端类引用；
唯一引用 `MinecraftClient/Screen/DrawContext` 的是 `PhiMCClient`，它只在 `client` 入口被加载。

### 2.3 运行时证据

专用服务端日志里 `Env=SERVER`、`[PhiMC] 模组已加载（main 入口）· 环境 SERVER`，
物品注册 / 战利品注入 / 开箱赋谱 / 彩蛋全部跑完，全程 **0 次** `NoClassDefFoundError`、**0 条** `ERROR`。

### 2.4 设计取舍：为什么没把扫描器搬进 `core`

`SongEntry`（和它用到的 `SongMeta` / `ChartMeta`）实测**零 Minecraft 依赖**：只有 `java.nio` /
`java.util.zip` / gson / slf4j（2.1 的 javap 已证）。所以服务端直接复用同一份扫描逻辑，
而不是在 `core` 里再写一个「解析器」——info.txt 字段、RPE 包文件名启发式、ZIP 编码回退
（GBK/Shift_JIS/ISO-8859-1）都只在这一个类里，复制一份迟早会走偏。
同理，`Records` / `WorldStore` / `ConfigDir` 也直接复用（纯 `java.nio`），
`ServerSurvival` 与客户端 `Session` 因此用的是**同一套路径规则**。

---

## 3. 服务端启动日志关键行

```
[13:07:34] (FabricLoader) ... Service=Knot/Fabric Env=SERVER
[13:07:44] [main/INFO] (phimc) [PhiMC] 模组已加载（main 入口）· 环境 SERVER
[13:07:44] [main/INFO] (phimc) [PhiMC] 芯片战利品已注册：phimc:chests/chart_chip / phimc:chests/chart_chip_rare
[13:07:44] [main/INFO] (phimc) [PhiMC] 彩蛋已就绪：大肥鱼 + Phira 相距 1.5 格内 → 谱面播放器
[13:07:44] [main/INFO] (phimc) [PhiMC] 已注册物品 phimc:chart_player / phimc:tablet / phimc:blank_chip / phimc:chart_chip / phimc:big_fish / phimc:phira 与创造页签 phimc:main（客户端 / 专用服务端通用）
[13:07:51] [Server thread/INFO] (Minecraft) Done (1.797s)! For help, type "help"
[13:07:52] [Server thread/INFO] (phimc) [PhiMC] 服务端扫描曲库 .\config\phimc\songs → 2 首
[13:07:52] [Server thread/INFO] (phimc) [PhiMC] 找到 2 首谱面（服务端曲库：.\config\phimc\songs）
[13:07:52] [Server thread/INFO] (phimc) [PhiMC] 服务端玩法存档：world\phimc（世界种子 -1569752040955345742，已写入）
[13:07:52] [Server thread/INFO] (phimc) [PhiMC] 服务端成绩表：world\phimc\records.tsv
```

统计（`runserver.log`）：

- `NoClassDefFoundError`：0
- `] ERROR]`：0
- 注入的原版箱子战利品表：43 张（`trial_chambers/*` 按原设计跳过）
- `crash-reports/`：本次运行没有新增（目录里唯一的文件是「端口被占用」那次尝试留下的）

服务端存档实测：

```
run/world/phimc/profile.json
{
  "worldSeed": -1569752040955345742,
  "unlocked": [],
  "records": {},
  "shopSeed": 0,
  "shopRefreshAt": 0,
  "tabletGiftGiven": false
}
```

**第二次启动**（同一个 run 目录，验证按世界隔离 + 不重写种子）：

```
[13:13:21] (Minecraft) Done (1.456s)! For help, type "help"
[13:13:21] (phimc) [PhiMC] 找到 2 首谱面（服务端曲库：.\config\phimc\songs）
[13:13:21] (phimc) [PhiMC] 服务端玩法存档：world\phimc（世界种子 -1569752040955345742，沿用存档）
[13:13:21] (phimc) [PhiMC] 服务端成绩表：world\phimc\records.tsv
```

### 复现命令

```powershell
# 1) 源码同步到纯 ASCII 工作目录（仓库路径含中文，Gradle native-platform 会挂）
robocopy <仓库> C:\dsh-work\phimc /MIR /XD out .gradle build fixtures dist
$env:JAVA_HOME='C:\dsh-tools\jdk25'; $env:GRADLE_USER_HOME='C:\dsh-gradle-home'

# 2) 构建（离线，0 错误）
gradle -p C:\dsh-work\phimc\mod :1.21.1:build --offline --no-daemon --console=plain

# 3) 跑专用服务端
#    run 目录 = C:\dsh-work\phimc\mod\versions\1.21.1\run
#    eula.txt: eula=true
#    server.properties: server-port=25575（本机 25565 被其它服务端占用）
#    config/phimc/songs/ 里放谱面包
gradle -p C:\dsh-work\phimc\mod :1.21.1:runServer --offline --no-daemon --console=plain
#   看到 "Done (" + "[PhiMC] 找到 N 首谱面" 后 taskkill /F /T 掉进程
```

> 注：`runServer` 第一次需要 `net.fabricmc:fabric-log4j-util:1.0.2`（`runClient`/构建不用它），
> 离线跑会报 `No cached version ... available for offline mode`。联网跑一次把它拉进
> `GRADLE_USER_HOME` 之后，后续 `runServer` 就能 `--offline`。
> 另外：`robocopy /MIR` 会把 `versions/1.21.1/run` 一起删掉（源里没有这个目录），
> 所以 `eula.txt` / `server.properties` / `config/phimc/songs` 要在同步**之后**再放。

---

## 4. 客户端回归

### 4.1 编译

```
gradle -p C:\dsh-work\phimc\mod :1.21.1:build --offline --no-daemon --console=plain
BUILD SUCCESSFUL in 46s   (12 actionable tasks: 6 executed, 6 up-to-date)
```

产物：

- `mod/versions/1.21.1/build/libs/phimc-1.21.1.jar`（713,686 B）→ 已覆盖
  `mod/dist/phimc-1.21.1.jar` 与 `D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`
- `phimcplayer-1.21.1.jar`（708,310 B）→ 已更新 `mod/dist/`

jar 内 `fabric.mod.json` 复核：

```json
"environment": "*",
"entrypoints": {
  "main":   ["dev.dsh.phimc.PhiMCMod"],
  "client": ["dev.dsh.phimc.client.PhiMCClient"]
}
```

### 4.2 StressTest（core 与 mod 共用同一份源码）

```
谱面：2500 音符 / 24 判定线 / 141.1s
[自动播放] 分数=1000000 评级=PHI P=2500 G=0 B=0 M=0 最大连击=2500/2500
[不操作]   分数=0 P=0 M=2500/2500（Drag 不再自动判，1002 个 Drag 一并 Miss）
[带 ±60ms] 分数=395690 评级=F P=934 G=225 B=30 M=1311 最大连击=170/2500
```

### 4.3 客户端行为不变的理由（逐条对照）

| 原行为 | 现在 |
|---|---|
| 物品右键 → 曲库 / 平板 / 重打 | 客户端分支调 `ItemHooks.*` → `ClientItemActions`（代码逐行搬过去） |
| 新平板赠品 2 首 | 同上（`giveTabletGift` 原样搬移） |
| 芯片右键解锁 + 动作条提示 | 客户端分支照旧写客户端 profile；服务端分支只在专用服务端生效 |
| 开箱赋谱候选集 | `ChartPool` 来源 = `session::chartIds`（动态，rescan 后立刻生效） |
| 结算奖励 | 单人：`server.execute` 在集成服务端线程发；客户端只画结算行 |
| 玩法进度目录 | 单人仍是 `saves/<世界名>/phimc/`（`WorldHooks` 的 JOIN 路径没改） |

> 客户端回归按需求只做「`build` + StressTest」：没有跑 `:1.21.1:runClient`（需要图形界面，
> 属于人工验证项）。若要在本机复核，`gradle :1.21.1:runClient`，进单人世界打一局即可
> （日志里应能看到 `[PhiMC] 玩法进度已切换到 ...` 与 `[PhiMC] 结算奖励：...`）。

---

## 5. 服务端还剩哪些功能不可用（与原因）

| 功能 | 专用服务端 | 原因 |
|---|---|---|
| 打歌 / 曲库 / 设置 / 结算 / HUD | ❌ 不可能 | 服务端无渲染、无 `Screen`/`DrawContext`、无音频输出；这些代码在 `client` 包，只由 `client` 入口加载 |
| 结算奖励（发物品） | ❌ 仍不发 | 客户端打完一局的结果只在本机，**没有「结果包」把它发给服务端**；`Rewards` 的硬规则（`SurvivalEnv.serverPlayer() == null` 不发）保持不变。要开这个口子需要新增自定义 payload + 服务端校验（分/ACC/谱面 id 都可能被改包），属于下一步 |
| 商店扣款 / 平板赠品 / 芯片「客户端那份」解锁 | ⚠️ 只在本机生效 | 这些入口是界面（客户端）驱动的，写的是客户端 profile；专用服务端侧只有「芯片右键解锁」一条写了服务端存档。要真正权威需要购买/解锁包 |
| 客户端 ↔ 服务端进度同步 | ❌ 未实现 | 服务端有自己的 `world/phimc/profile.json`，客户端有 `config/phimc/servers/<地址>/profile.json`，两边各写各的（与改造前「多人按服务器地址落盘」一致）。要让服主权威 + 客户端展示，需要同步包 |
| 在线谱面库（Phira 搜索 / 下载） | ❌ 不需要 | 纯客户端功能；服务端没有网络请求也没有界面 |
| 打歌版 `phimcplayer` | ❌ 不适用 | 仍是纯客户端 jar（`environment: client`、无 `main` 入口） |

**在服务端真正生效的**（本次交付的核心）：

1. 物品 / 音效 / 创造页签注册（服务端认得这些 id，可 `/give`、可合成）；
2. 配方（`data/phimc/recipe/*` 双端一致）；
3. 原版箱子战利品注入（43 张表）+ 开箱那一刻赋谱（候选集 = 服务端 `config/phimc/songs`）；
4. 大肥鱼 + Phira 融合彩蛋（`END_WORLD_TICK`）；
5. 服务端玩法存档 `world/phimc/profile.json`（世界种子已写入）/ `records.tsv` 目录；
6. 芯片右键把解锁写进服务端存档（专用服务端分支）。

---

## 6. 铺 19 个版本时的注意事项

- 本次只验证了 `1.21.1`；`build.gradle` / 版本条件注释没有改动，其余 18 个版本理论上只需
  `gradle :<版本>:build`（`PhiMCMod` 只用了 `ServerLifecycleEvents`、`EnvType`、
  `MinecraftServer#getSavePath`、`WorldSavePath`，这些在 1.20 ~ 1.21.11 全系都有）。
- 唯一需要逐版本确认的是 `item/*` 里被包在 `//? if >=1.21.2` 里的 `use()` 签名（原样保留了条件分支）。
- 1.20.x（`<1.21`）的 tooltip 分支里还有 `net.minecraft.client.item.TooltipType/TooltipContext`
  的**注释掉的** import —— 那是版本条件代码，1.21.1 不编译它；铺 1.20.x 时要按原逻辑走
  （这些是 tooltip 相关、不参与服务端启动路径，但如果要严格「1.20 服务端也零客户端类」，
  需要把 `appendTooltip` 的实现也过一遍）。
