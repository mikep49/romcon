using System;
using System.Drawing;
using Microsoft.Win32;

namespace RomCon
{
    /// <summary>Window colours: follow Windows, or force light, dark, or pure-black dark. Same choices as the Android app.</summary>
    enum ThemeMode { System, Light, Dark, Black }

    /// <summary>What a control sits on, so it can paint the right colour behind its rounded corners.</summary>
    enum Surface { Bg, Card, Inset, PrimaryC }

    enum Tone { Text, Muted, Primary, Ok, Warn, Error, OnPrimaryC }

    sealed class Palette
    {
        public bool IsDark;
        public Color Bg, Card, Inset, Line, Outline, Text, Muted, Primary, OnPrimary, PrimaryC, OnPrimaryC, Ok, Warn, Error;

        // The Android app's built-in light and dark schemes.
        public static readonly Palette Light = new Palette
        {
            Bg = Rgb(0xF8F9FF), Card = Rgb(0xECEEF8), Inset = Rgb(0xF8F9FF), Line = Rgb(0xDFE2ED), Outline = Rgb(0x74777F),
            Text = Rgb(0x1A1B21), Muted = Rgb(0x44474F), Primary = Rgb(0x2557D6), OnPrimary = Rgb(0xFFFFFF),
            PrimaryC = Rgb(0xDCE4FF), OnPrimaryC = Rgb(0x001849), Ok = Rgb(0x116932), Warn = Rgb(0x8A5A00), Error = Rgb(0xB3261E),
        };

        public static readonly Palette Dark = MakeDark(0x111318, 0x1D2027, 0x32353D);

        /// <summary>Dark with true-black backgrounds; cards stay a shade lighter.</summary>
        public static readonly Palette Black = MakeDark(0x000000, 0x131316, 0x242428);

        static Palette MakeDark(int bg, int card, int line)
        {
            return new Palette
            {
                IsDark = true,
                Bg = Rgb(bg), Card = Rgb(card), Inset = Rgb(bg), Line = Rgb(line), Outline = Rgb(0x8E9099),
                Text = Rgb(0xE2E2E9), Muted = Rgb(0xC4C6D0), Primary = Rgb(0xB4C5FF), OnPrimary = Rgb(0x002A77),
                PrimaryC = Rgb(0x0E3FA8), OnPrimaryC = Rgb(0xDCE4FF), Ok = Rgb(0x7ADB97), Warn = Rgb(0xF2C04B), Error = Rgb(0xFFB4AB),
            };
        }

        static Color Rgb(int rgb)
        {
            return Color.FromArgb(unchecked((int)0xFF000000) | rgb);
        }

        public Color Of(Surface s)
        {
            switch (s)
            {
                case Surface.Card: return Card;
                case Surface.Inset: return Inset;
                case Surface.PrimaryC: return PrimaryC;
                default: return Bg;
            }
        }

        public Color Of(Tone t)
        {
            switch (t)
            {
                case Tone.Muted: return Muted;
                case Tone.Primary: return Primary;
                case Tone.Ok: return Ok;
                case Tone.Warn: return Warn;
                case Tone.Error: return Error;
                case Tone.OnPrimaryC: return OnPrimaryC;
                default: return Text;
            }
        }
    }

    static class Theme
    {
        /// <summary>The colours every control paints with. Change it, then invalidate the window.</summary>
        public static Palette Current = Palette.Light;

        public static Palette For(ThemeMode mode)
        {
            switch (mode)
            {
                case ThemeMode.Light: return Palette.Light;
                case ThemeMode.Dark: return Palette.Dark;
                case ThemeMode.Black: return Palette.Black;
                default: return SystemIsDark ? Palette.Dark : Palette.Light;
            }
        }

        public static string Label(ThemeMode mode)
        {
            switch (mode)
            {
                case ThemeMode.Light: return "Light";
                case ThemeMode.Dark: return "Dark";
                case ThemeMode.Black: return "OLED black";
                default: return "System default";
            }
        }

        /// <summary>Whether Windows is set to dark mode for apps.</summary>
        public static bool SystemIsDark
        {
            get
            {
                try
                {
                    using (var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                    {
                        return key?.GetValue("AppsUseLightTheme") is int light && light == 0;
                    }
                }
                catch
                {
                    return false;
                }
            }
        }

        public static Color Blend(Color from, Color to, float amount)
        {
            return Color.FromArgb(
                (int)Math.Round(from.R + (to.R - from.R) * amount),
                (int)Math.Round(from.G + (to.G - from.G) * amount),
                (int)Math.Round(from.B + (to.B - from.B) * amount));
        }

        /// <summary>Colours the title bar to match. Windows 11 takes the exact colours; Windows 10 only switches between light and dark.</summary>
        public static void StyleWindow(IntPtr hwnd, Palette p)
        {
            try
            {
                int dark = p.IsDark ? 1 : 0;
                if (Native.DwmSetWindowAttribute(hwnd, Native.DWMWA_USE_IMMERSIVE_DARK_MODE, ref dark, sizeof(int)) != 0)
                {
                    Native.DwmSetWindowAttribute(hwnd, Native.DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, ref dark, sizeof(int));
                }
                int caption = ColorRef(p.Bg), text = ColorRef(p.Text);
                Native.DwmSetWindowAttribute(hwnd, Native.DWMWA_CAPTION_COLOR, ref caption, sizeof(int));
                Native.DwmSetWindowAttribute(hwnd, Native.DWMWA_TEXT_COLOR, ref text, sizeof(int));
                Native.SetWindowPos(hwnd, IntPtr.Zero, 0, 0, 0, 0,
                    Native.SWP_NOMOVE | Native.SWP_NOSIZE | Native.SWP_NOZORDER | Native.SWP_NOACTIVATE | Native.SWP_FRAMECHANGED);
            }
            catch (Exception ex) when (ex is DllNotFoundException || ex is EntryPointNotFoundException)
            {
            }
        }

        /// <summary>Gives a control's scroll bars the dark look in dark themes.</summary>
        public static void StyleScrollBars(IntPtr hwnd, Palette p)
        {
            try
            {
                Native.SetWindowTheme(hwnd, p.IsDark ? "DarkMode_Explorer" : "Explorer", null);
            }
            catch (Exception ex) when (ex is DllNotFoundException || ex is EntryPointNotFoundException)
            {
            }
        }

        static int ColorRef(Color c)
        {
            return c.R | (c.G << 8) | (c.B << 16);
        }
    }
}
