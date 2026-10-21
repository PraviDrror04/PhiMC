# PhiMC 多版本改造报告（1.20.1 / 1.21.1 / 1.21.11）

> 目标：同一份源码编出多个 MC 版本的 jar，并用一个安装器按实例的 MC 版本自动选 jar。
> 硬约束：`core/` 一行不改；1.21.1 判定/计分/UI/生存玩法/资源包/打击音不回归。

## 1. 方案：A（Stonecutter），为什么不用子项目

选 **A. Stonecutter 单源码 + 条件注释**，理由：

1. 要出 **3 个** MC 版本的 jar（1.20.1 / 1.21.1 / 1.21.11），按你给的判据（≥3 用 A）就该用 A；
2. 版本差异是**点状**的：60 处 `//? if` 覆盖 13 个文件，其余 20+ 个文件三个版本完全共用；
   方案 B 要复制 PlayScreen(51KB)/Session(40KB)/SongSelectScreen(35KB) 这类大文件，改一处要同步三份；
3. 每个版本仍能独立 `gradle :<版本>:build`，坏了只坏一个节点，不影响其它版本出包。

工具链（**这是本次唯一的环境改动**）：

| 组件 | 版本 | 原因 |
|---|---|---|
| Gradle | `C:\dsh-tools\gradle-9.8.2` | Stonecutter 0.9.8 硬性要求 Gradle ≥9（8.10.2 会直接报 "requires at least Gradle 9"） |
| JDK | `C:\dsh-tools\jdk25` | fabric-loom 1.19.2 的 classpath 要求 JVM 25（用 jdk21 会报 "requires at least JVM runtime version 25"） |
| fabric-loom | 1.19.2 | 1.21.11 需要新 loom；1.8.13 不认 1.21.11 |
| Stonecutter | 0.9.8 | 单源码多版本 |

编译目标仍按版本区分：1.20.1 → `--release 17`，1.21.x → `--release 21`。

`src/` 的可见代码状态 = **1.21.11 状态**（`vcsVersion = 1.21.11`，Stonecutter 切版本时用
`gradle stonecutterSwitchTo1.21.11` 同步注释状态）。1.20.1 / 1.21.1 由预处理生成到
`versions/<MC>/build/generated/stonecutter/`，构建产物在 `versions/<MC>/build/libs/`。

## 2. 每个版本的编译结果

| MC 版本 | 结果 | jar | 备注 |
|---|---|---|---|
| 1.20.1 | ✅ `:1.20.1:build` 通过（clean build 也过） | `mod/dist/phimc-1.20.1.jar` 436,339 B | Java 17 字节码；loader 0.16.14；fabric-api 0.92.12 |
| 1.21.1 | ✅ `:1.21.1:build` 通过 | `mod/dist/phimc-1.21.1.jar` 436,928 B | 保持原依赖（loader 0.20.0 / fapi 0.116.17），回归见 §5 |
| 1.21.11 | ✅ `:1.21.11:build` 通过 | `mod/dist/phimc-1.21.11.jar` 439,073 B | 需要重写渲染/输入兼容层（见 §2.2） |

`fabric.mod.json` 的 version range（都是**精确版本**，不是 `~`）：

| jar | `depends.minecraft` | `depends.java` |
|---|---|---|
| phimc-1.20.1.jar | `1.20.1` | `>=17` |
| phimc-1.21.1.jar | `1.21.1` | `>=21` |
| phimc-1.21.11.jar | `1.21.11` | `>=21` |

> 为什么从 `~` 改成精确版本：用 fabric-loader 0.16.14 实测
> `~1.21.1` **会同时匹配 1.21.11**（`~1.20.1` 还会匹配 1.20.4/1.20.6）。
> 现在有 1.21.11 的 jar 了，宽松范围会让「1.21.1 的 jar 被 1.21.11 实例加载 → 直接崩」。
> 精确版本对 1.21.1 用户没有任何行为变化（1.21.1 仍然匹配），只是拒绝装错。

## 3. 逐处核实的 API 差异（不只信旧表）

### 3.1 旧表里对/不对的地方

| 旧表说法 | 实测 |
|---|---|
| `Identifier.of(ns,path)` 只有 1.21+ 有 | ❌ **1.20.1 也有 `Identifier.of(String,String)`**（yarn 1.20.1+build.10 静态方法存在），三个版本都不用改 |
| 1.21.2+ `TypedActionResult` 改名 `ActionResult` | ✅ 对，1.21.11 编译报 "找不到符号 TypedActionResult" |
| `Item.Settings().maxCount()/maxDamage()` 1.21.2+ 变化 | ❌ 1.21.11 仍然有 `maxCount(int)`/`maxDamage(int)`，不用改 |
| `Registries.ITEM.getOrEmpty` 1.21.2+ 换 `getEntry` | ✅ 1.21.11 删了 `getOrEmpty`，改名 `getOptionalValue`（不是 `getEntry`） |
| `FabricItemGroup.builder()` / `Registries.ITEM_GROUP` 各版本微调 | ❌ 三个版本都一致（`icon(Supplier<ItemStack>)`、`entries(...)` 都在），没改 |

