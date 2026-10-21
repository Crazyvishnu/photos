import { afterEach,describe,expect,it,vi } from 'vitest';
vi.mock('./auth',()=>({connected:()=>true,token:()=> 'test-token',disconnect:vi.fn()}));
import { loadThumbnail } from './thumbnail-cache';
import type { Media } from './model';
const m:Media={id:'file',email:'test@example.test',name:'test.jpg',mimeType:'image/jpeg',thumbnailLink:'https://lh3.googleusercontent.com/expired'};
afterEach(()=>vi.unstubAllGlobals());
describe('Expired preview recovery',()=>{
  it('fetches fresh metadata only after failure and never places a bearer token in thumbnail URLs',async()=>{
    const urls:string[]=[];
    class TestImage{onload:(()=>void)|null=null;onerror:(()=>void)|null=null;naturalWidth=100;naturalHeight=100;value='';set src(url:string){this.value=url;urls.push(url);queueMicrotask(()=>{if(url.endsWith('/expired'))this.onerror?.();else this.onload?.();});}get src(){return this.value;}removeAttribute(){}}
    vi.stubGlobal('Image',TestImage);const fetch=vi.fn(async()=>new Response(JSON.stringify({thumbnailLink:'https://lh3.googleusercontent.com/fresh'}),{headers:{'Content-Type':'application/json'}}));vi.stubGlobal('fetch',fetch);
    const result=await loadThumbnail(m,new AbortController().signal,0);
    expect(result.src).toBe('https://lh3.googleusercontent.com/fresh');expect(urls).toEqual([m.thumbnailLink,'https://lh3.googleusercontent.com/fresh']);
    const [url,init]=fetch.mock.calls[0] as unknown as [URL,RequestInit];expect(url.origin).toBe('https://www.googleapis.com');expect(url.searchParams.get('fields')).toBe('thumbnailLink');expect(new Headers(init.headers).get('Authorization')).toBe('Bearer test-token');expect(urls.join(' ')).not.toContain('test-token');
  });
  it('prefers the authenticated grid derivative over an expiring Drive thumbnail',async()=>{
    class TestImage{onload:(()=>void)|null=null;onerror:(()=>void)|null=null;naturalWidth=512;naturalHeight=384;dataset:Record<string,string>={};value='';set src(url:string){this.value=url;queueMicrotask(()=>this.onload?.());}get src(){return this.value;}removeAttribute(){}}
    vi.stubGlobal('Image',TestImage);
    const create=vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:derivative');
    const revoke=vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{});
    const fetch=vi.fn(async()=>new Response(new Blob(['preview'],{type:'image/webp'}),{headers:{'Content-Type':'image/webp'}}));vi.stubGlobal('fetch',fetch);
    const derivative={...m,appProperties:{gridDerivativeId:'grid-file'}};
    const result=await loadThumbnail(derivative,new AbortController().signal,0);
    expect(result.src).toBe('blob:derivative');expect(result.dataset.objectUrl).toBe('blob:derivative');expect(create).toHaveBeenCalled();
    const [url,init]=fetch.mock.calls[0] as unknown as [URL,RequestInit];
    expect(url.pathname).toBe('/drive/v3/files/grid-file');expect(url.searchParams.get('alt')).toBe('media');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer test-token');expect(revoke).not.toHaveBeenCalled();
    create.mockRestore();revoke.mockRestore();
  });
  it('does not fetch metadata again when the current thumbnail is usable',async()=>{
    class TestImage{onload:(()=>void)|null=null;onerror:(()=>void)|null=null;value='';set src(url:string){this.value=url;queueMicrotask(()=>this.onload?.());}get src(){return this.value;}removeAttribute(){}}
    vi.stubGlobal('Image',TestImage);const fetch=vi.fn();vi.stubGlobal('fetch',fetch);await loadThumbnail(m,new AbortController().signal,0);expect(fetch).not.toHaveBeenCalled();
  });
});
