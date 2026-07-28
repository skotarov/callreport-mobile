# Relationship Manager Mobile — agent rules

These rules apply to the repository `skotarov/relationship-manager-mobile`.

The current product name is **Relationship Manager**. `CallReport`, `callreport`, and
`com.onlineimoti.calllog` may still appear as legacy internal names. Do not rename
those identifiers casually; treat a rename as a separate migration task.

## Repository layout

The Android project is located at:

- `mobile/calllog-android/`

Do not assume there is a root-level `/app` project.

Important files and values:

- Android build config: `mobile/calllog-android/app/build.gradle.kts`
- Android CI workflow: `.github/workflows/android-build.yml`
- Android namespace: `com.onlineimoti.calllog`
- Android application ID: `com.onlineimoti.relationshipmanager`
- APK artifact: `relationship-manager-apk`
- APK output: `mobile/calllog-android/app/build/outputs/apk/debug/relationship-manager.apk`

## Always start from current GitHub state

Before every task, even when the repository was inspected moments earlier:

1. Fetch the latest commit of `main` from GitHub.
2. Inspect the currently open pull requests and identify whether one is directly
   relevant to the requested change.
3. Continue an existing PR only when it is genuinely the active branch for the
   same work and contains the latest intended implementation.
4. Otherwise create a small task-specific branch from the exact latest `main`.
5. Fetch every affected file from that chosen branch immediately before editing.

Never rely on memory, an earlier chat summary, an old local checkout, a previous
file response, or a stale PR branch as the source of truth.

After writing:

- fetch the changed file again and verify the saved content;
- fetch the PR again and confirm its exact head SHA and state;
- report checks only for that exact head SHA;
- never merge a PR unless the user explicitly asks for a merge.

## Work style and change safety

- Prefer one focused bug or feature per PR.
- Do not append unrelated work to an old or stale PR merely because it is open.
- Make the smallest targeted change that preserves existing behavior.
- Do not rewrite medium or large Kotlin files for a small behavioral fix.
- When logic is repeated or needs isolated testing, add a small helper class/file
  and call it from the existing code.
- Avoid weakening or deleting existing regression tests.
- Preserve stored preferences, event IDs, endpoint defaults, authentication
  headers, serialized formats, and migration behavior unless the task explicitly
  requires a compatible migration.
- Ask before changing behavior when the requirement has two materially different
  interpretations that cannot be resolved from the current code and tests.

## Android work belongs in this repository

Implement mobile behavior here, including:

- Home, History, Clients, and Settings UI;
- local Android call-log loading and pagination;
- call start/end detection and popup behavior;
- permissions, notifications, floating controls, and fallback flows;
- Android-side caching, reconciliation, deduplication, and background loading;
- WebView opening and parameter passing;
- configurable Base URL, token, and endpoint paths;
- Android build, version display, icon, theme, and APK behavior.

## Server-side work does not belong in this repository

Do not add or modify PHP server logic in this Android repository.

Server-side files such as `lookup.php`, `form.php`, `history.php`,
`property_search.php`, and `notes_lookup.php` belong to the server-side
`onlineimoti.com` project under `/broker/callreport/`.

For a server-only task, provide a Codex prompt beginning with:

> Работим по server-side проекта onlineimoti.com, папка /broker/callreport/…

The prompt must specify:

- the file or files to create or modify;
- accepted input parameters;
- required JSON or HTML output;
- `access_token` validation;
- phone normalization;
- backward compatibility requirements;
- preservation of existing `lookup.php`, `form.php`, and `history.php` behavior;
- use of the existing server project structure.

For mixed tasks, implement only the Android part here and provide a separate
server-side prompt for the PHP part.

## Note-scope invariants

The note editor has independent storage scopes:

- Local;
- each company separately;
- server records may also be separated by author/profile.

These scopes are independent records, not alternative labels for one shared text.
The following rules must always be preserved:

1. Before switching from Local to a company, from one company to another, or back
   to Local, persist the currently visible scope first.
2. A blank value is a real deletion. Persist that deletion before loading the next
   scope.
3. Apply the next scope only after the current save succeeds. On failure, keep the
   current scope and text visible.
4. Initial spinner binding and selecting the already active scope must not create
   an unnecessary write.
5. Never automatically copy, move, merge, or restore note text between Local and
   company scopes.
6. Never infer duplicates from equal text alone. Distinct scopes or authors may
   legitimately contain identical text.
7. Deduplicate only with stable identity: scope, company, author/profile,
   client/server event ID, call identity, and version timestamp as applicable.
8. A colleague's company note may be displayed, but must not prefill the current
   user's editable company-note field unless the server marks it editable for the
   current profile.
9. Saving a company note must not automatically mark an unknown phone as CRM.
10. Local notes, company notes, and the personal Active/CRM marker are separate
    concepts. Changing one must not silently change another.

## Deletion and cache rules

A successful deletion must be authoritative across all visible and persisted
layers relevant to that note:

- local storage;
- pending/outbox state;
- Home note snapshots;
- History snapshots;
- in-memory UI models;
- server cache/index state when applicable.

Do not treat an absent row as “no update” when the fresh source is authoritative
and the previous value existed. Use an explicit tombstone or invalidation where
needed so stale notes cannot reappear after refresh, navigation, restart, or a
new prompt-driven change.

Temporary network failure may preserve the last known good server data, but it
must not resurrect a locally confirmed deletion or overwrite a newer local
pending change.

## Home and History loading behavior

Preserve progressive loading:

- render available local calls and cached data immediately;
- enrich notes and server information afterward;
- patch only changed rows where practical;
- do not clear a valid visible list merely to show loading;
- use generation/request guards so stale asynchronous responses cannot overwrite
  newer state;
- keep page, offset, and stable row identity consistent during refresh.

When fixing duplicates, determine whether the records are genuinely the same
stable event. Do not hide two legitimate Local/company or multi-author notes just
because their phone, time, or text looks similar.

## Regression tests are required for bug fixes

Every reproducible bug fix should add or update a focused regression test that
fails before the fix and passes afterward.

For note-scope bugs, cover the relevant cases where practical:

- Local deletion before switching to a company;
- save failure keeps the original scope visible;
- initial binding does not write;
- selecting the same scope does not write again;
- deletion does not return from cache or a stale asynchronous response;
- company and Local notes remain independent;
- colleague notes remain non-editable for the current profile.

Do not replace a behavioral test with a weaker implementation-detail assertion.

## Validation

For Android code or resource changes, run or confirm the applicable checks for the
exact PR head:

- Android unit tests;
- Kotlin compilation / Build Diagnostics;
- Android lint;
- Android CI and APK build.

For documentation-only changes, verify the saved file and PR diff; an Android
build is not required unless the workflow is triggered by the changed path.

Do not claim a check succeeded while it is pending. If an unrelated workflow
fails, inspect it before attributing a cause and distinguish it from the change
being made.

## APK/update compatibility

Updating over an installed application requires:

- the same application ID;
- a higher version code;
- the same signing key.

The project uses a fixed debug signing key. Do not replace signing material,
application ID, or versioning behavior as part of an unrelated task.
