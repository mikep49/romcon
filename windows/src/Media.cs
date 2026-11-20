using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Threading;
using Windows.Foundation;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace RomCon
{
    /// <summary>What a phone needs to show media controls: what's playing, how far in, and the PC's volume.</summary>
    sealed class MediaSnapshot
    {
        public static readonly MediaSnapshot None = new MediaSnapshot();

        public byte State = Proto.MediaNone;
        public int Can;
        public int Volume;
        public bool Muted;
        public long PositionMs, DurationMs; // a duration of 0 means unknown, as for a live stream
        public int RatePercent = 100;
        public string Title = "", Artist = "";
        public uint ArtId;   // 0 for no artwork; otherwise it changes whenever the picture does
        public byte[] Art;   // JPEG
        public long TakenAtMs;

        /// <summary>Where playback has got to by <paramref name="nowMs"/> on the watcher's clock.</summary>
        public long PositionAt(long nowMs)
        {
            if (State != Proto.MediaPlaying || DurationMs <= 0) return PositionMs;
            long position = PositionMs + (nowMs - TakenAtMs) * RatePercent / 100;
            return Math.Max(0, Math.Min(DurationMs, position));
        }

        /// <summary>True if a phone showing this needs no update to show <paramref name="next"/>.</summary>
        public bool SameAs(MediaSnapshot next)
        {
            if (State != next.State || Can != next.Can || Volume != next.Volume || Muted != next.Muted) return false;
            if (DurationMs != next.DurationMs || RatePercent != next.RatePercent || ArtId != next.ArtId) return false;
            if (Title != next.Title || Artist != next.Artist) return false;
            // A phone moves the position along by itself, so only a jump matters.
            long drift = PositionAt(next.TakenAtMs) - next.PositionMs;
            return Math.Abs(drift) < (State == Proto.MediaPlaying ? 1500 : 500);
        }
    }

    /// <summary>
    /// Watches what Windows says is playing (the same source as the media flyout and the lock screen) and the
    /// master volume, and carries out a phone's media commands. It only runs while a phone is showing media controls.
    /// </summary>
    sealed class MediaWatcher
    {
        const int PollMs = 1000;
        const int FastPollMs = 200;          // just after a command, so the phone sees the result quickly
        const int FastForMs = 1500;
        const int PausedKeepMs = 10 * 60 * 1000;
        const int ArtRetryMs = 6000;         // players often set the picture a moment after the title
        const int ArtMaxSide = 480;
        const int ArtMaxSourceBytes = 8 * 1024 * 1024;
        const int MaxTitleChars = 300;

        struct Order
        {
            public byte Command;
            public int Value;
        }

        readonly bool dryRun;
        readonly object gate = new object();
        readonly AutoResetEvent wake = new AutoResetEvent(false);
        readonly Stopwatch clock = Stopwatch.StartNew();
        readonly ConcurrentQueue<Order> orders = new ConcurrentQueue<Order>();
        Thread thread;
        int watchers;
        long fastUntil;
        volatile MediaSnapshot current = MediaSnapshot.None;

        // Only the polling thread uses what follows. The Windows media objects are kept as plain objects so this
        // class still loads on a Windows too old to have them.
        object manager;
        object session;
        bool unsupported;
        string lastPlayingApp;
        long lastPlayingAt = long.MinValue / 2;
        string artKey;
        long artUntil;
        string artHash = "";
        uint artId, artCounter;
        byte[] art;

        /// <summary>Raised on the polling thread when <see cref="Current"/> changes.</summary>
        public event Action Changed;

        public MediaWatcher(bool dryRun)
        {
            this.dryRun = dryRun;
        }

        public MediaSnapshot Current => current;

        public long NowMs => clock.ElapsedMilliseconds;

        public void AddWatcher()
        {
            lock (gate)
            {
                watchers++;
                if (thread != null) return;
                thread = new Thread(Run) { IsBackground = true, Name = "media" };
                thread.SetApartmentState(ApartmentState.MTA);
                thread.Start();
            }
        }

        public void RemoveWatcher()
        {
            lock (gate) watchers--;
            wake.Set();
        }

        /// <summary>Look again now instead of at the next poll.</summary>
        public void Kick()
        {
            wake.Set();
        }

        public void Command(byte command, int value)
        {
            if (dryRun)
            {
                Log.Info("[dry-run] media " + Describe(command, value));
                return;
            }
            orders.Enqueue(new Order { Command = command, Value = value });
            Interlocked.Exchange(ref fastUntil, clock.ElapsedMilliseconds + FastForMs);
            wake.Set();
        }

        static string Describe(byte command, int value)
        {
            switch (command)
            {
                case Proto.MediaPlay: return "play";
                case Proto.MediaPause: return "pause";
                case Proto.MediaNext: return "next";
                case Proto.MediaPrevious: return "previous";
                case Proto.MediaSeekTo: return "seek to " + value / 1000 + " s";
                case Proto.MediaSetVolume: return "volume to " + value;
                case Proto.MediaAdjustVolume: return "volume " + (value >= 0 ? "+" : "") + value;
                default: return "command " + command;
            }
        }

        void Run()
        {
            while (true)
            {
                lock (gate)
                {
                    if (watchers <= 0)
                    {
                        thread = null;
                        current = MediaSnapshot.None;
                        return;
                    }
                }

                Order order;
                while (orders.TryDequeue(out order))
                {
                    try { Carry(order.Command, order.Value); }
                    catch (Exception) { }
                }

                MediaSnapshot next = Poll();
                if (!current.SameAs(next))
                {
                    current = next;
                    Changed?.Invoke();
                }
                wake.WaitOne(clock.ElapsedMilliseconds < Interlocked.Read(ref fastUntil) ? FastPollMs : PollMs);
            }
        }

        MediaSnapshot Poll()
        {
            long now = clock.ElapsedMilliseconds;
            MediaSnapshot snapshot = null;
            if (!unsupported)
            {
                try
                {
                    snapshot = ReadSession(now);
                }
                catch (Exception ex) when (ex is TypeLoadException || ex is FileNotFoundException || ex is MissingMethodException || ex is PlatformNotSupportedException)
                {
                    unsupported = true;
                    Log.Info("This version of Windows can't tell RomCon what's playing, so phones won't show media controls.");
                }
                catch (Exception)
                {
                    // Usually a player that closed mid-read. Start afresh at the next poll.
                    manager = null;
                    session = null;
                }
            }
            if (snapshot == null)
            {
                artKey = null;
                return MediaSnapshot.None;
            }

            int volume;
            bool muted;
            if (SystemVolume.TryGet(out volume, out muted))
            {
                snapshot.Can |= Proto.CanVolume;
                snapshot.Volume = volume;
                snapshot.Muted = muted;
            }
            return snapshot;
        }

        /// <summary>Null when nothing is playing, or was until a few minutes ago.</summary>
        [MethodImpl(MethodImplOptions.NoInlining)]
        MediaSnapshot ReadSession(long now)
        {
            var sessions = manager as GlobalSystemMediaTransportControlsSessionManager;
            if (sessions == null)
            {
                sessions = Wait(GlobalSystemMediaTransportControlsSessionManager.RequestAsync(), 5000);
                manager = sessions;
                if (sessions == null) return null;
            }

            // Windows names one session as current; prefer whichever is actually playing.
            GlobalSystemMediaTransportControlsSession chosen = sessions.GetCurrentSession();
            if (chosen == null || !IsPlaying(chosen))
            {
                foreach (var candidate in sessions.GetSessions())
                {
                    if (!IsPlaying(candidate)) continue;
                    chosen = candidate;
                    break;
                }
            }
            session = chosen;
            if (chosen == null) return null;

            var info = chosen.GetPlaybackInfo();
            var controls = info.Controls;
            string app = chosen.SourceAppUserModelId ?? "";
            byte state;
            switch (info.PlaybackStatus)
            {
                case GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing:
                    state = Proto.MediaPlaying;
                    break;
                case GlobalSystemMediaTransportControlsSessionPlaybackStatus.Paused:
                case GlobalSystemMediaTransportControlsSessionPlaybackStatus.Stopped:
                    state = Proto.MediaPaused;
                    break;
                case GlobalSystemMediaTransportControlsSessionPlaybackStatus.Changing:
                    // Between two tracks: carry on as before.
                    state = current.State == Proto.MediaNone ? Proto.MediaPaused : current.State;
                    break;
                default:
                    return null;
            }
            if (state == Proto.MediaPlaying)
            {
                lastPlayingApp = app;
                lastPlayingAt = now;
            }
            else if (app != lastPlayingApp || now - lastPlayingAt > PausedKeepMs)
            {
                // Paused for a long while, or never seen playing: not worth a place on a lock screen.
                return null;
            }

            var snapshot = new MediaSnapshot { State = state, TakenAtMs = now };
            if (controls.IsPlayEnabled || controls.IsPlayPauseToggleEnabled) snapshot.Can |= Proto.CanPlay;
            if (controls.IsPauseEnabled || controls.IsPlayPauseToggleEnabled) snapshot.Can |= Proto.CanPause;
            if (controls.IsNextEnabled) snapshot.Can |= Proto.CanNext;
            if (controls.IsPreviousEnabled) snapshot.Can |= Proto.CanPrevious;

            double rate = info.PlaybackRate ?? 1.0;
            if (rate < 0.1 || rate > 16) rate = 1.0;
            snapshot.RatePercent = (int)Math.Round(rate * 100);

            var timeline = chosen.GetTimelineProperties();
            long duration = (timeline.EndTime - timeline.StartTime).Ticks;
            if (duration > 0)
            {
                long position = (timeline.Position - timeline.StartTime).Ticks;
                if (state == Proto.MediaPlaying)
                {
                    // The player reports a position now and then; it has moved on since.
                    TimeSpan age = DateTimeOffset.UtcNow - timeline.LastUpdatedTime;
                    if (age > TimeSpan.Zero && age < TimeSpan.FromDays(1)) position += (long)(age.Ticks * rate);
                }
                snapshot.DurationMs = duration / TimeSpan.TicksPerMillisecond;
                snapshot.PositionMs = Math.Max(0, Math.Min(duration, position)) / TimeSpan.TicksPerMillisecond;
                if (controls.IsPlaybackPositionEnabled) snapshot.Can |= Proto.CanSeek;
            }

            var properties = Wait(chosen.TryGetMediaPropertiesAsync(), 2000);
            if (properties != null)
            {
                snapshot.Title = Clip(properties.Title);
                snapshot.Artist = Clip(properties.Artist);
                if (snapshot.Artist.Length == 0) snapshot.Artist = Clip(properties.AlbumArtist);
            }

            string key = app + "\n" + snapshot.Title + "\n" + snapshot.Artist;
            if (key != artKey)
            {
                artKey = key;
                artUntil = now + ArtRetryMs;
            }
            if (now <= artUntil && properties != null) RefreshArt(properties);
            snapshot.ArtId = artId;
            snapshot.Art = art;
            return snapshot;
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        static bool IsPlaying(GlobalSystemMediaTransportControlsSession s)
        {
            try
            {
                return s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            }
            catch (Exception)
            {
                return false;
            }
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        void RefreshArt(GlobalSystemMediaTransportControlsSessionMediaProperties properties)
        {
            byte[] raw = null;
            try
            {
                var reference = properties.Thumbnail;
                if (reference != null)
                {
                    using (var source = Wait(reference.OpenReadAsync(), 2000))
                    {
                        if (source != null && source.Size > 0 && source.Size <= ArtMaxSourceBytes)
                        {
                            using (var reader = new DataReader(source.GetInputStreamAt(0)))
                            {
                                uint loaded = Wait(reader.LoadAsync((uint)source.Size), 2000);
                                if (loaded > 0)
                                {
                                    raw = new byte[loaded];
                                    reader.ReadBytes(raw);
                                }
                            }
                        }
                    }
                }
            }
            catch (Exception)
            {
                return; // keep what we have; the next poll tries again
            }

            string hash = "";
            if (raw != null)
            {
                using (var sha = SHA1.Create()) hash = Convert.ToBase64String(sha.ComputeHash(raw));
            }
            if (hash == artHash) return;
            artHash = hash;
            art = raw == null ? null : ToJpeg(raw);
            artId = art == null ? 0 : ++artCounter;
        }

        /// <summary>Shrinks artwork to something that crosses the network in a few frames.</summary>
        static byte[] ToJpeg(byte[] raw)
        {
            try
            {
                using (var source = Image.FromStream(new MemoryStream(raw)))
                {
                    double scale = Math.Min(1.0, (double)ArtMaxSide / Math.Max(source.Width, source.Height));
                    int width = Math.Max(1, (int)Math.Round(source.Width * scale));
                    int height = Math.Max(1, (int)Math.Round(source.Height * scale));
                    using (var bitmap = new Bitmap(width, height, PixelFormat.Format24bppRgb))
                    {
                        using (var g = Graphics.FromImage(bitmap))
                        using (var attributes = new ImageAttributes())
                        {
                            g.Clear(Color.Black);
                            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                            attributes.SetWrapMode(WrapMode.TileFlipXY); // no pale fringe at the edges
                            g.DrawImage(source, new Rectangle(0, 0, width, height), 0, 0, source.Width, source.Height, GraphicsUnit.Pixel, attributes);
                        }
                        ImageCodecInfo jpeg = null;
                        foreach (var codec in ImageCodecInfo.GetImageEncoders())
                        {
                            if (codec.FormatID == ImageFormat.Jpeg.Guid) jpeg = codec;
                        }
                        if (jpeg == null) return null;
                        using (var parameters = new EncoderParameters(1))
                        using (var output = new MemoryStream())
                        {
                            parameters.Param[0] = new EncoderParameter(Encoder.Quality, 82L);
                            bitmap.Save(output, jpeg, parameters);
                            return output.Length <= Proto.MaxArtBytes ? output.ToArray() : null;
                        }
                    }
                }
            }
            catch (Exception)
            {
                return null; // a picture GDI+ can't read
            }
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        void Carry(byte command, int value)
        {
            switch (command)
            {
                case Proto.MediaSetVolume:
                    SystemVolume.Set(value);
                    return;
                case Proto.MediaAdjustVolume:
                    SystemVolume.Adjust(value);
                    return;
            }

            var s = session as GlobalSystemMediaTransportControlsSession;
            if (s == null) return;
            var controls = s.GetPlaybackInfo().Controls;
            // These return at once; the result shows up in a later poll.
            switch (command)
            {
                case Proto.MediaPlay:
                    if (controls.IsPlayEnabled) Forget(s.TryPlayAsync()); else Forget(s.TryTogglePlayPauseAsync());
                    break;
                case Proto.MediaPause:
                    if (controls.IsPauseEnabled) Forget(s.TryPauseAsync()); else Forget(s.TryTogglePlayPauseAsync());
                    break;
                case Proto.MediaNext:
                    Forget(s.TrySkipNextAsync());
                    break;
                case Proto.MediaPrevious:
                    Forget(s.TrySkipPreviousAsync());
                    break;
                case Proto.MediaSeekTo:
                    long start = s.GetTimelineProperties().StartTime.Ticks;
                    Forget(s.TryChangePlaybackPositionAsync(start + Math.Max(0, value) * TimeSpan.TicksPerMillisecond));
                    break;
            }
        }

        /// <summary>Started and left to finish by itself.</summary>
        static void Forget(IAsyncOperation<bool> operation)
        {
        }

        /// <summary>The result of a Windows async call, or the type's default if it fails or takes too long.</summary>
        static T Wait<T>(IAsyncOperation<T> operation, int timeoutMs)
        {
            using (var done = new ManualResetEventSlim())
            {
                // Windows calls this at once if the operation has already finished.
                operation.Completed = (o, status) =>
                {
                    try { done.Set(); } catch (ObjectDisposedException) { }
                };
                if (!done.Wait(timeoutMs)) return default(T);
            }
            return operation.Status == AsyncStatus.Completed ? operation.GetResults() : default(T);
        }

        static string Clip(string s)
        {
            s = (s ?? "").Trim();
            return s.Length > MaxTitleChars ? s.Substring(0, MaxTitleChars) : s;
        }
    }

    /// <summary>The PC's master volume: the default playback device, through the Core Audio API.</summary>
    static class SystemVolume
    {
        const int ERender = 0, EMultimedia = 1, ClsCtxAll = 23;

        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
        class DeviceEnumerator
        {
        }

        // Only the methods up to the last one used are declared; they must stay in the order of the COM interfaces.
        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceEnumerator
        {
            [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
            [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        }

        [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioEndpointVolume
        {
            [PreserveSig] int RegisterControlChangeNotify(IntPtr notify);
            [PreserveSig] int UnregisterControlChangeNotify(IntPtr notify);
            [PreserveSig] int GetChannelCount(out uint count);
            [PreserveSig] int SetMasterVolumeLevel(float levelDb, ref Guid context);
            [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid context);
            [PreserveSig] int GetMasterVolumeLevel(out float levelDb);
            [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
            [PreserveSig] int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid context);
            [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
            [PreserveSig] int GetChannelVolumeLevel(uint channel, out float levelDb);
            [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
            [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
            [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
        }

        /// <summary>False when the PC has no playback device.</summary>
        public static bool TryGet(out int volume, out bool muted)
        {
            int v = 0;
            bool m = false;
            bool ok = WithEndpoint(endpoint =>
            {
                float level;
                if (endpoint.GetMasterVolumeLevelScalar(out level) != 0) return false;
                endpoint.GetMute(out m);
                v = ToPercent(level);
                return true;
            });
            volume = v;
            muted = m;
            return ok;
        }

        public static void Set(int volume)
        {
            WithEndpoint(endpoint => Apply(endpoint, volume));
        }

        /// <summary>
        /// Uses Windows' own volume keys where it can, so Windows shows its volume bar as if the keys on the PC had
        /// been pressed. They move in steps of 2, so [delta] is rounded to whole presses.
        /// </summary>
        public static void Adjust(int delta)
        {
            int volume;
            bool muted;
            if (delta == 0 || !TryGet(out volume, out muted)) return;
            if (muted)
            {
                // A muted PC shows as 0 on the phone, as on a phone's own volume keys: turning it down leaves it
                // muted, and turning it up starts from silence.
                if (delta < 0) return;
                Set(0); // stays muted
                volume = 0;
            }
            int target = Math.Max(0, Math.Min(100, volume + delta));
            if (target == volume && !muted) return;
            if (InputInjector.TapVolumeKey(delta > 0, (Math.Abs(delta) + KeyStep - 1) / KeyStep) && ChangesFrom(volume, muted)) return;
            // The keys don't reach Windows on its lock screen, for one. Set the volume without the bar there.
            Set(target);
        }

        const int KeyStep = 2;
        const int KeyWaitMs = 300;

        /// <summary>Waits a moment for the volume keys to take effect. False if nothing changed.</summary>
        static bool ChangesFrom(int volume, bool muted)
        {
            var clock = Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < KeyWaitMs)
            {
                Thread.Sleep(15);
                int now;
                bool nowMuted;
                if (TryGet(out now, out nowMuted) && (now != volume || nowMuted != muted)) return true;
            }
            return false;
        }

        static bool Apply(IAudioEndpointVolume endpoint, int volume)
        {
            volume = Math.Max(0, Math.Min(100, volume));
            Guid context = Guid.Empty;
            if (volume > 0) endpoint.SetMute(false, ref context);
            return endpoint.SetMasterVolumeLevelScalar(volume / 100f, ref context) == 0;
        }

        static int ToPercent(float level)
        {
            return Math.Max(0, Math.Min(100, (int)Math.Round(level * 100)));
        }

        /// <summary>Looks the device up each time, so it's always the one Windows is playing through now.</summary>
        static bool WithEndpoint(Func<IAudioEndpointVolume, bool> action)
        {
            object enumerator = null, device = null, endpoint = null;
            try
            {
                enumerator = new DeviceEnumerator();
                IMMDevice found;
                if (((IMMDeviceEnumerator)enumerator).GetDefaultAudioEndpoint(ERender, EMultimedia, out found) != 0 || found == null) return false;
                device = found;
                Guid iid = typeof(IAudioEndpointVolume).GUID;
                if (found.Activate(ref iid, ClsCtxAll, IntPtr.Zero, out endpoint) != 0 || endpoint == null) return false;
                return action((IAudioEndpointVolume)endpoint);
            }
            catch (Exception)
            {
                return false;
            }
            finally
            {
                if (endpoint != null) Marshal.ReleaseComObject(endpoint);
                if (device != null) Marshal.ReleaseComObject(device);
                if (enumerator != null) Marshal.ReleaseComObject(enumerator);
            }
        }
    }
}