### 3.2 1.20.1 的真实差异（`//? if` 标注处）

| 文件:行 | 差异 | 改法 |
|---|---|---|
| `item/ChipItem.java:12,79,109` | 没有组件系统（`DataComponentTypes`/`NbtComponent` 包不存在），也没有 `item.tooltip.TooltipType` | 1.20.1 走 `stack.getNbt()` / `stack.getOrCreateNbt()` |
| `item/ChipItem.java:278`、`item/BlankChipItem.java:32` | `Item.appendTooltip(ItemStack, Item.TooltipContext, List, TooltipType)`（1.21.1）vs `(ItemStack, World, List, TooltipContext)`（1.20.1）；`TooltipContext` 在 1.20.1 是 `net.minecraft.client.item.TooltipContext` | 签名 + import 按版本切 |
| `survival/ChipLoot.java:6,13,130` | fabric 战利品事件 v3 → v2：`LootTableEvents.MODIFY` 回调是 `(ResourceManager, LootManager, Identifier, LootTable.Builder, LootTableSource)` | 收拢成 `inject(Identifier, builder, source)`，v3 那条多一层 `key.getValue()` |
| `survival/ChipLoot.java:166` | 1.20.1 的 `LootTableEntry.builder` 收 `Identifier`（1.21+ 收 `RegistryKey<LootTable>`） | 版本分支 |
| `survival/ChipLoot.java:199` | 1.20.1 没有 `LootableInventory` | 用 `LootableContainerBlockEntity`（Chest/Barrel/Shulker 的父类） |
| `client/HitSoundPlayer.java:206` | 1.20.1 的 `OggAudioStream` 没有 `read(FloatConsumer)`，只有 `getBuffer(int)`（返回已量化的 16-bit PCM，native order） | 按 LITTLE_ENDIAN 读 short 再写回，和 `PCM_SIGNED/bigEndian=false` 对齐 |
| `client/SongSelectScreen.java:506,562` | 1.20.1 `mouseScrolled(DDD)`（少 horizontalAmount）、`renderBackground(DrawContext)`（少三个参） | 版本分支；1.20.1 的列表行不做 hover 高亮（拿不到鼠标坐标） |

`TypedActionResult`、`Identifier.of`、`ItemGroup`、`Registries.ITEM.getOrEmpty` 在 1.20.1 **不用改**。

### 3.3 1.21.11 的真实差异（这次是 GUI 渲染/输入整体换代）

| 文件:行 | 差异 | 改法 |
|---|---|---|
| `client/RenderCompat.java:40` | `DrawContext.drawTexture` 必须给 `RenderPipeline`，且 `setShaderColor` 被删除 | 统一走 `RenderPipelines.GUI_TEXTURED` + `drawTexture(..., int color)`；1.21.1 分支保持 `setShaderColor + drawTexture`（**字节码已核对**，见 §5） |
| `client/RenderCompat.java:133`~ | `ctx.getMatrices()` 从 `MatrixStack`（3D）变成 `Matrix3x2fStack`（`pushMatrix/popMatrix/translate(x,y)/rotate(rad)/scale(sx,sy)`） | `RenderCompat.Transform` 薄封装，PlayScreen/SongSelectScreen 只改类型与方法名 |
| `client/RenderCompat.java:95` | `NativeImageBackedTexture(NativeImage)` 删除，改成 `(Supplier<String> 名字, NativeImage)`；`setFilter` 也删除（改成 GpuSampler） | 曲绘走 `newTexture`；资源包贴图走 `newTextureLinear`（私有子类改 protected `sampler`，保住 GL_LINEAR，否则 1.21.11 会退回点采样、观感比 Phira 亮/刺） |
| `client/PlayScreen.java:781`、`client/SongSelectScreen.java:461` | 输入回调换成记录类：`mouseClicked(Click, boolean)`、`mouseDragged(Click, double, double)`、`mouseReleased(Click)`、`keyPressed(KeyInput)`、`keyReleased(KeyInput)` | 加「桥」：新签名 → 老三参数方法，判定逻辑一行没动；`super.mouseClicked` 也按版本切 |
| `item/ChartPlayerItem.java:8`、`TabletItem.java:14`、`ChipItem.java:25` | `TypedActionResult<ItemStack>` → `ActionResult` | 版本分支（返回值 `ActionResult.SUCCESS`） |
| `item/ChipItem.java:278`、`BlankChipItem.java:32` | `appendTooltip` 变 5 参：`(ItemStack, Item.TooltipContext, TooltipDisplayComponent, Consumer<Text>, TooltipType)` | 版本分支；内容先攒 `List<Text>`，再 `addAll` 或逐条 `accept` |
| `item/ChipItem.java:87` | `NbtCompound.getString(...)` 返回 `Optional<String>` | `.orElse("")` |
| `item/ChipItem.java:213` | `PlayerInventory.selectedSlot` 变 private | `getSelectedSlot()` |
| `survival/Wallet.java:30`、`Rewards.java:175` | `Registry.getOrEmpty` 删除 | `getOptionalValue(...).orElse(null)` |
| `survival/ChipLoot.java:224` | `Entity.getWorld()` → `getEntityWorld()` | 版本分支 |
| `client/HitSoundPlayer.java:303` | `PositionedSoundInstance.master(...)` 删除（SoundInstance 改成按 Identifier 构造） | 手写等价对象（MASTER 分类 / 无衰减 / 相对坐标） |
| `client/PhiMCClient.java:43` | `KeyBinding` 分类从 String 变 `KeyBinding.Category`（record + `Category.create(Identifier)`） | 版本分支；语言文件补 `key.category.phimc.main` |

