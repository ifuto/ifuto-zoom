# Ifuto Replay — Modrinth description

> Short description (one line)

An ultra-lightweight packet recorder for Minecraft 1.21.11. Save the network stream while you play, then replay and export at any FPS and resolution.

---

## Long description (Markdown for the Modrinth page)

# Ifuto Replay

**Ultra-lightweight packet recorder for Minecraft (Fabric, 1.21.11).**

Ifuto Replay does not capture your screen. It saves the **network packets** the server sends you (and,
optionally, the ones you send back) while you play — so recording costs almost nothing, and the
finished recording is still the world itself: **playback and export can use any FPS, any resolution and
any camera angle you want.**

> **Status: recording core only.** Playback and video export land in the next updates — see the roadmap
> at the bottom.

## Why packet recording?

| | Screen recording | Ifuto Replay (packets) |
| --- | --- | --- |
| Work while recording | Re-read and compress the screen every frame | Serialize incoming packets, that's it |
| Data rate at 1080p60 | 300 MB/s+ | Tens of KB/s to a few hundred KB/s |
| Keeps 60 FPS? | Struggles on heavy settings | Never touches rendering, so nothing to lose |
| Freedom after recording | Fixed at capture time | **FPS, resolution, bitrate and camera are all yours later** |

## How it stays light

- **One hook, both directions.** Two tiny mixins on `ClientConnection` (inbound `channelRead0`, outbound
  `send`) catch every packet in singleplayer *and* multiplayer. Anything outside the PLAY phase is ignored.
- **Free when idle.** While you are not recording, the cost is a single null check.
- **No hand-written protocol tables.** It borrows vanilla's own `PlayStateFactories` codecs, so it keeps
  working as the protocol changes.
- **Disk I/O and compression happen on their own thread.** The game thread only calls `offer()`; if the
  queue is full, packets are dropped instead of stalling the game (the drop count is reported when you stop).
- **No needless copies.** Packet payloads are handed to the writer thread as Netty `ByteBuf`s and released
  there.
- **Written once per packet type.** Names are stored in a definition frame the first time a type appears;
  later packets only store a one-byte-ish index.
- **Keep-alives are skipped** by default — they don't affect playback.

## Using it

**No keybinds — everything is a button.** Open the pause menu (ESC) and you will find the controls in the
top-left corner, just like Flashback:

| Button | What it does |
| --- | --- |
| `● Record` / `■ Stop 0:12` | Start and stop recording (shows the elapsed time while recording) |
| `⚑ Marker` | Drop a marker at the current time (only enabled while recording) |
| `≡ Recordings` | Browse saved recordings: date, duration, size and server, with delete |
| `⚙ Settings` | Open the settings screen |

Recordings are saved as `ifuto-replay/2026-09-13_01-23-45.ifreplay` inside your game folder. Leaving a
server or closing the game saves and closes the file automatically. A small indicator in a screen corner
shows the elapsed time and file size while recording.

The recordings screen only reads file headers plus the last 12 bytes of each file, so it stays instant
even with gigabytes of recordings.

## Settings (Mod Menu)

| Setting | Description | Default |
| --- | --- | --- |
| Auto Record | Start recording as soon as you join a world | Off |
| Record Input | Also store the packets you send (C2S) | On |
| Skip Keep-Alives | Leave out keep-alive and ping packets | On |
| Compression | Off / Fast / Balanced (runs on the writer thread) | Off |
| Size Limit | Stop and save past this size (0 = unlimited) | Unlimited |
| Time Limit | Stop and save after this long (0 = unlimited) | Unlimited |
| Recording HUD | Corner indicator with elapsed time and size | On |
| HUD Position | Which corner | Top Left |
| Chat Notices | Print start / save / marker to chat | On |
| Save Folder | Relative to the game directory | `ifuto-replay` |

Settings live in `config/ifuto-replay.json`, so they can be edited without Mod Menu too. The file already
contains the upcoming export options (`exportFps`, `exportWidth`, `exportHeight`, `exportBitrateKbps`,
`ffmpegPath`).

## Will this get me banned?

**Not from your network traffic.** The mod never sends anything:

| Concern | This mod |
| --- | --- |
| Outgoing packets | **None.** It only reads packets; it never modifies, drops or adds any |
| What the server sees | **Byte-identical to vanilla** — every packet is passed through untouched |
| Announcing itself to the server | No. It never answers a mod-list handshake |
| Automation (autoclicker, etc.) | None |

In other words, behaviour and packet-analysis anti-cheats (Grim, Vulcan, NCP, AAC) have nothing to detect.
It is the same category as ReplayMod and Flashback.

Things to keep in mind:

1. **Server rules.** Some servers forbid recording or streaming outright — check before you record.
2. **"Record Input"** stores the packets you send locally. Nothing is uploaded, but serializing them adds
   a tiny delay to outgoing packets. Turn it off if you prefer S2C-only recording.
3. **Recordings contain your account name, the server address and chat messages.** Be careful when sharing.
4. The upcoming playback is **fully local** (no server connection), so it will not send anything either.

## File format

The `.ifreplay` format is append-only (no seeking while recording), self-describing and fully documented:
[`docs/replay-file-format.md`](./replay-file-format.md).

## Roadmap

1. ✅ **Recording core** (this release) — lossless packet capture, async writer, markers, HUD
2. ⬜ **Playback** — decode the saved packets through an `EmbeddedChannel` and feed them into a replay
   world, seeking with the index stored in the file
3. ⬜ **Export** — interpolate the packet timeline, render offscreen at **any FPS and resolution**, and
   pipe raw frames to ffmpeg

## Requirements

- Minecraft **1.21.11**
- Fabric Loader 0.16.0+ (any 1.21.11-compatible version)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- (optional) [Mod Menu](https://modrinth.com/mod/modmenu) 17.x for the settings screen

Japanese and English are both supported.

## License

MIT
