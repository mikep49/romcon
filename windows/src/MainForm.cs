using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Net.NetworkInformation;
using System.Windows.Forms;

namespace RomCon
{
    sealed class MainForm : Form
    {
        readonly TrayApp app;
        readonly RemoteServer server;
        readonly Config config;
        readonly Font baseFont = SystemFonts.MessageBoxFont;
        readonly Font headingFont;
        readonly Font statusFont;
        readonly Font pinFont;
        readonly Font monoFont = new Font("Consolas", 10f);

        readonly Label statusDot = new Label();
        readonly Label statusLabel = new Label();
        readonly Label statusDetail = new Label();
        readonly TableLayoutPanel addressTable = new TableLayoutPanel();
        readonly Panel pairIdle = new Panel();
        readonly Panel pairActive = new Panel();
        readonly Label pinLabel = new Label();
        readonly Label pinExpiry = new Label();
        readonly ListView deviceList = new ListView();
        readonly Button removeButton = new Button();
        readonly CheckBox startupCheck = new CheckBox();
        readonly CheckBox notifyCheck = new CheckBox();
        readonly CheckBox pauseCheck = new CheckBox();
        readonly TextBox logBox = new TextBox();
        readonly Timer timer = new Timer { Interval = 1000 };
        bool allowClose;
        bool refreshing;

        public MainForm(TrayApp app, RemoteServer server, Config config, Icon icon)
        {
            this.app = app;
            this.server = server;
            this.config = config;
            headingFont = new Font(baseFont.FontFamily, 10f, FontStyle.Bold);
            statusFont = new Font(baseFont.FontFamily, 13f, FontStyle.Bold);
            pinFont = new Font(baseFont.FontFamily, 30f, FontStyle.Bold);

            // Sizes below are in 96-DPI pixels; Windows scales them to the screen's DPI when layout resumes.
            SuspendLayout();
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = "RomCon";
            Icon = icon;
            Font = baseFont;
            BackColor = SystemColors.Window;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(720, 500);
            MinimumSize = new Size(660, 480);
            ShowInTaskbar = true;

            BuildLayout();
            ResumeLayout(false);
            PerformLayout();

            server.Changed += () => OnUi(RefreshAll);
            Log.Line += line => OnUi(() => AppendLog(line));
            NetworkChange.NetworkAddressChanged += (s, e) => OnUi(RefreshAddresses);
            timer.Tick += (s, e) => RefreshPairing();
            VisibleChanged += (s, e) =>
            {
                timer.Enabled = Visible;
                if (Visible) RefreshAll();
            };
        }

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

        const int ColumnWidth = 320;

        void BuildLayout()
        {
            // Two columns: how to connect on the left, phones and options on the right.
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 2, Padding = new Padding(18, 12, 18, 14) };
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

            // Status header
            var header = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Dock = DockStyle.Top, Margin = new Padding(0, 0, 0, 4) };
            header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            statusDot.Text = "●";
            statusDot.Font = statusFont;
            statusDot.AutoSize = true;
            statusDot.Margin = new Padding(0, 0, 2, 0);
            statusLabel.Font = statusFont;
            statusLabel.AutoSize = true;
            statusDetail.AutoSize = true;
            statusDetail.ForeColor = SystemColors.GrayText;
            statusDetail.Margin = new Padding(3, 0, 3, 0);
            header.Controls.Add(statusDot, 0, 0);
            header.Controls.Add(statusLabel, 1, 0);
            header.Controls.Add(statusDetail, 1, 1);
            root.Controls.Add(header, 0, 0);
            root.SetColumnSpan(header, 2);

            var left = Column();
            left.Dock = DockStyle.Top;
            left.Margin = new Padding(0, 0, 18, 0);
            var right = Column();
            right.Dock = DockStyle.Fill;
            root.Controls.Add(left, 0, 1);
            root.Controls.Add(right, 1, 1);