**1.21.11 不用改的**（实测）：`Screen.renderBackground(ctx, mx, my, dt)` 签名没变、
`fill/drawText/drawTextWithShadow/drawCenteredTextWithShadow` 没变、`Item.Settings.maxCount/maxDamage`
还在、`Registries.ITEM_GROUP` + `FabricItemGroup.builder()` 没变、`HudRenderCallback` 仍可用（fabric-api 0.141.6 里还在）。

## 4. 安装器改动（`setup/`）

* **内嵌**：每个版本一对 —— `phimc-<MC>.jar` + `fabric-api-<ver>+<MC>.jar`
  （1.20.1 → 0.92.12，1.21.1 → 0.116.17，1.21.11 → 0.141.6，后两个从 Modrinth 下载放进 `setup/`）。
* **选 jar 的规则**（`SetupForm.cs` 的 `Variants` 表 + `DetectInstanceMc`）：
  1. 读 `versions/<实例>/<实例>.json` 的 `inheritsFrom`（Fabric 档案会写）；
  2. 没有就读 `id`（纯原版档案）；
  3. 再没有就从目录名里抓版本号（`fabric-loader-0.16.9-1.21.11` 也能认）；
  4. **精确匹配** `Variants` 里的 MC 版本，匹配不上就**拒绝安装**。
* **拒绝时**（不静默装错）：日志 + 弹窗列出实例版本与支持列表，例如
  `× 不支持的 MC 版本：实例「26.1.2-fabric」，支持：1.20.1 / 1.21.1 / 1.21.11`；
  界面状态行也会变红显示同一句。
* **清理**：写新 jar 前删掉 `mods/phimc-*.jar` 里其它版本的（两个版本同时加载会双双失败）。
* **Fabric API**：只放与实例 MC 版本匹配的那一个；已有匹配就跳过，有别的版本的会提示建议删掉。
* `setup/build-setup.ps1`：从 `mod/dist/`（没有就 `mod/versions/<MC>/build/libs/`）收集 jar，
  **逐个校验** jar 内 `fabric.mod.json` 的 `version==0.1.0` 且 `minecraft` 依赖包含该 MC 版本，
  再校验 `SetupForm.cs` 的版本表与脚本一致（两边不同步直接报错）。
* `mod/install.ps1`：命令行安装也改成按 `-McVersion`/实例自动选 jar，支持列表外的版本直接退出。
* 内置谱面 payload 不进仓库：脚本默认找 `setup/payload/`，没有就回退到 `C:\dsh-work\payload`
  （本次就是从旧 exe 里解出来的 19 张谱面）。

安装器尺寸：`setup/PhiMCSetup.exe` 447.8 MB（19 张谱面 + 3 套 jar/API）。

## 5. 1.21.1 回归

**核心引擎（core/，一行未改）**：

```
谱面：2500 音符 / 24 判定线 / 141.1s
[自动播放] 分数=1000000 评级=PHI P=2500 G=0 B=0 M=0 最大连击=2500/2500
[不操作]   分数=0 P=0 M=2500/2500（Drag 不再自动判，1002 个 Drag 一并 Miss）
[±60ms]    分数=395690 评级=F P=934 G=225 B=30 M=1311 最大连击=170/2500
```

两条硬指标都满足：**自动播放 = 1000000 / 0 Miss**、完全不操作 = **0 分 / 2500 Miss**。

**mod 侧**：

* 1.21.1 jar 是 **159 个 class**（基线 157 + `RenderCompat` / `RenderCompat$Transform` 两个兼容层类；
  1.21.11 再多一个 `RenderCompat$LinearTexture` = 160）。兼容层只做「把同一件事换个签名」，不含新逻辑；
* 渲染分支用 `javap -c` 核对：1.21.1 的 `RenderCompat.drawTexture` 编译成
  `method_51422`（`setShaderColor`）+ `method_25293`（`drawTexture(Identifier,...)`），
  与改造前的调用序列一致；1.21.11 才是 `RenderPipelines.GUI_TEXTURED` + 带颜色参数的重载；
