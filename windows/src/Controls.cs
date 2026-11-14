using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace RomCon
{
    /// <summary>Shared fonts, icons and drawing helpers. Sizes are written in 96-DPI pixels and scaled to the screen's DPI.</summary>
    static class Ui
    {
        public static readonly float Scale = ReadScale();

        public static readonly Font Body = new Font("Segoe UI", 10.5f);
        public static readonly Font Small = new Font("Segoe UI", 9.5f);
        public static readonly Font Strong = new Font("Segoe UI Semibold", 10.5f);
        public static readonly Font Title = new Font("Segoe UI Semibold", 13.5f);
        public static readonly Font Pin = new Font("Segoe UI", 25.5f, FontStyle.Bold);
        public static readonly Font Mono = MonoFont(9.75f);
        public static readonly Font MonoSmall = MonoFont(9f);
        public static readonly Font Icons = new Font("Segoe MDL2 Assets", 10.5f);
        public static readonly Font SmallIcons = new Font("Segoe MDL2 Assets", 8.25f);

        // Segoe MDL2 Assets glyphs; the font ships with Windows 10 and 11.
        public const string IconPause = "", IconPlay = "", IconCopy = "", IconCheck = "", IconPhone = "";

        public const TextFormatFlags OneLine = TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis;
        public const TextFormatFlags Centered = OneLine | TextFormatFlags.VerticalCenter;
        public const TextFormatFlags Wrapped = TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix | TextFormatFlags.WordBreak;

        public static int S(int px)
        {
            return (int)Math.Round(px * Scale);
        }

        public static int TextWidth(string text, Font font)
        {
            return string.IsNullOrEmpty(text) ? 0 : TextRenderer.MeasureText(text, font, Size.Empty, TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix | TextFormatFlags.SingleLine).Width;
        }

        public static int TextHeight(string text, Font font, int width)
        {
            return TextRenderer.MeasureText(text, font, new Size(Math.Max(1, width), int.MaxValue), Wrapped).Height;
        }

        public static void FillRound(Graphics g, Color color, RectangleF r, float radius)
        {
            using (var path = RoundRect(r, radius))
            using (var brush = new SolidBrush(color))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.FillPath(brush, path);
            }
        }

        public static void StrokeRound(Graphics g, Color color, RectangleF r, float radius, float width)
        {
            r.Inflate(-width / 2f, -width / 2f);
            using (var path = RoundRect(r, Math.Max(0, radius - width / 2f)))
            using (var pen = new Pen(color, width))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.DrawPath(pen, path);
            }
        }

        public static void FillCircle(Graphics g, Color color, RectangleF r)
        {
            using (var brush = new SolidBrush(color))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.FillEllipse(brush, r);
            }
        }

        static GraphicsPath RoundRect(RectangleF r, float radius)
        {
            var path = new GraphicsPath();
            float d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
            if (d <= 0)
            {
                path.AddRectangle(r);
                return path;
            }
            path.AddArc(r.X, r.Y, d, d, 180, 90);
            path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        static float ReadScale()
        {
            using (var g = Graphics.FromHwnd(IntPtr.Zero)) return g.DpiX / 96f;
        }

        static Font MonoFont(float size)
        {
            var font = new Font("Cascadia Mono", size);
            if (font.Name == "Cascadia Mono") return font;
            font.Dispose();
            return new Font("Consolas", size);
        }
    }

    /// <summary>A page that paints rounded cards behind its child controls.</summary>
    sealed class CardPanel : Panel
    {
        public struct Shape
        {
            public Rectangle Bounds;
            public Surface Fill;
            public int Radius;
        }

        public readonly List<Shape> Shapes = new List<Shape>();

        public CardPanel()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }

        public void Add(Rectangle bounds, Surface fill, int radius)
        {
            Shapes.Add(new Shape { Bounds = bounds, Fill = fill, Radius = radius });
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            e.Graphics.Clear(p.Bg);
            foreach (var s in Shapes) Ui.FillRound(e.Graphics, p.Of(s.Fill), s.Bounds, s.Radius);
            base.OnPaint(e);
        }
    }

    /// <summary>The strip that holds the tabs, with a hairline under it.</summary>
    sealed class BarPanel : Panel
    {
        public BarPanel()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            e.Graphics.Clear(p.Bg);
            using (var brush = new SolidBrush(p.Line)) e.Graphics.FillRectangle(brush, 0, Height - Ui.S(1), Width, Ui.S(1));
        }
    }

    /// <summary>Static text in a theme colour. Wraps when asked, otherwise ends with an ellipsis.</summary>
    sealed class TextBlock : Control
    {
        public Surface Surface = Surface.Card;
        public Tone Tone;
        public bool Wrap;
        public bool AlignRight;

        public TextBlock(string text, Font font, Tone tone = Tone.Text, bool wrap = false)
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            SetStyle(ControlStyles.Selectable, false);
            TabStop = false;
            AccessibleRole = AccessibleRole.StaticText;
            Font = font;
            Tone = tone;
            Wrap = wrap;
            Text = text;
        }

        public int LineHeight
        {
            get { return Font.Height; }
        }

        public int NaturalWidth
        {
            get { return Ui.TextWidth(Text, Font); }
        }

        public int HeightFor(int width)
        {
            return Wrap ? Ui.TextHeight(Text, Font, width) : Font.Height;
        }

        protected override void OnTextChanged(EventArgs e)
        {
            base.OnTextChanged(e);
            Invalidate();
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            Color back = p.Of(Surface);
            e.Graphics.Clear(back);
            var flags = Wrap ? Ui.Wrapped : Ui.OneLine;
            if (AlignRight) flags |= TextFormatFlags.Right;
            TextRenderer.DrawText(e.Graphics, Text, Font, ClientRectangle, p.Of(Tone), back, flags);
        }
    }

    enum ButtonKind { Primary, Outline, Ghost }

    /// <summary>A flat rounded button. It is still a real Button, so the keyboard and screen readers treat it as one.</summary>
    sealed class FlatButton : Button
    {
        public ButtonKind Kind = ButtonKind.Outline;
        public Surface Surface = Surface.Card;
        public Tone? GlyphTone;
        string glyph;
        bool hover, down;

        public FlatButton()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            Font = Ui.Small;
            Cursor = Cursors.Hand;
            UseMnemonic = false;
        }

        /// <summary>An icon drawn before the text, or on its own when there is no text.</summary>
        public string Glyph
        {
            get { return glyph; }
            set { glyph = value; Invalidate(); }
        }

        public int FitWidth()
        {
            return Ui.S(28) + Ui.TextWidth(Text, Font) + (glyph == null ? 0 : Ui.TextWidth(glyph, Ui.SmallIcons) + Ui.S(8));
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            var g = e.Graphics;
            Color surface = p.Of(Surface);
            Color onSurface = Surface == Surface.PrimaryC ? p.OnPrimaryC : p.Text;
            float press = down ? 0.16f : hover ? 0.08f : 0f;
            var box = new RectangleF(0, 0, Width, Height);
            float radius = Ui.S(6);
            Color fill, fore;

            g.Clear(surface);
            if (Kind == ButtonKind.Primary)
            {
                fill = Theme.Blend(p.Primary, p.OnPrimary, press);
                fore = p.OnPrimary;
                Ui.FillRound(g, fill, box, radius);
            }
            else
            {
                fill = Theme.Blend(surface, onSurface, press);
                fore = Kind == ButtonKind.Ghost ? (Surface == Surface.PrimaryC ? p.OnPrimaryC : p.Muted) : onSurface;
                if (press > 0) Ui.FillRound(g, fill, box, radius);
                if (Kind == ButtonKind.Outline) Ui.StrokeRound(g, Surface == Surface.PrimaryC ? p.OnPrimaryC : p.Outline, box, radius, Ui.S(1));
            }
            if (!Enabled) fore = Theme.Blend(fore, fill, 0.5f);

            bool hasText = !string.IsNullOrEmpty(Text);
            int glyphWidth = glyph == null ? 0 : Ui.TextWidth(glyph, Ui.SmallIcons);
            int textWidth = hasText ? Ui.TextWidth(Text, Font) : 0;
            int gap = glyph != null && hasText ? Ui.S(8) : 0;
            int x = (Width - glyphWidth - gap - textWidth) / 2;
            if (glyph != null)
            {
                Color glyphColor = GlyphTone.HasValue ? p.Of(GlyphTone.Value) : fore;
                TextRenderer.DrawText(g, glyph, Ui.SmallIcons, new Rectangle(x, 0, glyphWidth, Height), glyphColor, Ui.Centered);
            }
            if (hasText) TextRenderer.DrawText(g, Text, Font, new Rectangle(x + glyphWidth + gap, 0, textWidth + Ui.S(2), Height), fore, Ui.Centered);

            if (Focused && ShowFocusCues)
            {
                box.Inflate(-Ui.S(2), -Ui.S(2));
                Ui.StrokeRound(g, fore, box, radius - Ui.S(2), Ui.S(1));
            }
        }
    }

    /// <summary>A setting with a switch on the right. It is a CheckBox underneath.</summary>
    sealed class ToggleRow : CheckBox
    {
        public ToggleRow(string text)
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            AutoSize = false;
            Font = Ui.Body;
            Cursor = Cursors.Hand;
            UseMnemonic = false;
            Text = text;
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            var g = e.Graphics;
            g.Clear(p.Card);

            int trackW = Ui.S(38), trackH = Ui.S(22), thumb = Ui.S(14), inset = Ui.S(4);
            var track = new RectangleF(Width - trackW - Ui.S(3), (Height - trackH) / 2f, trackW, trackH);
            TextRenderer.DrawText(g, Text, Font, new Rectangle(0, 0, (int)track.Left - Ui.S(12), Height), p.Text, Ui.Centered);

            float thumbY = track.Top + (trackH - thumb) / 2f;
            if (Checked)
            {
                Ui.FillRound(g, p.Primary, track, trackH / 2f);
                Ui.FillCircle(g, p.OnPrimary, new RectangleF(track.Right - inset - thumb, thumbY, thumb, thumb));
            }
            else
            {
                Ui.StrokeRound(g, p.Outline, track, trackH / 2f, Ui.S(3) / 2f);
                Ui.FillCircle(g, p.Outline, new RectangleF(track.Left + inset, thumbY, thumb, thumb));
            }

            if (Focused && ShowFocusCues)
            {
                track.Inflate(Ui.S(3), Ui.S(3));
                Ui.StrokeRound(g, p.Text, track, track.Height / 2f, Ui.S(1));
            }
        }
    }

    /// <summary>One of the two page tabs. A RadioButton underneath, so the arrow keys switch pages.</summary>
    sealed class TabButton : RadioButton
    {
        bool hover;

        public TabButton(string text)
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            AutoSize = false;
            Font = Ui.Strong;
            Cursor = Cursors.Hand;
            UseMnemonic = false;
            Text = text;
        }

        public int FitWidth()
        {
            return Ui.TextWidth(Text, Font) + Ui.S(8);
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; Invalidate(); base.OnMouseLeave(e); }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            var g = e.Graphics;
            g.Clear(p.Bg);
            Color fore = Checked ? p.Primary : hover ? p.Text : p.Muted;
            TextRenderer.DrawText(g, Text, Font, ClientRectangle, fore, Ui.Centered | TextFormatFlags.HorizontalCenter);
            if (Checked)
            {
                using (var brush = new SolidBrush(p.Primary)) g.FillRectangle(brush, 0, Height - Ui.S(2), Width, Ui.S(2));
            }
            if (Focused && ShowFocusCues)
            {
                var r = new RectangleF(0, Ui.S(8), Width, Height - Ui.S(16));
                Ui.StrokeRound(g, p.Text, r, Ui.S(4), Ui.S(1));
            }
        }
    }

    /// <summary>A theme choice with a small preview of its colours. A RadioButton underneath.</summary>
    sealed class ThemeTile : RadioButton
    {
        public readonly ThemeMode Mode;

        public ThemeTile(ThemeMode mode)
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            Mode = mode;
            AutoSize = false;
            Font = Ui.Small;
            Cursor = Cursors.Hand;
            UseMnemonic = false;
            Text = Theme.Label(mode);
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            var g = e.Graphics;
            g.Clear(p.Card);
            var box = new RectangleF(0, 0, Width, Height);
            float radius = Ui.S(8);
            Ui.FillRound(g, p.Inset, box, radius);
            Ui.StrokeRound(g, Checked ? p.Primary : p.Line, box, radius, Ui.S(2));

            // Preview: the theme's background with a card and an accent dot on it.
            Palette look = Mode == ThemeMode.Dark ? Palette.Dark : Mode == ThemeMode.Black ? Palette.Black : Palette.Light;
            var swatch = new RectangleF(Ui.S(10), (Height - Ui.S(26)) / 2f, Ui.S(34), Ui.S(26));
            Ui.FillRound(g, look.Bg, swatch, Ui.S(4));
            if (Mode == ThemeMode.System)
            {
                var state = g.Save();
                g.SetClip(new RectangleF(swatch.Left + swatch.Width / 2f, swatch.Top, swatch.Width / 2f, swatch.Height));
                Ui.FillRound(g, Palette.Dark.Bg, swatch, Ui.S(4));
                g.Restore(state);
            }
            Ui.StrokeRound(g, p.Outline, swatch, Ui.S(4), Ui.S(1));
            Color bar = Mode == ThemeMode.System ? Palette.Dark.Outline : look.Line;
            Ui.FillRound(g, bar, new RectangleF(swatch.Left + Ui.S(4), swatch.Bottom - Ui.S(12), Ui.S(15), Ui.S(8)), Ui.S(2));
            Ui.FillCircle(g, look.Primary, new RectangleF(swatch.Right - Ui.S(10), swatch.Bottom - Ui.S(11), Ui.S(6), Ui.S(6)));

            int checkWidth = Ui.TextWidth(Ui.IconCheck, Ui.SmallIcons);
            int textLeft = (int)swatch.Right + Ui.S(9);
            int textRight = Width - Ui.S(8) - checkWidth - Ui.S(6);
            TextRenderer.DrawText(g, Text, Font, new Rectangle(textLeft, 0, textRight - textLeft, Height), p.Text, Ui.Centered);
            if (Checked)
            {
                TextRenderer.DrawText(g, Ui.IconCheck, Ui.SmallIcons, new Rectangle(Width - Ui.S(8) - checkWidth, 0, checkWidth, Height), p.Primary, Ui.Centered);
            }

            if (Focused && ShowFocusCues)
            {
                box.Inflate(-Ui.S(4), -Ui.S(4));
                Ui.StrokeRound(g, p.Text, box, radius - Ui.S(4), Ui.S(1));
            }
        }
    }

    /// <summary>A paired phone: its name, when it was last here, and a button to remove it.</summary>
    sealed class PhoneRow : Control
    {
        public readonly FlatButton Remove = new FlatButton { Kind = ButtonKind.Ghost, Text = "Remove" };
        public string DeviceId { get; private set; }
        public string DeviceName { get; private set; }
        string status = "";
        bool live;

        public PhoneRow()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.ContainerControl, true);
            SetStyle(ControlStyles.Selectable, false);
            Controls.Add(Remove);
        }

        public void Set(string id, string name, string statusText, bool isLive)
        {
            DeviceId = id;
            DeviceName = name;
            status = statusText;
            live = isLive;
            AccessibleName = name + ", " + statusText;
            Remove.AccessibleName = "Remove " + name;
            Invalidate();
        }

        protected override void OnLayout(LayoutEventArgs e)
        {
            base.OnLayout(e);
            int w = Remove.FitWidth(), h = Ui.S(30);
            Remove.SetBounds(Width - w, (Height - Ui.S(1) - h) / 2, w, h);
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            var g = e.Graphics;
            g.Clear(p.Card);
            int body = Height - Ui.S(1);
            TextRenderer.DrawText(g, Ui.IconPhone, Ui.Icons, new Rectangle(0, 0, Ui.S(20), body), p.Muted, Ui.Centered);

            int x = Ui.S(30), w = Remove.Left - Ui.S(8) - x;
            int nameHeight = Ui.Strong.Height, statusHeight = Ui.Small.Height;
            int y = (body - nameHeight - statusHeight) / 2;
            TextRenderer.DrawText(g, DeviceName ?? "", Ui.Strong, new Rectangle(x, y, w, nameHeight), p.Text, Ui.OneLine);
            TextRenderer.DrawText(g, status, Ui.Small, new Rectangle(x, y + nameHeight, w, statusHeight), live ? p.Ok : p.Muted, Ui.OneLine);
            using (var brush = new SolidBrush(p.Line)) g.FillRectangle(brush, 0, body, Width, Ui.S(1));
        }
    }

    /// <summary>A labelled value the phone app asks for, with a button that copies it.</summary>
    sealed class AddressRow : Control
    {
        public readonly FlatButton Copy = new FlatButton { Kind = ButtonKind.Ghost, Surface = Surface.Inset, Glyph = Ui.IconCopy };
        string label = "";
        bool copied;

        public AddressRow()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.ContainerControl, true);
            SetStyle(ControlStyles.Selectable, false);
            Controls.Add(Copy);
        }

        public string Value { get; private set; }

        public void Set(string labelText, string value, bool canCopy)
        {
            label = labelText;
            Value = value;
            Copy.Visible = canCopy;
            AccessibleName = labelText + ": " + value;
            Copy.AccessibleName = "Copy " + labelText;
            Invalidate();
        }

        /// <summary>Shows a tick in place of the copy icon for a moment after copying.</summary>
        public bool Copied
        {
            get { return copied; }
            set
            {
                copied = value;
                Copy.Glyph = value ? Ui.IconCheck : Ui.IconCopy;
                Copy.GlyphTone = value ? Tone.Ok : (Tone?)null;
            }
        }

        protected override void OnLayout(LayoutEventArgs e)
        {
            base.OnLayout(e);
            int size = Ui.S(28);
            Copy.SetBounds(Width - size - Ui.S(3), (Height - size) / 2, size, size);
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var p = Theme.Current;
            var g = e.Graphics;
            g.Clear(p.Card);
            Ui.FillRound(g, p.Inset, new RectangleF(0, 0, Width, Height), Ui.S(6));
            int labelWidth = Ui.S(76), x = Ui.S(12);
            TextRenderer.DrawText(g, label, Ui.Small, new Rectangle(x, 0, labelWidth, Height), p.Muted, Ui.Centered);
            int valueLeft = x + labelWidth + Ui.S(8);
            TextRenderer.DrawText(g, Value ?? "", Ui.Mono, new Rectangle(valueLeft, 0, Copy.Left - Ui.S(4) - valueLeft, Height), p.Text, Ui.Centered);
        }
    }
}