            // Left: addresses and pairing
            AddRow(left, Heading("Connect from the RomCon app"));
            AddRow(left, Note("On the same Wi-Fi this PC shows up in the app by itself. Over Tailscale or another network, add it in the app by one of these addresses."));
            addressTable.ColumnCount = 2;
            addressTable.AutoSize = true;
            addressTable.Dock = DockStyle.Top;
            addressTable.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            addressTable.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            addressTable.Margin = new Padding(0, 0, 0, 4);
            AddRow(left, addressTable);

            AddRow(left, Heading("Pairing"));
            var pairButton = new Button { Text = "Pair a new phone", AutoSize = true, Padding = new Padding(8, 2, 8, 2), Margin = new Padding(0, 2, 0, 4) };
            pairButton.Click += (s, e) => app.StartPairing();
            var idleFlow = Flow();
            idleFlow.Controls.Add(pairButton);
            idleFlow.Controls.Add(Note("Only paired phones can control this PC. Pairing stays open for " + (int)TrayApp.PairingWindow.TotalMinutes + " minutes."));
            pairIdle.AutoSize = true;
            pairIdle.Dock = DockStyle.Top;
            pairIdle.Controls.Add(idleFlow);

            pinLabel.Font = pinFont;
            pinLabel.AutoSize = true;
            pinLabel.ForeColor = Color.FromArgb(37, 99, 235);
            pinLabel.Margin = new Padding(0);
            pinExpiry.AutoSize = true;
            pinExpiry.MaximumSize = new Size(ColumnWidth, 0);
            pinExpiry.ForeColor = SystemColors.GrayText;
            var stopButton = new Button { Text = "Stop pairing", AutoSize = true, Margin = new Padding(0, 6, 0, 0) };
            stopButton.Click += (s, e) => server.Pairing.Stop();
            var activeFlow = Flow();
            activeFlow.Controls.Add(Note("In the app, tap this PC and enter this PIN:"));
            activeFlow.Controls.Add(pinLabel);
            activeFlow.Controls.Add(pinExpiry);
            activeFlow.Controls.Add(stopButton);
            pairActive.AutoSize = true;
            pairActive.Dock = DockStyle.Top;
            pairActive.Controls.Add(activeFlow);
            AddRow(left, pairIdle);
            AddRow(left, pairActive);

            // Right: phones, options, activity
            AddRow(right, Heading("Paired phones"));
            deviceList.View = View.Details;
            deviceList.FullRowSelect = true;
            deviceList.HeaderStyle = ColumnHeaderStyle.Nonclickable;
            deviceList.MultiSelect = false;
            deviceList.Dock = DockStyle.Top;
            deviceList.Height = 104;
            deviceList.Margin = new Padding(0, 2, 0, 4);
            deviceList.Columns.Add("Phone", 130);
            deviceList.Columns.Add("Status", 180);
            deviceList.SelectedIndexChanged += (s, e) => removeButton.Enabled = deviceList.SelectedItems.Count > 0 && deviceList.SelectedItems[0].Tag != null;
            deviceList.Resize += (s, e) => FitColumns();
            AddRow(right, deviceList);

            removeButton.Text = "Remove phone";
            removeButton.AutoSize = true;
            removeButton.Enabled = false;
            removeButton.Margin = new Padding(0, 0, 0, 2);
            removeButton.Click += (s, e) => RemoveSelected();
            AddRow(right, removeButton);

