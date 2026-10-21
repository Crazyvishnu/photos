import {describe,expect,it} from 'vitest';
import {screenPreviewCandidates,screenPreviewPixels,screenPreviewUrl} from './screen-preview';

describe('adaptive screen previews',()=>{
  it('uses only fixed reliable Drive preview tiers',()=>{
    expect(screenPreviewPixels(412,915,3)).toBe(1600);
    expect(screenPreviewPixels(1920,1080,2)).toBe(1600);
    expect(screenPreviewPixels(600,800,1.5)).toBe(1200);
    expect(screenPreviewPixels(320,480,1)).toBe(960);
  });

  it('normalizes requested sizes to a known tier',()=>{
    expect(screenPreviewUrl('https://lh3.example/photo=s220',1856)).toBe('https://lh3.example/photo=s1600');
    expect(screenPreviewUrl('https://lh3.example/photo=s220',1180)).toBe('https://lh3.example/photo=s1200');
    expect(screenPreviewUrl('https://lh3.example/photo',800)).toBe('https://lh3.example/photo=s960');
  });

  it('automatically descends through safe sizes before the original Drive thumbnail URL',()=>{
    expect(screenPreviewCandidates('https://lh3.example/photo=s220',1600)).toEqual([
      'https://lh3.example/photo=s1600',
      'https://lh3.example/photo=s1200',
      'https://lh3.example/photo=s960',
      'https://lh3.example/photo=s220'
    ]);
    expect(screenPreviewCandidates('https://lh3.example/photo=s220',1200)).toEqual([
      'https://lh3.example/photo=s1200',
      'https://lh3.example/photo=s960',
      'https://lh3.example/photo=s220'
    ]);
  });
});
