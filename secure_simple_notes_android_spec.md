# Secure Simple Notes --- Android Implementation Specification

**Document purpose:** Implementation specification for a coding agent\
**Platform:** Android\
**Product type:** Offline, privacy-focused personal notes application\
**Status:** Build specification\
**Primary design goals:** Simple, secure, local-first, predictable,
minimal UI

------------------------------------------------------------------------

## 1. Product Summary

Build a native Android notes application for everyday notes and
personal/private information.

The application must be fully usable offline and must not request the
Android Internet permission. Notes and attachments remain local to the
device. The only supported backup mechanism is a user-initiated
encrypted backup file that can be saved through Android's system file
picker.

The application should favor a small, understandable feature set over
feature breadth. Do not add cloud sync, accounts, analytics, reminders,
collaboration, AI features, or other functionality not specified here.

The app supports rich notes containing formatted text, checklists,
images, and file attachments. It provides fast local search across note
titles, note text, attachment filenames, and extracted text from
supported document attachments.

------------------------------------------------------------------------

## 2. Core Product Principles

1.  **Offline by design**
    -   Do not request `android.permission.INTERNET`.
    -   No network calls.
    -   No cloud synchronization.
    -   No telemetry, analytics, remote crash reporting, advertising, or
        tracking.
2.  **Local privacy**
    -   Notes, metadata, attachments, search indexes, and extracted
        document text stay in app-private local storage.
    -   Do not expose notes to Android system-wide search.
    -   Disable Android automatic app-data backup.
3.  **Simple UI**
    -   Main screen is a single list of notes.
    -   No folders.
    -   No tags.
    -   No pinned notes.
    -   No bulk selection.
    -   Avoid unnecessary buttons and persistent toolbars.
4.  **Safe persistence**
    -   Notes auto-save continuously.
    -   There is no Save button.
    -   Empty notes are automatically discarded.
    -   Deletion is permanent but requires confirmation.
5.  **User-controlled backup**
    -   Backup is manual.
    -   One encrypted backup file contains all restorable app data.
    -   Restore replaces current app data rather than merging it.

------------------------------------------------------------------------

## 3. Recommended Android Technology Baseline

Unless the project has an existing architecture that requires otherwise,
use:

-   **Language:** Kotlin
-   **UI:** Jetpack Compose + Material 3
-   **Architecture:** MVVM or similarly clear unidirectional state
    architecture
-   **Async work:** Kotlin Coroutines + Flow
-   **Database:** Room
-   **Preferences/settings:** DataStore
-   **Authentication:** AndroidX Biometric / Android system
    authentication APIs
-   **File sharing/opening:** `FileProvider` and Android content URI
    permissions
-   **File import/export:** Storage Access Framework
-   **Background indexing:** WorkManager only where useful for local
    attachment text extraction/index maintenance
-   **Dependency injection:** Hilt is acceptable but optional; do not
    introduce unnecessary architectural complexity.

Use current stable Android libraries available at implementation time.

### Minimum Android Version

Choose a reasonable supported minimum SDK based on current Android
ecosystem/library support. Prefer modern platform security APIs rather
than maintaining compatibility with obsolete Android versions.

Document the final `minSdk`, `targetSdk`, and rationale in the
repository README.

------------------------------------------------------------------------

## 4. Main Navigation

Keep navigation shallow.

Primary destinations:

1.  **Notes list**
2.  **Note screen**
3.  **Search**
4.  **Settings**
5.  **Backup / Restore flows**

There is no folder/tag navigation and no Trash screen.

------------------------------------------------------------------------

## 5. Authentication and App Lock

### 5.1 Unlock behavior

After the Android device has been locked, opening or returning to the
app must immediately request authentication before exposing notes.

Preferred authentication:

-   Biometric authentication (fingerprint/face as supported by
    Android/device)
-   Android device credential (PIN/password/pattern) is allowed as
    fallback.

Do **not** implement a separate everyday app PIN or app password.

### 5.2 Lock lifetime

Once successfully authenticated, the application remains unlocked until
the device itself locks.

Do not re-lock merely because:

-   the user switches to another app;
-   the app is backgrounded briefly;
-   a fixed timer expires.

Track device/authentication state using appropriate Android APIs rather
than fragile custom timers.

### 5.3 Launch flow

Expected flow after device lock:

`Launch/Resume app -> immediate system authentication prompt -> successful authentication -> Notes list`

