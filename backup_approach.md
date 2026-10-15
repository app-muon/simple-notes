# Automatic encrypted backup files: the approach

A guide for implementing automatic, encrypted, on-device backup files in an offline Android app.
It describes the design and the platform details that make it work. It leaves out any one app's
data model and screens.

## What it does

The app keeps an encrypted copy of all its data in a folder the user picks, normally in phone
storage. A separate sync app the user chooses (for example one that uploads a folder to Dropbox) uploads
that folder. The app itself needs no network permission.

The backup is a **pair of files, A and B**. Each save overwrites the older file, reads it back to
check it, and only then records it as current. So after the first successful save there is always
at least one complete, verified backup in the folder, even if a later save is interrupted.

The file is encrypted under a key derived from a **recovery phrase** the user has written down, so it
can be restored on a new phone without anything from the old one.

### Guarantees to preserve

Any change to the design must keep these:

1. After the first successful save, at least one complete, verified backup always exists in the folder.
2. A save never opens the second file for writing unless the current file has just been verified intact.
3. Automatic saves never replace backups with an empty database.
4. The app never silently switches to a different encryption phrase.
5. A failure never stops later attempts, unless retrying cannot help (see the retry policy).
6. Error text shown to users never contains file names, URIs or exception messages.
7. If the app can't tell whether its own settings were saved, it stops writing backups until it restarts.

## Why a folder in phone storage, not a single file in a cloud provider

The first version of this approach let the user pick one file, possibly directly in a cloud
provider's document picker (Dropbox). Repeated saves to a file in the Dropbox app's document provider were unreliable:

- `openFileDescriptor` succeeded, then writing threw `IOException` caused by `ErrnoException EBADF`,
  usually while the Dropbox app was idle;
- sometimes a save appeared as a separate copy named `<name>_<19 digits>.<ext>`;
- a "failed" save was sometimes uploaded 20 minutes later, on top of a newer one.

Setting battery use to unrestricted, using `"rwt"`, or keeping the app alive did not help. The fault is
in the provider. Writes to phone storage worked every time. So:

- Recommend a dedicated **subfolder in phone storage** (e.g. `Download/<App>`), uploaded by a sync app
  in **upload-only** mode. Android 11+ won't grant the Downloads root or storage root, but will grant a subfolder.
- Detect phone storage by the tree URI's authority: `com.android.externalstorage.documents` or
  `com.android.providers.downloads.documents`. If the user picks anything else, warn them and offer
  "Use anyway" or "Choose again".
- A single overwritten file has no safe moment: a crash or a sync mid-write can leave the only
  copy incomplete. The A/B pair fixes that.

## Components

Keep these as separate units. Each has one job, and every external dependency is injectable for tests.

| Unit | Responsibility |
| --- | --- |
| **Scheduler** (process-wide singleton) | Decides *when* to save: debounce after edits, on foreground, on leaving, on request. Owns the coroutine scope and the database change watcher. |
| **Saver** | Does *one* operation at a time behind a mutex: save, set up, turn off, accept phrase. Owns the in-memory settings and the status flow. |
| **File layer** (interface) | Storage Access Framework calls: keep and release the grant, check the grant, create, delete, write, verify. |
| **Crypto** | File format: key derivation, encrypt, header check, decrypt. |
| **Settings store** (interface) | Durable record of the folder, both slots and their digests, which slot is current, fingerprint, last save, pending flag and last error. |
| **Short foreground service** | Keeps the process alive while a save finishes after the user leaves. |
| **Backup codec** | Data snapshot → JSON bytes, and JSON → validated data on restore. Shared with restore and plain export. |

### File layer interface

```kotlin
interface BackupFile {
    fun retain(uri: Uri)                          // takePersistableUriPermission(READ | WRITE)
    fun release(uri: Uri)                         // releasePersistableUriPermission, ignore errors
    fun canWrite(uri: Uri): Boolean               // persistedUriPermissions has read AND write for uri
    fun create(folder: Uri, name: String): Slot   // always a NEW document; returns uri + actual name
    fun delete(uri: Uri)                          // best effort; never used on managed slots
    fun write(uri: Uri, bytes: ByteArray)
    fun verify(uri: Uri, size: Long, sha256: String)  // throws MismatchException or I/O error
}
```

