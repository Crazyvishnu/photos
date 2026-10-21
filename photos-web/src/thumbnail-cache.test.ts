import { describe,expect,it,vi } from 'vitest';
import { ThumbnailCache } from './thumbnail-cache';
import type { Media } from './model';
const media=(id:string,email='test@example.test'):Media=>({id,email,name:id,mimeType:'image/jpeg',thumbnailLink:`https://example.test/${id}`});
const image=(id:string)=>({src:id,naturalWidth:100,naturalHeight:100} as HTMLImageElement);
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));

describe('Visible thumbnail scheduling',()=>{
  it('prioritizes visible photos and never starts more than the configured transfers',async()=>{
    const order:string[]=[],finish=new Map<string,()=>void>();
    const cache=new ThumbnailCache(m=>new Promise(resolve=>{order.push(m.id);finish.set(m.id,()=>resolve(image(m.id)));}),2);
    const a=cache.acquire(media('a'),0),b=cache.acquire(media('b'),0),far=cache.acquire(media('nearby'),1),visible=cache.acquire(media('visible'),0);
    await tick();expect(order).toEqual(['a','b']);finish.get('a')!();await a.promise;await tick();expect(order).toEqual(['a','b','visible']);
    finish.get('b')!();await b.promise;await tick();finish.get('visible')!();finish.get('nearby')!();await Promise.all([visible.promise,far.promise]);
  });
  it('cancels off-screen queued work instead of downloading the whole library',async()=>{
    const loader=vi.fn((_m:Media,s:AbortSignal)=>new Promise<HTMLImageElement>((_resolve,reject)=>s.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError')))));
    const cache=new ThumbnailCache(loader,1);const first=cache.acquire(media('first'),0);const next=cache.acquire(media('offscreen'),1);
    const cancelled=expect(next.promise).rejects.toThrow();next.release();await cancelled;expect(loader).toHaveBeenCalledTimes(1);
    const stopped=expect(first.promise).rejects.toThrow();first.release();await stopped;
  });
  it('reuses loaded previews on return and shares concurrent requests for the same account/file',async()=>{
    const loader=vi.fn(async m=>image(m.id));const cache=new ThumbnailCache(loader);
    const a=cache.acquire(media('same'),0),b=cache.acquire(media('same'),0);expect(a.promise).toBe(b.promise);await a.promise;a.release();b.release();
    await cache.acquire(media('same'),0).promise;expect(loader).toHaveBeenCalledTimes(1);
    await cache.acquire(media('same','other@example.test'),0).promise;expect(loader).toHaveBeenCalledTimes(2);
  });
  it('evicts old unreferenced images and clears removed-account previews',async()=>{
    const loader=vi.fn(async m=>image(m.id));const cache=new ThumbnailCache(loader,2,80000,2);
    for(const id of ['a','b','c']){const lease=cache.acquire(media(id),0);await lease.promise;lease.release();}
    expect(cache.peek(media('a'))).toBeUndefined();expect(cache.peek(media('c'))?.src).toBe('c');cache.clear('test@example.test');expect(cache.peek(media('c'))).toBeUndefined();
  });
  it('warms a month in the background but lets a visible photo jump ahead of queued month work',async()=>{
    const order:string[]=[],finish=new Map<string,()=>void>();
    const cache=new ThumbnailCache(m=>new Promise(resolve=>{order.push(m.id);finish.set(m.id,()=>resolve(image(m.id)));}),2);
    cache.warm([media('m1'),media('m2'),media('m3'),media('m4')]);
    await tick();expect(order).toEqual(['m1','m2']);
    const visible=cache.acquire(media('visible'),0);
    finish.get('m1')!();await tick();expect(order).toEqual(['m1','m2','visible']);
    finish.get('visible')!();finish.get('m2')!();await visible.promise;await tick();
  });
  it('retries a failed thumbnail rather than retaining an unusable cache entry',async()=>{
    const loader=vi.fn().mockRejectedValueOnce(new Error('expired')).mockResolvedValue(image('fresh'));const cache=new ThumbnailCache(loader);
    await expect(cache.acquire(media('a'),0).promise).rejects.toThrow('expired');expect((await cache.acquire(media('a'),0).promise).src).toBe('fresh');
  });
});
