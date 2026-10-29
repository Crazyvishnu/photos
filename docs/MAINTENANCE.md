# AI and developer maintenance guide

Read this with the root README and the component READMEs before proposing a fix. This document describes source behavior and verification limits, not a promise that every feature works on every device.

## System map

| Area | Entry points | Responsibility |
| --- | --- | --- |
| Android backup | `photos-android/app/src/main/java/com/photosapp/worker/BackupScheduler.kt`, `BackupWorker.kt` | Coalesce discovery events, respect pause/network policy, run durable backup work |
| Android transfers | `data/DriveResumableUploader.kt`, `data/SharedMediaRepository.kt` | Original-byte upload, resumable sessions, Drive metadata/index |
| Android shares | `ui/share/ShareImportActivity.kt`, `ShareImportViewModel.kt` | Accept successive share intents while earlier batches are being processed |
| Android diagnostics | `CrashDiagnostics.kt`, `ui/home/BackupStatus.kt` | Crash reports and visible backup state |
| Website | `photos-web/src/App.tsx`, `PhotoGrid.tsx`, `Thumbnail.tsx` | Timeline, viewer, controls, account state and mutations |
| Web authorization | `photos-web/src/auth.ts` | Backend session restoration or legacy GIS token flow, selected at build time |
| Web Drive access | `photos-web/src/drive.ts`, `upload.ts` | Listing/change cursors, metadata edits, original downloads, resumable uploads |
| Original photos | `photos-web/src/preview-cache.ts` | Two simultaneous transfers, original decode, LRU object URL lifecycle, bounded lookahead |
| Grid previews | `thumbnail-cache.ts`, `thumbnail-viewport.ts` | Visible-first preview requests, caching and expired link recovery |
| Video | `video-stream.ts`, `photos-web/public/media-worker.js` | Backend media URL or static-site service-worker range streaming |
| Backend | `photos-cloudflare/worker.mjs` | OAuth state/PKCE, credential encryption, session isolation, Google API proxy and streamed media |
| Database | `photos-cloudflare/migrations/0001_sessions.sql` | Sessions, per-session linked accounts, single-use OAuth states |
| Deployment | `photos-cloudflare/wrangler.toml`, `build.mjs`, `.github/workflows/` | Worker/assets build, D1 binding, static Pages and Android CI |

Paths in Android rows are relative to `photos-android/app/src/main/java/com/photosapp/` unless fully specified. Use source search to follow callers before editing.

## Invariants: do not break these while fixing an error

1. Originals stay byte-for-byte unchanged during upload; never silently resize/recompress to improve speed. Display thumbnails are separate from originals.
2. Google account storage quotas are separate. Each file needs room in one account. This is a combined library, not merged Google storage.
3. Android and web must use clients in the same Google Cloud project to discover the same private app properties. Do not replace the project just to solve sign-in.
4. Media marker is `photosapp.library.v1`; album marker is `photosapp.albums.v1`. Capture timestamps use seconds; edit versions use milliseconds. Preserve hashes, dates and tombstones.
5. Do not save a change cursor or replace a complete cache with partial results when any requested page fails.
6. A thumbnail, queued task or partial upload is not proof of backup. Confirm the cloud original before showing success or allowing device cleanup.
7. Automatic discovery must not cancel an active upload or append unlimited workers. Pause must persist before cancellation. Follow Android's real background execution restrictions.
8. Credentials never belong in Git, chat, Vite variables, URLs or logs. Backend session cookies stay HttpOnly/Secure. Keep owner isolation, CSRF guards, fixed proxy destinations, PKCE and single-use state.
9. Do not automatically replay non-idempotent Drive mutations after token refresh. Never make Drive files public to fix playback.
10. Revoke discarded object URLs; keep original transfers/caches bounded. Do not download an entire multi-thousand-photo library into memory to make swiping feel fast.
11. Bin is recoverable; permanent deletion requires explicit confirmation. Preserve Android Room migrations and the app signing identity.

## Run checks from the repository root

Use Node 24 or newer with built-in SQLite support for the backend tests. Use the committed npm lockfile. Windows commands:

```powershell
npm.cmd --prefix photos-web ci
npm.cmd --prefix photos-web test
npm.cmd --prefix photos-web run build
node --test photos-cloudflare/test/worker.test.mjs
node photos-cloudflare/build.mjs
```

The final command validates the backend-enabled build. The ordinary web build validates static mode. Do not infer a working backend from a static-mode build.

Android checks, from `photos-android` with its documented Java/SDK toolchains installed:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Use ARTEMIS to explore a connected, authorized device before authoring UI tests. If unavailable, report the limitation and do not invent selectors or claim device results. Existing web/Worker unit tests use mocks; they cannot prove real Google consent, throughput, codec support or phone background behavior.

## Symptom to investigation

