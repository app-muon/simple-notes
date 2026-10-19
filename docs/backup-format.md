# Notes backup format, version 1

File extension: `.ssnb`. All integer header fields are signed big-endian Java `DataOutputStream` integers; only the positive values below are accepted.

| Field | Encoding |
| --- | --- |
| Magic | Four bytes, ASCII `SSNB` |
| Container version | 32-bit integer, `1` |
| PBKDF2 iteration count | 32-bit integer; writer uses `600000`; reader accepts `600000` through `2000000` |
| Salt | 32 cryptographically random bytes |
| Wrapped keyset length | 32-bit integer, `28` through `4096` |
| Wrapped keyset | 12-byte random GCM nonce followed by encrypted keyset and 16-byte GCM tag |
| Payload | Tink AES256_GCM_HKDF_1MB streaming ciphertext |

The password is the vault's 8-word recovery passphrase in canonical form: lowercase EFF large-wordlist words separated by single ASCII spaces (input is lowercased and whitespace is collapsed before use; hyphens inside list words are kept). PBKDF2-HMAC-SHA256 produces a 256-bit AES key from those characters using the platform's `PBEKeySpec` implementation. Interoperability implementations must match Java's UTF-8 password conversion. Password/key buffers are cleared where application-owned. The derived key is not saved.

The bytes from Magic through Salt are the AES-GCM associated data for wrapping the fresh binary Tink keyset. The **complete header including the wrapped keyset** is associated data for streaming encryption. No names, note counts, or titles appear in the plaintext header. The Tink wire format supplies segment nonce derivation, position binding, and final-segment authentication. Do not replace it with custom chunk encryption.

The decrypted payload is a ZIP64-capable ZIP stream containing:

1. `manifest.json`, UTF-8 kotlinx.serialization JSON, with manifest version `3` or `4`: all note/document records, attachment metadata, manual note order (`order`, every note ID exactly once), and tag definitions (`tags`, each with stable UUID `id` and display `name`). Every note carries default-empty `tagIds`. Unused tags are included. The reader also accepts tagless manifest version `2` and version `1`, which carries a sort mode (`sort`) instead of an order. Both restore as untagged. The encrypted container remains version `1`.
2. For manifest versions 1–3 only: one `attachments/<uuid>` entry for every referenced original attachment, containing its original bytes. A version-4 manifest is an index: its ZIP contains exactly `manifest.json`, and its attachments are separate files (below).

Notes contain UUID, title, a version-2 document (body text, one line format per line — paragraph, heading 1/2, bullet, numbered, or checklist with its checked state — bold ranges as body character offsets, and the ordered attachment IDs), and creation/edit timestamps. Attachment metadata contains UUID, owner note UUID, original filename, MIME type, plaintext byte length, and SHA-256 checksum. Indexing flags are reset in backups. UUID entry names—not user filenames—prevent traversal and filename collisions. The reader rejects unknown entries, duplicate IDs, attachments referenced twice, dangling/foreign references, unexpected versions, missing attachments, and size/checksum mismatches.

The reader and writer cap the JSON manifest at 16 MiB to bound untrusted metadata allocation. This is not a per-file attachment limit. Attachment payloads stream to encrypted staging; declared lengths and available disk space are checked. The reader drains the authenticated stream even after the ZIP reader finishes, so a valid-looking ZIP prefix is never enough to authorize restore. All checks finish before replacement confirmation is shown.

Tag names must be nonempty, trimmed, and have collapsed whitespace. Validation rejects duplicate tag IDs, duplicate names ignoring case, duplicate assignments, and dangling tag references before replacement. Definitions and assignments are stored only in SQLCipher. Tag creation, renaming, deletion, and note assignments notify automatic backup and change the manifest fingerprint; the active list filter and Find state are transient and are never backed up.

The source notes remain untouched on wrong password, invalid authentication, truncation, unsupported versions, insufficient storage, or cancelled validation. After validation, the import passphrase becomes the restored vault's recovery passphrase, and its `recovery.key` is written inside the staged generation. Rebuildable search indexes and unfinished operations are omitted. Future versions need explicit readers/migrations and fixture-based compatibility tests; silently interpreting an unknown container or document version is forbidden.

## Version 4: index and attachment files

Pairs created by this version save each attachment once, in its own file, and keep only an index in the A/B files:

```
NotesBackups/
  Notes-<timestamp>-A.ssnb
  Notes-<timestamp>-B.ssnb
  Notes-<timestamp>-attachments/
    <attachmentId>.ssna
```

Providers may adjust names (for example a collision suffix such as `<attachmentId>.ssna (1)`); the actual names are recorded.

