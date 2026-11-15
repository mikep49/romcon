using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

namespace RomCon
{
    /// <summary>
    /// The window: a Home tab (status, paired phones, how to connect) and a Settings tab (theme, options, activity).
    /// Everything is laid out by hand from the window size, and painted flat in the current theme's colours.
    /// </summary>
    sealed class MainForm : Form
    {
        readonly TrayApp app;
        readonly RemoteServer server;
        readonly Config config;

        readonly BarPanel tabBar = new BarPanel();
        readonly TabButton homeTab = new TabButton("Home");
        readonly TabButton settingsTab = new TabButton("Settings");
        readonly CardPanel home = new CardPanel();
        readonly CardPanel settings = new CardPanel();

        // Home: status
        readonly TextBlock statusTitle = new TextBlock("", Ui.Title);
        readonly TextBlock statusDetail = new TextBlock("", Ui.Small, Tone.Muted);
        readonly FlatButton pauseButton = new FlatButton();
        Rectangle statusDot;
        Tone statusTone = Tone.Primary;

        // Home: paired phones and pairing
        readonly TextBlock phonesHeading = new TextBlock("Paired phones", Ui.Strong);
        readonly Panel phoneList = new Panel { AutoScroll = true };
        readonly List<PhoneRow> phoneRows = new List<PhoneRow>();
        readonly TextBlock noPhones = new TextBlock("No phones yet. Pair one to get started.", Ui.Small, Tone.Muted);
        readonly FlatButton pairButton = new FlatButton { Kind = ButtonKind.Primary, Text = "Pair a new phone", Font = Ui.Strong };
        readonly TextBlock pairNote = new TextBlock("Only paired phones can control this PC. Pairing stays open for " + (int)TrayApp.PairingWindow.TotalMinutes + " minutes.", Ui.Small, Tone.Muted, true);
        readonly TextBlock pinHint = new TextBlock("In the app, tap this PC and enter this PIN:", Ui.Small, Tone.OnPrimaryC) { Surface = Surface.PrimaryC };
        readonly TextBlock pinLabel = new TextBlock("", Ui.Pin, Tone.OnPrimaryC) { Surface = Surface.PrimaryC };
        readonly TextBlock pinExpiry = new TextBlock("", Ui.Small, Tone.OnPrimaryC, true) { Surface = Surface.PrimaryC };
        readonly FlatButton stopButton = new FlatButton { Text = "Stop pairing", Surface = Surface.PrimaryC };
        bool pairingShown;

        // Home: how to connect
        readonly TextBlock connectHeading = new TextBlock("Connect from the RomCon app", Ui.Strong);
        readonly TextBlock connectNote = new TextBlock("On the same Wi-Fi this PC shows up in the app by itself. Over Tailscale or another network, add it in the app by one of these addresses.", Ui.Small, Tone.Muted, true);
        readonly List<AddressRow> addressRows = new List<AddressRow>();
        AddressRow copiedRow;
        DateTime copiedAt;

        // Settings
        readonly TextBlock themeHeading = new TextBlock("Theme", Ui.Strong);
        readonly TextBlock themeNote = new TextBlock("System default follows the Windows light or dark setting.", Ui.Small, Tone.Muted) { AlignRight = true };
        readonly ThemeTile[] themeTiles =
        {
            new ThemeTile(ThemeMode.System), new ThemeTile(ThemeMode.Light), new ThemeTile(ThemeMode.Dark), new ThemeTile(ThemeMode.Black),
        };
        readonly TextBlock optionsHeading = new TextBlock("Options", Ui.Strong);
        readonly ToggleRow startupToggle = new ToggleRow("Start with Windows");
        readonly ToggleRow notifyToggle = new ToggleRow("Notify me when a phone connects");
        readonly TextBlock adminTitle = new TextBlock("Run as administrator", Ui.Body);
        readonly TextBlock adminNote = new TextBlock("Needed to control apps that run as administrator, like Task Manager.", Ui.Small, Tone.Muted, true);
        readonly FlatButton adminButton = new FlatButton { Text = "Restart as administrator" };
        Rectangle optionsDivider;
        readonly TextBlock activityHeading = new TextBlock("Activity", Ui.Strong);
        readonly TextBox logBox = new TextBox();