Do not first expose the notes list underneath the authentication prompt
in a way that leaks sensitive information.

### 5.4 Recent Apps

The user explicitly permits normal Android Recent Apps previews.

Do not deliberately blur or hide the app's Recent Apps snapshot.

### 5.5 Screenshots

Screenshots and screen recording are allowed.

Do not set `FLAG_SECURE` globally.

------------------------------------------------------------------------

## 6. Main Notes List

The home screen is a single, uncluttered list.

Each row displays:

-   **Note title only**

Do not display:

-   body preview;
-   last-edited timestamp;
-   attachment count;
-   tags;
-   folders;
-   word count.

If a note has no title, display:

**Untitled**

### 6.1 New note

Provide a clear primary action such as a floating `+` button.

When creating a note:

1.  Open the note editor.
2.  Focus the title field.
3.  Show the keyboard.
4.  Begin auto-saving once meaningful content exists.

If the user leaves while both title and body/attachments are completely
empty, discard the note automatically.

### 6.2 Sorting

The user can choose the list sorting method in Settings.

Support at minimum:

-   Most recently edited
-   Most recently created
-   Alphabetical by title

Persist the selected sort mode.

Choose a sensible default, preferably **Most recently edited**, but
expose it clearly as a setting.

### 6.3 Scroll restoration

If the user:

`Notes list -> opens note -> returns to list`

restore the list to the same scroll position.

### 6.4 No swipe actions

Do not assign destructive or other actions to horizontal swipes on note
rows.

### 6.5 No multi-select

Do not implement bulk note selection or bulk deletion.

### 6.6 No pinning

Do not implement pinned/favorite notes.

------------------------------------------------------------------------

## 7. Note Data Model

A note should conceptually include:

``` text
Note
- id
- title
- content/document structure
- createdAt
- updatedAt
- attachments[]
```

Use stable IDs.

Timestamps are needed internally for sorting even though they are not
displayed on the main list.

The note body must support structured rich content rather than relying
on lossy plain-text serialization.

------------------------------------------------------------------------

## 8. Note Screen: Reading and Editing

### 8.1 Opening an existing note

Existing notes open:

-   at the **top** of the note;
-   in **reading mode**;
-   without automatically showing the keyboard.

Exception: opening a search result may navigate directly to the matching
content as specified in the Search section.

### 8.2 Entering editing mode

Tapping editable text while in reading mode enters editing mode.

In editing mode expose the relevant formatting controls and attachment
`+` action.

### 8.3 Leaving editing mode

Back behavior:

1.  If currently editing, Back exits editing mode and returns to reading
    mode.
2.  Pressing Back again returns to the Notes list.

Auto-save means there is no save confirmation.

Follow Android predictive-back/navigation conventions where applicable.

### 8.4 Auto-save

There is no Save button.

Changes should be persisted continuously with a short debounce where
appropriate to avoid excessive writes.

Requirements:

-   edits must survive normal process/activity lifecycle events;
-   moving between reading/editing states must not lose content;
-   attachments and structured content must be persisted safely;
-   app termination should not commonly result in lost recent edits.

Use transactions/atomic operations where necessary.

### 8.5 Undo/Redo

Provide:

-   Undo
-   Redo

Undo/redo only needs to apply to the current editing session.

Do **not** maintain persistent note version history after the editing
session ends.

------------------------------------------------------------------------

## 9. Rich Note Content

Notes support:

-   Plain text
-   Bold text
-   Headings
-   Bullet lists
-   Numbered lists
-   Checklists
-   Images
-   File attachments

Use a deliberately small formatting set. Do not add an expansive
word-processor feature set unless required for reliable implementation
of the items above.

### 9.1 Attach content

While editing, expose a single **+** action.

Its menu includes:

-   Photo
-   Camera
-   File

Avoid separate always-visible image/file buttons.

### 9.2 Images

Images inserted into a note must:

-   be copied into app-controlled private storage;
-   render inline;
-   automatically fit neatly within available note width;
-   not expose manual resize controls;
-   be reorderable within note content via press/drag or an equivalent
    accessible interaction.

Tapping an image in reading mode opens a full-screen viewer.

Viewer supports:

-   pinch-to-zoom;
-   pan when zoomed;
-   return/back.

Do not provide a dedicated "Save to Gallery" or export action.

### 9.3 File attachments