| Symptom | Evidence and likely code path | Correct next action |
| --- | --- | --- |
| Reconnect after every refresh | Confirm host, build mode, `/api/health`, `/api/session`, cookie presence via browser UI; inspect `auth.ts` and Worker session handling | Keep site and API same-origin; restore profiles from backend; diagnose expired/revoked grant separately from missing cookie. Never print cookie/token values. |
| Health `configured:false` | Worker secrets or DB binding missing | Verify secret names `GOOGLE_CLIENT_SECRET`, `TOKEN_ENCRYPTION_KEY`, and `DB` binding; values entered privately by owner. Do not regenerate encryption key casually. |
| OAuth redirect mismatch | Exact callback and public origin | Register `https://photos-app.0143crazy.workers.dev/api/auth/callback` on the existing Web client; preserve other clients/origins. |
| OAuth connects but no library | Account email, project, media marker, Drive API errors, all listing pages | Connect the actual backup-owning accounts; Google Photos-only media will not appear automatically. The inspected account previously showed zero items. |
| Sharp photo arrives after several seconds | Original request start/end, bytes, duplicate requests, prefetch cache hit and decode | Check `preview-cache.ts` and Viewer. Current rolling window is six ahead/two behind, two transfers, 128 MiB/12 completed entries; very large files and rapid browsing still take time. Do not promise a fixed five-second bound. |
| Grid shows placeholders | Account access, expired thumbnail metadata, request status, viewport scheduling | Check `thumbnail-cache.ts`, `/api/thumbnail` and allowed Google image hosts. Keep visible-first scheduling; do not start thousands of requests. |
| Video waits or displays 401 | `/api/media` or static private-video response status, Content-Type, Range/206/Content-Range, token renewal | Stream with server-side authorization in Cloudflare mode. Do not use an authenticated iframe or put bearer tokens in the URL. |
| Unsupported video format | `HTMLMediaElement.error`, MIME, codec/container evidence, whether response is actually JSON/HTML error | Distinguish transport/auth failure from decode failure. Retry is not transcoding. Preserve original; use Drive fallback. A compatible derivative requires a separate explicit design. |
| Android backup stays Waiting | WorkManager state, pause/local-mode/network settings, permissions, per-account quota, device battery restrictions | Trace scheduler -> worker -> uploader. Verify app reopen, media creation, network change, reboot and pause/resume on hardware. |
| Videos fail upload | Sanitized HTTP/status error, readable source URI, resumable offset and file size, interruptions | Resume from confirmed offset; preserve source grants/copies. Avoid generic error text hiding the underlying stage. |
| Second share batch ignored | Successive intent delivery, URI lifetime, import queue and persistent row state | Trace share activity/viewmodel and worker successor. Do not block receipt merely because an earlier batch is uploading. |
| Crash while scrolling/playing | Last crash report plus sanitized ADB logs, memory pressure, player/thumbnail lifecycle | Reproduce on device and release players/decodes appropriately; a build alone does not validate the fix. |

Google can revoke refresh tokens, Testing-mode consent can limit token lifetime, and the browser session expires after 90 days or cookie clearing. Automatic renewal cannot prevent all future consent prompts. Cloudflare Free has usage limits and is not an unlimited or guaranteed forever service.

## Deployment and rollback

Primary URL: https://photos-app.0143crazy.workers.dev
Legacy static URL: https://crazyvishnu.github.io/photos/

From the repository root, after checks:

```powershell
npx.cmd wrangler d1 migrations apply photos-db --remote --config photos-cloudflare/wrangler.toml
npx.cmd wrangler deploy --config photos-cloudflare/wrangler.toml
```

Review pending migrations first; never drop production tables to fix a migration error. Wrangler runs the backend-enabled web build automatically. `wrangler.toml` contains public identifiers; the two secret values stay in Cloudflare. Local `.dev.vars*`, `.wrangler/`, credential downloads, signing keys, APKs, diagnostic dumps and `local-notes/` remain ignored.

After deployment, verify health, connect a backup-owning account, reload, inspect thumbnail/original requests, swipe both directions, play/seek a known compatible video, and exercise account isolation. Do not delete real user photos for a smoke test. Record the deployed version and results. Roll back using Cloudflare deployment history to a known working Worker/assets version; do not rotate encryption keys or delete D1 to roll back code.

Pushing `main` triggers the separate static Pages workflow when web files change. It does not deploy Cloudflare. CI debug APKs may have a different debug signing key from a local installation; never recommend uninstalling without explaining loss of local state and unfinished uploads.

## Current evidence and open validation gaps (2026-10-29)

- Web: 35 passing automated checks; TypeScript/Vite static and backend-enabled builds passed.
- Worker: 13 passing tests with in-memory SQLite and mocked Google responses.
- Cloudflare deployed version at the last media update: `f2cb1a03-0e13-4f1d-8f63-43a22a5835cb` (verify dashboard for newer versions).
- Google callback was reached. Real-library prefetch/playback and renewal after token expiry were not verified; connected inspection account showed zero items and a saved-library/reconnect status after reload. Investigate this status rather than declaring persistent access solved.
- Android 2.0.4 (15) debug build/lint passed on 2026-10-29; no hardware proof of scheduler reliability, reinstall recovery or viewer stability.
- `REA-REVIEW.md` records static analysis limits. No successful reference-app decompilation or full Google Photos parity is claimed.

## Handoff format for every repair

Record: exact symptom; app version/URL/build mode; reproducible steps; sanitized error/status and timing; source cause; changed files; tests run and their actual results; deployment version; checks not run and why; remaining risks. Keep secrets and personal media out of the report. Prefer a targeted fix and a regression test that would fail before the fix. Never claim all possible test cases pass.
