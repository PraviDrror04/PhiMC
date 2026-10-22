# REPORT —— 独立「打歌 only」模组（phimcplayer）

> 目标：在同一份源码树里用**构建变体**产出第二个 jar `phimcplayer-<MC>.jar`
>（mod id `phimcplayer`，名字 `PhiMC Player` / PhiMC 打歌版），只做一件事：
> `Shift + /` 打开谱面播放器 → 选歌 → 打歌 → 成绩存本地。
> **不注册物品、不读配方/战利品、不发奖励、不碰世界。**
>
> 先只做 1.21.1；判定 / 计分一行未改。

---

## 1. 构建变体的实现方式

| 位置 | 作用 |
|---|---|
| `mod/build.gradle:152-153` | `liteMcVersions = ['1.21.1']` + `liteEnabled`：**哪些 MC 版本产出 lite 变体**（加一个版本号就铺开，其余 18 版构建不受影响） |
| `mod/build.gradle:156-157` | `modDir = rootProject.projectDir`、`liteDescriptorDir = mod/lite-resources`（Stonecutter 子项目的 `projectDir` 是 `mod/versions/<MC>/`，这里必须用根项目目录） |
| `mod/build.gradle:164-187` | `liteJar`（`Jar`）：`archiveBaseName = 'phimcplayer'`（169）；内容 = **已 remap 的完整版 jar 原样**（174-178，只 `exclude 'fabric.mod.json'` 与 `MANIFEST.MF`）+ lite 描述文件（180-186，同一套 `expand` 占位符） |
| `mod/build.gradle:191-195` | `liteDist`（`Copy`）→ `mod/dist/`（成品目录；删掉 jar 重跑 build 会重拷） |
| `mod/build.gradle:197-199` | `assemble` 依赖 `liteJar` + `liteDist` ⇒ **`gradle :1.21.1:build` 一次就同时产出两个 jar** |
| `mod/lite-resources/fabric.mod.json` | lite 的描述文件（不在任何 sourceSet 里，只被 `liteJar` 用） |
| `mod/build.ps1:59-65` | 19 版本循环里，若该节点产出了 `phimcplayer-<MC>.jar` 也一起拷进 `mod/dist/` |
| `mod/install.ps1:121-127` | 装完整版时**顺手删掉** `mods/phimcplayer-*.jar`（二选一的执行点） |

**为什么不用「自己再 remap 一遍」**：Loom 只 remap 一次（`remapJar`）。lite 的 class 直接从那个
产物里取，所以两个 jar 的 class **逐字节相同**，不存在 named / intermediary 混用（已实测，见 §5）。

**为什么是构建变体而不是同 jar 里的开关**：lite 的入口 `PhiMCLiteClient` 在
`onInitializeClient()` 里**不调用** `PhiMCRegistry.register()` —— 物品在那次启动里根本没进注册表。
（同 jar 开关永远是「代码在、只是不执行」，不满足「独立的打歌 only 模组」。）

---

## 2. lite jar 的 `fabric.mod.json`

`mod/lite-resources/fabric.mod.json`，构建时展开占位符，**jar 内实际内容**（`unzip -p phimcplayer-1.21.1.jar fabric.mod.json`）：

```json
{
  "schemaVersion": 1,
  "id": "phimcplayer",
  "version": "0.1.0",
  "name": "PhiMC Player",
  "description": "PhiMC 打歌版（纯客户端）：Shift + / 打开曲库，选歌、打歌，成绩保存在本地。不注册任何物品、不读配方/战利品、不发奖励、不碰世界。与完整版 phimc 二选一安装。",
  "authors": ["dsh"],
  "license": "MIT",
  "icon": "assets/phimc/icon.png",
  "environment": "client",
  "entrypoints": {
    "client": ["dev.dsh.phimc.client.PhiMCLiteClient"]
  },
  "breaks": {
    "phimc": "*"
  },
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "1.21.1",
    "java": ">=21",
    "fabric-api": "*"
  }
}
```

