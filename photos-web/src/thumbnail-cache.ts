import { connected } from './auth';
import { json } from './drive';
import { GRID_DERIVATIVE_KEY, keyOf, type Media } from './model';
import { derivativeBlob } from './derivative-cache';

type Entry={media:Media;priority:number;users:number;started:boolean;controller:AbortController;promise:Promise<HTMLImageElement>;resolve:(image:HTMLImageElement)=>void;reject:(error:unknown)=>void;image?:HTMLImageElement};
export class ThumbnailCache {
  private entries=new Map<string,Entry>();
  private queue:Entry[]=[];
  private active=0;
  private scheduled=false;
  constructor(private loader:(m:Media,s:AbortSignal,priority:number)=>Promise<HTMLImageElement>,private concurrency=12,private budget=96*1024*1024,private capacity=600){}
  peek(m:Media){return this.entries.get(keyOf(m))?.image;}
  acquire(m:Media,priority=0){
    const key=keyOf(m);let entry=this.entries.get(key);
    if(!entry){let resolve!:(image:HTMLImageElement)=>void,reject!:(error:unknown)=>void;const promise=new Promise<HTMLImageElement>((yes,no)=>{resolve=yes;reject=no;});entry={media:m,priority,users:0,started:false,controller:new AbortController(),promise,resolve,reject};this.entries.set(key,entry);this.queue.push(entry);}
    const e=entry;e.users++;e.priority=Math.min(e.priority,priority);this.touch(key,e);this.schedule();let released=false;
    return {promise:e.promise,promote:()=>{e.priority=0;this.schedule();},release:()=>{if(released)return;released=true;e.users--;if(!e.users&&!e.image)this.remove(key,e);this.trim();}};
  }
  clear(email?:string,id?:string){for(const [key,e] of this.entries)if((!email||e.media.email===email)&&(!id||e.media.id===id))this.remove(key,e);}
  warm(items:Media[],limit=600){
    const wanted=new Set(items.slice(0,limit).map(keyOf));
    // Cancel only old background work; never disturb thumbnails currently mounted in the UI.
    for(const [key,e] of this.entries)if(!wanted.has(key)&&!e.users&&!e.image)this.remove(key,e);
    for(const m of items.slice(0,limit)){
      const key=keyOf(m);if(this.entries.has(key))continue;
      let resolve!:(image:HTMLImageElement)=>void,reject!:(error:unknown)=>void;
      const promise=new Promise<HTMLImageElement>((yes,no)=>{resolve=yes;reject=no;});
      const entry:Entry={media:m,priority:2,users:0,started:false,controller:new AbortController(),promise,resolve,reject};
      // Background warming is best-effort; avoid unhandled rejection noise for expired links.
      void promise.catch(()=>{});
      this.entries.set(key,entry);this.queue.push(entry);
    }
    this.schedule();
  }

  private touch(key:string,e:Entry){this.entries.delete(key);this.entries.set(key,e);}
  private remove(key:string,e:Entry){if(this.entries.get(key)!==e)return;this.entries.delete(key);this.queue=this.queue.filter(x=>x!==e);if(e.image?.dataset?.objectUrl)URL.revokeObjectURL(e.image.dataset.objectUrl);if(!e.image){e.controller.abort();e.reject(new DOMException('Thumbnail left the viewport','AbortError'));}}
  private trim(){let bytes=0,count=0;for(const e of this.entries.values())if(e.image){bytes+=e.image.naturalWidth*e.image.naturalHeight*4;count++;}for(const [key,e] of this.entries){if(bytes<=this.budget&&count<=this.capacity)break;if(e.image&&!e.users){bytes-=e.image.naturalWidth*e.image.naturalHeight*4;count--;this.remove(key,e);}}}
  private schedule(){if(this.scheduled)return;this.scheduled=true;queueMicrotask(()=>{this.scheduled=false;this.pump();});}
  private pump(){
    this.queue.sort((a,b)=>a.priority-b.priority);
    while(this.active<this.concurrency&&this.queue.length){const e=this.queue.shift()!;e.started=true;this.active++;const key=keyOf(e.media);
      void this.loader(e.media,e.controller.signal,e.priority).then(image=>{if(this.entries.get(key)!==e)return;e.image=image;this.touch(key,e);e.resolve(image);this.trim();}).catch(error=>{if(this.entries.get(key)===e)this.entries.delete(key);e.reject(error);}).finally(()=>{this.active--;this.pump();});
    }
  }
}

async function readBlobImage(blob:Blob,signal:AbortSignal,priority:number):Promise<HTMLImageElement>{
  const url=URL.createObjectURL(blob);
  try{const image=await readImage(url,signal,priority);image.dataset.objectUrl=url;return image;}
  catch(e){URL.revokeObjectURL(url);throw e;}
}
function readImage(url:string,signal:AbortSignal,priority:number):Promise<HTMLImageElement>{
  return new Promise((resolve,reject)=>{
    if(signal.aborted){reject(new DOMException('Aborted','AbortError'));return;}
    const image=new Image();image.decoding='async';image.referrerPolicy='no-referrer';image.fetchPriority=priority===0?'high':'low';
    const clean=()=>{clearTimeout(timer);image.onload=null;image.onerror=null;signal.removeEventListener('abort',abort);};
    const fail=(error:unknown)=>{clean();image.removeAttribute('src');reject(error);};
    const abort=()=>fail(new DOMException('Aborted','AbortError'));
    const timer=setTimeout(()=>fail(new Error('Preview request timed out.')),8000);
    image.onload=()=>{clean();resolve(image);};image.onerror=()=>fail(new Error('Preview link is unavailable.'));
    signal.addEventListener('abort',abort,{once:true});image.src=url;
  });
}
export async function loadThumbnail(m:Media,signal:AbortSignal,priority:number){
  const derivativeId=m.appProperties?.[GRID_DERIVATIVE_KEY];
  if(derivativeId&&connected(m.email)){try{return await readBlobImage(await derivativeBlob(m.email,derivativeId,signal),signal,priority);}catch(e){if(signal.aborted)throw e;}}
  if(m.thumbnailLink){try{return await readImage(m.thumbnailLink,signal,priority);}catch(e){if(signal.aborted)throw e;}}
  if(!connected(m.email))throw new Error('Reconnect this account to refresh its preview.');
  // Drive thumbnail links expire without a media change. Refresh only this visible file.
  const fresh=await json<{thumbnailLink?:string}>(m.email,`drive/v3/files/${encodeURIComponent(m.id)}?fields=thumbnailLink`,{},AbortSignal.any([signal,AbortSignal.timeout(8000)]));
  if(!fresh.thumbnailLink)throw new Error('Google Drive has not generated a preview for this file yet.');
  return readImage(fresh.thumbnailLink,signal,priority);
}
export const thumbnails=new ThumbnailCache(loadThumbnail);
