# Verification

## Backup lifecycle follow-up, 15 October 2026

Fixed the three follow-up review findings: an automatic/manual save keeps its original service hold through deferred background flushes and retries; accepted setup transfers from the activity view model to the process-wide scheduler; and active-file verification now precedes encryption as well as the inactive write. Access failures offer **Check backup access** while cached permission is unknown or missing. That action only refreshes eligibility on IO.

- Full JVM suite: **98 passed**. New regressions capture edits after the first snapshot, leave while encryption is suspended, and verify that the latest revision reaches the active slot without foreground return or another service acquisition. They also cover a required pause flush after backup becomes ineligible, lock during the background follow-up, access refresh without encryption/writes/holds, repeated unreadable-active retries without preparation, and active damage both before and during preparation.
- Debug APK and instrumentation APK: **built successfully**. Lint: **0 errors, 9 existing advisory warnings**. `git diff --check`: **passed**.
- Focused Android run: **8 passed, 1 expected skip** on a disposable read-only Pixel 8 emulator, Android 17 / API 37, port 5580. Actual activity destruction retained both running and queued setup, through direct selection and provider acknowledgement. Recreation/lock invalidation, the enabled access-check UI, separate setup messages and service lifetime also passed. The real Downloads provider created and verified a pair, alternated saves and restored both files independently. The skipped test requires an interrupted fixture.
- Force-stop/reboot recovery: **passed** on this build. Interrupted the synthetic test after durable pending publication and 32 bytes of an inactive write, rebooted, and confirmed credential unlock before running recovery. The persisted grant and active digest survived; the inactive digest differed. A scheduler foreground retry repaired the pair and balanced its service hold, and both slots restored independently. All **9 distinct targeted Android tests** therefore passed across these runs. The full instrumentation suite was not repeated for this follow-up.

Artifacts are under `%TEMP%/secure-notes-review-build`. Debug APK SHA-256: `772eec525f069c917eb52cf5246cf467d0dfcc21a6454f16db29337f5768a359`. Transcripts: `%TEMP%/notes-followup-android-tests.txt`, `%TEMP%/notes-followup-interruption.txt` (intentional process termination), and `%TEMP%/notes-followup-reboot-recovery.txt`. The earlier complete-suite results remain in their dated record. Only synthetic vaults and UUID test folders were used; no user backups or signed release were replaced. External upload acceptance remains separate from local verification.

## Backup navigation, setup reservation and recovery review, 15 October 2026

Implemented session-safe Review backup navigation, conditional status-bar inset consumption, setup reservation and callback invalidation, foreground ordering and service ownership, process-local oversized-snapshot suppression, cached IO-refreshed grant eligibility, cycle-safe provider error classification, ESPIPE handling, unconfirmed filename labels and obsolete notification-channel cleanup. The A/B write protocol, encryption, size limits, Room schema and exported .ssnb format remain unchanged.

- Full JVM suite: **92 passed**. Covers callbacks before/after resume, slow flushes, competing automatic work, reservation cancellation and lock, pre-body job cancellation, immediate-dispatch ownership, restored configurations, restart blocking, deferred fingerprints, restored/stale grants, provider checks off the caller thread, matching revision counters after restart, wrapped/cyclic/deep causes, redacted diagnostics, filename fallback/cleanup and older slot decoding.
- Debug APK and instrumentation APK: **built successfully**. Final lint: **0 errors, 9 existing advisory warnings**. `git diff --check`: **passed**. No dependency or test framework was added.
- Full storage/UI/provider instrumentation run: **72 passed, 1 expected skip**, on a disposable read-only Pixel 8 emulator, Android 17 / API 37, port 5580, with a synthetic test PIN. The skipped test requires an intentionally interrupted backup fixture.
- Final app build: **7 focused UI tests passed**, plus **1 real MainActivity recreation/lock test passed**. Coverage includes note/read/image/empty-draft navigation, a slow or failed flush, repeated actions, cleared session state, Settings Back, banner appearance/disappearance without duplicate status spacing, provider warning/reselection/cancellation and explicitly unconfirmed names with separate setup errors. The final navigation test was expanded to include search and passed again after reboot. The recreation test initially had a JUnit return-signature error; it was corrected, rebuilt and passed.
- Real Downloads provider: **passed**. Setup results were queued until foreground return, the service hold was acquired before the initial flush and released afterwards, both files verified and restored independently, saves alternated, same-folder reselection retained the historical pair, and disabling retained the files.
- Force-stop/reboot recovery: **passed** on the final app build. After durable pending publication and 32 bytes written to the inactive document, the host force-stopped the process and rebooted the emulator. The persisted folder grant survived, the prior active digest matched and the inactive digest differed. The scheduler's normal foreground retry repaired the pair, cleared pending, switched the active slot and balanced its holds. Both A and B then restored independently. An initial post-reboot invocation ran before credential unlock completed and could not launch the test activity; both recovery and navigation passed after confirming the emulator user was unlocked.

