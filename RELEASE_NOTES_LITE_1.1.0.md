# BB-PixWall Lite v1.1.0 — build candidate

This source update extends Lite v1.0.0 without reinstating the heavy Full-edition AI engines.

## Included changes
- Safe Wall: two independent picker-selected original Home/Lock images, stored in private app files; shield Quick Settings toggle with persistent pause and restore of previous originals
- Previous Wall Quick Settings tile using the existing previous-wall engine
- History reduced from 40 to 10 entries, with automatic pruning of legacy history; same on-disk limit used by LAN Web Remote
- Existing 32 Hot + 64 Warm locally prepared source reserve and duplicate-avoidance candidate selection retained
- Deferred cloud refill under low battery, battery saver, thermal pressure, or offline network conditions; local wallpaper application remains available
- Network availability event triggers cache recovery instead of frequent polling
- BB-Remote now exposes Safe Wall state and rejects wallpaper-changing actions while Safe Wall is enabled
- In-app official GitHub release update check, user initiated only, no background updates
- Safe wallpaper preview cards with downsampled UI-only thumbnails
- New Safe Wall and Previous Wall Quick Settings icons

## Image quality
When blur is off, the unmodified locally stored source file is streamed directly to Android's WallpaperManager. No BB-PixWall recompression or resize is performed. Android itself may still transform final wallpaper contents. Safe Wall selection and restore similarly stream original saved bytes.

## Release policy
Do not publish or tag this source as a stable release until local Android SDK 37 compile, install, and behavior validation complete. Existing v1.0.0 signed release remains untouched.

## Test checklist
1. :app:assembleDebug succeeds with Android SDK 37
2. Choose Safe Home/Lock -> activate via tile -> screen off/on and interval cannot change wallpaper -> deactivate restores both prior originals
3. Reboot while Safe Wall active -> safe images remain selected and locked
4. Previous tile restores the last Home/Lock originals after normal auto/manual apply
5. History is max 10 directories on external storage and max 10 entries in BB-Remote
6. Offline rotation uses Hot/Warm cache and new network availability resumes cloud refill
7. Blur off means original source stream; blur on retains original source
8. BB-Remote Safe Wall chip matches tile state and wallpaper mutations are blocked while active
9. GitHub checker ignores Full releases and only compares stable Lite release tags
