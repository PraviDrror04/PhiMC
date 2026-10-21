# PhiMC — 在 Minecraft 里玩 Phigros 谱面

一个 Fabric mod：把官方 Phigros 谱面（`formatVersion` 1/3 的 JSON）放进 Minecraft 里跑，
判定线、音符、Tap/Hold/Drag/Flick 判定、连击计分全部自己实现。
**曲目和谱面不打包进 jar**，由玩家放进 `config/phimc/songs/`。

## 当前状态

| 模块 | 状态 | 证据 |
|---|---|---|
| 官方谱面解析 | ✅ | 真实谱面 Spasmodic SP：2500 音符 / 24 判定线 / 141.08s |
| **RPE（Re:PhiEdit）谱面解析** | ✅ | 3 个真实 `.pez`：3092 / 1739 / 2800 音符；与「照 prpr 手算」的独立实现比对，200 抽样误差 **≤3.6e-15**；时长误差 3.08 / 0.99 / 7.56s（第 3 首尾部留白 7.1s） |
| **MP3 音频（.pez 都是 mp3）** | ✅ | 纯 Java JLayer 解码：128.95 / 164.10 / 240.09s，与 `META.duration` 差 < 0.42s |
| **RPE 父判定线（father）/ 事件层求和 / 缓动** | ✅ | 合成谱 + 真实谱与手算 **Δ=0**（16:9 与 4:3 都对）；位置/旋转/透明度按 `tween.rs` 精确求值，不再折线化 |
| **RPE 音符可视字段（alpha/visibleTime/size/judgeArea）** | ✅ | Cyberneko 同一时刻屏上音符数 **806 → 13**（1455 个 fake 装饰音只在自己的可见窗口出现） |
| 判定线事件求值（移动/旋转/透明度/速度） | ✅ | 抽点验证无 NaN，事件有序性自检通过 |
| 判定 + 计分 + 评级 | ✅ | Autoplay 2500 全 Perfect → 1000000 · PHI；RPE 三首同样满分 0 Miss |
| 音频主时钟 | ✅ | 实测 `pos` 随时间线性前进（0.161 → 1.131s） |
| Fabric mod 编译 | ✅ | 19 个版本各一个 `phimc-<MC>.jar`（655～658 KB，314～320 个条目，含图标 + JLayer） |
| 游戏内加载 | ✅ | 真实 1.21.1 客户端日志：`phimc 0.1.0` → 找到 1 首 → `音符 2500 \| 判定线 24 \| 时长 141.1s \| 音频 music.ogg`，无异常 |
| **打击音** | ✅ | 内置 `click/drag/flick.ogg`（`sounds.json` + 3 个 `SoundEvent`，走 `SoundManager`）；资源包里有就用包里的（JavaSound，**每个音符新建一个 `Clip`、播完 STOP 回收**，同一种音可叠加不截断）；只有 Perfect / Good 播 |
| **设置页** | ✅ | 平板 / 选曲界面 →「设置」：打击音开关·音量、材质包选择、键盘开关；改一项立刻写 `profile.json`，重进游戏保持 |
| 游戏内 HUD / 按键 | ⚠️ 已实现未上机调手感 | 判定线按固定水平线渲染，未用旋转/移动事件 |

## 目录

