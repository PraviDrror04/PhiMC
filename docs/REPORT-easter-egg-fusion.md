# 彩蛋：大肥鱼 + Phira 融合 → 谱面播放器（1.21.1 拍视频用）

一句话：把 `phimc:big_fish` 和 `phimc:phira` 两个**掉落物**扔在**同一处**（中心距离 ≤ 1.5 格），
原地劈一道装饰性闪电、两个掉落物消失、同一位置掉出一个 `phimc:chart_player`（谱面播放器）。

本次只编 **1.21.1**，判定 / 计分 / 谱面解析 / UI 一行没改，`setup/`、exe、其余 18 个版本的发布流程都没动。

---

## 1. 两张 16×16 贴图：怎么生成的

素材（只读，用户自己提供）：

| 用途 | 源文件 | 原始尺寸 |
| --- | --- | --- |
| 大肥鱼 | `C:\Users\Administrator\.dsh\attachments\v1\objects\b4\b4a18d5d...fbade2` | 260×273 |
| Phira | `C:\Users\Administrator\.dsh\attachments\v1\objects\0e\0eaf73ec6c11...ac11` | 1024×1024 |

生成器（仓库内，JDK 单文件启动，不用先编译）：[`tools/icons/GenItemIcons.java`](../tools/icons/GenItemIcons.java)

```powershell
$java = "C:\dsh-tools\jdk25\bin\java.exe"
$tex  = "mcphigros\mod\src\main\resources\assets\phimc\textures\item"

& $java "-Dfile.encoding=UTF-8" tools\icons\GenItemIcons.java `
    "C:\Users\Administrator\.dsh\attachments\v1\objects\b4\b4a18d5d4d0c62db0fe2a22ae16f32f68de4cd649eaaaad0313397065efbade2" `
    "$tex\big_fish.png" 16

& $java "-Dfile.encoding=UTF-8" tools\icons\GenItemIcons.java `
    "C:\Users\Administrator\.dsh\attachments\v1\objects\0e\0eaf73ec6c11be566b995f3e4beb3764224289923b13fe4a09dde7feee40ac11" `
    "$tex\phira.png" 16
```

产物：

| 文件 | 尺寸 | 格式 |
| --- | --- | --- |
| `mod/src/main/resources/assets/phimc/textures/item/big_fish.png` | 16×16 | PNG，8bit/通道，colorType 6（RGBA，带 alpha 通道） |
| `mod/src/main/resources/assets/phimc/textures/item/phira.png` | 16×16 | 同上 |

缩放算法不是占位色块、也不是最近邻：

- **面积平均**（box filter）：每个目标像素覆盖 16×16（1024/16 → 64×64）个源像素，按覆盖面积加权平均；
- **颜色在预乘 alpha 空间里平均**再除回去，全透明像素不会把脏色渗到边缘（抠图素材不会出现黑边）；
- 实测：两张源图本身是**全不透明**（alpha 全 255，透明 0 / 半透明 0 / 不透明 70980 与 1048576），
  所以输出也是全不透明 16×16；生成器对带透明度的源图同样成立（预乘平均那条分支会把 alpha 一起算进去）。

生成器输出（原样）：

```
源图 : 260×273  alpha 透明 0 / 半透明 0 / 不透明 70980（min 255, max 255）
目标 : 16×16    alpha 透明 0 / 半透明 0 / 不透明 256  (841 字节)
源图 : 1024×1024 alpha 透明 0 / 半透明 0 / 不透明 1048576（min 255, max 255）
目标 : 16×16     alpha 透明 0 / 半透明 0 / 不透明 256  (957 字节)
```

物品模型（1.21.1 走 `models/item/*.json`，写法照 `tablet.json`）：

- `mod/src/main/resources/assets/phimc/models/item/big_fish.json`（layer0 = `phimc:item/big_fish`）
- `mod/src/main/resources/assets/phimc/models/item/phira.json`（layer0 = `phimc:item/phira`）
- 顺带给 1.21.4+ 的物品模型定义也补了两份（`mod/versioned-resources/assets-1.21.4/assets/phimc/items/{big_fish,phira}.json`），
  这两个文件只在 `>=1.21.4` 的节点才进资源目录，1.21.1 的 jar 里没有它们。

---

## 2. 物品：id / 中文名 / 注册位置

| 物品 id | 中文名 | 英文名 | 堆叠 | 右键 |
| --- | --- | --- | --- | --- |
| `phimc:big_fish` | 大肥鱼 | Big Fish | 64 | 无（`Item` 本体，没有 `use`） |
| `phimc:phira` | Phira | Phira | 64 | 无 |

注册代码（[`PhiMCRegistry.java`](../mod/src/main/java/dev/dsh/phimc/PhiMCRegistry.java)）：

