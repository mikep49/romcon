using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;

namespace RomCon
{
    sealed class ClientSession
    {
        public readonly TcpClient Tcp;
        public readonly SslStream Stream;
        public readonly string Address;
        public readonly InputState Input;
        public string DeviceId;
        public string DeviceName;
        public DateTime ConnectedAt;
        readonly object writeLock = new object();

        /// <summary>A media link: the phone only shows this PC's media controls, and can send nothing but media commands.</summary>
        public bool MediaOnly;

        /// <summary>The artwork this phone already has.</summary>
        public uint SentArtId;

        /// <summary>Set once the phone asks to send pointer movement over UDP.</summary>
        public volatile PointerChannel Pointer;

        // Pointer movement that came by UDP ahead of a click or key still on its way over TCP waits here,
        // so input is applied in the order the phone sent it.
        sealed class Held
        {
            public uint After;
            public byte Type;
            public int A, B;
        }

        const int MaxHeld = 256;
        readonly object order = new object();
        readonly Queue<Held> held = new Queue<Held>();
        Held lastHeld;
        uint framesHandled;

        public ClientSession(TcpClient tcp, SslStream stream, string address, InputState input)
        {
            Tcp = tcp;
            Stream = stream;
            Address = address;
            Input = input;
        }

        public void Send(byte[] frame)
        {
            lock (writeLock)
            {
                try
                {
                    Stream.Write(frame, 0, frame.Length);
                    Stream.Flush();
                }
                catch
                {
                    Close();
                }
            }
        }

        public void Close()
        {
            try { Tcp.Close(); } catch { }
        }

        /// <summary>Call after each TCP frame is dealt with. Releases the UDP movement that was waiting for it.</summary>
        public void FrameHandled(Action<byte, int, int> apply)
        {
            lock (order)
            {
                framesHandled++;
                while (held.Count > 0 && held.Peek().After <= framesHandled)
                {
                    Held h = held.Dequeue();
                    apply(h.Type, h.A, h.B);
                }
            }
        }

        /// <summary>
        /// A movement or scroll from a datagram the phone sent after its first <paramref name="after"/> TCP frames.
        /// It's applied now if those frames have been handled, and otherwise as soon as they are.
        /// </summary>
        public void FromUdp(uint after, byte type, int a, int b, Action<byte, int, int> apply)
        {
            lock (order)
            {
                // Everything still held is waiting for a later frame than this, so nothing is overtaken.
                if (after <= framesHandled)
                {
                    apply(type, a, b);
                    return;
                }
                if (held.Count > 0)
                {
                    if (after < lastHeld.After) after = lastHeld.After; // a late datagram doesn't jump the queue
                    if (after == lastHeld.After && type == lastHeld.Type)
                    {
                        lastHeld.A += a;
                        lastHeld.B += b;
                        return;
                    }
                    if (held.Count >= MaxHeld) return;
                }
                lastHeld = new Held { After = after, Type = type, A = a, B = b };
                held.Enqueue(lastHeld);
            }
        }
    }

    /// <summary>
    /// Accepts phones over TLS, checks they are paired, and feeds their input to Windows.
    /// A phone may also send pointer movement over UDP on the same port number (see PointerChannel).
    /// Besides those control connections, a phone can keep a media link open in the background: the PC tells it
    /// what's playing, and it sends back play, pause, skip and volume from its lock screen.
    /// </summary>
    sealed class RemoteServer
    {
        const int HandshakeTimeoutMs = 15000;
        const int HelloTimeoutMs = 120000; // the phone may still be waiting for the user to type the PIN
        const int IdleTimeoutMs = 20000;   // phones ping every few seconds
        const int UdpSyncWaitMs = 40;      // how long a click or key waits for a datagram sent just before it
        const int MediaKeepAliveMs = 5 * 60 * 1000; // a sleeping phone doesn't ping, so TCP checks on it now and then

