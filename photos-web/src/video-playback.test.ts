import { describe, expect, it } from 'vitest';
import { classifyVideoError, drivePreviewUrl, fallbackForStreamError } from './video-playback';

describe('video playback fallback', () => {
  it('distinguishes transport errors from codec errors', () => {
    expect(classifyVideoError({ code: 2 })).toMatchObject({ kind: 'network', useDrivePreview: false });
    expect(classifyVideoError({ code: 3 })).toMatchObject({ kind: 'decode', useDrivePreview: true });
    expect(classifyVideoError({ code: 4 })).toMatchObject({ kind: 'unsupported', useDrivePreview: true });
  });

  it('does not mislabel an aborted playback as an unsupported codec', () => {
    const failure = classifyVideoError({ code: 1 });
    expect(failure.kind).toBe('aborted');
    expect(failure.useDrivePreview).toBe(false);
    expect(failure.message).not.toContain('codec');
  });

  it('uses an authenticated original download before the Drive iframe when stream setup fails', () => {
    const failure = fallbackForStreamError(new Error('Video authorization expired.'));
    expect(failure.useDrivePreview).toBe(false);
    expect(failure.message).toContain('authorization expired');
    expect(failure.message).toContain('authenticated original');
  });

  it('builds a private Drive preview URL without putting account data or tokens in it', () => {
    const value = drivePreviewUrl('file/id?');
    const url = new URL(value);
    expect(url.origin).toBe('https://drive.google.com');
    expect(url.pathname).toBe('/file/d/file%2Fid%3F/preview');
    expect(url.search).toBe('');
    expect(value).not.toContain('token');
    expect(value).not.toContain('authuser');
  });
});
