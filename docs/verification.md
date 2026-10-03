# Verification — 3 October 2026

## Current source: 1.0.2 (3)

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