* 其余差异只剩 javac 25 的 lambda 编号、`ChipLoot` 的 `modify → modifyV3 + inject` 拆分（等价重构）；
* 依赖保持原样：loader 0.20.0 / yarn 1.21.1+build.3 / fabric-api 0.116.17+1.21.1；
* 判定/计分/UI/生存玩法/资源包/打击音的代码在 1.21.1 分支上没有语义改动。

## 6. 放弃的版本

| 版本 | 结论 | 原因 |
|---|---|---|
| **26.1.2** | ❌ 放弃 | 26.1 起 Minecraft **不再混淆**，Fabric 把 loom 拆成 `fabric-loom`（obf）与 `fabric-loom`（unobf）；更致命的是 **Fabric 已不再为 26.1.2 提供 Yarn 映射**（`https://meta.fabricmc.net/v2/versions/yarn/26.1.2` 返回空数组），而本仓库全部源码是 Yarn 名字。要支持就得把 30 个文件的 `net.minecraft.*` 全部换成 Mojang 官方名 + 再接一遍 1.21.9→26.x 的渲染/输入改动，等于另一套源码，风险远大于收益。 |
| 1.20.4 / 1.20.6 | ❌ 未做 | 优先级更低；1.20.4 的 GUI 输入/战利品 API 介于 1.20.1 与 1.21.1 之间，需要再验一轮（`~1.20.1` 会误匹配它们，所以本次改成精确版本，避免装错）。 |

## 7. 复现命令

```powershell
# 全版本编译（首次每个版本要下 MC+映射，约 5-15 分钟）
powershell -ExecutionPolicy Bypass -File mod\build.ps1
# 只编一个版本
powershell -ExecutionPolicy Bypass -File mod\build.ps1 -Versions 1.21.11

# 重打安装器（谱面 payload 不在仓库里，默认回退 C:\dsh-work\payload）
powershell -ExecutionPolicy Bypass -File setup\build-setup.ps1
```

---

# 追加：1.20 ~ 1.21.11 全系扩版（19 个版本）

## A. 可行性表（Fabric meta / Modrinth 实测，19/19 都有 Yarn + Fabric API）

| MC | Yarn | Fabric API | 编译 |
|---|---|---|---|
| 1.20 | 1.20+build.1 | 0.83.0+1.20 | ✅ |
| 1.20.1 | 1.20.1+build.10 | 0.92.12+1.20.1 | ✅ |
| 1.20.2 | 1.20.2+build.4 | 0.91.6+1.20.2 | ✅ |
| 1.20.3 | 1.20.3+build.1 | 0.91.1+1.20.3 | ✅ |
| 1.20.4 | 1.20.4+build.3 | 0.97.3+1.20.4 | ✅ |
| 1.20.5 | 1.20.5+build.1 | 0.97.8+1.20.5 | ✅ |
| 1.20.6 | 1.20.6+build.3 | 0.100.8+1.20.6 | ✅ |
| 1.21 | 1.21+build.9 | 0.102.0+1.21 | ✅ |
| 1.21.1 | 1.21.1+build.3 | 0.116.17+1.21.1 | ✅ |
| 1.21.2 | 1.21.2+build.1 | 0.106.1+1.21.2 | ✅ |
| 1.21.3 | 1.21.3+build.2 | 0.114.1+1.21.3 | ✅ |
| 1.21.4 | 1.21.4+build.8 | 0.119.4+1.21.4 | ✅ |
| 1.21.5 | 1.21.5+build.1 | 0.128.2+1.21.5 | ✅ |
| 1.21.6 | 1.21.6+build.1 | 0.128.2+1.21.6 | ✅ |
| 1.21.7 | 1.21.7+build.8 | 0.129.0+1.21.7 | ✅ |
| 1.21.8 | 1.21.8+build.1 | 0.136.1+1.21.8 | ✅ |
| 1.21.9 | 1.21.9+build.1 | 0.134.1+1.21.9 | ✅ |
| 1.21.10 | 1.21.10+build.3 | 0.138.4+1.21.10 | ✅ |
| 1.21.11 | 1.21.11+build.6 | 0.141.6+1.21.11 | ✅ |

## B. 实测出来的 API 分代边界（改条件注释的依据）

