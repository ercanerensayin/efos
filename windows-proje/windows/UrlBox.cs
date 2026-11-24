using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace EFOS;

internal class UrlBox : Control
{
	public readonly TextBox Box;

	public readonly GlyphButton PasteButton;

	private readonly Font fIcon = new Font(UI.IconFont, 11f);

	public UrlBox()
	{
		SetStyle(ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, value: true);
		Box = new TextBox
		{
			BorderStyle = BorderStyle.None,
			BackColor = UI.Card,
			ForeColor = UI.Text,
			Font = new Font("Segoe UI", 10.5f),
			PlaceholderText = Ortak.T("yerTutucu")
		};
		PasteButton = new GlyphButton
		{
			Kind = BtnKind.Ghost,
			Glyph = "\ue77f",
			Text = Ortak.T("yapistir"),
			Surface = UI.Card,
			Size = new Size(96, 32)
		};
		Box.GotFocus += (_, _) =>
		{
			Invalidate();
		};
		Box.LostFocus += (_, _) =>
		{
			Invalidate();
		};
		Controls.Add(Box);
		Controls.Add(PasteButton);
		Cursor = Cursors.IBeam;
		Click += (_, _) =>
		{
			Box.Focus();
		};
	}

	protected override void OnLayout(LayoutEventArgs e)
	{
		base.OnLayout(e);
		float num = (float)DeviceDpi / 96f;
		int num2 = (int)(7f * num);
		PasteButton.SetBounds(Width - PasteButton.Width - num2, (Height - PasteButton.Height) / 2, PasteButton.Width, PasteButton.Height);
		int num3 = (int)(42f * num);
		Box.SetBounds(num3, (Height - Box.Height) / 2, PasteButton.Left - num3 - (int)(8f * num), Box.Height);
	}

	protected override void OnPaint(PaintEventArgs e)
	{
		Graphics graphics = e.Graphics;
		graphics.SmoothingMode = SmoothingMode.AntiAlias;
		graphics.Clear(Parent?.BackColor ?? UI.Bg);
		float num = (float)DeviceDpi / 96f;
		RectangleF r = new RectangleF(0.75f * num, 0.75f * num, (float)Width - 1.5f * num - 1f, (float)Height - 1.5f * num - 1f);
		bool focused = Box.Focused;
		using (GraphicsPath path = UI.RoundRect(r, 10f * num))
		{
			using (SolidBrush brush = new SolidBrush(UI.Card))
			{
				graphics.FillPath(brush, path);
			}
			using Pen pen = new Pen(focused ? UI.Accent : UI.Border, (focused ? 1.8f : 1f) * num);
			graphics.DrawPath(pen, path);
		}
		TextRenderer.DrawText(graphics, "\ue71b", fIcon, new Rectangle((int)(15f * num), 0, (int)(24f * num), Height), focused ? UI.Accent : UI.Sub, TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
	}
}
