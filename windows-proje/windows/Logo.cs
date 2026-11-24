using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;

namespace EFOS;

internal static class Logo
{
	public static Bitmap Draw(int s)
	{
		Bitmap bitmap = new Bitmap(s, s, PixelFormat.Format32bppArgb);
		using Graphics graphics = Graphics.FromImage(bitmap);
		graphics.SmoothingMode = SmoothingMode.AntiAlias;
		graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
		float num = (float)s * 0.03f;
		RectangleF rectangleF = new RectangleF(num, num, (float)s - 2f * num, (float)s - 2f * num);
		using (GraphicsPath path = UI.RoundRect(rectangleF, (float)s * 0.24f))
		{
			using LinearGradientBrush brush = new LinearGradientBrush(rectangleF, UI.LogoTop, UI.LogoBottom, 90f);
			graphics.FillPath(brush, path);
		}
		float width = Math.Max(1.6f, (float)s * 0.1f);
		using Pen pen = new Pen(Color.White, width)
		{
			StartCap = LineCap.Round,
			EndCap = LineCap.Round,
			LineJoin = LineJoin.Round
		};
		graphics.DrawLine(pen, (float)s * 0.5f, (float)s * 0.21f, (float)s * 0.5f, (float)s * 0.6f);
		graphics.DrawLines(pen, new PointF[3]
		{
			new PointF((float)s * 0.31f, (float)s * 0.43f),
			new PointF((float)s * 0.5f, (float)s * 0.62f),
			new PointF((float)s * 0.69f, (float)s * 0.43f)
		});
		graphics.DrawLine(pen, (float)s * 0.27f, (float)s * 0.79f, (float)s * 0.73f, (float)s * 0.79f);
		return bitmap;
	}

	public static void SaveIco(string path)
	{
		int[] array = new int[9] { 16, 20, 24, 32, 40, 48, 64, 128, 256 };
		List<byte[]> list = array.Select((int sz) =>
		{
			using Bitmap bitmap = Draw(sz);
			using MemoryStream memoryStream = new MemoryStream();
			bitmap.Save(memoryStream, ImageFormat.Png);
			return memoryStream.ToArray();
		}).ToList();
		using FileStream output = File.Create(path);
		using BinaryWriter binaryWriter = new BinaryWriter(output);
		binaryWriter.Write((short)0);
		binaryWriter.Write((short)1);
		binaryWriter.Write((short)array.Length);
		int num = 6 + 16 * array.Length;
		for (int num2 = 0; num2 < array.Length; num2++)
		{
			byte value = (byte)((array[num2] < 256) ? ((uint)array[num2]) : 0u);
			binaryWriter.Write(value);
			binaryWriter.Write(value);
			binaryWriter.Write((byte)0);
			binaryWriter.Write((byte)0);
			binaryWriter.Write((short)1);
			binaryWriter.Write((short)32);
			binaryWriter.Write(list[num2].Length);
			binaryWriter.Write(num);
			num += list[num2].Length;
		}
		foreach (byte[] item in list)
		{
			binaryWriter.Write(item);
		}
	}
}