            AddRow(right, Heading("Options"));
            startupCheck.Text = "Start with Windows";
            startupCheck.AutoSize = true;
            startupCheck.CheckedChanged += (s, e) =>
            {
                if (refreshing) return;
                try { app.StartWithWindows = startupCheck.Checked; }
                catch (Exception ex) { MessageBox.Show(this, "Couldn't change the startup setting: " + ex.Message, "RomCon"); }
            };
            notifyCheck.Text = "Notify me when a phone connects";
            notifyCheck.AutoSize = true;
            notifyCheck.CheckedChanged += (s, e) =>
            {
                if (refreshing) return;
                config.NotifyOnConnect = notifyCheck.Checked;
                try { config.SaveSettings(); } catch (Exception ex) { Log.Info("Couldn't save settings: " + ex.Message); }
            };
            pauseCheck.Text = "Pause remote control";
            pauseCheck.AutoSize = true;
            pauseCheck.CheckedChanged += (s, e) =>
            {
                if (!refreshing) server.Paused = pauseCheck.Checked;
            };
            var options = Flow();
            options.Controls.Add(startupCheck);
            options.Controls.Add(notifyCheck);
            options.Controls.Add(pauseCheck);
            if (!TrayApp.IsElevated)
            {
                var admin = new LinkLabel { Text = "Restart as administrator", AutoSize = true, Margin = new Padding(3, 4, 3, 0) };
                admin.LinkClicked += (s, e) => app.RestartElevated();
                options.Controls.Add(admin);
                options.Controls.Add(Note("Needed to control apps that run as administrator, like Task Manager."));
            }
            AddRow(right, options);

            AddRow(right, Heading("Activity"));
            logBox.Multiline = true;
            logBox.ReadOnly = true;
            logBox.ScrollBars = ScrollBars.Vertical;
            logBox.Dock = DockStyle.Fill;
            logBox.BackColor = SystemColors.Control;
            logBox.BorderStyle = BorderStyle.None;
            logBox.Font = new Font("Consolas", 8.25f);
            logBox.Margin = new Padding(0, 2, 0, 0);
            logBox.Lines = Log.Snapshot();
            right.Controls.Add(logBox);
            right.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

