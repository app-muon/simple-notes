# Notes

A native offline notes app for a Pixel 8 running **Android 15 or later**. The app has no Internet permission, accounts, analytics, or Android system backup. It supports encrypted backups through a user-chosen file provider. Its product source is [the specification](secure_simple_notes_android_spec.md), with the clarifications recorded below.

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

Configure a device PIN/password/pattern first. Unlock with a strong biometric or your device credential. Fresh processes require authentication. Ordinary app switching leaves the session unlocked; device locking ends it. Screenshots remain available; Recent Apps previews are hidden, and screens showing the recovery passphrase block screenshots.

The list shows titles only. New notes focus the title. Existing notes open in reading mode; tap text to edit. In reading mode Select All covers the whole note. While editing, the header turns green and shows ✓ Done. The body is one editor, so selection, copy, paste and the keyboard work across lines. The toolbar's bold, heading, bullet, numbered and checklist buttons apply to every selected line and show the current line's format. Enter continues a list, and Enter on an empty list item ends it; Backspace at the start of a list item first removes its marker. Pasted text arrives without outside formatting. Undo and redo step through typing pauses and formatting changes. Lists are single-level. Checkboxes can be tapped in both modes. Back or Done leaves editing, then Back returns to the list. Empty new drafts are discarded; clearing an existing note does not silently delete it.

Slim scrollbars show your position in long notes, lists, search results, settings, and recovery screens. They appear only when content extends beyond the visible area. The formatting toolbar also shows a horizontal indicator when it does not fit.

Images and files sit in the **Attachments** section below the text. While editing, [+] adds a photo, camera shot or file. Each item's menu offers **Share** and **Save to device**; while editing it also moves the item left/right or removes it. Images open in a zoomable viewer, which has the same two actions. Files open through temporary read-only content URIs in another application. Text, links, images, and files can be shared **into** the app after choosing a destination. There is no note-export/share action.

Share hands one attachment to another app without writing an unencrypted copy. The receiving app keeps access until the phone locks, because some apps read the file only when they send it. Save to device writes an unencrypted copy wherever you choose. Locking or cancelling stops a save and deletes the partial copy; select the location again after unlocking. Both send the file exactly as stored, including photo metadata such as location. Camera shots are named after the time they were taken; older ones appear as Photo.jpg.

Search includes titles, body text, filenames, and locally extracted text from PDFs and UTF-8 text files, including Markdown and CSV. Search ignores case/diacritics, supports prefixes, and tolerates one spelling error for words of four or more characters. Matching terms and postings are paged without an arbitrary result cutoff. Scanned/password-protected/unsupported documents remain searchable by filename. Search indexes are rebuilt locally after restore.

**Find in note:** tap the search icon in an open note, in either reading or editing mode. Find matches literal text in the title and body, including partial words, phrases, punctuation, and line breaks, ignoring case and accents. It highlights every match; Previous/Next wrap through the results and keep the search field focused. While a search is pending, the counter is blank and navigation is disabled. “No matches” appears only when that search finishes.

Close or Back clears Find. While editing, the latest action determines the cursor: changing the query or navigating results makes the active match the destination, but moving the cursor or typing afterward preserves that newer selection. Closing immediately after entering a query resolves it without waiting for the debounce. With no match, the current applicable editor selection is preserved. Done closes Find and finishes editing in one tap; Back closes Find first, then leaves editing, then returns to the list. Ordinary edits and tapping text to edit do not jump back to a Find result. Attachments and tags are excluded. Highlights never become formatting or undo steps.

**Tags:** tap the list's **Notes** title to choose All notes, Untagged, or one tag. The same sheet offers **Manage tags** for creating, renaming, and deleting reusable tags. Names are trimmed, repeated whitespace is collapsed, and duplicates ignoring case are rejected. A note's **Tags** menu assigns any number of tags; assigned tags appear beneath its title. Deleting a tag removes assignments and keeps the notes. Unused tags remain available.

