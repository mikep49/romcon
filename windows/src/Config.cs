using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace RomCon
{
    sealed class PairedDevice
    {
        public string Id;
        public string Name;
        public string TokenHash; // hex SHA-256 of the token the phone holds
        public DateTime PairedUtc;
        public DateTime LastSeenUtc;
    }

    /// <summary>Settings and paired phones, kept as plain text files in %APPDATA%\RomCon.</summary>
    sealed class Config
    {
        public readonly string Dir;
        public int Port = Proto.DefaultPort;
        public string ServerName = Environment.MachineName;
        public bool NotifyOnConnect = true;
        public bool AlwaysAdmin;
        public ThemeMode Theme = ThemeMode.System;

        readonly List<PairedDevice> devices = new List<PairedDevice>();
        readonly object gate = new object();

        string SettingsPath => Path.Combine(Dir, "settings.ini");
        string DevicesPath => Path.Combine(Dir, "devices.tsv");

        Config(string dir)
        {
            Dir = dir;
        }

        public static Config Load(string dir)
        {
            Directory.CreateDirectory(dir);
            var c = new Config(dir);
            if (File.Exists(c.SettingsPath))
            {
                foreach (string line in File.ReadAllLines(c.SettingsPath, Encoding.UTF8))
                {
                    int eq = line.IndexOf('=');
                    if (eq <= 0 || line.StartsWith("#")) continue;
                    string key = line.Substring(0, eq).Trim(), value = line.Substring(eq + 1).Trim();
                    switch (key)
                    {
                        case "port":
                            if (int.TryParse(value, out int p) && p > 0 && p < 65536) c.Port = p;
                            break;
                        case "name":
                            if (value.Length > 0) c.ServerName = value;
                            break;
                        case "notify":
                            c.NotifyOnConnect = value != "0" && !value.Equals("false", StringComparison.OrdinalIgnoreCase);
                            break;
                        case "admin":
                            c.AlwaysAdmin = value == "1" || value.Equals("true", StringComparison.OrdinalIgnoreCase);
                            break;
                        case "theme":
                            if (Enum.TryParse(value, true, out ThemeMode mode) && Enum.IsDefined(typeof(ThemeMode), mode)) c.Theme = mode;
                            break;
                    }
                }
            }
            if (File.Exists(c.DevicesPath))
            {
                foreach (string line in File.ReadAllLines(c.DevicesPath, Encoding.UTF8))
                {
                    string[] f = line.Split('\t');
                    if (f.Length < 5 || f[0].Length == 0) continue;
                    c.devices.Add(new PairedDevice
                    {
                        Id = f[0],
                        Name = f[1],
                        TokenHash = f[2],
                        PairedUtc = ParseDate(f[3]),
                        LastSeenUtc = ParseDate(f[4]),
                    });
                }
            }
            return c;
        }

        public void SaveSettings()
        {
            var sb = new StringBuilder();
            sb.AppendLine("# RomCon settings. Restart RomCon after editing by hand.");
            sb.AppendLine("port=" + Port);
            sb.AppendLine("name=" + ServerName);
            sb.AppendLine("notify=" + (NotifyOnConnect ? "1" : "0"));
            sb.AppendLine("theme=" + Theme.ToString().ToLowerInvariant());
            sb.AppendLine("admin=" + (AlwaysAdmin ? "1" : "0"));
            WriteAtomically(SettingsPath, sb.ToString());
        }

        public List<PairedDevice> Devices
        {
            get { lock (gate) return devices.Select(Clone).ToList(); }
        }

        public bool HasDevices
        {
            get { lock (gate) return devices.Count > 0; }
        }

        /// <summary>Checks a phone's token. On success updates its name and last-seen time.</summary>
        public bool CheckToken(string deviceId, string deviceName, string token)
        {
            lock (gate)
            {
                var d = devices.FirstOrDefault(x => x.Id == deviceId);
                if (d == null || string.IsNullOrEmpty(token)) return false;
                if (!FixedTimeEquals(d.TokenHash, HashToken(token))) return false;
                if (!string.IsNullOrWhiteSpace(deviceName)) d.Name = Clean(deviceName);
                d.LastSeenUtc = DateTime.UtcNow;
                SaveDevicesLocked();
                return true;
            }
        }

        /// <summary>Registers a phone and returns the new secret token it must keep.</summary>
        public string AddDevice(string deviceId, string deviceName)
        {
            byte[] raw = new byte[32];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(raw);
            string token = Convert.ToBase64String(raw);
            lock (gate)
            {
                devices.RemoveAll(x => x.Id == deviceId);
                devices.Add(new PairedDevice
                {
                    Id = Clean(deviceId),
                    Name = Clean(string.IsNullOrWhiteSpace(deviceName) ? "Phone" : deviceName),
                    TokenHash = HashToken(token),
                    PairedUtc = DateTime.UtcNow,
                    LastSeenUtc = DateTime.UtcNow,
                });
                SaveDevicesLocked();
            }
            return token;
        }

        public void RemoveDevice(string deviceId)
        {
            lock (gate)
            {
                devices.RemoveAll(x => x.Id == deviceId);
                SaveDevicesLocked();
            }
        }

        void SaveDevicesLocked()
        {
            var sb = new StringBuilder();
            foreach (var d in devices)
            {
                sb.Append(d.Id).Append('\t').Append(d.Name).Append('\t').Append(d.TokenHash).Append('\t')
                  .Append(d.PairedUtc.ToString("o", CultureInfo.InvariantCulture)).Append('\t')
                  .Append(d.LastSeenUtc.ToString("o", CultureInfo.InvariantCulture)).Append("\r\n");
            }
            try
            {
                WriteAtomically(DevicesPath, sb.ToString());
            }
            catch (Exception ex)
            {
                Log.Info("Couldn't save paired phones: " + ex.Message);
            }
        }

        static string HashToken(string token)
        {
            using (var sha = SHA256.Create())
            {
                return Hex(sha.ComputeHash(Encoding.UTF8.GetBytes(token)));
            }
        }

        public static string Hex(byte[] bytes)
        {
            var sb = new StringBuilder(bytes.Length * 2);
            foreach (byte b in bytes) sb.Append(b.ToString("x2"));
            return sb.ToString();
        }

        static bool FixedTimeEquals(string a, string b)
        {
            if (a == null || b == null || a.Length != b.Length) return false;
            int diff = 0;
            for (int i = 0; i < a.Length; i++) diff |= a[i] ^ b[i];
            return diff == 0;
        }

        static string Clean(string s)
        {
            s = (s ?? "").Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ').Trim();
            return s.Length > 64 ? s.Substring(0, 64) : s;
        }

        static DateTime ParseDate(string s)
        {
            return DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out DateTime d) ? d : DateTime.MinValue;
        }

        static PairedDevice Clone(PairedDevice d)
        {
            return new PairedDevice { Id = d.Id, Name = d.Name, TokenHash = d.TokenHash, PairedUtc = d.PairedUtc, LastSeenUtc = d.LastSeenUtc };
        }

        static void WriteAtomically(string path, string content)
        {
            string tmp = path + ".tmp";
            File.WriteAllText(tmp, content, new UTF8Encoding(false));
            if (File.Exists(path)) File.Replace(tmp, path, null);
            else File.Move(tmp, path);
        }
    }
}