```
仓库根/
├─ core/                            纯 Java 引擎，零 Minecraft 依赖
│  └─ src/main/java/dev/dsh/phimc/core/
│     ├─ Chart.java                 谱面模型：Line / Note / Ev
│     ├─ ChartParser.java           官方 PGR JSON 解析 + **格式自动识别**（PGR / RPE）
│     ├─ RpeParser.java             RPE（Re:PhiEdit，.pez）解析 → 同一个 Chart 模型
│     ├─ Judgement.java             判定窗口 / 权重 / 评级
│     ├─ Engine.java                判定引擎 + 可见音符
│     ├─ Clock.java                 时钟抽象
│     └─ audio/AudioSource.java     音频源接口（时间基准）
│        audio/WavSource.java       JavaSound 播放 + 播放位置查询（WAV / MP3 两种输入）
│        audio/Mp3ToWav.java        MP3 → PCM（JLayer，纯 Java，离线可用）
│  └─ src/test/java/.../AudioClockTest.java   时钟自测
│  └─ src/test/java/.../RpeAccept.java        RPE/.pez 验收自测（解析 + mp3 + autoplay）
│  └─ src/test/java/.../RpeProbe.java         四组探针（落屏率 / 对照 prpr 手算 / 父线 / 速度语义）
│  └─ src/test/java/.../EasingCheck.java      缓动表硬核对（对照源码文本展开的 30 项映射 + 93 个采样点）
├─ mod/                             Fabric 侧
│  ├─ settings.gradle               Stonecutter：versions '1.20.1' / '1.21.1' / '1.21.11'
│  ├─ build.gradle                 中心构建脚本（每个版本跑一遍，按 versions/<MC> 取依赖）
│  ├─ versions/<MC>/gradle.properties  每个 MC 版本的 MC / yarn / loader / fabric-api
│  ├─ build.ps1                     一键构建全部版本（含中文路径规避）
│  ├─ dist/phimc-<MC>.jar       产物（1.20 ~ 1.21.11 全系，19 个）
│  └─ src/main/java/dev/dsh/phimc/
│     ├─ client/PhiMCClient.java   入口、键位、每帧 tick
│     ├─ client/Session.java           会话：谱面 + 引擎 + 音频时钟
│     ├─ client/HudRenderer.java       HUD 绘制
│     ├─ client/SongEntry.java         曲目扫描 + 包内定位（info.txt / 后缀唯一）
│     └─ client/audio/OggToWav.java    OGG → PCM(WAV)，用 MC 自带的 stb_vorbis
├─ fixtures/spasmodic/              测试谱面（chart.json + music.ogg）
└─ lib/gson-2.11.0.jar              JSON（MC 自带同名库，headless 自测用这份）
   lib/jlayer-1.0.1.4.jar           MP3 解码（LGPL，构建时摊进 mod jar）
```

## 安装（推荐：可视化安装器）

双击 **`setup/PhiMCSetup.exe`**（78.0 MB = 81,768,448 字节：19 个版本的 jar（12.2 MB）+ 各自 Fabric API（40.6 MB）+ 4 张 .pez 谱面（25.3 MB）全内嵌）：

1. 自动填好 `.minecraft` 目录（不对可以点浏览）
2. 选版本 → 安装器读该实例的 MC 版本（`versions/<实例>/<实例>.json` 的 `inheritsFrom`），
   自动装对应的 `mods/phimc-<MC>.jar` + 同一版本号的 Fabric API。
   **支持 1.20 ~ 1.21.11 全系 19 个版本**；选不到就明确提示并拒绝安装（不会装错）。
   装之前会先清掉 `mods/` 里**同 mod id** 的其它 jar（按 `fabric.mod.json` 的 id 认，
   所以改名前的旧 jar 也会被清掉，不会出现两个版本互相打架）
3. 建好 `config/phimc/songs`
4. 把谱面包 `*.zip` **拖进窗口中间的框**（支持一次拖多个，也支持拖整个文件夹）
5. 进游戏按 F8 开始，F9 击打

### 从旧名字升级（配置目录自动迁移，**不会丢曲库**）

改名后配置目录从 `config/<旧名>/` 变成 `config/phimc/`。首次启动时模组会自动搬家
（`ConfigDir`，逐条见下），`songs/`、`profile.json`、`respack/`、`records.tsv` 一起走：

| 玩家的目录状态 | 模组做什么 |
|---|---|
| 只有 `config/<旧名>/` | 整个目录 **rename** 成 `config/phimc/`（跨卷 / 被占用时退化成复制，**复制不删源**） |
| 新旧目录都有（例如安装器先建了新的并塞了内置谱面） | **合并**：只补新目录里没有的文件，**同名文件一律保留新目录的版本，绝不覆盖** |
| 已经合并过（`config/phimc/.legacy-migrated` 标记存在） | 跳过 —— 否则玩家删掉的谱面会从旧目录“复活” |
| 全新安装 | 什么都不做，只建 `config/phimc/` |

迁移只在启动时跑一次，日志里会有一行 `[PhiMC] 旧配置目录已迁移到 config/phimc（MERGED）`。
自测见 `tools/migrate/ConfigMigrationTest.java`（28 条断言，`MIGRATED_MOVE` / `MERGED` / 不覆盖 / 不复活 全覆盖）。

