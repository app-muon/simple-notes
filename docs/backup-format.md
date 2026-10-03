# Secure Notes backup format, version 1

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

1. `manifest.json`, UTF-8 kotlinx.serialization JSON, with manifest version `2`: all note/document records, attachment metadata, and the manual note order (`order`, every note ID exactly once). The reader also accepts manifest version `1`, which carries a sort mode (`sort`) instead of an order.
2. One `attachments/<uuid>` entry for every referenced original attachment, containing its original bytes.

Notes contain UUID, title, a version-2 document (body text, one line format per line — paragraph, heading 1/2, bullet, numbered, or checklist with its checked state — bold ranges as body character offsets, and the ordered attachment IDs), and creation/edit timestamps. Attachment metadata contains UUID, owner note UUID, original filename, MIME type, plaintext byte length, and SHA-256 checksum. Indexing flags are reset in backups. UUID entry names—not user filenames—prevent traversal and filename collisions. The reader rejects unknown entries, duplicate IDs, attachments referenced twice, dangling/foreign references, unexpected versions, missing attachments, and size/checksum mismatches.

The reader caps the JSON manifest at 16 MiB to bound untrusted metadata allocation. This is not a per-file attachment limit. Attachment payloads stream to encrypted staging; declared lengths and available disk space are checked. The reader drains the authenticated stream even after the ZIP reader finishes, so a valid-looking ZIP prefix is never enough to authorize restore. All checks finish before replacement confirmation is shown.

The source notes remain untouched on wrong password, invalid authentication, truncation, unsupported versions, insufficient storage, or cancelled validation. After validation, the import passphrase becomes the restored vault's recovery passphrase, and its `recovery.key` is written inside the staged generation. Rebuildable search indexes and unfinished operations are omitted. Future versions need explicit readers/migrations and fixture-based compatibility tests; silently interpreting an unknown container or document version is forbidden.