Inject `fsync` and `openFileDescriptor` too, so tests can simulate failures at each step.

### Settings store interface

```kotlin
interface BackupSettingsStore {
    fun load(): BackupSettings?
    fun commit(settings: BackupSettings?): Boolean   // null = backups off
}

data class Slot(val uri: Uri, val fileName: String, val digest: String? = null, val size: Long = 0)

data class BackupSettings(
    val folder: Uri,             // persisted tree grant
    val phraseHash: String,      // hash of the phrase the backups use
    val slots: List<Slot>,       // exactly two
    val activeSlot: Int,         // the last verified file
    val fingerprint: String?,    // of the data last saved
    val savedAt: Instant?,
    val pending: Boolean = true, // work started or owed but not confirmed
    val error: BackupError? = null,
)
```

## Setup: creating a new pair

Triggered when the user picks a folder (`ActivityResultContracts.OpenDocumentTree`). Changing folders
uses the same steps; so does picking the same folder again.

1. **Reject first, don't queue.** If a save or setup is running, the database isn't ready, or a restart
   is required, show why and do nothing. Check again when the user confirms the cloud warning.
2. `retain(folder)`, then confirm `canWrite(folder)`. If not, report "access couldn't be kept".
3. Load the phrase. If a previous configuration exists and its `phraseHash` differs, refuse: changing
   folders must not quietly accept a changed phrase.
4. Take a snapshot and build the encrypted payload (below).
5. Create two **new** documents with `DocumentsContract.createDocument(resolver, parentDocUri,
   "application/octet-stream", name)`, where `parentDocUri = buildDocumentUriUsingTree(folder, getTreeDocumentId(folder))`.
   - Names: `<App>-<UTC setup time>-A.<ext>` and `…-B.<ext>`, e.g. `<App>-2026-10-12_14-05-00Z-A.<ext>`.
     Both share one timestamp, which identifies the pair, not the latest contents.
   - **Never look up or adopt an existing file by name.** If the name exists, providers append a
     suffix such as ` (1)`. Query `OpenableColumns.DISPLAY_NAME` for the real name (fall back to the
     requested name) and store it. Never rename files during later saves.
   - Record each created URI immediately, so cleanup can find it if a later step fails.
   - Check that neither URI belongs to the current configuration and that the two differ.
6. Write and verify both files with the same payload.
7. Commit the new settings with `activeSlot = 1`, `pending = false`, the fingerprint and `savedAt`.
8. Only after a successful commit, release the previous folder's grant (if it was a different folder).
   **Leave the previous pair's files in place.**

Cleanup:
- If setup fails or is cancelled **before** the commit is attempted: in `NonCancellable`, try to delete only the
  documents this setup created, and release the new grant unless it's the current folder.
- Once the commit has been **attempted**, keep both pairs and both grants, because either configuration may be the durable one.
- Setup errors go in a separate in-memory "operation error". They don't affect the current backup's
  error or pending state.

Turning backups off commits `null`, then releases the grant. The files stay.

## One save: the protocol

All steps run on `Dispatchers.IO` inside the saver's mutex. `force` is true only for "Back up now".

