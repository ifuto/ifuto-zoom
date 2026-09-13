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

| Action | Default key |
| --- | --- |
| Start / stop recording | **R** |
| Add a marker | **M** |

Recordings are saved as `ifuto-replay/2026-09-13_01-23-45.ifreplay` inside your game folder. Leaving a
server or closing the game saves and closes the file automatically. A small indicator in a screen corner
shows the elapsed time and file size while recording.

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
