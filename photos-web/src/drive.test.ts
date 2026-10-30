import { beforeEach, describe, expect, it, vi } from 'vitest';
const memory=vi.hoisted(()=>new Map<string,unknown>());
vi.mock('./store',()=>({load:async(key:string,fallback:unknown)=>structuredClone(memory.get(key)??fallback),save:async(key:string,value:unknown)=>{memory.set(key,structuredClone(value));}}));
vi.mock('./auth',()=>({backendEnabled:false,token:()=> 'test-only-token',connected:()=>true,disconnect:vi.fn()}));
import { request, refreshAccount, changeMedia, downloadImage } from './drive';
import { MEDIA_MARKER, capturedAt, ordered, groups, type Cache, type Account, type Media } from './model';
const account:Account={email:'test@example.test',name:'Test account',limit:1000000,usage:0};
const original:Media={id:'photo-1',email:account.email,name:'one.jpg',mimeType:'image/jpeg',appProperties:{library:MEDIA_MARKER,capturedAt:'1640995200'}};
function reply(value:unknown,status=200){return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});}
beforeEach(()=>{memory.clear();vi.unstubAllGlobals();});
describe('Private Drive sync',()=>{
  it('reconciles stale cached dates even when the saved change cursor reports no edits',async()=>{
    memory.set(`cache:${account.email}`,{media:[original],albums:[],cursor:'stale-cursor'});
    const edited={...original,appProperties:{...original.appProperties,capturedAt:'1577880000',dateEditedAt:'1793340000000'}};
    vi.stubGlobal('fetch',vi.fn(async(input:URL)=>{const u=new URL(String(input));
      if(u.pathname.endsWith('/about'))return reply({storageQuota:{usage:'0'}});
      if(u.pathname.endsWith('/startPageToken'))return reply({startPageToken:'fresh-start'});
      if(u.pathname.endsWith('/files'))return reply({files:[edited]});
      expect(u.searchParams.get('pageToken')).toBe('fresh-start');return reply({changes:[],newStartPageToken:'fresh-end'});
    }));
    const result=await refreshAccount(account,true);expect(capturedAt(result.cache.media[0])).toBe(1577880000000);expect(result.cache.cursor).toBe('fresh-end');
  });
  it('keeps the usable cached dates if reconciliation cannot finish listing',async()=>{
    memory.set(`cache:${account.email}`,{media:[original],albums:[],cursor:'old-cursor'});
    vi.stubGlobal('fetch',vi.fn(async(input:URL)=>{const u=new URL(String(input));
      if(u.pathname.endsWith('/about'))return reply({storageQuota:{usage:'0'}});
      if(u.pathname.endsWith('/startPageToken'))return reply({startPageToken:'new-start'});
      if(!u.searchParams.has('pageToken'))return reply({files:[],nextPageToken:'broken'});
      return reply({error:{message:'Connection interrupted'}},503);
    }));
    await expect(refreshAccount(account,true)).rejects.toThrow('Connection interrupted');
    const cache=memory.get(`cache:${account.email}`) as Cache;expect(cache.cursor).toBe('old-cursor');expect(cache.media).toEqual([original]);
  });
  it('replaces a cached date with a phone edit and moves the photo to its corrected day',async()=>{
    const edited={...original,appProperties:{...original.appProperties,capturedAt:'1577880000',dateEditedAt:'1793340000000',dateSource:'edited'}};
    const other={...original,id:'other',appProperties:{...original.appProperties,capturedAt:'1609502400'}};
    memory.set(`cache:${account.email}`,{media:[original,other],albums:[],cursor:'before-phone-edit'});
    const fetch=vi.fn(async(input:URL)=>{const u=new URL(String(input));if(u.pathname.endsWith('/about'))return reply({storageQuota:{usage:'0'}});expect(u.searchParams.get('fields')).toContain('appProperties');return reply({changes:[{fileId:original.id,file:edited}],newStartPageToken:'after-phone-edit'});});
    vi.stubGlobal('fetch',fetch);
    const result=await refreshAccount(account);const timeline=ordered(result.cache.media,'newest');
    expect(timeline.map(m=>m.id)).toEqual(['other',original.id]);expect(capturedAt(timeline[1])).toBe(1577880000000);
    expect(groups(timeline)).toHaveLength(2);expect(timeline[1].appProperties?.dateSource).toBe('edited');
    expect((memory.get(`cache:${account.email}`) as Cache).media.find(m=>m.id===original.id)?.appProperties?.capturedAt).toBe('1577880000');
    await refreshAccount(account);expect(fetch).toHaveBeenCalledTimes(4);
  });
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
