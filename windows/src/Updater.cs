using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace RomCon
{
    /// <summary>The newest RomCon release on GitHub.</summary>
    sealed class Release
    {
        public Version Version;
        public string PageUrl;
        /// <summary>Where to download RomCon.exe, or null if the release doesn't have it.</summary>
        public string ExeUrl;
        /// <summary>The exe's SHA-256 as lower-case hex, or null if GitHub didn't say.</summary>
        public string ExeSha256;
    }

    enum UpdateStage { Idle, Checking, UpToDate, Available, Downloading, Failed }

    /// <summary>Where updating stands. A new one replaces it on every change, so it can be read from any thread.</summary>
    sealed class UpdateStatus
    {
        public UpdateStage Stage;
        /// <summary>The newest release, once a check found one.</summary>
        public Release Latest;
        /// <summary>While downloading: how far it got, or -1 if the size isn't known.</summary>
        public int Percent = -1;
        public string Error;
        /// <summary>True when the check was the one RomCon does by itself at startup.</summary>
        public bool Automatic;

        public bool HasUpdate => Latest != null && Latest.Version > Updater.Current;
    }

    /// <summary>
    /// Looks on GitHub for a newer RomCon and swaps the running exe for it. Windows lets a running exe be renamed but not
    /// overwritten, so the old one moves aside to RomCon.exe.old, the new one takes its name, and RomCon restarts.
    /// </summary>
    sealed class Updater
    {
        const string Repo = "mikep49/romcon";
        const string LatestApi = "https://api.github.com/repos/" + Repo + "/releases/latest";
        public const string ReleasesPage = "https://github.com/" + Repo + "/releases/latest";
        const string ExeAsset = "RomCon.exe";

        /// <summary>This RomCon's version, as major.minor.patch to match the release tags.</summary>
        public static readonly Version Current = Normalize(Assembly.GetExecutingAssembly().GetName().Version);

        static string UserAgent => "RomCon/" + Current;

        volatile UpdateStatus status = new UpdateStatus { Stage = UpdateStage.Idle };
        int busy; // 1 while a check or download runs

        /// <summary>Raised on a background thread whenever <see cref="Status"/> changes.</summary>
        public event Action Changed;

        public UpdateStatus Status => status;

        /// <summary>Asks GitHub for the newest release in the background. Does nothing while a check or download is running.</summary>
        public void Check(bool automatic)
        {
            if (Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
            var latest = status.Latest;
            Set(new UpdateStatus { Stage = UpdateStage.Checking, Latest = latest, Automatic = automatic });
            Run(() =>
            {
                try
                {
                    var release = FetchLatest();
                    bool newer = release.Version > Current;
                    Set(new UpdateStatus { Stage = newer ? UpdateStage.Available : UpdateStage.UpToDate, Latest = release, Automatic = automatic });
                    if (newer) Log.Info("RomCon " + release.Version + " is available (this is " + Current + ").");
                }
                catch (Exception ex)
                {
                    Log.Info("Couldn't check for updates: " + Describe(ex));
                    Set(new UpdateStatus { Stage = UpdateStage.Failed, Latest = latest, Error = "Couldn't check for updates. " + Describe(ex), Automatic = automatic });
                }
            });
        }

        /// <summary>
        /// Downloads the new exe and puts it in place of this one. Calls <paramref name="installed"/> on the background thread
        /// once RomCon only needs restarting, or <paramref name="failed"/> with the reason if it couldn't update.
        /// </summary>
        public void Install(Action installed, Action<string> failed)
        {
            var release = status.Latest;
            if (release == null || !status.HasUpdate) return;
            if (Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
            Set(new UpdateStatus { Stage = UpdateStage.Downloading, Latest = release });
            Run(() =>
            {
                try
                {
                    if (release.ExeUrl == null) throw new UpdateException("The new version doesn't include " + ExeAsset + ".");
                    Log.Info("Downloading RomCon " + release.Version + ".");
                    string exe = Application.ExecutablePath;
                    string download = exe + ".download";
                    try
                    {
                        Download(release, download);
                        Swap(exe, download);
                    }
                    finally
                    {
                        TryDelete(download);
                    }
                    Log.Info("Updated to RomCon " + release.Version + ". Restarting.");
                    installed();
                }
                catch (Exception ex)
                {
                    string reason = Describe(ex);
                    Log.Info("Couldn't update: " + reason);
                    Set(new UpdateStatus { Stage = UpdateStage.Failed, Latest = release, Error = "Couldn't update. " + reason });
                    failed(reason);
                }
            });
        }

        /// <summary>Deletes the exe an update left behind. It stays in use until the old RomCon has fully exited, so this keeps trying for a while.</summary>
        public static void CleanUpInBackground()
        {
            string old = Application.ExecutablePath + ".old";
            if (!File.Exists(old)) return;
            Run(() =>
            {
                for (int i = 0; i < 30 && File.Exists(old); i++)
                {
                    if (TryDelete(old)) return;
                    Thread.Sleep(1000);
                }
            });
        }

        void Set(UpdateStatus next)
        {
            if (next.Stage != UpdateStage.Checking && next.Stage != UpdateStage.Downloading) Interlocked.Exchange(ref busy, 0);
            status = next;
            Changed?.Invoke();
        }

        void Downloading(Release release, int percent)
        {
            if (status.Stage == UpdateStage.Downloading && status.Percent == percent) return;
            status = new UpdateStatus { Stage = UpdateStage.Downloading, Latest = release, Percent = percent };
            Changed?.Invoke();
        }

        static Release FetchLatest()
        {
            var request = (HttpWebRequest)WebRequest.Create(LatestApi);
            request.UserAgent = UserAgent;
            request.Accept = "application/vnd.github+json";
            request.Timeout = 15000;
            request.ReadWriteTimeout = 15000;
            string json;
            using (var response = (HttpWebResponse)request.GetResponse())
            using (var reader = new StreamReader(response.GetResponseStream()))
            {
                json = reader.ReadToEnd();
            }
            return ParseRelease(json);
        }

        /// <summary>Reads GitHub's description of a release.</summary>
        public static Release ParseRelease(string json)
        {
            var root = new JavaScriptSerializer().DeserializeObject(json) as Dictionary<string, object>;
            if (root == null) throw new UpdateException("GitHub's answer didn't make sense.");
            var version = ParseVersion(root.TryGetValue("tag_name", out object tag) ? tag as string : null);
            if (version == null) throw new UpdateException("The newest release has no version number.");
            var release = new Release
            {
                Version = version,
                PageUrl = root.TryGetValue("html_url", out object page) && page is string url ? url : ReleasesPage,
            };
            if (root.TryGetValue("assets", out object assets) && assets is object[] list)
            {
                foreach (var item in list)
                {
                    if (!(item is Dictionary<string, object> asset) || !(asset.TryGetValue("name", out object name) && ExeAsset.Equals(name as string, StringComparison.OrdinalIgnoreCase))) continue;
                    release.ExeUrl = asset.TryGetValue("browser_download_url", out object download) ? download as string : null;
                    // GitHub gives each asset's digest as "sha256:<hex>".
                    if (asset.TryGetValue("digest", out object digest) && digest is string d && d.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
                    {
                        release.ExeSha256 = d.Substring(7).ToLowerInvariant();
                    }
                    break;
                }
            }
            return release;
        }

        /// <summary>"v1.2.3" or "1.2" to a version; null if it doesn't start with a number.</summary>
        public static Version ParseVersion(string tag)
        {
            if (string.IsNullOrEmpty(tag)) return null;
            string s = tag.TrimStart('v', 'V');
            int end = 0;
            while (end < s.Length && (char.IsDigit(s[end]) || s[end] == '.')) end++;
            var parts = s.Substring(0, end).Split(new[] { '.' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return null;
            var numbers = new int[3];
            for (int i = 0; i < parts.Length && i < 3; i++)
            {
                if (!int.TryParse(parts[i], out numbers[i])) return null;
            }
            return new Version(numbers[0], numbers[1], numbers[2]);
        }

        static Version Normalize(Version v)
        {
            return new Version(v.Major, v.Minor, Math.Max(0, v.Build));
        }

        void Download(Release release, string path)
        {
            var request = (HttpWebRequest)WebRequest.Create(release.ExeUrl);
            request.UserAgent = UserAgent;
            request.Timeout = 30000;
            request.ReadWriteTimeout = 30000;
            using (var response = (HttpWebResponse)request.GetResponse())
            using (var input = response.GetResponseStream())
            using (var output = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None))
            using (var sha = SHA256.Create())
            {
                long total = response.ContentLength, done = 0;
                var buffer = new byte[81920];
                int read;
                while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                {
                    output.Write(buffer, 0, read);
                    sha.TransformBlock(buffer, 0, read, null, 0);
                    done += read;
                    Downloading(release, total > 0 ? (int)(done * 100 / total) : -1);
                }
                sha.TransformFinalBlock(buffer, 0, 0);
                if (total > 0 && done != total) throw new UpdateException("The download stopped part way.");
                if (done < 1024) throw new UpdateException("The download is empty.");
                if (release.ExeSha256 != null && Config.Hex(sha.Hash) != release.ExeSha256)
                {
                    throw new UpdateException("The download doesn't match the release, so it wasn't used.");
                }
            }
        }

        /// <summary>Moves the running exe aside and the new one into its place, putting the old one back if that fails.</summary>
        static void Swap(string exe, string download)
        {
            string old = exe + ".old";
            if (File.Exists(old) && !TryDelete(old))
            {
                old = exe + "." + DateTime.UtcNow.Ticks + ".old"; // the last update's is still in use
            }
            File.Move(exe, old);
            try
            {
                File.Move(download, exe);
            }
            catch
            {
                File.Move(old, exe);
                throw;
            }
        }

        static bool TryDelete(string path)
        {
            try
            {
                if (File.Exists(path)) File.Delete(path);
                return true;
            }
            catch (IOException)
            {
                return false;
            }
            catch (UnauthorizedAccessException)
            {
                return false;
            }
        }

        static string Describe(Exception ex)
        {
            if (ex is UpdateException) return ex.Message;
            if (ex is UnauthorizedAccessException)
            {
                return "RomCon isn't allowed to change its own folder (" + Path.GetDirectoryName(Application.ExecutablePath) + ").";
            }
            if (ex is WebException web)
            {
                if (web.Response is HttpWebResponse http)
                {
                    if (http.StatusCode == HttpStatusCode.NotFound) return "There are no releases yet.";
                    if ((int)http.StatusCode == 403 || (int)http.StatusCode == 429) return "GitHub is busy. Try again later.";
                    return "GitHub answered " + (int)http.StatusCode + ".";
                }
                if (web.Status == WebExceptionStatus.NameResolutionFailure || web.Status == WebExceptionStatus.ConnectFailure) return "No internet connection.";
                if (web.Status == WebExceptionStatus.Timeout) return "GitHub didn't answer.";
            }
            return ex.Message;
        }

        static void Run(Action action)
        {
            new Thread(() => action()) { IsBackground = true, Name = "RomCon updates" }.Start();
        }
    }

    sealed class UpdateException : Exception
    {
        public UpdateException(string message) : base(message)
        {
        }
    }
}