        readonly Config config;
        readonly X509Certificate2 certificate;
        readonly InputInjector injector;
        readonly MediaWatcher media;
        readonly List<ClientSession> sessions = new List<ClientSession>();
        readonly List<ClientSession> mediaLinks = new List<ClientSession>();
        readonly HashSet<string> mediaAnnounced = new HashSet<string>();
        readonly object mediaSend = new object();
        volatile bool asleep;
        readonly Dictionary<uint, ClientSession> pointerChannels = new Dictionary<uint, ClientSession>();
        TcpListener listener;
        UdpClient discovery;
        UdpClient pointerSocket;
        volatile bool running;
        volatile bool paused;

        public readonly Pairing Pairing = new Pairing();
        public readonly string Fingerprint;
        public readonly string VerifyCode;
        public string StartError { get; private set; }

        /// <summary>Raised on a background thread whenever sessions, pause state or pairing change.</summary>
        public event Action Changed;
        public event Action<ClientSession> Connected;

        public RemoteServer(Config config, X509Certificate2 certificate, InputInjector injector, MediaWatcher media)
        {
            this.config = config;
            this.certificate = certificate;
            this.injector = injector;
            this.media = media;
            Fingerprint = Identity.Fingerprint(certificate);
            VerifyCode = Identity.VerifyCode(Fingerprint);
            Pairing.Changed += () => Changed?.Invoke();
            media.Changed += PushMedia;
        }

        public int Port => config.Port;

        public bool Paused
        {
            get { return paused; }
            set
            {
                if (paused == value) return;
                paused = value;
                foreach (var s in Sessions)
                {
                    if (value) s.Input.ReleaseAll();
                    s.Send(new FrameBuilder(Proto.Status).U8(value ? 1 : 0).Build());
                }
                Log.Info(value ? "Remote control paused." : "Remote control resumed.");
                PushMedia();
                Changed?.Invoke();
            }
        }

        /// <summary>Set while the PC is going to sleep, so phones drop their media controls instead of showing stale ones.</summary>
        public bool Asleep
        {
            get { return asleep; }
            set
            {
                if (asleep == value) return;
                asleep = value;
                PushMedia();
                if (!value) media.Kick();
            }
        }

        public List<ClientSession> Sessions
        {
            get { lock (sessions) return sessions.ToList(); }
        }

        /// <summary>True while the phone has a media link open, ready to show this PC's media controls.</summary>
        public bool HasMediaLink(string deviceId)
        {
            lock (mediaLinks) return mediaLinks.Any(x => x.DeviceId == deviceId);
        }

        List<ClientSession> MediaLinks
        {
            get { lock (mediaLinks) return mediaLinks.ToList(); }
        }

        public void Start()
        {
            running = true;
            try
            {
                listener = new TcpListener(IPAddress.IPv6Any, config.Port);
                listener.Server.SetSocketOption(SocketOptionLevel.IPv6, SocketOptionName.IPv6Only, false);
                listener.Start();
            }
            catch (SocketException)
            {
                try
                {
                    listener = new TcpListener(IPAddress.Any, config.Port);
                    listener.Start();
                }
                catch (SocketException ex)
                {
                    StartError = "Port " + config.Port + " is unavailable: " + ex.Message;
                    Log.Info(StartError);
                    return;
                }
            }
            Log.Info("Listening on port " + config.Port + ".");
            new Thread(AcceptLoop) { IsBackground = true, Name = "accept" }.Start();
            StartPointerSocket();
            StartDiscovery();
        }

        public void Stop()
        {
            running = false;
            try { listener?.Stop(); } catch { }
            try { discovery?.Close(); } catch { }
            try { pointerSocket?.Close(); } catch { }
            foreach (var s in Sessions.Concat(MediaLinks)) s.Close();
        }

        public void Disconnect(string deviceId)
        {
            foreach (var s in Sessions.Concat(MediaLinks).Where(x => x.DeviceId == deviceId)) s.Close();
        }

        void AcceptLoop()
        {
            while (running)
            {
                TcpClient client;
                try
                {
                    client = listener.AcceptTcpClient();
                }
                catch (Exception)
                {
                    if (!running) return;
                    Thread.Sleep(200);
                    continue;
                }
                new Thread(() => Serve(client)) { IsBackground = true, Name = "client" }.Start();
            }
        }