```
attempt(force):
  settings = trusted (in-memory copy, see "Settings durability")
  if settings == null or not a two-slot config: return
  if !force and !automaticRetryAllowed(settings): return
  revision = databaseRevision.get()             // capture BEFORE the snapshot
  try:
    snapshot = read all tables in ONE read transaction; sort rows by id
    if !force and snapshot is empty: return     // never auto-save an empty database
    if !file.canWrite(settings.folder): fail(NO_ACCESS)
    phrase = load phrase
    if phrase == null or hash(phrase) != settings.phraseHash: fail(PHRASE_CHANGED)
    fp = fingerprint(snapshot, settings.phraseHash)
    if !force and !settings.pending and settings.error == null and fp == settings.fingerprint:
        return                                  // unchanged: skip, don't even touch the files
    active = settings.slots[settings.activeSlot]
    if active.digest isn't 64 hex chars, or active.size is out of range, or the two slot URIs are equal:
        fail(ACTIVE_DAMAGED)
    try file.verify(active.uri, active.size, active.digest)
      on MismatchException -> fail(ACTIVE_DAMAGED)
      on other error       -> fail(NO_ACCESS if SecurityException in the cause chain, else FAILED)
    bytes = encrypt(encode(snapshot))           // size limit checked before any file is opened
    if !settings.pending and !commit(settings.copy(pending = true)): return
    withContext(NonCancellable):
      inactive = 1 - settings.activeSlot
      written = writeVerified(settings.slots[inactive], bytes)
      commit(settings with slots[inactive] = written, activeSlot = inactive,
             fingerprint = fp, savedAt = now, pending = false, error = null)
  catch CancellationException: rethrow
  catch e: if TOO_LARGE then oversizedRevision = revision; fail(classify(e))

fail(kind, detail): commit(settings.copy(error = BackupError(kind, now, detail), pending = true))
                    // keeps the previous savedAt and fingerprint
```

Why each rule exists:

- **Verify the current file before opening the other one.** If the current file is missing or damaged, the
  other file may be the only good copy. Overwriting it could leave no backup at all. A content mismatch
  pauses saves (`ACTIVE_DAMAGED`) until the user creates a new pair. An I/O error while reading it is
  retryable, but still blocks writing that save.
- **Record `pending = true` durably before writing.** If the process dies mid-write, the next launch knows
  work is owed, even if the data then looks unchanged. A forced save that fails stays pending.
- **Write the inactive slot under `NonCancellable`.** The bytes are already ciphertext, so finishing after
  the app locks or the user leaves is safe. A half-finished write is worse than a finished one.
- **Only the final successful commit changes the current slot and "last saved" time.**
- **Skip unchanged data** using the fingerprint, before any file I/O. This avoids pointless writes and uploads.
- **Never auto-save empty data.** A wiped or freshly installed app would otherwise overwrite both
  backups after two saves. Manual "Back up now" may save empty data.

### Fingerprint

`SHA-256(encode(snapshot with its export timestamp set to "") ‖ phraseHash)`, stored as hex.

- Leave out the timestamp, or every snapshot would look new.
- Sort rows by id and use a JSON encoder with stable output (`encodeDefaults = true`) so equal data gives equal bytes.
- Include the phrase hash so that accepting a new phrase forces a rewrite.

### Writing a slot

```
writeVerified(slot, bytes):
  digest = sha256Hex(bytes)
  write(slot.uri, bytes)
  verify(slot.uri, bytes.size, digest)   // a mismatch here is a retryable FAILED, not ACTIVE_DAMAGED
  return slot.copy(digest = digest, size = bytes.size)
```

`write`:
1. Open with `"rwt"`, then `"wt"`, then `"w"`. Try the next mode **only if opening fails**, never after a failed
   write. Re-throw `SecurityException` immediately.
2. `write(bytes)`, `flush()`.
3. If the mode was plain `"w"` (which may not truncate), `channel.truncate(bytes.size)`.
4. `Os.fsync(fd)`. Ignore `ErrnoException` with `EINVAL`, `ESPIPE` or `EOPNOTSUPP`, which pipes and some
   providers return; any other errno is a real failure.
5. Close. Track the current step name (`write rwt`, `flush`, `truncate`, `sync`, `close`) for the error detail.

`verify`:
- Open `"r"`. Read at most `size + 1` bytes, hashing as you go. Fail if more than `size` bytes, if fewer, or if
  the SHA-256 differs. Throw a dedicated `MismatchException` for those cases. Anything else (open/read
  errors) is an I/O failure.