| 内容 | 位置 |
| --- | --- |
| 字段 `BIG_FISH` | `PhiMCRegistry.java:53` |
| 字段 `PHIRA` | `PhiMCRegistry.java:55` |
| 注册 `big_fish`（`maxCount(64)`） | `PhiMCRegistry.java:93` |
| 注册 `phira`（`maxCount(64)`） | `PhiMCRegistry.java:94` |
| 加进创造页签 `itemGroup.phimc` | `PhiMCRegistry.java:107-108`（`entries.add(BIG_FISH)` / `entries.add(PHIRA)`，排在页签最后） |
| 挂彩蛋监听 | `PhiMCRegistry.java:121`（`FusionEgg.register()`） |

中文名：`assets/phimc/lang/zh_cn.json:6-7`（`item.phimc.big_fish` = 大肥鱼、`item.phimc.phira` = Phira）；
英文名：`assets/phimc/lang/en_us.json:6-7`。

---

## 3. 融合逻辑（触发条件 / 闪电位置 / 移除与生成）

全部在一个新文件里：[`mod/src/main/java/dev/dsh/phimc/egg/FusionEgg.java`](../mod/src/main/java/dev/dsh/phimc/egg/FusionEgg.java)（122 行，包里唯一的新增）。

### 3.1 事件与「只在服务端」

| 内容 | 位置 |
| --- | --- |
| `ServerTickEvents.END_WORLD_TICK.register(...)` | `FusionEgg.java:57`（在 `register()` 里，只挂一次） |
| 服务端护栏 `if (world.isClient()) return;` | `FusionEgg.java:70`（注释里写明等价 `!world.isClient`） |

本 mod 是 `environment: "client"` 的纯客户端 mod，单人局里集成服务端与客户端同进程、共用同一份静态
`Registries`，所以 `ServerTickEvents` 在单人局照常触发 —— 同一个用法在 `survival/ChipLoot.java:141`
（`END_SERVER_TICK` 开箱赋谱）里已经跑了很久。专用服务器不加载本 mod，多人里也没有这个彩蛋。

### 3.2 触发条件

| 内容 | 位置 |
| --- | --- |
| 配对距离常量 `RADIUS = 1.5` | `FusionEgg.java:43` |
| 扫本世界所有**已加载**的 `ItemEntity`（`world.getEntitiesByType(EntityType.ITEM, e -> true)`） | `FusionEgg.java:74` |
| 按物品分成「鱼」「Phira」两堆（`stack.isOf(BIG_FISH)` / `isOf(PHIRA)`），任一堆为空立即返回 | `FusionEgg.java:77-80` |
| 中心点距离判定 `f.squaredDistanceTo(p) > 1.5²` 则跳过（`Entity.squaredDistanceTo` = 中心到中心） | `FusionEgg.java:87` |
| 成对后 `fuse(...)` | `FusionEgg.java:88` |

也就是：**两个 `ItemEntity` 的中心点距离 ≤ 1.5 格**即「同一处」。先 `O(n)` 分类再两两比较，
地上没有这两种东西时不做任何配对（常规游玩几乎没有额外开销）。

### 3.3 闪电

| 内容 | 位置 |
| --- | --- |
| 位置 = 两个掉落物中心点的**中点** | `FusionEgg.java:101-103` |
| `new LightningEntity(EntityType.LIGHTNING_BOLT, world)` + `setCosmetic(true)` + `refreshPositionAfterTeleport(x,y,z)` + `world.spawnEntity(bolt)` | `FusionEgg.java:107-110` |

选的是**装饰性闪电**（`setCosmetic(true)`）：有画面和雷声，但**不点火、不劈伤玩家、不点着布景**。
拍视频时不会把搭好的场景烧掉；想看原版（会引燃/伤害）的话把 `setCosmetic(true)` 改成 `false` 即可。

### 3.4 移除 + 生成播放器 + 日志

| 内容 | 位置 |
| --- | --- |
| `fish.discard(); phira.discard();` | `FusionEgg.java:113-114` |
| `new ItemEntity(world, x, y, z, new ItemStack(CHART_PLAYER))` + `world.spawnEntity(...)` → 掉在地上，**不塞背包** | `FusionEgg.java:117-118` |
| 日志 `[PhiMC] 大肥鱼 + Phira 融合 → 谱面播放器` | `FusionEgg.java:120` |

**每对只触发一次**：先 `discard()` 两个掉落物再生成播放器 —— 下一 tick 扫到它们时已经 `isRemoved()`，
状态列表里也逐个复查（`FusionEgg.java:75/84/86`）；生成的是播放器而不是鱼/Phira，不会被自己再吃一遍。

---

## 4. 构建（只编 1.21.1）

Gradle 9.8.2 + JDK 25，`GRADLE_USER_HOME=C:\dsh-gradle-home`，`--offline`。
工程先同步到纯 ASCII 目录 `C:\dsh-work\phimc`（Gradle 在中文路径下加载 native-platform 会失败，沿用 `mod/build.ps1` 的做法）：

