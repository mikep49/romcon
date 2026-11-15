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

        readonly Options options;
        readonly Config config;
        readonly RemoteServer server;
        readonly NotifyIcon tray;
        readonly MainForm form;
        readonly Icon iconIdle, iconConnected, iconPaused;
        readonly ToolStripMenuItem pauseItem;
        readonly RegisteredWaitHandle showWait;
        bool exiting;

        public TrayApp(Options options, Config config, EventWaitHandle showEvent)
        {
            this.options = options;
            this.config = config;
            Log.Info("RomCon starting" + (options.DryRun ? " in dry-run mode (input is logged, not sent)" : "") + (IsElevated ? " as administrator" : "") + ".");

            if (IsElevated)
            {
                try { Startup.Settle(config.AlwaysAdmin); }
                catch (Exception ex) { Log.Info("Couldn't update how RomCon starts with Windows: " + ex.Message); }
            }

            var certificate = Identity.LoadOrCreate(config.Dir);
            server = new RemoteServer(config, certificate, new InputInjector(options.DryRun), new MediaWatcher(options.DryRun));

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

            // Explorer asks programs to add their tray icons again when the taskbar comes back after a restart, or comes up after
            // RomCon at sign-in. Windows drops that request on its way to a program running as administrator unless it is let in.
            if (IsElevated) Native.ChangeWindowMessageFilter(Native.RegisterWindowMessage("TaskbarCreated"), Native.MSGFLT_ADD);
            tray = new NotifyIcon { Icon = iconIdle, Text = "RomCon", ContextMenuStrip = menu, Visible = true };
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };

            server.Changed += () => OnUi(RefreshTray);
            server.Connected += session =>
            {
                if (config.NotifyOnConnect) OnUi(() => tray.ShowBalloonTip(3000, "RomCon", session.DeviceName + " connected", ToolTipIcon.Info));
            };
            showWait = ThreadPool.RegisterWaitForSingleObject(showEvent, (s, timedOut) => OnUi(ShowWindow), null, Timeout.Infinite, false);
            SystemEvents.PowerModeChanged += OnPowerModeChanged;

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
            get => Startup.Enabled;
            set => Startup.Set(value, config.AlwaysAdmin);
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

        /// <summary>Restarts RomCon as administrator. False if Windows didn't let it, usually because the user said no.</summary>
        public bool RestartElevated()
        {
            if (!StartElevated(options, !form.Visible)) return false; // with the window open, it comes back open
            Exit();
            return true;
        }

        /// <summary>Starts another RomCon with the same options as administrator, which takes over once this one exits.</summary>
        public static bool StartElevated(Options options, bool minimized)
        {
            try
            {
                var start = new ProcessStartInfo(Application.ExecutablePath, options.RestartArgs(minimized)) { UseShellExecute = true, Verb = "runas" };
                Process.Start(start);
                return true;
            }
            catch (Win32Exception ex)
            {
                if (ex.NativeErrorCode != 1223) Log.Info("Couldn't restart as administrator: " + ex.Message); // 1223: the user said no
                return false;
            }
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

        /// <summary>A sleeping PC plays nothing, and can't say so later, so phones are told on the way down.</summary>
        void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
        {
            if (e.Mode == PowerModes.Suspend) server.Asleep = true;
            else if (e.Mode == PowerModes.Resume) server.Asleep = false;
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
                SystemEvents.PowerModeChanged -= OnPowerModeChanged; // a static event
                showWait?.Unregister(null);
                tray?.Dispose();
                form?.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