- Read-back works reliably in phone storage. A cloud provider may report stale contents until its upload
  completes, which shows up as repeated failures. That is expected and is another reason to
  recommend phone storage.

### Size limit

Use one limit (20 MiB worked) for the **final file, including encryption overhead**. Check it after encoding and before
opening any output, and enforce it when reading a file for restore (read in chunks and stop once
over the limit). The whole payload is held in memory, so the limit also guards against running out of memory.

## Encryption format

Authenticated encryption under a key derived from the recovery phrase.

| Field (big-endian) | Bytes | Notes |
| --- | --- | --- |
| Magic | 4 | ASCII, unique to the app, e.g. `XXBK`. Also tells encrypted files from plain JSON. |
| Format version | 1 | `1` |
| KDF id | 1 | `1` = PBKDF2-HMAC-SHA256 |
| Iterations | 4 | e.g. 600,000 |
| Salt | 16 | random |
| Nonce | 12 | random, **fresh on every save** |
| Ciphertext length | 8 | includes the 16-byte tag |
| Ciphertext + tag | n | AES-256-GCM, 128-bit tag |

Header = 46 bytes; total overhead = 62 bytes.

- **The whole header is GCM associated data**, so changing any header byte makes decryption fail.
- Key: `PBKDF2WithHmacSHA256`, 256 bits, password = the phrase's words trimmed, lowercased and
  joined with single spaces. Zero the `char[]` and call `spec.clearPassword()` afterwards.
- PBKDF2 at 600k iterations is slow on a phone, so cache the derived key (with its salt) in memory, keyed
  by phrase hash, for the process lifetime. Every save still gets a fresh random nonce, which is what GCM
  requires, and makes every file's bytes differ.
- If the same phrase also protects something else (e.g. the database), the separate random salt keeps the
  backup key different.
- Make the iteration count a constructor parameter so tests can use a small value.

Reading, in order, each with its own user-facing message:
1. Magic doesn't match → "not a backup file" (or try plain JSON).
2. Shorter than the header → "incomplete".
3. Unknown version or KDF, or iterations outside `1..10,000,000` → "made by a newer version". The cap stops a
   crafted file from hanging the app.
4. Stored length < 16 or > bytes after the header → "incomplete". This tells a file cut short (e.g. still syncing)
   apart from a wrong phrase.
5. Derive the key from the header's salt and iterations, decrypt. `AEADBadTagException` → wrong phrase (return null).

Do steps 1–4 **before** asking the user for a phrase.

## The recovery phrase

- Store the phrase on the device (protected by the Keystore) so automatic saves can derive the key.
  Store a hash of it in the backup settings.
- If the phrase is missing or its hash differs, pause with `PHRASE_CHANGED`. Never re-encrypt under the new
  phrase automatically: the user's written-down phrase would no longer open new backups, while older files
  still need the old one. Offer **Review phrase**: show the current phrase, and on confirmation commit the new hash and set pending
  (clearing only the `PHRASE_CHANGED` error).
- Before first setup, if no phrase exists yet, generate one and make the user confirm it before the folder picker opens.
- If the app's data is already encrypted under a phrase that isn't on this device, don't allow setup.
  A new phrase wouldn't match.

## When to save

| Trigger | Forced | Holds the service |
| --- | --- | --- |
| 60 s after the last database change | no | no (app is in use) |
| App comes to the foreground (once data is readable) | no | no |
| User leaves the app (`onPause`) | no | only if a save is likely to write, or one is already running |
| "Back up now" | yes | yes |
| Setup / accept phrase | — | yes |

- **Change watcher.** Observe the backed-up tables (Room: `invalidationTracker.createFlow(tables…,
  emitInitialState = false)`) with `collectLatest`. On each emission:
  1. increment an `AtomicLong databaseRevision` immediately (before any delay);
  2. set `automaticSaveNeeded = true`;
  3. `delay(1 s)`, then recompute `automaticSaveNeeded` (snapshot + fingerprint compare). This lets a burst of
     writes finish and ignores writes that changed nothing;
  4. `delay(59 s)`, then launch a save.
  `collectLatest` restarts on each new change, which gives the debounce for free.
  Start the watcher on foreground or setup; cancel it on turn-off.