- `id = phimcplayer`（完整版是 `phimc`）、`name = PhiMC Player`、`entrypoints.client = dev.dsh.phimc.client.PhiMCLiteClient`。
- `breaks: {"phimc": "*"}`：**同时装两个 jar 时 Fabric Loader 直接拒绝启动**，把「二选一」变成硬约束
  （两个 jar 的 class 名完全重复，同时装本来就会互相覆盖）。
- 完整版描述文件（`src/main/resources/fabric.mod.json`）**没有改动**：仍是 `id=phimc`、
  `entrypoints.client=[dev.dsh.phimc.client.PhiMCClient]`。

入口类 `PhiMCLiteClient`（`mod/src/main/java/dev/dsh/phimc/client/PhiMCLiteClient.java`）：

| 行 | 内容 |
|---|---|
| 58 | `PhiMC.onlyMode = true`（**第一件事**：所有 UI / 结算收口都读它） |
| 63 | `PhiMCRegistry.registerHitSounds()` —— 只注册 3 个打击音 SoundEvent（`PhiMCRegistry.java:137-146`），**不调用** `register()` |
| 66-78 | `ConfigDir` 迁移 → `new Session(songsDir)` → `PhiMCClient.installSession(session)` → `WorldHooks.register(session)` |
| 83-99 | 新键位 `key.phimc.player`，默认 `GLFW.GLFW_KEY_SLASH`（分类沿用 `category.phimc`；1.21.11 用 `KeyBinding.Category`） |
| 100-111 | `END_CLIENT_TICK`：`while (key.wasPressed()) if (shiftDown(client)) openPlayer();` + `if (!(currentScreen instanceof PlayScreen)) session.tick();`（与 `PhiMCClient` 一致） |
| 114-119 | `HudRenderCallback` → `HudRenderer.render(ctx, session)`（与 `PhiMCClient` 一致） |
| 138-144 | `openPlayer()` → `new SongSelectScreen(true)`（播放器模式：全部可玩） |
| 154-161 | `shiftDown()`：1.21.1 走 `Screen.hasShiftDown()`；**1.21.9+** 走 `InputUtil.isKeyPressed(Window, LEFT/RIGHT_SHIFT)`（1.21.9 起 `Screen.hasShiftDown()` 已被删除 —— 用 `javap` 逐个核对过 19 个版本的映射 jar，见下） |

> `PhiMCClient.installSession(...)`（`PhiMCClient.java:225-233`）让 lite 复用**同一份**界面 / 引擎代码：
> `SongSelectScreen` / `PlayScreen` / `SettingsScreen` 都读 `PhiMCClient.session()`，不必为 lite 复制一套。

---

## 3. `onlyMode` 收口点清单

标志本体：`mod/src/main/java/dev/dsh/phimc/PhiMC.java:43`（`public static volatile boolean onlyMode`，
`PhiMC.java:49` 提供只读入口）；置位点 `PhiMCLiteClient.java:58`。

### 3.1 选曲界面 `client/SongSelectScreen.java`

| 行 | 收口 |
|---|---|
| 409 | 构造函数：`backTo = PhiMC.onlyMode ? null : backTo` —— 从平板 / 商店进来的「来源界面」直接丢掉 |
| 617 | `init()`：锁定筛选归零（`LockFilter.ALL`） |
| 786 / 990 | 「锁定：全部」按钮在 lite 下变成不可点的说明按钮「全部可玩」 |
| 795 | **隐藏「拷芯片」**：`!PhiMC.onlyMode` 是 `copy.visible` 的一个必要条件（连按钮都不显示） |
| 1013-1019 | `cycleLockFilter()` 在 lite 下不切换，只给一行提示 |
| 1209-1210 | `isLocked()`：`unlockAll || onlyMode` ⇒ **全部谱面可玩** |
| 1218 | `trulyUnlocked()`：`onlyMode` ⇒ `false` —— **不读解锁数据**（`Profile.unlocked` 一个字节都不看） |
| 1276 | `copyRow()` 入口直接 return（芯片物品是 `null`，这条路径永不执行） |
| 1647-1653 | **副标题**：lite 下显示「共 N 首 · 第 x/y 页 · **纯客户端 · 成绩保存在本地** · ↑↓/滚轮/点击切换 · 回车开始 · Esc 返回」（不再出现「未解锁的谱面不能开始」「去商店」） |
| 1941-1945 | 详情「状态」列：lite 下写「可玩」（不再是「已解锁（播放器）」） |