| API | 生效版本 | 备注 |
|---|---|---|
| `Element.mouseScrolled(DDDD)` / `renderBackground(ctx,mx,my,dt)` | >=1.20.2 | 1.20/1.20.1 是三参 |
| 组件系统（`DataComponentType`/`NbtComponent`）、`TooltipType`（`client.item`） | >=1.20.5 | ≤1.20.4 用 `getNbt/getOrCreateNbt` |
| 战利品事件 v2 三参 + `LootTableEntry.builder(RegistryKey)` | >=1.20.5 | ≤1.20.4 是 v2 五参 + `builder(Identifier)` |
| 战利品事件 v3（带 `RegistryWrapper`） | >=1.21 | fabric-loot-api-v3 |
| `ActionResult`、`Registry.getOptionalValue` | >=1.21.2 | 1.21.1 用 `TypedActionResult`/`getOrEmpty` |
| `NbtCompound.getString→Optional`、`TooltipDisplayComponent+Consumer` 的 5 参 appendTooltip、`PlayerInventory.selectedSlot` 变 private | >=1.21.5 | |
| `getMatrices()` 返回 `Matrix3x2fStack`；`drawTexture` 首参 `RenderPipeline`；`NativeImageBackedTexture` 采样器化 | >=1.21.6 | 1.21.2~1.21.5 是 `Function<Identifier,RenderLayer>` + `RenderLayer::getGuiTextured` |
| 输入记录类 `Click` / `KeyInput`；`KeyBinding.Category`；`Entity.getEntityWorld` | >=1.21.9 | 1.21.9 的 KeyBinding 还是 4 参（无 sortOrder） |
| `PositionedSoundInstance.master` 删除；`AbstractTexture.setFilter` 删除 | >=1.21.11 | 1.21.11 手写 SoundInstance + 私有 sampler 子类 |

**结论**：19 个版本实际只需要 8 个条件边界（1.20.2 / 1.20.5 / 1.21 / 1.21.2 / 1.21.5 / 1.21.6 / 1.21.9 / 1.21.11），其余版本零差异直接编译。

## C. 结果

- 19/19 `gradle :<MC>:build` 通过（每个 18~26s，已缓存依赖）；`mod/build.ps1` 一把编完 19 个。
- 产物：`mod/dist/phimc-<MC>.jar`，466~468 KB（19 个）。
- 基线三版行为不变：1.20.1 / 1.21.1 走 `setShaderColor + drawTexture`（javap 已核对 `method_51422`），1.21.11 走 `RenderPipelines`；`core/` 未改。
- 新增 helper：`client/RenderCompat.java`（三档绘制/矩阵/贴图创建）、`PhiMCRegistry` 等仍用 `Identifier.of`（1.20 也有，已核实）。

## D. 安装器

- `SetupForm.cs` / `build-setup.ps1` 双份 19 行版本表（互相校验），19 份 Fabric API 全部从 Modrinth 下载齐全。
- 内置谱面收敛到 **4 张 .pez**（Cyberneko Pulse / Hardsync / 推活天使 / 琵琶曲DJ），15 个官方 zip 已移出 payload（`C:\dsh-work\payload-official-unused`），不再内置。
- `setup/PhiMCSetup.exe` = **74.5 MB**（19 jar + 19 API + 4 pez，共 42 个内嵌资源）。
- 版本识别修了 3 个 bug（目录名从左往右、主版本号>=1、`id` 扫全部候选），实测：
  - `1.21.1-fabric-0.20.0`（无 inheritsFrom）→ 识别 1.21.1，装 `phimc-1.21.1.jar`
  - `1.20.1-fabric-0.19.5` → 1.20.1
  - `26.1.2-fabric-0.19.5` → 拒绝，并列出 19 个支持版本

## E. 1.19.4 及更早的量化估计

| 项目 | 量 |
|---|---|
| 渲染层（`DrawContext` 不存在，要用 `MatrixStack` + `RenderSystem` + `VertexConsumerProvider` 手画） | `PlayScreen` 999 行、`SongSelectScreen` 1287 行、`HudRenderer` 82 行、`SettingsScreen` 271 行、`TabletScreen` 277 行、`CoverTextures` 190 行 |
| 需要改写的绘制/输入调用点 | **145 处**（fill/drawText/drawTexture/getMatrices/六种输入回调） |
| 注册层（`Registries.ITEM`/`ITEM_GROUP` 不存在，用 `Registry` + `ItemGroup.Builder` 静态注册） | `PhiMCRegistry` 119 行 |
| 物品组件（无 `DataComponentType`） | `ChipItem` 274 行 |
| 战利品（fabric loot v1 的 `LootTableLoadingCallback`） | `ChipLoot` 218 行 |
| 合计 | **约 3,700 行里 1,000~1,300 行要动**，另外要给 1.20+ 保留现有实现（RenderCompat 再分一代） |

结论：1.19.4 相当于**再写一个渲染后端**（不是加几个 `//?` 条件注释），预计 2~3 天工作量，本次未做。
---

# 追加：1.20.1 列表不渲染（已修）+ 跨版本渲染审计

## 根因
`SongSelectScreen` 的曲目列表画在 `renderBackground(...)`。**1.20.1 的 `Screen.render` 不调用 `renderBackground`**（1.20.2 起才在 `render()` 里调），
所以 1.20/1.20.1 上列表整块不画；标题/详情/按钮在 `render()` 里，故「其他界面正常」。

