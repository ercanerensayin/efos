using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace EFOS;

internal class GlyphButton : Control
{
	private bool _checked;

	private bool hover;

	private bool down;

	private readonly Font fTitle = new Font("Segoe UI Semibold", 10.5f);

	private readonly Font fPrimary = new Font("Segoe UI Semibold", 11f);

	private readonly Font fSub = new Font("Segoe UI", 8.5f);

	private readonly Font fGhost = new Font("Segoe UI", 9.5f);

	private readonly Font fIcon = new Font(UI.IconFont, 11f);

	private readonly Font fIconBig = new Font(UI.IconFont, 17f);

	private readonly Font fIconSmall = new Font(UI.IconFont, 7.5f);

	private bool pressed;

	public BtnKind Kind { get; set; }

	public string Glyph { get; set; } = "";

	public string SubText { get; set; } = "";

	public bool Danger { get; set; }

	public Color Surface { get; set; } = UI.Bg;

	public bool Checked
	{
		get
		{
			return _checked;
		}
		set
		{
			_checked = value;
			Invalidate();
		}
	}

	public GlyphButton()
	{
		SetStyle(ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.Selectable | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, value: true);
		SetStyle(ControlStyles.StandardClick | ControlStyles.StandardDoubleClick, value: false);
		Cursor = Cursors.Hand;
	}

	protected override void OnTextChanged(EventArgs e)
	{
		base.OnTextChanged(e);
		Invalidate();
	}

	protected override void WndProc(ref Message m)
	{
		if (m.Msg == 513 && Enabled)
		{
			pressed = true;
			if (CanFocus)
			{
				Focus();
			}
		}
		base.WndProc(ref m);
		if (m.Msg == 514)
		{
			int num = unchecked((int)(long)m.LParam);
			Point pt = new Point((short)(num & 0xFFFF), (short)((num >> 16) & 0xFFFF));
			bool num2 = pressed && Enabled && ClientRectangle.Contains(pt);
			pressed = false;
			if (num2)
			{
				OnClick(EventArgs.Empty);
			}
		}
	}

	protected override void OnEnabledChanged(EventArgs e)
	{
		base.OnEnabledChanged(e);
		Invalidate();
	}

	protected override void OnMouseEnter(EventArgs e)
	{
		hover = true;
		Invalidate();
		base.OnMouseEnter(e);
	}

	protected override void OnMouseLeave(EventArgs e)
	{
		hover = (down = false);
		Invalidate();
		base.OnMouseLeave(e);
	}

	protected override void OnMouseDown(MouseEventArgs e)
	{
		down = true;
		Invalidate();
		base.OnMouseDown(e);
	}

	protected override void OnMouseUp(MouseEventArgs e)
	{
		down = false;
		Invalidate();
		base.OnMouseUp(e);
	}

	protected override void OnGotFocus(EventArgs e)
	{
		Invalidate();
		base.OnGotFocus(e);
	}

	protected override void OnLostFocus(EventArgs e)
	{
		Invalidate();
		base.OnLostFocus(e);
	}

	protected override void OnKeyDown(KeyEventArgs e)
	{
		Keys keyCode = e.KeyCode;
		if ((keyCode == Keys.Return || keyCode == Keys.Space) ? true : false)
		{
			OnClick(EventArgs.Empty);
			e.Handled = true;
		}
		base.OnKeyDown(e);
	}

