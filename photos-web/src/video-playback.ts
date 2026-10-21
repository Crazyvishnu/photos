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
        message: 'Video playback was interrupted. Retry the original stream.',
        useDrivePreview: false,
      };
    case 2:
      return {
        kind: 'network',
        message: 'The private video stream was interrupted. Loading the authenticated original instead.',
        useDrivePreview: false,
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
        message: 'This browser cannot play the original video codec directly. Switching to the Google Drive compatible preview.',
        useDrivePreview: true,
      };
    default:
      return {
        kind: 'unknown',
        message: 'The browser could not play this original video. Switching to the Google Drive compatible preview.',
        useDrivePreview: true,
      };
  }
}

export function fallbackForStreamError(error: unknown): VideoFailure {
  const detail = error instanceof Error && error.message ? error.message : 'The private video stream could not be opened.';
  return {
    kind: 'network',
    message: `${detail} Loading the authenticated original instead.`,
    useDrivePreview: false,
  };
}

export function drivePreviewUrl(fileId: string): string {
  return `https://drive.google.com/file/d/${encodeURIComponent(fileId)}/preview`;
}