Files selected by the user must be copied/imported into app-private
storage rather than depending indefinitely on the original URI.

There is **no artificial per-file attachment size limit**. Device
storage and platform constraints are the practical limits.

Still handle low-storage and I/O errors gracefully.

Display attachments as understandable blocks containing at least the
filename and an appropriate generic/file-type indication.

Attachments can be reordered within note content.

### 9.4 Opening files externally

Tapping an attached file opens it in a compatible installed Android
application.

Implementation requirements:

-   use `content://` URIs;
-   use `FileProvider` or an equivalent secure Android mechanism;
-   grant only the minimum temporary URI permission needed;
-   do not expose internal filesystem paths;
-   revoke temporary access as soon as practical;
-   do not deliberately create a permanent external copy.

Note: Android cannot guarantee what a receiving third-party application
does with content once legitimately granted access. The notes app must
minimize the scope and duration of its own grant.

------------------------------------------------------------------------

## 10. Automatic Link Detection

Automatically detect and make tappable:

-   Web URLs
-   Phone numbers
-   Email addresses

Behavior:

-   Web links open directly using the user's default browser/appropriate
    Android handler.
-   Phone numbers and email addresses may use normal Android intent
    handling.
-   Do not add an intermediate confirmation dialog unless Android itself
    requires one.

Dates/times remain ordinary text.

Do not automatically create calendar links/events from dates.

------------------------------------------------------------------------

## 11. Search

Search is local-only.

Search across:

1.  Note titles
2.  Note body text
3.  Attachment filenames
4.  Extracted text inside supported document attachments

### 11.1 Matching behavior

Search must be:

-   Case-insensitive
-   Accent/diacritic-insensitive
-   Fuzzy enough to tolerate small spelling mistakes

Example expectations:

-   `holiday` matches `Holiday`
-   `cafe` matches `café`
-   a small typo such as `reciept` should be capable of matching
    `receipt`

Design fuzzy matching so it remains useful rather than returning
excessive irrelevant results.

### 11.2 Search result presentation

Each result displays:

-   note title;
-   short snippet around the matching text.

For untitled notes, display **Untitled**.

If a result originates from an attachment, make the context
understandable without cluttering the interface.

### 11.3 Opening note-text results

When a result matches text within a note:

1.  Open that note.
2.  Scroll directly to the matching text.
3.  Briefly highlight the match.

This overrides the normal "open note at top" behavior.

### 11.4 Attachment search results

If a match came from text extracted from an attachment:

1.  Open the containing note.
2.  Navigate to the relevant attachment.
3.  Visually highlight the attachment.

Do not automatically open the external attachment viewer.

### 11.5 No separate Find-in-Note

Do not implement a dedicated "Find in current note" feature.

### 11.6 Search indexing privacy

Any search index, normalized terms, extracted text, OCR-like
derivatives, or attachment text caches must remain in app-private
storage.

Do not expose them through Android system search.

### 11.7 Attachment text extraction

Support full-text extraction for reasonable document types where
reliable local parsers are available, particularly PDFs.

Prefer local parsing libraries/APIs.

Because the app has no Internet permission:

-   no cloud OCR;
-   no server-side document processing;
-   no remote indexing.

Do not silently upload documents under any circumstance.

Clearly structure the extraction layer so additional document formats
can be added later.

If a format cannot be text-indexed, index its filename and other
supported metadata rather than failing the attachment itself.

------------------------------------------------------------------------

## 12. Sharing Content Into the App

The app should appear as an Android Share target for supported incoming
content, including:

-   text;
-   URLs;
-   images;
-   files.

When content is shared to the app, do **not** immediately save it
without user input.

Present:

-   **New note**
-   **Add to existing note**

### 12.1 New note

Create/open a new note populated with the shared content.

Allow the user to edit the title/content normally.

### 12.2 Add to existing note

Show:

-   the full notes list;
-   a search field.

The user chooses the destination note.

Append/insert the incoming content in a sensible, predictable manner.

For incoming images/files, import them into app-private storage.

### 12.3 Outbound sharing

Do **not** provide an individual-note Share/Export feature.

Notes should not have an app-provided "Share note" action.

Normal Android clipboard behavior is allowed, so users may still
manually copy text.

------------------------------------------------------------------------

## 13. Clipboard

Allow normal Android copy/paste.

Users can:

-   copy note text;
-   paste text from other apps.