	protected override void OnPaint(PaintEventArgs e)
	{
		Graphics graphics = e.Graphics;
		graphics.SmoothingMode = SmoothingMode.AntiAlias;
		graphics.Clear(Surface);
		float num = (float)DeviceDpi / 96f;
		RectangleF r = new RectangleF(0.75f * num, 0.75f * num, (float)Width - 1.5f * num - 1f, (float)Height - 1.5f * num - 1f);
		Color color = Color.Empty;
		Color color2;
		Color foreColor;
		Color foreColor2;
		switch (Kind)
		{
		case BtnKind.Primary:
			if (Enabled)
			{
				if (Danger)
				{
					color2 = (hover ? UI.DangerHover : UI.Danger);
				}
				else if (down)
				{
					color2 = UI.AccentDown;
				}
				else
				{
					color2 = (hover ? UI.AccentHover : UI.Accent);
				}
			}
			else
			{
				color2 = UI.Disabled;
			}
			foreColor = (foreColor2 = Color.White);
			break;
		case BtnKind.Card:
			color2 = (Checked ? UI.AccentSoft : UI.Card);
			if (Checked)
			{
				color = UI.Accent;
			}
			else
			{
				color = (hover ? UI.BorderHover : UI.Border);
			}
			foreColor = (Enabled ? UI.Text : UI.Sub);
			foreColor2 = (Checked ? UI.Accent : UI.Sub);
			break;
		default:
			if (down)
			{
				color2 = Color.FromArgb(226, 229, 235);
			}
			else
			{
				color2 = (hover ? Color.FromArgb(235, 237, 241) : Surface);
			}
			foreColor = (foreColor2 = (Enabled ? Color.FromArgb(69, 76, 89) : Color.FromArgb(168, 174, 184)));
			break;
		}
		if (Focused && Kind != BtnKind.Card && ShowFocusCues)
		{
			color = UI.Accent;
		}
		using (GraphicsPath path = UI.RoundRect(r, 9f * num))
		{
			using (SolidBrush brush = new SolidBrush(color2))
			{
				graphics.FillPath(brush, path);
			}
			if (color != Color.Empty)
			{
				using Pen pen = new Pen(color, (Checked ? 1.8f : 1f) * num);
				graphics.DrawPath(pen, path);
			}
		}
		if (Kind == BtnKind.Card)
		{
			int num2 = (int)(16f * num);
			int num3 = (int)(58f * num);
			TextRenderer.DrawText(graphics, Glyph, fIconBig, new Rectangle(num2, 0, num3 - num2, Height), foreColor2, TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
			int num4 = Height / 2;
			int width = Width - num3 - (int)(44f * num);
			TextRenderer.DrawText(graphics, Text, fTitle, new Rectangle(num3, 0, width, num4 + (int)(1f * num)), foreColor, TextFormatFlags.Bottom | TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine | TextFormatFlags.NoPadding);
			TextRenderer.DrawText(graphics, SubText, fSub, new Rectangle(num3, num4 + (int)(3f * num), width, Height - num4), UI.Sub, TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine | TextFormatFlags.NoPadding);
			float num5 = 18f * num;
			RectangleF rectangleF = new RectangleF((float)Width - num5 - 14f * num, ((float)Height - num5) / 2f, num5, num5);
			if (Checked)
			{
				using (SolidBrush brush2 = new SolidBrush(UI.Accent))
				{
					graphics.FillEllipse(brush2, rectangleF);
					TextRenderer.DrawText(graphics, "\ue73e", fIconSmall, Rectangle.Round(rectangleF), Color.White, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
					return;
				}
			}
			using Pen pen2 = new Pen(UI.BorderHover, 1.4f * num);
			graphics.DrawEllipse(pen2, rectangleF);
			return;
		}
		Font font = ((Kind == BtnKind.Primary) ? fPrimary : fGhost);
		Font font2 = fIcon;
		Size size = (string.IsNullOrEmpty(Glyph) ? Size.Empty : TextRenderer.MeasureText(graphics, Glyph, font2, Size.Empty, TextFormatFlags.NoPadding));
		Size size2 = (string.IsNullOrEmpty(Text) ? Size.Empty : TextRenderer.MeasureText(graphics, Text, font, Size.Empty, TextFormatFlags.NoPadding));
		int num6 = ((!size.IsEmpty && !size2.IsEmpty) ? ((int)(8f * num)) : 0);
		int num7 = size.Width + num6 + size2.Width;
		int num8 = (Width - num7) / 2;
		if (!size.IsEmpty)
		{
			TextRenderer.DrawText(graphics, Glyph, font2, new Rectangle(num8, (int)(1f * num), size.Width + 2, Height), foreColor2, TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
		}
		if (!size2.IsEmpty)
		{
			TextRenderer.DrawText(graphics, Text, font, new Rectangle(num8 + size.Width + num6, 0, size2.Width + 4, Height), foreColor, TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
		}
	}
}
