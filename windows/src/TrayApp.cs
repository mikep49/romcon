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
        readonly System.Windows.Forms.Timer updateTimer = new System.Windows.Forms.Timer { Interval = 10000 };
        bool exiting, updateBalloon, updateAnnounced;

        public readonly Updater Updater = new Updater();

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

            form = new MainForm(this, server, config, Updater, LoadAppIcon(new Size(32, 32)));
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
            Ui.StyleMenu(menu);

            // Explorer asks programs to add their tray icons again when the taskbar comes back after a restart, or comes up after
            // RomCon at sign-in. Windows drops that request on its way to a program running as administrator unless it is let in.
            if (IsElevated) Native.ChangeWindowMessageFilter(Native.RegisterWindowMessage("TaskbarCreated"), Native.MSGFLT_ADD);
            tray = new NotifyIcon { Icon = iconIdle, Text = "RomCon", ContextMenuStrip = menu, Visible = true };
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };
            tray.BalloonTipClicked += (s, e) =>
            {
                if (!updateBalloon) return;
                ShowWindow();
                form.ShowSettings();
            };
            tray.BalloonTipClosed += (s, e) => updateBalloon = false;

            server.Changed += () => OnUi(RefreshTray);
            server.Connected += session =>
            {
                if (config.NotifyOnConnect) OnUi(() => ShowBalloon(session.DeviceName + " connected", false));
            };
            Updater.Changed += () => OnUi(AnnounceUpdate);
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

            // Look for a newer RomCon once it has settled in, which also gives the network time to come up after signing in.
            Updater.CleanUpInBackground();
            updateTimer.Tick += (s, e) =>
            {
                updateTimer.Stop();
                Updater.Check(true);
            };
            updateTimer.Start();
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

        /// <summary>Downloads the new version, then restarts into it.</summary>
        public void InstallUpdate()
        {
            Updater.Install(
                () => OnUi(RestartIntoUpdate),
                reason => OnUi(() =>
                {
                    var answer = MessageBox.Show(form, "RomCon couldn't update itself.\n\n" + reason + "\n\nDownload the new version from GitHub instead?", "RomCon",
                                                 MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                    if (answer == DialogResult.Yes) OpenReleasePage();
                }));
        }

        public void OpenReleasePage()
        {
            string url = Updater.Status.Latest?.PageUrl ?? Updater.ReleasesPage;
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
            catch (Exception ex) { Log.Info("Couldn't open " + url + ": " + ex.Message); }
        }

        void RestartIntoUpdate()
        {
            try
            {
                // The new RomCon waits for this one to let go before it takes over, and comes back with the window open if it was.
                Process.Start(new ProcessStartInfo(Application.ExecutablePath, options.RestartArgs(!form.Visible)) { UseShellExecute = false });
            }
            catch (Exception ex)
            {
                Log.Info("Couldn't start the new version: " + ex.Message);
                MessageBox.Show(form, "RomCon was updated, but couldn't restart: " + ex.Message + "\n\nOpen RomCon again to use the new version.", "RomCon");
            }
            Exit();
        }

        /// <summary>Tells the user once when the check at startup finds a newer version.</summary>
        void AnnounceUpdate()
        {
            var status = Updater.Status;
            if (updateAnnounced || !status.Automatic || status.Stage != UpdateStage.Available) return;
            updateAnnounced = true;
            ShowBalloon("RomCon " + status.Latest.Version + " is available. Click here to update.", true);
        }

        void ShowBalloon(string text, bool aboutUpdate)
        {
            updateBalloon = aboutUpdate;
            tray.ShowBalloonTip(aboutUpdate ? 10000 : 3000, "RomCon", text, ToolTipIcon.Info);
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
                updateTimer.Dispose();
                tray?.Dispose();
                form?.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
