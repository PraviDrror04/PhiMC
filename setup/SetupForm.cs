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
        /// <summary>一个受支持的 MC 版本 = 一个内嵌 mod jar + 一个对应版本的 Fabric API。</summary>
        private sealed class ModVariant
        {
            public readonly string Mc;            // "1.21.1"
            public readonly string JarName;        // 内嵌资源名 = mods/ 里的文件名
            public readonly string FabricApiName;  // 同上，Fabric API

            public ModVariant(string mc, string jar, string api)
            {
                Mc = mc;
                JarName = jar;
                FabricApiName = api;
            }
        }

        /// <summary>
        /// 支持版本表。内嵌资源名（csc /resource:路径,名字）就是这里的名字，也就是装进 mods/ 的文件名。
        /// 新增一个版本：这里加一行 + setup/build-setup.ps1 的 $Variants 加一行（脚本会校验两边一致）。
        /// </summary>
        private static readonly ModVariant[] Variants = new ModVariant[]
        {
            new ModVariant("1.20",    "phimc-1.20.jar",    "fabric-api-0.83.0+1.20.jar"),
            new ModVariant("1.20.1",  "phimc-1.20.1.jar",  "fabric-api-0.92.12+1.20.1.jar"),
            new ModVariant("1.20.2",  "phimc-1.20.2.jar",  "fabric-api-0.91.6+1.20.2.jar"),
            new ModVariant("1.20.3",  "phimc-1.20.3.jar",  "fabric-api-0.91.1+1.20.3.jar"),
            new ModVariant("1.20.4",  "phimc-1.20.4.jar",  "fabric-api-0.97.3+1.20.4.jar"),
            new ModVariant("1.20.5",  "phimc-1.20.5.jar",  "fabric-api-0.97.8+1.20.5.jar"),
            new ModVariant("1.20.6",  "phimc-1.20.6.jar",  "fabric-api-0.100.8+1.20.6.jar"),
            new ModVariant("1.21",    "phimc-1.21.jar",    "fabric-api-0.102.0+1.21.jar"),
            new ModVariant("1.21.1",  "phimc-1.21.1.jar",  "fabric-api-0.116.17+1.21.1.jar"),
            new ModVariant("1.21.2",  "phimc-1.21.2.jar",  "fabric-api-0.106.1+1.21.2.jar"),
            new ModVariant("1.21.3",  "phimc-1.21.3.jar",  "fabric-api-0.114.1+1.21.3.jar"),
            new ModVariant("1.21.4",  "phimc-1.21.4.jar",  "fabric-api-0.119.4+1.21.4.jar"),
            new ModVariant("1.21.5",  "phimc-1.21.5.jar",  "fabric-api-0.128.2+1.21.5.jar"),
            new ModVariant("1.21.6",  "phimc-1.21.6.jar",  "fabric-api-0.128.2+1.21.6.jar"),
            new ModVariant("1.21.7",  "phimc-1.21.7.jar",  "fabric-api-0.129.0+1.21.7.jar"),
            new ModVariant("1.21.8",  "phimc-1.21.8.jar",  "fabric-api-0.136.1+1.21.8.jar"),
            new ModVariant("1.21.9",  "phimc-1.21.9.jar",  "fabric-api-0.134.1+1.21.9.jar"),
            new ModVariant("1.21.10", "phimc-1.21.10.jar", "fabric-api-0.138.4+1.21.10.jar"),
            new ModVariant("1.21.11", "phimc-1.21.11.jar", "fabric-api-0.141.6+1.21.11.jar")
        };

        /// <summary>mod id（= fabric.mod.json 的 id = 资源命名空间）。</summary>
        private const string ModId = "phimc";
        private const string ModJarPrefix = ModId + "-";
        private const string ModJarSuffix = ".jar";

        private TextBox mcDirBox;
        private ComboBox versionBox;
        private CheckBox isoBox;
        private TextBox log;
        private Label dropHint;
        private Label statusLabel;
        private Label rootLabel;

        public SetupForm()
        {
            Text = "PhiMC 安装器";
            Width = 780;
            Height = 660;
            StartPosition = FormStartPosition.CenterScreen;
            AllowDrop = true;
            BackColor = Color.FromArgb(24, 24, 32);
            ForeColor = Color.Gainsboro;
            Font = new Font("Microsoft YaHei UI", 9F);

            Label title = new Label();
            title.Text = "PhiMC  —  在 Minecraft 里玩 Phigros 谱面";
            title.Font = new Font("Microsoft YaHei UI", 13F, FontStyle.Bold);
            title.ForeColor = Color.White;
            title.SetBounds(20, 14, 720, 30);
            Controls.Add(title);

            Label mcLabel = new Label();
            mcLabel.Text = "游戏目录（.minecraft）：";
            mcLabel.SetBounds(20, 54, 200, 22);
            Controls.Add(mcLabel);

            mcDirBox = new TextBox();
            mcDirBox.SetBounds(20, 76, 600, 26);
            mcDirBox.Text = DetectMinecraft();
            Controls.Add(mcDirBox);

            Button browse = new Button();
            browse.Text = "浏览…";
            browse.SetBounds(630, 75, 110, 28);
            browse.Click += delegate { BrowseMinecraft(); };
            Controls.Add(browse);

            Label verLabel = new Label();
            verLabel.Text = "游戏版本：";
            verLabel.SetBounds(20, 112, 80, 22);
            Controls.Add(verLabel);

            versionBox = new ComboBox();
            versionBox.SetBounds(100, 110, 190, 26);
            versionBox.DropDownStyle = ComboBoxStyle.DropDownList;
            versionBox.SelectedIndexChanged += delegate { ApplyLayoutForVersion(); };
            Controls.Add(versionBox);

            Button refresh = new Button();
            refresh.Text = "刷新";
            refresh.SetBounds(298, 109, 70, 28);
            refresh.Click += delegate { RefreshVersions(true); };
            Controls.Add(refresh);

            Button fabricBtn = new Button();
            fabricBtn.Text = "装 Fabric";
            fabricBtn.SetBounds(372, 109, 90, 28);
            fabricBtn.Click += delegate { InstallFabric(); };
            Controls.Add(fabricBtn);

            isoBox = new CheckBox();
            isoBox.Text = "版本隔离（强制）";
            isoBox.SetBounds(468, 112, 130, 24);
            isoBox.Checked = true;
            isoBox.Enabled = false;
            Controls.Add(isoBox);

            rootLabel = new Label();
            rootLabel.SetBounds(20, 144, 720, 22);
            rootLabel.ForeColor = Color.LightSkyBlue;
            Controls.Add(rootLabel);

            statusLabel = new Label();
            statusLabel.SetBounds(20, 166, 720, 22);
            statusLabel.ForeColor = Color.Gray;
            Controls.Add(statusLabel);

            Button install = new Button();
            install.Text = "① 一键安装 mod + 建好文件夹";
            install.SetBounds(20, 194, 300, 42);
            install.Font = new Font("Microsoft YaHei UI", 10F, FontStyle.Bold);
            install.Click += delegate { FullInstall(); };
            Controls.Add(install);

            Button openSongs = new Button();
            openSongs.Text = "打开曲目文件夹";
            openSongs.SetBounds(332, 194, 160, 42);
            openSongs.Click += delegate { OpenFolder(SongsDir()); };
            Controls.Add(openSongs);

            Button openMods = new Button();
            openMods.Text = "打开 mods 文件夹";
            openMods.SetBounds(504, 194, 160, 42);
            openMods.Click += delegate { OpenFolder(ModsDir()); };
            Controls.Add(openMods);

            Button diag = new Button();
            diag.Text = "③ 检查安装（诊断）";
            diag.SetBounds(20, 244, 180, 28);
            diag.Click += delegate { Diagnose(); };
            Controls.Add(diag);

            Button songsBtn = new Button();
            songsBtn.Text = "④ 只投放内置谱面";
            songsBtn.SetBounds(208, 244, 180, 28);
            songsBtn.Click += delegate
            {
                InstallBuiltinSongs();
                Print("谱面目录：" + SongsDir());
                Diagnose();
            };
            Controls.Add(songsBtn);

            dropHint = new Label();
            dropHint.Text = "② 把谱面包（*.zip）拖到这里导入\r\n也可以拖一整个文件夹";
            dropHint.SetBounds(20, 282, 720, 72);
            dropHint.TextAlign = ContentAlignment.MiddleCenter;
            dropHint.BackColor = Color.FromArgb(40, 44, 56);
            dropHint.ForeColor = Color.LightSkyBlue;
            dropHint.BorderStyle = BorderStyle.FixedSingle;
            dropHint.AllowDrop = true;
            dropHint.DragEnter += OnDragEnter;
            dropHint.DragDrop += OnDragDrop;
            Controls.Add(dropHint);
            DragEnter += OnDragEnter;
            DragDrop += OnDragDrop;

            log = new TextBox();
            log.Multiline = true;
            log.ScrollBars = ScrollBars.Vertical;
            log.ReadOnly = true;
            log.SetBounds(20, 362, 720, 240);
            log.BackColor = Color.FromArgb(16, 16, 22);
            log.ForeColor = Color.LightGreen;
            log.Font = new Font("Consolas", 9F);
            Controls.Add(log);

            LoadSettings();
            RefreshVersions(false);
            Print("提示：先确认目录和版本，再点【①】。版本隔离的启动器（PCL2/HMCL）要把“版本隔离”勾上。");
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

        /// <summary>上次扫到几个版本 / 有没有 Fabric 版本（给状态行用）。</summary>
        private int detectedCount;
        private bool detectedFabric;

        private void ApplyLayoutForVersion()
        {
            string mc = DetectInstanceMc(SelectedVersion());
            ModVariant v = VariantFor(mc);
            rootLabel.Text = "安装到：" + InstallRoot() +
                (Isolated() ? "   （版本隔离）" : "   （全局）");
            if (v != null)
            {
                statusLabel.ForeColor = Color.LightGreen;
                statusLabel.Text = "实例 MC " + mc + " → " + v.JarName + " + " + v.FabricApiName
                    + "  （检测到 " + detectedCount + " 个版本"
                    + (detectedFabric ? "" : "，没有 Fabric 版本！点【装 Fabric】") + "）";
            }
            else
            {
                statusLabel.ForeColor = Color.OrangeRed;
                statusLabel.Text = (mc == null ? "认不出这个实例的 MC 版本" : "不支持 Minecraft " + mc)
                    + "（支持：" + SupportedMcList() + "）—— 不会装错，请换一个受支持的实例";
            }
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
                    "iso=" + (Isolated() ? "1" : "0") + "\r\n");
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
                }
            }
            catch (Exception) { }
        }

        private void LoadVersionAndIso(string ver, bool iso)
        {
            if (ver != null)
            {
                for (int i = 0; i < versionBox.Items.Count; i++)
                {
                    if (versionBox.Items[i].ToString() == ver) { versionBox.SelectedIndex = i; break; }
                }
            }
            isoBox.Checked = iso;
        }

        // ---------- 版本探测 ----------

        private void RefreshVersions(bool verbose)
        {
            string keep = SelectedVersion();
            versionBox.Items.Clear();
            string versions = Path.Combine(McDir(), "versions");
            int found = 0;
            bool hasFabric = false;
            if (Directory.Exists(versions))
            {
                string[] dirs = Directory.GetDirectories(versions);
                Array.Sort(dirs);
                foreach (string d in dirs)
                {
                    string name = Path.GetFileName(d);
                    versionBox.Items.Add(name);
                    if (name.ToLowerInvariant().Contains("fabric")) hasFabric = true;
                    found++;
                }
            }
            if (found == 0)
            {
                versionBox.Items.Add(Variants[Variants.Length - 1].Mc);
            }

            detectedCount = found;
            detectedFabric = hasFabric;

            // 优先：Fabric+支持版本 > 含 fabric > 上次选的 > 支持版本 > 第一个
            int idx = -1;
            for (int i = 0; i < versionBox.Items.Count && idx < 0; i++)
            {
                string s = versionBox.Items[i].ToString().ToLowerInvariant();
                if (s.Contains("fabric") && VariantFor(DetectInstanceMc(versionBox.Items[i].ToString())) != null) idx = i;
            }
            for (int i = 0; i < versionBox.Items.Count && idx < 0; i++)
                if (versionBox.Items[i].ToString().ToLowerInvariant().Contains("fabric")) idx = i;
            for (int i = 0; i < versionBox.Items.Count && idx < 0; i++)
                if (keep != null && versionBox.Items[i].ToString() == keep) idx = i;
            for (int i = 0; i < versionBox.Items.Count && idx < 0; i++)
                if (VariantFor(DetectInstanceMc(versionBox.Items[i].ToString())) != null) idx = i;
            versionBox.SelectedIndex = idx >= 0 ? idx : 0;

            bool iso = GuessIsolation(SelectedVersion());
            isoBox.Checked = iso;
            ApplyLayoutForVersion();

            if (!hasFabric && verbose) Print("× 没找到任何 Fabric 版本 —— mod 不会加载，先点【装 Fabric】");
            if (verbose) Print("√ 扫描完成：" + found + " 个版本，" + (iso ? "已按版本隔离处理" : "按全局 mods 处理"));
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
            if (versionBox.SelectedItem == null) return "";
            return versionBox.SelectedItem.ToString();
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

        private void Print(string s)
        {
            log.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + s + "\r\n");
        }

        private void OpenFolder(string path)
        {
            try
            {
                if (!Directory.Exists(path)) Directory.CreateDirectory(path);
                Process.Start("explorer.exe", "\"" + path + "\"");
            }
            catch (Exception e)
            {
                Print("打开失败：" + e.Message);
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

        /// <summary>读当前实例对应的内嵌 mod jar；不支持这个版本时返回 null。</summary>
        private byte[] ReadEmbeddedJar(ModVariant v)
        {
            if (v == null) return null;
            byte[] jar = ReadEmbedded(v.JarName);
            if (jar != null) return jar;
            // 资源名对不上时的兜底：找名字以这个 jar 名结尾的资源
            Assembly asm = Assembly.GetExecutingAssembly();
            foreach (string name in asm.GetManifestResourceNames())
                if (name.EndsWith(v.JarName, StringComparison.OrdinalIgnoreCase))
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
                    File.WriteAllText(hint, "安装器已经把 19 张谱面放好了；想再放自己的，直接把这个文件夹当作 songs/ 用即可。\r\n启动游戏后拿「谱面播放器」右键开始。\r\n");
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
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "PhiMC_手动安装");
            Directory.CreateDirectory(dir);
            File.WriteAllBytes(Path.Combine(dir, v.JarName), jar);
            byte[] api = ReadEmbedded(v.FabricApiName);
            if (api != null) File.WriteAllBytes(Path.Combine(dir, v.FabricApiName), api);
            foreach (string z in new System.Collections.Generic.List<string>(dropped))
            {
                try { if (File.Exists(z)) File.Copy(z, Path.Combine(dir, Path.GetFileName(z)), true); }
                catch (Exception) { }
            }
            File.WriteAllText(Path.Combine(dir, "说明.txt"),
                "1) " + v.JarName + " 与 " + v.FabricApiName + " 复制到：" + ModsDir() + "\r\n" +
                "2) *.zip 复制到：" + SongsDir() + "\r\n" +
                "3) 还是拒绝访问：右键文件夹 → 属性 → 安全 → 编辑 → 你的用户 → 完全控制\r\n");
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
        /// mods/ 里属于本 mod 的所有 jar：文件名是本 mod 前缀，<b>或者</b> jar 内 fabric.mod.json
        /// 的 id 就是本 mod（后者兜得住「改名前的旧 jar」—— 文件名前缀不同，但 mod id 相同，
        /// 两个一起加载会冲突，必须一视同仁地清掉）。
        /// </summary>
        private string[] OwnModJars()
        {
            var list = new System.Collections.Generic.List<string>();
            foreach (string f in Directory.GetFiles(ModsDir(), "*" + ModJarSuffix))
            {
                string name = Path.GetFileName(f);
                if (name.StartsWith(ModJarPrefix, StringComparison.OrdinalIgnoreCase)) { list.Add(f); continue; }
                if (string.Equals(JarModId(f), ModId, StringComparison.OrdinalIgnoreCase)) list.Add(f);
            }
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
        /// 把某个版本对应的 mod jar + Fabric API 写进 mods/（先清掉别的同 mod jar，避免两个版本同时被加载）。
        /// 内嵌资源优先，其次看安装器旁边的 dist/&lt;jar 名&gt;。
        /// </summary>
        private bool InstallModFiles(ModVariant v, bool verbose, out string err)
        {
            err = null;
            byte[] jar = ReadEmbeddedJar(v);
            if (jar == null)
            {
                string local = Path.Combine(Path.GetDirectoryName(Application.ExecutablePath), "dist", v.JarName);
                if (File.Exists(local)) jar = File.ReadAllBytes(local);
            }
            if (jar == null) { err = "找不到内嵌 jar（" + v.JarName + "）"; return false; }

            Directory.CreateDirectory(ModsDir());
            // 清掉其它版本的 mod jar：两个版本的 PhiMC 同时在 mods/ 里会双双加载失败
            try
            {
                foreach (string old in OwnModJars())
                {
                    if (string.Equals(Path.GetFileName(old), v.JarName, StringComparison.OrdinalIgnoreCase)) continue;
                    File.Delete(old);
                    if (verbose) Print("· 已删除旧 jar：" + Path.GetFileName(old));
                }
            }
            catch (Exception e) { if (verbose) Print("· 清理旧 jar 失败（不影响本次写入）：" + e.Message); }

            string target = Path.Combine(ModsDir(), v.JarName);
            if (!WriteJarVerified(jar, target, out err)) return false;
            if (verbose) Print("√ 已写入 mod：" + target + "  " + jar.Length + " 字节（已回读校验）");
            return InstallFabricApi(v, verbose);
        }

        private void FullInstall()
        {
            string ver = SelectedVersion();
            if (ver.Length == 0)
            {
                MessageBox.Show(this, "请先选择游戏版本。", "提示", MessageBoxButtons.OK, MessageBoxIcon.Warning);
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
                MessageBox.Show(this,
                    why + "。\r\n\r\n实例：" + ver + "\r\n支持：" + SupportedMcList() + "\r\n\r\n" +
                    "不会安装（避免装错版本）。请换一个受支持的实例，或把该实例升级/降级到支持列表里的版本。",
                    "不支持这个版本", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
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
                    if (!EnsureDirs(out err)) { Print("× 仍然失败：" + err); return; }
                    Print("√ 补权限后成功");
                }
                else if (TryElevate()) { Print("已用管理员身份重开安装器，请在新窗口再点【①】"); return; }
                else return;
            }
            Print("√ 目录就绪：" + InstallRoot());
            Print("  实例 Minecraft " + mc + "  →  " + variant.JarName + " + " + variant.FabricApiName);

            if (!InstallModFiles(variant, true, out err))
            {
                Print("× 写入失败：" + err);
                Print("  当前是否管理员：" + (IsElevated() ? "是" : "否"));
                Print("  目标 ACL：" + AclOf(ModsDir()));
                string fallback;
                try { fallback = ExportFallback(ReadEmbeddedJar(variant), variant); Print("→ 已导出到：" + fallback); }
                catch (Exception ex) { fallback = "(导出失败: " + ex.Message + ")"; Print("× " + fallback); }
                DialogResult r = MessageBox.Show(this,
                    "写文件失败：\r\n\r\n" + err + "\r\n\r\n要【是】= 以管理员重试，【否】= 打开导出目录手动复制？",
                    "写入失败", MessageBoxButtons.YesNo, MessageBoxIcon.Error);
                if (r == DialogResult.Yes) TryElevate();
                else Process.Start("explorer.exe", "\"" + fallback + "\"");
                return;
            }
            Print("  曲目目录：" + SongsDir());

            InstallBuiltinSongs();

            foreach (string z in new System.Collections.Generic.List<string>(dropped))
            {
                try { ImportZip(z); }
                catch (Exception e) { Print("× " + Path.GetFileName(z) + "：" + e.Message); }
            }

            SaveSettings();
            Diagnose();
            MessageBox.Show(this,
                "安装完成！\r\n\r\n" +
                "实例：" + ver + "（Minecraft " + mc + "，" + (Isolated() ? "版本隔离" : "全局") + "）\r\n" +
                "mod：" + Path.Combine(ModsDir(), variant.JarName) + "\r\n" +
                "Fabric API：" + variant.FabricApiName + "\r\n" +
                "曲目：" + SongsDir() + "\r\n\r\n" +
                "下一步：\r\n" +
                "1) 启动游戏，用启动器选「" + ver + "」\r\n" +
                "2) 创造模式物品栏 → PhiMC 页签 → 拿「谱面播放器」\r\n" +
                "3) 右键它选歌开始",
                "完成", MessageBoxButtons.OK, MessageBoxIcon.Information);
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
                    + (Isolated() ? " --iso" : "");
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
            string jar = v == null ? null : Path.Combine(ModsDir(), v.JarName);
            if (jar != null && File.Exists(jar))
            {
                FileInfo fi = new FileInfo(jar);
                string hash = BitConverter.ToString(System.Security.Cryptography.SHA256.Create()
                    .ComputeHash(File.ReadAllBytes(jar))).Replace("-", "").Substring(0, 12).ToLowerInvariant();
                Print("  jar：√ " + fi.Length + " 字节  sha256=" + hash + "  (" + v.JarName + ")");
            }
            else Print("  jar：× 不存在（" + (jar ?? ModsDir() + "\\" + ModJarPrefix + "<MC 版本>.jar") + "）");

            try
            {
                string[] jars = OwnModJars();
                if (jars.Length > 1)
                    Print("  ⚠ mods/ 里有 " + jars.Length + " 个 " + ModId + " jar（只能有一个，否则会加载失败）");
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
            string mc = null, ver = null;
            bool iso = false;
            System.Collections.Generic.List<string> zips = new System.Collections.Generic.List<string>();
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--install") silent = true;
                else if (args[i] == "--mc" && i + 1 < args.Length) mc = args[++i];
                else if (args[i] == "--ver" && i + 1 < args.Length) ver = args[++i];
                else if (args[i] == "--iso") iso = true;
                else if (args[i] == "--zip" && i + 1 < args.Length) zips.Add(args[++i]);
                else if (args[i] == "--force") forceWrite = true;
            }

            if (silent)
            {
                SetupForm f = new SetupForm();
                if (mc != null) f.mcDirBox.Text = mc;
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
                        f.Print("√ 已写入 mod：" + Path.Combine(f.ModsDir(), variant.JarName)
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
                try { File.WriteAllText(Path.Combine(f.McDir(), "phimc-install.log"), f.log.Text); } catch (Exception) { }
                try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "phimc-install.log"), f.log.Text); } catch (Exception) { }
                Environment.Exit(ok ? 0 : 1);
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            SetupForm form = new SetupForm();
            if (mc != null) { form.mcDirBox.Text = mc; form.RefreshVersions(false); }
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
