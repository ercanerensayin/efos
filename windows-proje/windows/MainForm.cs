using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace EFOS;

internal class MainForm : Form
{
	private static string T(string k) => Ortak.T(k);

	private readonly UrlBox urlBox;
	private readonly GlyphButton btnVideo;
	private readonly GlyphButton btnMp3;
	private readonly GlyphButton btnSniff;
	private readonly GlyphButton btnFolder;
	private readonly GlyphButton btnUpdate;
	private readonly GlyphButton btnSettings;
	private readonly ProgressLine progress;
	private readonly Label lblTitle;
	private readonly Label lblVersion;
	private readonly StatusView status;
	private readonly ToolTip tips = new ToolTip();
	private readonly ContextMenuStrip settingsMenu = new ContextMenuStrip();

	private Process proc;
	private GlyphButton activeBtn;
	private bool busy;
	private bool cancelled;
	private bool audioMode;
	private string lastFile;
	private string lastError;
	private string lastDoneUrl;
	private string lastFailedUrl;
	private bool lastFailedAudio;
	private int parts;
	private readonly string startUrl;

	public MainForm(string url = null)
	{
		startUrl = url;
		AutoScaleMode = AutoScaleMode.None;
		SuspendLayout();
		Text = Ortak.Str("ad");
		BackColor = UI.Bg;
		Font = new Font("Segoe UI", 9.5f);
		FormBorderStyle = FormBorderStyle.FixedSingle;
		MaximizeBox = false;
		StartPosition = FormStartPosition.CenterScreen;
		ClientSize = new Size(520, 336);
		AllowDrop = true;
		try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

		var logo = new LogoView { Location = new Point(24, 22), Size = new Size(46, 46) };
		var title = new Label
		{
			Text = Ortak.Str("ad"),
			Font = new Font("Segoe UI Semibold", 16f),
			ForeColor = UI.Text,
			AutoSize = true,
			Location = new Point(80, 17)
		};
		var slogan = new Label
		{
			Text = Ortak.Str("slogan"),
			Font = new Font("Segoe UI", 9f),
			ForeColor = UI.Sub,
			AutoSize = true,
			Location = new Point(82, 50)
		};

		urlBox = new UrlBox { Location = new Point(24, 92), Size = new Size(472, 46), TabIndex = 0 };
		urlBox.PasteButton.Click += (_, _) => PasteFromClipboard(force: true);
		urlBox.Box.KeyDown += (_, e) =>
		{
			if (e.KeyCode == Keys.Return)
			{
				e.SuppressKeyPress = true;
				OnDownloadClick(btnVideo, audio: false);
			}
		};
		tips.SetToolTip(urlBox.PasteButton, "Panodaki bağlantıyı yapıştır");

		btnVideo = new GlyphButton
		{
			Kind = BtnKind.Primary,
			Glyph = UI.GVideo,
			Text = T("videoIndir"),
			Location = new Point(24, 150),
			Size = new Size(230, 54),
			TabIndex = 1
		};
		btnVideo.Click += (_, _) => OnDownloadClick(btnVideo, audio: false);
		btnMp3 = new GlyphButton
		{
			Kind = BtnKind.Primary,
			Glyph = UI.GMusic,
			Text = T("mp3Indir"),
			Location = new Point(266, 150),
			Size = new Size(230, 54),
			TabIndex = 2
		};
		btnMp3.Click += (_, _) => OnDownloadClick(btnMp3, audio: true);
		tips.SetToolTip(btnVideo, "MP4 olarak indir (Enter)");
		tips.SetToolTip(btnMp3, "Yalnızca sesi MP3 olarak indir");

		progress = new ProgressLine { Location = new Point(24, 220), Size = new Size(472, 6) };
		lblTitle = new Label
		{
			Location = new Point(24, 236),
			Size = new Size(472, 20),
			Font = new Font("Segoe UI Semibold", 9.5f),
			ForeColor = UI.Text,
			AutoEllipsis = true,
			Text = T("hazir")
		};
		btnSniff = new GlyphButton
		{
			Kind = BtnKind.Ghost,
			Glyph = UI.GLink,
			Text = Ortak.TOr("tarayicidaAra", "Tarayıcıda ara"),
			Location = new Point(342, 231),
			Size = new Size(154, 26),
			Visible = false
		};
		btnSniff.Click += (_, _) => OpenSniffer();
		tips.SetToolTip(btnSniff, "Sayfayı uygulama içi tarayıcıda açıp gömülü video akışını yakalar");

		status = new StatusView { Location = new Point(24, 256), Size = new Size(472, 20), Font = new Font("Segoe UI", 9f) };
		status.Set(UI.GInfo, T("ipucu"), UI.Sub);
		status.Click += (_, _) => ShowErrorDetailIfAny();

		var line = new Panel { Location = new Point(24, 286), Size = new Size(472, 1), BackColor = UI.Border };

		btnFolder = new GlyphButton
		{
			Kind = BtnKind.Ghost,
			Glyph = UI.GFolder,
			Text = T("klasoruAc"),
			Location = new Point(16, 294),
			Size = new Size(134, 34),
			TabIndex = 3
		};
		btnFolder.Click += (_, _) => OpenFolder();

		btnUpdate = new GlyphButton
		{
			Kind = BtnKind.Ghost,
			Glyph = UI.GRefresh,
			Text = T("guncelle"),
			Location = new Point(152, 294),
			Size = new Size(116, 34),
			TabIndex = 4
		};
		btnUpdate.Click += async (_, _) => await DoUpdate();
		tips.SetToolTip(btnUpdate, "yt-dlp ve FFmpeg'i winget ile kurar/günceller.\nwinget'in lisans koşulları otomatik kabul edilir.");

		btnSettings = new GlyphButton
		{
			Kind = BtnKind.Ghost,
			Glyph = UI.GSettings,
			Text = T("ayarlar"),
			Location = new Point(270, 294),
			Size = new Size(110, 34),
			TabIndex = 5
		};
		btnSettings.Click += (_, _) => ShowSettings();

		lblVersion = new Label
		{
			Location = new Point(380, 294),
			Size = new Size(116, 34),
			TextAlign = ContentAlignment.MiddleRight,
			ForeColor = UI.Sub,
			Font = new Font("Segoe UI", 8.5f),
			Text = ""
		};

		Controls.AddRange(new Control[] { logo, title, slogan, urlBox, btnVideo, btnMp3, progress, lblTitle, btnSniff, status, line, btnFolder, btnUpdate, btnSettings, lblVersion });
		RefreshFolderTip();

		float scale = DeviceDpi / 96f;
		if (Math.Abs(scale - 1f) > 0.01f) Scale(new SizeF(scale, scale));
		ResumeLayout(performLayout: true);

		DragEnter += (_, e) =>
		{
			if (e.Data.GetDataPresent(DataFormats.Text) || e.Data.GetDataPresent(DataFormats.UnicodeText))
				e.Effect = DragDropEffects.Copy;
		};
		DragDrop += (_, e) =>
		{
			string text = (e.Data.GetData(DataFormats.UnicodeText) ?? e.Data.GetData(DataFormats.Text)) as string;
			if (!busy && IsUrl(text)) urlBox.Box.Text = text.Trim();
		};
	}