证据（1.20.1 vs 1.21.1 `Screen.render` 字节码）：1.20.1 只遍历 `drawables`；1.21.1 第一条指令就是 `renderBackground(...)`。

## 修复
`SongSelectScreen.render()`（`client/SongSelectScreen.java:1153`）：
```java
//? if <1.20.2 {
/*this.renderBackground(ctx);
*///?}
super.render(ctx, mouseX, mouseY, delta);
```
同时给 `TabletScreen` / `SettingsScreen` / `PlayScreen` 的 `render()` 补了同样的 `<1.20.2` 背景调用（1.20.2+ 由框架调，1.20/1.20.1 之前没有模糊/压暗底）。

## 审计结论（19 版 × 6 个界面）
| 界面 | 1.20~1.20.1 | 1.20.2+ | 状态 |
|---|---|---|---|
| SongSelect 本地/在线列表 | 不画（已修） | 正常 | ✅ |
| SongSelect 详情/页签/按钮 | 正常 | 正常 | ✅ |
| TabletScreen / SettingsScreen | 无模糊底（已修） | 正常 | ✅ |
| PlayScreen | 自绘底色覆盖，补背景对齐 | 正常 | ✅ |
| HudRenderer | 正常（HudRenderCallback） | 正常 | ✅ |
| 物品 tooltip | 4 参（client.item.TooltipType/client.item.TooltipContext） | 4 参→5 参（≥1.21.5） | ✅ |

逐代核对（javap 产物）：`drawTexture` 三档（≤1.21.1 `setShaderColor`；1.21.2~1.21.5 `Function<Identifier,RenderLayer>`；≥1.21.6 `RenderPipeline`）、
矩阵两档（<1.21.6 `MatrixStack`+`Quaternionf`；≥1.21.6 `Matrix3x2fStack`）都落在正确版本上。

19/19 `gradle :<MC>:build` 通过；1.21.1 StressTest 仍 1000000 / 0 Miss。
---

# 追加：数据文件兼容（配方 / 战利品表 / 物品模型）——19 版本校验

## 实测的版本格式边界（对照各版本原版 jar）
| 项目 | ≤1.20.4 | 1.20.5 ~ 1.20.6 | ≥1.21 | ≥1.21.4 |
|---|---|---|---|---|
| 配方目录 | `data/<ns>/recipes/` | `recipes/` | **`recipe/`** | `recipe/` |
| 配方 `result` | `item` | **`id`** | `id` | `id` |
| 战利品表目录 | `loot_tables/` | `loot_tables/` | **`loot_table/`** | `loot_table/` |
| 战利品条目字段 | `name` | `name` | `name` | `name` |
| 物品模型定义 `assets/<ns>/items/*.json` | 不需要 | 不需要 | 不需要 | **必需** |

## 根因
1. **全部 1.20.x（7 个版本）**：jar 里的数据目录是 1.21 的命名（`recipe/`、`loot_table/`），1.20.x 只认 `recipes/`、`loot_tables/`
   → 平板/空芯片配方都加载不到（1.20.4 用户报的现象），开箱芯片的战利品表也解析不到。
2. **1.20 ~ 1.20.4（5 个版本）**：即使目录名对了，`"result": {"id": ...}` 也不被识别（这些版本要 `item`）。
3. **1.21.4 ~ 1.21.11**：缺 `assets/phimc/items/*.json` 物品模型定义 → 物品没有模型（不是配方问题，但属同一批数据兼容）。

## 修法：按版本选资源目录（Stonecutter srcDir）
- 新增 `mod/versioned-resources/`：
  - `data-1.20.4/`（`recipes/` + `loot_tables/` + `result.item`）
  - `data-1.20.5/`（`recipes/` + `loot_tables/` + `result.id`）
  - `data-1.21/`（`recipe/` + `loot_table/` + `result.id`）
  - `assets-1.21.4/`（4 个 `assets/phimc/items/<id>.json` 模型定义）
- `mod/build.gradle`（约 55–76 行）按 `sc.current.parsed` 选目录：
  `<1.20.5` → data-1.20.4；`<1.21` → data-1.20.5；其它 → data-1.21；`>=1.21.4` 再加 assets-1.21.4。
- 删掉 `src/main/resources/data`（避免 1.21 布局漏进 1.20.x）。
- **为什么不用 Gradle 字符串替换**：纯数据、文件小，显式目录是唯一真源、可 headless 逐字段校验；Gradle 里改 JSON 容易改坏。
  Stonecutter 的 `//?` 只能用于代码，JSON 靠资源目录选择。

## 校验（`tools/validate-data.ps1`，headless 解 jar）
对 19 个 jar 逐项检查：配方/战利品目录名、`result` 字段（且不能混入另一个）、战利品表结构（`minecraft:chest` + `pools[].rolls=1` + `item`/`empty` 条目与权重）、
4 个模型 JSON、1.21.4+ 的 4 个物品模型定义且 `model` 指向 `phimc:item/<id>`。结果：**19/19 OK**。

