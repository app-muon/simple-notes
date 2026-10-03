# Secure Notes

A native offline notes app for a Pixel 8 running **Android 15 or later**. The app has no Internet permission, accounts, analytics, or automatic backup. Its product source is [the specification](secure_simple_notes_android_spec.md), with the clarifications recorded below.

## Build and install

Open this directory in Android Studio, install Android SDK 37, and use the included Gradle wrapper. The project uses AGP 9.3.3 / Gradle 9.5.0, Kotlin, Compose / Material 3, Room / SQLCipher, Android Keystore, Tink, and DataStore. Dependencies are pinned in `gradle/libs.versions.toml`.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is normally in `app/build/outputs/apk/debug/`. On Windows, Dropbox can lock generated native-library directories. Build outside the synced directory when necessary:

```powershell
.\gradlew.bat "-Pnotes.buildRoot=$env:TEMP\secure-notes-build" :app:assembleDebug
```

Then the APK is in `%TEMP%\secure-notes-build\app\outputs\apk\debug\`. Android 15 is the minimum (`minSdk 35`) because it supplies PDF text extraction and fixes older unlocked-device Keystore behavior. The app compiles and targets Android 17 (`compileSdk/targetSdk 37`). No device admin, accessibility-service, broad storage, camera, or network permissions are requested. Camera capture is delegated to the installed camera application.

For a personal release, use `scripts/build-release.ps1`. It creates a dedicated signing key **outside the repository**, protects its password with Windows DPAPI, and reuses that identity for updates. It never uses the Android debug key for a release. Protect and back up the signing material separately; losing it prevents in-place updates. Alternative CI signing uses `NOTES_KEYSTORE`, `NOTES_STORE_PASSWORD`, `NOTES_KEY_ALIAS`, and `NOTES_KEY_PASSWORD` environment variables.

## Using the app

Configure a device PIN/password/pattern first. Unlock with a strong biometric or your device credential. Fresh processes require authentication. Ordinary app switching leaves the session unlocked; device locking ends it. Screenshots and normal Recent Apps previews remain available.

The list shows titles only. New notes focus the title. Existing notes open in reading mode; tap text to edit. Formatting and the single attachment menu appear only while editing. Bold applies to selected text within one block. Enter creates a following block; Backspace at a block's start joins adjacent text. Lists are single-level. Checklist boxes work in reading mode. Back first leaves editing, then returns to the list. Empty new drafts are discarded; clearing an existing note does not silently delete it.

Images and files can be moved with a long-press drag or their Move up/Move down menu. Images open in a zoomable viewer. Files open through temporary read-only content URIs in another application. Text, links, images, and files can be shared **into** the app after choosing a destination. There is no note-export/share action.

Search includes titles, body text, filenames, and locally extracted text from PDFs and UTF-8 text files, including Markdown and CSV. Search ignores case/diacritics, supports prefixes, and tolerates one spelling error for words of four or more characters. Matching terms and postings are paged without an arbitrary result cutoff. Scanned/password-protected/unsupported documents remain searchable by filename. Search indexes are rebuilt locally after restore.

## Backup and recovery

Set a separate backup password in Settings. Enter it for every backup; only an encrypted salted verifier is retained. An authenticated app session can change it without entering the old password. Changing it affects future backups, not existing files. Forgetting a backup's password makes that backup unrecoverable.

Backups are created as encrypted `.ssnb` files before Android's save picker opens. A cloud provider in that picker is controlled by Android/the selected provider; the app itself has no networking capability. Restore validates the entire backup, then asks before replacing all notes/settings. No safety backup or merge is performed. Keep manual backups outside this app's private storage.

Uninstalling, clearing application storage, or losing/invalidation of the device Keystore key can make local data inaccessible. The app preserves inaccessible encrypted files and does not reset them silently. To recover from a manual backup after key loss, clear app storage through Android Settings, open the app again, authenticate, and restore. **Only do this when you have a usable backup.** Screenshots, clipboard contents, external viewers, and the camera application are outside the vault's control.

See [security and lifecycle decisions](docs/security.md) and [the backup format](docs/backup-format.md).

## Verification

The signed APK is in `dist/SecureNotes-1.0.1.apk`. Install it over 1.0.0 to retain existing notes; do not uninstall first. See [recorded verification results](docs/verification.md) for the tested build, checksums, completed checks, and remaining physical-device acceptance.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Instrumentation tests must run only on a **test emulator**: the UI fixtures clear this app's emulator data. They do not simulate a successful real biometric; storage/UI fixtures use an in-process test session with no production bypass intent or preference. See [the acceptance checklist](docs/acceptance.md) for the remaining physical-device checks.

Database schema version 1 is exported into `app/schemas`. There is no destructive migration fallback. Any future schema change requires a versioned Room migration and migration tests. Document and backup formats are versioned independently; unsupported versions are rejected without replacing data.

## Accepted defaults

- Personal sideloading, English, approximately ten notes/attachments and 10 MB total. These are expected workload sizes, not enforced attachment limits.
- Android 15+; authentication after every new process; a secure device lock is mandatory.
- Password entry for every backup; PDF/plain-text extraction without OCR.
- Native block editing with reading-mode checklist toggles.
- Operations continue across ordinary app switching while the process is scheduled. Lock interrupts sensitive processing. Indexing/import checkpoints resume after authentication; backup/restore preparation restarts with password entry. Temporary third-party URI permissions may require selecting a source again.

Autosave keeps the newest snapshot per note, waits for 300 ms of quiet, and starts a save within one second of continuous typing. Navigation and ordinary backgrounding flush edits. Lock immediately hides content and revokes access, then finishes accepted writes before closing storage. Failed lock-time writes use encrypted recovery records and produce a warning. Sudden process termination before a write completes can still lose uncommitted changes. There is no claim of forensic erasure of flash storage or perfect zeroization of JVM/framework memory.
