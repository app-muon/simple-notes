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

1. **Choose a dedicated phone-storage subfolder**, for example `Downloads/NotesBackups`. Notes creates two timestamped A/B files, verifies both, and later replaces only the inactive slot after verifying the active copy.
2. **A sync app uploads both files in that folder to Dropbox,** for example Autosync for Dropbox in **Upload only** mode, which uploads new and changed files and never passes local deletions on. Check it on the phone, including after the phone has been idle.

The app keeps its no-network policy. It reports when it saved locally; the sync app reports uploads.

**If you later switch to uniquely named snapshots,** three things apply:

- Write each snapshot under a temporary name and rename it once complete, so the sync app never uploads a partial file.
- Upload-only mode never deletes, so snapshots build up in Dropbox. Use a mirror-style mode or prune them by hand.
- Snapshots need folder access (`ACTION_OPEN_DOCUMENT_TREE`). Since Android 11, the top level of Downloads can't be granted, but a subfolder can.

## Implementation notes

### Choosing and writing the pair

- Pick a subfolder with OpenDocumentTree. Retain and verify read/write access; store the folder, actual names, and URIs in one encrypted configuration record.
- Create two new timestamped documents; never adopt a file by name. Verify both before committing setup. Preserve historical files and retain all grants after an uncertain settings commit.
- Record each newly created URI before querying its name. If the provider supplies no usable name, show the requested label as unconfirmed; verification still uses the URI. A security or cancellation error during the name query triggers cleanup.
- If the active file cannot be read, say its condition could not be verified and no backup file was overwritten. Retry after checking access, or create a new pair if missing. Do not describe an unreadable file as a retained verified copy or as proven corruption.
- Keep oversized-snapshot revision suppression in memory. Preserve it across lock/unlock, but allow a fresh check after process restart. Cache grant eligibility in memory and refresh it on IO; recheck actual grants within storage attempts.
- Before writing, verify the active file's exact length and digest. Persist pending work, write only the inactive slot, explicitly flush/truncate/sync/close, then read it back with bounded buffers and verify exact EOF and SHA-256.
- Open with rwt, then wt, then w only when opening fails. Propagate security failures without fallback. Unlike the old single-file approach, read-back is mandatory for every mode. A stale provider result is a failed save, not grounds to claim verification.
- Prepare ciphertext in cache and finish an inactive copy non-cancellably. Publish active-slot metadata only after verification and acknowledged persistence. If locked, leave pending work for unlock. Uncertain settings writes require process restart.

### Format

- **Encrypt under a recovery passphrase,** so the backup restores on a new phone. Use authenticated encryption with a fresh random salt for every backup. An incomplete file then fails authentication, so a sync that catches the file mid-write is rejected on restore and replaced by the next sync.
- **Fingerprint the content and skip unchanged saves.** Include the normalized phrase hash and record the fingerprint and verified time only after read-back and settings commit. Pending work or a retryable error prevents unchanged-content skipping.

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

## Recovery and migration

Existing single-file backups become migration-required history and are never overwritten again. Create a fresh pair through folder selection. Restore tries the confirmed device phrase first, then asks for words if envelope authentication fails. The successful phrase becomes the recovery phrase. If it differs from the configured pair, review it after authentication and create a new pair; preserve both old files under their original words. Never silently replace an established missing phrase.

The .ssnb streaming format, fresh salts/keys, attachment handling, and 16 MiB manifest limit are unchanged. There is no total backup size limit, plaintext configuration/export path, cached derived key, or invented creation timestamp. See [the format](docs/backup-format.md) and [security decisions](docs/security.md).

## Testing

- **Control test:** tap "Back up now" repeatedly with the file in phone storage. If that works but a cloud-provider file doesn't, the provider is at fault.
- **Test after 15+ minutes idle,** not just straight after setup. Dropbox passed straight after being opened and failed when idle.
- **Test the ways of leaving:** edit then press Home without reopening, edit then swipe the app away from Recents, and reboot. Also restore on a fresh install.
- **Check uploads from a desktop that syncs the same account** by polling the synced file's size and first 16 bytes every half second. The per-backup salt makes every save's bytes differ, which reveals in-place replacements, stray copies, empty placeholders and late uploads.
- **Emulator:** `shortService` needs API 34 or later. Tests using Keystore keys that require user authentication need a screen lock: run `adb shell locksettings set-pin 1234` on a disposable emulator.
