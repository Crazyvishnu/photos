import { downloadImage } from './drive';
import { keyOf, type Media } from './model';

type Preview = { url:string; bytes:number };
type Entry = { media:Media; promise:Promise<Preview>; resolve:(p:Preview)=>void; reject:(e:unknown)=>void; controller:AbortController; started:boolean; value?:Preview };

// Session-only originals: never persist private photo bytes to browser storage.
export class PreviewCache {
  private entries=new Map<string,Entry>();
  private queue:Entry[]=[];
  private active=0;
  private pinned='';
  constructor(private loader:(m:Media,s:AbortSignal)=>Promise<Preview>,private revoke:(url:string)=>void=URL.revokeObjectURL,private budget=64*1024*1024,private maxEntries=8){}
  peek(m:Media){const key=keyOf(m);const entry=this.entries.get(key);if(entry?.value){this.touch(key,entry);return entry.value.url;}return '';}
  focus(m:Media,neighbors:Media[]){
    this.pinned=keyOf(m);
    const keep=new Set([this.pinned,...neighbors.map(keyOf)]);
    for(const [key,entry] of this.entries)if(!entry.value&&!keep.has(key))this.remove(key,entry);
    this.trim();
  }
  get(m:Media,priority=true):Promise<Preview>{
    const key=keyOf(m);let entry=this.entries.get(key);
    if(entry){this.touch(key,entry);if(priority&&!entry.started){this.queue=this.queue.filter(e=>e!==entry);this.queue.unshift(entry);}this.pump();return entry.promise;}
    let resolve!:(p:Preview)=>void,reject!:(e:unknown)=>void;
    const promise=new Promise<Preview>((yes,no)=>{resolve=yes;reject=no;});
    entry={media:m,promise,resolve,reject,controller:new AbortController(),started:false};
    this.entries.set(key,entry);if(priority)this.queue.unshift(entry);else this.queue.push(entry);this.pump();return promise;
  }
  prefetch(m:Media){if(this.queue.length<2&&m.mimeType.startsWith('image/')&&Number(m.size||0)<=12*1024*1024)void this.get(m,false).catch(()=>{});}
  release(){this.pinned='';this.trim();}
  clear(email?:string,id?:string){for(const [key,e] of this.entries)if((!email||e.media.email===email)&&(!id||e.media.id===id))this.remove(key,e);}
  private touch(key:string,e:Entry){this.entries.delete(key);this.entries.set(key,e);}
  private remove(key:string,e:Entry){
    this.entries.delete(key);this.queue=this.queue.filter(x=>x!==e);
    if(e.value)this.revoke(e.value.url);else{e.controller.abort();e.reject(new DOMException('Preview no longer needed','AbortError'));}
  }
  private trim(){
    let bytes=[...this.entries.values()].reduce((n,e)=>n+(e.value?.bytes||0),0);
    let count=[...this.entries.values()].filter(e=>e.value).length;
    for(const [key,e] of this.entries){if(bytes<=this.budget&&count<=this.maxEntries)break;if(e.value&&key!==this.pinned){bytes-=e.value.bytes;count--;this.remove(key,e);}}
  }
  private pump(){
    while(this.active<2&&this.queue.length){
      const e=this.queue.shift()!;const key=keyOf(e.media);e.started=true;this.active++;
      void this.loader(e.media,e.controller.signal).then(value=>{
        if(this.entries.get(key)!==e){this.revoke(value.url);return;}
        e.value=value;this.touch(key,e);e.resolve(value);this.trim();
      }).catch(error=>{if(this.entries.get(key)===e)this.entries.delete(key);e.reject(error);}).finally(()=>{this.active--;this.pump();});
    }
  }
}
export const photoPreviews=new PreviewCache(downloadImage);
