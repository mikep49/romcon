using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Security.Principal;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace RomCon
{
    sealed class TrayApp : ApplicationContext
    {
        public static readonly TimeSpan PairingWindow = TimeSpan.FromMinutes(3);
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunValue = "RomCon";

        readonly Options options;
        readonly Config config;
        readonly RemoteServer server;
        readonly NotifyIcon tray;
        readonly MainForm form;
        readonly Icon iconIdle, iconConnected, iconPaused;
        readonly ToolStripMenuItem pauseItem;
        readonly RegisteredWaitHandle showWait;
        bool exiting;

        public TrayApp(Options options, EventWaitHandle showEvent)
        {
            this.options = options;
            config = Config.Load(options.DataDir);
            if (options.Port.HasValue) config.Port = options.Port.Value;
            Log.Init(config.Dir);
            Log.Info("RomCon starting" + (options.DryRun ? " in dry-run mode (input is logged, not sent)" : "") + (IsElevated ? " as administrator" : "") + ".");

            var certificate = Identity.LoadOrCreate(config.Dir);
            server = new RemoteServer(config, certificate, new InputInjector(options.DryRun));

            Icon appIcon = LoadAppIcon(SystemInformation.SmallIconSize);
            iconIdle = appIcon;
            iconConnected = WithBadge(appIcon, Color.FromArgb(34, 197, 94));
            iconPaused = WithBadge(appIcon, Color.FromArgb(234, 179, 8));

            form = new MainForm(this, server, config, LoadAppIcon(new Size(32, 32)));
            form.CreateControl();
            _ = form.Handle; // so background threads can marshal onto the UI thread before the window is ever shown

            pauseItem = new ToolStripMenuItem("Pause remote control", null, (s, e) => server.Paused = !server.Paused);
            var menu = new ContextMenuStrip();
            menu.Items.Add(new ToolStripMenuItem("Open RomCon", null, (s, e) => ShowWindow()) { Font = new Font(menu.Font, FontStyle.Bold) });
            menu.Items.Add("Pair a new phone", null, (s, e) => { StartPairing(); ShowWindow(); });
            menu.Items.Add(pauseItem);
            menu.Items.Add(new ToolStripSeparator());
            if (!IsElevated) menu.Items.Add("Restart as administrator", null, (s, e) => RestartElevated());
            menu.Items.Add("Exit", null, (s, e) => Exit());

            tray = new NotifyIcon { Icon = iconIdle, Text = "RomCon", ContextMenuStrip = menu, Visible = true };
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };

            server.Changed += () => OnUi(RefreshTray);
            server.Connected += session =>
            {
                if (config.NotifyOnConnect) OnUi(() => tray.ShowBalloonTip(3000, "RomCon", session.DeviceName + " connected", ToolTipIcon.Info));
            };
            showWait = ThreadPool.RegisterWaitForSingleObject(showEvent, (s, timedOut) => OnUi(ShowWindow), null, Timeout.Infinite, false);

            server.Start();
            if (server.StartError != null)
            {
                MessageBox.Show("RomCon couldn't start listening for phones.\n\n" + server.StartError +
                                "\n\nAnother program may be using the port. You can change it in " + Path.Combine(config.Dir, "settings.ini") + ".",
                                "RomCon", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }

            bool firstRun = !config.HasDevices;
            if (firstRun) StartPairing();
            if (!options.Minimized || firstRun) ShowWindow();
            RefreshTray();
        }

        public static bool IsElevated
        {
            get
            {
                using (var id = WindowsIdentity.GetCurrent()) return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
            }
        }

        public bool StartWithWindows
        {
            get
            {
                using (var key = Registry.CurrentUser.OpenSubKey(RunKey)) return key?.GetValue(RunValue) != null;
            }
            set
            {
                using (var key = Registry.CurrentUser.CreateSubKey(RunKey))
                {
                    if (value) key.SetValue(RunValue, "\"" + Application.ExecutablePath + "\" --minimized");
                    else key.DeleteValue(RunValue, false);
                }
            }
        }

        public void StartPairing()
        {
            server.Pairing.Start(PairingWindow);
            if (options.DryRun) Log.Info("[dry-run] pairing PIN " + server.Pairing.Pin);
        }

        public void ShowWindow()
        {
            if (form.IsDisposed) return;
            form.Show();
            if (form.WindowState == FormWindowState.Minimized) form.WindowState = FormWindowState.Normal;
            form.Activate();
        }

        public void RestartElevated()
        {
            var args = "--restarted" + (options.Minimized ? " --minimized" : "") + (options.DryRun ? " --dry-run" : "");
            try
            {
                Process.Start(new ProcessStartInfo(Application.ExecutablePath, args) { UseShellExecute = true, Verb = "runas" });
            }
            catch (Win32Exception)
            {
                return; // the user said no to the UAC prompt
            }
            Exit();
        }

        public void Exit()
        {
            if (exiting) return;
            exiting = true;
            server.Stop();
            tray.Visible = false;
            form.AllowClose();
            form.Close();
            ExitThread();
        }

        void RefreshTray()
        {
            int count = server.Sessions.Count;
            pauseItem.Checked = server.Paused;
            tray.Icon = server.Paused ? iconPaused : count > 0 ? iconConnected : iconIdle;
            string text = server.Paused ? "RomCon - paused" : count == 0 ? "RomCon - waiting for a phone" : "RomCon - " + count + " phone" + (count == 1 ? "" : "s") + " connected";
            tray.Text = text.Length > 63 ? text.Substring(0, 63) : text;
        }

        void OnUi(Action action)
        {
            if (exiting || form.IsDisposed || !form.IsHandleCreated) return;
            try { form.BeginInvoke(action); } catch (InvalidOperationException) { }
        }

        static Icon LoadAppIcon(Size size)
        {
            using (var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("RomCon.app.ico"))
            {
                if (stream == null) return new Icon(SystemIcons.Application, size);
                return new Icon(stream, size);
            }
        }

        static Icon WithBadge(Icon icon, Color color)
        {
            using (var bmp = icon.ToBitmap())
            {
                using (var g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    float d = bmp.Width * 0.5f;
                    var rect = new RectangleF(bmp.Width - d, bmp.Height - d, d - 0.5f, d - 0.5f);
                    using (var ring = new SolidBrush(Color.White)) g.FillEllipse(ring, rect);
                    rect.Inflate(-bmp.Width * 0.07f, -bmp.Width * 0.07f);
                    using (var fill = new SolidBrush(color)) g.FillEllipse(fill, rect);
                }
                IntPtr h = bmp.GetHicon();
                var result = (Icon)Icon.FromHandle(h).Clone();
                Native.DestroyIcon(h);
                return result;
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                showWait?.Unregister(null);
                tray?.Dispose();
                form?.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
