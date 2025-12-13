## v3.4.3




## v3.4.2

- Update AndroidManifest.xml to fix fg crash


## v3.4.1

- Fix Shizuku (duplicacy) crash from prev. update (fix #18); Fix crash on opening few folders via SAF
- Update 570.txt
- Fix lists lag for older devices
- Fix empty folders being detected as files for root/shizuku specific areas + overwrite ux improvement
- set all work calls to act in foreground


## v3.3.5

- use the file uri for a unique key (for multiple external storage issues)
- Bump actions/checkout from 5 to 6 in /.github/workflows
- [chore] Revert readme
- [chore] Add Gplay banner
- [chore] punctuation in readme
- Show gplay now that it has been accepted
- Rename Screenshot_20250822_213749.png to 4.png
- Rename Screenshot_20250822_213720.png to 3.png
- Rename Screenshot_20250822_213711.png to 2.png
- Rename Screenshot_20250822_213651.png to 1.png
- Rename Screenshot_20250829_161340.png to 5.png
- Rename Screenshot_20250829_161243.png to 4.png
- Rename Screenshot_20250829_161145.png to 3.png
- Rename Screenshot_20250829_161111.png to 2.png
- Rename Screenshot_20250829_161048.png to 1.png
- Update README.md


## v3.3.4

- Update android.yml
- [chore] Bump ver
- add canRequestManageStorage check (#13)
- add file picker intents (#14)


## v3.3.3

- Update android.yml
- [chore] Bump ver
- add canRequestManageStorage check (#13)
- add file picker intents (#14)


## v3.3.2

- [chore] Bump ver
- add canRequestManageStorage check (#13)
- add file picker intents (#14)


## v3.3.1

- [chore] Bump ver
- add canRequestManageStorage check (#13)
- add file picker intents (#14)


## v3.2.10

- Bump actions/upload-artifact from 4 to 5 in /.github/workflows
- [chore] add folder part
- Add monochrome icon, and change svg only (looks congested on small screens)
- Remove Google Play link from README
- Refactor README for better structure and content
- Do not show tasks screen for ops (bottom bar is sufficient)
- Do not exclude from recents
- Bump gradle/actions from 4 to 5 in /.github/workflows


## v3.2.9

- opens with correct index from external apps
- Allow scrolling through images in the directory (for image viewer)
- clear item counts on ops


## v3.2.8

- Clear selection on move/delete, misc cleanup


## v3.2.7

- rem network state perm


## v3.2.6

- Update android.yml


## v3.2.5

- rm unused line and add id
- Increase settings header gap
- Adapt pdf viewer colors based on theme
- Add pdf viewer (basic, no dark)


## v3.2.4

- Update android.yml
- Add zoom func to image viewer (from flicky)
- Update android.yml
- modularise image viewer too
- Viewing 7z with multiple files works with proper 7z metadata (some may not be previewed, fix #3)


## v3.2.3

- Add zoom func to image viewer (from flicky)
- Update android.yml
- modularise image viewer too
- Viewing 7z with multiple files works with proper 7z metadata (some may not be previewed, fix #3)


## v3.2.2

- change name being shown as "Open in app" back to fluffy


## v3.2.1

- Reduce duplication
- Add text viewer and audio/video viewer
- Add small file row animation


## v3.1.2

- Fix image viewer (and opening images from root works)
- View data/data folders on root, clicking without a selection acts like "open with" button
- keep aurora code for future use while removing from settings
- Set content resolver to true by default


## v3.1.1

- Update build.gradle.kts
- Add option to install apks as a button (in the archive viewer screen, fix #8?) and other misc fixes
- Update README.md


## v3.0.3

- Update README.md


## v3.0.2

- Add options to use content resolver, instead of mime; and confirm on shell writes (false as default, you know you're using root)


## v3.0.1

- Add external storage quick access buttons
- Fix #5 (Add root and shizuku support in settings)
- Update README.md
- Update README.md
- MAke dark theme the default
- Theming improvements
- remove unused files


## v2.4.6

- Add screenshots


## v2.4.5

- Change icon
- Update android.yml to set alpha as default


## v2.4.4

- Update short_description.txt


## v2.4.3

- External image viewer intent handler
- Remove unused perms, fix file colors, add more animations (and lag for older devices)


## v2.4.2

- cp paste mistakes


## v2.4.1

- Fix quick extract not showing confirmation dialog
- Add Overwrite dialog for creating archive (
- Add few anims, overwrite confirmation blueprint
- Update build.gradle.kts
- Update android.yml
- Bump actions/setup-java from 4 to 5 in /.github/workflows


## v2.3.1

- Suppress few more warns for compat
- Suppress few warns
- Move directory item counting off the UI thread, Skipping symlinks/hardlinks in TAR archives


## v2.2.15

- Update android.yml
- Add the temp icon
- Revert "rm unused files"
- Allow checkboxes to be selected via dpad
- Bring back quick access buttons
- Show tasks in modal bottom bar for easy viewing
- Update android.yml
- add screenshots
- add icon files
- Update README.md
- rm unused files
- Empty folders now have a button directly to quick access
- Update android.yml to fix gradle error
- Bump actions/checkout from 4 to 5 in /.github/workflows
- Bump gradle/actions from 3 to 4 in /.github/workflows
- Empty folders now say they are empty
- Refresh instantly on changes
- Add a setting to extract files into a folder with the same name
- Copying and moving files now works as intended
- tar progress reporting
- Opening files from an archive works
- Add missing buttons for tv
- move archive engine to app.fluffy
- Add file count for folders
- Add open with button
- Fix quick archive button (pastes all files in current dir though)
- Fix quick extract button, 7z still asks for a pass when viewing files, zipped folders do not show properly
- Individual files unarchive works
- do not navigate to tasks on extraction
- Better options bar
- Realised that it was due to dynamic colors, but hey, new setting!
- Add latest changes
- Try adding gradientish theme
- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.14

- Update android.yml
- Add the temp icon
- Revert "rm unused files"
- Allow checkboxes to be selected via dpad
- Bring back quick access buttons
- Show tasks in modal bottom bar for easy viewing
- Update android.yml
- add screenshots
- add icon files
- Update README.md
- rm unused files
- Empty folders now have a button directly to quick access
- Update android.yml to fix gradle error
- Bump actions/checkout from 4 to 5 in /.github/workflows
- Bump gradle/actions from 3 to 4 in /.github/workflows
- Empty folders now say they are empty
- Refresh instantly on changes
- Add a setting to extract files into a folder with the same name
- Copying and moving files now works as intended
- tar progress reporting
- Opening files from an archive works
- Add missing buttons for tv
- move archive engine to app.fluffy
- Add file count for folders
- Add open with button
- Fix quick archive button (pastes all files in current dir though)
- Fix quick extract button, 7z still asks for a pass when viewing files, zipped folders do not show properly
- Individual files unarchive works
- do not navigate to tasks on extraction
- Better options bar
- Realised that it was due to dynamic colors, but hey, new setting!
- Add latest changes
- Try adding gradientish theme
- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.13

- add icon files
- Update README.md
- rm unused files
- Empty folders now have a button directly to quick access
- Update android.yml to fix gradle error
- Bump actions/checkout from 4 to 5 in /.github/workflows
- Bump gradle/actions from 3 to 4 in /.github/workflows
- Empty folders now say they are empty
- Refresh instantly on changes
- Add a setting to extract files into a folder with the same name
- Copying and moving files now works as intended
- tar progress reporting
- Opening files from an archive works
- Add missing buttons for tv
- move archive engine to app.fluffy
- Add file count for folders
- Add open with button
- Fix quick archive button (pastes all files in current dir though)
- Fix quick extract button, 7z still asks for a pass when viewing files, zipped folders do not show properly
- Individual files unarchive works
- do not navigate to tasks on extraction
- Better options bar
- Realised that it was due to dynamic colors, but hey, new setting!
- Add latest changes
- Try adding gradientish theme
- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.12

- add icon files
- Update README.md
- rm unused files
- Empty folders now have a button directly to quick access
- Update android.yml to fix gradle error
- Bump actions/checkout from 4 to 5 in /.github/workflows
- Bump gradle/actions from 3 to 4 in /.github/workflows
- Empty folders now say they are empty
- Refresh instantly on changes
- Add a setting to extract files into a folder with the same name
- Copying and moving files now works as intended
- tar progress reporting
- Opening files from an archive works
- Add missing buttons for tv
- move archive engine to app.fluffy
- Add file count for folders
- Add open with button
- Fix quick archive button (pastes all files in current dir though)
- Fix quick extract button, 7z still asks for a pass when viewing files, zipped folders do not show properly
- Individual files unarchive works
- do not navigate to tasks on extraction
- Better options bar
- Realised that it was due to dynamic colors, but hey, new setting!
- Add latest changes
- Try adding gradientish theme
- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.11

- Empty folders now have a button directly to quick access
- Update android.yml to fix gradle error
- Bump actions/checkout from 4 to 5 in /.github/workflows
- Bump gradle/actions from 3 to 4 in /.github/workflows
- Empty folders now say they are empty
- Refresh instantly on changes
- Add a setting to extract files into a folder with the same name
- Copying and moving files now works as intended
- tar progress reporting
- Opening files from an archive works
- Add missing buttons for tv
- move archive engine to app.fluffy
- Add file count for folders
- Add open with button
- Fix quick archive button (pastes all files in current dir though)
- Fix quick extract button, 7z still asks for a pass when viewing files, zipped folders do not show properly
- Individual files unarchive works
- do not navigate to tasks on extraction
- Better options bar
- Realised that it was due to dynamic colors, but hey, new setting!
- Add latest changes
- Try adding gradientish theme
- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.10

- Empty folders now say they are empty
- Refresh instantly on changes
- Add a setting to extract files into a folder with the same name
- Copying and moving files now works as intended
- tar progress reporting
- Opening files from an archive works
- Add missing buttons for tv
- move archive engine to app.fluffy
- Add file count for folders
- Add open with button
- Fix quick archive button (pastes all files in current dir though)
- Fix quick extract button, 7z still asks for a pass when viewing files, zipped folders do not show properly
- Individual files unarchive works
- do not navigate to tasks on extraction
- Better options bar
- Realised that it was due to dynamic colors, but hey, new setting!
- Add latest changes
- Try adding gradientish theme
- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.9

- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.8

- Update android.yml
- Update build.gradle.kts
- Previous openAsFolder change commit
- fix -Ptargetabi building all abis
- Add error screen instead of just crashing
- bootable, testable (basic) push with zipxtract like screen for now
- first commit


## v2.2.7

- Remove unused firebase lib and downgrade gradle for fdroid compat


## v2.2.6

- fix workflow
- use a property to toggle splitting
- Update android.yml to use gradle kts params instead
- Update android.yml
- Update android.yml
- Add app placeholder icons (if no icon)
- Force sync on changing repositories
- Fix fdroid archive crash on sync
- Try improving installer to prevent duplicate sha256 checks
- Fix app descriptions with <html> tags
- add tv screenshots
- add aab upload (try fooling gplay to allow?)
- Fix crash due to duplicate keys in updates screen


## v2.2.5

- use a property to toggle splitting
- Update android.yml to use gradle kts params instead
- Update android.yml
- Update android.yml
- Add app placeholder icons (if no icon)
- Force sync on changing repositories
- Fix fdroid archive crash on sync
- Try improving installer to prevent duplicate sha256 checks
- Fix app descriptions with <html> tags
- add tv screenshots
- add aab upload (try fooling gplay to allow?)
- Fix crash due to duplicate keys in updates screen


## v2.2.4

- Update android.yml to use gradle kts params instead
- Update android.yml
- Update android.yml
- Add app placeholder icons (if no icon)
- Force sync on changing repositories
- Fix fdroid archive crash on sync
- Try improving installer to prevent duplicate sha256 checks
- Fix app descriptions with <html> tags
- add tv screenshots
- add aab upload (try fooling gplay to allow?)
- Fix crash due to duplicate keys in updates screen


## v2.2.3

- Update android.yml
- Update android.yml
- Add app placeholder icons (if no icon)
- Force sync on changing repositories
- Fix fdroid archive crash on sync
- Try improving installer to prevent duplicate sha256 checks
- Fix app descriptions with <html> tags
- add tv screenshots
- add aab upload (try fooling gplay to allow?)
- Fix crash due to duplicate keys in updates screen


## v2.2.2

- Update android.yml
- Add app placeholder icons (if no icon)
- Force sync on changing repositories
- Fix fdroid archive crash on sync
- Try improving installer to prevent duplicate sha256 checks
- Fix app descriptions with <html> tags
- add tv screenshots
- add aab upload (try fooling gplay to allow?)
- Fix crash due to duplicate keys in updates screen


## v2.2.1

- Add app placeholder icons (if no icon)
- Force sync on changing repositories
- Fix fdroid archive crash on sync
- Try improving installer to prevent duplicate sha256 checks
- Fix app descriptions with <html> tags
- add tv screenshots
- add aab upload (try fooling gplay to allow?)
- Fix crash due to duplicate keys in updates screen


## v2.1.1

- Bump build.gradle.kts
- Add default sort and wifi-only setting
- Add theme and dynamic theme settings
- Add theme and dynamic theme setting
- Remove odd divider for TV


## v2.0.1

- revert build.gradle.kts
- Delete fastlane/metadata/android/en-US/changelogs/120.txt
- Delete fastlane/metadata/android/en-US/changelogs/150.txt
- Delete fastlane/metadata/android/en-US/changelogs/140.txt
- Delete fastlane/metadata/android/en-US/changelogs/130.txt
- Delete CHANGELOG.md
- Bump ver
- Update README.md
- Time for the initial kotlin release (satisfactory, need to open <html> "desc." issue, and screenshots)
- Make a prerelease (Stable enough / test on TV)
- UI changes
- Downloads, but to downloads folder
- Fix wrong indexv2 format usage
- Migrations related changes, and use alias for ksp
- Save 2
- Save for now
- Update README.md
- Update README.md
- Update README.md
- Update README.md
- first boot save (kotlin switch)
- Seperation refactor
- try also adding android ui
- rm unused repo service file
- delete duplicate theme, and other duplis
- Update README.md
- better theme
- focus for repository add button
- fix focus for categories
- log back to print
- warn fixes
- add icons properly
- wrap themeoption also in inkwell and add focusing
- Update README.md


# Changelog

## v2.0.4

- Delete CHANGELOG.md
- Bump ver
- Update README.md
- Time for the initial kotlin release (satisfactory, need to open <html> "desc." issue, and screenshots)
- Make a prerelease (Stable enough / test on TV)
- UI changes
- Downloads, but to downloads folder
- Fix wrong indexv2 format usage
- Migrations related changes, and use alias for ksp
- Save 2
- Save for now
- Update README.md
- Update README.md
- Update README.md
- Update README.md
- first boot save (kotlin switch)
- Seperation refactor
- try also adding android ui
- rm unused repo service file
- delete duplicate theme, and other duplis
- Update README.md
- better theme
- focus for repository add button
- fix focus for categories
- log back to print
- warn fixes
- add icons properly
- wrap themeoption also in inkwell and add focusing
- Update README.md