All **75 distinct instrumentation tests** passed across the full run and focused follow-ups, including the formerly skipped recovery case. The complete suite was not repeated after the final callback guard and test-only additions; the affected paths were covered by the focused runs. Verification used synthetic vaults and UUID test folders only. No user backups or signed release artifacts were replaced.

Artifacts are under `%TEMP%/secure-notes-review-build`: `app/outputs/apk`, `app/reports/tests/testDebugUnitTest`, `app/reports/androidTests/connected/debug` and `app/reports/lint-results-debug.html`. Final debug APK SHA-256: `ebf7d0ac7c54f21d7648ee99b0d8ba69d35c1caea11a90bfcf4cd0dbd26ae012`. Additional transcripts: `%TEMP%/notes-review-focused-tests.txt` (seven UI passes and the initial recreation signature failure), `%TEMP%/notes-review-recreation-test.txt` (corrected pass), `%TEMP%/notes-review-interruption.txt` (intentional force-stop) and `%TEMP%/notes-review-reboot-final.txt` (recovery and expanded navigation passes).

**External acceptance remains separate:** these local provider and emulator results do not establish either file's cloud upload completion, behavior of other providers, physical-device behavior or hardware power-loss durability. Check both external uploads independently using the acceptance checklist.

## Verified A/B backups, 14 October 2026

Implemented verified alternating destinations without changing the .ssnb container or manifest versions. Existing single-file destinations require folder migration and are never overwritten. Configuration remains in SQLCipher; restore transfers the latest device configuration at publication. A changed recovery phrase requires authenticated review and a new pair.

- JVM tests: **77 passed**, including coordinator/provider failure matrices, uncertain persistence, lock transitions, retry eligibility, setup cleanup, phrase review, and scheduler/service lifetimes.
- Debug build and test APK assembly: **passed**. Lint: **0 errors, 9 existing advisory warnings**. No new dependencies or test framework were added.
- Complete storage/UI/provider instrumentation: **64 passed, no skips**, on a disposable read-only Pixel 8 emulator, Android 17 / API 37, port 5580, with a synthetic test PIN. Coverage includes a streamed backup larger than 20 MiB, staged restore, canonical fingerprints, legacy compatibility, and first-run versus missing established phrases.
- Two additional Settings UI tests: **2 passed** on the final build, checking the migration banner and preserved filename, disabled saving after phrase changes, authenticated-review routing, and continued availability of restore/phrase viewing. The final service-eligibility adjustment was also checked by the full JVM suite and lint.
- Real Android folder picker and external-storage document provider: **passed** using a unique Downloads subfolder. Both slots verified and restored independently; saves alternated; same-folder reselection created new documents and retained the historical pair; disabling retained files.
- Process interruption plus reboot: **passed**. The test was force-stopped after writing 32 bytes to the inactive document, with pending already committed. After reboot, the persisted read/write folder grant survived, the prior active digest matched, and the inactive digest did not. A normal retry repaired the pair, cleared pending, switched active slots, and both files restored independently. This uses a synthetic isolated vault; no production authentication bypass exists.

