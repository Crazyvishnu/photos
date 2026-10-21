import { beforeEach, describe, expect, it, vi } from 'vitest';
const memory=vi.hoisted(()=>new Map<string,unknown>());
vi.mock('./store',()=>({load:async(key:string,fallback:unknown)=>structuredClone(memory.get(key)??fallback),save:async(key:string,value:unknown)=>{memory.set(key,structuredClone(value));}}));
vi.mock('./auth',()=>({token:()=> 'test-only-token',connected:()=>true,disconnect:vi.fn()}));
import { request, refreshAccount, changeMedia, downloadImage } from './drive';
import { MEDIA_MARKER, type Cache, type Account, type Media } from './model';
const account:Account={email:'test@example.test',name:'Test account',limit:1000000,usage:0};
const original:Media={id:'photo-1',email:account.email,name:'one.jpg',mimeType:'image/jpeg',appProperties:{library:MEDIA_MARKER,capturedAt:'1640995200'}};
function reply(value:unknown,status=200){return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});}
beforeEach(()=>{memory.clear();vi.unstubAllGlobals();});
describe('Private Drive sync',()=>{
  it('bounds stalled original retrieval and returns a useful retry error',async()=>{
    const deadline=new AbortController();const timeout=vi.spyOn(AbortSignal,'timeout').mockImplementation(ms=>ms===30000?deadline.signal:new AbortController().signal);
    vi.stubGlobal('fetch',vi.fn((_input:unknown,init:RequestInit)=>new Promise((_resolve,reject)=>{init.signal!.addEventListener('abort',()=>reject(new DOMException('Timed out','AbortError')),{once:true});})));
    try{const result=downloadImage(original,new AbortController().signal);const rejected=expect(result).rejects.toThrow('original is taking too long');deadline.abort();await rejected;}finally{timeout.mockRestore();}
  });
  it('does not send bearer tokens to an unexpected host',async()=>{const fetch=vi.fn();vi.stubGlobal('fetch',fetch);await expect(request(account.email,'https://evil.example.test/steal')).rejects.toThrow('Unexpected Drive endpoint');expect(fetch).not.toHaveBeenCalled();});
  it('commits paginated changes and the final cursor together',async()=>{
    memory.set(`cache:${account.email}`,{media:[original],albums:[],cursor:'start'});
    vi.stubGlobal('fetch',vi.fn(async(input:URL)=>{const u=new URL(String(input));if(u.pathname.endsWith('/about'))return reply({storageQuota:{limit:'1000000',usage:'42'}});if(u.searchParams.get('pageToken')==='start')return reply({changes:[{fileId:original.id,removed:true}],nextPageToken:'next'});return reply({changes:[{fileId:'photo-2',file:{...original,id:'photo-2',name:'two.jpg'}}],newStartPageToken:'end'});}));
    const result=await refreshAccount(account);expect(result.cache.cursor).toBe('end');expect(result.cache.media.map(x=>x.id)).toEqual(['photo-2']);expect((memory.get(`cache:${account.email}`) as Cache).cursor).toBe('end');
  });
  it('preserves the last good index and cursor when a later page fails',async()=>{
    memory.set(`cache:${account.email}`,{media:[original],albums:[],cursor:'start'});
    vi.stubGlobal('fetch',vi.fn(async(input:URL)=>{const u=new URL(String(input));if(u.pathname.endsWith('/about'))return reply({storageQuota:{usage:'0'}});if(u.searchParams.get('pageToken')==='start')return reply({changes:[{fileId:original.id,removed:true}],nextPageToken:'bad'});return reply({error:{message:'Server unavailable'}},503);}));
    await expect(refreshAccount(account)).rejects.toThrow('Server unavailable');const saved=memory.get(`cache:${account.email}`) as Cache;expect(saved.cursor).toBe('start');expect(saved.media).toHaveLength(1);
  });
  it('refuses permanent deletion if another device already restored the item',async()=>{const fetch=vi.fn(async()=>reply(original));vi.stubGlobal('fetch',fetch);await expect(changeMedia({...original,trashed:true},'delete')).rejects.toThrow('restored on another device');expect(fetch).toHaveBeenCalledTimes(1);});
  it('refuses mutation of unrelated Drive files',async()=>{const fetch=vi.fn(async()=>reply({...original,appProperties:{}}));vi.stubGlobal('fetch',fetch);await expect(changeMedia(original,'trash')).rejects.toThrow('not part of the app library');expect(fetch).toHaveBeenCalledTimes(1);});
});
