# Security and lifecycle decisions

## Boundaries

The vault protects private application data at rest and gates normal application access using system authentication. It is not a defence against a compromised/rooted OS, an already-authorized external viewer, screenshots the user permits, or keyboard/clipboard behavior controlled by Android and other applications.

The application requests `USE_BIOMETRIC`. AndroidX adds `USE_FINGERPRINT` compatibility declarations and a signature-only internal receiver permission. It does not request `INTERNET`; manifest removal rules also reject transitive Internet declarations. Application components except the launcher/share activity are non-exported. AndroidX also contributes a profile-installation receiver guarded by the system's `DUMP` permission; it has no note-data interface. Providers grant access only to specifically selected URIs. Automatic cloud backup and device transfer are excluded, including device-protected storage domains.

## Key hierarchy

An AES-256-GCM Android Keystore key (`secure-notes-vault-v1`) is authentication-required per operation, accepts strong biometrics/device credentials, and is usable only while the device is unlocked. It encrypts a random 256-bit vault root. The authentication prompt carries the Keystore cipher through a `CryptoObject`; success unwraps the root. New vaults use an authenticated encryption operation to create the envelope. Associated-data updates and finalization both happen after authentication; submitting metadata before authentication also attempts to use the key. No boolean preference bypasses this operation.

Each generation derives separate 256-bit database and file-key wrapping keys using HMAC-SHA256 with distinct generation-qualified labels. SQLCipher protects the Room database, journals, metadata, search sources, and token postings. SQLite temporary storage is in memory and secure deletion is enabled. An AES-GCM envelope protects the generation's random Tink keyset. Attachment ciphertext uses Tink `AES256_GCM_HKDF_1MB` with the attachment UUID as associated data.

Only sort order is stored unencrypted in a generation-local DataStore file. Backup password verification material is in SQLCipher. Backup containers never include the local root, Keystore identity, or device-bound keysets. An authenticated app session may replace the backup password without entering its old value; the verifier prevents accidental backups under a mistyped password, rather than providing a second authorization boundary. Existing backup files still require their original passwords.

## Session lifetime

The authorization state exists only in process memory. The session is closed at device lock; a new process always requires authentication. Activity recreation and ordinary backgrounding alone do not close it. `KeyguardManager` is checked on resume and before sensitive I/O, with runtime screen/unlock receivers invalidating sessions after screen cycles. Android does not give ordinary apps an unrestricted, reliable history of every keyguard transition. If a screen-off cycle cannot be proven safe, resume conservatively requests authentication. This can also happen if the display turns off during a device lock grace period. No inactivity timer or privileged listener permission is used.

Lock immediately removes sensitive Compose/model state, revokes ordinary repository access and outgoing grants, freezes new edits, and cancels other work. A private close operation finishes writing only the snapshots already accepted before the lock; then it closes the vault and clears keys. It cannot import files or grant read access while locked. Concurrent unlocks are serialized, opening an existing vault is idempotent, and a lock invalidates an unlock still in progress. The VM/OS/libraries can retain inaccessible managed-memory copies until garbage collection; zeroization is best effort. Delayed/missed OS scheduling is not represented as an instantaneous hardware memory wipe.

Both native body editors and Compose title/search/password fields request `IME_FLAG_NO_PERSONALIZED_LEARNING`. This requests cooperation from the selected keyboard; it cannot enforce a third-party keyboard's behavior.

## Attachment access and temporary data

External opening uses a non-exported, grant-enabled content provider. A seekable proxy descriptor decrypts Tink segments on demand. Its callbacks check the live session and per-launch grant, so revoking a grant also prevents subsequent proxy reads through already-open descriptors. Previously read bytes cannot be recalled. No plaintext export file is created for an external viewer or PDF indexing.

Camera capture needs a writable file for third-party compatibility. A narrowly scoped FileProvider exposes only the camera cache directory. The pending destination and stable import IDs are saved in SQLCipher before launching the camera. Its activity-result callback remains registered while the vault is locked; only a non-sensitive success/cancel flag is stored outside the vault. Pending capture files survive recreation/restart until imported or explicitly discarded. Orphan captures are cleaned after authentication. An interrupted capture without a definite result prompts the user to keep or discard it. Images decode through seekable encrypted descriptors with EXIF orientation and bounded output dimensions. There is no gallery-export action. Original URI import permissions are released after import when persistable grants were obtained.

File import is staged to an encrypted `.part`, synced, then renamed before metadata/document publication. Import jobs are recorded in the encrypted database. On restart, partial/orphan files are removed and surviving jobs retry from their source URI. Persistable URI grants are retained while an interrupted job remains pending. Source permission loss is reported without destroying other data. Index progress is stored per attachment (PDF page or plain-text character offset); chunks finish complete words. Parsing failures fall back to filename search. PDF parsing uses Android's maintained native parser in the app process; keep Android security updates installed.

## Persistence and replacement

The repository retains only the latest immutable snapshot per note. Autosave waits for 300 ms of quiet, with a maximum scheduling delay of one second during continuous typing (disk completion can take longer). Navigation/backgrounding flush pending changes. Saving reindexes changed sources only and updates the affected note in memory. Failed saves remain pending; failures are reported instead of silently discarded.

If a final lock-time database write fails, the remaining snapshots are authenticated-encrypted under a generation-specific key and saved as `pending.edits` for replay after authentication. If even that write fails, only encrypted recovery bytes remain in process memory, with a warning that closing the process may lose those edits. No software can promise persistence when all storage writes fail. Recovery records are removed after successful replay. They are temporary crash recovery, not persistent undo history.

Search enumerates distinct matching terms in ordered pages, keeping prefix/exact matching separate from fuzzy vocabulary matching. Matching postings are also paged, so a large document cannot crowd other notes out of an arbitrary candidate limit.

All current data belongs to a UUID generation under app-private `noBackupFilesDir`. Restore checks the bounded container header before asking for a password. This is structural validation only; it is repeated when decrypting, and authenticity is established only by full authenticated decryption. A key-envelope authentication failure is reported as an incorrect password or damaged file, without pretending to distinguish them. Restore prepares a separate encrypted generation, validates references/checksums and the version, and waits for explicit confirmation. Publishing an AtomicFile generation pointer is the commit point. Startup selects the published generation and removes abandoned generations. The previous generation exists only to make replacement recoverable until commit, not as user-accessible history.

Settings and password verifiers are included in the replacement transaction's generation. Android/flash/filesystem durability ultimately constrains power-loss guarantees; ordinary process interruption is covered by staging, database transactions, and atomic publication. No automatic deletion is performed when the current database or key cannot be opened.

## Review rules

Never add note contents, filenames, passwords, or keys to logs, exceptions shown verbatim to users, saved-state bundles, analytics, or crash reporters. Do not add a release authentication bypass. Preserve the no-network manifest check, ciphertext inspection tests, and malformed/truncated backup tests when changing storage or dependencies. Future migrations must not use `fallbackToDestructiveMigration`.