        readonly Timer timer = new Timer { Interval = 1000 };
        readonly bool elevated = TrayApp.IsElevated;
        int homeNeeds, settingsNeeds;
        bool allowClose;
        bool refreshing;

        public MainForm(TrayApp app, RemoteServer server, Config config, Icon icon)
        {
            this.app = app;
            this.server = server;
            this.config = config;

            SuspendLayout();
            AutoScaleMode = AutoScaleMode.None;
            Text = "RomCon";
            Icon = icon;
            Font = Ui.Body;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(S(860), S(584));
            MinimumSize = SizeFromClientSize(new Size(MinClientWidth, MinClientHeight));
            ShowInTaskbar = true;

            BuildTabs();
            BuildHome();
            BuildSettings();
            // Fill-docked pages first, then the bar that takes the top edge.
            Controls.Add(home);
            Controls.Add(settings);
            Controls.Add(tabBar);
            ResumeLayout(true);
            ApplyTheme();

            server.Changed += () => OnUi(RefreshAll);
            Log.Line += line => OnUi(() => AppendLog(line));
            NetworkChange.NetworkAddressChanged += (s, e) => OnUi(RefreshAddresses);
            SystemEvents.UserPreferenceChanged += OnWindowsColoursChanged;
            timer.Tick += (s, e) => Tick();
            VisibleChanged += (s, e) =>
            {
                timer.Enabled = Visible;
                if (Visible) RefreshAll();
            };
        }

        static int S(int px)
        {
            return Ui.S(px);
        }

        static int MinClientWidth => S(820);
        static int MinClientHeight => S(560);