	protected override async void OnShown(EventArgs e)
	{
		base.OnShown(e);
		urlBox.Box.Focus();
		if (IsUrl(startUrl)) urlBox.Box.Text = startUrl.Trim();
		else PasteFromClipboard(force: false);
		await RefreshVersion();
		if (Tools.YtDlp() == null) status.Set(UI.GError, T("ytdlpYok"), UI.Red);
	}

	protected override void OnActivated(EventArgs e)
	{
		base.OnActivated(e);
		if (!busy) PasteFromClipboard(force: false);
	}

	protected override void OnFormClosing(FormClosingEventArgs e)
	{
		if (busy && proc != null && !proc.HasExited)
		{
			if (MessageBox.Show(this, T("cikisSorusu"), Ortak.Str("ad"), MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
			{
				e.Cancel = true;
				return;
			}
			cancelled = true;
			try { proc.Kill(entireProcessTree: true); } catch { }
		}
		base.OnFormClosing(e);
	}

	private static bool IsUrl(string s) =>
		!string.IsNullOrWhiteSpace(s)
		&& Uri.TryCreate(s.Trim(), UriKind.Absolute, out Uri u)
		&& (u.Scheme == Uri.UriSchemeHttp || u.Scheme == Uri.UriSchemeHttps);

	private void PasteFromClipboard(bool force)
	{
		try
		{
			if ((!force && urlBox.Box.Text.Length > 0) || !Clipboard.ContainsText())
			{
				if (force) status.Set(UI.GInfo, T("panodaBaglantiYok"), UI.Sub);
				return;
			}
			string text = Clipboard.GetText().Trim();
			// Az önce indirilen bağlantıyı pencereye her dönüşte yeniden yapıştırma.
			if (IsUrl(text) && (force || text != lastDoneUrl))
			{
				urlBox.Box.Text = text;
				urlBox.Box.SelectionStart = text.Length;
			}
			else if (force)
			{
				status.Set(UI.GInfo, T("panodaBaglantiYok"), UI.Sub);
			}
		}
		catch { }
	}

	private async Task RefreshVersion()
	{
		string v = await Tools.YtDlpVersion();
		lblVersion.Text = v == null ? "" : "yt-dlp " + v;
	}

	private void RefreshFolderTip() => tips.SetToolTip(btnFolder, Ayarlar.Klasor);

	// ---------- Ayarlar menüsü: kalite, biçim, çerez, oynatma listesi, kayıt klasörü ----------
	private void ShowSettings()
	{
		if (busy) return;
		settingsMenu.Items.Clear();
		settingsMenu.Font = new Font("Segoe UI", 9.5f);

		var quality = new ToolStripMenuItem(T("kalite"));
		var list = Ortak.Kaliteler;
		for (int i = 0; i < list.Length; i++)
		{
			int idx = i;
			var item = new ToolStripMenuItem(list[i].Ad) { Checked = Ayarlar.Kalite == i };
			item.Click += (_, _) => Ayarlar.Kalite = idx;
			quality.DropDownItems.Add(item);
		}
		settingsMenu.Items.Add(quality);

		var container = new ToolStripMenuItem(T("bicim"));
		var bicimler = Ortak.Bicimler;
		for (int i = 0; i < bicimler.Length; i++)
		{
			int idx = i;
			var item = new ToolStripMenuItem(bicimler[i].Ad) { Checked = Ayarlar.Bicim == i };
			item.Click += (_, _) => Ayarlar.Bicim = idx;
			container.DropDownItems.Add(item);
		}
		settingsMenu.Items.Add(container);

		var cookiesMenu = new ToolStripMenuItem(Ortak.TOr("tarayiciCerez", "Tarayıcı çerezleri"));
		var browsers = new (string Ad, string Kod)[]
		{
			("Kapalı", ""),
			("Microsoft Edge", "edge"),
			("Google Chrome", "chrome"),
			("Mozilla Firefox", "firefox"),
			("Brave", "brave")
		};
		foreach (var b in browsers)
		{
			string kod = b.Kod;
			var item = new ToolStripMenuItem(b.Ad) { Checked = string.Equals(Ayarlar.CerezTarayici, kod, StringComparison.OrdinalIgnoreCase) };
			item.Click += (_, _) => Ayarlar.CerezTarayici = kod;
			cookiesMenu.DropDownItems.Add(item);
		}
		settingsMenu.Items.Add(cookiesMenu);

		var playlist = new ToolStripMenuItem(T("listeyiIndir")) { Checked = Ayarlar.Liste };
		playlist.Click += (_, _) => Ayarlar.Liste = !Ayarlar.Liste;
		settingsMenu.Items.Add(playlist);

		settingsMenu.Items.Add(new ToolStripSeparator());
		var folder = new ToolStripMenuItem("Kayıt klasörünü değiştir…");
		folder.Click += (_, _) => ChooseFolder();
		settingsMenu.Items.Add(folder);
		if (Ayarlar.OzelKlasor)
		{
			var reset = new ToolStripMenuItem("Varsayılan klasöre dön (İndirilenler\\" + Ortak.Str("klasor") + ")");
			reset.Click += (_, _) => { Ayarlar.Klasor = Ayarlar.DefaultFolder; RefreshFolderTip(); };
			settingsMenu.Items.Add(reset);
		}
		settingsMenu.Show(btnSettings, new Point(0, btnSettings.Height));
	}

	private void ChooseFolder()
	{
		using var dlg = new FolderBrowserDialog
		{
			Description = "İndirilen dosyaların kaydedileceği klasörü seçin",
			UseDescriptionForTitle = true,
			SelectedPath = Ayarlar.Klasor,
			ShowNewFolderButton = true
		};
		if (dlg.ShowDialog(this) == DialogResult.OK)
		{
			Ayarlar.Klasor = dlg.SelectedPath;
			RefreshFolderTip();
			status.Set(UI.GFolder, "Kayıt klasörü: " + dlg.SelectedPath, UI.Sub);
		}
	}

	private void SetSniffVisible(bool on)
	{
		btnSniff.Visible = on;
		float scale = DeviceDpi / 96f;
		lblTitle.Width = (int)((on ? 310 : 472) * scale);
	}

	private void SetBusy(bool on, GlyphButton active)
	{
		busy = on;
		activeBtn = on ? active : null;
		btnUpdate.Enabled = !on;
		btnSettings.Enabled = !on;
		btnSniff.Enabled = !on;
		urlBox.Box.ReadOnly = on;
		urlBox.PasteButton.Enabled = !on;
		foreach (var b in new[] { btnVideo, btnMp3 })
		{
			bool isActive = on && b == active;
			b.Enabled = !on || isActive;
			b.Danger = isActive;
			b.Glyph = isActive ? UI.GCancel : (b == btnMp3 ? UI.GMusic : UI.GVideo);
			b.Text = isActive ? T("iptal") : (b == btnMp3 ? T("mp3Indir") : T("videoIndir"));
			b.Invalidate();
		}
	}

	private void OpenFolder()
	{
		try
		{
			string dir = Ayarlar.Klasor;
			Directory.CreateDirectory(dir);
			if (lastFile != null && File.Exists(lastFile))
				Process.Start("explorer.exe", "/select,\"" + lastFile + "\"");
			else
				Process.Start("explorer.exe", "\"" + dir + "\"");
		}
		catch { }
	}

	private void OpenSniffer()
	{
		if (busy) return;
		string targetUrl = !string.IsNullOrWhiteSpace(lastFailedUrl) ? lastFailedUrl : urlBox.Box.Text.Trim();
		if (!IsUrl(targetUrl)) return;

		using var dlg = new MedyaYakalayiciForm(targetUrl);
		if (dlg.ShowDialog(this) == DialogResult.OK && dlg.Sonuc != null)
		{
			var m = dlg.Sonuc;
			var extra = new List<string>();
			if (!string.IsNullOrWhiteSpace(m.Referer))
			{
				extra.Add("--referer");
				extra.Add(m.Referer);
			}
			if (!string.IsNullOrWhiteSpace(m.UserAgent))
			{
				extra.Add("--user-agent");
				extra.Add(m.UserAgent);
			}
			if (!string.IsNullOrWhiteSpace(m.Cookie))
			{
				extra.Add("--add-header");
				extra.Add("Cookie:" + m.Cookie);
			}
			var sender = lastFailedAudio ? btnMp3 : btnVideo;
			status.Set(UI.GSync, Ortak.TOr("videoBulundu", "Video akışı yakalandı, indiriliyor…"), UI.Accent);
			_ = StartDownload(sender, lastFailedAudio, m.StreamUrl, extra, originUrl: targetUrl);
		}
	}

	private void ShowErrorDetailIfAny()
	{
		if (string.IsNullOrWhiteSpace(lastError) || busy) return;
		var res = MessageBox.Show(this,
			lastError + "\n\nHata metnini panoya kopyalamak ister misiniz?",
			T("indirilemedi"),
			MessageBoxButtons.YesNo,
			MessageBoxIcon.Information);
		if (res == DialogResult.Yes)
		{
			try { Clipboard.SetText(lastError); } catch { }
		}
	}

	private void OnDownloadClick(GlyphButton sender, bool audio)
	{
		if (busy)
		{
			if (sender == activeBtn && proc != null && !proc.HasExited)
			{
				cancelled = true;
				try { proc.Kill(entireProcessTree: true); } catch { }
			}
			return;
		}
		_ = StartDownload(sender, audio);
	}

	private async Task StartDownload(
		GlyphButton sender,
		bool audio,
		string overrideUrl = null,
		List<string> extraArgs = null,
		string originUrl = null)
	{
		string url = (overrideUrl ?? urlBox.Box.Text).Trim();
		string sourceUrl = (originUrl ?? url).Trim();
		if (!IsUrl(url))
		{
			status.Set(UI.GError, T("gecersizBaglanti"), UI.Red);
			urlBox.Box.Focus();
			return;
		}
		string ytdlp = Tools.YtDlp();
		if (ytdlp == null)
		{
			status.Set(UI.GError, T("ytdlpYok"), UI.Red);
			return;
		}
		string outDir = Ayarlar.Klasor;
		try { Directory.CreateDirectory(outDir); }
		catch (Exception ex)
		{
			status.Set(UI.GError, "Klasör oluşturulamadı: " + ex.Message, UI.Red);
			return;
		}

		var args = Ortak.Args("ortak");
		args.AddRange(Ortak.Args(Ayarlar.Liste ? "listeAcik" : "listeKapali"));
		// Yarım parçalar (.part, .ytdl, ara dosyalar) geçici klasöre iner; iptalde İndirilenler temiz kalır.
		string tempDir = Path.Combine(Path.GetTempPath(), "EFOS", Guid.NewGuid().ToString("N"));
		args.AddRange(new[] { "-P", outDir, "-P", "temp:" + tempDir, "-o", Ortak.Cikti });
		string ffmpeg = Tools.FfmpegDir();
		if (ffmpeg != null) args.AddRange(new[] { "--ffmpeg-location", ffmpeg });
		var kaliteler = Ortak.Kaliteler;
		string format = kaliteler[Math.Clamp(Ayarlar.Kalite, 0, kaliteler.Length - 1)].Format;
		args.AddRange(Ortak.Args(audio ? "mp3" : "video").Select(a => a.Replace("{format}", format)));
		if (!audio)
		{
			var bicimler = Ortak.Bicimler;
			args.AddRange(bicimler[Math.Clamp(Ayarlar.Bicim, 0, bicimler.Length - 1)].Args);
		}
		if (extraArgs != null && extraArgs.Count > 0)
		{
			args.AddRange(extraArgs);
		}
		else if (!string.IsNullOrWhiteSpace(Ayarlar.CerezTarayici))
		{
			args.Add("--cookies-from-browser");
			args.Add(Ayarlar.CerezTarayici);
		}
		args.Add("--");
		args.Add(url);

		audioMode = audio;
		cancelled = false;
		lastError = null;
		lastFile = null;
		parts = 0;
		progress.Value = 0f;
		lblTitle.Text = T("inceleniyor");
		SetSniffVisible(false);
		status.Cursor = Cursors.Default;
		tips.SetToolTip(status, "");
		status.Set(UI.GSync, T("bilgiAliniyor"), UI.Sub);
		btnFolder.Text = T("klasoruAc");
		btnFolder.Invalidate();
		SetBusy(true, sender);

		int code;
		try
		{
			proc = new Process { StartInfo = Tools.Start(ytdlp, args) };
			proc.OutputDataReceived += (_, e) => { if (e.Data != null) BeginInvoke(() => OnLine(e.Data)); };
			proc.ErrorDataReceived += (_, e) => { if (e.Data != null) BeginInvoke(() => OnErr(e.Data)); };
			proc.Start();
			proc.BeginOutputReadLine();
			proc.BeginErrorReadLine();
			await Task.Run(() => proc.WaitForExit());
			code = proc.ExitCode;
		}
		catch (Exception ex)
		{
			lastError = ex.Message;
			code = -1;
		}
		for (int i = 0; i < 5 && Directory.Exists(tempDir); i++)
		{
			try { Directory.Delete(tempDir, recursive: true); }
			catch { await Task.Delay(400); } // iptalde FFmpeg dosyayı bir an daha tutabilir
		}
		await Task.Yield();
		OnFinished(code, sourceUrl, audio);
	}

	private static bool Known(string s) => s.Length > 0 && !s.Contains("Unknown") && !s.Contains("N/A") && s != "NA";

	private void OnLine(string line)
	{
		if (line.StartsWith("PROG|"))
		{
			string[] p = line.Split('|');
			if (p.Length >= 4 && double.TryParse(p[1].Trim().TrimEnd('%'), NumberStyles.Float, CultureInfo.InvariantCulture, out double pct))
			{
				progress.Value = (float)(pct / 100.0);
				string text = $"{T("indiriliyor")}  %{pct:0}";
				string speed = p[2].Trim(), eta = p[3].Trim();
				if (Known(speed)) text += "  ·  " + speed;
				if (Known(eta)) text += "  ·  " + eta + " " + T("kaldi");
				if (!audioMode && parts >= 2) text += "  ·  " + T("sesParcasi");
				status.Set(UI.GDownload, text, UI.Accent);
			}
		}
		else if (line.StartsWith("TITLE|"))
		{
			lblTitle.Text = line.Substring(6);
			tips.SetToolTip(lblTitle, lblTitle.Text);
			parts = 0; // oynatma listesinde her video için sıfırla
		}
		else if (line.StartsWith("FILE|"))
		{
			lastFile = line.Substring(5).Trim();
		}
		else if (line.StartsWith("[download] Destination"))
		{
			parts++;
		}
		else if (line.StartsWith("[Merger]"))
		{
			progress.Value = 1f;
			status.Set(UI.GSync, T("birlestiriliyor"), UI.Accent);
		}
		else if (line.StartsWith("[ExtractAudio]"))
		{
			progress.Value = 1f;
			status.Set(UI.GSync, T("donusturuluyor"), UI.Accent);
		}
		else if (line.Contains("has already been downloaded"))
		{
			progress.Value = 1f;
		}
	}

	private void OnErr(string line)
	{
		int i = line.IndexOf("ERROR:", StringComparison.Ordinal);
		if (i >= 0) lastError = line.Substring(i + 6).Trim();
	}

	private void OnFinished(int code, string url, bool audio)
	{
		SetBusy(false, null);
		proc?.Dispose();
		proc = null;
		if (cancelled)
		{
			progress.Value = 0f;
			if (lblTitle.Text == T("inceleniyor")) lblTitle.Text = T("hazir");
			status.Set(UI.GCancel, T("iptalEdildi"), UI.Sub);
		}
		else if (code == 0)
		{
			progress.Value = 1f;
			string folderName = Ayarlar.OzelKlasor
				? Path.GetFileName(Ayarlar.Klasor.TrimEnd('\\'))
				: "İndirilenler\\" + Ortak.Str("klasor");
			status.Set(UI.GDone, string.Format(T("tamamlandi"), folderName), UI.Green);
			if (lastFile != null && File.Exists(lastFile))
			{
				btnFolder.Text = T("dosyayiGoster");
				btnFolder.Invalidate();
			}
			lastDoneUrl = url;
			urlBox.Box.Clear();
		}
		else
		{
			progress.Value = 0f;
			if (lblTitle.Text == T("inceleniyor")) lblTitle.Text = T("indirilemedi");
			lastFailedUrl = url;
			lastFailedAudio = audio;
			status.Set(UI.GError, Ortak.Friendly(lastError), UI.Red);
			status.Cursor = string.IsNullOrWhiteSpace(lastError) ? Cursors.Default : Cursors.Hand;
			tips.SetToolTip(status, string.IsNullOrWhiteSpace(lastError) ? "" : lastError + "\n(Ayrıntı için tıklayın)");
			bool unsupported = !string.IsNullOrWhiteSpace(lastError) && lastError.Contains("unsupported url", StringComparison.OrdinalIgnoreCase);
			SetSniffVisible(unsupported);
		}
	}

	// ---------- Güncelleme: winget varsa onunla, yoksa yt-dlp -U ----------
	private async Task DoUpdate()
	{
		if (busy) return;
		SetBusy(true, null);
		progress.Value = 0f;
		status.Set(UI.GSync, T("guncellemeKontrol"), UI.Accent);
		string before = await Tools.YtDlpVersion();
		string winget = Tools.WinGet();
		bool ok;
		bool ffmpegChanged = false;

		if (winget != null)
		{
			string[] common = { "-e", "--accept-source-agreements", "--accept-package-agreements", "--disable-interactivity" };
			// Kurulu değilse "install", kuruluysa "upgrade" (eski sürüm FFmpeg'i hiç kurmuyordu).
			string[] a1 = new[] { before != null ? "upgrade" : "install", "--id", "yt-dlp.yt-dlp" }.Concat(common).ToArray();
			int c1 = (await Tools.Run(winget, a1)).Code;
			progress.Value = 0.6f;
			string ff = Tools.FfmpegDir();
			int c2 = 0;
			// FFmpeg winget dışında (PATH'te) kuruluysa ona dokunma.
			if (ff == null || ff.Contains(@"\WinGet\Packages\", StringComparison.OrdinalIgnoreCase))
			{
				string[] a2 = new[] { ff != null ? "upgrade" : "install", "--id", "yt-dlp.FFmpeg" }.Concat(common).ToArray();
				c2 = (await Tools.Run(winget, a2)).Code;
			}
			ffmpegChanged = c2 == 0;
			const int NoUpdate = -1978335189; // APPINSTALLER_CLI_ERROR_UPDATE_NOT_APPLICABLE
			ok = (c1 == 0 || c1 == NoUpdate) && (c2 == 0 || c2 == NoUpdate);
		}
		else if (Tools.YtDlp() is string yt)
		{
			ok = (await Tools.Run(yt, "-U")).Code == 0;
		}
		else
		{
			ok = false;
		}

		progress.Value = 1f;
		string after = await Tools.YtDlpVersion();
		lblVersion.Text = after == null ? "" : "yt-dlp " + after;
		SetBusy(false, null);

		if (after == null)
			status.Set(UI.GError, winget == null ? "winget bulunamadı; yt-dlp kurulamadı." : T("guncellemeBasarisiz"), UI.Red);
		else if (before != after)
			status.Set(UI.GDone, before == null ? string.Format(T("kuruldu"), after) : string.Format(T("guncellendi"), before, after), UI.Green);
		else if (ffmpegChanged)
			status.Set(UI.GDone, "FFmpeg güncellendi, yt-dlp zaten güncel.", UI.Green);
		else if (ok)
			status.Set(UI.GDone, T("herSeyGuncel"), UI.Green);
		else
			status.Set(UI.GError, "Güncelleme tamamlanamadı. Daha sonra tekrar deneyin.", UI.Red);
		progress.Value = 0f;
	}
}
