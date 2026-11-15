using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Principal;
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
        bool dataDirGiven;

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
                    case "--data-dir":
                        if (i + 1 < args.Length)
                        {
                            o.DataDir = Path.GetFullPath(args[++i]);
                            o.dataDirGiven = true;
                        }
                        break;
                    case "--port": if (i + 1 < args.Length && int.TryParse(args[++i], out int p)) o.Port = p; break;
                }
            }
            return o;
        }

        /// <summary>The command line for this RomCon restarting itself.</summary>
        public string RestartArgs(bool minimized)
        {
            var args = new List<string> { "--restarted" };
            if (minimized) args.Add("--minimized");
            if (DryRun) args.Add("--dry-run");
            // Left out unless given: the default is the user's own folder, which is where it should stay.
            if (dataDirGiven) args.Add("--data-dir " + Quote(DataDir));
            if (Port.HasValue) args.Add("--port " + Port.Value);
            return string.Join(" ", args);
        }

        static string Quote(string path)
        {
            // Paths can't hold quotes, but a trailing backslash would escape the closing one unless it is doubled.
            int trailing = path.Length - path.TrimEnd('\\').Length;
            return "\"" + path + new string('\\', trailing) + "\"";
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

            using (var mutex = OpenInstanceMutex(mutexName))
            {
                bool owned = false;
                if (mutex != null)
                {
                    try { owned = mutex.WaitOne(options.Restarted ? 15000 : 0); }
                    catch (AbandonedMutexException) { owned = true; }
                }

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

                    var config = Config.Load(options.DataDir);
                    if (options.Port.HasValue) config.Port = options.Port.Value;
                    Log.Init(config.Dir);

                    // A RomCon that was already restarted for this never tries again, so it can't keep restarting itself.
                    if (config.AlwaysAdmin && !options.Restarted && !TrayApp.IsElevated)
                    {
                        if (TrayApp.StartElevated(options, options.Minimized)) return 0;
                        Log.Info("Running without administrator rights: Windows didn't give them.");
                    }

                    using (var showEvent = CreateShowEvent(showEventName))
                    using (var app = new TrayApp(options, config, showEvent))
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

        // RomCon with and without administrator rights must reach each other's mutex and event. Left to Windows' defaults, those
        // made by an administrator RomCon would shut a normal one out, so they allow this user explicitly.

        /// <summary>The mutex that says a RomCon is running, or null if one runs that this RomCon isn't allowed to see.</summary>
        static Mutex OpenInstanceMutex(string name)
        {
            var security = new MutexSecurity();
            security.AddAccessRule(new MutexAccessRule(CurrentUser(), MutexRights.FullControl, AccessControlType.Allow));
            try
            {
                return new Mutex(false, name, out _, security);
            }
            catch (UnauthorizedAccessException)
            {
                return null; // e.g. made by an older RomCon running as administrator
            }
        }

        static EventWaitHandle CreateShowEvent(string name)
        {
            var security = new EventWaitHandleSecurity();
            security.AddAccessRule(new EventWaitHandleAccessRule(CurrentUser(), EventWaitHandleRights.FullControl, AccessControlType.Allow));
            return new EventWaitHandle(false, EventResetMode.AutoReset, name, out _, security);
        }

        static SecurityIdentifier CurrentUser()
        {
            using (var id = WindowsIdentity.GetCurrent()) return id.User;
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