### 3.2 结算 / 奖励

| 位置 | 收口 |
|---|---|
| `client/Session.java:921-926` | `finish()`：`if (!PhiMC.onlyMode) Rewards.grantOnFinish(...)` —— lite **绝不调用** `grantOnFinish` |
| `survival/Rewards.java:113-130` | **硬规则**（与 onlyMode 无关，两个变体都遵守）：`SurvivalEnv.serverPlayer() == null` ⇒ 记日志 + 清 `lastReward` + `return List.of()`，**一件不发** |
| `client/PlayScreen.java:947-949` | 结算界面：lite 下那一行固定写「纯客户端 · 成绩已存本地」（不读 `Rewards.lastReward()`） |

> 为什么需要 `serverPlayer() == null` 这条兜底：本 mod 的物品只注册在客户端，专用服务器上没有这些注册，
> 在多人里发东西 = 「客户端看起来塞进去了，几 tick 后被服务端同步覆盖」，比不发更糟。
> 之前的分支是 `server != null && serverPlayer != null`，否则**直接改客户端背包** —— 那条已被删除。

### 3.3 平板 / 物品链路的入口层断开

| 位置 | 收口 |
|---|---|
| `client/PhiMCClient.java:139-145` | `openTablet()`：`if (PhiMC.onlyMode) return;`（lite 里 `PhiMCRegistry.TABLET == null`，进来就 NPE） |
| `client/TabletScreen.java:91-94` | `init()` 第一行：`if (onlyMode) { close(); return; }` —— 打开即关闭，**界面里任何一行代码都不执行** |
| `PhiMCRegistry.java:77` + `137-146` | `register()` 里改为调用 `registerHitSounds()`；lite 只调后者（不注册物品 / 页签 / `ChipLoot` / `FusionEgg`） |

### 3.4 成绩落点（lite 也能「存本地」）

| 位置 | 收口 |
|---|---|
| `client/Session.java:205-207` | 构造时（还没进过世界、在主菜单打歌）先落 `config/phimc/servers/local/` |
| `client/Session.java:216-229` | `attachOnlyModeLocal()`（`Profile.attachWorld(file, false)` + `Records.open(file)`） |
| `client/Session.java:240-250` | `onWorldJoin(root == null)`：lite 落 local，完整版保持「只读」 |
| `client/Session.java:273-289` | `onServerJoin(address)`：按服务器地址落盘；地址取不到时 lite 落 local、完整版只读 |
| `client/Session.java:299-305` | `onWorldLeave()`：lite 回到 local（否则从服务器退出后打的歌不落盘） |

---

## 4. 多人成绩路径（headless 验证）

实现：

- `client/ServerStore.java`（纯 `java.nio`，**不 import 任何 MC 类**）
  - `60` `serversRoot(modDir)` = `config/phimc/servers/`
  - `69` `serverDir(modDir, address)`、`74` `profileFile`、`79` `recordsFile`
  - `94-112` `safeName(address)`：只保留 `[A-Za-z0-9._-]`，其余（含 `:` `/` `\ * ? " < > |` 空白）→ `_`；
    ≤96 字符；Windows 保留名（`con`/`nul`/`com1`…）补 `_` 前缀；`null`/空白 → `local`
- `client/WorldHooks.java:54-64`：`JOIN` 里 `saveRoot(client)` 拿不到 → `serverAddress(handler)` → `session.onServerJoin(address)`；
  `65` `DISCONNECT` → `session.onWorldLeave()`（**复用** 原来那对事件，没有新增钩子）
- `client/WorldHooks.java:98-115`：`serverAddress()` = `handler.getConnection().getAddress()`
  （`SocketAddress`；是 `InetSocketAddress` 时拼 `host:port`），整段 `try/catch Throwable`
