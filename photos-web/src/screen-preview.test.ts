import {describe,expect,it} from 'vitest';
import {screenPreviewCandidates,screenPreviewPixels,screenPreviewUrl} from './screen-preview';

describe('adaptive screen previews',()=>{
  it('keeps phone and desktop requests within reliable Drive thumbnail bounds',()=>{
    expect(screenPreviewPixels(412,915,3)).toBe(1600);
    expect(screenPreviewPixels(1920,1080,2)).toBe(1600);
  });

  it('keeps a useful floor for small viewports',()=>{
    expect(screenPreviewPixels(320,480,1)).toBe(960);
  });

  it('replaces Drive thumbnail sizes and caps invalid sizes',()=>{
    expect(screenPreviewUrl('https://lh3.example/photo=s220',1856)).toBe('https://lh3.example/photo=s1600');
    expect(screenPreviewUrl('https://lh3.example/photo',9000)).toBe('https://lh3.example/photo=s1600');
  });

  it('returns descending fallbacks and finally the original Drive thumbnail URL',()=>{
    expect(screenPreviewCandidates('https://lh3.example/photo=s220',1472)).toEqual([
      'https://lh3.example/photo=s1472',
      'https://lh3.example/photo=s1600',
      'https://lh3.example/photo=s1280',
      'https://lh3.example/photo=s1024',
      'https://lh3.example/photo=s960',
      'https://lh3.example/photo=s220'
    ]);
  });
});
