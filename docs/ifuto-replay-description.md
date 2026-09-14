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
any camera angle you want.** **Start recording whenever you like, even in the middle of a session.**

> **Status: recording core, preview playback and video export are all in.** You can also start recording
> in the middle of a session — the current world state is saved along with it.
> (Roadmap is at the bottom.)

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
- **Nothing piles up in memory.** Buffered data is moved to the file on a timer (every 2 seconds by
  default), and the memory budget is **picked from your environment** (a fixed value is optional).
- **It stops before the disk runs out.** You get a warning when free space drops below a threshold
  (1 GB by default), and the recording is **saved and stopped automatically** when space gets critical
  (256 MB by default).

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

> **Note:** a replay needs the packet that creates the world. When you start recording mid-session,
> Ifuto Replay writes one itself (see the next section), so mid-session recordings are playable too.
> The only exception is a world you were already in **before** the mod was installed — rejoin once and
> it works.

## Recording mid-session (world snapshot)

**Hit record whenever you feel like it** — you don't have to be there from the moment you joined.

Packet replays need the packet that creates the world, and it is long gone by then. Asking the server to
send it again is out of the question (the mod never sends anything), so Ifuto Replay **builds the same
packets from the world you already have** and writes them at the front of the recording:

| Saved | What |
| --- | --- |
| The world itself | the `GameJoin` you received when joining (plus `Respawn` if you changed dimension since) |
| Terrain | blocks, biomes, block entities and **lighting** around you (**6 chunks** by default) |
| Entities | mobs, items, falling blocks in range: position, rotation, data, equipment, effects |
| You | position, rotation, health, food, XP, **inventory**, abilities |
| World | weather (rain and thunder levels) |

- Packets are built with **the same constructors the vanilla server uses**
  (`ChunkDataS2CPacket(WorldChunk, LightingProvider, ...)`, `EntitySpawnS2CPacket(...)`, and friends),
  so there is no hand-written protocol to keep up to date.
- It happens **once**, when you hit record (tens of milliseconds for a few hundred chunks; the log tells
  you what was saved).
- The radius is configurable. **0 turns it off** (starting a recording gets cheaper, but mid-session
  recordings are no longer playable).

Known limits:

- **Other players appear from the moment the server sends their info** during the recording (tab-list
  data isn't part of the snapshot).
- A world you joined **before installing the mod** has no `GameJoin` to reuse — rejoin once.

## Recording what never becomes a packet

A packet only carries what the server and you exchanged. Things that stay **on your machine** are
recorded separately — and reproduced during playback and export:

| Recorded | What |
| --- | --- |
| Mouse cursor | which slot or button you were pointing at |
| Text as you type | the chat line **while you are still typing it** (nothing reaches the server until you press enter) |
| Perspective (F5) | first person / third person / front |
| Debug screen (F3) | whether F3 was open |
| Open screen | inventory, chest, chat, ... (only *which* screen — its contents are in the packets) |

Everything is **delta-encoded**: the cursor stores movement with the odd absolute position mixed in,
and typed text stores only what was added. When nothing changes, nothing is written.

Playback drives **vanilla's own options**, so you see it exactly the way the person recording did, and the
preview shows the open screen and the text being typed at the top of the screen.

## Recording audio

Sound only exists while it is playing, so audio is captured **while you record** — there is nothing
left to capture at export time.

| Setting | What you get | How |
| --- | --- | --- |
| **Minecraft only** (default) | just what Minecraft plays | OpenAL loopback: the game's audio output is routed through the mod, which records it and passes it on to your speakers |
| **Whole PC** | everything your computer plays, voice chat included | ffmpeg reading the OS audio input |
| Off | — | as before |

- **Voice chat is recorded separately.** Simple Voice Chat opens its own output device, so its audio
  never lands in the Minecraft track. Ifuto Replay takes it straight from Simple Voice Chat's plugin
  API (the raw audio right before playback) and stores it as its own file, which is what makes
  **Include voice chat** at export a real on/off switch.
- Audio is **Opus**, written to separate files next to the recording (`.audio.ogg`, `.voice.ogg`,
  `.system.ogg`). The recording itself is never touched, so a recording still plays if its audio is
  missing or deleted.
- If audio can't be captured (no ffmpeg, no device, OpenAL without loopback support), **the recording
  is still saved**. When "Minecraft only" isn't possible, it falls back to whole-PC audio rather than
  silence.
- Preview playback is silent; the audio ends up in the exported video.

What each mode needs:

| OS | Whole-PC capture needs |
| --- | --- |
| Windows | Stereo Mix enabled, or a virtual device such as VB-CABLE |
| Linux | PulseAudio / PipeWire (usually already there) |
| macOS | a virtual device such as BlackHole (**there is no built-in way**) |

The settings screen has a **Detect** button that lists the devices it can find (blank = pick one
automatically).

## Clip mode (save it after it happened)

Turn **Clip mode** on and the mod records from the moment you join — but **nothing is kept unless you
save it**. Did something cool just happen? Press **✂ Save clip** in the pause menu. Everything from
the last N seconds is written out as one `.ifreplay` (the same feeling as Medal).

How it stays light:

- Recording is written in **short segments** (half the configured length) and the oldest segments are
  thrown away — nothing piles up in memory, the writer thread keeps moving data to disk
- Every segment starts with a **snapshot of the world at that moment**, so any segment can be played
  on its own — which means the joined result is always playable
- Saving just **concatenates the surviving segments** — no re-encoding, a few seconds of copying, done
  on a worker thread so the game never stalls
- Audio is recorded as **one continuous file** (stopping and restarting would cut the sound, and on
  Minecraft-only mode it means reopening the output device), and the **tail is cut out** with ffmpeg
  when you save. Voice chat is cut as its own track, so it stays a real on/off switch

Because clips are split at segment boundaries, a saved clip ends up between **the configured length and
1.5× that**. Temporary files live in `<save folder>/.clip-cache/` and are removed when you stop.

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
| Save World State | When starting mid-session, save terrain and entities within this radius (0 = off) | 6 chunks |
| Compression | Off / Fast / Balanced (runs on the writer thread) | Off |
| Size Limit | Stop and save past this size (0 = unlimited) | Unlimited |
| Time Limit | Stop and save after this long (0 = unlimited) | Unlimited |
| Memory Buffer | How much may wait in memory before being written (**0 = decide from the environment**) | Auto |
| Flush Interval | How often buffered data is moved to the file (shorter = lighter on memory) | 2 s |
| Low Disk Warning | Warn when free space drops below this (0 = don't watch) | 1024 MB |
| Critical Disk Space | **Save and stop recording** when free space drops below this (0 = never stop) | 256 MB |
| Recording HUD | Corner indicator with elapsed time and size | On |
| HUD Position | Which corner | Top Left |
| Chat Notices | Print start / save / marker to chat | On |
| Hide Server Address | Show the address as `********` in preview and export (remembered once enabled) | Off |
| Export FPS | Starting value of the export screen | 60 |
| Export Resolution | Starting value of the export screen (width x height) | 1920x1080 |
| Export Bitrate | Starting value of the export screen (kbps) | 20000 |
| Include Audio | Mux the audio captured alongside the recording (only shown when there is audio) | On |
| Include Voice Chat | Include Simple Voice Chat audio | On |
| Record Audio | Off / **Minecraft only** / Whole PC | Minecraft only |
| Audio Quality | Opus bitrate (kbps) | 96 |
| Audio Device | Device for whole-PC capture (blank = detect; the Detect button lists them) | blank |
| Record Voice Chat | Record Simple Voice Chat audio separately (include or drop it when exporting) | On |
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
4. ✅ **Mid-session recordings** — snapshot the world state as vanilla packets when you hit record, so a
   recording is playable no matter when you started it
5. ✅ **Automatic memory and disk care** (this release) — buffered data is flushed on a timer, the memory
   budget is picked from your environment, and the recording is saved and stopped before the disk runs out
6. ✅ **Recording what never becomes a packet** — cursor, text as you type, F5, F3 and the open screen,
   delta-encoded and applied to vanilla's own options during playback
7. ✅ **Audio** — **Minecraft only** or **whole PC**, muxed at export. **Voice chat is captured
   separately**, so including Simple Voice Chat is a real on/off switch
8. ✅ **A modern interface** — rounded surfaces, toggles, thin sliders and a progress bar. Only the
   drawing changed: input handling, tooltips and narration stay vanilla
9. ✅ **Clip mode** (this release) — record continuously like Medal, then **save backwards from the
   moment you press the button**. Segments are rolled on disk so memory never grows, and every segment
   starts with a world snapshot, so the joined clip is always playable

## Interface

Not vanilla's widget sprites: **rounded surfaces, a calm dark palette and one accent colour** live in a
single theme class, so every screen this mod adds looks like the same app.

| Screen | What changed |
| --- | --- |
| Pause menu | the four controls sit on **one rounded panel**; while recording, the button turns red and shows the elapsed time |
| Recording HUD | a **blinking red dot** on a rounded tag instead of a text bullet |
| Recording list | rounded Play (blue) / Export / Delete (red) buttons over a rounded panel |
| Settings | **toggles** with a small switch on the right, pickers with **name left / value right**, thin sliders, rounded text fields |
| Export | the same parts, plus a **progress bar** |
| Playback | rounded control bar, thin timeline with a **round handle** and yellow markers |
| Notices | rounded buttons and a soft gradient |

**It still behaves like Minecraft.** Only the drawing is swapped out — mouse handling, **keyboard
activation (Tab to a control, Enter or Space)**, tooltips and narration all work as usual. (Since 1.21.11 the button background can no longer be overridden, so these
widgets are built from the plain widget class instead.)

## Requirements

- Minecraft **1.21.11**
- Fabric Loader 0.16.0+ (any 1.21.11-compatible version)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- (optional) [Mod Menu](https://modrinth.com/mod/modmenu) 17.x for the settings screen
- ffmpeg, if you want audio (on your PATH, or set its location in the settings)
- (optional) [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat), if you want voice chat
  on its own track — everything works without it

Japanese and English are both supported.

## License

MIT
