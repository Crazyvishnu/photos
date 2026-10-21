export type VideoFailureKind = 'aborted' | 'network' | 'decode' | 'unsupported' | 'unknown';

export type VideoFailure = {
  kind: VideoFailureKind;
  message: string;
  useDrivePreview: boolean;
};

export function classifyVideoError(error: { code?: number } | null | undefined): VideoFailure {
  switch (error?.code) {
    case 1:
      return {
        kind: 'aborted',
        message: 'Video playback was interrupted. Retry the original stream or use the Google Drive preview.',
        useDrivePreview: false,
      };
    case 2:
      return {
        kind: 'network',
        message: 'The private video stream was interrupted. Switching to the Google Drive preview.',
        useDrivePreview: true,
      };
    case 3:
      return {
        kind: 'decode',
        message: 'Your browser could not decode this original video. Switching to the Google Drive compatible preview.',
        useDrivePreview: true,
      };
    case 4:
      return {
        kind: 'unsupported',
        message: 'This browser cannot play the original video format directly. Switching to the Google Drive compatible preview.',
        useDrivePreview: true,
      };
    default:
      return {
        kind: 'unknown',
        message: 'The original video could not be played here. Switching to the Google Drive preview.',
        useDrivePreview: true,
      };
  }
}

export function fallbackForStreamError(error: unknown): VideoFailure {
  const detail = error instanceof Error && error.message ? error.message : 'The private video stream could not be opened.';
  return {
    kind: 'network',
    message: `${detail} Switching to the Google Drive preview.`,
    useDrivePreview: true,
  };
}

export function drivePreviewUrl(fileId: string, email?: string): string {
  const url = new URL(`https://drive.google.com/file/d/${encodeURIComponent(fileId)}/preview`);
  if (email) url.searchParams.set('authuser', email);
  return url.toString();
}