```powershell
robocopy "…\mcphigros" "C:\dsh-work\phimc" /MIR /NFL /NDL /NJH /NJS /XD out .gradle build fixtures dist
$env:JAVA_HOME="C:\dsh-tools\jdk25"; $env:GRADLE_USER_HOME="C:\dsh-gradle-home"
& "C:\dsh-tools\gradle-9.8.2\bin\gradle.bat" -p "C:\dsh-work\phimc\mod" ":1.21.1:build" --no-daemon --console=plain --offline
```

输出（末段）：

```
> Task :1.21.1:stonecutterPrepare
> Task :1.21.1:stonecutterGenerate
> Task :1.21.1:compileJava
> Task :1.21.1:processResources
> Task :1.21.1:classes
> Task :1.21.1:jar
> Task :1.21.1:sourcesJar
> Task :1.21.1:remapJar
> Task :1.21.1:remapSourcesJar
> Task :1.21.1:assemble
> Task :1.21.1:build

BUILD SUCCESSFUL in 49s
10 actionable tasks: 7 executed, 3 up-to-date
```

产物：`C:\dsh-work\phimc\mod\versions\1.21.1\build\libs\phimc-1.21.1.jar`（665 KB）
→ 覆盖 `D:\.minecraft\versions\1.21.1-fabric-0.20.0\mods\phimc-1.21.1.jar`
（SHA256 两边一致：`AE3C4398BF70B202BF5F46D5CB12A2BD33FFA982C7DDB8F50ABBDDAA1535179E`）。

jar 内新增条目（用 zip 目录核对过）：

```
      90  assets/phimc/models/item/big_fish.json
      87  assets/phimc/models/item/phira.json
     841  assets/phimc/textures/item/big_fish.png      (16×16, RGBA)
     957  assets/phimc/textures/item/phira.png         (16×16, RGBA)
    4804  dev/dsh/phimc/egg/FusionEgg.class
```

`mod/dist/phimc-1.21.1.jar` **没有动**（那份属于 19 版本发布流程）。

---

## 5. StressTest（判定 / 计分 / 解析一行没改）

`java -cp "core-out;gson;jlayer" dev.dsh.phimc.core.StressTest fixtures\spasmodic\chart.json`
（2500 音符 / 24 判定线 / 141.1s）：

```
谱面：2500 音符 / 24 判定线 / 141.1s
[自动播放] 分数=1000000 评级=PHI P=2500 G=0 B=0 M=0 最大连击=2500/2500
[不操作]   分数=0 P=0 M=2500/2500（Drag 不再自动判，1002 个 Drag 一并 Miss）
[±60ms]    分数=395690 评级=F P=934 G=225 B=30 M=1311 最大连击=170/2500
```

自动播放 **1000000 / PHI / 0 Miss**，不操作 0 分 2500 Miss，±60ms 抖动 395690 —— 与
`docs/REPORT-charts-fix.md` 等旧报告里的数字逐位一致。

---

## 6. 视频里怎么快速复现

1. 进 1.21.1 单人存档（创造模式最省事），确认 `mods\phimc-1.21.1.jar` 是这次这一份。
2. 拿物品，两种方式任选：
   - 命令：
     ```
     /give @s phimc:big_fish
     /give @s phimc:phira
     ```
   - 创造模式物品栏 → **PhiMC** 页签 → 最后两个「大肥鱼」「Phira」。
3. 站着不动，先按 `Q` 丢一个、再丢另一个（两样都丢在脚边，落点相距基本 ≤ 1 格，满足 1.5 格判定）。
4. 下一 tick（≤ 0.05 秒）就会：**雷一劈 → 两样东西消失 → 原地掉出一个谱面播放器**。
   想拍慢镜头/多机位就重复第 3 步，地上每凑一对就触发一次（互不干扰）。
5. 日志核对：`logs/latest.log` 里应当出现
   ```
   [PhiMC] 彩蛋已就绪：大肥鱼 + Phira 相距 1.5 格内 → 谱面播放器
   [PhiMC] 大肥鱼 + Phira 融合 → 谱面播放器
   ```

小技巧：
- 想拍「东西飞过去撞在一起」：站在两块相邻方块上各丢一个，或者用发射器/漏斗把两样东西推到一处；
- 闪电是中点位置、装饰性的（不烧东西），旁边摆可燃方块也安全；
- 想让画面更亮可以是夜晚 + `/weather thunder`（只影响天色，不影响触发）。

---

## 7. 已知取舍

- **整堆一起消失**：一个 `ItemEntity` 只算一次触发，所以一次丢 64 个也只融合成一发（不会连闪 64 次）。
  这是「每对只触发一次」的直接结果。
- **每个世界每 tick 扫一遍掉落物**：`O(已加载掉落物数)`，地上没有鱼/Phira 时不做配对。
  单个存档里掉落物成千的极端场景（刷怪塔收集）会有这点常数开销，对拍视频没有影响。
- **多人无效**：`environment: "client"` 的 mod，专用服务器不加载，物品与彩蛋都只在单人（集成服务端）存在 ——
  和本 mod 其它物品一致。
