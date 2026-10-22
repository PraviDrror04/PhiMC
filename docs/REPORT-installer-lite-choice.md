# 安装器：完整版 / 打歌版二选一

> 目标：安装器支持在「③ 安装选项」里**二选一**（完整版 `phimc` / 打歌版 `phimcplayer`），
> 内嵌两者各 19 个版本的 jar，重打 `setup/PhiMCSetup.exe`，README 说清「不能同时装」。
> **mod 源码一行未改**（jar 只用已构建好的产物）。

## 1. 安装器改动（`setup/SetupForm.cs`）

| 行 | 改动 |
|---|---|
| 15-31 | `ModVariant` 增加 `LiteJarName`，一个 MC 版本 = 完整版 jar + 打歌版 jar + 同一个 Fabric API |
| 34-63 | `Variants` 19 行各补 `phimcplayer-<MC>.jar`；新增常量 `LiteModId = "phimcplayer"`、`LiteJarPrefix` |
| 96-100 | 新控件字段 `fullRadio` / `liteRadio` / `typeHint`，以及 `uiReady`（构造期不响应切换） |
| 131-132 | 窗口 880 / 最小 840（③ 多了两行） |
| 153 | 根布局第 3 行高度 `112 → 168`（5 行 × 26） |
| 305 | 实例列表「PhiMC」列 `100 → 160`（要放「打歌版已装（将换）」） |
| 318-342 | `optT.RowCount = 5`：第 0 行 `完整版（默认）` 单选 + 说明，第 1 行 `打歌版（PhiMC Player）` 单选 + 说明；两个 `CheckedChanged` 都接 `OnModTypeChanged()`；其后是原有的 Fabric API / 覆盖 / 删旧 jar 三行 |
| 344-352 | 删旧 jar 的说明改成「同一类型只能有一个 jar；另一种类型的 jar 每次安装都会清掉」 |
| 578-591 | 控件工厂 `MakeRadio()`（两个 RadioButton 同父 = 互斥） |
| 1082-1103 | `IsLite()` / `TypeLabel()` / `TypeModId()` / `JarNameFor(v)`：**当前类型决定要写哪个 jar 名** |
| 1113-1130 | `OnModTypeChanged()`（切类型 → 刷列 + 刷状态行 + 日志）/ `SetModType()`（`--type`、记住上次选择） |
| 1131-1157 | `RefreshVersionColumn()` / `InstalledText()`：「√ 完整版已装」「√ 打歌版已装」「打歌版已装（将换）」「× 两个都装了」「— 未装」 |
| 1159-1198 | `DetectInstalled()`：按文件名前缀 + `fabric.mod.json` 的 id 判断装的是哪一类（返回 `None/Full/Lite/Both`） |
| 853 | 实例列表那一列改为按当前类型渲染（切单选立刻变） |
| 1627-1665 | `ModJarsOfKind(bool lite)` / `SameTypeJars()` / `OtherTypeJars()` / `OwnModJars()`；**`phimcplayer-*` 不会被当成 `phimc-*`**（`phimc-` 后面必须紧跟 `-`） |
| 1699-1789 | `InstallModFiles()`：① 无条件删「另一种类型」的 jar（lite 有 `breaks:{phimc:"*"}`，共存即拒绝启动）② 按勾选删同类型的其它 MC 版本 ③ 同名同大小 + 没勾「覆盖」→ 跳过写入 ④ `WriteJarVerified` 回读校验 |
| 1779 | 写入 / 跳过 / 删除的日志逐条写清是哪个 jar（「√ 已删除冲突 jar（另一种版本）：…」「√ 已写入 mod（打歌版）：…」） |
| 1872-1969 | `RunInstall()` 打印版本类型；装完自检 `OtherTypeJars()`，还有冲突 jar 就把结果横幅降级成警示并提示手删 |
| 1959-1979 | 完成弹窗按类型给下一步：打歌版「进游戏按 V 打开曲库」，完整版「创造模式栏拿谱面播放器」 |
| 2001 | 提权重开时带 `--type lite|full`，不会因为提权丢掉刚选的类型 |
| 2170-2199 | `Diagnose()` 打印「版本类型 / 期望 jar」，并提示「mods 里有另一种版本的 jar」 |
| 2258 / 2274-2289 | `Main` 解析 `--type`；`--install` 静默安装同样按类型写/删 |
| 780-789 | 设置文件记住 `type=full|lite` |

