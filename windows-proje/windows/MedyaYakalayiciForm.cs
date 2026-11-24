using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace EFOS;

internal sealed record YakalananMedya(string StreamUrl, string Referer, string UserAgent, string Cookie);

/// <summary>
/// yt-dlp'nin doğrudan desteklemediği gömülü oynatıcı (iframe / JS) sayfalarından
/// WebView2 ağ istekleri üzerinden .m3u8, .mpd veya .mp4 akışını yakalar.
/// </summary>
internal sealed class MedyaYakalayiciForm : Form
{
	private static readonly string[] EngelliParcalar =
	{
		"doubleclick", "googlesyndication", "googleads", "adservice",
		"analytics", "favicon", "thumbnail", "thumb", "poster", "preview"
	};

	private readonly string pageUrl;
	private readonly WebView2 webView;
	private readonly Label lblInfo;
	private int yakalandi;

	public YakalananMedya Sonuc { get; private set; }

	public MedyaYakalayiciForm(string url)
	{
		pageUrl = url;
		Text = Ortak.Str("ad") + " — " + Ortak.TOr("tarayicidaAra", "Tarayıcıda ara");
		BackColor = UI.Bg;
		Font = new Font("Segoe UI", 9.5f);
		StartPosition = FormStartPosition.CenterParent;
		Size = new Size(980, 680);
		MinimumSize = new Size(640, 480);

		var topBar = new Panel
		{
			Dock = DockStyle.Top,
			Height = 46,
			BackColor = UI.Card,
			Padding = new Padding(14, 8, 12, 8)
		};

		var btnClose = new Button
		{
			Text = "Kapat",
			Dock = DockStyle.Right,
			Width = 84,
			FlatStyle = FlatStyle.Flat,
			BackColor = UI.Bg,
			ForeColor = UI.Text,
			Cursor = Cursors.Hand
		};
		btnClose.FlatAppearance.BorderColor = UI.Border;
		btnClose.Click += (_, _) => { DialogResult = DialogResult.Cancel; Close(); };

		lblInfo = new Label
		{
			Text = Ortak.TOr("videoAraniyor", "Sayfada video aranıyor… Gerekirse oynat tuşuna basın"),
			Dock = DockStyle.Fill,
			TextAlign = ContentAlignment.MiddleLeft,
			ForeColor = UI.Text
		};

		topBar.Controls.Add(lblInfo);
		topBar.Controls.Add(btnClose);

		var divider = new Panel { Dock = DockStyle.Top, Height = 1, BackColor = UI.Border };
		webView = new WebView2 { Dock = DockStyle.Fill };

		Controls.Add(webView);
		Controls.Add(divider);
		Controls.Add(topBar);

		Shown += async (_, _) => await InitWebView();
	}

	private async Task InitWebView()
	{
		try
		{
			string userData = Path.Combine(Path.GetTempPath(), "EFOS", "WebView2");
			var env = await CoreWebView2Environment.CreateAsync(userDataFolder: userData);
			await webView.EnsureCoreWebView2Async(env);

			var core = webView.CoreWebView2;
			core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);

			core.WebResourceRequested += async (_, e) =>
			{
				try
				{
					string uri = e.Request.Uri;
					if (MedyaAkisiMi(uri, null))
					{
						string referer = e.Request.Headers.Contains("Referer") ? e.Request.Headers.GetHeader("Referer") : null;
						string ua = e.Request.Headers.Contains("User-Agent") ? e.Request.Headers.GetHeader("User-Agent") : core.Settings.UserAgent;
						await Yakala(uri, referer, ua);
					}
				}
				catch { }
			};

			core.WebResourceResponseReceived += async (_, e) =>
			{
				try
				{
					string uri = e.Request.Uri;
					string ct = e.Response.Headers.Contains("Content-Type") ? e.Response.Headers.GetHeader("Content-Type") : null;
					if (MedyaAkisiMi(uri, ct))
					{
						string referer = e.Request.Headers.Contains("Referer") ? e.Request.Headers.GetHeader("Referer") : null;
						string ua = e.Request.Headers.Contains("User-Agent") ? e.Request.Headers.GetHeader("User-Agent") : core.Settings.UserAgent;
						await Yakala(uri, referer, ua);
					}
				}
				catch { }
			};

			core.Navigate(pageUrl);
		}
		catch (Exception ex)
		{
			MessageBox.Show(this,
				"WebView2 başlatılamadı. Microsoft Edge WebView2 Runtime kurulu olduğundan emin olun.\n\nAyrıntı: " + ex.Message,
				Ortak.Str("ad"), MessageBoxButtons.OK, MessageBoxIcon.Warning);
			DialogResult = DialogResult.Cancel;
			Close();
		}
	}

	private async Task Yakala(string streamUrl, string referer, string ua)
	{
		if (Interlocked.CompareExchange(ref yakalandi, 1, 0) != 0) return;

		string currentUrl = webView.Source?.AbsoluteUri ?? pageUrl;
		string finalReferer = string.IsNullOrWhiteSpace(referer) ? currentUrl : referer;
		string cookieHeader = null;
		try
		{
			var cookies = await webView.CoreWebView2.CookieManager.GetCookiesAsync(streamUrl);
			if (cookies == null || cookies.Count == 0)
				cookies = await webView.CoreWebView2.CookieManager.GetCookiesAsync(currentUrl);
			if (cookies != null && cookies.Count > 0)
				cookieHeader = string.Join("; ", cookies.Select(c => c.Name + "=" + c.Value));
		}
		catch { }

		Sonuc = new YakalananMedya(streamUrl, finalReferer, ua ?? "", cookieHeader);
		if (!IsDisposed)
		{
			BeginInvoke(() =>
			{
				DialogResult = DialogResult.OK;
				Close();
			});
		}
	}

	private static bool MedyaAkisiMi(string url, string contentType)
	{
		if (string.IsNullOrWhiteSpace(url)) return false;
		string low = url.ToLowerInvariant();
		if (!low.StartsWith("http://") && !low.StartsWith("https://")) return false;
		if (EngelliParcalar.Any(p => low.Contains(p))) return false;

		string path = "";
		if (Uri.TryCreate(low, UriKind.Absolute, out var u)) path = u.AbsolutePath;
		if (path.EndsWith(".ts") || path.EndsWith(".m4s") || path.EndsWith(".vtt") ||
			path.EndsWith(".jpg") || path.EndsWith(".jpeg") || path.EndsWith(".png") ||
			path.EndsWith(".webp") || path.EndsWith(".gif") || path.EndsWith(".js") ||
			path.EndsWith(".css"))
			return false;

		if (path.EndsWith(".m3u8") || path.EndsWith(".mpd") || path.EndsWith(".mp4") || path.EndsWith(".m4v") ||
			low.Contains(".m3u8?") || low.Contains(".mpd?"))
			return true;

		if (!string.IsNullOrEmpty(contentType))
		{
			string ct = contentType.ToLowerInvariant();
			if (ct.Contains("application/vnd.apple.mpegurl") ||
				ct.Contains("application/x-mpegurl") ||
				ct.Contains("application/dash+xml"))
				return true;
		}
		return false;
	}
}
