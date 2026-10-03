# BB-PixWall v1.0.0-beta3

Hardening build focused on the failures observed on-device after beta2.

## Fixed in beta3
- LAN dashboard: local HTTP explicitly allowed, raw-socket health probe, server watchdog, and network-change rebind race removed.
- Google Photos/Drive text entry: cloud parsing is debounced instead of running for every typed/pasted character.
- Ordering changes: debounce + serialized preparation to avoid first-run Random/Surprise crashes.
- Screen-off path: cloud I/O no longer holds the wallpaper apply/file lock; cached next assets can apply while refill continues.
- Startup: local/offline preparation first, cloud warm-up deferred off the first-frame path.
- 2100+ album repeat bug: SHA history and source-ID history raised to 10,000, and source IDs are now enforced before download; cycle resets only after the candidate pool is exhausted.
- Cache refill duplicate checking is O(1)-style set-based instead of repeatedly hashing the entire cache for every candidate.
- Cloud parsed candidate index is retained for 10 minutes to avoid repeatedly reparsing huge shared albums.
- Legacy direct /cache images are migrated into /cache/warm.
- Diagnostics show hot/warm/queue cache breakdown and cycle progress.
- Web dashboard refresh interval reduced to 3 seconds.

## Storage
/sdcard/wallpaper/
  backup/
  cache/hot/
  cache/warm/
  local/
  logs/
  quarantine/
  queue/
  quee/  (legacy compatibility)
  saved/
