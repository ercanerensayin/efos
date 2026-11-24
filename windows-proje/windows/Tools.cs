using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace EFOS;

internal static class Tools
{
	private static string WingetPackages => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "WinGet", "Packages");

	public static string YtDlp()
	{
		try
		{
			string[] directories = Directory.GetDirectories(WingetPackages, "yt-dlp.yt-dlp_*");
			for (int i = 0; i < directories.Length; i++)
			{
				string text = Path.Combine(directories[i], "yt-dlp.exe");
				if (File.Exists(text))
				{
					return text;
				}
			}
		}
		catch
		{
		}
		return FindOnPath("yt-dlp.exe");
	}

	public static string FfmpegDir()
	{
		try
		{
			string[] directories = Directory.GetDirectories(WingetPackages, "yt-dlp.FFmpeg_*");
			for (int i = 0; i < directories.Length; i++)
			{
				string[] files = Directory.GetFiles(directories[i], "ffmpeg.exe", SearchOption.AllDirectories);
				if (files.Length > 0)
				{
					return Path.GetDirectoryName(files[0]);
				}
			}
		}
		catch
		{
		}
		string text = FindOnPath("ffmpeg.exe");
		if (text != null)
		{
			return Path.GetDirectoryName(text);
		}
		return null;
	}

	public static string FreshPath()
	{
		string text = Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.Machine) ?? "";
		string text2 = Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.User) ?? "";
		string text3 = Environment.GetEnvironmentVariable("PATH") ?? "";
		return string.Join(";", new string[3] { text, text2, text3 }.Where((string s) => s.Length > 0));
	}

	public static string WinGet()
	{
		string text = FindOnPath("winget.exe");
		if (text != null)
		{
			return text;
		}
		string text2 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "WindowsApps", "winget.exe");
		if (!File.Exists(text2))
		{
			return null;
		}
		return text2;
	}

	private static string FindOnPath(string exe)
	{
		string[] array = FreshPath().Split(';', StringSplitOptions.RemoveEmptyEntries);
		foreach (string text in array)
		{
			try
			{
				string text2 = Path.Combine(Environment.ExpandEnvironmentVariables(text.Trim()), exe);
				if (File.Exists(text2))
				{
					return text2;
				}
			}
			catch
			{
			}
		}
		return null;
	}

	public static ProcessStartInfo Start(string file, IEnumerable<string> args)
	{
		ProcessStartInfo processStartInfo = new ProcessStartInfo(file)
		{
			UseShellExecute = false,
			RedirectStandardOutput = true,
			RedirectStandardError = true,
			CreateNoWindow = true,
			StandardOutputEncoding = Encoding.UTF8,
			StandardErrorEncoding = Encoding.UTF8
		};
		processStartInfo.Environment["PATH"] = FreshPath();
		processStartInfo.Environment["PYTHONIOENCODING"] = "utf-8";
		processStartInfo.Environment["PYTHONUTF8"] = "1";
		foreach (string arg in args)
		{
			processStartInfo.ArgumentList.Add(arg);
		}
		return processStartInfo;
	}

	public static Task<(string Output, int Code)> Run(string file, params string[] args)
	{
		return Task.Run(() =>
		{
			try
			{
				using Process process = Process.Start(Start(file, args));
				Task<string> task = process.StandardOutput.ReadToEndAsync();
				Task<string> task2 = process.StandardError.ReadToEndAsync();
				process.WaitForExit();
				return (task.Result + task2.Result, process.ExitCode);
			}
			catch (Exception ex)
			{
				return (ex.Message, -1);
			}
		});
	}

	public static async Task<string> YtDlpVersion()
	{
		string text = YtDlp();
		if (text == null)
		{
			return null;
		}
		(string, int) tuple = await Run(text, "--version");
		var (text2, _) = tuple;
		return (tuple.Item2 == 0) ? text2.Trim() : null;
	}
}
