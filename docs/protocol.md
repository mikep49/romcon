# RomCon protocol, version 1

Both apps implement this: `windows/src/Protocol.cs` and `android/app/src/main/java/dev/romcon/net/Protocol.kt`.

## Discovery (UDP 50506)

The phone broadcasts the ASCII string `ROMCON?1` to `255.255.255.255:50506` and to each interface's directed broadcast address. Each PC answers by unicast with four `\n`-separated lines:

```
ROMCON!1
<PC name>
<TCP port>
<SHA-256 of the PC's certificate, 64 lowercase hex chars>
```

## Control connection (TCP 50505, TLS)

The phone opens TCP, then TLS 1.2. The PC presents a self-signed RSA-2048 certificate. The phone doesn't validate it against any CA. Instead it compares the certificate's SHA-256 with the fingerprint it pinned at pairing, and refuses any other. The 8-character *PC code* shown while pairing is the first 8 hex digits of that fingerprint, uppercased and split 4+4.

### Frames

Every message in both directions is

```
u16 length   (big-endian; counts the type byte plus payload; 1..8192)
u8  type
... payload
```

Integers are big-endian. `str` is `u16 byte length` followed by UTF-8 bytes. `i16` is a two's-complement `u16`.

### Sign-in

The phone's first frame must be **HELLO**. The PC waits up to 120 s for it.

| Type | Name | Payload |
| --- | --- | --- |
| `0x01` | HELLO | `u8 version (1)`, `str deviceId`, `str deviceName`, `u8 mode` (0 = token, 1 = PIN), `str secret`, then optionally `u8 role` (0 = control, 1 = media link) |
| `0x81` | WELCOME | `str pcName`, `str token`, then optionally `u8 features` (bit 0: the PC offers media links). The token is non-empty only after PIN pairing; the phone stores it. |
| `0x82` | REJECTED | `u8 code`, `str message`, then the PC closes the connection |

Rejection codes: 1 version mismatch, 2 not paired (unknown device or token), 3 wrong PIN, 4 pairing not open, 5 too many wrong PINs (pairing closed).

A PIN is accepted only while pairing is open on the PC (3 minutes, single use, closed after 5 failures). The PC stores the SHA-256 of each issued token, never the token.

`role` and `features` were added after the first release. A receiver reads them only if the frame has bytes left, so an app that predates them simply sends a shorter frame, and one that doesn't know them ignores the extra byte. A missing role means control. A role of media is honoured only with a token; a phone pairing with a PIN gets a control connection.

### After WELCOME

Phone to PC:

| Type | Name | Payload |
| --- | --- | --- |
| `0x02` | PING | none. The PC answers PONG. The phone pings every 2.5 s; the PC drops a link that's silent for 20 s. |
| `0x03` | UDP_REQUEST | none. The phone wants to send pointer movement over UDP. The PC answers UDP_OFFER. |
| `0x04` | UDP_SYNC | `u64 counter`: see *Pointer movement over UDP* |
| `0x10` | MOUSE_MOVE | `i16 dx`, `i16 dy`: relative pixels |
| `0x11` | MOUSE_BUTTON | `u8 button` (0 left, 1 right, 2 middle, 3 back, 4 forward), `u8 down` (1/0) |
| `0x12` | SCROLL | `i16 vertical`, `i16 horizontal`: Windows wheel units, 120 per notch. Positive is up / right. |
| `0x20` | TEXT | `str text`: typed as Unicode; `\n` is Enter, `\t` Tab, `\b` Backspace |
| `0x21` | KEY | `u16 virtualKey` (Windows VK code), `u8 action` (0 up, 1 down, 2 tap), `u8 mods` (tap only) |
| `0x22` | CHAR_WITH_MODS | `u8 mods`, `u16 utf16char`: the PC finds the key for the character on its current layout and taps it with the modifiers |
| `0x30` | COMMAND | `u8 command` (1 = lock the workstation) |
| `0x40` | MEDIA_COMMAND | `u8 command`, `u32 value`: see *Media link* |

`mods` bits: 1 Ctrl, 2 Alt, 4 Shift, 8 Win.

PC to phone:

| Type | Name | Payload |
| --- | --- | --- |
| `0x83` | PONG | none |
| `0x84` | STATUS | `u8 paused`. Sent after WELCOME and whenever remote control is paused or resumed on the PC. |
| `0x85` | UDP_OFFER | `u16 udpPort`, `u32 channelId`, 32 bytes of key |
| `0x86` | UDP_PONG | none. The PC received a PING by UDP. |
| `0x87` | MEDIA | What's playing: see *Media link* |
| `0x88` | MEDIA_ART | A piece of the artwork: see *Media link* |

