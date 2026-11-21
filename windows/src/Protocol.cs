using System;
using System.IO;
using System.Text;

namespace RomCon
{
    /// <summary>Wire constants shared with the Android app (see docs/protocol.md).</summary>
    static class Proto
    {
        public const byte Version = 1;
        public const int DefaultPort = 50505;
        public const int DiscoveryPort = 50506;

        /// <summary>
        /// Where RomCon listens when its own port is taken, tried in this order. Windows lends blocks of ports from 49152 up to
        /// Hyper-V, WSL and Docker, a new set at every start, and those can land on the default port; these sit below that.
        /// The phone tries the same ports when a PC doesn't answer on the one it saved.
        /// </summary>
        public static readonly int[] FallbackPorts = { 45505, 46505, 47505 };
        public const string DiscoveryRequest = "ROMCON?1";
        public const string DiscoveryReply = "ROMCON!1";
        public const int MaxFrame = 8192;

        // Phone -> PC
        public const byte Hello = 0x01;
        public const byte Ping = 0x02;
        public const byte UdpRequest = 0x03;
        public const byte UdpSync = 0x04;
        public const byte MouseMove = 0x10;
        public const byte MouseButton = 0x11;
        public const byte Scroll = 0x12;
        public const byte Text = 0x20;
        public const byte Key = 0x21;
        public const byte CharWithMods = 0x22;
        public const byte Command = 0x30;
        public const byte SwitchDesktop = 0x31;
        public const byte MediaCommand = 0x40;

        // PC -> phone
        public const byte Welcome = 0x81;
        public const byte Rejected = 0x82;
        public const byte Pong = 0x83;
        public const byte Status = 0x84;
        public const byte UdpOffer = 0x85;
        public const byte UdpPong = 0x86;
        public const byte Media = 0x87;
        public const byte MediaArt = 0x88;
        public const byte Desktops = 0x89;

        // A datagram is [u32 channel id][u64 counter][AES-256-GCM ciphertext][16-byte tag].
        public const int UdpKeySize = 32;
        public const int UdpNonceSize = 12;
        public const int UdpTagSize = 16;

        public const byte AuthToken = 0;
        public const byte AuthPin = 1;

        // What a connection is for: controlling the PC, or only showing its media controls on the phone.
        public const byte RoleControl = 0;
        public const byte RoleMedia = 1;

        // WELCOME feature bits.
        public const byte FeatureMedia = 1;

        public const byte RejectVersion = 1;
        public const byte RejectNotPaired = 2;
        public const byte RejectWrongPin = 3;
        public const byte RejectPairingClosed = 4;
        public const byte RejectTooManyAttempts = 5;

        public const byte KeyUp = 0;
        public const byte KeyDown = 1;
        public const byte KeyTap = 2;

        public const int ModCtrl = 1;
        public const int ModAlt = 2;
        public const int ModShift = 4;
        public const int ModWin = 8;

        public const byte CmdLock = 1;

        public const byte MediaNone = 0;
        public const byte MediaPlaying = 1;
        public const byte MediaPaused = 2;

        public const int CanPlay = 1;
        public const int CanPause = 2;
        public const int CanNext = 4;
        public const int CanPrevious = 8;
        public const int CanSeek = 16;
        public const int CanVolume = 32;

        public const byte MediaPlay = 1;
        public const byte MediaPause = 2;
        public const byte MediaNext = 3;
        public const byte MediaPrevious = 4;
        public const byte MediaSeekTo = 5;
        public const byte MediaSetVolume = 6;
        public const byte MediaAdjustVolume = 7;

        // Artwork crosses in MEDIA_ART frames of at most this much picture each.
        public const int ArtChunk = 8000;
        public const int MaxArtBytes = 256 * 1024;

        public const int ButtonLeft = 0;
        public const int ButtonRight = 1;
        public const int ButtonMiddle = 2;
        public const int ButtonBack = 3;
        public const int ButtonForward = 4;
    }

    /// <summary>One received frame: a type byte and a big-endian payload read front to back.</summary>
    sealed class Frame
    {
        public readonly byte Type;
        readonly byte[] data;
        int pos;

        public Frame(byte type, byte[] data)
        {
            Type = type;
            this.data = data;
        }

        /// <summary>True while there is payload left to read. Newer senders append fields that older ones leave out.</summary>
        public bool HasMore => pos < data.Length;

        public byte U8()
        {
            if (pos >= data.Length) throw new InvalidDataException("Frame too short");
            return data[pos++];
        }

        public ushort U16() => (ushort)((U8() << 8) | U8());

        public short I16() => (short)U16();

        public uint U32() => ((uint)U16() << 16) | U16();

        public ulong U64() => ((ulong)U32() << 32) | U32();

        public string Str()
        {
            int n = U16();
            if (pos + n > data.Length) throw new InvalidDataException("String runs past the frame");
            string s = Encoding.UTF8.GetString(data, pos, n);
            pos += n;
            return s;
        }
    }

    /// <summary>Frames are [u16 length][u8 type][payload], where length counts the type byte and payload.</summary>
    static class FrameIO
    {
        /// <summary>Returns null on a clean end of stream between frames.</summary>
        public static Frame Read(Stream s)
        {
            var header = new byte[2];
            if (!ReadExactly(s, header, 2, true)) return null;
            int len = (header[0] << 8) | header[1];
            if (len < 1 || len > Proto.MaxFrame) throw new InvalidDataException("Bad frame length " + len);
            var body = new byte[len];
            ReadExactly(s, body, len, false);
            var payload = new byte[len - 1];
            Buffer.BlockCopy(body, 1, payload, 0, len - 1);
            return new Frame(body[0], payload);
        }

        static bool ReadExactly(Stream s, byte[] buffer, int count, bool eofAllowed)
        {
            int offset = 0;
            while (offset < count)
            {
                int read = s.Read(buffer, offset, count - offset);
                if (read <= 0)
                {
                    if (offset == 0 && eofAllowed) return false;
                    throw new EndOfStreamException();
                }
                offset += read;
            }
            return true;
        }
    }

    sealed class FrameBuilder
    {
        readonly MemoryStream ms = new MemoryStream(32);

        public FrameBuilder(byte type)
        {
            ms.WriteByte(0);
            ms.WriteByte(0);
            ms.WriteByte(type);
        }

        public FrameBuilder U8(int v)
        {
            ms.WriteByte((byte)v);
            return this;
        }

        public FrameBuilder U16(int v)
        {
            ms.WriteByte((byte)(v >> 8));
            ms.WriteByte((byte)v);
            return this;
        }

        public FrameBuilder U32(uint v)
        {
            U16((int)(v >> 16));
            U16((int)(v & 0xFFFF));
            return this;
        }

        public FrameBuilder Bytes(byte[] b)
        {
            ms.Write(b, 0, b.Length);
            return this;
        }

        public FrameBuilder Bytes(byte[] b, int offset, int count)
        {
            ms.Write(b, offset, count);
            return this;
        }

        public FrameBuilder Str(string s)
        {
            byte[] b = Encoding.UTF8.GetBytes(s ?? "");
            if (b.Length > 0xFFFF) throw new ArgumentException("String too long");
            U16(b.Length);
            ms.Write(b, 0, b.Length);
            return this;
        }

        public byte[] Build()
        {
            byte[] a = ms.ToArray();
            int len = a.Length - 2;
            a[0] = (byte)(len >> 8);
            a[1] = (byte)len;
            return a;
        }
    }
}
