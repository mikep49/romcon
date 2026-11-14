using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace RomCon
{
    static class Log
    {
        const int MaxLines = 300;
        static readonly object gate = new object();
        static readonly Queue<string> lines = new Queue<string>();
        static string path;

        public static event Action<string> Line;

        public static void Init(string dir)
        {
            path = Path.Combine(dir, "romcon.log");
            try
            {
                var info = new FileInfo(path);
                if (info.Exists && info.Length > 512 * 1024) File.Delete(path);
            }
            catch { }
        }

        public static void Info(string message)
        {
            string line = DateTime.Now.ToString("HH:mm:ss") + "  " + message;
            lock (gate)
            {
                lines.Enqueue(line);
                while (lines.Count > MaxLines) lines.Dequeue();
                if (path != null)
                {
                    try { File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd ") + line + "\r\n"); } catch { }
                }
            }
            Line?.Invoke(line);
        }

        public static string[] Snapshot()
        {
            lock (gate) return lines.ToArray();
        }
    }

    /// <summary>The PC's TLS identity: a self-signed certificate made on first run and kept encrypted with DPAPI.</summary>
    static class Identity
    {
        static readonly byte[] Entropy = Encoding.UTF8.GetBytes("RomCon identity v1");
        const string PfxPassword = "romcon";

        public static X509Certificate2 LoadOrCreate(string dir)
        {
            string file = Path.Combine(dir, "identity.bin");
            if (File.Exists(file))
            {
                try
                {
                    byte[] pfx = ProtectedData.Unprotect(File.ReadAllBytes(file), Entropy, DataProtectionScope.CurrentUser);
                    return new X509Certificate2(pfx, PfxPassword, X509KeyStorageFlags.UserKeySet);
                }
                catch (Exception ex)
                {
                    Log.Info("Couldn't read the saved identity (" + ex.Message + "); making a new one. Phones will need to pair again.");
                }
            }

            byte[] created;
            using (RSA rsa = RSA.Create(2048))
            {
                var request = new CertificateRequest("CN=RomCon " + Environment.MachineName, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                using (var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(30)))
                {
                    created = cert.Export(X509ContentType.Pfx, PfxPassword);
                }
            }
            File.WriteAllBytes(file, ProtectedData.Protect(created, Entropy, DataProtectionScope.CurrentUser));
            Log.Info("Created a new identity for this PC.");
            return new X509Certificate2(created, PfxPassword, X509KeyStorageFlags.UserKeySet);
        }

        public static string Fingerprint(X509Certificate2 cert)
        {
            using (var sha = SHA256.Create()) return Config.Hex(sha.ComputeHash(cert.RawData));
        }

        /// <summary>Short code shown on both screens while pairing so the user can spot a man in the middle.</summary>
        public static string VerifyCode(string fingerprint)
        {
            string s = fingerprint.Substring(0, 8).ToUpperInvariant();
            return s.Substring(0, 4) + " " + s.Substring(4, 4);
        }
    }

    /// <summary>A short-lived PIN the user opens from the PC; the only way a new phone can pair.</summary>
    sealed class Pairing
    {
        public enum Result { Ok, Closed, WrongPin, TooManyAttempts }

        const int MaxFailures = 5;
        readonly object gate = new object();
        string pin;
        DateTime expiresUtc;
        int failures;

        public event Action Changed;

        public string Pin
        {
            get { lock (gate) return Active ? pin : null; }
        }

        public DateTime ExpiresUtc
        {
            get { lock (gate) return expiresUtc; }
        }

        bool Active => pin != null && DateTime.UtcNow < expiresUtc;

        public bool IsActive
        {
            get { lock (gate) return Active; }
        }

        public void Start(TimeSpan duration)
        {
            lock (gate)
            {
                pin = NewPin();
                expiresUtc = DateTime.UtcNow + duration;
                failures = 0;
            }
            Changed?.Invoke();
        }

        public void Stop()
        {
            lock (gate) pin = null;
            Changed?.Invoke();
        }

        public Result TryConsume(string attempt)
        {
            Result result;
            lock (gate)
            {
                if (!Active)
                {
                    result = Result.Closed;
                }
                else if (attempt != null && attempt.Trim() == pin)
                {
                    pin = null;
                    result = Result.Ok;
                }
                else if (++failures >= MaxFailures)
                {
                    pin = null;
                    result = Result.TooManyAttempts;
                }
                else
                {
                    result = Result.WrongPin;
                }
            }
            if (result != Result.WrongPin) Changed?.Invoke();
            return result;
        }

        static string NewPin()
        {
            var bytes = new byte[4];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(bytes);
            uint n = BitConverter.ToUInt32(bytes, 0) % 1000000;
            return n.ToString("D6");
        }
    }

    sealed class LocalAddress
    {
        public string Label;
        public string Address;
        public bool IsTailscale;
    }

    static class NetInfo
    {
        /// <summary>IPv4 addresses a phone could reach: adapters with a gateway (Wi-Fi, Ethernet) plus Tailscale.</summary>
        public static List<LocalAddress> Addresses()
        {
            var list = new List<LocalAddress>();
            NetworkInterface[] interfaces;
            try { interfaces = NetworkInterface.GetAllNetworkInterfaces(); }
            catch { return list; }

            foreach (var ni in interfaces)
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                IPInterfaceProperties props;
                try { props = ni.GetIPProperties(); } catch { continue; }
                bool hasGateway = false;
                foreach (var g in props.GatewayAddresses)
                {
                    if (g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)) hasGateway = true;
                }
                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    byte[] b = ua.Address.GetAddressBytes();
                    if (b[0] == 169 && b[1] == 254) continue;
                    bool tailscale = (b[0] == 100 && (b[1] & 0xC0) == 64) || ni.Description.IndexOf("Tailscale", StringComparison.OrdinalIgnoreCase) >= 0;
                    if (!tailscale && !hasGateway) continue;
                    list.Add(new LocalAddress
                    {
                        Label = tailscale ? "Tailscale" : (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? "Wi-Fi" : ni.Name),
                        Address = ua.Address.ToString(),
                        IsTailscale = tailscale,
                    });
                }
            }
            list.Sort((x, y) => x.IsTailscale.CompareTo(y.IsTailscale));
            return list;
        }
    }
}