- **`onPause`, not `onStop`**: the service can only be started while the app is still visible.
- **Scope**: one process-wide `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, so saves outlive
  activities. Hold only the application context.
- **One mutex** in the saver serializes saves, setup, turn-off, phrase acceptance and grant release.
  Status shows `saving = true` while the mutex is held.
- Catch every non-cancellation exception around each operation and log it. A failure must not crash the app or stop the watcher.
- If the data is only readable while the app is unlocked (e.g. the database key is in memory only), the
  triggers above are the only ones that work. WorkManager can't read the data later, which is why a
  short foreground service finishes the save instead.

### Automatic retry policy

One function decides whether automatic saves may run. Use the same function for the attempt itself,
for `automaticSaveNeeded`, and for whether leaving starts the service:

| Current error | Automatic behaviour |
| --- | --- |
| none | Save when data changed or work is pending. |
| `FAILED` | Retry at every normal trigger, even if data is unchanged (pending repair). |
| `NO_ACCESS` | Retry only if `persistedUriPermissions` again has read+write for the folder (cheap check). |
| `TOO_LARGE` | Skip while `databaseRevision` equals the revision recorded when it was too large. In memory only, so a restart allows one fresh check. |
| `PHRASE_CHANGED` | Paused until the user reviews the phrase. |
| `ACTIVE_DAMAGED` | Paused until the user creates a new pair. |
| writes blocked (see below) | Everything paused until restart. |

Manual "Back up now" ignores the policy but still does every check, so it can never overwrite the
other slot while the current one is damaged.

`automaticSaveNeeded` starts `true` (conservative) and is refreshed after every operation and after each
debounce. If computing it throws, treat it as `true`. A wrong value only affects whether the service
starts. It never skips a save.

## Finishing a save after the user leaves

Android freezes a backgrounded app within seconds, so a save started in `onPause` would stall until
the app is next opened. Use a **short foreground service** that only keeps the process alive. The save
itself still runs in the scheduler's scope.

Manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<service
    android:name=".backup.BackupSaveService"
    android:exported="false"
    android:foregroundServiceType="shortService"/>
```

No `POST_NOTIFICATIONS` or type-specific permission is needed. Android allows `shortService` about 3 minutes.

```kotlin
class BackupSaveService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must come first: stopping before startForeground crashes the app.
        ServiceCompat.startForeground(this, ID, notification(),
            if (SDK_INT >= 34) FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0)
        lastStartId = startId; running = this
        if (holds == 0) stopSelf(startId)
        else { handler.removeCallbacks(stop); handler.postDelayed(stop, 2 * 60_000L) } // no system limit below API 34
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int) = stopSelf()
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {           // main thread only
        private var holds = 0
        private var running: BackupSaveService? = null

        @MainThread fun hold(ctx: Context): Boolean {
            holds++
            return try { ContextCompat.startForegroundService(ctx, Intent(ctx, BackupSaveService::class.java)); true }
            catch (e: Exception) { holds--; false }      // carry on without it
        }
        fun release(ctx: Context) = ContextCompat.getMainExecutor(ctx).execute {
            holds--; if (holds == 0) running?.let { it.stopSelf(it.lastStartId) }
        }
    }
}
```

- Notification: a channel with `IMPORTANCE_MIN`, `PRIORITY_MIN`, silent. Android 12+ delays
  foreground-service notifications about 10 s, so for a quick save it normally never appears.
- Call `hold()` synchronously before launching the job, and `release()` from `job.invokeOnCompletion`.
  That callback also fires if the job is cancelled before it starts, so holds always balance.
- On leaving, hold only if `status.saving || (retryAllowed && automaticSaveNeeded)`. Otherwise every
  exit with paused or empty data would flash a foreground service.

## Settings durability

- Use `SharedPreferences` with **synchronous `commit()`**, check its result, and write the whole record
  each time (`clear()` then all keys). `apply()` can't report failure.