Build artifacts/reports are under %TEMP%/secure-notes-build. The final debug APK SHA-256 is 5ac1a55d2e28d9f9542a99aa0483e13fc6ec140afad3abf5e1944f00ebbbfe7f. Extra transcripts are %TEMP%/notes-ab-settings-ui-verification.txt and %TEMP%/notes-ab-reboot-verification.txt. The signed dist release was not replaced.

To repeat interruption testing, install the debug and androidTest APKs on a disposable API 35+ emulator. Invoke BackupProviderTest#downloadsPairVerifiesAlternatesAndBothFilesRestoreIndependently with instrumentation argument backupCheckpoint=interrupt. When the app-private cache/ab-interrupt-ready marker exists, force-stop dev.securenotes, reboot and unlock the emulator, then invoke BackupProviderTest#resumesInterruptedDownloadsPairAfterReboot. The latter test is intentionally skipped in an ordinary run without an interrupted fixture. Test-only fixture roots and keys are synthetic and are removed after recovery verification.

**Separate acceptance still required:** external upload of both files, other document providers, and physical-device behaviour. Local read-back, emulator reboot, and process-interruption success do not establish cloud upload completion or hardware power-loss durability. Verification used synthetic vaults and UUID test folders only; user backups were not touched.

## Dropbox follow-up, 10 October: current source 1.0.2 (3)

The 9 October advice to set the provider app's battery use to Unrestricted was disproven later that day (see below), and the save refused at about 11:00 never reached Dropbox, even overnight. Settings, the README, the acceptance checklist and `encrypted_backup_tips.md` now recommend a phone-storage file uploaded by a sync app for dependable Dropbox backups. The write now tries `"rwt"` before `"wt"`, because read/write modes imply a seekable file while `"wt"` may be a pipe. Unexpected failures now include the errno name after the exception type.

- Owner's phone, with Dropbox idle overnight and not opened during the test: the save on opening Notes, two "Back up now" taps, and a save after 15 minutes idle and leaving the app all failed with `(Failed at write rwt: IOException / ErrnoException EBADF)`. Nothing reached Dropbox; `Notes-new.ssnb` stayed at its 9 October 10:47 version. Dropbox accepts `"rwt"` but returns a descriptor that cannot be written, so `"rwt"` does not fix direct Dropbox saves. The 9 October `write wt` failures predate the errno name, so their code is unknown.
- JVM unit tests: **56 passed**. Debug lint: **0 errors, 9 existing advisory warnings**, none in changed code. `git diff --check`: **passed**.
- Full UI and storage instrumentation: **59 passed** on the same emulator with its test PIN.

## Automatic backup to Dropbox, 9 October: source 1.0.2 (3)

Automatic backups to a Dropbox file on the owner's phone failed three ways. They were diagnosed from the phone's Settings messages and from the synced desktop Dropbox folder, polled every 0.5 s for size and leading bytes; no device logs were used.

- Reading the size back after a truncating write saw Dropbox's previous copy until its upload finished. Successful saves were therefore reported as incomplete and retried at every trigger. The read-back now runs only after the non-truncating `"w"` fallback.
- Android froze the app seconds after it was left, so a save started on leaving finished only when the app was reopened. A `shortService` foreground service (`BackupKeepAlive`) now holds the process while a save that needs writing runs on leaving, and during "Back up now" and choosing a file.
- While Dropbox's battery use was optimised, only the first save into a newly created file was reliable. Later saves to the same file were replaced, uploaded as `<name>_<number>.ssnb` copies, or failed at `write wt: IOException / ErrnoException`; one refused save was uploaded about 20 minutes later. A file in Downloads accepted repeated saves. With Dropbox set to Unrestricted and just opened, two "Back up now" saves and a save after leaving the app each replaced the same file within seconds, with no empty or renamed intermediate. About 13 minutes later, with Dropbox idle, the next save failed at `write wt` again, so that setting does not fix it: repeated overwrites succeeded only shortly after the Dropbox app or picker had been on screen. (The Settings advice added that day was corrected on 10 October.) Unexpected failures show the failed step and exception types (never messages, which can contain names).

Results:

- JVM unit tests: **56 passed**. Debug lint: **0 errors, 9 existing advisory warnings**, none in changed code. `git diff --check`: **passed**.
- Full UI and storage instrumentation: **59 passed**, including a new test that `keepAlive` runs `BackupKeepAlive` in the foreground until every hold is released. An earlier run on the emulator without its test PIN failed only `graceResumesAfterRepeatedLocks`, which needs the PIN.
- Physical phone with Dropbox: as above. Not yet checked: behaviour after a reboot or long idle, leaving by swiping from Recents, and restoring from the Dropbox copy.

Tests used synthetic vaults on a disposable, headless, read-only Pixel 8 emulator (Android 17 / API 37, emulator-5580) with a test PIN. Artifacts are under `%TEMP%\secure-notes-build-2` (`app/outputs/apk`, `app/reports/tests/testDebugUnitTest`, `app/reports/lint-results-debug.html`, `app/reports/androidTests/connected/debug`). The signed `dist` release was not replaced.

## Find checklist and cursor-priority follow-up: source 1.0.2 (3)

Find now keeps the full checkbox visible for matches on a checklist's first visual line while retaining text-based scrolling for deep wrapped matches. Tag assignments and tag-only history preserve Find's cursor destination; text/formatting history and checkbox edits count as editor interactions. Removed redundant saved-selection state and used the tested title-clipping helper in reading-mode rendering. Text changes still invalidate highlights while the replacement search is pending. Room remains at schema 2 and backup manifests at version 3.

- JVM unit tests: **56 passed**.
- Full UI run: **33 of 34 passed** initially. The only failure was the new wrapped-match test comparing text-layout coordinates with accessibility bounds that include padding. Corrected the test's coordinate conversion; production scrolling needed no additional change.
- Focused final UI runs: **5 passed in compact light mode and 5 passed in compact dark mode**, including the corrected checklist test, tag assignment/history, body text/formatting history, title history, and trimmed-title/tap-to-edit behavior. All 34 distinct UI cases therefore passed across the full run and corrected focused reruns; the complete suite was not rerun after the assertion correction.
- Compact layout: **360×640 dp** (900×1600 px, density 400). Inspected light/dark screenshots for the complete first checkbox, the deep wrapped match, highlights, Find controls, and scrollbar visibility. Focus and selection restoration are asserted by the UI tests.
- Debug APK and instrumentation APK: **built successfully**. Debug lint: **0 errors, 7 existing advisory warnings**. `git diff --check`: **passed**.
- Storage code was unchanged in this follow-up; the **24 passing storage tests** from the preceding review below were not rerun.

Tests used synthetic vaults on a disposable, headless, read-only Pixel 8 emulator (Android 17 / API 37, emulator-5580) with a test PIN. Artifacts are under `%TEMP%\notes-find-followup-build`:

- `app/outputs/apk/debug/app-debug.apk` and `app/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- `app/reports/tests/testDebugUnitTest` and `app/reports/lint-results-debug.html`.
- `app/reports/androidTests/connected/debug` and `app/outputs/androidTest-results/connected/debug`: the full run, including the subsequently corrected assertion failure.
- `compact-light-tests.txt` and `compact-dark-tests.txt`: passing final focused runs.
- `screenshots-light` and `screenshots-dark`: inspected compact layouts.

The signed `dist` release was not replaced. Remaining physical-device checks are in [the acceptance checklist](acceptance.md).

## Preceding Find interaction and tag review: source 1.0.2 (3)

Implemented the follow-up review: latest-action cursor/focus restoration, immediate Close during pending search, one-tap Done, explicit Find scrolling, pending generations, independent prepared-text caches, multiline grouping, trimmed-title offsets, full checklist-row scrolling, conflict-safe tag insertion/update, shared validated tag IDs, a read-only lock-aware catalog projection, centralized deletion reconciliation, and one ordering operation. Room stays at schema 2 and backup manifests at version 3.

- JVM unit tests: **56 passed**, including eight deterministic Find-session tests for pending state, superseded/cancelled work, immediate Close, independent cache invalidation/reuse, cancelled scroll requests, multiline grouping, and title offset clipping. Existing accent, emoji, overlap, document, backup, and history tests also pass.
- Full storage suite: **24 passed**. Direct DAO conflicts leave existing rows intact; failed tag operations do not publish change notifications. Stale snapshots cannot restore deleted assignments. Full/subset ordering, hidden slots, backup round-trips, migration, and lock-time saving pass.
- Full UI suite after fixes: **30 passed**. Covers body/title selection and typing before Close, no-match range preservation, Close during debounce, later-input/reopen/note-switch/lock guards, Done, staged Back, failed-save editability, formatting/history, deep wrapped matches, title whitespace, tap-to-edit scrolling, checklist visibility, catalog reconciliation, immediate assignment, and lock clearing.
- Compact **360×640 dp** checks (900×1600 px, density 400): **6 light-theme tests and 10 dark-theme tests passed**. The checklist fixture was then strengthened with trailing paragraphs so the result can align at the viewport top; that regression passed again in both themes. Light/dark screenshots were inspected for visible controls, active/ordinary highlights, caret placement, full checkboxes, and vertical/horizontal scrollbars. Accessible control labels are exercised by UI selectors.
- Debug APK and instrumentation APK: **built successfully**. Debug lint: **0 errors, 7 existing advisory warnings**. `git diff --check`: **passed**.

Verification used synthetic vaults on a disposable, headless, read-only Pixel 8 emulator (Android 17 / API 37, emulator-5580) with a test PIN. The first full run exposed Compose collapsing a title range during focus transfer; selection-only blur callbacks are now distinguished from user input, and the full UI rerun passes. A tag-management test also needed to wait for the asynchronous rename dialog to dismiss. The storage suite passed in the initial combined run; subsequent production changes affected Find/editor code only. The final strengthened checklist fixture was compiled/linted and verified separately in both themes.

Artifacts are under `%TEMP%\notes-find-review-build`:

- `app/outputs/apk/debug/app-debug.apk` and `app/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- `app/reports/tests/testDebugUnitTest`, `app/reports/lint-results-debug.html`.
- `initial-suite-report` / `initial-suite-results`: all 24 passing storage tests and the two subsequently fixed UI failures.
- `final-ui-report` / `final-ui-results`: all 30 passing UI tests.
- `compact-light-tests.txt`, `compact-dark-tests.txt`, `checklist-light-tests.txt`, `checklist-dark-tests.txt`.
- `screenshots-light` and `screenshots-dark`: reviewed compact layouts.

Physical TalkBack, large fonts, and external-provider/device acceptance remain in [the acceptance checklist](acceptance.md). The signed `dist` release was not replaced.

## Initial Find and tags implementation: 1.0.2 (3)

Implemented Find in reading/editing, reusable tags and one active filter, scoped global search, and filtered drag/accessibility ordering. Tags use the additive Room 1→2 migration inside SQLCipher and backup manifest 3; the encrypted container remains version 1. Supported tagless manifests restore untagged. Historical block-document conversion is outside this change.

- JVM unit tests: **48 passed**, including literal/overlapping matches, phrases and newlines, Unicode offsets, navigation, tag validation, subset order, and invalid backup references.
- Storage instrumentation: **23 passed**, including encrypted schema upgrade, tag persistence/reopen, pending-edit flush before deletion, hidden-slot preservation, unused tag backup/restore, and metadata change notifications/fingerprints.
- Full UI suite: **23 passed**, including Find focus, title/body cursor restoration, Back behavior, formatting and undo/redo, deep wrapped titles/paragraphs, ordinary edits without Find-driven scrolling, tag sheets, conflicts/deletion, inherited empty drafts, scoped search, filtered drag/accessibility moves, and lock reset.
- Compact dark-mode pass: **5 passed** at 360×640 dp (900×1600 px, density 400), covering the Find and tag flows. Light and dark screenshots were inspected for controls, distinct highlights, the compact header, and vertical/horizontal scrollbars.
- Debug APK and test APK assembly: **passed**. Debug lint: **0 errors, 7 existing advisory warnings**. `git diff --check`: **passed**.