        void Serve(TcpClient tcp)
        {
            string address = "?";
            ClientSession session = null;
            try
            {
                var remote = (IPEndPoint)tcp.Client.RemoteEndPoint;
                address = remote.Address.IsIPv4MappedToIPv6 ? remote.Address.MapToIPv4().ToString() : remote.Address.ToString();
                tcp.NoDelay = true;
                tcp.SendTimeout = 5000;
                tcp.ReceiveTimeout = HandshakeTimeoutMs;

                using (tcp)
                using (var ssl = new SslStream(tcp.GetStream(), false))
                {
                    ssl.AuthenticateAsServer(certificate, false, SslProtocols.Tls12, false);
                    tcp.ReceiveTimeout = HelloTimeoutMs;

                    Frame hello = FrameIO.Read(ssl);
                    if (hello == null) return; // the phone only looked at our certificate
                    if (hello.Type != Proto.Hello) throw new InvalidDataException("Expected hello");

                    int version = hello.U8();
                    string deviceId = hello.Str();
                    string deviceName = hello.Str();
                    int mode = hello.U8();
                    string secret = hello.Str();
                    int role = hello.HasMore ? hello.U8() : Proto.RoleControl; // phones from before media links send no role

                    string token = "";
                    if (version != Proto.Version)
                    {
                        Reject(ssl, Proto.RejectVersion, "This app and the PC run different RomCon versions. Update both.");
                        return;
                    }
                    if (mode == Proto.AuthToken)
                    {
                        if (!config.CheckToken(deviceId, deviceName, secret))
                        {
                            Log.Info("Refused " + deviceName + " (" + address + "): not paired.");
                            Reject(ssl, Proto.RejectNotPaired, "This phone isn't paired with " + config.ServerName + ".");
                            return;
                        }
                    }
                    else if (mode == Proto.AuthPin)
                    {
                        switch (Pairing.TryConsume(secret))
                        {
                            case Pairing.Result.Ok:
                                token = config.AddDevice(deviceId, deviceName);
                                Log.Info("Paired with " + deviceName + " (" + address + ").");
                                break;
                            case Pairing.Result.Closed:
                                Reject(ssl, Proto.RejectPairingClosed, "Pairing isn't open. Click \"Pair a new phone\" in RomCon on the PC.");
                                return;
                            case Pairing.Result.TooManyAttempts:
                                Log.Info("Pairing closed after too many wrong PINs (last from " + address + ").");
                                Reject(ssl, Proto.RejectTooManyAttempts, "Too many wrong PINs. Start pairing again on the PC.");
                                return;
                            default:
                                Log.Info("Wrong pairing PIN from " + address + ".");
                                Reject(ssl, Proto.RejectWrongPin, "Wrong PIN.");
                                return;
                        }
                    }
                    else
                    {
                        throw new InvalidDataException("Unknown auth mode " + mode);
                    }

                    session = new ClientSession(tcp, ssl, address, new InputState(injector))
                    {
                        DeviceId = deviceId,
                        DeviceName = deviceName,
                        ConnectedAt = DateTime.Now,
                        // A phone pairing with a PIN is at the app's pairing screen, about to control the PC.
                        MediaOnly = role == Proto.RoleMedia && mode == Proto.AuthToken,
                    };
                    session.Send(new FrameBuilder(Proto.Welcome).Str(config.ServerName).Str(token).U8(Proto.FeatureMedia).Build());
                    if (session.MediaOnly)
                    {
                        BeginMediaLink(session);
                        tcp.ReceiveTimeout = 0; // the phone sleeps between pings
                    }
                    else
                    {
                        session.Send(new FrameBuilder(Proto.Status).U8(paused ? 1 : 0).Build());
                        lock (sessions) sessions.Add(session);
                        Log.Info(deviceName + " connected from " + address + ".");
                        Connected?.Invoke(session);
                        tcp.ReceiveTimeout = IdleTimeoutMs;
                    }
                    Changed?.Invoke();

                    Frame frame;
                    while ((frame = FrameIO.Read(ssl)) != null)
                    {
                        Dispatch(session, frame);
                        session.FrameHandled(ApplyPointer);
                    }
                }
            }
            catch (AuthenticationException ex)
            {
                // Usually a phone that paired with an earlier identity of this PC and now refuses the new one.
                Log.Info("Secure connection with " + address + " failed (" + ex.Message + "). If that phone paired before, pair it again.");
            }
            catch (IOException)
            {
                // Dropped connection, timeout, or a phone that only checked our certificate.
            }
            catch (ObjectDisposedException)
            {
            }
            catch (Exception ex)
            {
                Log.Info("Connection from " + address + " ended: " + ex.Message);
            }
            finally
            {
                tcp.Close();
                if (session != null && session.MediaOnly)
                {
                    lock (mediaLinks) mediaLinks.Remove(session);
                    media.RemoveWatcher();
                    Changed?.Invoke();
                }
                else if (session != null)
                {
                    PointerChannel channel = session.Pointer;
                    if (channel != null)
                    {
                        lock (pointerChannels) pointerChannels.Remove(channel.Id);
                        channel.Dispose();
                    }
                    session.Input.ReleaseAll();
                    lock (sessions) sessions.Remove(session);
                    Log.Info(session.DeviceName + " disconnected.");
                    Changed?.Invoke();
                }
            }
        }

