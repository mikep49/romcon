using System;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace RomCon
{
    /// <summary>
    /// The UDP side of one phone's connection. The phone sends pointer movement and scrolling here as
    /// AES-256-GCM datagrams, sealed with a key the PC handed it over TLS.
    /// </summary>
    sealed class PointerChannel : IDisposable
    {
        public readonly uint Id;
        public readonly byte[] Secret;
        public bool Seen; // a valid datagram has arrived; only the UDP thread touches it

        readonly object gate = new object();
        IntPtr key;

        // Replay window: bit i is set once counter (highest - i) has been accepted.
        ulong highest;
        ulong window;

        // The highest counter whose datagram has been dealt with, for a TCP thread waiting in WaitFor.
        readonly object arrivals = new object();
        ulong processed;

        public PointerChannel(uint id)
        {
            Id = id;
            Secret = new byte[Proto.UdpKeySize];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(Secret);
            Gcm.Check(Gcm.BCryptGenerateSymmetricKey(Gcm.Algorithm, out key, IntPtr.Zero, 0, Secret, Secret.Length, 0));
        }

        /// <summary>
        /// Checks and decrypts one datagram. Returns null for anything forged, damaged or already seen.
        /// Datagrams may arrive out of order, so a counter is accepted once anywhere in the last 64.
        /// </summary>
        public byte[] Open(byte[] packet, out ulong counter)
        {
            counter = 0;
            int length = packet.Length - Proto.UdpNonceSize - Proto.UdpTagSize;
            if (length < 1) return null;
            for (int i = 4; i < Proto.UdpNonceSize; i++) counter = (counter << 8) | packet[i];
            if (counter == 0) return null;
            ulong behind = counter > highest ? 0 : highest - counter;
            if (counter <= highest && (behind >= 64 || (window & (1UL << (int)behind)) != 0)) return null;

            var plain = new byte[length];
            GCHandle pin = GCHandle.Alloc(packet, GCHandleType.Pinned);
            try
            {
                var info = new Gcm.AuthInfo
                {
                    cbSize = Marshal.SizeOf(typeof(Gcm.AuthInfo)),
                    dwInfoVersion = 1,
                    pbNonce = Marshal.UnsafeAddrOfPinnedArrayElement(packet, 0),
                    cbNonce = Proto.UdpNonceSize,
                    pbTag = Marshal.UnsafeAddrOfPinnedArrayElement(packet, packet.Length - Proto.UdpTagSize),
                    cbTag = Proto.UdpTagSize,
                };
                lock (gate)
                {
                    if (key == IntPtr.Zero) return null;
                    int status = Gcm.BCryptDecrypt(key, Marshal.UnsafeAddrOfPinnedArrayElement(packet, Proto.UdpNonceSize), length,
                        ref info, IntPtr.Zero, 0, plain, plain.Length, out int written, 0);
                    if (status != 0 || written != length) return null;
                }
            }
            finally
            {
                pin.Free();
            }

            if (counter > highest)
            {
                ulong ahead = counter - highest;
                window = ahead >= 64 ? 0 : window << (int)ahead;
                window |= 1;
                highest = counter;
            }
            else
            {
                window |= 1UL << (int)behind;
            }
            return plain;
        }

        /// <summary>The UDP thread calls this once the datagram with this counter has been applied or queued.</summary>
        public void Processed(ulong counter)
        {
            lock (arrivals)
            {
                if (counter > processed) processed = counter;
                Monitor.PulseAll(arrivals);
            }
        }

        /// <summary>
        /// Waits a moment for the datagram with this counter, or a later one. The phone sent it before the TCP
        /// frame now being handled, but the two travel separately and either can get here first. A datagram
        /// that isn't here by the timeout is taken as lost.
        /// </summary>
        public void WaitFor(ulong counter, int timeoutMs)
        {
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            lock (arrivals)
            {
                while (processed < counter)
                {
                    int left = (int)(deadline - DateTime.UtcNow).TotalMilliseconds;
                    if (left <= 0 || !Monitor.Wait(arrivals, left)) return;
                }
            }
        }

        public void Dispose()
        {
            lock (gate)
            {
                if (key != IntPtr.Zero) Gcm.BCryptDestroyKey(key);
                key = IntPtr.Zero;
            }
        }
    }

    /// <summary>AES-GCM from Windows CNG. The .NET Framework has no managed AES-GCM.</summary>
    static class Gcm
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct AuthInfo
        {
            public int cbSize;
            public int dwInfoVersion;
            public IntPtr pbNonce;
            public int cbNonce;
            public IntPtr pbAuthData;
            public int cbAuthData;
            public IntPtr pbTag;
            public int cbTag;
            public IntPtr pbMacContext;
            public int cbMacContext;
            public int cbAAD;
            public long cbData;
            public int dwFlags;
        }

        public static IntPtr Algorithm { get; private set; }

        /// <summary>Opens the AES-GCM provider. Returns false, with the reason, where Windows can't supply it.</summary>
        public static bool TryInit(out string error)
        {
            error = null;
            if (Algorithm != IntPtr.Zero) return true;
            try
            {
                Check(BCryptOpenAlgorithmProvider(out IntPtr handle, "AES", null, 0));
                byte[] mode = Encoding.Unicode.GetBytes("ChainingModeGCM\0");
                Check(BCryptSetProperty(handle, "ChainingMode", mode, mode.Length, 0));
                Algorithm = handle;
                return true;
            }
            catch (Exception ex)
            {
                error = ex.Message;
                return false;
            }
        }

        public static void Check(int status)
        {
            if (status != 0) throw new CryptographicException("Windows cryptography error 0x" + status.ToString("X8"));
        }

        [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
        static extern int BCryptOpenAlgorithmProvider(out IntPtr algorithm, string algorithmId, string implementation, uint flags);

        [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
        static extern int BCryptSetProperty(IntPtr handle, string property, byte[] input, int inputSize, uint flags);

        [DllImport("bcrypt.dll")]
        public static extern int BCryptGenerateSymmetricKey(IntPtr algorithm, out IntPtr key, IntPtr keyObject, int keyObjectSize, byte[] secret, int secretSize, uint flags);

        [DllImport("bcrypt.dll")]
        public static extern int BCryptDecrypt(IntPtr key, IntPtr input, int inputSize, ref AuthInfo info, IntPtr iv, int ivSize, byte[] output, int outputSize, out int written, uint flags);

        [DllImport("bcrypt.dll")]
        public static extern int BCryptDestroyKey(IntPtr key);
    }
}
