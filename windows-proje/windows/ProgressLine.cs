using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace EFOS;

internal class ProgressLine : Control
{
	private float value;

	public float Value
	{
		get
		{
			return value;
		}
		set
		{
			this.value = Math.Clamp(value, 0f, 1f);
			Invalidate();
		}
	}

	public ProgressLine()
	{
		SetStyle(ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, value: true);
	}

	protected override void OnPaint(PaintEventArgs e)
	{
		Graphics graphics = e.Graphics;
		graphics.SmoothingMode = SmoothingMode.AntiAlias;
		graphics.Clear(Parent?.BackColor ?? UI.Bg);
		RectangleF r = new RectangleF(0f, 0f, Width - 1, Height - 1);
		using (GraphicsPath path = UI.RoundRect(r, r.Height / 2f))
		{
			using SolidBrush brush = new SolidBrush(UI.Border);
			graphics.FillPath(brush, path);
		}
		if (!(value > 0f))
		{
			return;
		}
		using GraphicsPath path2 = UI.RoundRect(new RectangleF(0f, 0f, Math.Max(r.Height, r.Width * value), r.Height), r.Height / 2f);
		using SolidBrush brush2 = new SolidBrush(UI.Accent);
		graphics.FillPath(brush2, path2);
	}
}