- Keep a `trusted` in-memory copy and update it **only** when a commit returns true.
- **If a commit returns false or throws, block all backup writes for the rest of the process** and show
  "restart required". After a failed commit, `SharedPreferences`' in-memory map may already hold the new
  values while the disk doesn't, so the app can't tell which configuration is real. Never reload the
  preferences in the same process. A restart reloads from disk.
- While blocked or while an operation runs, disable setup, change folder, back up now, accept phrase
  and turn off. Restore, plain export and viewing the phrase stay available.
- **Exclude this preferences file from Android Auto Backup and device transfer** (`fullBackupContent`
  and `dataExtractionRules`, both `<cloud-backup>` and `<device-transfer>`). URI grants don't carry over
  to another device, and restored digests would describe files that aren't there.

## Errors and status

Status exposed as a `StateFlow`: `settings`, `saving`, `operationError` (setup/folder change, not
persisted), `restartRequired`.

| Kind | Meaning | User sees |
| --- | --- | --- |
| `NO_ACCESS` | Grant missing, or `SecurityException` anywhere in the cause chain | "Access lost, choose the folder again" (setup: "access couldn't be kept") |
| `PHRASE_CHANGED` | Phrase missing or different | "Review phrase" action |
| `TOO_LARGE` | Over the size limit | Limit message, plus "your last backup is kept" if one exists |
| `ACTIVE_DAMAGED` | Current file doesn't match its recorded digest or the metadata is invalid | "Restore an intact copy if needed, then choose a folder for a new pair" |
| `FAILED` | Anything else | Time of failure, "will retry", plus a diagnostic detail |
| restart required | Settings commit failed | "Restart the app" |

**Diagnostic detail**: the failed step plus the exception class names along the cause chain (up to 5),
with `OsConstants.errnoName(errno)` for `ErrnoException`, for example:

```
Failed at write rwt: IOException / ErrnoException EBADF
```

Never include exception messages, which can contain file names or URIs. Users can relay this text, and
it was enough to diagnose the Dropbox provider failure without device logs.

UI:
- Backup panel: phone storage or not, both current file names, which one was last saved, the
  last-saved time, the current error and the setup error separately, and a single folder action whose label
  depends on state (**Set up**, **Choose backup folder** for damaged or failed, **Choose folder again** for lost
  access, otherwise **Change folder**). Keep the panel open while setup runs so its result appears.
- A persistent banner on the main screens that opens the panel, in priority order: restart required, migration
  needed, current `FAILED`. Setup errors don't trigger it.

## Restore

1. `OpenDocument("*/*")`. The user can pick either A or B.
2. Read with the size limit. If the magic matches, check the header (reports incomplete or newer-version files before asking for a phrase).
   Otherwise treat it as plain JSON.
3. Encrypted: try this device's phrase first. Only if that fails, ask the user for the phrase. Show
   "wrong phrase" on tag failure and let them retry.
4. Parse: strip a leading byte-order mark, check it starts with `{`, decode, then validate the schema version and
   referential integrity (unique positive ids, valid foreign keys, no cycles). Throw validation errors that map to
   localized messages.
5. Confirm with the backup's date and record counts before replacing anything.
6. Replace all data in **one database transaction**. Run it in a scope that outlives the dialog.

Tell users to sync **both** files. If one file is rejected as incomplete (interrupted save or upload), restore the other.

## Optional: plain JSON export

Keep an unencrypted export behind a warning. Build and validate the JSON before opening the output, open with
explicit `"wt"` with no fallback, and don't delete the destination on failure. Warn that overwriting an existing file isn't atomic.

## Migrating from a single-file backup

If the app previously kept one backup file: load the old single-document setting as a configuration that
"needs migration" (anything without two slots). Never open that file for writing. Pause automatic saves,
show the migration banner, and ask the user to choose a folder. The setup steps above then create a new pair.
Keep showing the old file name and last-saved time until then.

## Testing

### JVM (Robolectric) with fakes

