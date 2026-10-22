using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Security.Principal;
using System.Windows.Forms;

namespace PhiMCSetup
{
    public class SetupForm : Form
    {
        /// <summary>
        /// 一个受支持的 MC 版本 = 两个互斥的 mod jar（完整版 / 打歌版）+ 一个对应版本的 Fabric API。
        /// </summary>
        private sealed class ModVariant
        {
            public readonly string Mc;            // "1.21.1"
            public readonly string JarName;       // 完整版：内嵌资源名 = mods/ 里的文件名（phimc-&lt;MC&gt;.jar）
            public readonly string LiteJarName;   // 打歌版：同上（phimcplayer-&lt;MC&gt;.jar）
            public readonly string FabricApiName; // 同上，Fabric API（两种版本共用同一个 Fabric API）

            public ModVariant(string mc, string jar, string lite, string api)
            {
                Mc = mc;
                JarName = jar;
                LiteJarName = lite;
                FabricApiName = api;
            }
        }

        /// <summary>
        /// 支持版本表。内嵌资源名（csc /resource:路径,名字）就是这里的名字，也就是装进 mods/ 的文件名。
        /// 新增一个版本：这里加一行 + setup/build-setup.ps1 的 $Variants 加一行（脚本会校验两边一致）。
        /// 两种版本「二选一」：完整版 mod id = phimc，打歌版 mod id = phimcplayer，class 重复不能共存。
        /// </summary>
        private static readonly ModVariant[] Variants = new ModVariant[]
        {
            new ModVariant("1.20",    "phimc-1.20.jar",    "phimcplayer-1.20.jar",    "fabric-api-0.83.0+1.20.jar"),
            new ModVariant("1.20.1",  "phimc-1.20.1.jar",  "phimcplayer-1.20.1.jar",  "fabric-api-0.92.12+1.20.1.jar"),
            new ModVariant("1.20.2",  "phimc-1.20.2.jar",  "phimcplayer-1.20.2.jar",  "fabric-api-0.91.6+1.20.2.jar"),
            new ModVariant("1.20.3",  "phimc-1.20.3.jar",  "phimcplayer-1.20.3.jar",  "fabric-api-0.91.1+1.20.3.jar"),
            new ModVariant("1.20.4",  "phimc-1.20.4.jar",  "phimcplayer-1.20.4.jar",  "fabric-api-0.97.3+1.20.4.jar"),
            new ModVariant("1.20.5",  "phimc-1.20.5.jar",  "phimcplayer-1.20.5.jar",  "fabric-api-0.97.8+1.20.5.jar"),
            new ModVariant("1.20.6",  "phimc-1.20.6.jar",  "phimcplayer-1.20.6.jar",  "fabric-api-0.100.8+1.20.6.jar"),
            new ModVariant("1.21",    "phimc-1.21.jar",    "phimcplayer-1.21.jar",    "fabric-api-0.102.0+1.21.jar"),
            new ModVariant("1.21.1",  "phimc-1.21.1.jar",  "phimcplayer-1.21.1.jar",  "fabric-api-0.116.17+1.21.1.jar"),
            new ModVariant("1.21.2",  "phimc-1.21.2.jar",  "phimcplayer-1.21.2.jar",  "fabric-api-0.106.1+1.21.2.jar"),
            new ModVariant("1.21.3",  "phimc-1.21.3.jar",  "phimcplayer-1.21.3.jar",  "fabric-api-0.114.1+1.21.3.jar"),
            new ModVariant("1.21.4",  "phimc-1.21.4.jar",  "phimcplayer-1.21.4.jar",  "fabric-api-0.119.4+1.21.4.jar"),
            new ModVariant("1.21.5",  "phimc-1.21.5.jar",  "phimcplayer-1.21.5.jar",  "fabric-api-0.128.2+1.21.5.jar"),
            new ModVariant("1.21.6",  "phimc-1.21.6.jar",  "phimcplayer-1.21.6.jar",  "fabric-api-0.128.2+1.21.6.jar"),
            new ModVariant("1.21.7",  "phimc-1.21.7.jar",  "phimcplayer-1.21.7.jar",  "fabric-api-0.129.0+1.21.7.jar"),
            new ModVariant("1.21.8",  "phimc-1.21.8.jar",  "phimcplayer-1.21.8.jar",  "fabric-api-0.136.1+1.21.8.jar"),
            new ModVariant("1.21.9",  "phimc-1.21.9.jar",  "phimcplayer-1.21.9.jar",  "fabric-api-0.134.1+1.21.9.jar"),
            new ModVariant("1.21.10", "phimc-1.21.10.jar", "phimcplayer-1.21.10.jar", "fabric-api-0.138.4+1.21.10.jar"),
            new ModVariant("1.21.11", "phimc-1.21.11.jar", "phimcplayer-1.21.11.jar", "fabric-api-0.141.6+1.21.11.jar")
        };

        /// <summary>完整版 mod id（= fabric.mod.json 的 id = 资源命名空间）。</summary>
        private const string ModId = "phimc";
        private const string ModJarPrefix = ModId + "-";
        /// <summary>打歌版 mod id：另一个 mod（另一个 jar、另一个 fabric.mod.json）。</summary>
        private const string LiteModId = "phimcplayer";
        private const string LiteJarPrefix = LiteModId + "-";
        private const string ModJarSuffix = ".jar";

        // ==================================================================
        // 配色 / 字体：浅色系统风 —— 白卡片 + 一个强调色（蓝），
        // 红/绿只用来表达「失败 / 成功」，不做装饰。
        // ==================================================================
        private static readonly Color C_BG = Color.FromArgb(245, 246, 248);      // 窗体底
        private static readonly Color C_CARD = Color.White;                      // 分组卡片
        private static readonly Color C_TEXT = Color.FromArgb(31, 35, 40);       // 主文字
        private static readonly Color C_MUTED = Color.FromArgb(110, 118, 129);   // 说明小字
        private static readonly Color C_BORDER = Color.FromArgb(222, 226, 232);  // 描边
        private static readonly Color C_ACCENT = Color.FromArgb(37, 99, 235);    // 唯一强调色
        private static readonly Color C_OK = Color.FromArgb(21, 128, 61);        // 成功
        private static readonly Color C_ERR = Color.FromArgb(185, 28, 28);       // 失败
        private const string UiFamily = "Microsoft YaHei UI";
        private const string MonoFamily = "Consolas";

        // ==================================================================
        // 控件
        // ==================================================================
        private TextBox mcDirBox;
        /** 实例列表：实例名 / MC 版本 / 加载器 / 是否已装 PhiMC（原来的下拉框升级成列表）。 */
        private ListView instanceList;
        private CheckBox isoBox;
        private CheckBox apiBox;
        private CheckBox overwriteBox;
        private CheckBox deleteOldBox;
        /** 版本类型单选：完整版（phimc）/ 打歌版（phimcplayer），二选一。 */
        private RadioButton fullRadio;
        private RadioButton liteRadio;
        private Label typeHint;
        /** 构造期控件还没齐：单选切换的回调在界面就绪前不做事。 */
        private bool uiReady;
        private RichTextBox log;
        private Label dropHint;
        private Label statusLabel;
        private Label rootLabel;
        private Label respackHint;
        private Label resultLabel;
        private Label progressLabel;
        private ProgressBar progressBar;
        private Button installBtn;
        private Button respackBtn;
        private Button songsBtn;
        private Button modsBtn;
        private Button diagBtn;
        private Button builtinBtn;
        private Button refreshBtn;
        private Button fabricBtn;

        /** 实例目录名，和 instanceList 的行一一对应（行 = 安装目标）。 */
        private readonly System.Collections.Generic.List<string> instNames =
            new System.Collections.Generic.List<string>();
        /** 日志纯文本（RichTextBox.Text 的分行符不是 \r\n，导出文件用这个）。 */
        private readonly System.Text.StringBuilder logText = new System.Text.StringBuilder();
        /** 安装进行中：挡住 DoEvents 造成的重入。 */
        private bool busy;
        /** RefreshVersions 选中的行号（窗体真正显示后再应用一次：ListViewItem.Selected 在句柄建立前不一定生效）。 */
        private int wantedIndex = -1;

