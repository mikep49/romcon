# RomCon guide

Everything the [README](../README.md) leaves out.

- [Features](#features)
- [Managing phones](#managing-phones)
- [Tailscale](#tailscale)
- [Security](#security)
- [Limitations](#limitations)
- [Development](#development)

## Features

### Trackpad

- One finger moves the pointer, with adjustable speed and acceleration.
- Tap to left-click, two-finger tap to right-click, three-finger tap to middle-click.
- Two-finger scroll, vertical and horizontal, with momentum and a natural/traditional switch.
- Double-tap and drag to drag (selecting text, moving windows), like a laptop touchpad.
- A scroll bar down the right side of the pad: slide one finger along it to scroll.

### Bottom bar

- Keyboard, Windows key and Shortcuts sit under the pad by default.
- Choose your own buttons, up to five, and their order in Settings: Keys, Send text, Switch app, Task View, Show desktop, Esc, Enter, Copy, Paste, play/pause, volume, and Left / Right / Middle click (hold one and move with another finger to drag).

### Keyboard

- The phone keyboard types straight into the PC. Autocorrect is off and Backspace always reaches the PC.
- A box above the phone keyboard shows what you type.
- Text is sent as Unicode, so accents, other scripts and emoji work whatever the PC's keyboard layout is.
- Ctrl, Alt, Shift and Win latches: tap one for the next key, long-press to lock it.
- Keys panel: Esc, Tab, arrows (hold to repeat), Home/End, PgUp/PgDn, Delete, F1–F12, media and volume keys.
- Shortcuts: copy, paste, undo, Alt+Tab (tap again to move through windows), Task View, Show desktop, Task Manager, close window, snip, and more.
- *Send text* types a whole block at once (links, passwords, pasted text).
- A hardware keyboard paired to the phone works too.

### Connection

- PCs on the same Wi-Fi show up automatically.
- Over Tailscale (or any routed network), add the PC by its 100.x address or MagicDNS name.
- If the phone is on the PC's Wi-Fi it uses the faster LAN path, and it follows the PC when the router gives it a new address.
- Pointer movement and scrolling can go over UDP instead of TCP (Settings, Connection), which keeps the pointer smooth on weak Wi-Fi. Clicks, keys and text always use TCP. If UDP can't get through, the app carries on with TCP.
- Reconnects automatically. When the app goes to the background it disconnects, and the PC releases any held keys or buttons.
- *Lock PC* button, *Pause remote control* on the PC, and a notification when a phone connects.

### Themes

Both apps offer System default, Light, Dark and OLED black. On the PC, the theme is on the **Settings** tab.

## Managing phones

- **Pair another phone**: click **Pair a new phone** in the PC window or the tray menu. Pairing stays open for 3 minutes.
- **Revoke a phone**: click **Remove** next to it under *Paired phones*.
- **Stop all control for a while**: click **Pause remote control** in the PC window.

## Tailscale

Install Tailscale on both devices and sign them in to the same tailnet. On the phone, add the PC by its Tailscale IP (`100.x.y.z`) or MagicDNS name. Nothing else is needed: Tailscale already encrypts the link, and RomCon adds its own TLS and pairing on top. Broadcast discovery doesn't cross Tailscale, which is why you add the PC by address.

If the phone can't reach the PC over Tailscale, check that Windows Firewall allows RomCon on the network profile Windows assigned to the Tailscale adapter.

## Security

- **Pairing is required.** A phone can only control the PC after you pair it with a PIN that's shown on the PC screen. The PIN works once and expires after 3 minutes. Pairing closes after 5 wrong attempts. Pairing is only open while you have it turned on.
- **Encrypted.** All TCP traffic is TLS 1.2, and pointer movement sent over UDP is AES-256-GCM with a key the PC issues over that TLS connection. The PC makes its own certificate on first run (stored DPAPI-encrypted in `%APPDATA%\RomCon`). The phone pins that certificate when pairing and refuses any other, so a device impersonating the PC never sees your keystrokes. The matching *PC code* on both screens guards the first pairing.
- **Revocable.** Each phone gets its own random token. The PC stores only a hash of it, and removing the phone on the PC revokes it at once. Tokens are kept in the app's private storage and excluded from Android backups.

## Limitations

These are Windows rules, not bugs.

- **Lock screen, UAC prompts, Ctrl+Alt+Del**: Windows blocks injected input on the secure desktop. Lock the PC from the phone, but unlock it at the PC.
- **Admin windows** (Task Manager, installers, admin terminals): Windows ignores input from a normal app aimed at an elevated window. Use **Restart as administrator** in the tray menu or window when you need to control those.
- Some games read raw hardware input and may ignore injected mouse or keyboard events.

## Development

### Ports

| Port | Use |
| --- | --- |
| TCP 50505 | Control connection |
| UDP 50505 | Pointer movement, when the phone is set to UDP |
| UDP 50506 | Discovery |

The wire format is in [protocol.md](protocol.md).

### Command-line flags

`RomCon.exe` accepts:

| Flag | Effect |
| --- | --- |
| `--dry-run` | Log input instead of sending it to Windows. Also logs the pairing PIN. |
| `--data-dir <dir>` | Use another settings folder instead of `%APPDATA%\RomCon`. |
| `--port <n>` | Listen on another port, TCP and UDP (the permanent setting is `port=` in `settings.ini`). |
| `--minimized` | Start in the tray (used by *Start with Windows*). |

### Testing against a real server

The Android unit tests include `LiveServerTest`, which pairs with and drives a real PC server through the app's own networking code. It's skipped unless you point it at one:

```powershell
RomCon.exe --dry-run --data-dir $env:TEMP\romcon-test --port 50599
# read the PIN from the RomCon window, then:
cd android
$env:ROMCON_TEST_HOST="127.0.0.1"; $env:ROMCON_TEST_PORT="50599"; $env:ROMCON_TEST_PIN="123456"
.\gradlew.bat testDebugUnitTest
```
