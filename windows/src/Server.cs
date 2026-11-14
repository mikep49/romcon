using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
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
    }

    /// <summary>Accepts phones over TLS, checks they are paired, and feeds their input to Windows.</summary>
    sealed class RemoteServer
    {
        const int HandshakeTimeoutMs = 15000;
        const int HelloTimeoutMs = 120000; // the phone may still be waiting for the user to type the PIN
        const int IdleTimeoutMs = 20000;   // phones ping every few seconds

        readonly Config config;
        readonly X509Certificate2 certificate;
        readonly InputInjector injector;
        readonly List<ClientSession> sessions = new List<ClientSession>();
        TcpListener listener;
        UdpClient discovery;
        volatile bool running;
        volatile bool paused;

        public readonly Pairing Pairing = new Pairing();
        public readonly string Fingerprint;
        public readonly string VerifyCode;
        public string StartError { get; private set; }

        /// <summary>Raised on a background thread whenever sessions, pause state or pairing change.</summary>
        public event Action Changed;
        public event Action<ClientSession> Connected;

        public RemoteServer(Config config, X509Certificate2 certificate, InputInjector injector)
        {
            this.config = config;
            this.certificate = certificate;
            this.injector = injector;
            Fingerprint = Identity.Fingerprint(certificate);
            VerifyCode = Identity.VerifyCode(Fingerprint);
            Pairing.Changed += () => Changed?.Invoke();
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
                Changed?.Invoke();
            }
        }

        public List<ClientSession> Sessions
        {
            get { lock (sessions) return sessions.ToList(); }
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
            StartDiscovery();
        }

        public void Stop()
        {
            running = false;
            try { listener?.Stop(); } catch { }
            try { discovery?.Close(); } catch { }
            foreach (var s in Sessions) s.Close();
        }

        public void Disconnect(string deviceId)
        {
            foreach (var s in Sessions.Where(x => x.DeviceId == deviceId)) s.Close();
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
                    };
                    session.Send(new FrameBuilder(Proto.Welcome).Str(config.ServerName).Str(token).Build());
                    session.Send(new FrameBuilder(Proto.Status).U8(paused ? 1 : 0).Build());
                    lock (sessions) sessions.Add(session);
                    Log.Info(deviceName + " connected from " + address + ".");
                    Connected?.Invoke(session);
                    Changed?.Invoke();

                    tcp.ReceiveTimeout = IdleTimeoutMs;
                    Frame frame;
                    while ((frame = FrameIO.Read(ssl)) != null)
                    {
                        Dispatch(session, frame);
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
                if (session != null)
                {
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
            if (paused) return;

            switch (f.Type)
            {
                case Proto.MouseMove:
                    injector.Move(f.I16(), f.I16());
                    break;
                case Proto.MouseButton:
                    {
                        int button = f.U8();
                        s.Input.Button(button, f.U8() != 0);
                        break;
                    }
                case Proto.Scroll:
                    injector.Wheel(f.I16(), f.I16());
                    break;
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