        static void Reject(SslStream ssl, byte code, string message)
        {
            byte[] f = new FrameBuilder(Proto.Rejected).U8(code).Str(message).Build();
            ssl.Write(f, 0, f.Length);
            ssl.Flush();
        }

        void Dispatch(ClientSession s, Frame f)
        {
            if (f.Type == Proto.Ping)
            {
                s.Send(new FrameBuilder(Proto.Pong).Build());
                return;
            }
            if (s.MediaOnly)
            {
                // Nothing else is accepted on a media link, so a phone in a pocket can never move the pointer or type.
                if (f.Type == Proto.MediaCommand && !paused)
                {
                    byte command = f.U8();
                    media.Command(command, unchecked((int)f.U32()));
                }
                return;
            }
            if (f.Type == Proto.UdpRequest)
            {
                OfferPointerChannel(s);
                return;
            }
            if (f.Type == Proto.UdpSync)
            {
                // Movement the phone sent by UDP before its next frame here should be applied before that frame.
                s.Pointer?.WaitFor(f.U64(), UdpSyncWaitMs);
                return;
            }
            if (paused) return;

            switch (f.Type)
            {
                case Proto.MouseMove:
                case Proto.Scroll:
                    ApplyPointer(f.Type, f.I16(), f.I16());
                    break;
                case Proto.MouseButton:
                    {
                        int button = f.U8();
                        s.Input.Button(button, f.U8() != 0);
                        break;
                    }
                case Proto.Text:
                    injector.Text(f.Str());
                    break;
                case Proto.Key:
                    {
                        ushort vk = f.U16();
                        int action = f.U8();
                        int mods = f.U8();
                        if (vk == 0) break;
                        if (action == Proto.KeyTap) injector.Tap(vk, mods);
                        else s.Input.Key(vk, action == Proto.KeyDown);
                        break;
                    }
                case Proto.CharWithMods:
                    {
                        int mods = f.U8();
                        injector.CharWithMods((char)f.U16(), mods);
                        break;
                    }
                case Proto.Command:
                    if (f.U8() == Proto.CmdLock) injector.LockPc();
                    break;
                // Unknown types are ignored so newer phones can talk to older PCs.
            }
        }

        void ApplyPointer(byte type, int a, int b)
        {
            if (paused) return;
            if (type == Proto.MouseMove) injector.Move(a, b);
            else injector.Wheel(a, b);
        }

        // ---------- Media links ----------

        /// <summary>What phones should show now: nothing while remote control is paused or the PC is going to sleep.</summary>
        MediaSnapshot MediaNow => paused || asleep ? MediaSnapshot.None : media.Current;

