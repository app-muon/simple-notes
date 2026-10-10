# Automatic encrypted backups from an offline Android app

Lessons from a notes app with no network permission (minSdk 35, target 37), tested on a real phone on 9–10 October 2026. The app writes a passphrase-encrypted backup file, and another app uploads it.

## Why saving straight to a Dropbox file doesn't work

Android's file picker lets the user choose a file in Dropbox, after which the Dropbox app uploads whatever the app writes to it. Only the first save into a newly created file worked reliably. Later saves to the same file:

- sometimes replaced it;
- sometimes appeared as a separate copy named `<name>_<19-digit number>.<ext>`;
- usually, while Dropbox was idle, failed. Opening the file succeeded, then writing threw an `IOException` caused by `ErrnoException EBADF`: the file descriptor Dropbox returned could not be written to.

Repeated saves only succeeded shortly after the Dropbox app or its picker had been on screen. One "failed" save was uploaded about 20 minutes later, on top of a newer backup; another never arrived. None of these helped:

- setting Dropbox's battery use to Unrestricted;
- opening the file with `"rwt"` instead of `"wt"`;
- keeping the app running after the user leaves it.

The fault is inside the Dropbox app's file provider, which the backing-up app can't control. Saves to a file in phone storage worked every time.

## The setup that works

1. **The user chooses a backup file in phone storage,** for example `Downloads/NotesBackups/Notes.ssnb`, and the app keeps it overwritten with the latest encrypted backup.
2. **A sync app uploads that folder to Dropbox,** for example Autosync for Dropbox in **Upload only** mode, which uploads new and changed files and never passes local deletions on. Check it on the phone, including after the phone has been idle.

The app keeps its no-network policy. It reports when it saved locally; the sync app reports uploads.

**If you later switch to uniquely named snapshots,** three things apply:

- Write each snapshot under a temporary name and rename it once complete, so the sync app never uploads a partial file.
- Upload-only mode never deletes, so snapshots build up in Dropbox. Use a mirror-style mode or prune them by hand.
- Snapshots need folder access (`ACTION_OPEN_DOCUMENT_TREE`). Since Android 11, the top level of Downloads can't be granted, but a subfolder can.

## Implementation notes

### Choosing and writing the file

- **Pick the file** with `CreateDocument("application/octet-stream")`, call `takePersistableUriPermission(uri, READ or WRITE)`, and store the URI. Before each save, check that `persistedUriPermissions` still holds a write grant; if not, ask the user to choose the file again.
- **Open it with a truncating mode,** `"rwt"` then `"wt"`. Fall back to plain `"w"` only when opening fails, never after a failed write.
- **Check the size only after plain `"w"`,** because that mode may leave old bytes at the end of the file. Don't read the file back after a truncating mode: a cloud provider reports the old size until its upload finishes. That turns every success into a false failure, and the app then retries at every trigger.
- **Build the encrypted file in the app's cache first,** then copy it across in one go inside `withContext(NonCancellable + Dispatchers.IO)`. It's already ciphertext, so finishing the copy after the app locks is safe.

### Format

- **Encrypt under a recovery passphrase,** so the backup restores on a new phone. Use authenticated encryption with a fresh random salt for every backup. An incomplete file then fails authentication, so a sync that catches the file mid-write is rejected on restore and replaced by the next sync.
- **Fingerprint the content and skip unchanged saves.** Record the fingerprint and the "last saved" time only after the copy succeeds.

### When to save

Save 60 seconds after edits stop, on unlock, when the user leaves the app (`onPause`), and on "Back up now". Run saves one at a time behind a mutex, and never let one failure stop later attempts.

### Finishing a save after the user leaves

Android freezes an app within seconds of the user leaving it, so a save started on leaving stalls until the app is next opened. WorkManager doesn't help when notes can only be read while the app is unlocked. Use a short foreground service:

- **Manifest:** the `FOREGROUND_SERVICE` permission and `<service … android:foregroundServiceType="shortService" android:exported="false" />`. No other permission is needed, and Android allows it about 3 minutes.
- **Start it synchronously in `onPause` with `startForegroundService`.** Android only allows this while the app is still visible. Start it only when the save is likely to write, and also around "Back up now". If starting it fails, catch the exception and carry on.
- **In `onStartCommand`, call `startForeground(…, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)` immediately.** Stopping the service before that crashes the app. Then call `stopSelf(startId)` once a hold counter reaches zero, and override both `onTimeout` overloads to call `stopSelf()`.
- **No `POST_NOTIFICATIONS` permission is needed.** The required notification stays out of the notification shade, and the app only appears briefly in the active-apps list.

### Errors the user can relay

Show the "last saved" time and the latest error in Settings. For unexpected failures, add the failed step, the exception types, and the errno name for an `ErrnoException` (from `OsConstants.errnoName`), for example `(Failed at write rwt: IOException / ErrnoException EBADF)`. Never show exception messages, which can contain file names or URIs. This detail is what diagnosed the Dropbox failure without device logs.

## Testing

- **Control test:** tap "Back up now" repeatedly with the file in phone storage. If that works but a cloud-provider file doesn't, the provider is at fault.
- **Test after 15+ minutes idle,** not just straight after setup. Dropbox passed straight after being opened and failed when idle.
- **Test the ways of leaving:** edit then press Home without reopening, edit then swipe the app away from Recents, and reboot. Also restore on a fresh install.
- **Check uploads from a desktop that syncs the same account** by polling the synced file's size and first 16 bytes every half second. The per-backup salt makes every save's bytes differ, which reveals in-place replacements, stray copies, empty placeholders and late uploads.
- **Emulator:** `shortService` needs API 34 or later. Tests using Keystore keys that require user authentication need a screen lock: run `adb shell locksettings set-pin 1234` on a disposable emulator.
