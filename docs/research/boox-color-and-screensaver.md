# BOOX Color and Screensaver Compatibility

Date: 2026-07-23

## Result

CodexBar Ink will use a capability ladder rather than a BOOX-private screensaver API:

1. The normal dashboard uses restrained color accents with independent text labels and strong grayscale contrast.
2. The BOOX display adapter detects a color-capable Onyx device when the public SDK reports one. It keeps the existing
   `REGAL`-when-supported, otherwise `GU`, partial-refresh policy and `GC` cleanup.
3. The reader can export the current sanitized dashboard as a PNG in shared `Pictures/CodexBar Ink` storage. The user
   selects that image through BOOX **Apps > Screensaver > Image Screensaver** or the image viewer's **Set As**
   operation.
4. The app also registers a standard Android `DreamService` backed by the last exported image. This is available on
   readers that expose Android's dream picker, but it is not treated as proof that BOOX firmware will use it for
   suspend.
5. BOOX **Transparent Screensaver** is the zero-copy option: it retains the current foreground dashboard when the
   device sleeps. Users must opt in because usage data remains visible while the device is locked.

The complete path was verified on a color BOOX Leaf3C running firmware 4.2:

- The Onyx adapter detected a color display and selected `GU` partial refresh because this device reports no `REGAL`
  support.
- The muted teal Codex card and muted peach Claude card remained distinguishable without carrying meaning by color
  alone.
- **SCREEN** exported a 1264 x 1680 RGBA PNG with the action row removed.
- BOOX Gallery's **More > Set As > Set as screensaver** flow accepted the image.
- After sleep, the actual BOOX lock screen displayed the exported dashboard with BOOX's battery and wake affordance.

This proves the native image-screensaver path. The registered `DreamService` remains a compatibility fallback for
Android readers that expose the system Dreams picker; BOOX firmware did not use it for suspend.

## Evidence

- BOOX documents Image, Memo, Clock, Transparent, and Monthly Calendar screensaver styles. Image Screensaver accepts
  local images; Transparent Screensaver retains the current screen.
  <https://help.boox.com/hc/en-us/articles/8569260546196-Screensavers>
- BOOX documents selecting a saved PNG and using **Set As > Set as screensaver**.
  <https://help.boox.com/hc/en-us/articles/4577439831828-How-to-set-a-book-cover-as-a-screensaver>
- BOOX documents per-app refresh configuration and notes that color and monochrome devices differ. Regal targets
  cleaner static/image-rich content, while faster modes trade quality for motion.
  <https://help.boox.com/hc/en-us/articles/8569262708372-Refresh-Modes>
- BOOX documents color-device Vividness and Color Brightness controls as per-app settings.
  <https://help.boox.com/hc/en-us/articles/10701256390548-Contrast-and-Color>
- Android's public screensaver surface is `DreamService`; selection remains a system/user decision.
  <https://developer.android.com/reference/android/service/dreams/DreamService>
- Android 10+ permits an app to add and update its own shared images through `MediaStore` without broad storage
  permission.
  <https://developer.android.com/training/data-storage/shared/media>

## Compatibility and privacy

| Capability | BOOX Leaf3C 4.2 | Other Android e-readers | Failure behavior |
| --- | --- | --- | --- |
| Muted color dashboard | Verified native color; still grayscale-readable | Native color or grayscale conversion | Text and borders retain meaning |
| Onyx partial refresh | Verified `GU`; `GC` manual cleanup | Not used | Standard Android invalidation |
| Exported PNG | Verified through Gallery and actual sleep screen | Select with vendor image/screensaver UI | Image remains in Pictures |
| Android `DreamService` | Firmware-dependent; not assumed | Works if system exposes Dreams | App and image export still work |
| Transparent screensaver | BOOX built-in option | Vendor-dependent | Use exported PNG |

The exported image contains only the already-sanitized Reader presentation, never provider credentials or the reader
token. It can expose usage values on a lock screen, so export requires an explicit privacy confirmation.