        public void AllowClose()
        {
            allowClose = true;
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!allowClose && e.CloseReason == CloseReason.UserClosing)
            {
                // Closing the window keeps RomCon running in the tray.
                e.Cancel = true;
                Hide();
                return;
            }
            base.OnFormClosing(e);
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Theme.StyleWindow(Handle, Theme.Current);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                // A static event: it would keep the window alive if left attached.
                SystemEvents.UserPreferenceChanged -= OnWindowsColoursChanged;
                timer.Dispose();
            }
            base.Dispose(disposing);
        }

        // ---- Building ----

        void BuildTabs()
        {
            tabBar.Dock = DockStyle.Top;
            tabBar.Height = S(41);
            int x = S(24);
            foreach (var tab in new[] { homeTab, settingsTab })
            {
                tab.SetBounds(x, 0, tab.FitWidth(), S(40));
                x += tab.Width + S(20);
                tabBar.Controls.Add(tab);
            }
            homeTab.Checked = true;
            homeTab.CheckedChanged += (s, e) => ShowPage();
            home.Dock = DockStyle.Fill;
            settings.Dock = DockStyle.Fill;
            settings.Visible = false;
        }

        void ShowPage()
        {
            bool showHome = homeTab.Checked;
            home.Visible = showHome;
            settings.Visible = !showHome;
            if (showHome)
            {
                LayoutHome();
            }
            else
            {
                LayoutSettings();
                ScrollLogToEnd(); // open on the newest entries
            }
        }

        void BuildHome()
        {
            pauseButton.Click += (s, e) => server.Paused = !server.Paused;
            pairButton.Click += (s, e) => app.StartPairing();
            stopButton.Click += (s, e) => server.Pairing.Stop();

            noPhones.Dock = DockStyle.Top;
            noPhones.Height = S(40);
            phoneList.Controls.Add(noPhones);
            phoneList.HandleCreated += (s, e) => Theme.StyleScrollBars(phoneList.Handle, Theme.Current);
            foreach (var c in new Control[] { pinHint, pinLabel, pinExpiry, stopButton }) c.Visible = false;

            home.Controls.AddRange(new Control[]
            {
                statusTitle, statusDetail, pauseButton,
                phonesHeading, phoneList, pairButton, pairNote, pinHint, pinLabel, pinExpiry, stopButton,
                connectHeading, connectNote,
            });
            home.Paint += (s, e) => Ui.FillCircle(e.Graphics, Theme.Current.Of(statusTone), statusDot);
            home.Resize += (s, e) => LayoutHome();
        }

        void BuildSettings()
        {
            foreach (var tile in themeTiles)
            {
                tile.Checked = tile.Mode == config.Theme;
                tile.CheckedChanged += (s, e) =>
                {
                    if (!tile.Checked || config.Theme == tile.Mode) return;
                    config.Theme = tile.Mode;
                    SaveSettings();
                    ApplyTheme();
                };
            }

            startupToggle.CheckedChanged += (s, e) =>
            {
                if (refreshing) return;
                try { app.StartWithWindows = startupToggle.Checked; }
                catch (Exception ex) { MessageBox.Show(this, "Couldn't change the startup setting: " + ex.Message, "RomCon"); }
            };
            notifyToggle.CheckedChanged += (s, e) =>
            {
                if (refreshing) return;
                config.NotifyOnConnect = notifyToggle.Checked;
                SaveSettings();
            };
            adminButton.Click += (s, e) => app.RestartElevated();

            logBox.Multiline = true;
            logBox.ReadOnly = true;
            logBox.BorderStyle = BorderStyle.None;
            logBox.Font = Ui.MonoSmall;
            logBox.TabStop = false;
            logBox.Lines = Log.Snapshot();
            logBox.HandleCreated += (s, e) => Theme.StyleScrollBars(logBox.Handle, Theme.Current);

            settings.Controls.Add(themeHeading);
            settings.Controls.Add(themeNote);
            settings.Controls.AddRange(themeTiles);
            settings.Controls.AddRange(new Control[] { optionsHeading, startupToggle, notifyToggle });
            // An elevated RomCon has nothing to offer here.
            if (!elevated) settings.Controls.AddRange(new Control[] { adminTitle, adminNote, adminButton });
            settings.Controls.AddRange(new Control[] { activityHeading, logBox });
            settings.Paint += (s, e) =>
            {
                using (var brush = new SolidBrush(Theme.Current.Line)) e.Graphics.FillRectangle(brush, optionsDivider);
            };
            settings.Resize += (s, e) => LayoutSettings();
        }

        void SaveSettings()
        {
            try { config.SaveSettings(); } catch (Exception ex) { Log.Info("Couldn't save settings: " + ex.Message); }
        }

        // ---- Theme ----

        void ApplyTheme()
        {
            var p = Theme.Current = Theme.For(config.Theme);
            BackColor = p.Bg;
            ForeColor = p.Text;
            phoneList.BackColor = p.Card;
            logBox.BackColor = p.Inset;
            logBox.ForeColor = p.Muted;
            if (IsHandleCreated) Theme.StyleWindow(Handle, p);
            if (logBox.IsHandleCreated) Theme.StyleScrollBars(logBox.Handle, p);
            if (phoneList.IsHandleCreated) Theme.StyleScrollBars(phoneList.Handle, p);
            Invalidate(true);
        }

        void OnWindowsColoursChanged(object sender, UserPreferenceChangedEventArgs e)
        {
            if (e.Category != UserPreferenceCategory.General || config.Theme != ThemeMode.System) return;
            OnUi(() =>
            {
                if (Theme.For(ThemeMode.System) != Theme.Current) ApplyTheme();
            });
        }

        // ---- Layout ----

        void LayoutHome()
        {
            int width = home.ClientSize.Width, height = home.ClientSize.Height;
            if (refreshing || width <= 0 || height <= 0) return;
            int margin = S(24), gap = S(16), padX = S(20), padY = S(18);
            home.Shapes.Clear();

            // Status
            var status = new Rectangle(margin, S(20), width - 2 * margin, S(72));
            home.Add(status, Surface.Card, S(8));
            statusDot = new Rectangle(status.Left + padX, status.Top + (status.Height - S(12)) / 2, S(12), S(12));
            int pauseWidth = pauseButton.FitWidth();
            pauseButton.SetBounds(status.Right - padX - pauseWidth, status.Top + (status.Height - S(34)) / 2, pauseWidth, S(34));
            int textLeft = statusDot.Right + S(14), textWidth = pauseButton.Left - S(16) - textLeft;
            int textTop = status.Top + (status.Height - statusTitle.LineHeight - statusDetail.LineHeight) / 2;
            statusTitle.SetBounds(textLeft, textTop, textWidth, statusTitle.LineHeight);
            statusDetail.SetBounds(textLeft, statusTitle.Bottom, textWidth, statusDetail.LineHeight);

            int cardTop = status.Bottom + gap, cardHeight = height - cardTop - margin;
            int cardWidth = (width - 2 * margin - gap) / 2;
            var left = new Rectangle(margin, cardTop, cardWidth, cardHeight);
            var right = new Rectangle(left.Right + gap, cardTop, width - margin - left.Right - gap, cardHeight);
            home.Add(left, Surface.Card, S(8));
            home.Add(right, Surface.Card, S(8));

            // Paired phones, with pairing pinned under the list. The list scrolls if there are more phones than fit.
            int x = left.Left + padX, w = left.Width - 2 * padX, y = left.Top + padY;
            phonesHeading.SetBounds(x, y, w, phonesHeading.LineHeight);
            y = phonesHeading.Bottom + S(8);

            int boxPadX = S(16), boxPadY = S(14), boxWidth = w - 2 * boxPadX;
            int pairHeight = pairingShown
                ? 2 * boxPadY + pinHint.LineHeight + pinLabel.LineHeight + S(4) + pinExpiry.HeightFor(boxWidth) + S(12) + S(32)
                : S(36) + S(10) + pairNote.HeightFor(w);
            int rowHeight = S(56);
            int listWanted = phoneRows.Count == 0 ? noPhones.Height : phoneRows.Count * rowHeight;
            int listRoom = left.Bottom - padY - pairHeight - S(16) - y;
            int listHeight = Math.Max(Math.Min(listWanted, rowHeight), Math.Min(listWanted, listRoom));
            phoneList.SetBounds(x, y, w, listHeight);
            y = phoneList.Bottom + S(16);
            int leftNeeds = phoneList.Top + Math.Min(listWanted, rowHeight) + S(16) + pairHeight + padY;

            if (pairingShown)
            {
                home.Add(new Rectangle(x, y, w, pairHeight), Surface.PrimaryC, S(8));
                int bx = x + boxPadX, by = y + boxPadY;
                pinHint.SetBounds(bx, by, boxWidth, pinHint.LineHeight);
                pinLabel.SetBounds(bx, pinHint.Bottom, boxWidth, pinLabel.LineHeight);
                pinExpiry.SetBounds(bx, pinLabel.Bottom + S(4), boxWidth, pinExpiry.HeightFor(boxWidth));
                stopButton.SetBounds(bx, pinExpiry.Bottom + S(12), stopButton.FitWidth(), S(32));
            }
            else
            {
                pairButton.SetBounds(x, y, pairButton.FitWidth() + S(4), S(36));
                pairNote.SetBounds(x, pairButton.Bottom + S(10), w, pairNote.HeightFor(w));
            }

            // How to connect
            x = right.Left + padX;
            w = right.Width - 2 * padX;
            y = right.Top + padY;
            connectHeading.SetBounds(x, y, w, connectHeading.LineHeight);
            connectNote.SetBounds(x, connectHeading.Bottom + S(10), w, connectNote.HeightFor(w));
            y = connectNote.Bottom + S(12);
            foreach (var row in addressRows)
            {
                row.SetBounds(x, y, w, S(34));
                y = row.Bottom + S(6);
            }
            int rightNeeds = y - S(6) + padY;

            home.Invalidate();
            homeNeeds = Math.Max(leftNeeds, rightNeeds) + margin;
            FitWindow();
        }

        void LayoutSettings()
        {
            int width = settings.ClientSize.Width, height = settings.ClientSize.Height;
            if (refreshing || width <= 0 || height <= 0) return;
            int margin = S(24), gap = S(16), padX = S(20), padY = S(18);
            settings.Shapes.Clear();

            // Theme
            var theme = new Rectangle(margin, S(20), width - 2 * margin, 2 * padY + themeHeading.LineHeight + S(12) + S(52));
            settings.Add(theme, Surface.Card, S(8));
            int x = theme.Left + padX, w = theme.Width - 2 * padX, y = theme.Top + padY;
            int headingWidth = themeHeading.NaturalWidth + S(2), noteWidth = themeNote.NaturalWidth + S(2);
            themeHeading.SetBounds(x, y, headingWidth, themeHeading.LineHeight);
            themeNote.Visible = headingWidth + S(16) + noteWidth <= w;
            themeNote.SetBounds(x + w - noteWidth, y + themeHeading.LineHeight - themeNote.LineHeight - S(1), noteWidth, themeNote.LineHeight);
            y = themeHeading.Bottom + S(12);
            int tileGap = S(10), tileWidth = (w - 3 * tileGap) / 4;
            for (int i = 0; i < themeTiles.Length; i++)
            {
                themeTiles[i].SetBounds(x + i * (tileWidth + tileGap), y, tileWidth, S(52));
            }

            int cardTop = theme.Bottom + gap, cardHeight = height - cardTop - margin;
            int cardWidth = (width - 2 * margin - gap) / 2;
            var left = new Rectangle(margin, cardTop, cardWidth, cardHeight);
            var right = new Rectangle(left.Right + gap, cardTop, width - margin - left.Right - gap, cardHeight);
            settings.Add(left, Surface.Card, S(8));
            settings.Add(right, Surface.Card, S(8));

            // Options
            x = left.Left + padX;
            w = left.Width - 2 * padX;
            y = left.Top + padY;
            optionsHeading.SetBounds(x, y, w, optionsHeading.LineHeight);
            startupToggle.SetBounds(x, optionsHeading.Bottom + S(8), w, S(40));
            notifyToggle.SetBounds(x, startupToggle.Bottom, w, S(40));
            y = notifyToggle.Bottom;
            optionsDivider = Rectangle.Empty;
            if (!elevated)
            {
                optionsDivider = new Rectangle(x, y + S(8), w, S(1));
                adminTitle.SetBounds(x, optionsDivider.Bottom + S(14), w, adminTitle.LineHeight);
                adminNote.SetBounds(x, adminTitle.Bottom + S(2), w, adminNote.HeightFor(w));
                adminButton.SetBounds(x, adminNote.Bottom + S(12), adminButton.FitWidth(), S(34));
                y = adminButton.Bottom;
            }
            int leftNeeds = y + padY;

            // Activity: the log sits in an inset box that takes the rest of the card.
            x = right.Left + padX;
            w = right.Width - 2 * padX;
            y = right.Top + padY;
            activityHeading.SetBounds(x, y, w, activityHeading.LineHeight);
            var box = Rectangle.FromLTRB(x, activityHeading.Bottom + S(12), x + w, right.Bottom - padY);
            settings.Add(box, Surface.Inset, S(6));
            // A whole number of lines, so the last one is never cut in half.
            int lineHeight = logBox.Font.Height, logHeight = Math.Max(0, box.Height - S(20)) / lineHeight * lineHeight;
            logBox.SetBounds(box.Left + S(12), box.Top + S(10), Math.Max(0, box.Width - S(16)), logHeight);
            UpdateLogScrollBar();

            settings.Invalidate();
            settingsNeeds = leftNeeds + margin;
            FitWindow();
        }

        /// <summary>Keeps the window at least tall enough that neither page's cards are cut off, growing it if needed.</summary>
        void FitWindow()
        {
            int needed = Math.Max(MinClientHeight, tabBar.Height + Math.Max(homeNeeds, settingsNeeds));
            var min = SizeFromClientSize(new Size(MinClientWidth, needed));
            min.Height = Math.Min(min.Height, Screen.FromControl(this).WorkingArea.Height);
            if (MinimumSize != min) MinimumSize = min;
        }

        // ---- Live state ----

        void RefreshAll()
        {
            refreshing = true;
            try
            {
                var sessions = server.Sessions;
                if (server.StartError != null)
                {
                    SetStatus(Tone.Error, "Not listening", server.StartError);
                }
                else if (server.Paused)
                {
                    SetStatus(Tone.Warn, "Paused", "Phones can stay connected, but their input is ignored.");
                }
                else if (sessions.Count > 0)
                {
                    SetStatus(Tone.Ok, "Connected", string.Join(", ", sessions.Select(s => s.DeviceName).Distinct()) + " can control this PC.");
                }
                else
                {
                    SetStatus(Tone.Primary, "Ready", "Waiting for your phone." + (elevated ? " Running as administrator." : ""));
                }
                pauseButton.Text = server.Paused ? "Resume remote control" : "Pause remote control";
                pauseButton.Glyph = server.Paused ? Ui.IconPlay : Ui.IconPause;

                RefreshAddresses();
                RefreshPairing();
                RefreshDevices(sessions);
                notifyToggle.Checked = config.NotifyOnConnect;
                try { startupToggle.Checked = app.StartWithWindows; } catch { }
            }
            finally
            {
                refreshing = false;
            }
            LayoutHome();
        }

        void SetStatus(Tone tone, string title, string detail)
        {
            statusTone = tone;
            statusTitle.Text = title;
            statusDetail.Text = detail;
        }

        void RefreshAddresses()
        {
            var rows = new List<string[]>();
            var addresses = NetInfo.Addresses();
            if (addresses.Count == 0) rows.Add(new[] { "Network", "No connection found", null });
            foreach (var a in addresses) rows.Add(new[] { a.Label, a.Address, "" });
            rows.Add(new[] { "PC name", config.ServerName, "" });
            rows.Add(new[] { "Port", server.Port.ToString(), "" });
            rows.Add(new[] { "PC code", server.VerifyCode, "" });

            while (addressRows.Count > rows.Count)
            {
                var last = addressRows[addressRows.Count - 1];
                addressRows.Remove(last);
                if (copiedRow == last) copiedRow = null;
                home.Controls.Remove(last);
                last.Dispose();
            }
            while (addressRows.Count < rows.Count)
            {
                var row = new AddressRow();
                row.Copy.Click += (s, e) => CopyValue(row);
                addressRows.Add(row);
                home.Controls.Add(row);
            }
            for (int i = 0; i < rows.Count; i++)
            {
                // A row that now shows something else no longer shows what was copied.
                if (addressRows[i] == copiedRow && addressRows[i].Value != rows[i][1]) ClearCopied();
                addressRows[i].Set(rows[i][0], rows[i][1], rows[i][2] != null);
            }
            LayoutHome();
        }

        void CopyValue(AddressRow row)
        {
            try
            {
                Clipboard.SetText(row.Value);
            }
            catch (ExternalException)
            {
                return; // another program is holding the clipboard
            }
            ClearCopied();
            copiedRow = row;
            copiedAt = DateTime.UtcNow;
            row.Copied = true;
        }

        void ClearCopied()
        {
            if (copiedRow != null) copiedRow.Copied = false;
            copiedRow = null;
        }

        void Tick()
        {
            RefreshPairing();
            if (copiedRow != null && DateTime.UtcNow - copiedAt > TimeSpan.FromSeconds(1.5)) ClearCopied();
        }

        void RefreshPairing()
        {
            string pin = server.Pairing.Pin;
            bool active = pin != null;
            if (active)
            {
                pinLabel.Text = pin.Substring(0, 3) + " " + pin.Substring(3);
                var left = server.Pairing.ExpiresUtc - DateTime.UtcNow;
                if (left < TimeSpan.Zero) left = TimeSpan.Zero;
                pinExpiry.Text = "Expires in " + (int)left.TotalMinutes + ":" + left.Seconds.ToString("00") +
                                 ". The app shows the PC code " + server.VerifyCode + "; check that it matches.";
            }
            if (active == pairingShown) return;
            pairingShown = active;
            foreach (var c in new Control[] { pinHint, pinLabel, pinExpiry, stopButton }) c.Visible = active;
            pairButton.Visible = pairNote.Visible = !active;
            LayoutHome();
        }

        void RefreshDevices(List<ClientSession> sessions)
        {
            var devices = config.Devices.OrderByDescending(x => x.LastSeenUtc).ToList();
            phoneList.SuspendLayout();
            while (phoneRows.Count > devices.Count)
            {
                var last = phoneRows[phoneRows.Count - 1];
                phoneRows.Remove(last);
                phoneList.Controls.Remove(last);
                last.Dispose();
            }
            while (phoneRows.Count < devices.Count)
            {
                var row = new PhoneRow { Dock = DockStyle.Top, Height = S(56) };
                row.Remove.Click += (s, e) => RemovePhone(row);
                phoneRows.Add(row);
                phoneList.Controls.Add(row);
                row.BringToFront(); // top-docked controls stack in reverse order, so the newest goes to the bottom
            }
            for (int i = 0; i < devices.Count; i++)
            {
                var d = devices[i];
                var live = sessions.FirstOrDefault(s => s.DeviceId == d.Id);
                string status = live != null ? "Connected from " + live.Address
                    : server.HasMediaLink(d.Id) ? "Ready to show media controls"
                    : "Last seen " + Ago(d.LastSeenUtc);
                phoneRows[i].Set(d.Id, d.Name, status, live != null);
            }
            noPhones.Visible = devices.Count == 0;
            phoneList.ResumeLayout();
            LayoutHome();
        }

        void RemovePhone(PhoneRow row)
        {
            string id = row.DeviceId, name = row.DeviceName;
            var answer = MessageBox.Show(this, "Remove \"" + name + "\"? It will need to pair again to control this PC.", "RomCon",
                                         MessageBoxButtons.OKCancel, MessageBoxIcon.Question);
            if (answer != DialogResult.OK) return;
            config.RemoveDevice(id);
            server.Disconnect(id);
            Log.Info("Removed " + name + ".");
            RefreshAll();
        }

        void AppendLog(string line)
        {
            if (logBox.Lines.Length > 400)
            {
                logBox.Lines = Log.Snapshot();
                return;
            }
            if (logBox.TextLength > 0) logBox.AppendText(Environment.NewLine);
            logBox.AppendText(line);
            UpdateLogScrollBar();
        }

        /// <summary>Shows the log's scroll bar only once there is more than fits, so a short log has no empty track beside it.</summary>
        void UpdateLogScrollBar()
        {
            if (!logBox.IsHandleCreated || logBox.ClientSize.Height <= 0) return;
            int lines = logBox.GetLineFromCharIndex(logBox.TextLength) + 1;
            int fits = logBox.ClientSize.Height / logBox.Font.Height;
            // Taking the bar away re-wraps the text, so wait until there is a little room to spare.
            var wanted = lines > fits ? ScrollBars.Vertical : lines + 2 <= fits ? ScrollBars.None : logBox.ScrollBars;
            if (wanted == logBox.ScrollBars) return;
            logBox.ScrollBars = wanted;
            ScrollLogToEnd();
        }

        void ScrollLogToEnd()
        {
            logBox.SelectionStart = logBox.TextLength;
            logBox.ScrollToCaret();
        }

        static string Ago(DateTime utc)
        {
            if (utc == DateTime.MinValue) return "never";
            var span = DateTime.UtcNow - utc;
            if (span.TotalMinutes < 1) return "just now";
            if (span.TotalHours < 1) return (int)span.TotalMinutes + " min ago";
            if (span.TotalDays < 1) return (int)span.TotalHours + " h ago";
            if (span.TotalDays < 30) return (int)span.TotalDays + " days ago";
            return utc.ToLocalTime().ToString("d");
        }

        void OnUi(Action action)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(action); } catch (InvalidOperationException) { }
        }
    }
}
