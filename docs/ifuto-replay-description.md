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

> **Status: recording core, preview playback and video export are all in.** (A replay still has to be
> recorded from the moment you join a world — see the roadmap at the bottom.)

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

## Playback preview

Hit **Play** in the recordings list to rebuild the world from the stored packets and watch it.
A control bar appears at the bottom of the screen (the world keeps running behind it):

| Control | What it does |
| --- | --- |
| `⏮` | Restart from the beginning |
| `❚❚` / `▶` | Pause / resume |
| `1×` | Playback speed (0.25× → 8×) |
| `⚑ ◀` / `⚑ ▶` | Jump to the previous / next marker |
| timeline | Drag anywhere; markers show as yellow ticks |
| `Resource Packs` | Opens the vanilla pack screen — **switch packs while the replay is running** |
| `Shaders` | Opens **Iris**' shader selection screen (disabled when Iris is not installed) |
| `View` | First person / third person / third person (front) |
| `Address` | Toggle hiding the server address (`********`) |
| `✕` | Leave the replay and go back to the recordings list |

How it works: the saved S2C packets are handed straight to vanilla's own `ClientPlayNetworkHandler`,
so the client builds the world, the entities and the weather itself. Nothing is sent (the replay uses a
connection whose `send` is a no-op), the camera is reconstructed from your own recorded movement
packets and interpolated every frame, and the dynamic registries stored at the top of the file let a
single `.ifreplay` file be played back on its own.

Because it is just a normal world being rendered, **Iris shaders and resource packs apply to the replay** —
change them mid-playback and you see the result immediately.

> **Important:** a replay can only be played back if it was recorded **from the moment you joined** the
> world/server (the file needs the packet that creates the world). Turn on **Auto Record**, or rejoin
> before you start recording.

## Export

Hit **Export** in the recordings list to turn a recording into an `.mp4`.

- **FPS, resolution and bitrate are independent of the recording.** Record at 60 fps and export at 30,
  or export 4K while your window is 1080p — and redo it as many times as you like.
- You can also export **just a range** (start / end sliders).

| Option | What it does | Default |
| --- | --- | --- |
| FPS | Frames per second of the video (24 / 30 / 50 / 60 / 120 / 144 / 240) | 60 |
| Resolution | 1280x720 / 1920x1080 / 2560x1440 / 3840x2160 / **same as screen** / custom | 1920x1080 |
| Bitrate | Higher looks better and weighs more (10000-20000 is a good range for 1080p60) | 20000 kbps |
| ffmpeg | The executable to use — `ffmpeg` works if it is on your PATH | `ffmpeg` |
| File name | Written to `ifuto-replay/exports/` (a suffix is added if the name is taken) | same as the recording |
| Start / End | The range to export | everything |

How it differs from screen recording:

- **Time is driven by the exporter.** Each frame it advances the recording clock by 1/FPS of a second,
  lets the game draw, grabs the result and pipes raw RGBA frames to `ffmpeg`.
- Because wall-clock time is irrelevant, **heavy scenes never stutter** (they just take longer).
- Frames are captured through **vanilla's own screenshot path**, so no custom GL readback that could
  break with a Minecraft update.
- While exporting, the framebuffer is resized to the target resolution and the HUD is hidden;
  everything is restored when it finishes.
- The progress screen can **cancel** at any time. If ffmpeg is missing or the resolution cannot be used,
  you get the reason on screen instead of a broken file.

> **ffmpeg is not bundled.** Install it yourself, either on your PATH or by pointing the setting at the
> executable.

> **Tip:** don't resize the game window while exporting (that changes the framebuffer size, and the
> export stops itself rather than produce a broken file). Exports take real time, so shorter recordings
> are easier to work with.

## Settings (Mod Menu)

| Setting | Description | Default |
| --- | --- | --- |
| Auto Record | Start recording as soon as you join a world | Off |
| Record Input | Also store the packets you send (C2S) | On |
| Skip Keep-Alives | Leave out keep-alive and ping packets | On |
| Store Registries | Keep a copy of the dynamic registries in the file, so a recording can be played back on its own | On |
| Compression | Off / Fast / Balanced (runs on the writer thread) | Off |
| Size Limit | Stop and save past this size (0 = unlimited) | Unlimited |
| Time Limit | Stop and save after this long (0 = unlimited) | Unlimited |
| Recording HUD | Corner indicator with elapsed time and size | On |
| HUD Position | Which corner | Top Left |
| Chat Notices | Print start / save / marker to chat | On |
| Hide Server Address | Show the address as `********` in preview and export (remembered once enabled) | Off |
| Export FPS | Starting value of the export screen | 60 |
| Export Resolution | Starting value of the export screen (width x height) | 1920x1080 |
| Export Bitrate | Starting value of the export screen (kbps) | 20000 |
| ffmpeg Location | Executable used for exporting (`ffmpeg` works if it is on your PATH) | `ffmpeg` |
| Save Folder | Relative to the game directory | `ifuto-replay` |

Settings live in `config/ifuto-replay.json`, so they can be edited without Mod Menu too. The
`exportFps` / `exportWidth` / `exportHeight` / `exportBitrateKbps` / `ffmpegPath` entries are the
starting values of the export screen.

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
4. **Playback is fully local** (no server connection): it feeds the stored packets into a connection that
   never sends, so nothing goes out during a replay either.

## File format

The `.ifreplay` format is append-only (no seeking while recording), self-describing and fully documented:
[`docs/replay-file-format.md`](./replay-file-format.md).

## Roadmap

1. ✅ **Recording core** — lossless packet capture, async writer, markers, HUD
2. ✅ **Preview playback** — packets fed into a vanilla world, pause / speed / marker jumps /
   drag-to-seek, live resource pack and Iris shader switching
3. ✅ **Export** (this release) — render at **any FPS, resolution and bitrate**, pipe raw frames to
   ffmpeg, with range selection and cancel
4. ⬜ **Mid-session recordings** — snapshot the world state when you start recording, so every recording
   is playable no matter when you hit record

## Requirements

- Minecraft **1.21.11**
- Fabric Loader 0.16.0+ (any 1.21.11-compatible version)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- (optional) [Mod Menu](https://modrinth.com/mod/modmenu) 17.x for the settings screen

Japanese and English are both supported.

## License

MIT