Instrumentation used synthetic vaults on a disposable read-only Pixel 8 AVD, Android 17 / API 37, with a test PIN; no physical phone was used. An initial tag-picker test had an ambiguous selector and was corrected. The new native-caret regression also exposed the generic test activity's default window panning; its fixture now uses the same `adjustResize` mode as production. All final tests above passed. A final guard preventing late tag operations from repopulating locked UI state was rebuilt/linted and covered by the compact tag/lock pass.

Artifacts are under `%TEMP%\notes-find-tags-build`:

- `app/outputs/apk/debug/app-debug.apk` — current debug build; the signed `dist` release was not replaced.
- `app/reports/tests/testDebugUnitTest` and `app/reports/androidTests/connected/debug` — passing unit and full UI reports.
- `full-suite-results` / `full-suite-report` — the initial combined run, containing all 23 passing storage tests and the subsequently corrected UI selector failure.
- `app/reports/lint-results-debug.html`, `focused-find-tests.txt`, and `compact-dark-tests.txt`.
- `screenshots-light`, `screenshots-focus-fixed`, and `screenshots-compact-dark-final` — reviewed screenshots.

Physical TalkBack, large fonts, external file-provider automatic backup uploads, and the remaining device checks stay in [the acceptance checklist](acceptance.md). The metadata notification and fingerprint tests do not establish a provider's upload behavior.

## Previous verification: 3 October 2026, source 1.0.2 (3)

The current source includes the single-body editor, recovery passphrase, automatic backups, manual note ordering, compact notes header, and five-minute authentication grace period. This source verification uses fresh synthetic vaults on a disposable Pixel 8 emulator, Android 17 / API 37, with a test PIN.

- JVM unit tests: 39 passed, including rapid undo/typing, redo/typing, typing groups, and history reset when switching notes.
- UI instrumentation: 18 passed, including locking as passphrase setup completes, drag persistence, accessibility reordering, edge scrolling, cancelled drags, and repeated/expired unlock grace periods.
- Storage instrumentation: 20 passed, including saved note order, lock-time saving, encrypted recovery, backup/restore, passphrase recovery, attachment access, and search indexing.
- Debug lint: 0 errors, 7 advisory warnings. `git diff --check` passes.

The 38 instrumentation tests passed across separate UI and storage runs. Reports are under `%TEMP%\secure-notes-review-latest`: `app/reports/tests/testDebugUnitTest`, `ui-test-report`, `ui-test-results`, `app/reports/androidTests/connected/debug` (storage), and `app/reports/lint-results-debug.html`. The archived UI report also records the initial storage-class declaration failure; after explicitly declaring that test's `Unit` return type, all 20 storage tests passed in the subsequent run.

The signed APK below has not been rebuilt for these source changes; its checksum still identifies the archived 1.0.1 artifact.

After the display name and in-app text were changed to **Notes**, debug assembly, instrumentation-test compilation, and debug lint passed again. APK metadata confirms `application-label:'Notes'`. The unit and instrumentation suites above ran before this text-only rename and were not rerun for it.

After adding scrollbars to overflowing lists, notes, settings, recovery screens, and the formatting toolbar, debug assembly and lint passed (0 errors, 7 warnings), and all 18 UI tests passed again on the disposable Pixel 8 emulator. That UI run includes the renamed app. The transcript is `%TEMP%\notes-scrollbars-build\scrollbar-ui-tests.txt`; APKs and the lint report are under that build directory's `app/outputs/apk` and `app/reports`. These drawing/layout changes did not require rerunning the unchanged unit or storage suites. Physical checks of scrollbar appearance in both themes and at large font sizes remain in the acceptance checklist.

## Archived signed build: 1.0.1 (2)

The earlier deliverable is `dist/SecureNotes-1.0.1.apk`, version 1.0.1 (2), minimum Android 15 / API 35, targeting API 37. It contains ARM64 and x86_64 native libraries. Its in-place upgrade from 1.0.0 retained notes. That build's database schema and backup format remain version 1.

## Completed checks