```
版本      配方路径                    result 战利品表                        模型                  判定
1.20    data/phimc/recipes/ item   data/phimc/loot_tables/ models=4/4          OK
1.20.1  data/phimc/recipes/ item   data/phimc/loot_tables/ models=4/4          OK
1.20.2  data/phimc/recipes/ item   data/phimc/loot_tables/ models=4/4          OK
1.20.3  data/phimc/recipes/ item   data/phimc/loot_tables/ models=4/4          OK
1.20.4  data/phimc/recipes/ item   data/phimc/loot_tables/ models=4/4          OK
1.20.5  data/phimc/recipes/ id     data/phimc/loot_tables/ models=4/4          OK
1.20.6  data/phimc/recipes/ id     data/phimc/loot_tables/ models=4/4          OK
1.21    data/phimc/recipe/  id     data/phimc/loot_table/  models=4/4          OK
1.21.1  data/phimc/recipe/  id     data/phimc/loot_table/  models=4/4          OK
1.21.2  data/phimc/recipe/  id     data/phimc/loot_table/  models=4/4          OK
1.21.3  data/phimc/recipe/  id     data/phimc/loot_table/  models=4/4          OK
1.21.4  data/phimc/recipe/  id     data/phimc/loot_table/  models=4/4 defs=4/4 OK
1.21.5 ~ 1.21.11 同上            id     data/phimc/loot_table/  models=4/4 defs=4/4 OK
```

## 其它确认
- `fabric.mod.json` 的 `fabric-api` 依赖与配方/战利品加载无关（数据文件随模组内置数据包加载，不需要任何 API），保留原样。
- 战利品表结构已与各版本原版 `chests/simple_dungeon.json` 对照：根 `type: minecraft:chest`、`pools[].entries[]`、item 条目用 `name`，全部一致。
- 1.21.1 回归：StressTest 自动播放 1000000 / 0 Miss、不操作 2500 Miss。
---

# 追加：1.21.9+「Item id not set」崩溃修复（源码依据 / 平台依据分栏）

## 根因
1. **1.21.9 起** `Item` 构造器要求 `Item.Settings` 里已经设好 `registryKey`，否则
   `Objects.requireNonNull` 抛 `NullPointerException: Item id not set`（用户 1.21.11 崩溃栈第 2、3 行）。
2. 之前用**反射**补救（`Item.Settings.class.getMethod("registryKey", RegistryKey.class)`）必然失败：
   发布出去的 jar 里 Minecraft 方法名是 **intermediary**，`Item.Settings.registryKey` 运行时叫
   `method_63686`（1.21.9/1.21.10/1.21.11 一致），`getMethod("registryKey", ...)` 抛
   `NoSuchMethodException`；它又被 `catch (Throwable)` 吞掉，于是 key 一直没设上。
   顺带：`RegistryKeys.ITEM` 运行时也是 `field_xxxx`（1.21.11 = `class_7924.field_41197`），
   即便方法名对了，反射拼 registry key 也不稳。
   → 结论：**这类跨版本 API 只能用 Stonecutter 条件在编译期选择，不能用反射**。

## 修法（`mod/src/main/java/dev/dsh/phimc/PhiMCRegistry.java:102-124`）
```java
    private static Item registerItem(String name,
                                     java.util.function.Function<Item.Settings, Item> factory,
                                     Item.Settings settings) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, name));
        //? if >=1.21.9 {
        settings = settings.registryKey(key);
        //?}
        return Registry.register(Registries.ITEM, key, factory.apply(settings));
    }
```
4 个物品改成 `registerItem("chart_player", ChartPlayerItem::new, new Item.Settings().maxCount(1))` 等
（`PhiMCRegistry.java:60/66/70/74`）。条件只包一行，沿用项目现有的 `//? if` 风格。

生成源码两侧：
- 1.21.9 / 1.21.10 / 1.21.11（含 vcs 版本直接编 `src/`）：`settings = settings.registryKey(key);` 生效
- 1.20.1 / 1.21.1 / … / 1.21.8：该行是 `/*settings = settings.registryKey(key);`（注释，不参与编译）

## 反编译证据（jar 内 `PhiMCRegistry.registerItem`，javap -c）
1.21.11（1.21.9 / 1.21.10 逐字节相同）：
```
 12: astore_3                                      // key
 13: aload_2
 14: aload_3
 15: invokevirtual class_1792$class_1793.method_63686:(class_5321)class_1792$class_1793   ← Settings.registryKey(key)
 18: astore_2
 19: getstatic     class_7923.field_41178          // Registries.ITEM
 22: aload_3
 23: aload_1
 24: aload_2
 25: invokeinterface java/util/function/Function.apply                                  ← Item 构造（拿到的 settings 已有 key）
 33: invokestatic  class_2378.method_39197         // Registry.register(registry, key, item)
```
1.21.1 / 1.20.1：`method_63686` 出现次数 = 0（编译期就被条件排除，jar 里没有这条调用）。