        void BeginMediaLink(ClientSession s)
        {
            media.AddWatcher(); // first, so that whatever happens next, the end of the connection takes it away again
            List<ClientSession> older;
            lock (mediaLinks)
            {
                older = mediaLinks.Where(x => x.DeviceId == s.DeviceId).ToList();
                mediaLinks.Add(s);
            }
            // A phone keeps one media link. An older one is a connection it has given up on without our hearing of it.
            foreach (var o in older) o.Close();

            try
            {
                var values = new byte[12];
                BitConverter.GetBytes(1u).CopyTo(values, 0);
                BitConverter.GetBytes((uint)MediaKeepAliveMs).CopyTo(values, 4);
                BitConverter.GetBytes(10000u).CopyTo(values, 8);
                s.Tcp.Client.IOControl(IOControlCode.KeepAliveValues, values, null);
            }
            catch (Exception)
            {
            }

            bool first;
            lock (mediaAnnounced) first = mediaAnnounced.Add(s.DeviceId);
            if (first) Log.Info(s.DeviceName + " is ready to show this PC's media controls.");
            lock (mediaSend) SendMedia(s, MediaNow);
        }

        void PushMedia()
        {
            // One at a time and always the latest, so a phone never ends up showing an older state than the PC's.
            lock (mediaSend)
            {
                MediaSnapshot now = MediaNow;
                foreach (var s in MediaLinks) SendMedia(s, now);
            }
        }

        void SendMedia(ClientSession s, MediaSnapshot m)
        {
            s.Send(new FrameBuilder(Proto.Media)
                .U8(m.State).U8(m.Can).U8(m.Volume).U8(m.Muted ? 1 : 0)
                .U32((uint)m.PositionAt(media.NowMs)).U32((uint)m.DurationMs).U16(m.RatePercent)
                .U32(m.ArtId).Str(m.Title).Str(m.Artist).Build());
            if (m.ArtId == 0 || m.Art == null || s.SentArtId == m.ArtId) return;
            s.SentArtId = m.ArtId;
            int count = (m.Art.Length + Proto.ArtChunk - 1) / Proto.ArtChunk;
            for (int i = 0; i < count; i++)
            {
                int offset = i * Proto.ArtChunk;
                s.Send(new FrameBuilder(Proto.MediaArt).U32(m.ArtId).U16(i).U16(count)
                    .Bytes(m.Art, offset, Math.Min(Proto.ArtChunk, m.Art.Length - offset)).Build());
            }
        }

        // ---------- Pointer movement over UDP ----------

        void StartPointerSocket()
        {
            if (!Gcm.TryInit(out string error))
            {
                Log.Info("UDP is off (" + error + "). Phones will use TCP for everything.");
                return;
            }
            try
            {
                pointerSocket = BindPointerSocket(AddressFamily.InterNetworkV6);
            }
            catch (SocketException)
            {
                try
                {
                    pointerSocket = BindPointerSocket(AddressFamily.InterNetwork);
                }
                catch (SocketException ex)
                {
                    Log.Info("UDP is off (UDP port " + config.Port + " unavailable: " + ex.Message + "). Phones will use TCP for everything.");
                    return;
                }
            }
            new Thread(PointerLoop) { IsBackground = true, Name = "pointer" }.Start();
        }

        UdpClient BindPointerSocket(AddressFamily family)
        {
            var udp = new UdpClient(family);
            try
            {
                bool v6 = family == AddressFamily.InterNetworkV6;
                if (v6) udp.Client.SetSocketOption(SocketOptionLevel.IPv6, SocketOptionName.IPv6Only, false);
                // Stop Windows from failing the next Receive when a phone has gone away.
                const int SIO_UDP_CONNRESET = -1744830452;
                udp.Client.IOControl(SIO_UDP_CONNRESET, new byte[] { 0 }, null);
                udp.Client.Bind(new IPEndPoint(v6 ? IPAddress.IPv6Any : IPAddress.Any, config.Port));
                return udp;
            }
            catch
            {
                udp.Close();
                throw;
            }
        }

