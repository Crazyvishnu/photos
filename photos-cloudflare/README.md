# Cloudflare Photos

Same-origin website and authentication backend for https://photos-app.0143crazy.workers.dev.

## Setup

The existing Worker is `photos-app`; D1 `photos-db` is bound as `DB`. Configuration is in `wrangler.toml`. These identifiers are public configuration. The deployment targets the Free plan; no paid products are enabled by this configuration.

1. Authenticate the official Wrangler CLI with Workers edit and D1 edit permissions. Review the OAuth screen before authorizing it.
2. `npx wrangler d1 migrations apply photos-db --remote --config photos-cloudflare/wrangler.toml`
3. In Google Cloud, open the existing **Web client** in the same project as Android. Add the authorized redirect URI `https://photos-app.0143crazy.workers.dev/api/auth/callback`. Keep existing Android and GitHub Pages entries. Client ID must match `GOOGLE_CLIENT_ID` in the config.
4. In Cloudflare Worker Settings → Runtime variables and secrets, save `GOOGLE_CLIENT_SECRET` as a **Secret**. Enter the value directly there, never into chat, source, a terminal command argument or a public repository.
5. Generate a random 32-byte key and save its base64url encoding as the **Secret** `TOKEN_ENCRYPTION_KEY`. Keep a private backup: changing it without re-encrypting the database requires reconnecting all accounts.
6. Build the website with `VITE_AUTH_BACKEND=true`, then run `npx wrangler deploy --config photos-cloudflare/wrangler.toml` from the repository root.
7. Open `/api/health`; `configured` must be true. Connect each Google account once at the new website, then verify reload, token renewal, thumbnails, video seeking, disconnect and app/web library synchronization.

PowerShell build:

```powershell
$env:VITE_AUTH_BACKEND='true'
npm.cmd --prefix photos-web run build
Remove-Item Env:VITE_AUTH_BACKEND
npx.cmd wrangler deploy --config photos-cloudflare/wrangler.toml
```

The GitHub Pages build remains static when this environment flag is absent. It cannot renew Google authorization automatically.

## Security and operation

- Google authorization-code flow with PKCE and an expiring, session-bound, single-use OAuth state.
- Random 256-bit browser session, stored only as a SHA-256 digest in D1. Cookie is HttpOnly, Secure, SameSite=Lax, host-only, with a 90-day lifetime.
- AES-256-GCM encryption for refresh/access tokens with randomized IVs and account/session-bound additional authenticated data. The encryption key and OAuth client secret live in Worker Secrets.
- Google tokens are not returned to frontend JavaScript. Linked accounts are isolated by browser session; each browser must connect its own accounts. Google account permissions are not revoked globally when one browser removes an account.
- Same-origin checks plus a non-simple CSRF header protect mutations. No permissive CORS endpoint exists.
- Only the Google Drive v3 and upload v3 endpoint families are proxied. Preview redirects are validated against Google-hosted image domains. Token destinations are fixed; user-supplied arbitrary proxy destinations are rejected.
- Drive original downloads and videos are streamed, forwarding real byte range headers. Thumbnail metadata is renewed on demand. Media responses use `no-store`; the backend does not retain original photos/videos.
- An account's access token renews server-side before expiry; invalid grants mark only that account as needing reconnection. Non-idempotent Drive mutations are never automatically replayed.
- Scheduled cleanup deletes expired sessions, linked credentials and OAuth states. Application observability is disabled to avoid logging OAuth callback URLs. Do not add request logging that records query strings, cookies or authorization headers.
- Refresh tokens can still be revoked or expire. External OAuth apps in Google's Testing mode can receive limited-lifetime refresh tokens; configure the consent-screen publishing status appropriately and follow Google's verification requirements.
- A cleared cookie or expired 90-day session requires reconnecting. This backend is not a cross-device master login or a separate photo-storage service.
- Keep uploads in bounded chunks as the client already does. Free Worker request limits apply to proxied API/media requests.

## Verification

`node --test photos-cloudflare/test/worker.test.mjs` exercises the Worker against a real in-memory SQLite schema with a D1 adapter and mocked Google HTTP responses. This verifies local behavior; it does not prove real OAuth consent or real media playback. `npm.cmd --prefix photos-web test` covers frontend regressions and backend session restoration. Run Wrangler's deployment dry-run as well.

No secret is included in this repository. `.dev.vars*` and `.wrangler/` must remain ignored. Never commit local database files or Google credential downloads.

## Private preview authentication repair (2026-10-30)

Drive thumbnailLink values for private files require an authenticated request. The Worker attaches the owning account token only after validating the Google-hosted image URL, validates every redirect, and rejects successful non-image responses. Never fetch private thumbnails anonymously or expose bearer tokens in image URLs. The client tries existing optimized grid copies before another backend thumbnail and uses a screen preview while the unchanged original downloads.

The streaming proxy preserves upstream Content-Encoding with manual encoding and omits the upstream Content-Length so compressed bodies cannot be mislabeled or carry an unsafe length. A regression test relays gzip JSON and verifies its header/body representation. Cloudflare reference: https://developers.cloudflare.com/workers/runtime-apis/fetch/ .