Inject the file layer, settings store, clock, database provider, iteration count, `fsync` and `open`.
Cover at least:

- failure at each write step (open, partial write, flush, sync, close, verification) leaves the other slot unchanged;
- settings commit failure → writes blocked until a new saver is created; commit failing after the write;
- process restart with `pending = true` retries even though the data is unchanged;
- current slot missing, unreadable (retryable) or mismatched (`ACTIVE_DAMAGED`), and in no case is the other slot opened;
- unchanged data skips I/O; empty data skips automatic saves but not manual ones;
- phrase changed / missing / accepted; setup refuses a changed phrase;
- name collisions return suffixed names, which are stored and reused; existing files never adopted;
- setup failure/cancellation deletes only its own new files and releases the unused grant; uncertain commit keeps both;
- `TOO_LARGE` suppression by revision, including edits made during the oversized attempt;
- `NO_ACCESS` recovers when the grant returns;
- service holds balance, including cancellation before the job starts; no hold when nothing needs saving;
- the snapshot reads all tables inside one transaction;
- size checks just below, at and above the limit for both plain and encrypted files;
- crypto: round trip, header tampering, truncation vs wrong phrase, unknown version, iteration cap.

For test isolation: cancel and join the watcher, wait for running saves **without** cancelling them, then reset the
singleton references, before tearing down the database.

### On a device (disposable emulator, API 34+ for `shortService`)

1. Pick a subfolder of Downloads. Confirm two non-empty files. Cancel a folder change and check that the hashes are unchanged.
   Pick the same folder again and check that a new pair appears and the old pair is untouched.
2. Edit, then press Home immediately. Without reopening the app, check that one file changed and the other kept its
   hash, and that the service ran then stopped (`dumpsys activity services <pkg>` shows `isShortFgs=true` while it runs).
   Repeat after swiping the app away from Recents, after a reboot, and **after 15+ minutes idle**.
3. Force-stop during repeated "Back up now". Reopen, save again, and restore both files.
4. Make the current file unreadable, then alter its bytes. The other file must never change.
5. Restore A and B separately on a fresh install and compare all data, not just counts.
6. With a real sync app: wait for the upload, download both on another device, compare SHA-256 and restore.
   A successful local save doesn't prove the upload finished.
7. Control test: if repeated saves to phone storage work but a cloud provider folder fails, the provider is at fault.

Useful commands:

```bash
adb shell input keyevent KEYCODE_HOME
adb shell ls -l /sdcard/Download/<App>
adb shell sha256sum /sdcard/Download/<App>/*
adb shell run-as <pkg> cat shared_prefs/<backup_prefs>.xml   # URIs and hashes only
adb shell am force-stop <pkg>
adb shell dumpsys activity services <pkg>
adb shell locksettings set-pin 1234   # only if tests need Keystore keys that require a screen lock
```

To watch a sync app from a desktop that syncs the same account, poll the file's size and first 16 bytes
every half second. Because each save has a fresh nonce, this shows in-place replacements, stray copies, empty
placeholders and late uploads.

## Pitfalls checklist

- Calling `stopSelf` before `startForeground` in `onStartCommand` crashes the app.
- `startForegroundService` from the background throws; call it in `onPause`, and catch the exception.
- Plain `"w"` may leave old trailing bytes, so truncate explicitly.
- Don't fall back to another open mode after a write has started.
- `fsync` is unsupported on pipes and some providers (`EINVAL`/`ESPIPE`/`EOPNOTSUPP`). Ignore only those.
- `SharedPreferences.commit()` can fail after changing the in-memory map, so never trust a reload in the same process.
- Providers rename on collision; store the returned display name.
- The Downloads root can't be granted on Android 11+; ask for a subfolder.
- Cloud document providers: writes may fail with `EBADF`, duplicate or upload late, and reads may be stale.
- Snapshot all tables in one transaction, or a backup can hold rows from two different moments.
- Capture the database revision **before** taking the snapshot, so edits during a failed attempt still trigger a retry.
