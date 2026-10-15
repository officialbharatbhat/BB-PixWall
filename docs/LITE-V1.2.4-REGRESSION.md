# BB-PixWall Lite v1.2.4 audit regression matrix

This is a candidate build, **not** a verified release. Keep `lite-v1.2.3` intact until device verification succeeds.

## Device tests (Android 16 + secure PIN/password)
- Home=Screen Lock, Lock=Unlock: lock from unlocked launcher; Home changes once; repeatedly wake/sleep while still locked and verify Home does not change; PIN-unlock and verify Lock changes once.
- Home=Screen Lock, Lock=Screen Lock: both targets change on a single genuine secure lock; no extra change for notification wake/sleep.
- Home=Unlock, Lock=Unlock: both targets change only after authentic user unlock.
- Home=Interval 1 min, Lock=Interval 2 min: check logs after several cycles; switching unrelated appearance/blur settings must not restart countdown.
- Home=Interval, Lock=Screen Lock; reverse the modes and repeat to check no cross-target interference.
- Change Home order Random -> A-Z with Lock order unchanged; prepared pair must be invalidated. Repeat changing Lock order only.
- Test manual Next with Target=Home, Lock, Both and Split; automatic trigger targets must still ignore this manual selector.
- Kill/restart foreground service while locked and unlocked; reboot and verify secure-lock cycles and interval scheduling.
- With network unavailable and cold cache, automatic triggers report `no-ready-wall`; after network resumes ensure future triggers recover without duplicate changes.
- Inspect ready cache count/space for Lite configured 4: intended maximum 4 Hot + 8 Warm, excluding current/next/previous images.
- Test Safe Wall, LAN remote, blur, previous, save/favorite and backup/restore for regressions.

## Remaining platform constraints
- `AlarmManager.setAndAllowWhileIdle` is inexact under Doze; 1-minute selection is **not** a guaranteed 1-minute firing rate.
- Lock-detection bounded checks rely on Android handler scheduling while the foreground service is alive; deep sleep can delay confirmation.
- `WallpaperManager` Home+Lock commits are not hardware-atomic and recovery is best-effort.
- This repository has no Android instrumented device-test runner; do not call this matrix device-tested until executed.