Do not implement a custom isolated clipboard.

Respect modern Android clipboard/privacy behavior.

------------------------------------------------------------------------

## 14. Deletion

Deletion is permanent.

There is:

-   no Trash;
-   no recycle bin;
-   no automatic recovery;
-   no version history.

Before deletion, display a simple confirmation dialog:

**Delete this note?**

Actions:

-   Cancel
-   Delete

Do not require biometric reauthentication just to delete a note.

Do not implement swipe-to-delete.

Do not implement bulk deletion.

------------------------------------------------------------------------

## 15. Backup

Backup is manual and encrypted.

### 15.1 Backup scope

A backup must contain everything necessary to recreate the application's
restorable state, including:

-   all notes;
-   rich note structure/formatting;
-   images;
-   file attachments;
-   searchable attachment data where appropriate or enough source data
    to rebuild indexes;
-   app settings.

Caches that can be deterministically rebuilt do not need to be included
if excluding them improves backup size/reliability.

### 15.2 Backup workflow

Provide a Settings action such as:

**Create encrypted backup**

Flow:

1.  Authenticate if necessary.
2.  Ensure backup protection has been configured.
3.  Build the backup.
4.  Encrypt it.
5.  Launch Android's system document/file creation picker.
6.  Let the user choose where the encrypted file is stored.

The app itself does not need Internet access even if the user's system
file picker offers a cloud-backed storage provider.

### 15.3 Backup password

Backups are protected by a **separate backup password** configured by
the user.

The backup must remain decryptable on a replacement phone without access
to the old phone's biometric keys.

Do not store the user's plaintext backup password.

Use a modern password-based key derivation mechanism and authenticated
encryption. Prefer well-reviewed platform/library cryptographic
primitives and formats.

At implementation time, choose secure current parameters appropriate for
Android hardware and document them. For example, an appropriate
memory-hard KDF such as Argon2id may be used if a mature audited
dependency is selected; otherwise use a well-supported secure
alternative with appropriate parameters.

Use authenticated encryption such as AES-GCM where appropriate.

The encrypted backup format should contain non-secret versioning/KDF
parameters/salt/nonce metadata required for future restoration.

### 15.4 Password warning

When configuring the backup password, clearly warn:

> If you forget this backup password, the encrypted backup cannot be
> recovered.

Do not create an email/account-based recovery mechanism.

### 15.5 Backup format

Define and document a versioned backup container format.

It must support:

-   format versioning;
-   integrity/authentication;
-   future migration;
-   detection of corrupted/wrong-password backups without partially
    restoring data.

Avoid inventing custom cryptographic algorithms.

------------------------------------------------------------------------

## 16. Restore

Provide a Settings action such as:

**Restore encrypted backup**

Flow:

1.  Select backup using Android's file picker.
2.  Validate the container.
3.  Request backup password.
4.  Authenticate/decrypt and validate fully.
5.  Show a clear destructive warning.
6.  User confirms.
7.  Replace current app state with backup contents.
8.  Rebuild any indexes/caches as necessary.
9.  Return to a valid restored state.

### 16.1 Replace, never merge

Restore **replaces all existing notes/settings**.

Do not merge backup data with current data.

### 16.2 No automatic safety backup

Do not automatically create a backup of the current data before restore.

The confirmation must therefore clearly communicate that current app
data will be replaced.

### 16.3 Transactional restore

Restore should be effectively transactional.

Do not erase current data before establishing that:

-   the backup can be decrypted;
-   integrity checks pass;
-   required files are readable;
-   the backup version is supported/migratable.

Prefer staging restored data and atomically switching/replacing state so
a failure does not leave the user with neither old nor restored data.

------------------------------------------------------------------------

## 17. Android Automatic Backup

Disable Android automatic/cloud app backup for application data.

The manual encrypted backup is the **only supported backup mechanism**.

Configure the manifest/data extraction/backup rules appropriately for
supported Android versions.

Verify this behavior in tests/build review.

------------------------------------------------------------------------

## 18. Local Data Security

This application stores personal/private information, so use
defense-in-depth while retaining the specified UX.

Requirements:

-   Store databases/files only in app-private storage.
-   Do not write note contents to logs.
-   Do not log backup passwords or encryption keys.
-   Avoid sensitive plaintext temporary files where possible.
-   Clean up temporary decrypted attachment files immediately when they
    are no longer required.