- `survival/Profile.java:192-241`：新增 `attachWorld(file, migrateLegacy)`，服务器目录传 `false`
  —— **不认领**旧版单文件进度（否则「第一个进过的服务器」会把单人旧进度吃走）
- `survival/Profile.java:530`：`saveGlobal()` 改为 `!progressMigrated ? readObject(globalFile) : null`
  —— 服务器目录还没认领旧进度时，写 B 类设置不能把全局文件里的旧 A 类内容冲掉（数据安全）

验证工具：`tools/server/ServerStoreTest.java`（34 项断言，**模拟地址、不发任何网络请求**）。

运行（仓库根目录，JDK 25）：

```powershell
javac -encoding UTF-8 -d out-server -cp "lib\gson-2.11.0.jar;lib\fabric-loader-0.20.0.jar" `
    core\src\main\java\dev\dsh\phimc\core\Judgement.java `
    mod\src\main\java\dev\dsh\phimc\survival\Profile.java `
    mod\src\main\java\dev\dsh\phimc\client\ConfigDir.java `
    mod\src\main\java\dev\dsh\phimc\client\ServerStore.java `
    mod\src\main\java\dev\dsh\phimc\client\Records.java `
    tools\server\ServerStoreTest.java
java -cp "out-server;lib\gson-2.11.0.jar" ServerStoreTest %TEMP%\phimc-server-test
```

输出（节选，实跑）：

```
== ① 地址 → 文件名安全串（`:` 与 `/` → `_`） ==
  ✓ 127.0.0.1:25565 → 127.0.0.1_25565
  ✓ play.example.com:25565 → play.example.com_25565
  ✓ /1.2.3.4:25565 → _1.2.3.4_25565（getAddress().toString() 的形态）
  ✓ mc.example.com/1.2.3.4:25565 → mc.example.com_1.2.3.4_25565
  ✓ `/ : * ? " < > |` 全部 → _
  ✓ null → local / 空白 → local / /// → ___ / con → _con / NUL → _NUL
  ✓ 幂等：safeName(safeName(x)) == safeName(x)   ✓ 超长地址被截断到 ≤96 字符

== ② 模拟两个服务器：各写各的 config/phimc/servers/<地址>/ ==
  ✓ 目录 = config/phimc/servers/play.example.com_25565
  ✓ profile.json 已生成: config/phimc/servers/play.example.com_25565/profile.json
  ✓ records.tsv 已生成: config/phimc/servers/play.example.com_25565/records.tsv
  ✓ A 服务器**没有**认领旧版单文件进度（没有 Legacy#EZ）
  ✓ B 服务器：解锁 0 首、没有 A 的成绩；B 的活动没碰到 A 的文件（逐字节一致）
  ✓ 全局文件里的旧 A 类字段没被服务器目录吃掉
  ✓ A 服务器重进：profile.json / records.tsv 里成绩还是 980000

== ③ 取不到服务器地址（打歌 only 的主菜单兜底 → servers/local/） ==
  ✓ 目录 = config/phimc/servers/local
  ✓ local/records.tsv 已生成，含主菜单那一局的成绩
== ③ 完整版语义不变：没有世界也没有地址 → 只读、不写盘 ==
  ✓ 完整版没有生成 local/records.tsv / profile.json

