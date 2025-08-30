
# Fluffy - Android TV File Manager

![Banner](assets/banner.svg)

> The UI is mostly TV oriented, certain features that are not possible to be executed in minimal way (like extraction notifications not being possible on TV) are not present.

A fast, modern file manager with powerful archive support and an Android TV–friendly UI.

## Features
- File browsing: internal storage or any SAF folder
- Archives: create ZIP/7z; extract ZIP, 7z, TAR, TGZ, TBZ2, TXZ, APK/JAR
- Encrypted archives: ZIP (AES) and 7z
- Open archives as folders; extract selected paths
- Background tasks (slightly unreliable, can't request for always on bg permission on TV)
- Android TV layout, focus rings, and large targets
- Compose UI, Material 3

## Downloads
- GH Releases
- F‑Droid
- IzzyOnDroid
- Google Play: Coming soon (currently testing with, uhm *test* accounts)

## Permissions
- Storage / All files access (Android 11+): optional. Use SAF-only mode if you don’t grant it.

## Other notes
- Path traversal is prevented during extraction.
- Large archives are streamed when possible; 7z listing may stage to cache.

## Contributing
Issues and PRs are welcome. Do run ktlint/detekt if configured.

## Installation
**Option 1**: Direct APK download from github using Downloader app by AFTV, or any other web broswer. 

**Option 2**: Share through localsend (or send files to tv).

## License
See LICENSE file for details.