-   Use Android Keystore for device-bound key material where
    appropriate.
-   Protect locally stored sensitive database/content at rest with a
    sound, maintainable design.

### 18.1 Local encryption architecture

The coding agent should implement a documented local-at-rest encryption
strategy using established Android/cryptographic components rather than
home-grown crypto.

The local encryption key may be device-bound because portability is
provided by the separate password-encrypted backup format.

Ensure authentication/locking behavior and local key access are
compatible with the requirement that the app stays unlocked until the
device locks.

### 18.2 Logs and debugging

Production builds must not leak:

-   note text;
-   titles;
-   attachment filenames where avoidable;
-   extracted document text;
-   backup passwords;
-   encryption keys.

Review exception/error reporting paths even though remote crash
reporting is disabled.

------------------------------------------------------------------------

## 19. Settings

Keep Settings intentionally small.

Include only functionality needed by this specification.

At minimum:

### Appearance

-   Theme follows Android system light/dark mode automatically.
-   No manual theme selector is required.

### Notes

-   Sort order:
    -   Recently edited
    -   Recently created
    -   Alphabetical

### Backup & Restore

-   Configure/change backup password
-   Create encrypted backup
-   Restore encrypted backup

Potentially include an About section with app/version information if
useful.

Do **not** add a storage usage dashboard.

------------------------------------------------------------------------

## 20. Theme

Automatically follow Android's system theme.

Support:

-   light system theme;
-   dark system theme.

Do not require a manual Light/Dark/System selector.

Use Material 3 conventions and accessibility-friendly contrast.

------------------------------------------------------------------------

## 21. Accessibility

Even though the UI is minimal, implement normal Android accessibility
expectations:

-   meaningful content descriptions;
-   appropriate semantics for checklists/buttons;
-   support font scaling;
-   adequate touch targets;
-   TalkBack-compatible controls;
-   do not rely solely on color to indicate state;
-   keyboard/accessibility navigation where Android conventions require
    it.

Drag/reorder functionality should have an accessible alternative if drag
gestures are not usable.

------------------------------------------------------------------------

## 22. Explicit Non-Goals

Do **not** implement the following unless this specification is later
changed:

-   User accounts
-   Cloud synchronization
-   Internet/network access
-   Ads
-   Analytics
-   Telemetry
-   Remote crash reporting
-   AI features
-   Collaboration
-   Shared notebooks
-   Folders
-   Tags
-   Pinned/favorite notes
-   Reminders
-   Notifications for notes
-   Trash/recycle bin
-   Persistent note version history
-   Individual note export
-   Individual note sharing
-   Drawing
-   Handwriting/stylus canvas
-   Calendar integration
-   Word count
-   Character count
-   Find-in-current-note
-   Note duplication
-   Bulk selection/actions
-   Swipe actions
-   Storage usage dashboard
-   Android system-wide note search
-   Android automatic/cloud app backup
-   Manual image resizing
-   Save-to-gallery from image viewer

Do not add features simply because another notes application commonly
has them.

------------------------------------------------------------------------

## 23. Important UX Flows

### Create note

``` text
Notes list
  -> Tap +
  -> New note opens
  -> Title focused + keyboard visible
  -> User types
  -> Auto-save
  -> Back
  -> Reading mode
  -> Back
  -> Notes list
```

If no title, body content, image, or attachment was added:

``` text
Back
  -> discard empty note
```

### Open existing note

``` text
Notes list
  -> Tap note
  -> Note opens at top in reading mode
  -> Tap content
  -> Editing mode
```

### Delete

``` text
Note menu
  -> Delete
  -> "Delete this note?"
  -> Cancel / Delete
  -> Delete permanently
```

### Search

``` text
Notes list
  -> Search
  -> Enter query
  -> Local fuzzy/accent-insensitive/case-insensitive results
  -> Title + match snippet
  -> Tap result
  -> Open note at match + temporary highlight
```

Attachment text result:

``` text
Search result
  -> Tap
  -> Open containing note
  -> Scroll to attachment
  -> Highlight attachment
```

### Receive Android share

``` text
Other app
  -> Share
  -> Secure Notes
  -> New note / Add to existing note
```

For existing:

``` text
Add to existing
  -> Notes list + search
  -> Choose note
  -> Import/append content
```

### Backup

