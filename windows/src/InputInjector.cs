using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using static RomCon.Native;

namespace RomCon
{
    /// <summary>Turns remote commands into SendInput calls. With dryRun it only logs them.</summary>
    sealed class InputInjector
    {
        // Keys that need KEYEVENTF_EXTENDEDKEY so Windows reads them as the navigation-block versions.
        static readonly HashSet<ushort> ExtendedKeys = new HashSet<ushort>
        {
            0x21, 0x22, 0x23, 0x24, // Page Up, Page Down, End, Home
            0x25, 0x26, 0x27, 0x28, // arrows
            0x2C, 0x2D, 0x2E,       // Print Screen, Insert, Delete
            0x5B, 0x5C, 0x5D,       // left/right Windows, Apps
            0x6F, 0x90,             // numpad divide, Num Lock
            0xA3, 0xA5,             // right Ctrl, right Alt
            0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xAB, 0xAC, // browser keys
            0xAD, 0xAE, 0xAF,       // volume
            0xB0, 0xB1, 0xB2, 0xB3, // media
        };

        const ushort VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D;
        const ushort VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_LWIN = 0x5B;

        readonly bool dryRun;
        int failuresLogged;

        public InputInjector(bool dryRun)
        {
            this.dryRun = dryRun;
        }

        public void Move(int dx, int dy)
        {
            if (dx == 0 && dy == 0) return;
            if (dryRun) { Log.Info("[dry-run] move " + dx + "," + dy); return; }
            Send(MouseInput(MOUSEEVENTF_MOVE, dx, dy, 0));
        }

        public void Button(int button, bool down)
        {
            if (dryRun) { Log.Info("[dry-run] button " + button + (down ? " down" : " up")); return; }
            // SendInput speaks physical buttons; follow the user's left-handed setting so "left" stays primary.
            if (GetSystemMetrics(SM_SWAPBUTTON) != 0)
            {
                if (button == Proto.ButtonLeft) button = Proto.ButtonRight;
                else if (button == Proto.ButtonRight) button = Proto.ButtonLeft;
            }
            uint flags, data = 0;
            switch (button)
            {
                case Proto.ButtonLeft: flags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP; break;
                case Proto.ButtonRight: flags = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP; break;
                case Proto.ButtonMiddle: flags = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP; break;
                case Proto.ButtonBack: flags = down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP; data = XBUTTON1; break;
                case Proto.ButtonForward: flags = down ? MOUSEEVENTF_XDOWN : MOUSEEVENTF_XUP; data = XBUTTON2; break;
                default: return;
            }
            Send(MouseInput(flags, 0, 0, data));
        }

        /// <summary>Wheel deltas in Windows units: 120 is one notch. Positive vertical scrolls up, positive horizontal scrolls right.</summary>
        public void Wheel(int vertical, int horizontal)
        {
            if (dryRun) { Log.Info("[dry-run] wheel " + vertical + "," + horizontal); return; }
            var list = new List<INPUT>(2);
            if (vertical != 0) list.Add(MouseInput(MOUSEEVENTF_WHEEL, 0, 0, unchecked((uint)vertical)));
            if (horizontal != 0) list.Add(MouseInput(MOUSEEVENTF_HWHEEL, 0, 0, unchecked((uint)horizontal)));
            if (list.Count > 0) Send(list.ToArray());
        }

        public void Key(ushort vk, bool down)
        {
            if (dryRun) { Log.Info("[dry-run] key 0x" + vk.ToString("X2") + (down ? " down" : " up")); return; }
            Send(KeyInput(vk, down));
        }

        /// <summary>Presses the modifiers, taps the key, and releases the modifiers in reverse order.</summary>
        public void Tap(ushort vk, int mods)
        {
            if (dryRun) { Log.Info("[dry-run] tap 0x" + vk.ToString("X2") + " mods " + mods); return; }
            var mvks = ModifierKeys(mods);
            var list = new List<INPUT>(2 + mvks.Count * 2);
            foreach (var m in mvks) list.Add(KeyInput(m, true));
            list.Add(KeyInput(vk, true));
            list.Add(KeyInput(vk, false));
            for (int i = mvks.Count - 1; i >= 0; i--) list.Add(KeyInput(mvks[i], false));
            Send(list.ToArray());
        }

        /// <summary>A character with modifiers held, e.g. Ctrl + "c". Uses the PC's current keyboard layout to find the key.</summary>
        public void CharWithMods(char c, int mods)
        {
            if (mods == 0) { Text(c.ToString()); return; }
            IntPtr layout = GetKeyboardLayout(GetWindowThreadProcessId(GetForegroundWindow(), out _));
            short scan = VkKeyScanEx(c, layout);
            if (scan == -1)
            {
                // No key types this character on the current layout; send it without the modifiers.
                Text(c.ToString());
                return;
            }
            ushort vk = (ushort)(scan & 0xFF);
            int shiftState = (scan >> 8) & 0xFF;
            if ((shiftState & 1) != 0) mods |= Proto.ModShift;
            if ((shiftState & 2) != 0) mods |= Proto.ModCtrl;
            if ((shiftState & 4) != 0) mods |= Proto.ModAlt;
            Tap(vk, mods);
        }