## 19 版本 `method_63686`（Settings.registryKey）出现次数
| 版本 | 次数 | 版本 | 次数 |
|---|---|---|---|
| 1.20 / 1.20.1 / 1.20.2 / 1.20.3 / 1.20.4 / 1.20.5 / 1.20.6 | 0 | 1.21 / 1.21.1 / 1.21.2 / 1.21.3 / 1.21.4 / 1.21.5 / 1.21.6 / 1.21.7 / 1.21.8 | 0 |
| — | — | **1.21.9 / 1.21.10 / 1.21.11** | **1** |

## 1.21.4+ 物品模型定义校验（headless，解 jar）
| 版本段 | 检查 | 结果 |
|---|---|---|
| ≤1.21.3（11 版） | 有 4 个 `assets/phimc/models/item/*.json`（parent+layer0），**不应**有 `items/` 定义 | 11/11 OK |
| ≥1.21.4（8 版） | 有 4 个 `assets/phimc/items/<id>.json`，`model.model` = `phimc:item/<id>`，且该 model 文件确实存在 | 8/8 OK（defs=4/4、model存在=4/4） |

## 源码依据 vs 平台依据（分栏）
| 结论 | 源码依据（prpr，`C:\dsh-work\phira-src\prpr\src\`） | 平台依据（MC，prpr 无此项） |
|---|---|---|
| 谱面数据/解析与 MC 版本无关（因此 `core/` 不参与任何版本分支） | `parse/rpe.rs:296`（RPEChart）、`parse/pec.rs:184`（parse_pec）、`parse/pgr.rs:69`（PgrChart）、`info.rs:19`（ChartInfo） | — |
| 资源包贴图槽位/持握三段（holdAtlas）与 MC 版本无关 | `core/resource.rs:57`（ResPackInfo）、`:73/:148`（hold_atlas）、`:169`（to_uv） | — |
| `registryKey` 必须在 Item 构造前设置 | **prpr 无物品/注册概念**（全仓库无 registry/data pack/item model 关键字） | 1.21.11 反编译 `Item.java` 构造器 `Objects.requireNonNull(registryKey)`（NPE 文案 `Item id not set`）；yarn 1.21.9+ 有 `Item.Settings.registryKey`，1.21.8 及更早无；运行时名 `method_63686` |
| 数据目录 `recipes/`→`recipe/`、`loot_tables/`→`loot_table/`（边界 1.21） | **prpr 无数据包概念** | 原版 jar：`data/minecraft/recipes/stick.json`（1.20.4）vs `data/minecraft/recipe/stick.json`（1.21.1）；`data/minecraft/loot_tables/chests/simple_dungeon.json`（1.20.4）vs `data/minecraft/loot_table/chests/simple_dungeon.json`（1.21.1） |
| 配方 result `item`→`id`（边界 1.20.5） | **prpr 无配方概念** | 原版 `data/minecraft/recipes/stick.json` 的 `result` 字段（1.20.4 用 `item`，1.20.5 用 `id`） |
| 物品模型定义 `assets/<ns>/items/*.json`（边界 1.21.4） | **prpr 无物品模型概念** | 原版 `assets/minecraft/items/stone.json`（1.21.4+，内容 `{"model":{"type":"minecraft:model","model":"minecraft:block/stone"}}`）；1.21.3 原版该目录不存在 |
| `fabric.mod.json` 的版本范围语义（`~1.21.1` 会匹配 1.21.11） | — | fabric-loader `VersionPredicate` 实测（0.16.14）；不是 prpr 概念 |
| 1.20.1 列表不渲染（`Screen.render` 不调 renderBackground） | prpr 是 wgpu 自绘、无 Screen 生命周期，**无对应** | MC 各版本 `net.minecraft.client.gui.screen.Screen.render` 字节码（1.20.1 只遍历 drawables；1.20.2+ 首条指令调 renderBackground） |

## 部署与回归
- 已把重新编出的 jar 覆盖到用户实例：`1.20.1-fabric-0.19.5`、`1.20.4-fabric-0.19.5`、`1.20.6-fabric-0.20.0`、`1.21.1-fabric-0.20.0`、`1.21.11-fabric-0.19.5`；1.21.11 产物 `registryKey=1`，1.21.1 产物 `registryKey=0`。
- 安装器重打：`setup/PhiMCSetup.exe` = **77.3 MB（81,024,512 B）**，内嵌 42 项（19 jar + 19 Fabric API + 4 pez）。
- 1.21.1 StressTest：自动播放 **1000000 / PHI / 0 Miss**、不操作 **0 分 / 2500 Miss**；19/19 编译通过；数据校验 19/19 OK。