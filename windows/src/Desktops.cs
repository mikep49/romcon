using System;
using System.Diagnostics;
using System.Threading;
using Microsoft.Win32;

namespace RomCon
{
    /// <summary>How many virtual desktops the PC has, and which one is showing (0 is the first).</summary>
    struct DesktopState : IEquatable<DesktopState>
    {
        public static readonly DesktopState Single = new DesktopState(1, 0);

        public readonly int Count;
        public readonly int Current;

        public DesktopState(int count, int current)
        {
            Count = count;
            Current = current;
        }

        public bool Equals(DesktopState other) => Count == other.Count && Current == other.Current;
        public override bool Equals(object obj) => obj is DesktopState d && Equals(d);
        public override int GetHashCode() => Count * 256 + Current;
    }

    /// <summary>
    /// Windows' virtual desktops, the ones Task View shows as Desktop 1, Desktop 2 and so on. Windows has no public API
    /// to list or switch them, but Explorer keeps the list and the current one in the registry, and switching is
    /// Ctrl+Win+Left and Right, so phones can see them and jump to one. Watched only while a phone is connected.
    /// </summary>
    sealed class VirtualDesktops
    {
        const string Key = @"Software\Microsoft\Windows\CurrentVersion\Explorer\VirtualDesktops";
        const int PollMs = 300;
        const int StepWaitMs = 800; // the slide to the next desktop takes about a quarter of a second
        const ushort VK_LEFT = 0x25, VK_RIGHT = 0x27;

        readonly InputInjector injector;
        readonly Timer timer;
        readonly object gate = new object();
        readonly int sessionId = Process.GetCurrentProcess().SessionId;
        DesktopState last = DesktopState.Single;
        bool watching;
        bool switching;
        int target;

        /// <summary>Raised on a background thread when a desktop is added or removed, or another one is shown.</summary>
        public event Action<DesktopState> Changed;

        public VirtualDesktops(InputInjector injector)
        {
            this.injector = injector;
            timer = new Timer(_ => Poll());
        }

        /// <summary>Watch for changes while true, which is while a phone is connected.</summary>
        public bool Watching
        {
            set
            {
                lock (gate)
                {
                    if (watching == value) return;
                    watching = value;
                    if (value) last = Read();
                    timer.Change(value ? PollMs : Timeout.Infinite, value ? PollMs : Timeout.Infinite);
                }
            }
        }

        /// <summary>The desktops as they are now.</summary>
        public DesktopState Current => Read();

        /// <summary>
        /// Shows desktop <paramref name="index"/> (0 is the first), one Ctrl+Win+arrow at a time, waiting for each before
        /// the next so none is lost in the slide. Asked again before it gets there, it heads for the new one instead.
        /// </summary>
        public void SwitchTo(int index)
        {
            lock (gate)
            {
                target = index;
                if (switching) return;
                switching = true;
            }
            ThreadPool.QueueUserWorkItem(_ => RunSwitch());
        }

        void RunSwitch()
        {
            try
            {
                while (true)
                {
                    int want;
                    lock (gate) want = target;
                    if (Step(want)) continue;
                    lock (gate)
                    {
                        if (target != want) continue; // asked for another desktop meanwhile
                        switching = false;
                        break;
                    }
                }
            }
            catch (Exception ex)
            {
                lock (gate) switching = false;
                Log.Info("Couldn't switch desktops: " + ex.Message);
            }
            // Phones hear where it ended up, once, rather than each desktop on the way. Even if that's where it started:
            // the phone that asked already shows the desktop it wanted.
            Poll(true);
        }

        /// <summary>Moves one desktop towards <paramref name="want"/>. False once there, or if it can't get any closer.</summary>
        bool Step(int want)
        {
            DesktopState now = Read();
            if (want < 0 || want >= now.Count || want == now.Current) return false;
            injector.Tap(want > now.Current ? VK_RIGHT : VK_LEFT, Proto.ModCtrl | Proto.ModWin);
            // Nothing changing means Windows didn't take the keys: the lock screen, say, or a dry run.
            for (int waited = 0; waited < StepWaitMs; waited += 25)
            {
                Thread.Sleep(25);
                if (Read().Current != now.Current) return true;
            }
            return false;
        }

        void Poll(bool always = false)
        {
            DesktopState now;
            lock (gate)
            {
                if (!watching || switching) return;
                now = Read();
                if (now.Equals(last) && !always) return;
                last = now;
            }
            Changed?.Invoke(now);
        }

        DesktopState Read()
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(Key))
                {
                    // Until a second desktop is added, Windows may not have written the list at all.
                    if (!(key?.GetValue("VirtualDesktopIDs") is byte[] ids) || ids.Length < 16) return DesktopState.Single;
                    int count = Math.Min(ids.Length / 16, 255);
                    // Windows 11 keeps the current desktop beside the list; Windows 10 keeps it per sign-in session.
                    byte[] current = key.GetValue("CurrentVirtualDesktop") as byte[];
                    if (current == null)
                    {
                        using (var session = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Explorer\SessionInfo\" + sessionId + @"\VirtualDesktops"))
                        {
                            current = session?.GetValue("CurrentVirtualDesktop") as byte[];
                        }
                    }
                    return new DesktopState(count, IndexOf(ids, count, current));
                }
            }
            catch (Exception)
            {
                return DesktopState.Single;
            }
        }

        static int IndexOf(byte[] ids, int count, byte[] id)
        {
            if (id == null || id.Length != 16) return 0;
            for (int i = 0; i < count; i++)
            {
                int j = 0;
                while (j < 16 && ids[i * 16 + j] == id[j]) j++;
                if (j == 16) return i;
            }
            return 0;
        }
    }
}