命令行/静默模式（给脚本用）：

```bat
PhiMCSetup.exe --install --mc "D:\Games\.minecraft" --zip "Verruckt_Raimukun_IN.zip"
```

> 为什么之前 `install.ps1` 跑不起来：Windows 默认 ExecutionPolicy 是 Restricted，`.ps1` 会被直接拦掉。exe 不受这个限制。

## 用法

1. 装 Fabric Loader、Fabric API，Minecraft **1.20 ~ 1.21.11** 之一（19 个版本都在支持列表里）
   （1.20.1 用 Java 17，1.21.x 用 Java 21）。
2. 把对应版本的 `mod/dist/phimc-<MC>.jar` 丢进 `mods/`
   （版本必须对得上：`fabric.mod.json` 里写的是精确版本号）。
3. 谱面放进 `<游戏目录>/config/phimc/songs/`，支持三种形态：

   **a) 官方谱面包 zip（推荐）** —— 一个难度一个包：

   ```
   songs/Verruckt_Raimukun_IN.zip
   ├─ info.txt          Name / Song / Chart / Picture / Level / Composer / Illustrator / Charter
   ├─ Chart_IN.json     官方谱面（formatVersion 1/3）
   ├─ music.wav         44.1kHz 16bit 立体声（也接受 music.ogg）
   └─ defaultImage.png  曲绘
   ```

   **b) RPE 谱面包 `.pez`（Re:PhiEdit 导出）** —— 直接丢进去，不用改名：

   ```
   songs/Hardsync.pez
   ├─ info.txt                   Name / Level / Chart: / Song: / Picture: / Composer: / Charter:
   ├─ 1939818528944948.json      RPE 谱面（BPMList + META，没有 formatVersion）
   ├─ 1939818524948.mp3          音乐（mp3）
   └─ 1939818528944948.jpg       曲绘
   ```

   > 文件名是随机 id，**不靠名字匹配**：先按 `info.txt` 里的 `Chart:` / `Song:` 字段找，
   > 找不到再退到「全包唯一的 .json / .mp3」。格式也是自动识别的：
   > 有 `formatVersion` 走官方解析，有 `BPMList`+`META` 走 RPE 解析。

   **c) 目录** —— `songs/<曲名>/` 里放 `chart.json` + `music.ogg`/`music.wav`/`music.mp3`

4. 进游戏后按 **F8** 开始/停止（按顺序轮换曲目），**F9** 是击打键。

> 实测（Minecraft 1.21.1 客户端）：`找到 2 首` → `载入 Verruckt [IN Lv.?] | 音符 1285 | 判定线 24 | 时长 117.7s | 音频 music.wav`，无异常。

## 构建

```powershell
cd mod
.\build.ps1        # 产物会拷到 mod/dist/
```

`build.ps1` 里做了两件在特殊环境里必须的事，普通机器上可以直接 `gradle build`：

- **工程同步到 ASCII 路径再编译**：Gradle 的 `native-platform.dll` 在含中文的路径下加载会失败。
- **可指定 `JAVA_HOME` / `GRADLE_USER_HOME`**，避免被系统默认 JDK 版本影响。

另外两个踩过的坑：

- **Loom 版本要和 Gradle 匹配**：Loom 1.19 要求 Gradle ≥ 9.8，本项目钉在 Loom **1.8.13** + Gradle **8.10.2**。
- **必须给 Gradle 配代理**：如果系统走 `127.0.0.1:7897` 之类的本地代理，PowerShell 能上网但
  Gradle（Java）不会自动用它，表现为「下载卡在 0 字节」。解决办法是往
  `%GRADLE_USER_HOME%\gradle.properties` 写：
  ```properties
  systemProp.https.proxyHost=127.0.0.1
  systemProp.https.proxyPort=7897
  ```

## 跑自测（不需要 Minecraft）

```powershell
$jdk = "C:\dsh-tools\jdk21\bin"
# 注意 jlayer 也在 classpath 里（core/audio/Mp3ToWav.java 编译期依赖它）
$cp = "lib\gson-2.11.0.jar;lib\jlayer-1.0.1.4.jar"
& "$jdk\javac.exe" -encoding UTF-8 -d out -cp $cp `
    (Get-ChildItem core\src\main\java -Recurse -Filter *.java | % FullName)
