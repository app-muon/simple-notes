# Acceptance checklist

Automated tests run against synthetic notes and files. Do not run instrumentation tests on a phone containing real notes; test fixtures clear this app's data.

## Automated coverage

- Find: literal repeated/overlapping matches, punctuation, phrases, line breaks, mixed case, composed/decomposed accents, emoji UTF-16 offsets, cancellation, empty queries, no matches, and wrapping navigation.
- Find state: pending counters/navigation, superseded queries, cancelled workers, immediate Close during debounce, independent normalized-text cache reuse/invalidation, multiline grouping, and trimmed title offsets.
- Find follow-up: first-line checklist matches retain the whole checkbox, deep wrapped checklist matches remain visible, tag assignment/tag-only history preserve the Find destination, and text/formatting undo/redo retain the editor selection. Title queries spanning trimmed whitespace exercise the production clipping path.
- Find integration: temporary editor spans, preserved bold and undo/redo history, latest cursor/typing taking priority over older results, no-match range preservation, deferred-close invalidation, one-tap Done, failed-save editability, staged Back, tapping to edit without a Find jump, deep wrapped matches, and whole checklist-row visibility for global search.
- Tags: assignment/removal immediately after creation, normalized creation, direct DAO insert/update conflicts retaining original rows, unchanged notifications on failures, unused definitions, catalog reconciliation and affected undo clearing, stale snapshots after deletion, filtered search, inherited/discarded drafts, lock reset, and full/filtered ordering with unchanged hidden slots and invalid-input rejection.
- Recovery: additive encrypted schema 1→2 upgrade, tag manifest validation, tagless compatibility, tag backup/restore round-trip, and metadata-only backup fingerprint/change notifications.

- Case/diacritic normalization with original offsets, prefixes, one-edit/transposition matching, sorting, empty/untitled behavior, document serialization, and undo/redo.
- Authenticated encryption roundtrips across segment boundaries, fresh randomness, wrong password, altered headers/payloads, truncation, unsupported versions, unreasonable KDF parameters, full-stream authentication after partial consumption, and manifest reference validation.
- SQLCipher persistence/reopen, encrypted attachment bytes, seekable decryption, PDF/text extraction, deletion/index cleanup, staged restore versus commit, settings replacement, corrupt/cancelled restore, and abandoned staging cleanup.
- Locked UI, create/autosave/read/back, search navigation, deletion confirmation, share destination choice, and reading-mode checklist toggles.
- Debounced/conflated edits, continuous-typing save bounds, device lock with pending saves, encrypted recovery after a failed final database write, and duplicate/stale unlock prevention.
- Search beyond 10,000 unrelated and matching postings, deep-result scrolling and character highlighting, English accent-aware sorting, and shared text replacing an empty draft body.
- Camera destination/result recovery across service recreation, idempotent import, EXIF image rotation, and proxy descriptor release.
- Backup header validation before asking for a password, restore replacement warning/cancellation, and the backup CreateDocument contract/cancellation cleanup. The picker contract test uses a test activity-result registry; real providers still need the checks below.

See generated Gradle reports for the actual run results; passing a compile alone is not a passing instrumented test run.

## Physical Pixel 8 checks

- First launch with a secure lock shows the prompt before any content. With no device credential, only the setup screen is available.
- Fingerprint and strong face authentication work; choose device credential and verify PIN/password/pattern fallback. Cancel and temporarily lock out biometrics without exposing notes.
- Switch apps and return without locking: no new prompt. Lock while foregrounded/backgrounded, unlock the device, then return: authentication is required. Force-stop/relaunch also prompts.
- Create a note, type rapidly, immediately press the power button, unlock, rotate, background, and return. Confirm the latest text and formatting survive. Verify keyboard/predictive Back exits editing before leaving the note.
- At large font scale and with TalkBack, edit/check/reorder content; confirm accessible names, touch targets, contrast, and both system themes.
- On compact layouts in both themes, open Find while reading/editing; navigate whitespace-trimmed titles and deep wrapped-body matches, type/paste/undo/redo, move the caret or select a range, and close with both toolbar and system Back. Close immediately after typing a query and confirm the current destination. Confirm Done finishes editing in one tap, Back remains staged, normal editing/tapping never jumps to an old result, and highlights do not affect copied/saved formatting. Check blank pending counters, disabled navigation, keyboard focus, scrollbars, accessible Find labels, and fully visible checklist search rows.
- Filter by All notes, Untagged, and a long tag name; verify the header stays compact, the tag sheets scroll, and their controls work with TalkBack. Drag tagged rows across hidden notes and repeat with accessible Move up/down. Rename/delete the selected tag and verify search, assignments, and automatic backup updates.
- Scroll long notes, note lists, search results, settings, and recovery screens: the scrollbar should track the viewport and disappear when the content fits. On a narrow screen, check the formatting toolbar's horizontal indicator. Verify text selection and note dragging still work beside the indicators.
- Import photos/files; cancel each picker and the camera. Rotate or recreate the notes activity while the camera is open, and test interrupted-capture recovery after process death. Check portrait photo orientation, image pinch/pan, attachment Move left/right and Remove, duplicate filenames, a large attachment, missing source, unavailable viewer, and return from a viewer after permission revocation.
- Share text/URL, one image/file, and multiple files from other apps. Confirm cancellation does not create a note and both destinations append content predictably.
- Set a cloud-backed provider app's (for example Dropbox's) battery use to Unrestricted, then choose an automatic backup file in it. Tap "Back up now" twice, then edit a note and leave the app at once; confirm within a couple of minutes that the provider shows the same file replaced each time, not a renamed `<name>_<number>` copy. Reboot and confirm a later edit still updates it. On a fresh install, choose "Restore from backup" and restore using only the 8-word recovery passphrase.
- Remove and re-add the device screen lock (invalidating the Keystore key). Confirm the lock screen offers "Unlock with recovery passphrase" and that the passphrase reopens the same notes. Confirm unsupported/corrupt/truncated files leave current notes intact.
- Interrupt imports/indexing/backup/restore with device lock and process termination. Retry after authentication. Check low-storage behavior using a disposable emulator, not a phone with real notes.
- Confirm normal Recents and screenshots remain available; inspect release permissions and logs using synthetic sensitive markers.
- Time search/opening with the intended ten-note/ten-attachment collection. Targets are approximately 200 ms after search debounce and 300 ms to open an ordinary note after authentication.

External file viewers and Android file-provider/camera applications can behave differently between devices. Emulator results do not establish that these physical-device checks passed.
