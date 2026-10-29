# Photos App: REA-assisted review

Review date: 2026-10-28. Scope: supplied reference APK, Android source and local web build. This is a static review, not a completed device test or a claim of Google Photos feature parity.

## Tools and evidence

- Requested project: https://github.com/morluto/rea. Used official `rea-agents@6.8.0` CLI through npx without running setup or registering an MCP server.
- Installed Node 25.9.0 is outside REA's advertised supported range. CLI commands ran, but this limits reproducibility; use a supported Node runtime for follow-up analysis.
- Supplied `Photos.apk` is a reference Google Photos package: `com.google.android.apps.photos`, version `7.96.0.996044120`, versionCode 52514897. It is not our app. Identity comes from Android SDK aapt2 manifest inspection.
- APK SHA-256 from REA: `99cb38e442eec74e8ac56ae4a7d7a233cd83280d7b2036eaa62fde571d06cf3c`.
- REA's APK signature reader reported v2/v3/v3.1 records and matching declared content digests. This is not an independent claim that the APK is an authentic distribution or that every cryptographic signature was verified.
- `inspect-android-package` could not run for either APK: `REA_JADX_MCP_JAR` is not configured. REA requires a caller-supplied jadx-headless-mcp 0.7.1 JAR. No decompilation claims are made.
- Local raw evidence is retained in ignored `local-notes/rea-reference-signature.json`, `rea-google-photos-package.json`, `rea-our-photos-package.json`, and `google-photos-manifest.txt`. Package inspection reports contain the provider errors, not successful analysis.
- REA web bundle analysis returned provider_timeout at its 300000 ms per-source deadline while analyzing the minified main bundle. local-notes/rea-web-evidence.json retains the error and partial observations; it is not a completed application graph. No product conclusions are attributed to a successful REA web reconstruction.

## Confirmed reference declarations

The Google Photos manifest declares background/user-initiated backup job services, a foreground upload-status notification service, notification-action receivers, device-folder settings and a backup overview activity. These are component declarations only: they do not prove scheduling, retry, deduplication, cloud architecture or throughput behavior.

The transferable idea is to separate durable scheduling, transfer execution, user controls and observable backup status. Implement these using our own source and Android APIs; no reference code or resources were copied.

## Changes made in this review

1. **Bound automatic scheduling.** `BackupScheduler.ensure` serializes scheduling decisions and checks durable WorkManager state. Repeated discovery events reuse a waiting job; an active job may have one waiting successor. Previously every event appended another scan. Uploads are not cancelled by discovery.
2. **Move blocking WorkManager lookups off the UI thread.** Scheduling runs on Dispatchers.IO, with bounded waits for WorkManager query/commit operations.
3. **Persist Pause before cancellation.** The pending debounce job is cancelled and the paused preference is saved before cancelling automatic/manual/periodic work. This reduces the window in which another discovery event can restart backup.
4. **Handle scheduling errors without crashing the gallery.** Cancellation is propagated; other scheduling errors are logged. Media-triggered scheduling failures request retry.
5. **Restart the web player on Retry.** The retry attempt is included in the video element key, forcing a fresh media element even when the source URL is unchanged. This does not establish that the underlying Drive stream/codec problem is fixed.
6. Android build version advanced to 2.0.3 (14).

## Remaining problems and proposed solutions

| Priority | Problem and evidence | Proposed solution |
| --- | --- | --- |
| P1 | BackupWorker stops the whole run when cloud refresh returns any account error. Quota loading is also all-or-nothing. | Track account readiness and errors independently. Continue only with verified healthy accounts. Preserve duplicate prevention: do not blindly reupload unknown items when an unavailable account may already hold them. |
| P1 | Pending one-time work retains network constraints from when it was created. | Reconcile only waiting work on settings changes, while allowing active transfers to checkpoint and observing current policy before every chunk. Test Wi-Fi-to-mobile and mobile-to-Wi-Fi transitions. |
| P1 | Permanent permission/source errors and temporary transport errors can enter repeated batch retries. | Typed failure classification, per-item retry deadlines, exponential backoff with jitter, and an actionable terminal failure state. |
| P1 | Real web playback failed in user reports; worker test mocks a successful range response. | Verify actual authenticated status, byte ranges, seeking and codec support. Report stream/auth/decode failures separately. Test MP4 metadata at the end of the file and unsupported codecs. Do not label every failure a codec error. |
| P1 | No Android test source sets exist, and runtime exploration is unavailable. | Configure ARTEMIS and a device first; then author runnable verified tests for discovery, reboot, process death, retry and sharing. Build/lint are not substitutes. |
| P2 | Web stores expiring access tokens per tab without automatic renewal. | Add explicit session expiry state and deliberate renewal. A backend authorization-code flow would be a separate architecture decision; never embed client secrets in the site. |
| P2 | Full-quality photo display still depends on original transfer. | Use size-appropriate previews with renewal/retry, bounded memory/disk caching, and selected-item priority. Preserve original bytes for backup/download. |
| P2 | All gallery item elements are rendered for large web libraries. | Virtualize rows while keeping the complete library accessible by scrolling and date navigation. |
| P2 | Several local fixes are not synchronized with the published repository. | Publish a reviewed source revision, attach build/version identifiers and use the same revision for APK and website artifacts. |

## Useful additions

- A backup health screen showing the actual blocker: paused, network restriction, permission, account reconnect, quota, retry time or Android background restriction.
- Per-account health and storage routing, with the owning account shown for each cloud item.
- A persistent queue with item counts, upload progress, retry controls and checkpoint age.
- Separate cache management from original-file deletion, and make deletion scope explicit.
- A privacy-conscious diagnostic export with version, WorkManager state and redacted error codes; exclude tokens, full private paths and photo content.
- Release checks covering APK identity/signing, source revision, migrations and rollback compatibility.

## Verification limits

Verification completed: 29 existing web tests passed; TypeScript/Vite production build passed; Android assembleDebug and lintDebug passed. Photos-App-2.0.3.apk reports com.photosapp, versionCode 14, versionName 2.0.3. apksigner verification succeeded with the existing certificate SHA-256 97bb006040836e3a04e25d0985717229c3950157cf9b5ad8b09c76b3b891f655. These are debug-signed development artifacts, not a Play Store release.

No phone backup, reboot recovery, duplicate prevention, new-media discovery or real video playback test passed as part of this review. ARTEMIS previously reported no connected device/emulator and missing credentials. Follow the repository's ARTEMIS rules before authoring Android interaction tests.

The current changes remain local unless a separate publish operation is confirmed by its result. Do not interpret this report as a website deployment.