``` text
Settings
  -> Backup & Restore
  -> Create encrypted backup
  -> Authenticate if needed
  -> Encrypt complete backup
  -> Android file picker
  -> User chooses destination
```

### Restore

``` text
Settings
  -> Backup & Restore
  -> Restore encrypted backup
  -> Choose file
  -> Enter backup password
  -> Validate/decrypt completely
  -> Warn that current data will be replaced
  -> Confirm
  -> Transactional replacement
  -> Rebuild indexes
```

------------------------------------------------------------------------

## 24. Suggested Data/Storage Separation

A reasonable internal separation is:

``` text
Room database
  - note metadata
  - document/block structure
  - attachment metadata
  - search/index metadata
  - timestamps

Private files directory
  - imported images
  - imported attachments
  - derived local indexing artifacts if needed

DataStore
  - non-sensitive UI/settings preferences

Keystore / cryptographic layer
  - device-bound local encryption key material
```

Exact implementation may differ if a more secure/reliable architecture
is selected, but preserve the behavior in this specification.

------------------------------------------------------------------------

## 25. Search Architecture Expectations

Search quality is a significant feature.

The implementation should support normalized indexing:

-   lowercase/case folding;
-   Unicode normalization;
-   diacritic folding;
-   tokenization;
-   attachment filename indexing;
-   extracted attachment text.

Fuzzy matching should use a bounded strategy (for example edit
distance/trigram-based matching or another efficient local technique) so
large note collections remain responsive.

Do not make fuzzy matching so permissive that ordinary queries become
noisy.

Search indexing should update automatically after note edits, attachment
imports/removals, and restore.

If indexing an attachment is expensive, it may occur asynchronously, but
the UI should remain usable and eventually become searchable without
user intervention.

------------------------------------------------------------------------

## 26. Failure and Edge Cases

Handle at minimum:

-   device runs out of storage while importing an attachment;
-   source file disappears during import;
-   external viewer is unavailable for an attachment;
-   camera operation is cancelled;
-   photo/file picker is cancelled;
-   corrupt backup file;
-   incorrect backup password;
-   unsupported future/old backup version;
-   interrupted backup creation;
-   interrupted restore;
-   app/process death during note editing;
-   app/process death during attachment import;
-   app/process death during indexing;
-   biometric authentication cancellation/failure;
-   biometric temporarily unavailable;
-   device credential fallback;
-   duplicate attachment filenames;
-   extremely large attachments;
-   malformed PDFs/documents;
-   search index rebuild after restore;
-   Unicode filenames and note text.

Failures must not silently destroy existing user data.

------------------------------------------------------------------------

## 27. Performance Expectations

The application should feel immediate for ordinary personal use.

Targets:

-   Main list should scroll smoothly.
-   Opening a normal note should be effectively immediate.
-   Auto-save must not visibly block typing.
-   Search should update quickly while typing for normal collections.
-   Large attachment parsing must not run on the UI thread.
-   Backup/restore should expose progress for operations long enough to
    warrant it.
-   Images should be decoded/downsampled appropriately for display to
    avoid memory exhaustion while preserving the original imported file
    where required.

------------------------------------------------------------------------

## 28. Testing Requirements

Implement automated tests for critical behavior.

### Unit tests

Cover:

-   search normalization;
-   case-insensitive matching;
-   diacritic-insensitive matching;
-   fuzzy matching;
-   sort modes;
-   untitled note behavior;
-   empty-note discard logic;
-   backup serialization;
-   backup encryption/decryption;
-   wrong backup password;
-   backup integrity failure;
-   backup format version handling.

### Database/storage tests

Cover:

-   note persistence;
-   attachment metadata;
-   updates and deletion;
-   permanent deletion;
-   restore replacement;
-   migrations.

### UI/instrumentation tests

Cover important flows:

-   authentication gate;
-   create note;
-   auto-save;
-   reading -\> editing transition;
-   Back editing -\> reading -\> list;
-   deletion confirmation;
-   search -\> navigate to match;
-   share-in flow;
-   image attachment;
-   file attachment;
-   backup picker flow where testable;
-   restore warning/replacement.

### Security-oriented verification

Verify:

-   no `INTERNET` permission;
-   Android automatic backup disabled;
-   no note data exposed through system search;
-   private files are not world-readable;
-   exported/opened attachments use content URIs and temporary
    permissions;
-   release logging does not expose note content/secrets;
-   backups cannot be read without the backup password;
-   tampered backups fail authentication rather than partially
    restoring.