Tag assignments and tag-only undo/redo preserve Find's chosen cursor destination. Undo/redo that changes title text, body text, or formatting takes priority over an earlier Find result. In reading mode, Find keeps the checkbox visible for first-line matches and scrolls directly to matches farther down wrapped checklist text.

Global search respects the selected filter, and new notes inherit a selected tag. Tags alone do not keep an empty new draft. Drag a row or use its accessible Move up/down actions to reorder the visible notes; hidden notes keep their positions. The filter survives navigation and resets when locking or starting a new process. The share-destination picker continues to show all notes.

## Backup and recovery

On first run the app shows an 8-word **recovery passphrase** and asks for three of the words before continuing. Write it down. It opens your notes if this phone's unlock key is lost (for example after removing the screen lock), and it is the password for every backup. Settings → "View recovery passphrase" shows it again after a fresh biometric/device-credential check. Restoring a backup adopts its phrase; there is no separate phrase-change action. Losing the words makes backups unrecoverable.

Settings > **Choose backup folder** creates two new encrypted files in a folder you select. Use a dedicated phone-storage subfolder such as `Downloads/NotesBackups`, then sync the **whole folder, including subfolders**, in upload-only mode. Each pair has timestamped A and B filenames; provider collision suffixes are shown in Settings. The A and B files hold note text and details; each attachment is saved once, as its own encrypted file in a `Notes-<timestamp>-attachments` folder beside them, created with the pair's first attachment. A text edit then rewrites only one small file, and a new photo adds one file. Both A and B depend on that folder, and routine saves check that its files are present at the right size without re-reading them. Settings > **Verify backup** reads both files and every attachment they name with the same checks as a restore, while you stay in the app; Settings shows when that last happened and whether a save has happened since. Run it now and then. Removed attachments stay in the folder for the pair's lifetime; creating a new pair starts again from current content. Pairs created by earlier versions keep saving everything in each file; Settings > **Upgrade backup** creates a new pair in the same folder, and the old files stay. Creating a pair with many photos can take a while: if it is interrupted (by locking, leaving the app, or a failure), Settings offers **Resume setup**, which reuses the files already saved, or **Discard setup**, which removes only the files that setup made. Your current backup keeps saving meanwhile. An index may reach the cloud before its attachments, so let the sync app finish before relying on the newest file. Notes alternates slots after about a minute of quiet, on authenticated foreground return, and when leaving the app. Before replacing the inactive file, it verifies the active file; after writing it reads the new file back and checks its exact length and SHA-256. Settings shows the latest locally verified slot and time. Verification is separate from successful external upload: check the sync app too. The app has no network permission.

To restore on a new phone, first download the whole backup folder, including its attachments folder, to phone storage. Choose an A or B file; if it has attachments, Notes then asks for the folder that holds them (or that folder itself, even if moved or renamed). Nothing is replaced until every file has been authenticated and checked.

An interrupted save leaves the previous verified slot active, and attachment files it already completed are reused. Ordinary failures retry on the next normal trigger or **Back up now**. Empty collections do not automatically replace backups; manual backup and explicit setup can save an empty collection. A damaged active copy requires a new pair. If saving backup settings has an uncertain outcome, restart Notes before changing backups. Locking and unlocking does not clear that block. Turning backups off leaves both files intact.

If the active file cannot be read, its present condition is unverified; the attempt overwrites neither file. For an access failure, use **Check backup access** in Settings; **Back up now** becomes available after access is confirmed. Create a new pair if the file is missing or remains inaccessible. A read failure alone does not prove corruption. Setup failures ask you to choose a folder again. If the provider omits its filename, Settings shows the requested name with “provider filename unavailable; requested name”; Notes still identifies and verifies the file by its URI. Oversized metadata is checked again after an edit or a fresh process start; locking and unlocking alone does not reset that suppression.

