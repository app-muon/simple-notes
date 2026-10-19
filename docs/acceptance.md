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
- Backup header validation before asking for a password, restore replacement warning/cancellation, and the backup OpenDocumentTree contract/cancellation cleanup. The picker contract test uses a test activity-result registry; real providers still need the checks below.

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
- Share a photo and a PDF out, from the image viewer and from a tile menu in both modes, to a messaging app, email and a cloud app. Return to Notes before sending and confirm the send still completes; lock the phone and confirm later reads fail. Notes must not appear in its own share sheet. Save to device into Downloads and a cloud provider, then cancel the picker, lock while it is open, and lock during a large save: no empty or partial file may remain. Overwrite an existing longer file in each provider and confirm it ends up exactly the attachment, or the save reports failure. Check new camera shots are named by time and older ones appear as Photo.jpg.
- Share text/URL, one image/file, and multiple files from other apps. Confirm cancellation does not create a note and both destinations append content predictably.
- Choose a dedicated backup folder in Downloads and verify both A/B files. Test repeated saves, idle-provider behaviour, process interruption and reboot. Verify both files are independently restorable, then verify both external uploads separately. Test another provider explicitly; stale read-back must fail safely.
- Remove and re-add the device screen lock (invalidating the Keystore key). Confirm the lock screen offers "Unlock with recovery passphrase" and that the passphrase reopens the same notes. Confirm unsupported/corrupt/truncated files leave current notes intact.
- Interrupt imports/indexing/backup/restore with device lock and process termination. Retry after authentication. Check low-storage behavior using a disposable emulator, not a phone with real notes.
- Confirm normal Recents and screenshots remain available; inspect release permissions and logs using synthetic sensitive markers.
- Time search/opening with the intended ten-note/ten-attachment collection. Targets are approximately 200 ms after search debounce and 300 ms to open an ordinary note after authentication.

External file viewers and Android file-provider/camera applications can behave differently between devices. Emulator results do not establish that these physical-device checks passed.

## Verified A/B rollout checks

- From list, search, reading, editing, an image and an empty draft, use Review backup. Confirm pending edits save, failed saves remain editable, successful navigation clears the session, Settings Back returns to the list, and the banner never doubles the status-bar inset.
- Return a folder before and after resume, rotate during selection, reselect from the provider warning, cancel the picker and lock before its callback. Confirm one setup reservation, no competing automatic save, visible rejection reasons and balanced service holds. Restore and phrase viewing stay available.
- Finish the activity after setup is accepted, both while queued before resume and during its initial flush. Accepted setup must survive the cleared view model; unsubmitted selection and lock cancellation must retain their existing cleanup.
- Edit after a running automatic/manual save captures its snapshot, then press Home. Confirm the later edits reach local storage and a follow-up backup under the same uninterrupted service hold. A failure that blocks further backup writes must still allow the pause flush.
- Remove and restore folder access; eligibility must not call the provider from composition or Main. A late permission refresh in the background must wait for foreground return unless an existing hold covers work.
- For an active-file access failure, confirm **Check backup access** is enabled, refreshes without a service or write, and changes to **Back up now** when the folder grant is available. An unreadable active file must fail before encryption on repeated retries; a file changed during encryption must still be caught before writing.
- Exercise absent, blank and failed filename queries. Confirm explicitly unconfirmed requested labels, URI-based identity and cleanup after wrapped security/cancellation failures. Verify older configuration records remain readable.
- Oversized metadata waits for edits within a process, survives lock/unlock suppression and gets one fresh check after restart even when revision counters coincide. Unreadable active files offer retry/new pair without claiming corruption or a retained verified file.
- Verify migration preserves the existing single-file backup, name, saved time, and grant until new-pair commit. Confirm old files are never overwritten or automatically deleted.
- Choose a dedicated Downloads subfolder. Verify both actual names (including collisions), independent A and B restores, alternating active slots, both-file upload-only sync, and grants across reboot.
- Terminate the process during inactive copying and before final metadata recording. Restart and verify the old active copy, durable pending work, and a successful retry.
- Test lock during metadata preparation and ciphertext writing; balance service holds on cancellation and timeout.
- Stage restore, then change/disable the destination before confirming. Confirm latest configuration is transferred. Restore another phrase and verify review plus a new pair is required.

## Attachment file checks

- With Autosync in upload-only mode, confirm uploads include the attachments subfolder. Add 20 photos and save; then edit only text and confirm that only one index file changes (modified times and the sync app's log). Add one photo and confirm exactly one new `.ssna` plus one index. Remove a photo and confirm its file stays.
- Measure an initial setup, an interrupted and resumed save, and a text-only save with a photo-heavy collection. Leave the app during a large first save, let the service time limit pass, and confirm the next save continues without rewriting completed files.
- Delete or truncate one `.ssna` in phone storage and confirm the next save reports damage without writing either index; revoke folder access and confirm it reports the files as unverified instead.
- Run **Verify backup** on a photo-heavy pair and time it. Change one byte of an `.ssna` without changing its size: a routine save must not notice, and Verify backup must report damage and pause the pair. Leave the app and lock the phone during a check: nothing may be recorded, and the save on leaving must still complete. After a later save, Settings must show the check as made before it.
- Download the whole folder to a fresh device or emulator and restore each of A and B with only the phrase. Choose the parent folder, the attachments folder itself, a renamed copy and a wrong folder; cancel the folder picker; lock while validating. Current notes must stay intact until replacement is confirmed. Restore a partially synced copy and confirm it is rejected.
- Upgrade the app over an existing whole-file pair: it keeps saving whole files and Settings suggests a new pair. Create one in the same folder and confirm the old files remain.
- Exercise missing phrases, active-file corruption, stale provider read-back, lost grants, full storage, uncertain settings commits, setup cancellation, and same-folder reselection.
- Record local verification and external upload as separate acceptance results. A local pass never establishes that the sync provider uploaded either file.
