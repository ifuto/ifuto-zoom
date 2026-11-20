workdir: ifuto-fastplace
artifact_path: ifuto-fastplace/build/libs/*.jar
# 2026-09-17-06:03:20
# 2026-09-17-06:32:25
# 2026-09-17-06:35:40
# 2026-09-17-11:04:27
# 2026-09-17-11:09:22
# 2026-09-17-11:11:21
# 2026-09-17-11:14:28
# 2026-09-17-11:16:43
# 2026-10-03-22:37:55
# 2026-10-03-23:22:39
# 2026-10-03-23:27:12
# 2026-10-05-replay-perf
# 2026-10-05-fix-exporter-tail
# 2026-10-05-restore
# 2026-10-05-cast
# 2026-10-05-diag
# 2026-10-05-notice
# 2026-10-05-r2
- tput r100: 0.16.0 export display-uncap + x264 preset
- tput r101: PlayerInput stance fix
- tput r102: 0.17.0 cuda pixel convert
- tput r102: 0.17.0 per-render export advance
- tput r103: 0.18.0 volume-mute fix
- tput r104: 0.19.0 sound/queue/encode-warn
- tput r105: 0.20.0 fastest button
- tput r106: 0.21.0 hardware auto-tune
- tput r107: 0.22.0 heavy-mod detection
- tput r108: 0.23.0 reader caps + no manual tick
- tput r109: 0.24.0 text-field clip
- tput r110: 0.25.0 text-field centering
- tput r111: 0.26.0 fast export
- tput r112: 0.26.0 no-PBO retry
- tput r113: 0.27.0 background export
- tput r114: 0.28.0 smooth ETA
- tput r115: 0.29.0 enterWorld
- tput r116: kb 0.1.0 scaffold
- tput r117: kb 0.2.0 measure+gui+selftest
- tput r118: kb 0.2.0 armor-fix
- tput r119: kb 0.2.0 dup-tail-fix
- tput r120: kb 0.2.0 equipment-restore
- tput r121: kb 0.2.0 spike-fix+tests
- tput r122: kb 0.2.0 spike-fix-reapply
- tput r123: kb 0.2.0 spikefit-reapply
- tput r124: kb 0.2.0 robustness
- tput r125: kb 0.2.0 protected-fix
- tput r126: f3 0.1.0 scaffold
- tput r127: f3 0.1.0 typo-fix
- tput r128: f3 0.1.0 fps-word-fix
- tput r129: replay 0.30.0 snapshot-async
- tput r130: replay 0.30.0 nullable-fix
- tput r131: f3 0.2.0 module screen + scaffold
- tput r132: f3 0.2.0 build-check
- tput r133: fastplace 0.1.0 new mod
- tput r134: fastplace 0.1.0 long-cast fix
- tput r135: fastplace human-timing model per literature
- tput r136: fastplace no-sources-jar (fix wrong-jar startup crash)
- tput r137: fastplace 0.1.1 version bump for unambiguous artifact
- tput r138: fastplace mixin package fix + CI mixin-class verification
# 2026-11-16-fastplace-scaffold
- tput r139: fastplace 0.2.0 module screen + FastPlace keybind + god bridge scaffold
- tput r140: fastplace 0.2.1 scaffold aim fixes (sub-stepped pursuit, 3deg saccade threshold, jump-strip via KeyboardInput) 
- tput r141: fastplace 0.2.2 saccade plan no longer restarts on its own progress (main-sequence timing) 
- tput r142: fastplace 0.3.0 melee aim assist module (human tracking model + hitbox safety) 
- tput r143: fastplace 0.3.1 melee weapon detection via Items (SwordItem/AxeItem are gone in 1.21) + CI annotation shows symbol detail 
- tput r144: fastplace 0.3.2 aim assist lead gain 0.40 + goal/true combined safety rule (no per-tick tug of war) 
- tput r145: fastplace 0.4.0 Auto Tool module (human tool-choice latency model) 
- tput r146: fastplace 0.4.1 crash fix (class_743 mixin) — drop inherited @Shadow in KeyboardInputMixin, mixin config required=false + jump-hook warning 
- tput r147: fastplace 0.4.2 compile fix (FastPlaceMod was missing the MinecraftClient import; 0.4.1 never built) 
- tput r148: fastgenerate 0.1.0 new mod — predictive chunk prefetch (stride/Markov) + AIMD window (Little's law) + bg thread tuning for weak CPUs
- tput r149: fastplace 0.5.0 aim assist click-acquire + per-frame smoothing + strafe bias, ClickCrystal module, The Client. GUI
- tput r150: fastplace 0.5.0 compile fix — JOML Matrix3x2fStack pushMatrix/popMatrix + 2-arg translate/scale, clearLine takes World (Entity#getWorld removed in 1.21.11)
- tput r151: fastplace 0.5.0 — lazy melee weapon table (static Items.* init crashed testAim with 'Not bootstrapped: minecraft:game_event')
- tput r152: fastplace 0.5.1 frame-driven placement + no-repeat intervals + virtual key reads, ClickGUI blur backdrop; replay 0.30.1 deferred chunk packets + Essential-safe pause buttons
- tput r153: replay 0.30.1 build check (deferred chunk packets, Essential-safe pause panel)
- tput r154: replay 0.30.1 full build (chunk deferral + Essential pause panel)
- tput r155: replay 0.30.2 missing-GameJoin fix (synthetic world + minimal snapshot prefix)
- tput r156: replay 0.30.2 compile fix — RegistryEntryLookup#getOptional for the dimension entry
- tput r157: replay 0.30.2 compile fix 2 — drop Registries.DIMENSION_TYPE fallback
- tput r158: replay 0.30.3 — minecraft:login decode failure is not fatal + dimension type fallback
- tput r159: replay 0.30.3 compile fix — don't name ClientWorld (not imported)
- tput r160: replay 0.30.4 — remember live registries at join; use them when the recording lacks dimension_type
- tput r161: replay 0.30.4 compile fix — annotate the nested type (DynamicRegistryManager.@Nullable Immutable)
- tput r162: replay 0.30.5 — layered fallbacks (alt codec, join re-decode, dimension-type candidates, real error text)
- tput r163: replay 0.30.5 build (the r162 run built fastplace by mistake)
- tput r164: fastplace 0.6.0 rounded web-like ClickGUI (no chamfer, right-click menu), hold-to-repeat ClickCrystal with real aim/LOS, smooth aim assist (no min-speed buzz), Auto Stun Slam, Auto Safe Anchor
- tput r164a: 0.6.0 compile fix (getPos/withY)
- tput r164b: CI annotations include self-test failures
- tput r164c: self-tests throw on failure (CI annotation)
- tput r164d: fixplace UI docs + interaction-hook warning
