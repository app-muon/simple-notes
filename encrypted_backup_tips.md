# Automatic encrypted backups to Dropbox from an Android app

Lessons from getting this working in an offline Android notes app (minSdk 35, target 37), tested on 9 October 2026 against the Dropbox Android app on a real phone. Dropbox's internals are inferred from what reached the synced Dropbox folder on a desktop PC; there were no device logs. Re-verify the Dropbox-specific behaviour, because it is controlled by the Dropbox app and can change with its releases.

## Architecture that works

- **The app has no network access.** Remove `INTERNET` and `ACCESS_NETWORK_STATE` with `tools:node="remove"`. The Dropbox app does the uploading through Android's Storage Access Framework (SAF), so you need no Dropbox SDK, account or OAuth.
- **The user chooses one backup file once.** Use `ActivityResultContracts.CreateDocument("application/octet-stream")` with a suggested name such as `Notes.ssnb`. Dropbox appears in the system picker when its app is installed and signed in.
- **Keep the grant.** Call `takePersistableUriPermission(uri, READ or WRITE)` and store the URI and display name in encrypted app storage. If taking the grant throws `SecurityException`, tell the user the location cannot be used for repeated saving.
- **Check the grant before every save.** Look it up in `contentResolver.persistedUriPermissions` (it needs `isWritePermission`). If it's missing, ask the user to choose the location again.
- **Keep Android's own backup off** (`android:allowBackup="false"`, data-extraction rules), so the only copy leaving the device is the passphrase-encrypted one.

## Encrypted file format

- **Encrypt under a recovery passphrase**, not a device key, so the backup can be restored on a new phone. Use authenticated encryption (for example Tink streaming AEAD or AES-GCM) with a key from a passphrase KDF. PBKDF2-HMAC-SHA256 at about 600,000 iterations takes roughly a second on a Pixel 8.
- **Start the header with a magic value,** then the format version, the KDF parameters and a random salt. Validate the header before asking for the passphrase, and reject KDF parameters outside a sane range.
- **Use a fresh random salt for every backup.** Two backups of identical content then differ from about byte 12 onwards. That is handy for diagnosis: comparing the first 16 bytes tells you whether a file was actually replaced.
- **A truncated or partial file must fail authentication.** Never restore partially. Test truncation, wrong passphrase and altered headers.
- **Put the content in a zip inside the ciphertext:** a `manifest.json` first, then attachments. Validate the manifest strictly on restore (IDs, references, sizes, hashes) before replacing anything, and restore into a staging area that you swap in atomically.
- **Fingerprint the manifest** (SHA-256) and skip the write when it matches the last successful backup. Record the fingerprint and the "last saved" time only after the write succeeds.
- **Write the encrypted file to the app's private cache first, then copy it to the provider.** The bytes are already ciphertext, so the copy can safely finish after the app locks: wrap it in `withContext(NonCancellable + Dispatchers.IO)`. Delete the cache file afterwards, and clear the cache directory on startup.

## When to save

- About 60 seconds after content changes stop (debounce a change counter).
- When the app is unlocked or opened.
- When the user leaves the app (`onPause`): flush pending edits, then save if the content changed.
- "Back up now", and straight after the user chooses a file.
- Serialise saves with a mutex. One failed attempt must never stop later ones.

## Writing to the chosen file

```kotlin
step("open")
val (mode, output) = listOf("wt", "rwt").firstNotNullOfOrNull { m ->
    runCatching { resolver.openOutputStream(uri, m) }.getOrNull()?.let { m to it }
} ?: ("w" to checkNotNull(resolver.openOutputStream(uri, "w")))
step("write $mode")
output.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) }; step("close $mode") }
if (mode != "w") return
// Only plain "w" may leave old trailing bytes, so only then compare the size.
step("verify")
val size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
if (size >= 0 && size != file.length()) throw IncompleteWrite()
```

- **Do not read the file back after a truncating mode.** Dropbox uploads after the stream closes, and until then it reports the previous copy's size. A read-back check therefore reported successful saves as failures. The app then never recorded success, retried at every trigger, and Dropbox turned the burst of overwrites into stray copies.

## Saves after the user leaves the app: use a short foreground service

Android freezes an app's process within seconds of the user leaving it. A save started in `onPause` then stalls until the app is next opened. WorkManager doesn't help if the data can only be read while the app is unlocked (keys held in memory), because the job would run after the session is gone.

