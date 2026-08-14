# Project rules for CallReport Mobile

## Repository layout

This repository is `skotarov/callreport-mobile`.

The Android project is here:

- `mobile/calllog-android/`

Do not look for a root `/app` project first.

Important files:

- Android build config: `mobile/calllog-android/app/build.gradle.kts`
- APK workflow: `.github/workflows/android-build.yml`
- APK artifact name: `callreport-debug-apk`
- APK output path: `mobile/calllog-android/app/build/outputs/apk/debug/callreport-debug.apk`

## APK/update rules

Known values:

- Android package: `com.onlineimoti.calllog`
- `versionCode` is based on `GITHUB_RUN_NUMBER`
- APK update over an existing install requires the same package name, higher version code, and the same signing key
- The debug APK workflow uses a cached debug keystore with cache key `callreport-debug-keystore-v1`

## Work style

When the user says “направи го” for this repo:

- Change `main` directly unless the user explicitly asks for a PR or branch.
- For APK/build/install questions, inspect only:
  - `mobile/calllog-android/app/build.gradle.kts`
  - `.github/workflows/android-build.yml`
- After writing a file, verify the saved file once.

## Edit-size rule

Do not rewrite large or medium Kotlin files wholesale just to make a small behavioral change.

Use this order of preference:

1. Make the smallest targeted edit in the existing file.
2. If the change touches repeated logic, add a small helper file/class and call it from the existing files.
3. Only rewrite a whole file when the file is genuinely small or the user explicitly asks for a full refactor.

For files such as `ContactNoteReader.kt`, prefer adding a helper such as `NotePersistence.kt` instead of replacing the entire file.

## Clients architecture and sync contract

The `Clients` screen is a server-backed Relationship Manager view. Treat the rules below as product behavior, not as an implementation detail.

### Server is authoritative for clients

- A client exists because it exists on the Relationship Manager server.
- Company clients are server-side only.
- The Android phone call log and Android Contacts are not authoritative client databases and must not create a separate Clients universe.
- Local Android data for Clients is a cache used for fast rendering, offline tolerance and reconciliation.
- If Android has a personal CRM marker for a phone but the corresponding client is missing from the server-side Clients data, treat that as a synchronization problem to repair, not as a valid local-only client.
- Client storage, archival and authoritative search live on the server.

### Cache-first, server-reconciled rendering

- Opening Clients should render the matching local cache immediately when available.
- Then request the authoritative page from the server.
- Reconcile by stable client/phone identity and update only rows/fields whose authoritative state changed; avoid replacing the whole visible list when that would cause unnecessary jumping or flicker.
- Pagination exists for performance. Do not solve loading problems by downloading the entire server client database into every normal page request.
- A fresh install or empty cache must be able to hydrate the local Clients cache from the server.

### Search belongs to the server

- Clients search is server-side and must search the authoritative server client data, including server notes/data that may not be present in the current local page cache.
- For responsiveness, the app may show an immediate provisional result from its local cache.
- When the server result arrives, reconcile it into the visible result and update only the differences where practical.
- Search must always be executed inside the currently selected filters, not on an unrelated broad result followed by an inconsistent client-side filter.

### Filter semantics

Empty/unselected filter means no restriction on that dimension:

- CRM filter OFF: show all server clients visible to the signed-in user, whether or not they are personally marked CRM.
- CRM filter ON: show only clients for which the current signed-in user has an active personal CRM/care marker.
- Company filter empty: include personal server records plus all accessible company records.
- Company selected: restrict to the selected accessible company/companies.
- Phase filter empty: include every phase plus records with no phase.
- Phase selected: filter by the current signed-in user's own phase state only.
- Colleagues' CRM/care state and phases may be displayed as useful status, but they must not decide the current user's personal CRM or phase filter results.

### No-company filter compatibility rule

This is a dedicated compatibility rule for Clients requests and must not be generalized to company-filtered requests:

- If the company filter is empty and the CRM filter is OFF, the request is the neutral "all visible clients" scope. Do **not** send `crm_only=0`; omit the `crm_only` parameter entirely.
- If the company filter is empty and the CRM filter is ON, send `crm_only=1`.
- If one or more companies are selected, preserve the current explicit company-filter behavior: send the selected `company_id` value(s) and keep `crm_only=0/1` explicit according to the CRM filter state.
- Do not "simplify" these branches into one universal `crm_only=0/1` rule. Mixed/legacy server deployments may interpret the mere presence of `crm_only` as an enabled CRM restriction even when the value is `0`.
- Fixes for the empty-company case must not change company-filtered pagination, server search, phase filtering, cache semantics, or result ordering unless the user explicitly asks for those changes.
- Treat working company-filter behavior as higher-risk regression territory: when changing the no-company case, isolate the change and add/keep regression coverage proving that selected-company requests remain unchanged.

### CRM / care markers are per user

- CRM means: "this user is actively taking care of this client". It does not mean "this record is a client".
- Multiple colleagues may independently have CRM/care active for the same client at the same time.
- Show when the same client is also active/CRM for other colleagues when that information is available.
- Each user's CRM state is independent and contains its own active/inactive value and change timestamp.
- Marking or unmarking CRM by one user must never overwrite another user's CRM state.
- On reinstall/login, the signed-in user's CRM state must be restored from the server into the local cache.

### Phases are per user

- Each user maintains their own phase/progress for the same client.
- Different colleagues can legitimately have different phases for the same client because they may be managing different workflows.
- Example: one broker can track the purchase workflow while another colleague tracks mortgage/credit progress.
- The UI may show colleagues' phase/progress as additional information.
- The phase filter must use only the current signed-in user's phase.
- A colleague changing their phase must not overwrite the current user's phase.

### Notes and ownership

- Notes are server-synchronized records with their own author identity and timestamps.
- A user's own note is editable by that user.
- A colleague's note should show the colleague's name/identity and is read-only for the current user unless an explicit permission model says otherwise.
- Notes from different authors are independent records. Never merge two authors' notes into one mutable last-write-wins field.

### Per-field/per-object timestamps and conflict resolution

- Do not use one timestamp for the whole client as a replacement for independent state timestamps.
- Every independently mutable piece of information must carry its own modification timestamp/version where needed for synchronization.
- At minimum, independent timestamps/versioning are required for personal CRM state, each user's phase state, each note, and other independently editable client fields.
- Reconciliation compares the same logical field/object on both sides. Newer state wins for that field/object only.
- If the server copy is newer, update the local cache for that field/object.
- If the local copy is newer and the current user is allowed to edit that field/object, sync it to the server.
- A newer change to one field (for example a colleague's note) must not overwrite an unrelated field (for example the current user's CRM marker or phase).
- Conflict resolution must preserve user/author ownership boundaries.

### Multi-user visibility

- Server-side changes made by colleagues should flow into the local cache and become visible after reconciliation.
- Display colleague identity for colleague-owned notes/status where available.
- Visibility does not imply edit permission: colleague-owned information can be visible while remaining read-only.

## Android tasks belong in this repo

Implement Android/mobile work here: UI, Settings, recent calls, pagination, permissions, app icon, theme, build version display, call detection, popups, local call log, WebView opening of remote pages, and Android-side settings.

## Server-side tasks do not belong in this repo

Do not implement PHP server-side logic directly in this Android repo unless explicitly requested.

For server-side work, prepare a Codex prompt for the `onlineimoti.com` project under `/broker/callreport/` instead of changing this Android repo.
