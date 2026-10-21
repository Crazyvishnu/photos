import { token } from './auth';
import type { Media } from './model';

let ready: Promise<void> | undefined;

async function probePrivateStream(url: string): Promise<void> {
  let response: Response;
  try {
    response = await fetch(url, {
      cache: 'no-store',
      headers: { Range: 'bytes=0-0' },
    });
  } catch {
    throw new Error('The private video stream could not be reached.');
  }

  try {
    if (response.status === 401 || response.status === 403) {
      throw new Error('Video authorization expired. Reconnect the owning Google account.');
    }
    if (response.status !== 200 && response.status !== 206) {
      throw new Error(`The private video stream failed (HTTP ${response.status}).`);
    }

    const contentType = response.headers.get('Content-Type')?.toLowerCase();
    if (contentType && !contentType.startsWith('video/') && contentType !== 'application/octet-stream') {
      throw new Error('Google Drive returned an unexpected response instead of the video.');
    }

    if (response.status === 206 && !response.headers.get('Content-Range')) {
      throw new Error('Google Drive returned an invalid byte-range response.');
    }
  } finally {
    try {
      await response.body?.cancel();
    } catch {
      // The one-byte probe is disposable; cancellation failure is not a playback failure.
    }
  }
}

export function videoStream(m: Media): Promise<string> {
  if (!('serviceWorker' in navigator)) {
    return Promise.reject(new Error('Private video streaming needs a supported browser with HTTPS.'));
  }

  if (!ready) {
    ready = (async () => {
      navigator.serviceWorker.addEventListener('message', event => {
        if (event.data?.type !== 'video-authorization' || !event.ports[0]) return;
        let value: string | null = null;
        try {
          value = token(event.data.email);
        } catch {
          // The service worker returns 401 and the viewer switches to Drive preview.
        }
        event.ports[0].postMessage(value);
      });

      await navigator.serviceWorker.register(import.meta.env.BASE_URL + 'media-worker.js', {
        scope: import.meta.env.BASE_URL,
      });
      await navigator.serviceWorker.ready;

      if (!navigator.serviceWorker.controller) {
        await new Promise<void>((resolve, reject) => {
          const changed = () => {
            clearTimeout(timer);
            resolve();
          };
          const timer = setTimeout(() => {
            navigator.serviceWorker.removeEventListener('controllerchange', changed);
            reject(new Error('Reload once to enable private video streaming.'));
          }, 10000);
          navigator.serviceWorker.addEventListener('controllerchange', changed, { once: true });
        });
      }
    })().catch(error => {
      ready = undefined;
      throw error;
    });
  }

  return ready.then(async () => {
    token(m.email);
    const url = import.meta.env.BASE_URL + 'private-video?' + new URLSearchParams({ id: m.id, email: m.email });
    await probePrivateStream(url);
    return url;
  });
}