------------------------------------------------------------------------

## 29. Definition of Done

The initial release is complete when all of the following are true:

-   App works entirely without Internet permission.
-   Device authentication gates access after the device has been locked.
-   Biometrics and device credential fallback behave correctly.
-   User can create, edit, read, and permanently delete notes.
-   Notes auto-save reliably.
-   Rich text/checklists work.
-   Images and files can be imported and reordered.
-   Images have a full-screen zoom viewer.
-   Files can be securely opened in compatible external apps.
-   Main list contains only note titles and supports configurable
    sorting.
-   Scroll position is restored when returning to the list.
-   Search covers title/body/filename/supported attachment text.
-   Search is case-insensitive, accent-insensitive, and typo-tolerant.
-   Search results include snippets and navigate/highlight correctly.
-   Android Share can create a new note or add content to an existing
    note.
-   No outbound individual-note share/export feature exists.
-   Manual password-encrypted full backup works.
-   Restore validates first and then replaces all current data
    transactionally.
-   Android automatic backup is disabled.
-   No analytics, telemetry, ads, cloud sync, or remote crash reporting
    exists.
-   App follows Android system light/dark mode.
-   Explicit non-goals have not been added inadvertently.
-   Critical storage, backup, restore, authentication, and search tests
    pass.

------------------------------------------------------------------------

## 30. Coding-Agent Instructions

Treat this document as the product source of truth.

When implementation details are unspecified:

1.  Choose the simplest solution consistent with the requirements.
2.  Prefer official Android/Jetpack APIs and mature security libraries.
3.  Do not weaken privacy/security for convenience.
4.  Do not invent additional product features.
5.  Follow normal modern Android UX conventions when they do not
    conflict with explicit requirements.
6.  Keep architecture maintainable but avoid overengineering.
7.  Document important security decisions, backup format, database
    migrations, and non-obvious implementation choices.
8.  If a requirement is technically impossible to guarantee because of
    Android/third-party-app behavior, implement the strongest practical
    behavior and document the limitation rather than pretending it can
    be guaranteed.
9.  Before release, perform a permission review and verify the final
    manifest contains no unnecessary permissions.

------------------------------------------------------------------------

## 31. Product Decisions Captured

This specification incorporates the chosen behavior:

-   Everyday + private/personal notes
-   Local storage
-   Manual encrypted backups
-   Biometric authentication
-   Device credential fallback
-   Remain unlocked until phone locks
-   Normal Recent Apps previews
-   Screenshots allowed
-   Rich formatting + checklists + images + files
-   Single notes list
-   No folders/tags/pinning
-   Configurable sorting
-   Permanent deletion
-   No reminders
-   Continuous auto-save
-   Separate optional title
-   `Untitled` fallback
-   Title-only list rows
-   Single `+` attachment menu
-   Full backup file
-   Separate reusable backup password
-   Follow system light/dark theme
-   Search title/body/filename/attachment text
-   No outbound note sharing/export
-   Android share-in supported
-   New-note vs existing-note choice for share-in
-   Full list + search when selecting existing destination
-   Undo + redo
-   No persistent version history
-   Immediate authentication after device lock
-   New note starts focused on title
-   No word/character count
-   Auto-detected clickable URLs/phone/email
-   URLs open through normal default handler
-   No handwriting/drawing
-   Reorder images/attachments
-   Images auto-fit; no manual resize
-   Empty notes auto-discard
-   No bulk actions
-   Confirmation before permanent delete
-   No duplication
-   No analytics/crash reporting
-   No Internet permission
-   Backup includes full restorable state
-   Restore replaces rather than merges
-   No automatic pre-restore safety backup
-   Android automatic backup disabled
-   No system-wide note search
-   Normal copy/paste
-   Dates remain plain text
-   Full-screen zoomable image viewer
-   Attachments open externally
-   Temporary attachment URI access
-   No artificial attachment size limit
-   No storage usage display
-   No swipe actions
-   Restore notes-list scroll position
-   Existing notes normally open at top
-   Existing notes initially open in reading mode
-   Editing controls hidden during reading
-   Back exits editing before leaving note
-   No Find-in-note
-   Search result snippets
-   Search navigates to/highlights matching text
-   Attachment search highlights containing attachment
-   Fuzzy search
-   Accent-insensitive search
-   Case-insensitive search