**Review backup** first saves and closes an open note or image session, discarding an empty new draft. A failed save keeps the note available for correction. Choosing a backup folder reserves setup while the picker or provider warning is open. Other backup mutations wait; restore and recovery phrase viewing remain available. Locking cancels the selection, so select the folder again after unlocking.

Once setup is accepted, it belongs to the app's scheduler and survives closing the activity; locking still cancels preparation. If you edit while a save is running and then leave, the scheduler keeps its service hold through a follow-up flush and backup of those edits. Android's service time limit still applies.

**Upgrading from a single backup file:** the old file, filename, saved time, and permission are preserved. Notes never overwrites that file again. Choose a folder to create a fresh pair; historical backup files are never automatically deleted. Re-selecting the same folder creates new documents rather than adopting files by name.

To restore, choose either A or B. Notes first tries this device's confirmed recovery phrase; if it cannot authenticate the key envelope, it asks for the phrase that encrypted that file and allows retry. An incorrect phrase and some forms of damage cannot always be distinguished. Notes validates the complete backup and displays its note, attachment, and tag counts before asking to replace current content. No backup creation date is inferred, no merge is performed, and no safety backup is made.

The phrase that opens the restored backup becomes the app's recovery phrase. Device backup configuration is transferred when replacement is confirmed, including changes made while validation was pending. If the restored phrase differs from the current pair's phrase, backups pause. Use **View recovery passphrase**, authenticate, review the words, tap **Done**, and create a **new** pair. The old pair stays encrypted with its original phrase. Cancelling review or setup keeps it paused. An established but missing phrase is never silently replaced with newly generated words.

Uninstalling or clearing application storage deletes the local vault; restore from your backup file afterwards. Screenshots, clipboard contents, external viewers, and the camera application are outside the vault's control.

The passphrase wordlist is the [EFF large wordlist](https://www.eff.org/dice) by the Electronic Frontier Foundation, licensed under [CC BY 3.0 US](https://creativecommons.org/licenses/by/3.0/us/).

See [security and lifecycle decisions](docs/security.md) and [the backup format](docs/backup-format.md).

## Verification

The signed APK is in `dist/SecureNotes-1.0.1.apk`. Install it over 1.0.0 to retain existing notes; do not uninstall first. See [recorded verification results](docs/verification.md) for the tested build, checksums, completed checks, and remaining physical-device acceptance.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Instrumentation tests must run only on a **test emulator**: the UI fixtures clear this app's emulator data. They do not simulate a successful real biometric; storage/UI fixtures use an in-process test session with no production bypass intent or preference. See [the acceptance checklist](docs/acceptance.md) for the remaining physical-device checks.

Database schema versions 1 and 2 are exported into `app/schemas`. The additive 1→2 migration adds encrypted tag definitions and default-empty note assignments without converting historical block documents. There is no destructive migration fallback. Document and backup formats are versioned independently; unsupported versions are rejected without replacing data. Manifest version 3 includes tags; supported tagless manifests restore untagged.

## Accepted defaults

- Personal sideloading, English, approximately ten notes/attachments and 10 MB total. These are expected workload sizes, not enforced attachment limits.
- Android 15+; authentication after every new process; a secure device lock is mandatory.
- Automatic backups encrypted under the generated recovery passphrase (no password entry); PDF/plain-text extraction without OCR.
- One native editor for the note body with line formats; attachments in a separate section; checklist toggles in both modes.
- Operations continue across ordinary app switching while the process is scheduled. Lock interrupts sensitive processing. Indexing/import checkpoints resume after authentication; backup/restore preparation restarts with password entry. Temporary third-party URI permissions may require selecting a source again.

Autosave keeps the newest snapshot per note, waits for 300 ms of quiet, and starts a save within one second of continuous typing. Navigation and ordinary backgrounding flush edits. Lock immediately hides content and revokes access, then finishes accepted writes before closing storage. Failed lock-time writes use encrypted recovery records and produce a warning. Sudden process termination before a write completes can still lose uncommitted changes. There is no claim of forensic erasure of flash storage or perfect zeroization of JVM/framework memory.