- **Declare it in the manifest:** `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />` and `<service android:name=".backup.BackupKeepAlive" android:exported="false" android:foregroundServiceType="shortService" />`. The `shortService` type needs no further permission and has a limit of about 3 minutes.
- **Keep a hold counter** (`MutableStateFlow<Int>`). `keepAlive()` increments it, calls `startForegroundService(...)` inside try/catch (if starting isn't allowed, carry on without it), and returns an idempotent release function.
- **Start it synchronously in `onPause`,** while the app is still visible, which is when Android allows it. Start it only when a write is likely: pending edits exist, or the change counter differs from the one at the last successful save. Otherwise every exit flashes the service.
- **Wrap "Back up now" and choosing a file in it too,** because users leave while those run.
- **In `onStartCommand`, call `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)` first,** even if the work has already finished. Stopping a service started with `startForegroundService` before it calls `startForeground` crashes the app.
- **Then wait for the counter to reach 0 and call `stopSelf(startId)`.** Pass the id from the latest `onStartCommand`, so a stop is ignored if a newer start is already queued.
- **Override both `onTimeout(startId)` and `onTimeout(startId, fgsType)` to call `stopSelf()`.**
- **No notification permission is needed.** Without `POST_NOTIFICATIONS`, the required notification doesn't appear in the notification shade; the app only shows in quick settings' active-apps list for those seconds.

## Dropbox behaviour (observed 9 October 2026)

- **Creating a file in the picker uploads an empty placeholder straight away.** The contents follow a few seconds later. A "zero size" file is that placeholder, or a save still in progress.
- **The first save into a newly created file worked every time.**
- **While Dropbox's battery use was "Optimized", repeated saves to the same file were unreliable.** They did one of three things:
  - replaced the file in place;
  - were uploaded as a separate copy named `<name>_<19-digit number>.<ext>`, Dropbox's temporary-file name used to avoid a name clash. That name does not come from your app.
  - were refused: `openOutputStream(uri, "wt")` succeeded, then writing failed with `IOException` caused by `ErrnoException` (a broken pipe). Dropbox sometimes uploaded the "refused" data about 20 minutes later, when it next became active.
- **After setting Dropbox to "Unrestricted" and opening it once, repeated overwrites worked.** Two "Back up now" taps and a save after leaving the app each replaced the same file within seconds, never via an empty or renamed file. Show this advice in your Settings screen: *"If the backup file is in Dropbox or a similar app, set that app's battery use to Unrestricted in Android Settings → Apps."*
- **Choosing an existing name in the picker creates `<name>_<number>.<ext>`** instead of overwriting.
- **Each device needs its own backup file.** Two devices writing one file produce Dropbox conflicted copies.
- **Dropbox's version history** (on dropbox.com) is the safety net if a bad version is ever uploaded.
- **Not verified:** behaviour after a reboot or long idle, leaving by swiping the app away from Recents, and whether Dropbox supports folder access (`ACTION_OPEN_DOCUMENT_TREE`). Older developer reports say its picker support is limited, so don't design around folder access without testing it.

## Error reporting the user can relay

- **Store an error message and "last saved" time, and show them in Settings.** Classify errors:
  - lost grant or `SecurityException`: "choose the backup location again";
  - incomplete write after plain `"w"`: "choose a different file or location";
  - anything else: the general message plus `(Failed at <step>: <ExceptionClass> / <CauseClass>)`.
- **Show exception class names only, never messages,** because messages can contain file names or URIs. Never log note contents, file names, passphrases or keys.
- This step and error-type detail diagnosed Dropbox's broken-pipe refusal without any access to the phone.

## Testing and diagnosis

- **If the developer's PC syncs the same Dropbox account, watch the synced folder.** Poll each backup file's size and first 16 bytes every 0.5 seconds. That shows in-place replacement (new bytes, same name), copies (new `_<number>` files), empty placeholders, and delayed uploads, all without touching the phone:

  ```powershell
  # Logs every size/first-bytes change of *.ssnb files in a folder.
  param([string]$Folder, [int]$Minutes = 60)
  $last = @{}; $stop = (Get-Date).AddMinutes($Minutes)
  while ((Get-Date) -lt $stop) {
      $now = @{}
      Get-ChildItem $Folder -File -Filter *.ssnb | ForEach-Object {
          $s = [IO.File]::Open($_.FullName, 'Open', 'Read', 'ReadWrite,Delete')
          try { $b = New-Object byte[] 16; $n = $s.Read($b, 0, 16) } finally { $s.Dispose() }
          $now[$_.Name] = "$($_.Length) bytes, head " + (($b[0..([Math]::Max($n,1)-1)] | % { $_.ToString('x2') }) -join '')
      }
      foreach ($k in @($now.Keys + $last.Keys | Select-Object -Unique)) {
          if ($now[$k] -ne $last[$k]) { "{0:HH:mm:ss.f} {1}: {2} -> {3}" -f (Get-Date), $k, $last[$k], $now[$k] }
      }
      $last = $now; Start-Sleep -Milliseconds 500
  }
  ```

- **Run this test matrix on a real phone:**
  1. "Back up now" twice.
  2. Edit, then stay in the app for 90 seconds (the debounced save runs while visible).
  3. Edit, then press Home, without reopening.
  4. Edit, then swipe the app away from Recents.
  5. Reboot, then edit.
  6. Restore on a fresh install.
  7. A file in phone storage (Downloads) as the control: if it accepts repeated saves but Dropbox doesn't, the problem is Dropbox.
- **Emulators:** a 64-bit API 34+ image is needed for `shortService`. An instrumentation test can call `keepAlive()` while an activity is resumed, then check `ActivityManager.getRunningServices` for your service with `foreground == true`, and that it disappears after release. Tests that use Keystore keys requiring user authentication need a screen lock: run `adb shell locksettings set-pin 1234` on a disposable, read-only emulator.
- **Things that look alarming but aren't:** zero-byte files (placeholders or saves in progress), and identical sizes across saves (same content; the salt bytes still differ).
