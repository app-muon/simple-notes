# Verification — 4 October 2026

## Find checklist and cursor-priority follow-up: current source 1.0.2 (3)

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