## 2. 打包改动（`setup/build-setup.ps1`）

| 行 | 改动 |
|---|---|
| 37-59 | `$Variants` 每个版本多一个 `Lite = "phimcplayer-<MC>.jar"` |
| 79-86 | 校验 `SetupForm.cs` 里三种名字（完整版 / 打歌版 / Fabric API）都在，缺一个就 `throw` |
| 87-92 | 额外校验窗体里两个 mod id（`"phimc"`、`"phimcplayer"`）都在 |
| 106-126 | `Assert-Jar <jar> <期望 id> <MC> <标签>`：`fabric.mod.json` 的 `id` / `version` / `depends.minecraft` 三项全对才继续，**任一项不对直接 throw** |
| 133-154 | 每个版本：完整版校验（`id=phimc`）→ 打歌版校验（`id=phimcplayer`）→ Fabric API 存在性；三条 `/resource:` 依次内嵌（资源名 = 目标文件名，天然不冲突） |

内嵌资源命名规则：`/resource:<路径>,<目标文件名>`，即完整版 `phimc-<MC>.jar`、打歌版 `phimcplayer-<MC>.jar`、
Fabric API `fabric-api-<ver>+<MC>.jar`、内置谱面 `builtinNN.zip`。

> 实测：把任一 jar 的 id 改成别的（或不放某个打歌版 jar），脚本会在打包前报错退出，不会打出「少一半 jar」的安装器。

## 3. 产物

| 项 | 值 |
|---|---|
| `setup/PhiMCSetup.exe` | **95,663,104 字节（91.2 MB）**（改前 82,243,072 → 78.4 MB） |
| 内嵌资源 | **61 个**：19 完整版 + 19 打歌版 + 19 Fabric API + 4 张内置谱面（`.pez`） |
| 编译 | `csc.exe`（v4.0.30319）0 警告 0 错误；`--target:winexe` 启动正常，窗口标题「PhiMC 安装器」 |

## 4. 验证

夹具：`%TEMP%\phimc-e2e\.minecraft\versions\fabric-loader-0.16.9-1.21.1`（json 里 `inheritsFrom: 1.21.1`）。

| 用例 | 结果 |
|---|---|
| `--type lite`（空 mods） | 写入 `phimcplayer-1.21.1.jar`（697,507 B）+ `fabric-api-0.116.17+1.21.1.jar` |
| 再 `--type full` | 删 `phimcplayer-1.21.1.jar` →「√ 已删除冲突 jar（另一种版本）」，写入 `phimc-1.21.1.jar`（702,738 B） |
| 再 `--type lite` | 删 `phimc-1.21.1.jar`，回到打歌版 |
| mods 预置 `phimc-1.20.jar` + `phimcplayer-1.20.jar` 后装完整版 | 两者都被删（前者「同类型其它 MC 版本」、后者「冲突 jar」），只剩 1.21.1 |
| 不勾「覆盖」，同名同大小已存在 | 跳过写入（`LastWriteTime` 未变），日志「已有同名且大小一致的 jar，按勾选跳过写入」 |
| 不勾「删除旧 jar」，另存一个同类型 1.20 | 保留 + 警告「有 2 个完整版 jar」；**另一种类型仍被删掉** |
| 内置谱面 / 资源包 / 曲目目录 | 两种类型路径完全一致（`config/phimc/{songs,respack}`），4 张 `.pez` 正常投放 |
| GUI | 两个单选渲染正常、无重叠；完整版选中时列显示「打歌版已装（将换）」，切到打歌版显示「√ 打歌版已装」 |

## 5. 没做

- **没改 mod 源码 / 没重编 jar**（按约束）。
- 打歌版 jar 内 `fabric.mod.json` 的 `description` 还写着「Shift + / 打开曲库」，实际键位是 `V`
  （`PhiMCLiteClient.java:88` 是 `GLFW_KEY_V`，第 51/83 行的注释也还是旧的 `Shift + /`）。
  这是 jar 里已经构建好的字符串，改它需要重编打歌版 —— 本次没动，README 按真实键位写 `V`。
- 安装器没做「两个都装」的兼容模式（明确禁止，`breaks` 也不允许）。
- 打歌版不需要「批改 / 芯片」入口，安装器侧本来就没有这类 UI，因此无改动。
