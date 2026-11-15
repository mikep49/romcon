using System;
using System.IO;
using System.Security;
using System.Security.Principal;
using System.Windows.Forms;
using Microsoft.Win32;

namespace RomCon
{
    /// <summary>
    /// Starting RomCon when the user signs in. Usually that's a value under the Run key. Windows starts those without
    /// administrator rights and won't ask for them, so when RomCon always runs as administrator, a scheduled task that runs with
    /// the highest privileges starts it instead. Only RomCon running as administrator can make that task.
    /// </summary>
    static class Startup
    {
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string Name = "RomCon"; // of both the Run value and the task
        const int TaskCreateOrUpdate = 6;
        const int TaskLogonInteractiveToken = 3;

        static bool? hasTask; // asking the Task Scheduler is slow-ish, and only RomCon changes the task

        public static bool Enabled => HasRunValue || HasTask;

        static bool HasRunValue
        {
            get
            {
                using (var key = Registry.CurrentUser.OpenSubKey(RunKey)) return key?.GetValue(Name) != null;
            }
        }

        static bool HasTask
        {
            get
            {
                if (hasTask == null)
                {
                    try
                    {
                        TaskFolder().GetTask(Name);
                        hasTask = true;
                    }
                    catch (Exception)
                    {
                        hasTask = false; // there's no such task (or no Task Scheduler)
                    }
                }
                return hasTask.Value;
            }
        }

        /// <summary>Turns starting with Windows on or off, as administrator or not.</summary>
        public static void Set(bool enabled, bool asAdmin)
        {
            bool elevated = TrayApp.IsElevated;
            if (enabled && asAdmin && (elevated || HasTask))
            {
                if (elevated) RegisterTask(); // without the rights to remake it, an existing task is left as it is
                SetRunValue(false);
            }
            else
            {
                // Run as administrator but without the rights to make the task, RomCon asks for them when it starts.
                SetRunValue(enabled);
                if (HasTask) DeleteTask();
            }
        }

        /// <summary>
        /// Switches to the task or back to the Run key after "always run as administrator" changed in a RomCon that couldn't make
        /// the switch itself. Needs administrator rights.
        /// </summary>
        public static void Settle(bool asAdmin)
        {
            if (asAdmin ? HasRunValue : HasTask) Set(true, asAdmin);
        }

        static void SetRunValue(bool on)
        {
            using (var key = Registry.CurrentUser.CreateSubKey(RunKey))
            {
                if (on) key.SetValue(Name, "\"" + Application.ExecutablePath + "\" --minimized");
                else key.DeleteValue(Name, false);
            }
        }

        static void RegisterTask()
        {
            TaskFolder().RegisterTask(Name, TaskXml(), TaskCreateOrUpdate, null, null, TaskLogonInteractiveToken, null);
            hasTask = true;
            Log.Info("RomCon will start as administrator with Windows (scheduled task \"" + Name + "\").");
        }

        static void DeleteTask()
        {
            TaskFolder().DeleteTask(Name, 0);
            hasTask = false;
        }

        static dynamic TaskFolder()
        {
            dynamic service = Activator.CreateInstance(Type.GetTypeFromProgID("Schedule.Service", true));
            service.Connect();
            return service.GetFolder(@"\");
        }

        /// <summary>
        /// Starts RomCon in the tray at this user's sign-in. The settings undo the Task Scheduler's defaults that don't suit an app
        /// that stays open: stopping it after 3 days, not starting it on battery, and running it at below-normal priority.
        /// </summary>
        static string TaskXml()
        {
            string user;
            using (var id = WindowsIdentity.GetCurrent()) user = id.User.Value;
            string exe = Application.ExecutablePath;
            return
@"<?xml version=""1.0"" encoding=""UTF-16""?>
<Task version=""1.2"" xmlns=""http://schemas.microsoft.com/windows/2004/02/mit/task"">
  <RegistrationInfo>
    <Description>Starts RomCon as administrator when you sign in. Turn this off in RomCon's settings.</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <UserId>" + user + @"</UserId>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id=""Author"">
      <UserId>" + user + @"</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>5</Priority>
  </Settings>
  <Actions Context=""Author"">
    <Exec>
      <Command>" + SecurityElement.Escape(exe) + @"</Command>
      <Arguments>--minimized</Arguments>
      <WorkingDirectory>" + SecurityElement.Escape(Path.GetDirectoryName(exe)) + @"</WorkingDirectory>
    </Exec>
  </Actions>
</Task>";
        }
    }
}