        /// <summary>Answers a phone that wants to send pointer movement over UDP with a channel id and key.</summary>
        void OfferPointerChannel(ClientSession s)
        {
            if (pointerSocket == null) return; // no answer: the phone keeps using TCP
            PointerChannel channel = s.Pointer;
            if (channel == null)
            {
                var id = new byte[4];
                using (var rng = RandomNumberGenerator.Create())
                {
                    lock (pointerChannels)
                    {
                        uint n;
                        do
                        {
                            rng.GetBytes(id);
                            n = BitConverter.ToUInt32(id, 0);
                        }
                        while (n == 0 || pointerChannels.ContainsKey(n));
                        channel = new PointerChannel(n);
                        s.Pointer = channel;
                        pointerChannels[n] = s;
                    }
                }
            }
            s.Send(new FrameBuilder(Proto.UdpOffer).U16(config.Port).U32(channel.Id).Bytes(channel.Secret).Build());
        }

        void PointerLoop()
        {
            while (running)
            {
                try
                {
                    var from = new IPEndPoint(IPAddress.Any, 0);
                    HandleDatagram(pointerSocket.Receive(ref from));
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                catch (SocketException)
                {
                    if (!running) return;
                }
                catch (Exception)
                {
                    // A datagram that decrypted but didn't parse. Drop it.
                }
            }
        }

        void HandleDatagram(byte[] packet)
        {
            if (packet.Length < Proto.UdpNonceSize + 4 + Proto.UdpTagSize) return;
            uint id = ((uint)packet[0] << 24) | ((uint)packet[1] << 16) | ((uint)packet[2] << 8) | packet[3];
            ClientSession s;
            lock (pointerChannels)
            {
                if (!pointerChannels.TryGetValue(id, out s)) return;
            }
            PointerChannel channel = s.Pointer;
            byte[] plain = channel.Open(packet, out ulong counter);
            if (plain == null) return;
            if (!channel.Seen)
            {
                channel.Seen = true;
                Log.Info(s.DeviceName + " is sending pointer movement over UDP.");
            }

            try
            {
                // The phone had sent this many TCP frames before the datagram; what it carries comes after them.
                uint after = ((uint)plain[0] << 24) | ((uint)plain[1] << 16) | ((uint)plain[2] << 8) | plain[3];
                var frames = new MemoryStream(plain, 4, plain.Length - 4, false);
                Frame f;
                while ((f = FrameIO.Read(frames)) != null)
                {
                    switch (f.Type)
                    {
                        case Proto.Ping:
                            // Answered over TCP, so the phone knows its datagrams arrive. Off this thread: a stalled
                            // TCP link to one phone mustn't hold up the datagrams of another.
                            ThreadPool.QueueUserWorkItem(_ => s.Send(new FrameBuilder(Proto.UdpPong).Build()));
                            break;
                        case Proto.MouseMove:
                        case Proto.Scroll:
                            s.FromUdp(after, f.Type, f.I16(), f.I16(), ApplyPointer);
                            break;
                    }
                }
            }
            finally
            {
                channel.Processed(counter);
            }
        }

        void StartDiscovery()
        {
            try
            {
                discovery = new UdpClient();
                discovery.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                // Stop Windows from failing the next Receive when a phone has already closed its socket.
                const int SIO_UDP_CONNRESET = -1744830452;
                discovery.Client.IOControl(SIO_UDP_CONNRESET, new byte[] { 0 }, null);
                discovery.Client.Bind(new IPEndPoint(IPAddress.Any, Proto.DiscoveryPort));
            }
            catch (SocketException ex)
            {
                Log.Info("Auto-discovery is off (UDP " + Proto.DiscoveryPort + " unavailable: " + ex.Message + "). Phones can still add this PC by address.");
                discovery = null;
                return;
            }
            new Thread(DiscoveryLoop) { IsBackground = true, Name = "discovery" }.Start();
        }

        void DiscoveryLoop()
        {
            byte[] request = Encoding.UTF8.GetBytes(Proto.DiscoveryRequest);
            while (running)
            {
                try
                {
                    var from = new IPEndPoint(IPAddress.Any, 0);
                    byte[] data = discovery.Receive(ref from);
                    if (!data.SequenceEqual(request)) continue;
                    string reply = Proto.DiscoveryReply + "\n" + config.ServerName + "\n" + config.Port + "\n" + Fingerprint;
                    byte[] bytes = Encoding.UTF8.GetBytes(reply);
                    discovery.Send(bytes, bytes.Length, from);
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                catch (SocketException)
                {
                    if (!running) return;
                }
            }
        }
    }
}
