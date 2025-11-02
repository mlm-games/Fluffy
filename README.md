# Fluffy — Android (TV) File Manager

<p align="center">
  <a href="https://f-droid.org/packages/app.fluffy/">
    <img src="https://f-droid.org/badge/get-it-on.png" height="80" alt="Get it on F-Droid">
  </a>
  <a href="https://apt.izzysoft.de/packages/app.fluffy">
    <img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="54" alt="Get it at IzzyOnDroid">
  </a>
  <a href="https://play.google.com/store/apps/details?id=app.fluffy">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="80" alt="Get it on Google Play">
  </a>
  <!-- <a href="https://github.com/mlm-games/fluffy/releases/latest">
    <img src="https://img.shields.io/badge/Get%20it%20on-GitHub%20Releases-24292e?style=for-the-badge&logo=github&logoColor=white" height="80" alt="Get it on GitHub Releases">
  </a> -->
</p>

<p align="center">
  <a href="https://f-droid.org/packages/app.fluffy/">
    <img alt="F-Droid version"
         src="https://img.shields.io/f-droid/v/app.fluffy?logo=f-droid&logoColor=white&label=F-Droid&labelColor=1976d2&color=1976d2">
  </a>
  <a href="https://apt.izzysoft.de/packages/app.fluffy">
    <img alt="IzzyOnDroid version"
         src="https://img.shields.io/f-droid/v/app.fluffy?baseUrl=https://apt.izzysoft.de/fdroid&label=IzzyOnDroid&labelColor=1b1f23&color=00a3d9&logo=android&logoColor=white">
  <!-- </a>
  <a href="https://shields.rbtlog.dev/app.fluffy">
    <img src="https://shields.rbtlog.dev/simple/app.fluffy?style=for-the-badge" alt="RB Status">
  </a> -->
</p>

> UI is primarily TV‑oriented; features that don’t fit TV constraints (e.g., certain notifications) are omitted.

A fast, modern file manager with powerful archive support and an Android TV–friendly UI.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screenshot 1" width="24%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Screenshot 2" width="24%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screenshot 3" width="24%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Screenshot 4" width="24%">
</p>

## Features
- Browse internal storage or any SAF folder
- Create ZIP/7z; extract ZIP, 7z, TAR, TGZ, TBZ2, TXZ, APK/JAR
- Encrypted archives: ZIP (AES) and 7z
- Open archives like folders; extract selected paths
- Background tasks
- Android TV layout with DPAD/keyboard support
- Compose UI, Material 3

## Permissions
- Storage / “All files” access (Android 11+): optional. SAF‑only mode works without it.

## Notes
- Path traversal is prevented during extraction.
- Large archives stream when possible; 7z listing may stage to cache.

## Contributing
Issues and PRs are welcome :)

## Installation
- Option 1: Direct APK from GitHub Releases (e.g., Downloader app on Fire TV, or a browser).
- Option 2: Share via LocalSend (or any file transfer to TV).
- Option 3: Available on GPlay Store for Android TV

## License
See [LICENSE](LICENSE).