& "$jdk\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" `
    dev.dsh.phimc.core.Main fixtures\spasmodic\chart.json
& "$jdk\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" `
    dev.dsh.phimc.core.AudioClockTest

# RPE / .pez 验收：直接吃真实谱面包，打印曲名/难度/BPM/音符数/时长并断言
& "$jdk\javac.exe" -encoding UTF-8 -d out -cp "out;$cp" `
    (Get-ChildItem core\src\test\java -Recurse -Filter *.java | % FullName)
& "$jdk\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" `
    dev.dsh.phimc.core.RpeAccept
& "$jdk\java.exe" "-Dstdout.encoding=UTF-8" -cp "out;$cp" `
    dev.dsh.phimc.core.EasingCheck
```

## 格式结论（踩过的坑）

- 时间单位不是秒，是 **1/32 拍**：`秒 = time * 60 / (32 * bpm)`。
  验证：`bpm=200, time=128` → 1.2s，与 note 自带 `floorPosition=1.2` 吻合。
- `floorPosition` 就是秒，且 speed 事件每段都给了起点累计值 → 当前下落距离 =
  `起点值 + (t - t0) * speed`，不用自己积分。
- `judgeLineMoveEvents` 的 `start/end` 是 x、`start2/end2` 是 y；
  `judgeLineRotateEvents` / `judgeLineDisappearEvents` 只用 `start/end`。
- 事件里会出现 `startTime = -999999` 的哨兵段，二分查找要兜住。
- 音符类型 `1=Tap, 2=Drag, 3=Hold, 4=Flick`，`isFake` 不参与计分。

### RPE（Re:PhiEdit / .pez）

- **时间不是 1/32 拍**，是三元组 `[i, n, d] = i + n/d`（拍），要按 `BPMList` 折成秒：
  第 k 段 `秒 = 段起点秒 + (拍 - 段起点拍) * 60 / bpm[k]`。变速谱（如推活天使 17 个 BPM 段）必须分段折。
- **音符类型编号和官方不一样**：RPE 是 `1=Tap, 2=Hold, 3=Flick, 4=Drag`（官方是 `3=Hold, 4=Flick`），照抄会全错。
- **速度事件要乘 `10/45`**：`rpe.rs:24` 的 `SPEED_RATIO = 10/45/HEIGHT_RATIO`，
  而我们的 `Chart.heightAt` 已经负责除 `HEIGHT_RATIO`，所以只乘 `10/45`。
- **坐标是画布中心制**：`positionX ∈ [-675, 675]`、`moveY ∈ [-450, 450]`（中位数就是 0），
  而我们的 `xAt/yAt` 是「从左上角算的比例」，所以 `moveY` 要 `0.5 + y/900`，少那个 `0.5` 整条线会掉到屏幕外。
- **`eventLayers` 是相加的**（`anim.rs:27-33`）：同一条线可能有多层移动/旋转，值是求和，不是覆盖。
- **`easingType` 是数字编号，而且 `RPE_TWEEN_MAP` 是一位对一位的**：
  `1=Linear, 2=SineOut, 3=SineIn, 4=QuadOut, 5=QuadIn, 6=SineInOut, 7=QuadInOut, 8=CubicOut,
   9=CubicIn, 10=QuartOut, 11=QuartIn, 12=CubicInOut, 13=QuartInOut, 14=QuintOut, 15=QuintIn,
   16=ExpoOut, 17=ExpoIn, 18=CircOut, 19=CircIn, 20=BackOut, 21=BackIn, 22=CircInOut,
   23=BackInOut, 24=ElasticOut, 25=ElasticIn, 26=BounceOut, 27=BounceIn, 28=BounceInOut, 29=ElasticInOut`
  （表 30 项，`[0]` 不用；`QuintInOut`/`ExpoInOut` 在 RPE 里没有对应编号）。
  **这条错一位就全错**：曾经把 2 当线性，结果 30%/20%/76% 的事件用了错误的曲线，
  位置最大差到 0.58 屏高。现在 `EasingCheck` 拿「从 `parse.rs` / `tween.rs` 源码文本展开的数字」逐项断言。
- 位置/旋转/透明度**不能折线化**：推活 76% 的事件带缓动，折线化会和 prpr 差 1e-2 量级。
  `Chart.Ev` 现在直接挂缓动函数（33 条曲线 + `bezier` + `easingLeft/Right` 的 ClampedTween）。
