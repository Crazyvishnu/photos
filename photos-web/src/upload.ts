import { parse } from 'exifr';
import { MEDIA_MARKER, sourceAlbum, type Account, type DriveFile, type Media } from './model';
import { load, save } from './store';
import { checked, fields, json, listing, request, DriveError } from './drive';
import { ensureImageDerivatives } from './derivatives';
export type UploadProgress={phase:string;percent:number};
interface Session {email:string;fileId:string;hash:string;size:number;url?:string;capturedAt:number;dateSource:string}
export function rangeOffset(header:string|null,total:number){if(!header)return 0;const m=/^bytes=0-(\d+)$/.exec(header.trim());const offset=m ? Number(m[1])+1 : -1;if(offset<0||offset>total)throw new Error('Drive returned an invalid upload offset.');return offset;}
export async function hashFile(file:File,signal:AbortSignal,progress:(p:number)=>void):Promise<string>{
  return new Promise((resolve,reject)=>{const w=new Worker(new URL('./hash.worker.ts',import.meta.url),{type:'module'});const stop=()=>{w.terminate();signal.removeEventListener('abort',abort);};const abort=()=>{stop();reject(new DOMException('Upload paused','AbortError'));};if(signal.aborted){abort();return;}signal.addEventListener('abort',abort,{once:true});w.onerror=()=>{stop();reject(new Error('File hashing failed.'));};w.onmessage=({data})=>{if(data.hash){stop();resolve(data.hash);}else if(data.error){stop();reject(new Error(data.error));}else progress(data.progress);};w.postMessage(file);});
}
async function capture(file:File){if(file.type.startsWith('image/')){try{const info=await parse(file,{pick:['DateTimeOriginal','CreateDate']});const date=info?.DateTimeOriginal||info?.CreateDate;if(date instanceof Date&&Number.isFinite(date.getTime()))return {capturedAt:Math.floor(date.getTime()/1000),dateSource:'exif'};}catch{/* Unsupported metadata: use a clearly labelled file-modified fallback. */}}return {capturedAt:Math.floor((file.lastModified||Date.now())/1000),dateSource:'file_modified'};}
function confirmed(f:DriveFile,s:Session){return f.id===s.fileId&&!f.trashed&&Number(f.size)===s.size&&f.appProperties?.sha256===s.hash&&f.appProperties?.library===MEDIA_MARKER;}
async function finalizeMedia(file:File,media:Media,signal:AbortSignal,progress:(p:UploadProgress)=>void):Promise<Media>{
  if(!file.type.startsWith('image/')){progress({phase:'Backed up',percent:100});return media;}
  progress({phase:'Optimizing previews',percent:100});
  try{return await ensureImageDerivatives(file,media,signal);}
  catch(e){if(signal.aborted)throw e;console.warn('Derivative generation failed; original remains safely backed up.',e);return media;}
  finally{progress({phase:'Backed up',percent:100});}
}
export async function uploadOriginal(file:File,accounts:Account[],preferred:string,signal:AbortSignal,progress:(p:UploadProgress)=>void):Promise<Media>{
  if(!file.size||!/^(image|video)\//.test(file.type))throw new Error('Choose a non-empty photo or video with a supported media type.');
  progress({phase:'Reading original',percent:0});const hash=await hashFile(file,signal,p=>progress({phase:'Reading original',percent:Math.round(p*100)}));
  progress({phase:'Checking existing backups',percent:0});
  for(const account of accounts){const existing=(await listing(account.email,`appProperties has { key='library' and value='${MEDIA_MARKER}' } and appProperties has { key='sha256' and value='${hash}' }`))[0];if(existing){if(existing.trashed)throw new Error('This original is already in Bin. Restore it instead of uploading another copy.');return finalizeMedia(file,{...existing,email:account.email},signal,progress);}}
  const storageKey=`upload:${hash}`;let s=await load<Session|null>(storageKey,null);
  if(s&&!accounts.some(a=>a.email===s!.email))throw new Error('Reconnect the account owning this unfinished upload.');
  if(s){try{const f=await json<DriveFile>(s.email,`drive/v3/files/${s.fileId}?fields=${fields}`,{},signal);if(confirmed(f,s)){await save(storageKey,undefined);return finalizeMedia(file,{...f,email:s.email},signal,progress);}}catch(e){if(!(e instanceof DriveError&&e.status===404))throw e;}}
  if(!s){let selected:Account|undefined;for(const a of accounts.filter(a=>preferred==='auto'||a.email===preferred)) {const about=await json<{storageQuota:{limit?:string;usage:string}}>(a.email,'drive/v3/about?fields=storageQuota',{},signal);if(!about.storageQuota.limit||Number(about.storageQuota.limit)-Number(about.storageQuota.usage)>=file.size+1024*1024){selected=a;break;}}
    if(!selected)throw new Error('No selected account has enough space for this entire original.');
    const id=(await json<{ids:string[]}>(selected.email,'drive/v3/files/generateIds?count=1&space=drive&type=files',{},signal)).ids[0];s={email:selected.email,fileId:id,hash,size:file.size,...await capture(file)};await save(storageKey,s);
  }
  const row=s;let restarts=0;let offset=0;let probe=!!row.url;let failures=0;let chunk=1024*1024;
  while(true){
    signal.throwIfAborted();
    if(!row.url){const metadata={id:row.fileId,name:file.name,mimeType:file.type,appProperties:{library:MEDIA_MARKER,sha256:hash,capturedAt:String(row.capturedAt),dateSource:row.dateSource,sourceAlbum:sourceAlbum(file.name)}};
      const res=await request(row.email,`upload/drive/v3/files?uploadType=resumable&fields=${fields}`,{method:'POST',headers:{'Content-Type':'application/json','X-Upload-Content-Type':file.type,'X-Upload-Content-Length':String(file.size)},body:JSON.stringify(metadata)},signal);
      if(res.status===409){const f=await json<DriveFile>(row.email,`drive/v3/files/${row.fileId}?fields=${fields}`,{},signal);if(confirmed(f,row)){await save(storageKey,undefined);return finalizeMedia(file,{...f,email:row.email},signal,progress);}}
      await checked(res);row.url=res.headers.get('Location')||undefined;if(!row.url||new URL(row.url).origin!=='https://www.googleapis.com')throw new Error('Drive did not provide an accessible resumable upload session.');await save(storageKey,row);probe=false;offset=0;
    }
    const length=probe?0:Math.min(chunk,file.size-offset);const range=length?`bytes ${offset}-${offset+length-1}/${file.size}`:`bytes */${file.size}`;let res:Response;const start=performance.now();
    try{res=await request(row.email,row.url,{method:'PUT',redirect:'manual',headers:{'Content-Type':file.type,'Content-Range':range},body:length?file.slice(offset,offset+length):new Blob()},signal);}
    catch(e){if(signal.aborted||!navigator.onLine||!String(e).includes('fetch'))throw e;if(++failures>4)throw new Error('Upload interrupted. Retry to resume from Drive’s saved offset.');await new Promise(r=>setTimeout(r,Math.min(8000,500*2**failures)));probe=true;chunk=256*1024;continue;}
    if(res.ok){let f=await res.json().catch(()=>null) as DriveFile|null;if(!f||!confirmed(f,row))f=await json<DriveFile>(row.email,`drive/v3/files/${row.fileId}?fields=${fields}`,{},signal);if(!confirmed(f,row))throw new Error('Google has not confirmed this cloud copy. Retry to verify it.');await save(storageKey,undefined);return finalizeMedia(file,{...f,email:row.email},signal,progress);}
    if(res.status===308){const next=rangeOffset(res.headers.get('Range'),file.size);if(next<=offset&&!probe&&++failures>4)throw new Error('Drive is not acknowledging upload progress. Retry to resume.');if(next>offset)failures=0;const elapsed=performance.now()-start;if(length&&next===offset+length)chunk=Math.max(256*1024,Math.min(4*1024*1024,Math.floor((length*2500/Math.max(1,elapsed))/(256*1024))*256*1024,chunk*2));offset=next;probe=false;progress({phase:'Uploading original',percent:Math.floor(offset/file.size*100)});continue;}
    if((res.status===404||res.status===410)&&restarts++<2){row.url=undefined;await save(storageKey,row);continue;}
    if([408,429,500,502,503,504].includes(res.status)&&++failures<=4){await new Promise(r=>setTimeout(r,Math.min(8000,500*2**failures)));probe=true;chunk=256*1024;continue;}
    if(res.type==='opaqueredirect')throw new Error('The browser blocked Drive’s resumable redirect. The session is saved; retry or use Android backup.');
    await checked(res);throw new Error('Drive returned an unexpected upload response.');
  }
}
