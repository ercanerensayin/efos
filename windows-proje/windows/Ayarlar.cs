using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;

namespace EFOS;

/// <summary>Küçük ayar dosyası: %APPDATA%\EFOS\ayarlar.txt (anahtar=değer).</summary>
internal static class Ayarlar
{
	private static readonly string FilePath = Path.Combine(
		Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EFOS", "ayarlar.txt");

	private static readonly Dictionary<string, string> Values = Load();

	public static string Klasor
	{
		get => Values.TryGetValue("klasor", out var v) && v.Length > 0 ? v : DefaultFolder;
		set => Set("klasor", value == DefaultFolder ? "" : value);
	}

	public static bool OzelKlasor => Klasor != DefaultFolder;

	public static int Kalite
	{
		get => Values.TryGetValue("kalite", out var v) && int.TryParse(v, out int i) ? i : 0;
		set => Set("kalite", value.ToString());
	}

	public static int Bicim
	{
		get => Values.TryGetValue("bicim", out var v) && int.TryParse(v, out int i) ? i : 0;
		set => Set("bicim", value.ToString());
	}

	public static bool Liste
	{
		get => Values.TryGetValue("liste", out var v) && v == "1";
		set => Set("liste", value ? "1" : "0");
	}

	/// <summary>yt-dlp --cookies-from-browser tarayıcı adı (boş = kapalı).</summary>
	public static string CerezTarayici
	{
		get => Values.TryGetValue("cerez", out var v) ? v : "";
		set => Set("cerez", value ?? "");
	}

	/// <summary>Windows'un gerçek İndirilenler klasörü (taşınmış olsa bile) + Videolar.</summary>
	public static string DefaultFolder { get; } = Path.Combine(DownloadsFolder(), Ortak.Str("klasor"));

	private static string DownloadsFolder()
	{
		try
		{
			var id = new Guid("374DE290-123F-4565-9164-39C4925E467B"); // FOLDERID_Downloads
			if (SHGetKnownFolderPath(id, 0, IntPtr.Zero, out IntPtr p) == 0)
			{
				string s = Marshal.PtrToStringUni(p);
				Marshal.FreeCoTaskMem(p);
				if (!string.IsNullOrEmpty(s)) return s;
			}
		}
		catch { }
		return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
	}

	[DllImport("shell32.dll")]
	private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint flags, IntPtr token, out IntPtr path);

	private static Dictionary<string, string> Load()
	{
		var d = new Dictionary<string, string>();
		try
		{
			foreach (string line in File.ReadAllLines(FilePath))
			{
				int i = line.IndexOf('=');
				if (i > 0) d[line[..i]] = line[(i + 1)..];
			}
		}
		catch { }
		return d;
	}

	private static void Set(string key, string value)
	{
		Values[key] = value;
		try
		{
			Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
			var lines = new List<string>();
			foreach (var kv in Values) lines.Add(kv.Key + "=" + kv.Value);
			File.WriteAllLines(FilePath, lines);
		}
		catch { }
	}
}