- **`RPEVersion` 只影响速度事件**：`>=170` 速度按 `easingType` 走（`SpeedIntegralTween`），
  `<170` 速度段内**线性**并忽略缓动（`rpe.rs:415-490`，梯形积分）。
- **父子判定线要按 aspect 合成**：`fetch_pos(子) = fetch_pos(父) + R(fetch_rot(父))·now_translation(子)`，
  而 `now_translation` 里 `tr.y /= aspect`（`object.rs:55`），所以求值函数必须带宽高比参数，
  不能把父变换烘进 `moveX/moveY`。三个包各有 18 / 11 / 26 条子线，样本里父链只有一层但实现是递归的。
- **音符的 `visibleTime` 是「判定前多久才出现」**，不是「淡入时长」：`visible_time >= time` 时是静态透明度，
  否则 `(time - visible_time)` 之前完全透明（`rpe.rs:578-587`）。RPE 谱大量用 fake 音符 + 0.1~0.2s 的
  visibleTime 做「打击闪光」，照抄这段画面才不脏（Cyberneko 修前同时画 806 个音符，修后 13 个）。
- **`alpha/size/pos/yControl` 的自变量是高度不是时间**（`note.rs:171-173`），值是**相乘**的乘数，
  且缓动是「错位」的（区间 `[x_i, x_i+1]` 用第 i+1 个事件的 easing，`rpe.rs:619-624`）。
- **同一时刻取哪一段**：prpr 的 `Anim` 光标只前进，等价于「最后一个 `t0 <= t` 的段」；
  事件首尾相接时（音符判定时刻经常正好落在事件边界）用二分查找随便落一边就会差一大截。
- **事件层是相加的**：`Chart.Line` 的移动/旋转/透明度现在是「每条事件层一条轨道」，
  求值时求和（`anim.rs:27-33`），这样 2 层非线性缓动叠加也精确。
- **mp3 不是 JavaSound 能解的**：`.pez` 里音乐一律 `.mp3`，用 JLayer 解成 PCM 再交给同一个 `WavSource`。

## 开源参考

