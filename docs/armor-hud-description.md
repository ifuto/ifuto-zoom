# ifuto-armor-hud

**Armor HUD for Fabric (MC 1.21.11+) — your equipped armor and durability, right next to the hotbar, drawn pixel-perfect with vanilla's own textures and renderer.**

No custom-drawn shapes, no floating panels. Everything you see is cut straight out of Minecraft's real `hotbar.png` / `hotbar_offhand_left.png` textures and rendered by vanilla code paths, so it sits in your UI like it was always there — and resource packs restyle it automatically.

---

## ✨ What you get

### 🎯 A HUD that belongs to the hotbar
- Docks to the **left or right side of the hotbar**, as a **vertical column or a horizontal strip**
- **Frame style switches automatically with the slot gap:**
  - Gap **0** → seamless **vanilla hotbar strip** (square corners, exact 20 px cells, 1:1 pixel crop)
  - Gap **1+** → **offhand-style rounded box** (rounded corners, exact 22 px crop)
- Or go minimalist with **ghost-icon mode**: vanilla's own equipment silhouette icons, no frames
- **Default distance is 7 px** — the exact blank space vanilla builds into its offhand widget. It looks factory-installed
- **Offhand-aware**: automatically steps aside when your offhand slot is occupied — for left-hand players too
- Follows the hotbar's honesty: hidden with **F1**, hidden for **spectators**, and drawn **under the screen blur** when you open the inventory (just like the hotbar)

### 🛡️ Durability, the vanilla way
- Slot contents use **vanilla's own stack overlay** — the real durability bar and the real count-style font, straight from the game
- Info modes (independent for **inside** the slot and **outside**):
  - None / Gauge / Remaining **%** / Remaining **count** / **Lost** count / Lost **%**
- **Clean at full health**: gauges hide while durability is full *or barely worn* (damage ≤ 1), so server-or-plugin-distributed cosmetic gear stays pristine-looking
- **Low-durability blink warning** with an adjustable threshold (1–50 %)
- Outside text gets a subtle **dynamic contrast** backing so it stays readable on any surface

### 🎛️ Shape it to your setup
- Vertical / horizontal layout, slot gap, distance from the hotbar, X/Y fine offsets, HUD scale (50–150 %)
- Empty slots: keep, dim, or hide; per-slot ghost icons
- Show condition: **always / when damaged / when critical only**
- **Toggle keybind** to show or hide the HUD in a flash
- Settings screen opens from **Mod Menu**: a proper vanilla-style, **scrollable** layout that fits any window size — including a one-tap **Reset** button

### 💬 Discord Rich Presence (optional)
- Show your armor situation on your Discord profile
- Enter your app **Client ID** in settings and it's connected; leave it blank and the integration stays fully off (and never touches other mods' rich presence)

### 🌍 15 languages out of the box
English (US), Japanese, German, Spanish, French, Italian, Korean, Polish, Portuguese (BR), Russian, Ukrainian, Turkish, Chinese (Simplified), Chinese (Traditional), Dutch.

---

## 🔌 Details
- **Client-side only** — vanilla servers welcome
- **No server mod needed**, no invasive hooks: textures and identifiers read straight from vanilla
- Requires **Fabric Loader** (Fabric API + Mod Menu recommended)
- Tested on **MC 1.21.11**