34 通过, 0 失败
```

回归（改动没过界）：

| 测试 | 结果 |
|---|---|
| `tools/world/WorldProgressTest`（单人按存档隔离 + 旧进度迁移） | **70 通过, 0 失败** |
| `tools/survival/SurvivalSelfTest`（`PriceTable` + `Profile` + `Shop`） | **全部通过** |

---

## 5. 编译结果与大小

环境：`Gradle 9.8.2`（`C:\dsh-tools\gradle-9.8.2`）+ `JDK 25`（`C:\dsh-tools\jdk25`）+ `GRADLE_USER_HOME=C:\dsh-gradle-home` + `--offline`。

| 命令 | 结果 |
|---|---|
| `gradle :1.21.1:build --offline`（在 ASCII 工作目录 `C:\dsh-work\phimc`） | **BUILD SUCCESSFUL in 1m 32s**，`compileJava` / `jar` / `remapJar` / **`liteJar`** / **`liteDist`** / `assemble` / `build` 全部执行，0 错误 |
| `gradle :1.20.1:compileJava :1.21.11:compileJava --offline` | **BUILD SUCCESSFUL**（跨版本 API 冒烟：最老 + 最新节点都 0 错误） |
| `gradle :1.21.8:compileJava :1.21.9:compileJava --offline` | **BUILD SUCCESSFUL**（`shiftDown()` 的版本分界线上 / 下各一侧，见下） |

`Shift` 检测的跨版本 API 边界（用 `javap` 逐个核对 `GRADLE_USER_HOME` 里 19 个版本的映射 jar）：

| 版本 | `Screen.hasShiftDown()` | `InputUtil.isKeyPressed(...)` | 本仓库走哪支 |
|---|---|---|---|
| 1.20 / 1.20.1 ~ 1.20.6 | ✅ | `(long, int)` | `Screen.hasShiftDown()` |
| 1.21 / 1.21.1 ~ 1.21.8 | ✅ | `(long, int)` | `Screen.hasShiftDown()` |
| **1.21.9** / 1.21.10 / 1.21.11 | ❌ **已删除** | `(Window, int)` | `InputUtil.isKeyPressed(window, LEFT/RIGHT_SHIFT)` |

> 这是本次唯一一处「差点写错版本边界」的地方：最初写成 `>=1.21.10`，会导致 **1.21.9** 编译失败；
> 逐个 javap 之后改成 `>=1.21.9`，并补跑了 1.21.8 / 1.21.9 两个节点的 `compileJava` 验证。

产物：

| 文件 | 大小 | 说明 |
|---|---|---|
| `mod/dist/phimc-1.21.1.jar` | **702,752 B（≈686 KB）** | 完整版（改前 695,066 B；+3 个新 class） |
| `mod/dist/phimcplayer-1.21.1.jar` | **697,521 B（≈681 KB）** | **打歌 only 变体（新交付物）** |
| `mod/versions/1.21.1/build/libs/phimc-1.21.1-sources.jar` | 392,037 B | 完整版源码（lite 无 sources jar） |

两个 jar 的结构对比（`System.IO.Compression` 逐条目）：

```
只存在于 full： (无)
只存在于 lite： (无)
大小不同的条目： fabric.mod.json (508 B / 676 B)、META-INF/MANIFEST.MF (553 / 163)
条目数： full=325 / lite=325        .class 数量： full=253 / lite=253
PhiMCLiteClient.class 在 full / lite 里的 SHA-256 相同（逐字节一致）
```

`javap` 验证 lite 入口（顺带证明 jar 已 remap 到 intermediary，可直接放进 `mods/`）：

```
public final class dev.dsh.phimc.client.PhiMCLiteClient implements net.fabricmc.api.ClientModInitializer {
  public static final java.lang.String MOD_ID;
  public dev.dsh.phimc.client.PhiMCLiteClient();
  public void onInitializeClient();
  public static void openPlayer();
  public static dev.dsh.phimc.client.Session session();
  public static net.minecraft.class_2561 keyHint();   // ← 已是 intermediary 名
}
```

StressTest（判定 / 计分一行未改）：

```
谱面：2500 音符 / 24 判定线 / 141.1s
[自动播放] 分数=1000000 评级=PHI P=2500 G=0 B=0 M=0 最大连击=2500/2500     ← 1000000 / 0 Miss ✔
[不操作]   分数=0 P=0 M=2500/2500（Drag 不再自动判，1002 个 Drag 一并 Miss）
[±60ms]    分数=395690 评级=F P=934 G=225 B=30 M=1311 最大连击=170/2500
```

已安装到游戏：

```
D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar   702,752 B
SHA-256 = 12058F152AF98B1A432B731A3DED08FC3BE632416BD9309C4D96AB961CF0A0D1（与 mod/dist 一致）
```

lite jar **没有**放进这个实例的 `mods/`（二选一：`phimcplayer` 自带 `breaks: phimc`）。

---

## 6. 没做 / 与需求不完全一致的地方

| 项 | 原因 |
|---|---|
| lite 只做 1.21.1 | 按需求「先只做 1.21.1，验证通了我再让它铺 19 个版本」。铺开只需把版本号加进 `build.gradle:152` 的 `liteMcVersions` |
| 没有跑全 19 个版本的 `build` | 冒烟了 4 个节点：`1.20.1`（最老）、`1.21.8`、`1.21.9`（`shiftDown` 分界）、`1.21.11`（最新）的 `compileJava` 全部 0 错误；配置阶段 19 个节点全部配置通过。全量 19 版构建约 30 分钟，等这批验证通过再一起跑更划算 |
| **没有在真实 MC 客户端里启动验证 lite jar** | 本机没有可交互的客户端 / 无法截图；验证靠 ①`compileJava` 0 错误 ②jar 条目集合与 class 逐字节对比 ③`javap` 确认入口 `implements ClientModInitializer` 且已是 intermediary 名 ④headless 单测。**真机启动（`Shift + /` → 选歌 → 打歌 → 成绩落 `config/phimc/servers/...`）仍需你手动点一次** |
| 安装器（`setup/`，C#）没加「打歌版」选项 | 当时安装器只认 `phimc` id，本次未改它的 UI/逻辑；改用 `mod/install.ps1` 已会自动删掉 lite jar（二选一），README 也写清了。**后来已补上**：见 `docs/REPORT-installer-lite-choice.md`（③ 里加「完整版 / 打歌版」单选，内嵌 19+19 个 jar） |
| lite 变体仍注册 3 个打击音 `SoundEvent`（`phimc:hit_click/drag/flick`） | 它们**不是物品**：只是 `Registries.SOUND_EVENT` 里的记录，不落世界、不需要服务端。没有它们打歌就没有打击音（只剩资源包 / 音符盒兜底），体验会明显变差。为此把 `register()` 里的三行抽成 `PhiMCRegistry.registerHitSounds()`（`PhiMCRegistry.java:137-146`），完整版照常经 `register()` 调用它。如果要求「连 SoundEvent 都不能注册」，把 `PhiMCLiteClient.java:63` 那一行删掉即可（其余功能不受影响） |
| 主菜单兜底目录 `config/phimc/servers/local/` | 需求没点名，但打歌版可以在主菜单打歌，必须有落点（否则「成绩存本地」不成立）。目录名复用了 `ServerStore.LOCAL`，三条路只差一个目录名 |
| `Rewards` 里删掉了「没有集成服务端就改客户端背包」的分支 | 就是需求要的硬规则：`serverPlayer() == null` ⇒ 一律不发 |
| `Profile.saveGlobal()` 的 JSON 保留条件放宽 | 不加这一改，服务器目录第一次写设置就会把全局文件里**尚未被任何存档认领**的旧版 A 类进度冲掉（数据丢失）。已有 `WorldProgressTest` 70 项断言覆盖，行为对单人存档不变 |
| 没给 lite 出 `-sources.jar` | 需求没要；class 与完整版逐字节相同，源码看完整版即可 |
| 在线（Phira）页签在 lite 下**保留** | 它属于「选歌」链路（下载谱面到 `songs/`），不在「物品 / 配方 / 战利品 / 奖励 / 世界」的黑名单里 |
| `Session.finish()` 里老日志的 `{:.2f}` 格式串没修 | 既有小 bug（SLF4J 不支持该写法，会原样输出），与本次需求无关，避免超范围改动 |
| 新增 `lib/fabric-loader-0.20.0.jar`（1.9 MB） | `Profile` 里 `devUnlockAll()` 引用 `FabricLoader`，headless 测试要编译 `Profile` 就得有它。`tools/world/WorldProgressTest` 的注释本来就假设 `lib/` 里有这个 jar，但实际缺失 |