| 项目 | 用途 |
|---|---|
| [BeatCraft](https://github.com/Swifter1243/BeatCraft) | MC 里做音游的完整先例（音频同步/音符生成/选曲） |
| [PhiZone/player](https://github.com/PhiZone/player) | HTML5 版 Phi 谱面播放器，判定线事件与判定流程的短参考 |
| [Phira](https://github.com/TeamFlos/phira) | 开源音游客户端，判定窗口与手感对照 |
| [Phira 谱面文档](https://teamflos.github.io/phira-docs/chart-standard/chart-format/phi/root.html) | 官方谱面结构说明（单位换算依据） |
| [PhiCommonChart 文档](https://docs.nuanr-mxi.com/en/chart_format/judge_line.html) | 事件/父线/锚点语义 |
| [Cube Rhythm](https://www.minebbs.com/resources/cube-rhythm-minecraft.9321/) | MC 上的 3D 判定面音游（Skript） |
| [phigros-charts-repo](https://github.com/yuameshi/phigros-charts-repo) | 测试谱面来源 |
| [Phira rpe.rs](https://github.com/TeamFlos/phira/blob/main/prpr/src/parse/rpe.rs) | RPE 解析的唯一依据（GPL，只做阅读参考，没有拷贝代码） |
| [JLayer](https://github.com/umjammer/jlayer) | 纯 Java MP3 解码（LGPL，`lib/jlayer-1.0.1.4.jar`） |

## 已知简化 / 下一步

1. **HUD 只用矩形**：判定线的 move/rotate 事件还没接到渲染（`Engine.visible` 已经算出来了）。
2. **击打不校验位置**：目前 F9 打最近音符，下一步接鼠标点击 + x 坐标匹配。
3. **没有选曲界面**：F8 轮换，下一步做列表。
4. **延迟校准**：首次进入给 ±ms 滑条，写回 config。
5. **hold 尾判定**：现在按「保持到结束」算完美，之后要做按住状态与断连。
6. **RPE 的 `extended.scaleX/scaleY/incline` 没接**：我们的判定线是固定长度的轴对齐矩形
   （`ctx.fill` 画不了平行四边形），要接得先改渲染。三首里 `scaleYEvents` 全空、`scaleXEvents` 只有推活 20 个。
7. **RPE 的贴图层没接**：`colorEvents`/`textEvents`/`paintEvents`/`gifEvents`、贴图判定线（`Texture != line.png`）、
   音符的 `tint`、RPE 音符的 per-note `hitsound` —— 分别是着色器/文字/贴图/音效系统，本项目还没有。
   （默认的 click / drag / flick 打击音已经接了，见上表「打击音」；没有接的是谱面里逐音符指定的自定义打击音。）
8. **非线性缓动的「速度」事件仍是分段线性**：`Chart.heightAt` 的 keyframe 模型只能表达「段内常量斜率」，
   而 prpr 给速度积分单独造了 `SpeedIntegralTween`。折线粒度 20ms（8~64 段），块边界精确。
   三首的速度事件 100% 是线性的，实际误差 0。
9. **重来时 mp3 要重解**（暂停菜单「重来」会在渲染线程上跑 ~0.6s 解码），后续可以缓存 PCM。
10. **音符画法**（有资源包 / 纯色兜底两套）：
    - **有贴图**：完全照 prpr —— tap/flick/drag 高 = 宽 × 贴图高宽比（`note.rs:114-128`），
      hold 三段（`core/resource.rs:168-182`）：头/尾整块贴在身体两端的**外侧**、源图分别是
      贴图底部/顶部 `holdAtlas` 段，高度 = `capPx * noteW / texW`（= `note.rs:324/342` 的
      `hf.y * 2`），身体默认竖向拉伸、`holdRepeat: true` 时按自然比例平铺，头在命中后隐藏
      （`holdKeepHead` 可保留）。这套逻辑在 `core/HoldLayout`，`PlayScreen.drawHoldTexture` 只负责画。
    - **纯色兜底**（没有资源包 / 缺贴图）：是使用方指定的风格，不是 prpr —— tap/flick/drag/hold 头
      **厚度统一 = 上一版 tap 的厚度**（≈音符宽 × 100/998，即 `click.png` 的高宽比）、
      hold **头与身体颜色分开**（头 = 浅青 `#8CEFF2`，身体 = 浅蓝 `#BFE9FF`）、hold 的**尾巴不单独画一块**、
      所有音符描 **1px 近黑细边**。prpr 的 flick/drag 厚度是 172/998、50/948 且没有描边。
      渲染常数在 `PlayScreen.NOTE_THICK_FRAC` / `NOTE_EDGE_PX` / `NOTE_EDGE_RGB` / `HOLD_ACCENT` / `HOLD_HEAD_RGB`。
    连击字号也在 `PlayScreen.COMBO_SCALE`（数字，现在 2×）和 `COMBO_LABEL_SCALE`（"COMBO" 小字，现在 1×），
    改一个数就能调大小（1× = MC 原字号，一行 9px）。
11. **RPE 里「停在屏幕外」的音符没做特殊处理**：谱面把判定线停在画布边缘外（例如 `moveX=685`、`moveY=-460`）
    或停在 ±22221 这种哨兵坐标时，线上的音符确实在屏外（Cyberneko 25 个、Hardsync 97 个、推活 348 个，
    出界距离中位 1.2%）。这和 prpr 的换算逐位一致，没有为了「落屏率 99%」去调坐标缩放。

## 版权

谱面与音频版权归 Pigeon Games 及各曲师所有。本仓库不含任何官方音频/曲绘/PV；
`fixtures/` 里的样本仅用于解析自测，来自公开的 fan-made 谱面仓库。
唯一的例外是打击音：`mod/src/main/resources/assets/phimc/sounds/hit/{click,drag,flick}.ogg`
是从**用户提供的 Phigros 官方资源包**里取出的三段打击音效（其余内容一律不进仓库；
用户自己的资源包仍由 `config/phimc/respack/*.zip` 在运行时读取）。
mod 图标由 AI 生成（bailian `bl image generate`）。

`lib/jlayer-1.0.1.4.jar` 是 JLayer（LGPL 2.1），构建时被摊平进每个 `phimc-<MC>.jar`，
用来解 `.pez` 里的 mp3；未做任何修改。源码见 <https://github.com/umjammer/jlayer>。
