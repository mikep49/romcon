using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Versioning;
using System.Threading;
using System.Windows.Forms;

// Without this the runtime treats the exe as an old .NET 4.0 app and turns on legacy behavior (including old TLS defaults).
[assembly: TargetFramework(".NETFramework,Version=v4.8", FrameworkDisplayName = ".NET Framework 4.8")]
[assembly: AssemblyTitle("RomCon")]
[assembly: AssemblyProduct("RomCon")]
[assembly: AssemblyDescription("Control this PC from your Android phone")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace RomCon
{
    sealed class Options
    {
        public bool Minimized;
        public bool DryRun;
        public bool Restarted;
        public string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "RomCon");
        public int? Port;

        public static Options Parse(string[] args)
        {
            var o = new Options();
            for (int i = 0; i < args.Length; i++)
            {
                switch (args[i])
                {
                    case "--minimized": o.Minimized = true; break;
                    case "--dry-run": o.DryRun = true; break;
                    case "--restarted": o.Restarted = true; break;
                    case "--data-dir": if (i + 1 < args.Length) o.DataDir = Path.GetFullPath(args[++i]); break;
                    case "--port": if (i + 1 < args.Length && int.TryParse(args[++i], out int p)) o.Port = p; break;
                }
            }
            return o;
        }
    }

    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            var options = Options.Parse(args);
            // One instance per data folder; a second launch just brings the first one's window up.
            string key = StableHash(options.DataDir.ToLowerInvariant());
            string mutexName = @"Local\RomCon.Server." + key;
            string showEventName = @"Local\RomCon.Show." + key;

            using (var mutex = new Mutex(false, mutexName))
            {
                bool owned;
                try { owned = mutex.WaitOne(options.Restarted ? 15000 : 0); }
                catch (AbandonedMutexException) { owned = true; }

                if (!owned)
                {
                    try
                    {
                        using (var ev = EventWaitHandle.OpenExisting(showEventName)) ev.Set();
                    }
                    catch { }
                    return 0;
                }

                try
                {
                    Application.EnableVisualStyles();
                    Application.SetCompatibleTextRenderingDefault(false);
                    Application.ThreadException += (s, e) => Fatal(e.Exception);
                    AppDomain.CurrentDomain.UnhandledException += (s, e) => Fatal(e.ExceptionObject as Exception);

                    using (var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, showEventName))
                    using (var app = new TrayApp(options, showEvent))
                    {
                        Application.Run(app);
                    }
                }
                finally
                {
                    mutex.ReleaseMutex();
                }
            }
            return 0;
        }

        static string StableHash(string s)
        {
            uint h = 2166136261;
            foreach (char c in s) h = (h ^ c) * 16777619;
            return h.ToString("X8");
        }

        static void Fatal(Exception ex)
        {
            try { Log.Info("Unexpected error: " + ex); } catch { }
            MessageBox.Show("RomCon hit an unexpected error:\n\n" + (ex?.Message ?? "unknown"), "RomCon", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