        /// <summary>Types text as Unicode key events, so it works with any keyboard layout.</summary>
        public void Text(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            if (dryRun) { Log.Info("[dry-run] text \"" + text.Replace("\n", "\\n") + "\""); return; }
            var list = new List<INPUT>(Math.Min(text.Length, 64) * 2);
            foreach (char c in text)
            {
                switch (c)
                {
                    case '\r':
                        continue;
                    case '\n':
                        list.Add(KeyInput(VK_RETURN, true));
                        list.Add(KeyInput(VK_RETURN, false));
                        break;
                    case '\t':
                        list.Add(KeyInput(VK_TAB, true));
                        list.Add(KeyInput(VK_TAB, false));
                        break;
                    case '\b':
                        list.Add(KeyInput(VK_BACK, true));
                        list.Add(KeyInput(VK_BACK, false));
                        break;
                    default:
                        list.Add(UnicodeInput(c, false));
                        list.Add(UnicodeInput(c, true));
                        break;
                }
                // Long pastes overwhelm some apps if delivered in one burst.
                if (list.Count >= 128)
                {
                    Send(list.ToArray());
                    list.Clear();
                    Thread.Sleep(4);
                }
            }
            if (list.Count > 0) Send(list.ToArray());
        }

        public void LockPc()
        {
            if (dryRun) { Log.Info("[dry-run] lock workstation"); return; }
            if (!LockWorkStation()) Log.Info("Couldn't lock the PC (error " + Marshal.GetLastWin32Error() + ")");
        }

        /// <summary>
        /// Taps Windows' own volume up or down key [times] times, which also brings up its volume bar. False if
        /// Windows refused the input.
        /// </summary>
        public static bool TapVolumeKey(bool up, int times)
        {
            ushort vk = up ? (ushort)0xAF : (ushort)0xAE;
            var inputs = new INPUT[times * 2];
            for (int i = 0; i < times; i++)
            {
                inputs[i * 2] = KeyInput(vk, true);
                inputs[i * 2 + 1] = KeyInput(vk, false);
            }
            return SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT))) == inputs.Length;
        }

        static List<ushort> ModifierKeys(int mods)
        {
            var list = new List<ushort>(4);
            if ((mods & Proto.ModCtrl) != 0) list.Add(VK_CONTROL);
            if ((mods & Proto.ModAlt) != 0) list.Add(VK_MENU);
            if ((mods & Proto.ModShift) != 0) list.Add(VK_SHIFT);
            if ((mods & Proto.ModWin) != 0) list.Add(VK_LWIN);
            return list;
        }

        void Send(params INPUT[] inputs)
        {
            uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
            if (sent != inputs.Length && failuresLogged < 5)
            {
                failuresLogged++;
                int err = Marshal.GetLastWin32Error();
                Log.Info("Windows blocked input (error " + err + "). This happens on the lock screen, in UAC prompts, " +
                         "and in apps running as administrator unless RomCon also runs as administrator.");
            }
        }

        static INPUT MouseInput(uint flags, int dx, int dy, uint data)
        {
            var input = new INPUT { type = INPUT_MOUSE };
            input.u.mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags };
            return input;
        }

        static INPUT KeyInput(ushort vk, bool down)
        {
            uint flags = down ? 0 : KEYEVENTF_KEYUP;
            if (ExtendedKeys.Contains(vk)) flags |= KEYEVENTF_EXTENDEDKEY;
            var input = new INPUT { type = INPUT_KEYBOARD };
            input.u.ki = new KEYBDINPUT { wVk = vk, wScan = (ushort)MapVirtualKey(vk, MAPVK_VK_TO_VSC), dwFlags = flags };
            return input;
        }

        static INPUT UnicodeInput(char c, bool up)
        {
            var input = new INPUT { type = INPUT_KEYBOARD };
            input.u.ki = new KEYBDINPUT { wVk = 0, wScan = c, dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0) };
            return input;
        }
    }

    /// <summary>Per-connection view of the injector that remembers what is held down, so a dropped phone never leaves a key stuck.</summary>
    sealed class InputState
    {
        readonly InputInjector injector;
        readonly HashSet<int> buttons = new HashSet<int>();
        readonly HashSet<ushort> keys = new HashSet<ushort>();
        readonly object gate = new object();

        public InputState(InputInjector injector)
        {
            this.injector = injector;
        }

        public InputInjector Injector => injector;

        public void Button(int button, bool down)
        {
            lock (gate)
            {
                if (down) buttons.Add(button); else buttons.Remove(button);
                injector.Button(button, down);
            }
        }

        public void Key(ushort vk, bool down)
        {
            lock (gate)
            {
                if (down) keys.Add(vk); else keys.Remove(vk);
                injector.Key(vk, down);
            }
        }

        public void ReleaseAll()
        {
            lock (gate)
            {
                foreach (int b in buttons) injector.Button(b, false);
                foreach (ushort k in keys) injector.Key(k, false);
                buttons.Clear();
                keys.Clear();
            }
        }
    }
}
