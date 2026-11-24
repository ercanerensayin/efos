using System;
using System.Drawing;
using System.Drawing.Drawing2D;

namespace EFOS;

/// <summary>Renkler ortak/efos.json'dan gelir; simgeler Windows'a özgüdür.</summary>
internal static class UI
{
	public static readonly Color Bg = Ortak.Renk("arkaPlan");
	public static readonly Color Card = Ortak.Renk("kart");
	public static readonly Color Border = Ortak.Renk("kenar");
	public static readonly Color BorderHover = Ortak.Renk("kenarUzerinde");
	public static readonly Color Text = Ortak.Renk("yazi");
	public static readonly Color Sub = Ortak.Renk("soluk");
	public static readonly Color Accent = Ortak.Renk("vurgu");
	public static readonly Color AccentHover = Ortak.Renk("vurguUzerinde");
	public static readonly Color AccentDown = Ortak.Renk("vurguBasili");
	public static readonly Color AccentSoft = Ortak.Renk("vurguYumusak");
	public static readonly Color Disabled = Ortak.Renk("pasif");
	public static readonly Color Danger = Ortak.Renk("tehlike");
	public static readonly Color DangerHover = Ortak.Renk("tehlikeUzerinde");
	public static readonly Color Green = Ortak.Renk("yesil");
	public static readonly Color Red = Ortak.Renk("kirmizi");
	public static readonly Color LogoTop = Ortak.Renk("logoUst");
	public static readonly Color LogoBottom = Ortak.Renk("logoAlt");

	public const string GVideo = "";
	public const string GMusic = "";
	public const string GDownload = "";
	public const string GCancel = "";
	public const string GPaste = "";
	public const string GLink = "";
	public const string GFolder = "";
	public const string GRefresh = "";
	public const string GSettings = "";
	public const string GCheck = "";
	public const string GDone = "";
	public const string GError = "";
	public const string GInfo = "";
	public const string GSync = "";

	public static readonly string IconFont = FontExists("Segoe Fluent Icons") ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";

	private static bool FontExists(string name)
	{
		using Font font = new Font(name, 10f);
		return string.Equals(font.Name, name, StringComparison.OrdinalIgnoreCase);
	}

	public static GraphicsPath RoundRect(RectangleF r, float rad)
	{
		GraphicsPath p = new GraphicsPath();
		float d = Math.Min(rad * 2f, Math.Min(r.Width, r.Height));
		p.AddArc(r.X, r.Y, d, d, 180f, 90f);
		p.AddArc(r.Right - d, r.Y, d, d, 270f, 90f);
		p.AddArc(r.Right - d, r.Bottom - d, d, d, 0f, 90f);
		p.AddArc(r.X, r.Bottom - d, d, d, 90f, 90f);
		p.CloseFigure();
		return p;
	}
}
