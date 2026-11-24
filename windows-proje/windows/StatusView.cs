using System;
using System.Drawing;
using System.Windows.Forms;

namespace EFOS;

internal class StatusView : Control
{
	private string glyph = "";

	private Color color = UI.Sub;

	private readonly Font fIcon = new Font(UI.IconFont, 10f);

	public StatusView()
	{
		SetStyle(ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, value: true);
	}

	public void Set(string g, string text, Color c)
	{
		glyph = g;
		color = c;
		Text = text;
		Invalidate();
	}

	protected override void OnTextChanged(EventArgs e)
	{
		base.OnTextChanged(e);
		Invalidate();
	}

	protected override void OnPaint(PaintEventArgs e)
	{
		Graphics graphics = e.Graphics;
		graphics.Clear(Parent?.BackColor ?? UI.Bg);
		float num = (float)DeviceDpi / 96f;
		int num2 = 0;
		if (!string.IsNullOrEmpty(glyph))
		{
			TextRenderer.DrawText(graphics, glyph, fIcon, new Rectangle(0, (int)(1f * num), (int)(20f * num), Height), color, TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
			num2 = (int)(24f * num);
		}
		TextRenderer.DrawText(graphics, Text, Font, new Rectangle(num2, 0, Width - num2, Height), color, TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
	}
}
