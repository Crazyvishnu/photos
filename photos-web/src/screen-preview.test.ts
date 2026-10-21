import {describe,expect,it} from 'vitest';
import {screenPreviewPixels,screenPreviewUrl} from './screen-preview';

describe('adaptive screen previews',()=>{
  it('requests retina screen quality without camera-original dimensions',()=>{
    expect(screenPreviewPixels(412,915,3)).toBe(1856);
    expect(screenPreviewPixels(1920,1080,2)).toBe(2560);
  });
  it('keeps a useful floor for small viewports',()=>{
    expect(screenPreviewPixels(320,480,1)).toBe(1200);
  });
  it('replaces Drive thumbnail sizes and caps invalid sizes',()=>{
    expect(screenPreviewUrl('https://lh3.example/photo=s220',1856)).toBe('https://lh3.example/photo=s1856');
    expect(screenPreviewUrl('https://lh3.example/photo',9000)).toBe('https://lh3.example/photo=s4096');
  });
});
