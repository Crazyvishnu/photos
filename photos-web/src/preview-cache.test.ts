import { describe,expect,it,vi } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { PreviewCache } from './preview-cache';
import { PhotoGrid } from './PhotoGrid';
import type { Media } from './model';
const photo=(id:string,email='test@example.test'):Media=>({id,email,name:`${id}.jpg`,mimeType:'image/jpeg',size:'5',createdTime:'2026-10-20T12:00:00Z'});
const settle=()=>new Promise(resolve=>setTimeout(resolve,0));

describe('Original preview cache',()=>{
  it('shares an in-flight download and reuses the completed original',async()=>{
    const loader=vi.fn(async()=>({url:'blob:original',bytes:5}));const cache=new PreviewCache(loader,vi.fn());
    const first=cache.get(photo('a'));expect(cache.get(photo('a'))).toBe(first);await first;
    expect(cache.peek(photo('a'))).toBe('blob:original');expect((await cache.get(photo('a'))).url).toBe('blob:original');expect(loader).toHaveBeenCalledTimes(1);
  });
  it('limits transfers to two and prioritizes the selected photo over queued prefetches',async()=>{
    const finishes=new Map<string,()=>void>();const calls:string[]=[];
    const cache=new PreviewCache(m=>new Promise(resolve=>{calls.push(m.id);finishes.set(m.id,()=>resolve({url:`blob:${m.id}`,bytes:5}));}),vi.fn());
    const a=cache.get(photo('a')),b=cache.get(photo('b'));cache.prefetch(photo('c'));const selected=cache.get(photo('selected'));
    expect(calls).toEqual(['a','b']);finishes.get('a')!();await a;await settle();expect(calls).toEqual(['a','b','selected']);
    finishes.get('selected')!();await selected;await settle();finishes.get('b')!();finishes.get('c')!();await b;await settle();
  });
  it('revokes least recently used originals while keeping the active photo available',async()=>{
    const revoked:string[]=[];const cache=new PreviewCache(async m=>({url:`blob:${m.id}`,bytes:5}),u=>revoked.push(u),10,2);
    cache.focus(photo('a'),[]);await cache.get(photo('a'));await cache.get(photo('b'));await cache.get(photo('c'));
    expect(cache.peek(photo('a'))).toBe('blob:a');expect(cache.peek(photo('b'))).toBe('');expect(revoked).toEqual(['blob:b']);expect(cache.peek(photo('c'))).toBe('blob:c');
    cache.clear();expect(revoked.sort()).toEqual(['blob:a','blob:b','blob:c']);
  });
  it('allows retry after failure and isolates accounts with the same file ID',async()=>{
    const loader=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue({url:'blob:ok',bytes:5});const cache=new PreviewCache(loader,vi.fn());
    await expect(cache.get(photo('a'))).rejects.toThrow('offline');await cache.get(photo('a'));expect(cache.peek(photo('a','other@example.test'))).toBe('');
    await cache.get(photo('a','other@example.test'));expect(loader).toHaveBeenCalledTimes(3);
  });
  it('cancels abandoned transfers and disposes originals completed after removal',async()=>{
    let finish!:(p:{url:string;bytes:number})=>void;const revoked=vi.fn();const cache=new PreviewCache(()=>new Promise(resolve=>{finish=resolve;}),revoked);
    const result=cache.get(photo('a'));const rejected=expect(result).rejects.toThrow('Preview no longer needed');cache.clear('test@example.test');await rejected;
    finish({url:'blob:late',bytes:5});await settle();expect(revoked).toHaveBeenCalledWith('blob:late');expect(cache.peek(photo('a'))).toBe('');
  });
});

it('renders all 4,450 photos and videos without a show-more step',()=>{
  const items=Array.from({length:4450},(_,i)=>({...photo(String(i)),mimeType:i%2?'image/jpeg':'video/mp4'}));
  const html=renderToStaticMarkup(createElement(PhotoGrid,{items,selection:new Set<string>(),badges:true,onOpen:()=>{},onToggle:()=>{},onSelectDay:()=>{}}));
  expect((html.match(/class="photo-open"/g)||[]).length).toBe(4450);expect(html).toContain('Open 4449.jpg');expect(html).not.toContain('Show more');
  expect(html).not.toContain('<img');
});
