# Photos App for the web

A responsive React/TypeScript website using the same Google Drive library as Android. The web client is a static app: Google handles authorization and originals go directly to the linked Drive accounts.

## Run locally

Use Node 24 LTS (verified locally with Node 25.9.0). From this directory:

```sh
npm ci
npm run dev
```

Open `http://localhost:5173`. If using `http://127.0.0.1:5173`, register that origin too.

```sh
npm test
npm run build
```

`dist/` contains the production site. Relative asset URLs allow deployment under `/photos/` or a custom-domain root. Navigation is state-based, so GitHub Pages does not need an SPA rewrite.

## OAuth configuration

The included **public** Web client ID is from the same Cloud project as the existing Android client. A client ID is not a client secret. Never put a client secret, token or signing key into a Vite environment variable or this repository.

In Google Cloud project `calculate-501409`, open Google Auth Platform > Clients > Web client 1. Add the exact origins where you will use this app:

```text
http://localhost:5173
http://127.0.0.1:5173
https://crazyvishnu.github.io
```

JavaScript origins do not include `/photos/`. This app uses Google Identity Services' popup token flow, so it does not use an application redirect URI or client secret. Existing redirect URIs for other uses can remain. Enable Drive API and configure consent/test users for the project.

To use another project, set `VITE_GOOGLE_CLIENT_ID` in an untracked `.env.local` or change the public client ID under web Settings. Register the Android OAuth client in that same project if you want both clients to discover the same private app properties.

## Features

- The complete timeline is available in one continuous scroll, without a Show more button. Explicit viewport observation assigns image sources only near the screen; a six-transfer priority queue loads visible previews first and cancels abandoned requests. Loaded thumbnails are retained in a bounded 32 MiB / 240-item session cache for scrolling back. Hovering grid tiles never downloads full originals.
- Filename/account/date search; month, account and media-type filters; comfortable/compact grids; newest/oldest order.
- Multi-account connection, actual Drive quotas and Google storage links.
- Original-byte uploads, chunked hashing in a Web Worker, content-hash duplicate checks, saved resumable sessions and progress/pause/retry.
- Instant available-thumbnail fallback while originals download, bounded session-only original caching, and adjacent-photo prefetch. Left/right photo areas navigate; the center toggles controls in white/black view.
- Image viewer, original downloads, Google Drive video previews, favorites and editable timeline dates.
- Bulk selection, custom albums, automatic collections, Bin, restoration and confirmed permanent deletion.
- Light/dark/system appearance, backup badges, upload destination, optional minute refresh, keyboard shortcuts and metadata-index export.
- Android 2.0 shared album support. Earlier Android clients can sync media metadata but do not read the album manifests.

## Sync contract

Media retains Android's `library=photosapp.library.v1` app property and SHA-256 hash. `capturedAt` is epoch seconds; date/favorite edit versions are epoch milliseconds. Listing and change cursors are per account. Cursors and cached indexes are committed only after all requested pages succeed. Partial account failures retain the last usable cache.

Custom albums are private `application/json` Drive files marked `library=photosapp.albums.v1`. Their JSON has `version:1`, `id`, `title`, `createdAt`, `updatedAt`, `members` and `deleted`. Members use stable `drive:<email>:<fileId>` identities. Deleted manifests remain as tombstones. Each album has one owning account; reconnect it to edit that album. Android preserves device-only unbacked members locally until their files are backed up.

Album conflict resolution uses the newest timestamp. It is not collaborative editing: simultaneous edits from multiple devices can still race. No server-side global uniqueness lock is provided. Keep device clocks correct and refresh before making changes on another device.

## Browser limits and privacy

- Keep the tab open during uploads. After reload, reconnect all accounts and reselect the same files to resume saved sessions. The website cannot continuously back up a phone gallery while closed.
- Access tokens are retained in sessionStorage for this tab until Google expiry, and removed on disconnect. Account identities, library metadata and resumable session URLs are cached in IndexedDB on this device; removing an account clears its displayed library cache. Upload-session records remain private in this browser so unfinished originals can resume after reconnection.
- Preview originals are kept only in a bounded in-memory cache (64 MiB / 8 items, with the active photo retained). Removing an account clears its cached originals. No photo bytes are persisted into browser storage. First-time cloud retrieval still depends on connection speed; available thumbnails remain visible during the transfer, and failed originals have a retry action.
- Original files are not persisted into browser storage. Chunking changes transfer boundaries, never image/video bytes. Photo EXIF dates are used when supported; otherwise file modification time is labelled as the fallback and can be edited.
- Google Photos cloud-only items must first be shared/downloaded/exported as actual files. This is a Google Drive library, not an automatic Google Photos migration.
- Private thumbnails use Drive-provided short-lived URLs and may expire or be blocked. A failed visible thumbnail refreshes only its own metadata once when the account is connected. Preview errors show an Open to view fallback. Original and thumbnail caches are both tab-session and are cleared when an account is removed. The original can still be opened after reconnecting. Full-image previews are capped at 100 MB; large files open through Drive.
- Video playback first uses an authenticated same-origin byte-range stream so compatible originals can start without a full download. When Google Drive returns a 206 response whose `Content-Range` header is hidden by CORS, the service worker reconstructs that header from the requested range and known file size for mobile/browser playback. If range streaming still fails, the app downloads the authenticated original into a temporary in-memory blob (up to the existing preview size limit) before considering Google Drive's embedded preview. The Drive iframe is now only a codec/final fallback, because third-party cookies can block it. Open in Drive remains available. No access token is put into an image/video URL.
- Permanent deletion removes the cloud original; phone originals are not directly deleted by this website. Bin retention follows Drive's policy.
- No analytics or advertisements. Google Identity, Drive previews and the Google-hosted font contact Google services.

## Hosting without a student ID

GitHub Pages provides the project address `https://crazyvishnu.github.io/photos/` without domain registration. This is a subdomain/project path, not an owned second-level domain. The workflow in `../.github/workflows/web-pages.yml` builds/tests the site and deploys `dist/`.

In repository Settings > Pages, select **GitHub Actions** as the source. Push to `main` or run the workflow manually. The website is not live until Pages is enabled and deployment succeeds.

A subdomain of a domain you already own is another option. A separate custom registration is not guaranteed to be free forever. Name.com/.tech student offers require their own eligibility and usually charge for renewal after the promotional year.

## Verification

The local browser was used to inspect the welcome screen, settings, theme selection and reload persistence. A local fixture with deliberately delayed original downloads verified immediate thumbnails, left/right navigation, and navigation with controls hidden. An automated rendering check covers all 4,450 media items without pagination and ensures images are not assigned sources before viewport activation; cache tests cover deduplication, transfer priority, memory eviction, retry and cancellation. A 5,000-item local browser fixture confirmed only 26 image sources at the initial viewport and loaded previews after a jump to item 4,001. Thumbnail tests also cover expired-link recovery and token destination checks. Automated web tests cover original hashes, Drive acknowledgments, ordering, album identities, token destination checks, cursor atomicity and deletion guards. They mock Drive responses; passing them is not evidence of a real account upload.

Live OAuth, uploads, browser CORS behavior and Android/web round trips require the allowed origins and user authorization, followed by verification with a disposable test file. No private photo is uploaded or permanently deleted as an automatic test.

References: [Google's browser token model](https://developers.google.com/identity/oauth2/web/guides/use-token-model), [Drive uploads](https://developers.google.com/workspace/drive/api/guides/manage-uploads), [Drive custom properties](https://developers.google.com/workspace/drive/api/guides/properties), [GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/about-github-pages).
