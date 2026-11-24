using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text.Json;

namespace EFOS;

/// <summary>ortak/efos.json dosyasını okur (Android sürümü de aynı dosyayı kullanır).</summary>
internal static class Ortak
{
	private static readonly JsonElement R;

	static Ortak()
	{
		using var s = typeof(Ortak).Assembly.GetManifestResourceStream("efos.json");
		R = JsonDocument.Parse(s).RootElement.Clone();
	}

	public static string Str(string key) => R.GetProperty(key).GetString();

	public static string T(string key) => R.GetProperty("metinler").GetProperty(key).GetString();

	public static string TOr(string key, string fallback) =>
		R.GetProperty("metinler").TryGetProperty(key, out var v) ? v.GetString() : fallback;

	public static Color Renk(string key) => ColorTranslator.FromHtml(R.GetProperty("renkler").GetProperty(key).GetString());

	public static string Cikti => R.GetProperty("ytdlp").GetProperty("cikti").GetString();

	public static List<string> Args(string key) =>
		R.GetProperty("ytdlp").GetProperty(key).EnumerateArray().Select(e => e.GetString()).ToList();

	public static (string Ad, string Format)[] Kaliteler =>
		R.GetProperty("ytdlp").GetProperty("kaliteler").EnumerateArray()
			.Select(e => (e.GetProperty("ad").GetString(), e.GetProperty("format").GetString())).ToArray();

	public static (string Ad, List<string> Args)[] Bicimler =>
		R.GetProperty("ytdlp").GetProperty("bicimler").EnumerateArray()
			.Select(e => (e.GetProperty("ad").GetString(), e.GetProperty("args").EnumerateArray().Select(a => a.GetString()).ToList())).ToArray();

	/// <summary>yt-dlp hata metnini anlaşılır Türkçe mesaja çevirir.</summary>
	public static string Friendly(string err)
	{
		if (string.IsNullOrEmpty(err)) return Str("bilinmeyenHata");
		string low = err.ToLowerInvariant();
		foreach (var rule in R.GetProperty("hatalar").EnumerateArray())
		{
			if (rule.GetProperty("ara").EnumerateArray().Any(a => low.Contains(a.GetString().ToLowerInvariant())))
				return rule.GetProperty("mesaj").GetString();
		}
		return "Hata: " + err;
	}
}