| Check | Result |
| --- | --- |
| JVM unit tests | 17 passed, 0 failed |
| Instrumented storage and UI tests | 26 passed, 0 failed, 0 skipped (15 storage, 11 UI) |
| Debug and release Android lint | Passed, no errors; advisory dependency/style warnings remain |
| Minified, resource-shrunk release build | Passed |
| APK signature | Verified with the dedicated personal release certificate |
| Final manifest | No Internet, camera, or broad storage permission; not debuggable; automatic backup disabled |
| Signed in-place upgrade | 1.0.0 to 1.0.1 retained an existing synthetic note and reopened its vault |

Instrumentation ran on the Pixel 8 AVD, Android 17 / API 37, x86_64 with 16 KB pages. Tests use synthetic fixtures; the storage/UI tests do not bypass authentication in production code. Existing coverage includes encrypted persistence, segmented attachment reads, revoked access, interrupted imports/indexing, PDF/plain-text extraction, search boundaries, corrupt/wrong-password backups, staged restore, autosave, system Back from the title keyboard, share destination choice, checklists, and attachment rearrangement.

The review regression tests additionally cover:

- Conflation, 300 ms debounce, the continuous-typing scheduling bound, and edits arriving during a save.
- Lock with 1,000 accepted snapshots and revoked ordinary access; encrypted recovery and replay after a forced final database-write failure.
- Exact, prefix, and fuzzy search beyond 10,000 postings, including matching postings in multiple notes.
- Camera destination/result recovery with idempotent import, EXIF rotation, and released descriptor removal.
- Repeated/concurrent unlock and lock during vault opening.
- Deep search-result scrolling and character highlighting, attachment reordering, and shared text replacing the empty draft body. (Recorded for the earlier block editor; re-run for the single-editor body.)
- Backup header preflight before password entry, destructive restore confirmation/cancellation, and CreateDocument launch/cancellation cleanup. The picker test uses a test activity-result registry; it does not establish compatibility with every external provider.

Separately, the signed releases were exercised against the **real Android system credential prompt and Keystore**, on a disposable read-only emulator with a test PIN. A note created in signed 1.0.0 remained available after installing signed 1.0.1 in place and authenticating. In 1.0.1, a title edit immediately followed by screen-off survived device unlock and a fresh app authentication. The same note survived force-stop, cold launch, and reauthentication. Android's input-method state confirmed the no-personalized-learning flag on both title and search fields. The dark notes list was also visually checked; physical-device launch transitions remain part of acceptance.

An earlier emulator attempt was interrupted by another deployment process. After that session was paused, the complete final suite above passed. Test PIN/data and theme changes were confined to a disposable read-only emulator.

## Build identity

The APK was rebuilt with the updated launcher icon. Release build, release lint, signature, and manifest checks passed again. The unit, instrumentation, and device smoke-test results above were recorded before this icon-only rebuild; those tests were not rerun.

APK SHA-256:

```text
18E3BDAD69F0F748A42B2F5D153A94D53F87E6EF4BD5C013502E72E0DC7DB247
```

Signing certificate SHA-256:

```text
323293c592952cf7a0953dd3c283484bfec379dd37eb9f6cec765fac19607252
```

Signing material is outside the repository in `%USERPROFILE%\.android\secure-notes-signing`. Updates must reuse it. The password is protected by Windows DPAPI; back up signing material separately using an appropriate secure process.

## Reproduce

Use the commands in [README](../README.md) and `scripts/build-release.ps1`. This run used `-Pnotes.buildRoot=$env:TEMP\secure-notes-build` to avoid Dropbox file locks. Reports are beneath that directory:

- `app/reports/tests/testDebugUnitTest/`
- `app/reports/androidTests/connected/debug/`
- `app/reports/lint-results-debug.html`
- `app/reports/lint-results-release.html`

## Remaining device acceptance

No physical Pixel 8 was connected. Fingerprint/face sensors, camera and external-viewer compatibility, file-provider variations, TalkBack, large fonts, low-storage interruption behavior, and performance targets still require the [physical acceptance checklist](acceptance.md). The emulator run is not an independent security audit or proof of resistance to every power-loss/OS failure.
