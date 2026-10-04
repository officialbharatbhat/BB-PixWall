# BB-PixWall v1.0 beta4 — next-level foundation

Built on the tested beta3 stable behavior. The beta3 stability fixes are included in this source.

## Implemented in this build

- LAN client disconnect hardening: broken pipe / ordinary browser disconnects cannot take down the app process.
- Cache single-flight guard and cached source-ID reservation to stop parallel duplicate downloads.
- Applied-wall history recording for SHA-256 + source IDs with 10,000-entry caps.
- Root diagnostics: grant state, provider string, probe latency, supported capability matrix, verified Doze whitelist, AppOps, priority/nice and OOM score results.
- Root actions are capability-gated. Unsupported hooks are not blindly executed.
- Root diagnostics are visible in the app, Web dashboard, runtime log and exported debug report.
- Conservative self-healing engine health audit: storage check, cache integrity quarantine, offline next-wall recovery and low-cache refill scheduling.
- Quality-first Smart Crop toggle for a 9:20 library.
  - 9:20-compatible images are streamed untouched to WallpaperManager.
  - No BB-PixWall crop, resize, bitmap decode or app-side recompression on the normal 9:20 path.
  - Smart Crop runs only on a detected aspect mismatch.
  - Mismatch crop attempts full-resolution center crop. If that cannot be done safely, the original is streamed instead of silently downsampling.
  - Exact source file is always retained for Save Wall/unblur/preview metadata.
- Lean local storage toggle. When enabled, Advance mode respects the configured cache target instead of forcing a 16-wall minimum.
- Google Photos / Drive source count delta diagnostics, so additions/deletions become visible after index refresh.
- Manual Prepare/Refill forces a fresh cloud index so newly added or deleted album items can be picked up immediately.

## Deliberately not faked

The larger ideas discussed for content-aware mood/taste learning, OLED visual scoring, semantic story sequencing and subject-aware composition need a real scoring/index layer. They are not represented by decorative toggles in this build. Beta4 lays the diagnostics/quality/self-healing foundation first so those features can be added without destabilizing the working engine.

## Quality contract

For Bharat's devices and album, 9:20 is the canonical aspect ratio. A compatible source stays untouched inside BB-PixWall unless Blur is explicitly enabled. Android/OEM WallpaperManager may still internally transform its own system wallpaper copy; BB-PixWall does not control that internal representation.