Receivers ignore frame types they don't know, so either side can add messages without breaking the other.

The PC tracks buttons and keys each phone holds down (MOUSE_BUTTON, KEY down/up) and releases them when the connection ends.

## Media link

A second kind of connection, which the phone keeps open in the background to show the PC's media controls on its lock screen. It's the same TLS connection and sign-in, with `role` 1 in HELLO. The phone only opens one to a PC whose WELCOME had the media feature bit.

On a media link the PC accepts PING and MEDIA_COMMAND and ignores everything else, so the link can't move the pointer or type. It doesn't count as a connected phone on the PC, and the PC doesn't drop it for being silent: a sleeping phone stops pinging, and TCP keep-alive (every 5 minutes) finds dead links instead. A phone has one media link per PC; a new one replaces the old.

The PC sends MEDIA right after WELCOME and again whenever something in it changes:

```
u8  state        0 nothing to show, 1 playing, 2 paused
u8  can          bits: 1 play, 2 pause, 4 next, 8 previous, 16 seek, 32 volume
u8  volume       the PC's master volume, 0..100
u8  muted
u32 positionMs   where playback is as the frame is sent
u32 durationMs   0 if unknown (a live stream)
u16 ratePercent  playback speed; 100 is normal
u32 artId        0 for no artwork
str title
str artist
```

The phone moves the position on by itself from `positionMs` at `ratePercent`, so the PC sends a new MEDIA for a position only when it jumps. A paused player is reported for 10 minutes after it was last playing, then as state 0. While remote control is paused on the PC, and as the PC goes to sleep, the state is 0.

A non-zero `artId` names a JPEG (at most 480 pixels a side) that follows in MEDIA_ART frames, unless this connection has already been sent that id:

```
u32 artId
u16 index        0, 1, 2, ...
u16 count        how many frames the picture takes
...              up to 8000 bytes of the JPEG
```

MEDIA_COMMAND, phone to PC, is `u8 command`, `u32 value`:

| Command | | Value |
| --- | --- | --- |
| 1 | Play | unused |
| 2 | Pause | unused |
| 3 | Next | unused |
| 4 | Previous | unused |
| 5 | Seek | position in milliseconds |
| 6 | Set volume | 0..100 |
| 7 | Adjust volume | change in percent, a two's-complement `i32`. Turning a muted PC up unmutes it from 0; turning it down leaves it muted. The PC may round the change to its own volume steps. |

Playback commands go to the player the last MEDIA described. The PC doesn't answer a command; the result arrives as the next MEDIA.

## Pointer movement over UDP (UDP 50505)

Optional, and chosen on the phone (Settings, Connection). MOUSE_MOVE and SCROLL can travel as UDP datagrams, so a packet the network drops costs one movement instead of stalling the pointer while TCP resends. Sign-in and every other message stay on the TLS connection, which also carries the key. The UDP port has the same number as the TCP port.

1. After WELCOME the phone sends UDP_REQUEST. A PC without UDP support ignores it, and the phone carries on with TCP.
2. The PC answers UDP_OFFER with a random channel id and a random 256-bit key, both good for this connection only.
3. The phone sends datagrams to `udpPort` at the PC's address. The PC never sends datagrams.

A datagram is

```
u32 channelId
u64 counter      (1, 2, 3, ... per channel; never reused)
...              AES-256-GCM ciphertext
16 bytes         GCM tag
```

The first 12 bytes are the GCM nonce; there is no additional authenticated data. The plaintext is `u32 tcpFrames` followed by one or more frames in the TCP framing. Only PING, MOUSE_MOVE and SCROLL are accepted. The PC drops a datagram whose tag doesn't verify or whose counter it has already accepted; counters may arrive out of order within a window of 64.

**Knowing it works.** The PC answers a PING that came by UDP with UDP_PONG over TCP. The phone probes with a PING twice on receiving the offer and then every 2.5 s. It sends movement by UDP only while the last UDP_PONG is under 7.5 s old, and over TCP otherwise, so a network that blocks UDP just means TCP.

**Keeping the order.** A movement and a click sent one after the other take different roads, and either can arrive first.

- `tcpFrames` is how many frames the phone had written to the TCP connection after HELLO when it sent the datagram. The PC counts the frames it has handled the same way and holds a datagram's movement until it has caught up, so movement never overtakes an earlier click or key.
- Before a TCP frame other than PING that follows datagrams, the phone sends UDP_SYNC with the counter of the last datagram. The PC then waits up to 40 ms for that datagram, or a later one, before going on to the next frame. A datagram that hasn't come by then counts as lost.
