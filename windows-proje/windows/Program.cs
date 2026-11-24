using System;
using System.Windows.Forms;

namespace EFOS;

internal static class Program
{
	[STAThread]
	private static void Main(string[] args)
	{
		if (args.Length == 2 && args[0] == "--make-icon")
		{
			Logo.SaveIco(args[1]);
			return;
		}
		ApplicationConfiguration.Initialize();
		Application.Run(new MainForm((args.Length != 0) ? args[0] : null));
	}
}