        public SetupForm()
        {
            Text = "PhiMC 安装器";
            ClientSize = new Size(920, 880);
            MinimumSize = new Size(900, 840);
            StartPosition = FormStartPosition.CenterScreen;
            AllowDrop = true;
            BackColor = C_BG;
            ForeColor = C_TEXT;
            Font = new Font(UiFamily, 9F);
            DoubleBuffered = true;

            // ---- 根布局：一行一个区块，固定高 + 一个「日志」百分比我伸缩。
            //      行高按各组内容的实际需要算（内容高 + GroupBox 标题/内边距），
            //      窗口缩到 MinimumSize 也只是日志变矮，不会有控件互相压住 ----
            TableLayoutPanel root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.ColumnCount = 1;
            root.RowCount = 11;
            root.BackColor = C_BG;
            root.Padding = new Padding(18, 12, 18, 12);
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 46F));   // 0 标题区
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 128F));  // 1 游戏目录（4 行内容）
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 182F));  // 2 实例列表（工具栏 + 表头 + 4 行）
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 168F));  // 3 安装选项（5 行 × 26：版本类型 2 行）
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 44F));   // 4 主按钮行
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 20F));   // 5 资源包说明
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34F));   // 6 拖入区
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 18F));   // 7 进度条
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34F));   // 8 进度文字 + 结果横幅
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));   // 9 日志（唯一伸缩行）
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34F));   // 10 底部
            Controls.Add(root);

            // ---------------- 0. 标题区 ----------------
            Panel header = new Panel();
            header.Dock = DockStyle.Fill;
            header.BackColor = C_BG;
            root.Controls.Add(header, 0, 0);

            Label title = new Label();
            title.Text = "PhiMC 安装器";
            title.Font = new Font(UiFamily, 15F, FontStyle.Bold);
            title.ForeColor = C_TEXT;
            title.AutoSize = false;
            title.SetBounds(0, 0, 600, 26);
            title.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            header.Controls.Add(title);

            Label subtitle = new Label();
            subtitle.Text = "在 Minecraft 里玩 Phigros 谱面 · 自带 " + Variants.Length
                + " 个 MC 版本（" + Variants[0].Mc + " ~ " + Variants[Variants.Length - 1].Mc
                + "）的完整版 / 打歌版 mod + Fabric API（两种版本二选一）";
            subtitle.Font = new Font(UiFamily, 9F);
            subtitle.ForeColor = C_MUTED;
            subtitle.AutoSize = false;
            subtitle.AutoEllipsis = true;
            subtitle.SetBounds(0, 25, 600, 17);
            subtitle.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            header.Controls.Add(subtitle);

            Panel headerLine = new Panel();
            headerLine.BackColor = C_BORDER;
            headerLine.Dock = DockStyle.Bottom;
            headerLine.Height = 1;
            header.Controls.Add(headerLine);

            // ---------------- 1. 游戏目录 ----------------
            GroupBox dirGroup = MakeGroup("① 游戏目录");
            root.Controls.Add(dirGroup, 0, 1);

            TableLayoutPanel dirT = new TableLayoutPanel();
            dirT.Dock = DockStyle.Fill;
            dirT.ColumnCount = 3;
            dirT.RowCount = 4;
            dirT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 104F));
            dirT.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            dirT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 92F));
            dirT.RowStyles.Add(new RowStyle(SizeType.Absolute, 30F));
            dirT.RowStyles.Add(new RowStyle(SizeType.Absolute, 24F));
            dirT.RowStyles.Add(new RowStyle(SizeType.Absolute, 20F));
            dirT.RowStyles.Add(new RowStyle(SizeType.Absolute, 20F));
            dirGroup.Controls.Add(dirT);

            dirT.Controls.Add(MakeLabel(".minecraft 目录", 9F, FontStyle.Regular, C_TEXT,
                ContentAlignment.MiddleLeft), 0, 0);

            mcDirBox = new TextBox();
            mcDirBox.Dock = DockStyle.Fill;
            mcDirBox.Font = new Font(UiFamily, 9F);
            mcDirBox.Text = DetectMinecraft();
            dirT.Controls.Add(mcDirBox, 1, 0);

            Button browse = MakeButton("浏览…", false);
            browse.Dock = DockStyle.Fill;
            browse.Margin = new Padding(6, 2, 0, 2);
            browse.Click += delegate { BrowseMinecraft(); };
            dirT.Controls.Add(browse, 2, 0);

            isoBox = new CheckBox();
            isoBox.Text = "版本隔离（强制启用：mod / config 一律装到 versions\\<实例>\\ 下）";
            isoBox.Font = new Font(UiFamily, 9F);
            isoBox.ForeColor = C_MUTED;
            isoBox.Checked = true;
            isoBox.Enabled = false;
            isoBox.AutoSize = true;
            isoBox.Margin = new Padding(0);
            dirT.Controls.Add(isoBox, 0, 1);
            dirT.SetColumnSpan(isoBox, 3);

            rootLabel = new Label();
            rootLabel.Font = new Font(UiFamily, 9F);
            rootLabel.ForeColor = C_ACCENT;
            rootLabel.AutoEllipsis = true;
            rootLabel.TextAlign = ContentAlignment.MiddleLeft;
            rootLabel.Dock = DockStyle.Fill;
            rootLabel.Margin = new Padding(0);
            dirT.Controls.Add(rootLabel, 0, 2);
            dirT.SetColumnSpan(rootLabel, 3);

            statusLabel = new Label();
            statusLabel.Font = new Font(UiFamily, 9F);
            statusLabel.ForeColor = C_MUTED;
            statusLabel.AutoEllipsis = true;
            statusLabel.TextAlign = ContentAlignment.MiddleLeft;
            statusLabel.Dock = DockStyle.Fill;
            statusLabel.Margin = new Padding(0);
            dirT.Controls.Add(statusLabel, 0, 3);
            dirT.SetColumnSpan(statusLabel, 3);

            // ---------------- 2. 实例列表（三列 + 是否已装 PhiMC） ----------------
            GroupBox instGroup = MakeGroup("② 选择实例（点一行 = 安装目标）");
            root.Controls.Add(instGroup, 0, 2);

            TableLayoutPanel instT = new TableLayoutPanel();
            instT.Dock = DockStyle.Fill;
            instT.ColumnCount = 4;
            instT.RowCount = 2;
            instT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 76F));   // 刷新
            instT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 96F));   // 装 Fabric
            instT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150F));  // 提示
            instT.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            instT.RowStyles.Add(new RowStyle(SizeType.Absolute, 28F));
            instT.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            instGroup.Controls.Add(instT);

            refreshBtn = MakeButton("↻ 刷新", false);
            refreshBtn.Dock = DockStyle.Fill;
            refreshBtn.Margin = new Padding(0, 0, 6, 4);
            refreshBtn.Click += delegate { RefreshVersions(true); };
            instT.Controls.Add(refreshBtn, 0, 0);

            fabricBtn = MakeButton("装 Fabric", false);
            fabricBtn.Dock = DockStyle.Fill;
            fabricBtn.Margin = new Padding(0, 0, 6, 4);
            fabricBtn.Click += delegate { InstallFabric(); };
            instT.Controls.Add(fabricBtn, 1, 0);

            instT.Controls.Add(MakeLabel("每行 = 一个启动器实例", 8.5F, FontStyle.Regular, C_MUTED,
                ContentAlignment.MiddleLeft), 2, 0);

            instanceList = new ListView();
            instanceList.Dock = DockStyle.Fill;
            instanceList.View = View.Details;
            instanceList.FullRowSelect = true;
            instanceList.MultiSelect = false;
            instanceList.HideSelection = false;
            instanceList.HeaderStyle = ColumnHeaderStyle.Nonclickable;
            instanceList.BorderStyle = BorderStyle.FixedSingle;
            instanceList.BackColor = C_CARD;
            instanceList.ForeColor = C_TEXT;
            instanceList.Font = new Font(UiFamily, 9F);
            instanceList.Margin = new Padding(0);
            instanceList.Columns.Add("实例目录", 320, HorizontalAlignment.Left);
            instanceList.Columns.Add("MC 版本", 90, HorizontalAlignment.Left);
            instanceList.Columns.Add("加载器", 130, HorizontalAlignment.Left);
            instanceList.Columns.Add("PhiMC", 160, HorizontalAlignment.Left);
            instanceList.SelectedIndexChanged += delegate { ApplyLayoutForVersion(); };
            instanceList.Resize += delegate { FitInstanceColumns(); };
            instT.Controls.Add(instanceList, 0, 1);
            instT.SetColumnSpan(instanceList, 4);

            // ---------------- 3. 安装选项（每项后面一句小字说明） ----------------
            GroupBox optGroup = MakeGroup("③ 安装选项");
            root.Controls.Add(optGroup, 0, 3);

            TableLayoutPanel optT = new TableLayoutPanel();
            optT.Dock = DockStyle.Fill;
            optT.ColumnCount = 2;
            optT.RowCount = 5;
            optT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 250F));
            optT.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            optT.RowStyles.Add(new RowStyle(SizeType.Absolute, 26F));   // 版本类型：完整版
            optT.RowStyles.Add(new RowStyle(SizeType.Absolute, 26F));   // 版本类型：打歌版
            optT.RowStyles.Add(new RowStyle(SizeType.Absolute, 26F));   // Fabric API
            optT.RowStyles.Add(new RowStyle(SizeType.Absolute, 26F));   // 覆盖
            optT.RowStyles.Add(new RowStyle(SizeType.Absolute, 26F));   // 删旧 jar
            optGroup.Controls.Add(optT);

            // 版本类型：单选（两个 RadioButton 同父 = 互斥），默认「完整版」
            fullRadio = MakeRadio("完整版（默认）", true);
            optT.Controls.Add(fullRadio, 0, 0);
            optT.Controls.Add(MakeLabel("物品 · 生存玩法 · 在线曲库 —— 装 phimc-<MC>.jar",
                8.5F, FontStyle.Regular, C_MUTED, ContentAlignment.MiddleLeft), 1, 0);

            liteRadio = MakeRadio("打歌版（PhiMC Player）", false);
            optT.Controls.Add(liteRadio, 0, 1);
            typeHint = MakeLabel("只打歌，按 V 打开曲库，成绩存本地，不给任何物品 —— 装 phimcplayer-<MC>.jar",
                8.5F, FontStyle.Regular, C_MUTED, ContentAlignment.MiddleLeft);
            optT.Controls.Add(typeHint, 1, 1);

            // 切换类型：立即刷新实例列表的「PhiMC」列（完整版已装 / 打歌版已装 / 未装）
            fullRadio.CheckedChanged += delegate { if (fullRadio.Checked) OnModTypeChanged(); };
            liteRadio.CheckedChanged += delegate { if (liteRadio.Checked) OnModTypeChanged(); };

            apiBox = MakeCheck("装 Fabric API", true);
            optT.Controls.Add(apiBox, 0, 2);
            optT.Controls.Add(MakeLabel("mod 依赖它；没有 Fabric API 游戏里什么都不会加载",
                8.5F, FontStyle.Regular, C_MUTED, ContentAlignment.MiddleLeft), 1, 2);

            overwriteBox = MakeCheck("覆盖已存在的 jar", true);
            optT.Controls.Add(overwriteBox, 0, 3);
            optT.Controls.Add(MakeLabel("不勾选时：mods 里已有同名且大小一致的 jar 就跳过（不重写）",
                8.5F, FontStyle.Regular, C_MUTED, ContentAlignment.MiddleLeft), 1, 3);

            deleteOldBox = MakeCheck("删除其它版本的旧 PhiMC jar", true);
            optT.Controls.Add(deleteOldBox, 0, 4);
            optT.Controls.Add(MakeLabel("同一类型只能有一个 jar；另一种类型的 jar 每次安装都会清掉（否则启动即冲突）",
                8.5F, FontStyle.Regular, C_MUTED, ContentAlignment.MiddleLeft), 1, 4);

            // ---------------- 4. 主按钮行 ----------------
            TableLayoutPanel actT = new TableLayoutPanel();
            actT.Dock = DockStyle.Fill;
            actT.ColumnCount = 5;
            actT.RowCount = 1;
            actT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 250F));
            actT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 168F));
            actT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 140F));
            actT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 140F));
            actT.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            actT.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            root.Controls.Add(actT, 0, 4);

            installBtn = MakeButton("立即安装 mod + 建好文件夹", true);
            installBtn.Dock = DockStyle.Fill;
            installBtn.Margin = new Padding(0, 2, 8, 2);
            installBtn.Click += delegate { FullInstall(); };
            actT.Controls.Add(installBtn, 0, 0);

            respackBtn = MakeButton("打开资源包文件夹", false);
            respackBtn.Dock = DockStyle.Fill;
            respackBtn.Margin = new Padding(0, 2, 8, 2);
            respackBtn.Click += delegate { OpenResPackFolder(); };
            actT.Controls.Add(respackBtn, 1, 0);

            songsBtn = MakeButton("打开曲目文件夹", false);
            songsBtn.Dock = DockStyle.Fill;
            songsBtn.Margin = new Padding(0, 2, 8, 2);
            songsBtn.Click += delegate { OpenFolder(SongsDir()); };
            actT.Controls.Add(songsBtn, 2, 0);

            modsBtn = MakeButton("打开 mods 文件夹", false);
            modsBtn.Dock = DockStyle.Fill;
            modsBtn.Margin = new Padding(0, 2, 8, 2);
            modsBtn.Click += delegate { OpenFolder(ModsDir()); };
            actT.Controls.Add(modsBtn, 3, 0);

            // ---------------- 5. 资源包文件夹说明 ----------------
            respackHint = new Label();
            respackHint.Text = "把材质包 .zip 放进这个文件夹，游戏内设置页即可选择";
            respackHint.Font = new Font(UiFamily, 8.5F);
            respackHint.ForeColor = C_MUTED;
            respackHint.AutoEllipsis = true;
            respackHint.TextAlign = ContentAlignment.MiddleLeft;
            respackHint.Dock = DockStyle.Fill;
            respackHint.Margin = new Padding(0);
            root.Controls.Add(respackHint, 0, 5);

            // ---------------- 6. 拖入区 ----------------
            dropHint = new Label();
            dropHint.Text = "把谱面包（*.zip）拖到这里导入，也可以拖一整个文件夹";
            dropHint.Font = new Font(UiFamily, 9F);
            dropHint.Dock = DockStyle.Fill;
            dropHint.Margin = new Padding(0);
            dropHint.TextAlign = ContentAlignment.MiddleCenter;
            dropHint.BackColor = C_CARD;
            dropHint.ForeColor = C_ACCENT;
            dropHint.BorderStyle = BorderStyle.FixedSingle;
            dropHint.AllowDrop = true;
            dropHint.DragEnter += OnDragEnter;
            dropHint.DragLeave += delegate { dropHint.BackColor = C_CARD; };
            dropHint.DragDrop += OnDragDrop;
            root.Controls.Add(dropHint, 0, 6);
            DragEnter += OnDragEnter;
            DragDrop += OnDragDrop;

            // ---------------- 7. 进度条 ----------------
            progressBar = new ProgressBar();
            progressBar.Dock = DockStyle.Fill;
            progressBar.Minimum = 0;
            progressBar.Maximum = 100;
            progressBar.Value = 0;
            progressBar.Margin = new Padding(0, 3, 0, 3);
            root.Controls.Add(progressBar, 0, 7);

            // ---------------- 8. 进度文字 + 结果横幅（上下两行，不重叠） ----------------
            Panel progPanel = new Panel();
            progPanel.Dock = DockStyle.Fill;
            progPanel.BackColor = C_BG;
            root.Controls.Add(progPanel, 0, 8);

            progressLabel = new Label();
            progressLabel.Text = "等待安装";
            progressLabel.Font = new Font(UiFamily, 8.5F);
            progressLabel.ForeColor = C_MUTED;
            progressLabel.AutoEllipsis = true;
            progressLabel.SetBounds(0, 0, 420, 16);
            progressLabel.Anchor = AnchorStyles.Top | AnchorStyles.Left;
            progPanel.Controls.Add(progressLabel);

            resultLabel = new Label();
            resultLabel.Text = "";
            resultLabel.Font = new Font(UiFamily, 9F, FontStyle.Bold);
            resultLabel.ForeColor = C_TEXT;
            resultLabel.AutoEllipsis = true;
            resultLabel.SetBounds(0, 16, 600, 18);
            resultLabel.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            progPanel.Controls.Add(resultLabel);

            // ---------------- 9. 日志 ----------------
            GroupBox logGroup = MakeGroup("日志");
            root.Controls.Add(logGroup, 0, 9);

            log = new RichTextBox();
            log.Dock = DockStyle.Fill;
            log.ReadOnly = true;
            log.BorderStyle = BorderStyle.None;
            log.WordWrap = true;
            log.DetectUrls = false;
            log.ScrollBars = RichTextBoxScrollBars.Vertical;
            log.BackColor = C_CARD;
            log.ForeColor = C_TEXT;
            log.Font = new Font(MonoFamily, 9F);
            logGroup.Controls.Add(log);

            // ---------------- 10. 底部 ----------------
            TableLayoutPanel footT = new TableLayoutPanel();
            footT.Dock = DockStyle.Fill;
            footT.ColumnCount = 4;
            footT.RowCount = 1;
            footT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170F));
            footT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170F));
            footT.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            footT.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 300F));
            footT.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            root.Controls.Add(footT, 0, 10);

            diagBtn = MakeButton("检查安装（诊断）", false);
            diagBtn.Dock = DockStyle.Fill;
            diagBtn.Margin = new Padding(0, 2, 8, 2);
            diagBtn.Click += delegate { Diagnose(); SetProgress(progressBar.Value, "诊断完成（见下方日志）"); };
            footT.Controls.Add(diagBtn, 0, 0);

            builtinBtn = MakeButton("只投放内置谱面", false);
            builtinBtn.Dock = DockStyle.Fill;
            builtinBtn.Margin = new Padding(0, 2, 8, 2);
            builtinBtn.Click += delegate
            {
                InstallBuiltinSongs();
                Print("谱面目录：" + SongsDir());
                SetProgress(100, "内置谱面已投放");
                Diagnose();
            };
            footT.Controls.Add(builtinBtn, 1, 0);

            Label supportLabel = MakeLabel("支持 " + Variants.Length + " 个版本：" + Variants[0].Mc + " ~ "
                + Variants[Variants.Length - 1].Mc, 8.5F, FontStyle.Regular, C_MUTED,
                ContentAlignment.MiddleRight);
            footT.Controls.Add(supportLabel, 3, 0);

            LoadSettings();
            RefreshVersions(false);
            UpdateResPackHint();
            // 首帧布局定下来之后再算一次列宽；顺手把选中行坐实
            //（构造期 ListView 还没有句柄，Items[].Selected 不一定真的选中）
            Shown += delegate
            {
                if (SelectedVersion().Length == 0 && wantedIndex >= 0
                    && wantedIndex < instanceList.Items.Count)
                {
                    instanceList.Items[wantedIndex].Selected = true;
                    instanceList.Items[wantedIndex].Focused = true;
                    instanceList.EnsureVisible(wantedIndex);
                    ApplyLayoutForVersion();
                }
                FitInstanceColumns();
            };
            Print("提示：先确认目录、实例和③里的「版本类型」，再点【立即安装】。完整版与打歌版只能二选一（安装器会自动清理另一种）。");
            Print("      装完把材质包 .zip 丢进【打开资源包文件夹】里那个目录，进游戏设置页切换。");
            uiReady = true;
        }

        // ==================================================================
        // 控件工厂（统一字号 / 边距 / 描边，避免各处手写样式不一致）
        // ==================================================================

        private static Label MakeLabel(string text, float size, FontStyle style, Color color,
            ContentAlignment align)
        {
            Label l = new Label();
            l.Text = text;
            l.Font = new Font(UiFamily, size, style);
            l.ForeColor = color;
            l.TextAlign = align;
            l.AutoSize = false;
            l.Dock = DockStyle.Fill;
            l.Margin = new Padding(0);
            l.AutoEllipsis = true;
            return l;
        }

        private static GroupBox MakeGroup(string title)
        {
            GroupBox g = new GroupBox();
            g.Text = "  " + title;
            g.Font = new Font(UiFamily, 9F, FontStyle.Bold);
            g.ForeColor = C_TEXT;
            g.BackColor = C_CARD;
            g.Padding = new Padding(10, 6, 10, 8);
            g.Dock = DockStyle.Fill;
            g.Margin = new Padding(0, 0, 0, 6);
            return g;
        }

        private static CheckBox MakeCheck(string text, bool @checked)
        {
            CheckBox c = new CheckBox();
            c.Text = text;
            c.Font = new Font(UiFamily, 9F);
            c.ForeColor = C_TEXT;
            c.Checked = @checked;
            c.AutoSize = false;
            c.Dock = DockStyle.Fill;
            c.TextAlign = ContentAlignment.MiddleLeft;
            c.Margin = new Padding(0);
            return c;
        }

        /** 版本类型单选按钮（两个同父 → 互斥）。 */
        private static RadioButton MakeRadio(string text, bool @checked)
        {
            RadioButton r = new RadioButton();
            r.Text = text;
            r.Font = new Font(UiFamily, 9F);
            r.ForeColor = C_TEXT;
            r.Checked = @checked;
            r.AutoSize = false;
            r.Dock = DockStyle.Fill;
            r.TextAlign = ContentAlignment.MiddleLeft;
            r.Margin = new Padding(0);
            r.Cursor = Cursors.Hand;
            return r;
        }

        private static Button MakeButton(string text, bool primary)
        {
            Button b = new Button();
            b.Text = text;
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = 1;
            b.FlatAppearance.BorderColor = primary ? C_ACCENT : C_BORDER;
            b.UseVisualStyleBackColor = false;
            b.BackColor = primary ? C_ACCENT : C_CARD;
            b.ForeColor = primary ? Color.White : C_TEXT;
            b.Font = new Font(UiFamily, primary ? 10F : 9F, primary ? FontStyle.Bold : FontStyle.Regular);
            b.Cursor = Cursors.Hand;
            b.Margin = new Padding(0);
            return b;
        }

        /** 实例列表列宽：第一列吃满剩余宽度，后三列固定。 */
        private void FitInstanceColumns()
        {
            if (instanceList == null || instanceList.Columns.Count < 4) return;
            int fixedW = 90 + 130 + 160 + 8;
            int w = instanceList.ClientSize.Width - fixedW;
            if (w < 160) w = 160;
            instanceList.Columns[0].Width = w;
        }

        // ---------- 路径 ----------

        private string McDir()
        {
            return mcDirBox.Text.Trim();
        }

        private bool Isolated()
        {
            return true;   // 一律版本隔离：mods/config 都放 versions\<版本>\ 下
        }

        /// <summary>真正的安装根目录：版本隔离时是 versions\&lt;版本&gt;\，否则是 .minecraft。</summary>
        private string InstallRoot()
        {
            if (!Isolated()) return McDir();
            return Path.Combine(McDir(), "versions", SelectedVersion());
        }

        private string ModsDir()
        {
            return Path.Combine(InstallRoot(), "mods");
        }

        private string SongsDir()
        {
            return Path.Combine(InstallRoot(), "config", "phimc", "songs");
        }

        /// <summary>
        /// 资源包目录：<b>游戏真正读的那个</b>{@code <安装根>/config/phimc/respack}。
        ///
        /// <p>目录名 {@code phimc/respack} 与 mod 侧的 {@code ConfigDir.NAME} + {@code ResPack.DIR_NAME}
        /// 一致；因为安装器一律按版本隔离装，所以它就是
        /// {@code .minecraft/versions/<实例>/config/phimc/respack}。没有选中实例时退回 .minecraft 根
        /// （非隔离启动器的位置）。
        /// </summary>
        private string ResPackDir()
        {
            if (SelectedVersion().Length == 0)
                return Path.Combine(McDir(), "config", "phimc", "respack");
            return Path.Combine(InstallRoot(), "config", "phimc", "respack");
        }

        /// <summary>建好资源包目录（连同上一层的 config\phimc）并返回路径。</summary>
        private string EnsureResPackDir()
        {
            string dir = ResPackDir();
            Directory.CreateDirectory(dir);
            return dir;
        }

        /// <summary>
        /// 「打开资源包文件夹」按钮：目录不存在就建一个空目录再打开 ——
        /// 没安装过（连 config\phimc 都没有）也能直接打开丢 .zip 进去。
        /// </summary>
        private void OpenResPackFolder()
        {
            string dir;
            try
            {
                dir = EnsureResPackDir();
            }
            catch (Exception e)
            {
                Print("× 建资源包目录失败：" + e.Message);
                SetResult(false, "打不开资源包文件夹",
                    "确认 .minecraft 目录可写，或用管理员运行（安装器默认已请求管理员）");
                return;
            }
            try
            {
                Process.Start("explorer.exe", "\"" + dir + "\"");
                Print("√ 已打开资源包文件夹：" + dir);
                int zips = 0;
                try { zips = Directory.GetFiles(dir, "*.zip").Length; }
                catch (Exception) { }
                Print("  " + (zips == 0
                    ? "现在还是空的：把材质包 .zip 直接放进这个文件夹，进游戏 → 设置 → 材质包 就能选到"
                    : "里面已有 " + zips + " 个 .zip：进游戏 → 设置 → 材质包 用【" + "文件名" + "】按钮切换"));
                SetProgress(progressBar.Value, "资源包文件夹：" + dir);
                SetResult(true, "已打开资源包文件夹", "把 .zip 放进去后进游戏设置页切换");
            }
            catch (Exception e)
            {
                Print("× 打开资源包文件夹失败：" + e.Message);
                SetResult(false, "打不开资源包文件夹", "请手动在资源管理器里打开：" + dir);
            }
        }

        /** 资源包说明行 = 固定文案 + 真实路径（用户不用猜到底打开的是哪个目录）。 */
        private void UpdateResPackHint()
        {
            if (respackHint == null) return;
            string dir;
            try { dir = ResPackDir(); }
            catch (Exception) { dir = ""; }
            respackHint.Text = "把材质包 .zip 放进这个文件夹，游戏内设置页即可选择：  " + dir;
        }

        /// <summary>上次扫到几个版本 / 有没有 Fabric 版本（给状态行用）。</summary>
        private int detectedCount;
        private bool detectedFabric;

        private void ApplyLayoutForVersion()
        {
            string mc = DetectInstanceMc(SelectedVersion());
            ModVariant v = VariantFor(mc);
            bool hasInst = instNames.Count > 0;
            rootLabel.Text = hasInst
                ? "安装到：" + InstallRoot() + (Isolated() ? "   （版本隔离）" : "   （全局）")
                : "还没找到任何实例：请先装好 Minecraft（或用启动器装一个 Fabric 实例）再点【↻ 刷新】";
            if (!hasInst)
            {
                statusLabel.ForeColor = C_MUTED;
                statusLabel.Text = "支持的版本：" + SupportedMcList();
            }
            else if (v != null)
            {
                statusLabel.ForeColor = C_OK;
                statusLabel.Text = "实例 MC " + mc + " → " + TypeLabel() + "："
                    + JarNameFor(v) + " + " + v.FabricApiName
                    + "  （检测到 " + detectedCount + " 个实例"
                    + (detectedFabric ? "" : "，没有 Fabric 实例！点【装 Fabric】") + "）";
            }
            else
            {
                statusLabel.ForeColor = C_ERR;
                statusLabel.Text = (mc == null ? "认不出这个实例的 MC 版本" : "不支持 Minecraft " + mc)
                    + "（支持：" + SupportedMcList() + "）—— 不会装错，请换一个受支持的实例";
            }
            UpdateResPackHint();
            SaveSettings();
        }

        private static string DetectMinecraft()
        {
            string p = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft");
            return p;
        }

        private void BrowseMinecraft()
        {
            FolderBrowserDialog d = new FolderBrowserDialog();
            d.Description = "选择 .minecraft 目录";
            if (d.ShowDialog() == DialogResult.OK)
            {
                mcDirBox.Text = d.SelectedPath;
                RefreshVersions(true);
            }
        }

        // ---------- 记住上次的选择 ----------

        private static string SettingsPath()
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhiMCSetup");
            Directory.CreateDirectory(dir);
            return Path.Combine(dir, "settings.txt");
        }

        private void SaveSettings()
        {
            try
            {
                File.WriteAllText(SettingsPath(),
                    "mc=" + McDir() + "\r\n" +
                    "ver=" + SelectedVersion() + "\r\n" +
                    "iso=" + (Isolated() ? "1" : "0") + "\r\n" +
                    "type=" + (IsLite() ? "lite" : "full") + "\r\n");
            }
            catch (Exception) { }
        }

        private void LoadSettings()
        {
            try
            {
                string p = SettingsPath();
                if (!File.Exists(p)) return;
                foreach (string line in File.ReadAllLines(p))
                {
                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;
                    string k = line.Substring(0, eq), v = line.Substring(eq + 1);
                    if (k == "mc" && v.Length > 0 && Directory.Exists(v)) mcDirBox.Text = v;
                    else if (k == "type" && v.Length > 0) SetModType(string.Equals(v, "lite", StringComparison.OrdinalIgnoreCase));
                }
            }
            catch (Exception) { }
        }

        private void LoadVersionAndIso(string ver, bool iso)
        {
            if (ver != null)
            {
                for (int i = 0; i < instNames.Count; i++)
                {
                    if (string.Equals(instNames[i], ver, StringComparison.OrdinalIgnoreCase))
                    {
                        wantedIndex = i;     // silent 模式（--install）没有句柄，靠它兜底
                        instanceList.Items[i].Selected = true;
                        instanceList.Items[i].Focused = true;
                        break;
                    }
                }
            }
            isoBox.Checked = Isolated();
        }

        // ---------- 版本探测 ----------

        private void RefreshVersions(bool verbose)
        {
            string keep = SelectedVersion();
            instNames.Clear();
            instanceList.BeginUpdate();
            instanceList.Items.Clear();

            string versions = Path.Combine(McDir(), "versions");
            int found = 0;
            bool hasFabric = false;
            int installed = 0;
            if (Directory.Exists(versions))
            {
                string[] dirs = Directory.GetDirectories(versions);
                Array.Sort(dirs, StringComparer.OrdinalIgnoreCase);
                foreach (string d in dirs)
                {
                    string name = Path.GetFileName(d);
                    string mc = DetectInstanceMc(name);
                    string loader = DetectLoader(name);
                    // 「PhiMC」列跟着③的版本类型走：完整版已装 / 打歌版已装 / 未装（另一种装了会标「将换」）
                    InstalledKind kind = DetectInstalled(name);
                    string state = InstalledText(name, kind);
                    bool has = kind != InstalledKind.None;
                    ListViewItem it = new ListViewItem(name);
                    it.SubItems.Add(mc == null ? "?" : mc);
                    it.SubItems.Add(loader);
                    it.SubItems.Add(state);
                    it.Tag = name;
                    instanceList.Items.Add(it);
                    instNames.Add(name);
                    if (name.ToLowerInvariant().Contains("fabric")) hasFabric = true;
                    if (has) installed++;
                    found++;
                }
            }
            instanceList.EndUpdate();
            if (found == 0 && verbose)
                instanceList.Items.Add(new ListViewItem("（没找到任何实例）"));

            detectedCount = found;
            detectedFabric = hasFabric;

            // 优先：Fabric+支持版本 > 含 fabric > 上次选的 > 支持版本 > 第一个
            int idx = -1;
            for (int i = 0; i < instNames.Count && idx < 0; i++)
            {
                if (instNames[i].ToLowerInvariant().Contains("fabric")
                    && VariantFor(DetectInstanceMc(instNames[i])) != null) idx = i;
            }
            for (int i = 0; i < instNames.Count && idx < 0; i++)
                if (instNames[i].ToLowerInvariant().Contains("fabric")) idx = i;
            for (int i = 0; i < instNames.Count && idx < 0; i++)
                if (keep != null && keep.Length > 0 && instNames[i] == keep) idx = i;
            for (int i = 0; i < instNames.Count && idx < 0; i++)
                if (VariantFor(DetectInstanceMc(instNames[i])) != null) idx = i;
            if (idx < 0 && instNames.Count > 0) idx = 0;

            if (idx >= 0 && idx < instanceList.Items.Count)
            {
                wantedIndex = idx;
                instanceList.Items[idx].Selected = true;
                instanceList.Items[idx].Focused = true;
                instanceList.EnsureVisible(idx);
            }

            isoBox.Checked = Isolated();
            FitInstanceColumns();
            ApplyLayoutForVersion();

            if (!hasFabric && verbose) Print("× 没找到任何 Fabric 实例 —— mod 不会加载，先点【装 Fabric】");
            if (verbose)
                Print("√ 扫描完成：" + found + " 个实例（已装 PhiMC " + installed + " 个），"
                    + (GuessIsolation(SelectedVersion()) ? "该实例有 mods/config，按版本隔离处理" : "按版本隔离处理"));
        }

        /// <summary>
        /// 加载器名字：目录名 {@code fabric-loader-0.16.14-1.21.1} 优先，
        /// 其次读实例 json 里 fabric-loader 库的版本，最后按关键字兜底。
        /// </summary>
        private string DetectLoader(string ver)
        {
            string low = ver.ToLowerInvariant();
            System.Text.RegularExpressions.Match m =
                System.Text.RegularExpressions.Regex.Match(low, "fabric-loader-([0-9]+(?:\\.[0-9]+)+)");
            if (m.Success) return "Fabric " + m.Groups[1].Value;
            if (low.Contains("quilt-loader-"))
            {
                System.Text.RegularExpressions.Match q =
                    System.Text.RegularExpressions.Regex.Match(low, "quilt-loader-([0-9]+(?:\\.[0-9]+)+)");
                return "Quilt " + (q.Success ? q.Groups[1].Value : "");
            }
            if (low.Contains("neoforge")) return "NeoForge";
            if (low.Contains("forge")) return "Forge";

            string json = Path.Combine(McDir(), "versions", ver, ver + ".json");
            try
            {
                if (File.Exists(json))
                {
                    string text = File.ReadAllText(json);
                    System.Text.RegularExpressions.Match f = System.Text.RegularExpressions.Regex.Match(
                        text, "fabric-loader[\\s\"':,]*([0-9]+(?:\\.[0-9]+)+)",
                        System.Text.RegularExpressions.RegexOptions.IgnoreCase);
                    if (f.Success) return "Fabric " + f.Groups[1].Value;
                    if (text.IndexOf("quilt-loader", StringComparison.OrdinalIgnoreCase) >= 0) return "Quilt";
                    if (text.IndexOf("neoforge", StringComparison.OrdinalIgnoreCase) >= 0) return "NeoForge";
                    if (text.IndexOf("\"forge\"", StringComparison.OrdinalIgnoreCase) >= 0) return "Forge";
                }
            }
            catch (Exception) { }
            return low.Contains("fabric") ? "Fabric" : "原版";
        }

        /// <summary>猜这个版本是不是版本隔离：看 versions&lt;ver&gt; 里有没有 mods / config / saves。</summary>
        private bool GuessIsolation(string ver)
        {
            if (string.IsNullOrEmpty(ver)) return false;
            string root = Path.Combine(McDir(), "versions", ver);
            if (!Directory.Exists(root)) return false;
            return Directory.Exists(Path.Combine(root, "mods"))
                || Directory.Exists(Path.Combine(root, "config"))
                || Directory.Exists(Path.Combine(root, "saves"))
                || File.Exists(Path.Combine(root, "options.txt"));
        }

        private string SelectedVersion()
        {
            if (instanceList != null && instanceList.SelectedIndices.Count > 0)
            {
                int i = instanceList.SelectedIndices[0];
                if (i >= 0 && i < instNames.Count) return instNames[i];
            }
            // ListView 还没有原生句柄时（构造期 / --install 无界面模式）SelectedIndices 是空的，
            // 用 RefreshVersions 算出来的目标行兜底，保证「选中哪一行」不依赖窗口是否已显示
            if (wantedIndex >= 0 && wantedIndex < instNames.Count) return instNames[wantedIndex];
            return "";
        }

        // ---------- 版本 → 内嵌 jar 的映射 ----------

        /// <summary>支持版本列表，用来在提示里列出来（"1.20.1 / 1.21.1"）。</summary>
        private static string SupportedMcList()
        {
            System.Text.StringBuilder sb = new System.Text.StringBuilder();
            for (int i = 0; i < Variants.Length; i++)
            {
                if (i > 0) sb.Append(" / ");
                sb.Append(Variants[i].Mc);
            }
            return sb.ToString();
        }

        /// <summary>精确匹配 MC 版本 → 内嵌 jar 组；不在支持列表就返回 null（绝不猜、绝不装错）。</summary>
        private static ModVariant VariantFor(string mc)
        {
            if (string.IsNullOrEmpty(mc)) return null;
            string want = mc.Trim();
            foreach (ModVariant v in Variants)
                if (string.Equals(v.Mc, want, StringComparison.OrdinalIgnoreCase)) return v;
            return null;
        }

        /// <summary>
        /// 读实例的 MC 版本：优先 versions\&lt;实例&gt;\&lt;实例&gt;.json 里的 inheritsFrom（Fabric 档案会写），
        /// 其次 id（纯原版档案），最后从目录名里抓一个像版本号的 token（"fabric-loader-0.16.9-1.21.1"）。
        /// 返回 null 表示认不出来。
        /// </summary>
        private string DetectInstanceMc(string ver)
        {
            if (string.IsNullOrEmpty(ver)) return null;
            string json = Path.Combine(McDir(), "versions", ver, ver + ".json");
            string text = null;
            try { if (File.Exists(json)) text = File.ReadAllText(json); }
            catch (Exception) { }
            if (text != null)
            {
                string inherited = RegexField(text, "inheritsFrom");
                if (inherited != null) { string mc0 = ExtractMc(inherited); if (mc0 != null) return mc0; }
                // 合并档案里可能有多个 "id"（如 assetIndex.id = "17"），取第一个像 MC 版本的
                foreach (System.Text.RegularExpressions.Match mm in System.Text.RegularExpressions.Regex.Matches(
                    text, "\"id\"\\s*:\\s*\"([^\"]+)\""))
                {
                    string cand = mm.Groups[1].Value;
                    { string mc1 = ExtractMc(cand); if (mc1 != null) return mc1; }
                }
            }
            // 目录名兜底：从右往左找第一段“像 MC 版本”的 token
            string[] parts = ver.Split(new char[] { '-', '_', '+' });
            // 从左往右找，避开 fabric-loader 的 0.20.0 这类「loader 版本」
            for (int i = 0; i < parts.Length; i++)
                { string mc2 = ExtractMc(parts[i]); if (mc2 != null) return mc2; }
            return null;
        }

        /// <summary>"inheritsFrom": "1.21.1" → 1.21.1（只取第一个匹配，够用且不引 JSON 库）。</summary>
        /// <summary>
        /// 从任意字符串里抽第一个「主版本号 >= 1」的 MC 版本号。
        /// 例： "1.21.1-fabric-0.20.0" → 1.21.1 ； "fabric-loader-0.20.0-1.21.1" → 1.21.1 ；
        /// "0.20.0" → null（loader 版本，不是 MC）。
        /// </summary>
        private static string ExtractMc(string s)
        {
            if (string.IsNullOrEmpty(s)) return null;
            foreach (System.Text.RegularExpressions.Match m in System.Text.RegularExpressions.Regex.Matches(
                s, @"\d{1,3}\.\d{1,3}(?:\.\d{1,3})?"))
            {
                string t = m.Value;
                int dot = t.IndexOf('.');
                int major;
                if (int.TryParse(t.Substring(0, dot), out major) && major >= 1) return t;
            }
            return null;
        }
        private static string RegexField(string json, string field)
        {
            System.Text.RegularExpressions.Match m = System.Text.RegularExpressions.Regex.Match(
                json, "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
            return m.Success ? m.Groups[1].Value : null;
        }

        /// <summary>像不像一个 MC 版本号：1.20.1 / 1.21 / 26.1.2（后面带 snapshot 之类的也认）。</summary>
        private static bool IsMcVersion(string s)
        {
            if (string.IsNullOrEmpty(s)) return false;
            string t = s.Trim();
            if (!System.Text.RegularExpressions.Regex.IsMatch(t, @"^\d+\.\d+(\.\d+)?([-.][A-Za-z0-9]+)*$")) return false;
            // 主版本号必须 >= 1：0.20.0 是 fabric-loader 的版本号，不是 MC
            int dot = t.IndexOf('.');
            int major;
            return int.TryParse(t.Substring(0, dot), out major) && major >= 1;
        }

        /// <summary>当前选中实例对应的 jar 组；不支持时返回 null。</summary>
        private ModVariant SelectedVariant()
        {
            return VariantFor(DetectInstanceMc(SelectedVersion()));
        }

        // ---------- 版本类型（完整版 / 打歌版：二选一） ----------

        /// <summary>mods/ 里实际装着哪一类 PhiMC。</summary>
        private enum InstalledKind
        {
            None,
            Full,      // phimc-*.jar（完整版）
            Lite,      // phimcplayer-*.jar（打歌版）
            Both       // 两个都在 = 冲突状态，游戏启动会拒绝加载
        }

        /** 当前选的是不是「打歌版」。 */
        private bool IsLite()
        {
            return liteRadio != null && liteRadio.Checked;
        }

        /** 当前选中的版本类型的中文名。 */
        private string TypeLabel()
        {
            return IsLite() ? "打歌版" : "完整版";
        }

        /** 当前类型对应的 mod id。 */
        private string TypeModId()
        {
            return IsLite() ? LiteModId : ModId;
        }

        /** 当前类型对应、本次要写进 mods/ 的 jar 名（不存在这个 MC 版本时返回 null）。 */
        private string JarNameFor(ModVariant v)
        {
            if (v == null) return null;
            return IsLite() ? v.LiteJarName : v.JarName;
        }

        /** 「另一种类型」的 jar 名前缀：完整版 ↔ 打歌版。 */
        private static string OtherJarPrefix(bool lite)
        {
            return lite ? ModJarPrefix : LiteJarPrefix;
        }

        /** 单选切换：刷新实例列表那一列 + 状态行（安装目标不变）。 */
        private void OnModTypeChanged()
        {
            if (!uiReady) return;
            RefreshVersionColumn();
            ApplyLayoutForVersion();
            Print("→ 版本类型：" + TypeLabel() + "（" + (!IsLite() ? ModJarPrefix : LiteJarPrefix)
                + "<MC>.jar；安装时会删掉 mods 里另一种类型的 jar）");
        }

        /** 程序内部设置类型（命令行 --type / 记住上次的选择），不触发日志刷屏。 */
        private void SetModType(bool lite)
        {
            if (liteRadio == null || fullRadio == null) return;
            if (lite) liteRadio.Checked = true; else fullRadio.Checked = true;
            RefreshVersionColumn();
        }

        /// <summary>重新渲染实例列表「PhiMC」列（切类型 / 刷新 / 装完都调）。</summary>
        private void RefreshVersionColumn()
        {
            if (instanceList == null) return;
            try
            {
                instanceList.BeginUpdate();
                for (int i = 0; i < instanceList.Items.Count && i < instNames.Count; i++)
                    instanceList.Items[i].SubItems[3].Text = InstalledText(instNames[i], DetectInstalled(instNames[i]));
                instanceList.EndUpdate();
            }
            catch (Exception) { }
        }

        /** 某一行的状态文字：完整版已装 / 打歌版已装 / 未装，并标出「将换」。 */
        private string InstalledText(string ver, InstalledKind k)
        {
            bool lite = IsLite();
            if (k == InstalledKind.None) return "— 未装";
            if (k == InstalledKind.Both) return "× 两个都装了";
            bool isLiteInstalled = k == InstalledKind.Lite;
            if (isLiteInstalled == lite) return "√ " + (lite ? "打歌版已装" : "完整版已装");
            return (lite ? "完整版已装" : "打歌版已装") + "（将换）";
        }

        /**
         * 这个实例的 mods/ 里装的是哪一类 PhiMC：文件名前缀命中，<b>或者</b> jar 内
         * {@code fabric.mod.json} 的 id 命中（改名前的旧 jar 也算已装）。
         */
        private InstalledKind DetectInstalled(string ver)
        {
            string mods = Path.Combine(McDir(), "versions", ver, "mods");
            bool full = false, lite = false;
            try
            {
                if (!Directory.Exists(mods)) return InstalledKind.None;
                string[] jars = Directory.GetFiles(mods, "*" + ModJarSuffix);
                foreach (string f in jars)
                {
                    string name = Path.GetFileName(f);
                    // 注意顺序：phimcplayer-* 不会命中 phimc-* 前缀（phimc- 后面必须紧跟 '-'）
                    if (name.StartsWith(LiteJarPrefix, StringComparison.OrdinalIgnoreCase)) lite = true;
                    else if (name.StartsWith(ModJarPrefix, StringComparison.OrdinalIgnoreCase)) full = true;
                }
                // 兜底：读 fabric.mod.json（只在 jar 不多时扫，避免大盘实例卡界面）
                if ((!full || !lite) && jars.Length <= 80)
                {
                    foreach (string f in jars)
                    {
                        string id = JarModId(f);
                        if (string.Equals(id, LiteModId, StringComparison.OrdinalIgnoreCase)) lite = true;
                        else if (string.Equals(id, ModId, StringComparison.OrdinalIgnoreCase)) full = true;
                    }
                }
            }
            catch (Exception) { }
            if (full && lite) return InstalledKind.Both;
            if (full) return InstalledKind.Full;
            if (lite) return InstalledKind.Lite;
            return InstalledKind.None;
        }

        // ---------- Fabric 自动安装 ----------

        private static string FindJava()
        {
            // 1) PATH
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo("java", "-version");
                psi.UseShellExecute = false;
                psi.RedirectStandardError = true;
                psi.RedirectStandardOutput = true;
                psi.CreateNoWindow = true;
                Process p = Process.Start(psi);
                p.WaitForExit(4000);
                if (p.ExitCode == 0) return "java";
            }
            catch (Exception) { }

            // 2) Minecraft 自带运行时
            string mc = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft", "runtime");
            try
            {
                if (Directory.Exists(mc))
                {
                    string[] exes = Directory.GetFiles(mc, "java.exe", SearchOption.AllDirectories);
                    if (exes.Length > 0) return exes[0];
                }
            }
            catch (Exception) { }

            // 3) 常见 JDK 安装位置
            string[] roots = new string[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Java"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Eclipse Adoptium"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Microsoft"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Eclipse Adoptium")
            };
            foreach (string r in roots)
            {
                try
                {
                    if (!Directory.Exists(r)) continue;
                    string[] exes = Directory.GetFiles(r, "java.exe", SearchOption.AllDirectories);
                    if (exes.Length > 0) return exes[0];
                }
                catch (Exception) { }
            }
            return null;
        }

        private void InstallFabric()
        {
            string java = FindJava();
            if (java == null)
            {
                Print("× 没找到 java.exe（需要 Java 21 才能跑 Fabric 安装器）");
                MessageBox.Show(this, "没找到 Java。请先安装 Java 21（或启动过一次 Minecraft 让它生成自带运行时）。",
                    "缺少 Java", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            Print("使用 Java：" + java);

            string jar = Path.Combine(Path.GetTempPath(), "fabric-installer-1.0.1.jar");
            if (!File.Exists(jar))
            {
                string url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.1/fabric-installer-1.0.1.jar";
                try
                {
                    Print("下载 Fabric 安装器…");
                    using (WebClient wc = new WebClient())
                    {
                        wc.Headers.Add("User-Agent", "PhiMCSetup");
                        wc.DownloadFile(url, jar);
                    }
                    Print("√ 下载完成：" + new FileInfo(jar).Length + " 字节");
                }
                catch (Exception e)
                {
                    Print("× 下载失败：" + e.Message);
                    MessageBox.Show(this, "下载 Fabric 安装器失败（可能没网/被墙）：\r\n" + e.Message +
                        "\r\n\r\n也可以手动装：https://fabricmc.net/use/installer/", "失败",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return;
                }
            }

            string mcTarget = DetectInstanceMc(SelectedVersion());
            if (!IsMcVersion(mcTarget))
            {
                MessageBox.Show(this, "认不出这个实例的 Minecraft 版本，没法自动装 Fabric。\r\n" +
                    "可以手动装：https://fabricmc.net/use/installer/", "缺少版本号",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            string args = "-jar \"" + jar + "\" -dir \"" + McDir() + "\" -mcversion " + mcTarget
                + " -loader latest -noprofile -downloadMinecraft";
            Print("执行：java " + args);
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo(java, args);
                psi.UseShellExecute = false;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.CreateNoWindow = true;
                Process p = Process.Start(psi);
                string so = p.StandardOutput.ReadToEnd();
                string se = p.StandardError.ReadToEnd();
                p.WaitForExit();
                foreach (string line in so.Split('\n'))
                    if (line.Trim().Length > 0) Print("  " + line.Trim());
                Print(p.ExitCode == 0 ? "√ Fabric 安装完成" : "× Fabric 安装器退出码 " + p.ExitCode + "：" + se.Trim());
                RefreshVersions(true);
            }
            catch (Exception e)
            {
                Print("× 运行 Fabric 安装器失败：" + e.Message);
            }
        }

        // ---------- 安装 ----------

        /// <summary>
        /// 写一行日志。颜色按行首符号自动分：{@code ×}=红（失败）、{@code √}=绿（成功）、
        /// {@code →}=蓝（建议 / 结论）、{@code ·}/{@code 两个空格} 开头=灰（细节）。
        /// </summary>
        private void Print(string s)
        {
            string t = s == null ? "" : s;
            Color c = C_TEXT;
            if (t.StartsWith("×")) c = C_ERR;
            else if (t.StartsWith("√")) c = C_OK;
            else if (t.StartsWith("→")) c = C_ACCENT;
            else if (t.StartsWith("·") || t.StartsWith("  ") || t.StartsWith("—")) c = C_MUTED;
            AppendLog("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + t, c);
        }

        private void AppendLog(string line, Color color)
        {
            logText.Append(line).Append("\r\n");
            if (log == null) return;
            try
            {
                log.SelectionStart = log.TextLength;
                log.SelectionLength = 0;
                log.SelectionColor = color;
                log.AppendText(line + "\r\n");
                log.SelectionColor = C_TEXT;
                log.ScrollToCaret();
            }
            catch (Exception) { }
        }

        /** 日志纯文本（silent 模式导出 / 诊断用）。 */
        private string LogText()
        {
            return logText.ToString();
        }

        /** 进度条定值模式（0-100）：很长的写盘之前先让界面画出来。 */
        private void SetProgress(int pct, string text)
        {
            if (progressBar == null) return;
            progressBar.Style = ProgressBarStyle.Continuous;
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            try { progressBar.Value = pct; }
            catch (Exception) { }
            if (text != null && progressLabel != null) progressLabel.Text = (pct + "%  ") + text;
            PumpUi();
        }

        /** 不确定要多久的步骤（跑 Fabric 安装器 / 写 78MB）：comctl32 自己会跑跑马灯。 */
        private void SetBusy(string text)
        {
            if (progressBar == null) return;
            progressBar.Style = ProgressBarStyle.Marquee;
            progressBar.MarqueeAnimationSpeed = 30;
            if (progressLabel != null) progressLabel.Text = text;
            PumpUi();
        }

        /**
         * 让进度条 / 日志先画一帧。
         *
         * <p>只在 {@link #busy} 期间泵消息，并且安装期间所有动作按钮都已禁用 ——
         * 不然 DoEvents 会让用户点出第二个安装线程。
         */
        private void PumpUi()
        {
            try { Application.DoEvents(); }
            catch (Exception) { }
        }

        /**
         * 结果横幅：成功绿、失败红，失败一律带上「下一步」建议。
         */
        private void SetResult(bool ok, string main, string next)
        {
            if (resultLabel == null) return;
            resultLabel.ForeColor = ok ? C_OK : C_ERR;
            resultLabel.Text = (ok ? "√  " : "×  ") + main
                + (string.IsNullOrEmpty(next) ? "" : "    下一步：" + next);
        }

        private void OpenFolder(string path)
        {
            try
            {
                if (!Directory.Exists(path)) Directory.CreateDirectory(path);
                Process.Start("explorer.exe", "\"" + path + "\"");
                Print("√ 已打开：" + path);
            }
            catch (Exception e)
            {
                Print("× 打开失败：" + e.Message);
            }
        }

        /// <summary>读一个内嵌资源（资源名 = /resource:路径,名字 里的名字），没有返回 null。</summary>
        private byte[] ReadEmbedded(string name)
        {
            Assembly asm = Assembly.GetExecutingAssembly();
            using (Stream s = asm.GetManifestResourceStream(name))
            {
                if (s == null) return null;
                byte[] buf = new byte[s.Length];
                int read = 0;
                while (read < buf.Length)
                {
                    int n = s.Read(buf, read, buf.Length - read);
                    if (n <= 0) break;
                    read += n;
                }
                return buf;
            }
        }

        /// <summary>
        /// 读内嵌 mod jar（按内嵌资源名 = 目标文件名）。{@code jarName} 决定取完整版还是打歌版：
        /// 完整版 {@code phimc-<MC>.jar}、打歌版 {@code phimcplayer-<MC>.jar}，两者互斥。
        /// 不支持这个版本时返回 null。
        /// </summary>
        private byte[] ReadEmbeddedJar(ModVariant v, string jarName)
        {
            if (v == null) return null;
            if (string.IsNullOrEmpty(jarName)) jarName = JarNameFor(v);
            byte[] jar = ReadEmbedded(jarName);
            if (jar != null) return jar;
            // 资源名对不上时的兜底：找名字以这个 jar 名结尾的资源（只做后缀匹配，两种 jar 名不会互为后缀）
            Assembly asm = Assembly.GetExecutingAssembly();
            foreach (string name in asm.GetManifestResourceNames())
                if (name.EndsWith(jarName, StringComparison.OrdinalIgnoreCase))
                    return ReadEmbedded(name);
            return null;
        }

        private bool EnsureDirs(out string err)
        {
            err = null;
            try
            {
                Directory.CreateDirectory(InstallRoot());
                Directory.CreateDirectory(ModsDir());
                Directory.CreateDirectory(SongsDir());
                string hint = Path.Combine(SongsDir(), "_把谱面包(.zip)放这里.txt");
                if (!File.Exists(hint))
                {
                    // 路径两种版本完全一样（mod 侧 ConfigDir.NAME 都是 phimc），只有入口提示不同
                    File.WriteAllText(hint, IsLite()
                        ? "安装器已经把内置谱面放好了；打歌版进游戏按 V 打开曲库。\r\n想再放自己的谱面，直接把这个文件夹当作 songs/ 用即可。\r\n"
                        : "安装器已经把内置谱面放好了；想再放自己的，直接把这个文件夹当作 songs/ 用即可。\r\n进游戏后拿「谱面播放器」右键开始。\r\n");
                }
                string probe = Path.Combine(ModsDir(), ".phimc_write_test");
                File.WriteAllText(probe, "ok");
                File.Delete(probe);
                return true;
            }
            catch (UnauthorizedAccessException e)
            {
                err = DescribeWriteError(e, ModsDir());
                return false;
            }
            catch (IOException e)
            {
                err = DescribeWriteError(e, ModsDir());
                return false;
            }
            catch (Exception e)
            {
                err = e.GetType().Name + "：" + e.Message;
                return false;
            }
        }

        private string DescribeWriteError(Exception e, string dir)
        {
            bool gameRunning = AnyJavaRunning();
            string baseMsg = e.GetType().Name + "（0x" + e.HResult.ToString("X8") + "）：" + e.Message;
            if (e is UnauthorizedAccessException)
            {
                if (gameRunning)
                    return baseMsg + "\r\n→ 检测到 Java 进程：请先完全退出 Minecraft（含启动器）。";
                return baseMsg + "\r\n→ 目标目录没有写权限：\r\n" +
                       "   1) 点【是】以管理员身份重试\r\n" +
                       "   2) 检查 Defender“受控文件夹访问”\r\n" +
                       "   3) 目录是否被设成只读\r\n" +
                       "   目标：" + dir;
            }
            return baseMsg;
        }

        /// <summary>命令行 --force：跳过「检测到 Java 进程就不写」的预检（写文件本身仍会做长度校验）。</summary>
        private static bool forceWrite = false;
        /// <summary>用户已确认「Java 在跑也继续」，本次安装内不再重复询问。</summary>
        private bool javaOkOnce = false;

        private static bool AnyJavaRunning()
        {
            try
            {
                return Process.GetProcessesByName("javaw").Length > 0
                    || Process.GetProcessesByName("java").Length > 0;
            }
            catch (Exception) { return false; }
        }

        private static bool IsElevated()
        {
            try
            {
                WindowsPrincipal p = new WindowsPrincipal(WindowsIdentity.GetCurrent());
                return p.IsInRole(WindowsBuiltInRole.Administrator);
            }
            catch (Exception) { return false; }
        }

        private static string Run(string exe, string args)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo(exe, args);
                psi.UseShellExecute = false;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.CreateNoWindow = true;
                Process p = Process.Start(psi);
                string o = p.StandardOutput.ReadToEnd();
                p.WaitForExit(5000);
                return o.Trim();
            }
            catch (Exception e) { return "(执行失败: " + e.Message + ")"; }
        }

        private static string AclOf(string dir)
        {
            if (!Directory.Exists(dir)) return "(目录不存在)";
            return Run("icacls", "\"" + dir + "\"").Replace("\r\n", " | ");
        }

        private static bool GrantAcl(string dir)
        {
            string s = Run("icacls", "\"" + dir + "\" /grant \"" + Environment.UserName + "\":(OI)(CI)M /C");
            return s.IndexOf("已处理", StringComparison.Ordinal) >= 0
                || s.IndexOf("processed", StringComparison.OrdinalIgnoreCase) >= 0;
        }

        private string ExportFallback(byte[] jar, ModVariant v)
        {
            string jarName = JarNameFor(v);
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "PhiMC_手动安装");
            Directory.CreateDirectory(dir);
            File.WriteAllBytes(Path.Combine(dir, jarName), jar);
            byte[] api = ReadEmbedded(v.FabricApiName);
            if (api != null) File.WriteAllBytes(Path.Combine(dir, v.FabricApiName), api);
            foreach (string z in new System.Collections.Generic.List<string>(dropped))
            {
                try { if (File.Exists(z)) File.Copy(z, Path.Combine(dir, Path.GetFileName(z)), true); }
                catch (Exception) { }
            }
            File.WriteAllText(Path.Combine(dir, "说明.txt"),
                "1) " + jarName + " 与 " + v.FabricApiName + " 复制到：" + ModsDir() + "\r\n" +
                "2) 打歌版（phimcplayer-*.jar）与完整版（phimc-*.jar）只能留一个：先把另一种删掉\r\n" +
                "3) *.zip 复制到：" + SongsDir() + "\r\n" +
                "4) 还是拒绝访问：右键文件夹 → 属性 → 安全 → 编辑 → 你的用户 → 完全控制\r\n");
            return dir;
        }

        private bool WriteJarVerified(byte[] jar, string target, out string err)
        {
            err = null;
            // 改成「弹一次确认」而不是硬拒绝：launcher/后台 JVM 常驻，硬拒绝会让用户永远装不上
            if (!forceWrite && AnyJavaRunning() && !javaOkOnce)
            {
                DialogResult r = MessageBox.Show(
                    "检测到 Java 进程正在运行（可能是 Minecraft、启动器或后台 JVM）。\r\n\r\n"
                    + "继续写入通常没问题（jar 未被锁时可直接覆盖）；若写入失败会提示你重试。\r\n\r\n"
                    + "要现在继续安装吗？",
                    "PhiMC 安装器", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                if (r != DialogResult.Yes)
                {
                    err = "已取消：请先完全退出游戏和启动器，然后重试。";
                    return false;
                }
                javaOkOnce = true;
            }
            for (int attempt = 0; attempt < 2; attempt++)
            {
                try
                {
                    if (attempt == 1 && File.Exists(target)) File.SetAttributes(target, FileAttributes.Normal);
                    File.WriteAllBytes(target, jar);
                    FileInfo fi = new FileInfo(target);
                    if (fi.Length != jar.Length)
                    {
                        err = "写入后大小不一致：期望 " + jar.Length + "，实际 " + fi.Length;
                        return false;
                    }
                    return true;
                }
                catch (Exception e)
                {
                    err = DescribeWriteError(e, target);
                    if (attempt == 0) System.Threading.Thread.Sleep(300);
                }
            }
            return false;
        }

        /// <summary>
        /// mods/ 里属于「某一类」的所有 jar：文件名是本类前缀，<b>或者</b> jar 内 fabric.mod.json
        /// 的 id 就是本类 mod（后者兜得住「改名前的旧 jar」—— 文件名前缀不同，但 mod id 相同，
        /// 两个一起加载会冲突，必须一视同仁地清掉）。
        /// 另一类（完整版 ↔ 打歌版）的 jar 不算在内。
        /// </summary>
        private string[] ModJarsOfKind(bool lite)
        {
            string prefix = lite ? LiteJarPrefix : ModJarPrefix;        // phimcplayer- / phimc-
            string otherPrefix = lite ? ModJarPrefix : LiteJarPrefix;
            string id = lite ? LiteModId : ModId;
            var list = new System.Collections.Generic.List<string>();
            foreach (string f in Directory.GetFiles(ModsDir(), "*" + ModJarSuffix))
            {
                string name = Path.GetFileName(f);
                if (name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) { list.Add(f); continue; }
                if (name.StartsWith(otherPrefix, StringComparison.OrdinalIgnoreCase)) continue;   // 归另一类
                if (string.Equals(JarModId(f), id, StringComparison.OrdinalIgnoreCase)) list.Add(f);
            }
            return list.ToArray();
        }

        /** 当前选择的版本类型（完整版 / 打歌版）的 jar。 */
        private string[] SameTypeJars()
        {
            return ModJarsOfKind(IsLite());
        }

        /**
         * 另一种类型的 jar：<b>必须删掉</b>。
         * 打歌版的 fabric.mod.json 里写了 {@code "breaks": {"phimc": "*"}}，
         * 两个 jar 同时在 mods/ 里时 Fabric Loader 会直接拒绝启动。
         */
        private string[] OtherTypeJars()
        {
            return ModJarsOfKind(!IsLite());
        }

        /** 两种类型的全部 jar（诊断用）。 */
        private string[] OwnModJars()
        {
            var list = new System.Collections.Generic.List<string>();
            list.AddRange(ModJarsOfKind(true));
            foreach (string f in ModJarsOfKind(false))
                if (!list.Contains(f)) list.Add(f);
            return list.ToArray();
        }

        /// <summary>读 jar 内 fabric.mod.json 的 id（读不到返回 null，不抛异常）。</summary>
        private static string JarModId(string jar)
        {
            try
            {
                using (ZipArchive zip = ZipFile.OpenRead(jar))
                {
                    ZipArchiveEntry e = zip.GetEntry("fabric.mod.json");
                    if (e == null) return null;
                    using (var sr = new StreamReader(e.Open(), System.Text.Encoding.UTF8))
                    {
                        var m = System.Text.RegularExpressions.Regex.Match(
                            sr.ReadToEnd(), "\"id\"\\s*:\\s*\"([^\"]+)\"");
                        return m.Success ? m.Groups[1].Value : null;
                    }
                }
            }
            catch (Exception) { return null; }
        }

        /// <summary>
        /// 把某个版本对应的 mod jar（完整版或打歌版，由③的单选决定）+ Fabric API 写进 mods/。
        ///
        /// <p>勾选项在这里生效：
        /// <b>删旧 jar</b>（{@link #deleteOldBox}）—— 同一类型只能留一个 jar；
        /// <b>覆盖</b>（{@link #overwriteBox}）—— 不勾时同名且大小一致就跳过；
        /// <b>装 Fabric API</b>（{@link #apiBox}）—— 不勾时只写 mod jar。
        /// <b>另一种类型的 jar 不受勾选影响，永远删掉</b>（两个一起加载游戏会拒绝启动）。
        /// 内嵌资源优先，其次看安装器旁边的 dist/&lt;jar 名&gt;。
        /// </summary>
        private bool InstallModFiles(ModVariant v, bool verbose, out string err)
        {
            err = null;
            string jarName = JarNameFor(v);
            if (string.IsNullOrEmpty(jarName)) { err = "没有可安装的 jar（版本类型/实例为空）"; return false; }
            byte[] jar = ReadEmbeddedJar(v, jarName);
            if (jar == null)
            {
                string local = Path.Combine(Path.GetDirectoryName(Application.ExecutablePath), "dist", jarName);
                if (File.Exists(local)) jar = File.ReadAllBytes(local);
            }
            if (jar == null) { err = "找不到内嵌 jar（" + jarName + "）"; return false; }

            Directory.CreateDirectory(ModsDir());
            if (verbose)
                Print("· 版本类型：" + TypeLabel() + "（mod id " + TypeModId() + "，jar " + jarName + "）");

            // ① 另一种类型（完整版 ↔ 打歌版）：无条件删。lite 的 fabric.mod.json 有 breaks:{phimc:"*"}，
            //    两个 jar 同时存在时 Fabric Loader 直接拒绝启动 —— 所以这一步不看「删除旧 jar」勾选。
            try
            {
                foreach (string other in OtherTypeJars())
                {
                    File.Delete(other);
                    if (verbose) Print("√ 已删除冲突 jar（另一种版本）：" + Path.GetFileName(other));
                }
            }
            catch (Exception e)
            {
                // 删不掉（文件被占用 / 没权限）不要中断安装，但要在日志里说清楚后果；
                // 装完 Diagnose 还会再报一次，结果横幅也会提示。
                Print("× 删除冲突 jar 失败（另一种版本的 jar 还在，游戏会拒绝启动）：" + e.Message);
            }

            // ② 同类型的其它 MC 版本：按勾选删（mods/ 里同 id 只能有一个 jar）
            if (OptDeleteOldJars())
            {
                try
                {
                    foreach (string old in SameTypeJars())
                    {
                        if (string.Equals(Path.GetFileName(old), jarName, StringComparison.OrdinalIgnoreCase)) continue;
                        File.Delete(old);
                        if (verbose) Print("· 已删除旧 jar（同类型其它 MC 版本）：" + Path.GetFileName(old));
                    }
                }
                catch (Exception e) { if (verbose) Print("· 清理旧 jar 失败（不影响本次写入）：" + e.Message); }
            }
            else
            {
                try
                {
                    string[] own = SameTypeJars();
                    if (own.Length > 1 && verbose)
                        Print("· 警告：mods/ 里有 " + own.Length + " 个 " + TypeLabel()
                            + " jar（按勾选没删），两个都会被加载，可能冲突");
                }
                catch (Exception) { }
            }

            // ③ 写入：同名同大小且没勾「覆盖」→ 跳过
            string target = Path.Combine(ModsDir(), jarName);
            bool skipWrite = false;
            if (!OptOverwrite() && File.Exists(target))
            {
                try
                {
                    long len = new FileInfo(target).Length;
                    if (len == jar.Length) skipWrite = true;
                    else if (verbose)
                        Print("· 已有 " + jarName + " 但大小不同（" + len + " vs " + jar.Length + "），仍然覆盖");
                }
                catch (Exception) { }
            }
            if (skipWrite)
            {
                if (verbose) Print("· 已有同名且大小一致的 jar，按勾选跳过写入：" + target);
            }
            else
            {
                if (!WriteJarVerified(jar, target, out err)) return false;
                if (verbose) Print("√ 已写入 mod（" + TypeLabel() + "）：" + target + "  " + jar.Length
                    + " 字节（已回读校验）");
            }

            if (!OptInstallFabricApi())
            {
                if (verbose)
                {
                    Print("· 按勾选跳过 Fabric API");
                    Print("→ 注意：PhiMC 依赖 Fabric API，mods/ 里没有它游戏里不会加载");
                }
                return true;
            }
            return InstallFabricApi(v, verbose);
        }

        /// <summary>勾选项读取（控件还没建好时给「按默认值」的兜底）。</summary>
        private bool OptInstallFabricApi()
        {
            return apiBox == null || apiBox.Checked;
        }

        private bool OptOverwrite()
        {
            return overwriteBox == null || overwriteBox.Checked;
        }

        private bool OptDeleteOldJars()
        {
            return deleteOldBox == null || deleteOldBox.Checked;
        }

        /// <summary>安装期间禁用会重入的按钮，结束（成功或失败）一律恢复。</summary>
        private void SetActionsEnabled(bool on)
        {
            Button[] bs = new Button[] { installBtn, respackBtn, songsBtn, modsBtn,
                diagBtn, builtinBtn, refreshBtn, fabricBtn };
            foreach (Button b in bs) if (b != null) b.Enabled = on;
            Cursor = on ? Cursors.Default : Cursors.WaitCursor;
        }

        private void FullInstall()
        {
            if (busy) return;
            string ver = SelectedVersion();
            if (ver.Length == 0)
            {
                SetResult(false, "还没选择实例", "在【② 选择实例】里点一行，再点【↻ 刷新】重扫");
                MessageBox.Show(this, "请先在【② 选择实例】列表里点一行（那就是安装目标）。",
                    "提示", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            string mc = DetectInstanceMc(ver);
            ModVariant variant = VariantFor(mc);
            if (variant == null)
            {
                string why = mc == null
                    ? "认不出这个实例的 Minecraft 版本"
                    : "本安装器没有 Minecraft " + mc + " 的 jar";
                Print("× " + why + "：实例「" + ver + "」");
                Print("  支持的版本：" + SupportedMcList());
                SetResult(false, "不支持这个版本：" + ver + "（" + (mc ?? "?") + "）",
                    "换成支持列表里的实例，或点【装 Fabric】给该版本装 Fabric");
                SetProgress(0, "未安装");
                MessageBox.Show(this,
                    why + "。\r\n\r\n实例：" + ver + "\r\n支持：" + SupportedMcList() + "\r\n\r\n" +
                    "不会安装（避免装错版本）。请换一个受支持的实例，或把该实例升级/降级到支持列表里的版本。",
                    "不支持这个版本", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            busy = true;
            SetActionsEnabled(false);
            ResetInstallUi();
            try
            {
                RunInstall(ver, mc, variant);
            }
            finally
            {
                busy = false;
                SetActionsEnabled(true);
            }
        }

        /** 每次新装从 0 开始，避免上一次的进度 / 结果横幅残留在界面上误导用户。 */
        private void ResetInstallUi()
        {
            SetProgress(0, "开始安装…");
            if (resultLabel != null) resultLabel.Text = "";
        }

        private void RunInstall(string ver, string mc, ModVariant variant)
        {
            SetProgress(8, "检查目录权限…");
            string err;
            if (!EnsureDirs(out err))
            {
                Print("× 建目录失败：" + err);
                Print("  当前是否管理员：" + (IsElevated() ? "是" : "否"));
                Print("  目标 ACL：" + AclOf(InstallRoot()));
                if (IsElevated())
                {
                    Print("  尝试补权限…");
                    GrantAcl(InstallRoot());
                    if (!EnsureDirs(out err))
                    {
                        Print("× 仍然失败：" + err);
                        Print("→ 下一步：手动给这个目录写权限，或用管理员运行安装器");
                        SetResult(false, "建目录失败（没有写权限）", "以管理员运行安装器，或换一个可写的 .minecraft 目录");
                        SetProgress(0, "安装中止");
                        return;
                    }
                    Print("√ 补权限后成功");
                }
                else if (TryElevate())
                {
                    Print("→ 已用管理员身份重开安装器，请在新窗口再点【立即安装】");
                    SetResult(false, "需要管理员权限", "在刚弹出的新窗口里再点一次【立即安装】");
                    return;
                }
                else
                {
                    SetResult(false, "建目录失败", "以管理员运行安装器，或换一个可写的 .minecraft 目录");
                    return;
                }
            }
            Print("√ 目录就绪：" + InstallRoot());
            Print("  版本类型：" + TypeLabel() + "（mod id " + TypeModId() + "）");
            Print("  实例 Minecraft " + mc + "  →  " + JarNameFor(variant) + " + " + variant.FabricApiName);
            SetProgress(20, "写入 mod jar…");

            if (!InstallModFiles(variant, true, out err))
            {
                Print("× 写入失败：" + err);
                Print("  当前是否管理员：" + (IsElevated() ? "是" : "否"));
                Print("  目标 ACL：" + AclOf(ModsDir()));
                string fallback;
                try { fallback = ExportFallback(ReadEmbeddedJar(variant, JarNameFor(variant)), variant); Print("→ 已导出到：" + fallback); }
                catch (Exception ex) { fallback = "(导出失败: " + ex.Message + ")"; Print("× " + fallback); }
                Print("→ 下一步：完全退出游戏/启动器后重试；或以管理员重试；或把 " + JarNameFor(variant)
                    + " 手动复制进 " + ModsDir());
                SetResult(false, "写文件失败：" + Short(err, 80), "退出游戏后重试，或以管理员重试（也可用导出的手动安装包）");
                SetProgress(0, "安装中止");
                DialogResult r = MessageBox.Show(this,
                    "写文件失败：\r\n\r\n" + err + "\r\n\r\n要【是】= 以管理员重试，【否】= 打开导出目录手动复制？",
                    "写入失败", MessageBoxButtons.YesNo, MessageBoxIcon.Error);
                if (r == DialogResult.Yes) TryElevate();
                else
                {
                    try { Process.Start("explorer.exe", "\"" + fallback + "\""); }
                    catch (Exception) { }
                }
                return;
            }
            Print("  曲目目录：" + SongsDir());
            SetProgress(55, "投放入内置谱面…");

            InstallBuiltinSongs();
            SetProgress(80, "导入拖入的谱面包…");

            foreach (string z in new System.Collections.Generic.List<string>(dropped))
            {
                try { ImportZip(z); }
                catch (Exception e) { Print("× " + Path.GetFileName(z) + "：" + e.Message); }
            }

            SetProgress(92, "诊断…");
            SaveSettings();
            Diagnose();

            // 收尾自检：另一种版本的 jar 一定要没了（两个共存 = 游戏拒绝启动）
            int conflicts = 0;
            try { conflicts = OtherTypeJars().Length; } catch (Exception) { }
            if (conflicts > 0)
                Print("× 还有一个另一种版本的 jar 没删掉 —— 手动删掉 " + ModsDir() + " 里的 "
                    + (IsLite() ? "phimc-*.jar" : "phimcplayer-*.jar") + "，否则游戏会拒绝启动");

            SetProgress(100, "安装完成");
            string nextStep = IsLite()
                ? "启动游戏并选「" + ver + "」实例，进游戏按 V 打开曲库"
                : "启动游戏并选「" + ver + "」实例；材质包 .zip 丢进【打开资源包文件夹】";
            SetResult(conflicts == 0, "安装完成：" + TypeLabel() + " " + JarNameFor(variant) + " → " + ModsDir(),
                conflicts == 0 ? nextStep : "先手动删掉另一种版本的 jar");
            MessageBox.Show(this,
                "安装完成！\r\n\r\n" +
                "版本类型：" + TypeLabel() + "（mod id " + TypeModId() + "）\r\n" +
                "实例：" + ver + "（Minecraft " + mc + "，" + (Isolated() ? "版本隔离" : "全局") + "）\r\n" +
                "mod：" + Path.Combine(ModsDir(), JarNameFor(variant)) + "\r\n" +
                "Fabric API：" + (OptInstallFabricApi() ? variant.FabricApiName : "（按勾选跳过）") + "\r\n" +
                "曲目：" + SongsDir() + "\r\n" +
                "材质包：" + ResPackDir() + "\r\n" +
                (conflicts == 0 ? "" : "\r\n⚠ " + ModsDir() + " 里还有另一种版本的 jar，必须手动删掉！\r\n") +
                "\r\n下一步：\r\n" +
                "1) 启动游戏，用启动器选「" + ver + "」\r\n" +
                (IsLite()
                    ? "2) 进游戏按 V 打开曲库（默认键位，可在「控制 → 按键绑定」里改）\r\n" +
                      "3) 选歌开始；打歌版不发任何物品，成绩保存在本地"
                    : "2) 创造模式物品栏 → PhiMC 页签 → 拿「谱面播放器」\r\n" +
                      "3) 右键它选歌开始"),
                "完成", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        /** 一行提示用的短文本（长异常信息塞不进结果横幅）。 */
        private static string Short(string s, int max)
        {
            if (string.IsNullOrEmpty(s)) return "";
            string one = s.Replace("\r", " ").Replace("\n", " ").Trim();
            return one.Length <= max ? one : one.Substring(0, max) + "…";
        }

        private bool TryElevate()
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = Application.ExecutablePath;
                psi.UseShellExecute = true;
                psi.Verb = "runas";
                psi.Arguments = "--mc \"" + McDir() + "\" --ver \"" + SelectedVersion() + "\""
                    + (Isolated() ? " --iso" : "")
                    + (IsLite() ? " --type lite" : " --type full");
                foreach (string z in new System.Collections.Generic.List<string>(dropped))
                    psi.Arguments += " --zip \"" + z + "\"";
                Process.Start(psi);
                return true;
            }
            catch (Exception e)
            {
                Print("× 提权失败（可能点了取消）：" + e.Message);
                return false;
            }
        }

        // ---------- 拖入 ----------

        private static readonly System.Collections.Generic.List<string> dropped =
            new System.Collections.Generic.List<string>();

        private void OnDragEnter(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                e.Effect = DragDropEffects.Copy;
                dropHint.BackColor = Color.FromArgb(60, 80, 100);
            }
        }

        private void OnDragDrop(object sender, DragEventArgs e)
        {
            string[] files = (string[])e.Data.GetData(DataFormats.FileDrop);
            dropHint.BackColor = Color.FromArgb(40, 44, 56);
            if (files == null || files.Length == 0) return;
            int ok = 0, bad = 0;
            foreach (string f in files)
            {
                try
                {
                    if (Directory.Exists(f))
                        foreach (string inner in Directory.GetFiles(f, "*.zip"))
                        { if (ImportZip(inner)) ok++; else bad++; }
                    else if (f.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                    { if (ImportZip(f)) ok++; else bad++; }
                    else { Print("跳过（不是 zip）：" + Path.GetFileName(f)); bad++; }
                }
                catch (Exception ex) { Print("× " + Path.GetFileName(f) + "：" + ex.Message); bad++; }
            }
            Print("导入完成：成功 " + ok + "，失败 " + bad);
        }

        private bool ImportZip(string zipPath)
        {
            string name = Path.GetFileName(zipPath);
            bool hasChart = false, hasMusic = false;
            using (ZipArchive a = ZipFile.OpenRead(zipPath))
            {
                foreach (ZipArchiveEntry entry in a.Entries)
                {
                    string n = entry.Name.ToLowerInvariant();
                    if (n.StartsWith("chart") && n.EndsWith(".json")) hasChart = true;
                    if (n.StartsWith("music")) hasMusic = true;
                }
            }
            if (!hasChart) { Print("× " + name + "：没有 Chart_*.json"); return false; }
            Directory.CreateDirectory(SongsDir());
            File.Copy(zipPath, Path.Combine(SongsDir(), name), true);
            if (!dropped.Contains(zipPath)) dropped.Add(zipPath);
            Print("√ " + name + (hasMusic ? "" : "（缺 music，会静音）") + "  → " + SongsDir());
            return true;
        }

        /// <summary>内置的谱面（已去重：删掉内嵌的重复谱面包、重复音轨），装完自动放进 songs/。</summary>
        /// <summary>
        /// 内置谱面：只放 4 张 <c>.pez</c>（官方曲包有版权顾虑，不再内置）。
        /// 资源名 = csc /resource 的名字，第二个字段 = 装进 songs/ 的文件名。
        /// </summary>
        private static readonly string[][] BuiltinSongs = new string[][]
        {
            new string[] { "builtin01.zip", "Cyberneko Pulse (2).pez" },
            new string[] { "builtin02.zip", "Hardsync.pez" },
            new string[] { "builtin03.zip", "推活天使ビビアンちゃん .pez" },
            new string[] { "builtin04.zip", "琵琶曲DJ(1.15x).pez" }
        };

        /// <summary>把流原样写到文件（谱面单个 30+MB，不整块读进 byte[]）。</summary>
        private static void CopyToFile(Stream s, string path)
        {
            using (FileStream fs = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
            {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = s.Read(buf, 0, buf.Length)) > 0) fs.Write(buf, 0, n);
            }
        }

        /// <summary>把与实例 MC 版本匹配的内嵌 Fabric API 放进 mods/（已有同版本的跳过）。</summary>
        private bool InstallFabricApi(ModVariant v, bool verbose)
        {
            Directory.CreateDirectory(ModsDir());
            try
            {
                string[] existing = Directory.GetFiles(ModsDir(), "fabric-api-*.jar");
                foreach (string f in existing)
                {
                    if (Path.GetFileName(f).IndexOf(v.Mc, StringComparison.OrdinalIgnoreCase) >= 0)
                    {
                        if (verbose) Print("· 已有匹配的 Fabric API，跳过：" + Path.GetFileName(f));
                        return true;
                    }
                }
                if (existing.Length > 0 && verbose)
                {
                    Print("· 注意：mods/ 里有其它版本的 Fabric API（" + Path.GetFileName(existing[0]) + "），"
                        + "它和 Minecraft " + v.Mc + " 不匹配，建议删掉；本次仍会放入匹配版本。");
                }
            }
            catch (Exception) { }

            byte[] api = ReadEmbedded(v.FabricApiName);
            if (api == null)
            {
                if (verbose) Print("× 内置 Fabric API 资源缺失（" + v.FabricApiName + "）");
                return false;
            }
            string target = Path.Combine(ModsDir(), v.FabricApiName);
            try
            {
                File.WriteAllBytes(target, api);
                long len = new FileInfo(target).Length;
                if (len != api.Length) { if (verbose) Print("× Fabric API 写入大小不一致：" + target); return false; }
                if (verbose) Print("√ 已放入 Fabric API：" + v.FabricApiName + "（" + (len / 1024) + " KB）");
                return true;
            }
            catch (Exception e)
            {
                if (verbose) Print("× Fabric API 写入失败：" + e.Message);
                return false;
            }
        }
        private void InstallBuiltinSongs()
        {
            Assembly asm = Assembly.GetExecutingAssembly();
            int ok = 0, skipped = 0, failed = 0;
            foreach (string[] pair in BuiltinSongs)
            {
                string res = pair[0], name = pair[1];
                string target = Path.Combine(SongsDir(), name);
                if (File.Exists(target)) { Print("· 已有，跳过：" + name); skipped++; continue; }
                try
                {
                    using (Stream s = asm.GetManifestResourceStream(res))
                    {
                        if (s == null) { Print("× 内置谱面资源缺失：" + res); failed++; continue; }
                        Directory.CreateDirectory(SongsDir());
                        CopyToFile(s, target);
                    }
                    Print("√ 内置谱面：" + name + "（" + (new FileInfo(target).Length / 1024 / 1024) + " MB）");
                    ok++;
                }
                catch (Exception e)
                {
                    Print("× 内置谱面写入失败：" + name + "：" + e.Message);
                    failed++;
                }
            }
            Print("— 内置谱面共 " + BuiltinSongs.Length + " 张：新装 " + ok + "、已有 " + skipped + "、失败 " + failed);
        }

        // ---------- 诊断 ----------

        private void Diagnose()
        {
            Print("— 诊断 —");
            string ver = SelectedVersion();
            string mc = DetectInstanceMc(ver);
            ModVariant v = VariantFor(mc);
            string wantJar = JarNameFor(v);
            string jar = wantJar == null ? null : Path.Combine(ModsDir(), wantJar);
            Print("  版本类型：" + TypeLabel() + "（mod id " + TypeModId() + "，期望 jar " + (wantJar ?? "?") + "）");
            if (jar != null && File.Exists(jar))
            {
                FileInfo fi = new FileInfo(jar);
                string hash = BitConverter.ToString(System.Security.Cryptography.SHA256.Create()
                    .ComputeHash(File.ReadAllBytes(jar))).Replace("-", "").Substring(0, 12).ToLowerInvariant();
                Print("  jar：√ " + fi.Length + " 字节  sha256=" + hash + "  (" + wantJar + ")");
            }
            else Print("  jar：× 不存在（" + (jar ?? ModsDir() + "\\" + (IsLite() ? LiteJarPrefix : ModJarPrefix) + "<MC 版本>.jar") + "）");

            try
            {
                string[] same = SameTypeJars();
                if (same.Length > 1)
                    Print("  ⚠ mods/ 里有 " + same.Length + " 个 " + TypeLabel()
                        + " jar（同类型只能有一个，否则会加载失败）");
                string[] other = OtherTypeJars();
                if (other.Length > 0)
                    Print("  ⚠ mods/ 里有另一种版本的 jar（" + Path.GetFileName(other[0]) + "）："
                        + "完整版与打歌版二选一，两个共存游戏会拒绝启动");
                string[] apis = Directory.GetFiles(ModsDir(), "fabric-api-*.jar");
                Print("  Fabric API：" + (apis.Length > 0 ? "√ " + Path.GetFileName(apis[0]) : "× 没有（mod 依赖它，缺了不会加载）"));
            }
            catch (Exception) { Print("  Fabric API：× 无法检查 mods 目录"); }

            bool fabric = false;
            if (!string.IsNullOrEmpty(ver))
            {
                string vJson = Path.Combine(McDir(), "versions", ver, ver + ".json");
                if (File.Exists(vJson))
                {
                    try { fabric = File.ReadAllText(vJson).IndexOf("fabric-loader", StringComparison.OrdinalIgnoreCase) >= 0; }
                    catch (Exception) { }
                }
                if (ver.ToLowerInvariant().IndexOf("fabric") >= 0) fabric = true;
            }
            Print("  版本：" + ver + "（Minecraft " + (mc ?? "?") + "）  Fabric Loader：" + (fabric ? "√" : "× 没有（没 Fabric 不会加载任何 mod）"));
            if (v == null)
                Print("  → 结论：这个实例的 MC 版本不在支持列表（" + SupportedMcList() + "），没有可用的 jar。");

            string logFile = Path.Combine(InstallRoot(), "logs", "latest.log");
            if (!File.Exists(logFile)) logFile = Path.Combine(McDir(), "logs", "latest.log");
            if (File.Exists(logFile))
            {
                try
                {
                    bool loaded = File.ReadAllText(logFile).IndexOf("phimc", StringComparison.OrdinalIgnoreCase) >= 0;
                    Print("  日志：" + (loaded ? "√ 出现 phimc（已加载）" : "× 没有 phimc（没加载）")
                        + "  " + File.GetLastWriteTime(logFile).ToString("MM-dd HH:mm"));
                }
                catch (Exception e) { Print("  日志读取失败：" + e.Message); }
            }
            else Print("  日志：× 没找到 latest.log（这版本还没启动过？）");

            if (v == null)
                Print("  → 结论：没有可用的 jar（支持：" + SupportedMcList() + "），请换实例。");
            else if (!fabric && ver.ToLowerInvariant().IndexOf("fabric") < 0)
                Print("  → 结论：这个版本没有 Fabric Loader。");
            else if (!File.Exists(jar))
                Print("  → 结论：jar 没进这个 mods 目录。");
            else
                Print("  → 结论：装到位了；如果游戏里还是没有，确认启动器选的是「" + ver + "」。");
        }

        // ---------- 入口 ----------

        [STAThread]
        public static void Main(string[] args)
        {
            bool silent = false;
            string mc = null, ver = null, type = null;
            bool iso = false;
            System.Collections.Generic.List<string> zips = new System.Collections.Generic.List<string>();
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--install") silent = true;
                else if (args[i] == "--mc" && i + 1 < args.Length) mc = args[++i];
                else if (args[i] == "--ver" && i + 1 < args.Length) ver = args[++i];
                else if (args[i] == "--iso") iso = true;
                // --type full|lite —— 提权重开时带上，保证还是刚才选的那种版本
                else if (args[i] == "--type" && i + 1 < args.Length) type = args[++i];
                else if (args[i] == "--zip" && i + 1 < args.Length) zips.Add(args[++i]);
                else if (args[i] == "--force") forceWrite = true;
            }

            if (silent)
            {
                SetupForm f = new SetupForm();
                if (mc != null) f.mcDirBox.Text = mc;
                if (type != null) f.SetModType(string.Equals(type, "lite", StringComparison.OrdinalIgnoreCase));
                f.RefreshVersions(false);
                f.LoadVersionAndIso(ver, iso);
                f.ApplyLayoutForVersion();
                string err;
                bool ok = f.EnsureDirs(out err);
                if (ok)
                {
                    f.Print("√ 目录就绪：" + f.InstallRoot());
                    ModVariant variant = VariantFor(f.DetectInstanceMc(f.SelectedVersion()));
                    if (variant == null)
                    {
                        f.Print("× 不支持的 MC 版本：实例「" + f.SelectedVersion() + "」，支持：" + SupportedMcList());
                        ok = false;
                    }
                    else if (f.InstallModFiles(variant, true, out err))
                    {
                        f.Print("√ 已写入 mod（" + f.TypeLabel() + "）：" + Path.Combine(f.ModsDir(), f.JarNameFor(variant))
                            + "  + " + variant.FabricApiName);
                        f.InstallBuiltinSongs();
                    }
                    else
                    {
                        f.Print("× 写入失败：" + err);
                        ok = false;
                    }
                }
                else f.Print("× 建目录失败：" + err);

                foreach (string z in zips)
                {
                    try { f.ImportZip(z); } catch (Exception e) { f.Print("× " + Path.GetFileName(z) + "：" + e.Message); }
                }
                try { f.Diagnose(); } catch (Exception) { }
                try { File.WriteAllText(Path.Combine(f.McDir(), "phimc-install.log"), f.LogText()); } catch (Exception) { }
                try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "phimc-install.log"), f.LogText()); } catch (Exception) { }
                Environment.Exit(ok ? 0 : 1);
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            SetupForm form = new SetupForm();
            if (mc != null) form.mcDirBox.Text = mc;
            if (type != null) form.SetModType(string.Equals(type, "lite", StringComparison.OrdinalIgnoreCase));
            if (mc != null || type != null) form.RefreshVersions(false);
            form.LoadVersionAndIso(ver, iso);
            form.ApplyLayoutForVersion();
            if (zips.Count > 0)
            {
                form.Print("已收到 " + zips.Count + " 个谱面包，点【①】后导入");
                foreach (string z in zips) if (!dropped.Contains(z)) dropped.Add(z);
            }
            Application.Run(form);
        }
    }
}
