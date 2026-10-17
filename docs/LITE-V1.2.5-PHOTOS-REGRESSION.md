# Lite v1.2.5 — Google Photos no-repeat regression

## Confirmed v1.2.4 issue
`primeCacheImpl()` runs `ensureNext()`, then `primeLiteReadyCache()` and returns. The more advanced seen-hash/seen-ID cache refill below that return is unreachable in Lite. `primeLiteReadyCache()` previously excluded only IDs currently cached, not IDs/hashes previously displayed. As its small cache emptied, it downloaded the same first candidates repeatedly from the album.

## v1.2.5 changes
- Before each Lite cache refill, load persistent `seen_ids.txt` and `seen_hashes.txt` and SHA hashes of current/next Home/Lock.
- Evict disposable old cached images whose source ID or SHA was already seen. Do not delete current/next/previous wallpapers.
- Skip already-seen candidate IDs before download; reject repeated SHA after download, including collisions with the active wallpaper, queued wallpaper and already-cached images.
- Do **not** reset seen history when discovery runs out. No-repeat is prioritized over recycling the same few images.
- Expose `photos_selection_window` and `cache_refill_result` diagnostic status (index/unseen/seen counts, skipped IDs and hashes).

## Device verification before merging
1. Confirm Google Photos index size in app runtime status (`photos_index_total`, `candidate_count`, `photos_selection_window`), compare with album size ~2600. An index of 8–10, 300, etc. indicates a **separate indexing/pagination issue** that this no-repeat fix cannot resolve alone.
2. Set Home=Screen Lock, Lock=Unlock; observe at least 20 genuine events, record applied photo IDs and SHA (inspect .meta and seen files). No ID/hash should recur before available unseen photos are exhausted.
3. Repeat when Hot/Warm refill from zero, when switching between Home/Lock and when app service restarts. No current/next/previous files should be purged.
4. Test Google Photos fetch with internet temporarily unavailable; existing prepared images should still apply, and failed refill must not clear seen history.
5. Ensure downloaded media count is limited by configured Lite cache budget and no extra background CPU/network work is sustained.
6. Test backup/restore and Google Photos album link changes; resetting cycle history should be a separate intentional action, never an automatic refill fallback.

## Remaining known limitations
Google Photos shared-album discovery uses an undocumented public HTML/batchexecute pagination interface which can change. A no-repeat policy may correctly stop rotating after the *currently discovered index* is exhausted. That should be diagnosed as incomplete indexing, **not** fixed by clearing seen history.
