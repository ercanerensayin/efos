using System;
using System.Drawing;
using System.Windows.Forms;

namespace EFOS;

internal class LogoView : Control
{
	private Bitmap cache;

	public LogoView()
	{
		SetStyle(ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, value: true);
	}

	protected override void OnPaint(PaintEventArgs e)
	{
		e.Graphics.Clear(Parent?.BackColor ?? UI.Bg);
		int num = Math.Min(Width, Height);
		if (cache == null || cache.Width != num)
		{
			cache?.Dispose();
			cache = Logo.Draw(num);
		}
		e.Graphics.DrawImage(cache, 0, 0);
	}
}