            Controls.Add(root);
        }

        static TableLayoutPanel Column()
        {
            var t = new TableLayoutPanel { ColumnCount = 1, AutoSize = true, Margin = new Padding(0), Padding = new Padding(0) };
            t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            return t;
        }

        static FlowLayoutPanel Flow()
        {
            return new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Dock = DockStyle.Fill, Margin = new Padding(0) };
        }

        static void AddRow(TableLayoutPanel table, Control control)
        {
            table.Controls.Add(control);
            table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        }

        Label Heading(string text)
        {
            return new Label { Text = text, Font = headingFont, AutoSize = true, Margin = new Padding(0, 10, 0, 3) };
        }

        Label Note(string text)
        {
            return new Label { Text = text, AutoSize = true, MaximumSize = new Size(ColumnWidth, 0), ForeColor = SystemColors.GrayText, Margin = new Padding(0, 0, 0, 4) };
        }

        void RefreshAll()
        {
            refreshing = true;
            try
            {
                var sessions = server.Sessions;
                if (server.StartError != null)
                {
                    SetStatus(Color.FromArgb(220, 38, 38), "Not listening", server.StartError);
                }
                else if (server.Paused)
                {
                    SetStatus(Color.FromArgb(234, 179, 8), "Paused", "Phones can stay connected, but their input is ignored.");
                }
                else if (sessions.Count > 0)
                {
                    SetStatus(Color.FromArgb(34, 197, 94), "Connected", string.Join(", ", sessions.Select(s => s.DeviceName).Distinct()) + " can control this PC.");
                }
                else
                {
                    SetStatus(Color.FromArgb(37, 99, 235), "Ready", "Waiting for your phone. " + (TrayApp.IsElevated ? "Running as administrator." : ""));
                }

                RefreshAddresses();
                RefreshPairing();
                RefreshDevices(sessions);
                pauseCheck.Checked = server.Paused;
                notifyCheck.Checked = config.NotifyOnConnect;
                try { startupCheck.Checked = app.StartWithWindows; } catch { }
            }
            finally
            {
                refreshing = false;
            }
        }

        void SetStatus(Color color, string title, string detail)
        {
            statusDot.ForeColor = color;
            statusLabel.Text = title;
            statusDetail.Text = detail;
        }

        void RefreshAddresses()
        {
            addressTable.SuspendLayout();
            addressTable.Controls.Clear();
            addressTable.RowStyles.Clear();
            var addresses = NetInfo.Addresses();
            int row = 0;
            if (addresses.Count == 0)
            {
                addressTable.Controls.Add(Note("No network connection found."), 0, row++);
            }
            foreach (var a in addresses)
            {
                AddAddressRow(row++, a.Label, a.Address);
            }
            AddAddressRow(row++, "PC name", config.ServerName);
            AddAddressRow(row++, "Port", server.Port.ToString());
            AddAddressRow(row++, "PC code", server.VerifyCode);
            addressTable.ResumeLayout();
        }

        void AddAddressRow(int row, string label, string value)
        {
            var l = new Label { Text = label, AutoSize = true, ForeColor = SystemColors.GrayText, Margin = new Padding(0, 4, 12, 0) };
            var v = new TextBox
            {
                Text = value,
                ReadOnly = true,
                BorderStyle = BorderStyle.None,
                BackColor = BackColor,
                Font = monoFont,
                Dock = DockStyle.Fill,
                Margin = new Padding(0, 4, 0, 0),
                TabStop = false,
            };
            addressTable.Controls.Add(l, 0, row);
            addressTable.Controls.Add(v, 1, row);
        }

        void RefreshPairing()
        {
            string pin = server.Pairing.Pin;
            bool active = pin != null;
            pairActive.Visible = active;
            pairIdle.Visible = !active;
            if (!active) return;
            pinLabel.Text = pin.Substring(0, 3) + " " + pin.Substring(3);
            var left = server.Pairing.ExpiresUtc - DateTime.UtcNow;
            if (left < TimeSpan.Zero) left = TimeSpan.Zero;
            pinExpiry.Text = "Expires in " + (int)left.TotalMinutes + ":" + left.Seconds.ToString("00") +
                             ". The app shows the PC code " + server.VerifyCode + "; check that it matches.";
        }

        void RefreshDevices(List<ClientSession> sessions)
        {
            string selected = deviceList.SelectedItems.Count > 0 ? (string)deviceList.SelectedItems[0].Tag : null;
            deviceList.BeginUpdate();
            deviceList.Items.Clear();
            foreach (var d in config.Devices.OrderByDescending(x => x.LastSeenUtc))
            {
                var live = sessions.FirstOrDefault(s => s.DeviceId == d.Id);
                string status = live != null ? "Connected from " + live.Address : "Last seen " + Ago(d.LastSeenUtc);
                var item = new ListViewItem(new[] { d.Name, status }) { Tag = d.Id };
                if (live != null) item.ForeColor = Color.FromArgb(21, 128, 61);
                deviceList.Items.Add(item);
                if (d.Id == selected) item.Selected = true;
            }
            if (deviceList.Items.Count == 0)
            {
                deviceList.Items.Add(new ListViewItem(new[] { "No phones yet", "" }) { ForeColor = SystemColors.GrayText });
            }
            deviceList.EndUpdate();
            removeButton.Enabled = deviceList.SelectedItems.Count > 0 && deviceList.SelectedItems[0].Tag != null;
            FitColumns();
        }

        void FitColumns()
        {
            if (deviceList.Columns.Count < 2) return;
            int width = deviceList.ClientSize.Width;
            deviceList.Columns[0].Width = Math.Max(100, width * 4 / 10);
            deviceList.Columns[1].Width = Math.Max(100, width - deviceList.Columns[0].Width - 4);
        }

        void RemoveSelected()
        {
            if (deviceList.SelectedItems.Count == 0 || deviceList.SelectedItems[0].Tag == null) return;
            var item = deviceList.SelectedItems[0];
            string id = (string)item.Tag;
            var answer = MessageBox.Show(this, "Remove \"" + item.Text + "\"? It will need to pair again to control this PC.", "RomCon",
                                         MessageBoxButtons.OKCancel, MessageBoxIcon.Question);
            if (answer != DialogResult.OK) return;
            config.RemoveDevice(id);
            server.Disconnect(id);
            Log.Info("Removed " + item.Text + ".");
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