**Index.** The `.ssnb` envelope, key derivation and container version 1 are unchanged. Manifest version 4 keeps every version-3 field and adds:

- `attachmentKey`: the binary Tink keyset that encrypts the pair's attachment files, as standard padded base64 with no line breaks. After the whole index has been authenticated, the reader requires the exact canonical encoding, at most 4096 bytes, and exactly one enabled primary key whose parameters equal `AES256_GCM_HKDF_1MB`.
- `attachmentFolder`: the provider-confirmed display name of the attachment directory. Required when the index has attachments; absent when the pair has never saved one.
- `attachmentFiles`: a map from each attachment ID to its actual file display name. Its keys are exactly the manifest's attachment IDs.

Folder and file names must be single path components: not blank, not `.` or `..`, at most 255 characters, with no `/`, `\` or NUL. File names must be unique ignoring case. The index never contains device URIs. Version-4 fields are omitted from version 1–3 manifests, which a reader rejects if they carry them, so a version-3 manifest's bytes and content fingerprint are unchanged. The 16 MiB limit applies to the complete serialized version-4 manifest, including the new fields.

**Attachment file (`.ssna`).**

| Field | Encoding |
| --- | --- |
| Magic | Four bytes, ASCII `SSNA` |
| Version | 32-bit big-endian integer, `1` |
| Payload | Tink `AES256_GCM_HKDF_1MB` streaming ciphertext of the original attachment bytes |

The associated data is the 8-byte header followed by the UTF-8 bytes of the canonical (lowercase) attachment UUID. Tink's normal fresh randomness is used for every encryption, so the same attachment never encrypts to the same bytes twice; there is no custom nonce or chunk scheme. Authentication binds a file to its attachment ID, not its name. Readers consume the whole authenticated stream, including after their consumer stops early, then check the plaintext length and SHA-256 recorded in the manifest. A file cut at a segment boundary fails, because Tink marks the final segment.

**Keys and retention.** Each new pair gets a new random attachment key, stored only in encrypted device settings and in that pair's encrypted indexes. A phrase change requires a new pair and so a new key; earlier files are left untouched. Completed attachment files are kept for the pair's lifetime, including those whose attachments were since removed, so the other slot and older index copies in the cloud stay restorable. Storage therefore grows with everything imported during a pair's lifetime; a new pair starts again from current content. A document created just before a crash, and never recorded, is also left in place: it is never adopted by name.

**Restore.** The selected index is authenticated and validated completely before any attachment is requested. A version-4 index with attachments then needs read access to the folder holding its attachment directory, or that directory itself, even if it was moved or renamed. Each mapped name must match exactly one direct child; if it is absent, exactly one child whose name starts with the attachment ID is accepted. Files not referenced by the index are ignored. Every file goes through the same stream authentication, length, SHA-256 and free-space checks as an embedded attachment before replacement is offered. A version-4 index without attachments restores from the file alone.

## Verified A/B destinations

A/B is a device-side save protocol, not a new container or manifest version. Both files are independently restorable .ssnb backups; for a version-4 pair, each needs the pair's attachment directory, which both indexes share. Setup writes the same fresh ciphertext to both; subsequent saves create fresh salts and keys and replace only the inactive slot. Exact ciphertext lengths and SHA-256 digests are stored in encrypted device configuration, outside this format. That configuration is never exported. No creation date was added to the manifest.

Device configuration version 2 adds `pairTag`, which marks a version-4 pair, `attachmentFolder` (URI and confirmed name), set when the pair saves its first attachment, and `check`, the last completed full check: its time, each slot's outcome (verified, incomplete or damaged) and the two index digests it read. Version-1 records still load; a pair without `pairTag` keeps saving whole version-3 files. The pair's attachment key and one record per attachment file (plaintext length and SHA-256, document URI and name, expected ciphertext length and SHA-256, and whether it was completed) are separate SQLCipher secret rows keyed by `pairTag`, never part of the configuration record or any export. Restore publication copies them, with the configuration, into the new vault generation. An older build cannot read version-2 settings: it reports invalid configuration and blocks backup changes until the app is updated again.

Device configuration slots also record whether the provider confirmed their display name (`nameConfirmed`, default `true` for older records). A requested fallback name is labeled unconfirmed in Settings; file operations continue to use URIs. Legacy error revision fields remain readable but do not suppress retries after restart. These changes require no Room or exported-backup format migration.

A restore first tries the confirmed device phrase and then permits phrase entry after key-envelope authentication failure. The successful phrase becomes the restored vault's recovery phrase. A different phrase requires review and a new pair, retaining historical files. Full payload validation and explicit replacement confirmation remain mandatory.
