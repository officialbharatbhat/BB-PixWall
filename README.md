# BB-PixWall Lite v1.0.0

BB-PixWall Lite is the lightweight edition of BB-PixWall, focused on fast automatic wallpaper changes with original-quality wallpaper application.

## Highlights
- Google Photos primary source
- Google Drive fallback
- Optional local wallpaper source
- Home, Lock, Both Same, and Home/Lock Split targets
- Screen Lock, Screen Unlock, and interval triggers
- Hot/Warm offline wallpaper reserve
- Original-quality wallpaper stream when Blur is OFF
- Independent Home and Lock blur controls
- Lightweight LAN Web Remote
- Current and Next wallpaper previews
- History and restore support
- Quick Settings tiles for Next, Save, Blur, and BB-Remote
- Standard and Advanced/root modes
- Light, Dark, System, and Pure Black appearance modes

## Lite design
BB-PixWall Lite intentionally removes the heavier adaptive/AI wallpaper engines from the Full edition. The runtime path is kept focused on:

```
Source -> prepared local cache -> trigger -> target -> WallpaperManager
```

When Blur is OFF, the downloaded/original wallpaper file is streamed directly to Android's WallpaperManager without a BB-PixWall resize/recompression step.

## Remote
BB-Remote is LAN-only. The controlling browser/device must be on the same local network as the Android device running BB-PixWall Lite.

## Requirements
- Android 15+ (minSdk 35)
- Internet permission for cloud sources
- Storage/media permissions as required by Android
- Root is optional; Standard mode works without root

## Package
`bb.pixwall.lite`

## Version
`1.0.0`

## Developer
**Bharat Bhat**

- Email: bkbhatinfo@gmail.com
- Instagram: @officialbharatbhat
- Facebook: officialbharatbhat
- Telegram: BharatBhat
- YouTube: sloverbofficial
