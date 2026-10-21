import { connected, disconnect, token } from './auth';
import { ALBUM_MARKER, MEDIA_MARKER, isMedia, validateAlbum, type Account, type Album, type Cache, type DriveFile, type Media } from './model';
import { load, save } from './store';
const ROOT='https://www.googleapis.com/';
export const fields='id,name,mimeType,size,createdTime,thumbnailLink,trashed,appProperties,imageMediaMetadata(width,height,time),videoMediaMetadata(durationMillis)';
export class DriveError extends Error { constructor(public status:number,public reason:string,message:string) { super(message); } }
export async function request(email:string, path:string, init:RequestInit={}, signal?:AbortSignal):Promise<Response> {
  const url=new URL(path,ROOT);if(url.origin!==new URL(ROOT).origin) throw new Error('Unexpected Drive endpoint.');
  let res:Response;
  try { res=await fetch(url,{...init,headers:{...Object.fromEntries(new Headers(init.headers)),Authorization:`Bearer ${token(email)}`},signal:signal ? AbortSignal.any([signal,AbortSignal.timeout(150000)]) : AbortSignal.timeout(150000)}); }
  catch(e){ if(signal?.aborted) throw new DOMException('Upload paused','AbortError'); if(!navigator.onLine) throw new Error('You are offline. Reconnect and retry.'); throw e; }
  if(res.status===401){disconnect(email);throw new Error(`Reconnect ${email}; Google authorization expired.`);}
  return res;
}
export async function checked(res:Response) { if(res.ok) return res; const body=await res.json().catch(()=>null);const reason=body?.error?.errors?.[0]?.reason || 'requestFailed'; throw new DriveError(res.status,reason,body?.error?.message || `Google Drive rejected the request (${res.status}).`); }
export async function json<T>(email:string,path:string,init:RequestInit={},signal?:AbortSignal):Promise<T>{return (await checked(await request(email,path,init,signal))).json();}
export async function listing(email:string,q:string):Promise<DriveFile[]> { const files:DriveFile[]=[];let page:string|undefined; do{const p=new URLSearchParams({q,fields:`nextPageToken,files(${fields})`,pageSize:'1000',...(page?{pageToken:page}:{})});const r=await json<{files?:DriveFile[];nextPageToken?:string}>(email,`drive/v3/files?${p}`);files.push(...(r.files||[]));page=r.nextPageToken;}while(page);return files; }
const marked=`(appProperties has { key='library' and value='${MEDIA_MARKER}' } or appProperties has { key='library' and value='${ALBUM_MARKER}' })`;
async function readAlbum(email:string,f:DriveFile){if(Number(f.size)>5*1024*1024) throw new Error(`Album ${f.name} is too large.`);return validateAlbum(await json(email,`drive/v3/files/${encodeURIComponent(f.id)}?alt=media`),email,f.id);}
export async function refreshAccount(account:Account):Promise<{account:Account;cache:Cache}> {
  const email=account.email;let cache=await load<Cache>(`cache:${email}`,{media:[],albums:[]});
  const about=await json<{storageQuota:{limit?:string;usage:string}}>(email,'drive/v3/about?fields=storageQuota');
  const nextAccount={...account,limit:about.storageQuota.limit ? Number(about.storageQuota.limit):null,usage:Number(about.storageQuota.usage)||0};
  if(!cache.cursor){
    const start=await json<{startPageToken:string}>(email,'drive/v3/changes/startPageToken');
    const files=await listing(email,marked);const albums:Album[]=[];
    for(const f of files.filter(f=>f.appProperties?.library===ALBUM_MARKER&&!f.trashed)) albums.push(await readAlbum(email,f));
    cache={media:files.filter(isMedia).map(f=>({...f,email})),albums,cursor:start.startPageToken};
  }
  // Commit a cursor only together with the complete, successfully processed result.
  const media=new Map(cache.media.map(f=>[f.id,f])); const albums=new Map(cache.albums.map(a=>[a.fileId,a]));let cursor=cache.cursor;
  try { do{
    const p=new URLSearchParams({pageToken:cursor!,fields:`nextPageToken,newStartPageToken,changes(fileId,removed,file(${fields}))`,pageSize:'1000'});
    const r=await json<{changes:{fileId:string;removed?:boolean;file?:DriveFile}[];nextPageToken?:string;newStartPageToken?:string}>(email,`drive/v3/changes?${p}`);
    for(const c of r.changes){const f=c.file;if(c.removed){media.delete(c.fileId);albums.delete(c.fileId);}else if(f){if(isMedia(f))media.set(f.id,{...f,email});else media.delete(f.id);if(f.appProperties?.library===ALBUM_MARKER&&!f.trashed)albums.set(f.id,await readAlbum(email,f));else albums.delete(f.id);}}
    cursor=r.nextPageToken||r.newStartPageToken||cursor;
    if(!r.nextPageToken) break;
  }while(true); }catch(e){if(e instanceof DriveError&&e.status===410)await save(`cache:${email}`,{...cache,cursor:undefined});throw e;}
  const result={media:[...media.values()],albums:[...albums.values()],cursor};await save(`cache:${email}`,result);return {account:nextAccount,cache:result};
}
export async function patch(m:Media,body:object){return json<DriveFile>(m.email,`drive/v3/files/${encodeURIComponent(m.id)}?fields=${fields}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});}
export async function changeMedia(m:Media,kind:'favorite'|'date'|'trash'|'restore'|'delete',date?:number):Promise<Media|null>{
  if(kind==='date'&&(date===undefined||!Number.isFinite(date)||Math.abs(date)>8640000000000000))throw new Error('Choose a valid capture date.');
  // Read the current version before a date/favorite edit to avoid a stale browser timestamp.
  const current=await json<DriveFile>(m.email,`drive/v3/files/${encodeURIComponent(m.id)}?fields=${fields}`);
  if(current.appProperties?.library!==MEDIA_MARKER)throw new Error('This file is not part of the app library.');
  if(kind==='delete'){if(!current.trashed)throw new Error('This item was restored on another device. Refresh before deleting.');await checked(await request(m.email,`drive/v3/files/${encodeURIComponent(m.id)}`,{method:'DELETE'}));return null;}
  const props=current.appProperties||{};
  const body=kind==='favorite'?{appProperties:{favorite:String(props.favorite!=='true'),favoriteEditedAt:String(Math.max(Date.now(),Number(props.favoriteEditedAt||0)+1))}}:
    kind==='date'?{appProperties:{capturedAt:String(Math.floor(date!/1000)),dateEditedAt:String(Math.max(Date.now(),Number(props.dateEditedAt||0)+1)),dateSource:'edited'}}:{trashed:kind==='trash'};
  return {...await patch(m,body),email:m.email};
}
export async function saveAlbum(album:Album):Promise<Album>{
  if(album.fileId){const f=await json<DriveFile>(album.email,`drive/v3/files/${album.fileId}?fields=appProperties`);if(Number(f.appProperties?.updatedAt)>album.updatedAt)throw new Error('This album changed on another device. Refresh before editing.');}
  const updated={...album,updatedAt:Math.max(Date.now(),album.updatedAt+1)};
  const id=album.fileId || (await json<{ids:string[]}>(album.email,'drive/v3/files/generateIds?count=1&space=drive&type=files')).ids[0];
  const boundary=`photos_${crypto.randomUUID()}`;
  const metadata={...(album.fileId?{}:{id}),name:`Photos album - ${album.title}`,mimeType:'application/json',appProperties:{library:ALBUM_MARKER,albumId:album.id,updatedAt:String(updated.updatedAt)}};
  const document={version:1,id:updated.id,title:updated.title,createdAt:updated.createdAt,updatedAt:updated.updatedAt,members:updated.members,deleted:updated.deleted};
  const body=`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(document)}\r\n--${boundary}--`;
  const path=`upload/drive/v3/files${album.fileId?`/${id}`:''}?uploadType=multipart&fields=id`;
  await json(album.email,path,{method:album.fileId?'PATCH':'POST',headers:{'Content-Type':`multipart/related; boundary=${boundary}`},body});return {...updated,fileId:id};
}
export async function downloadImage(m:Media,signal:AbortSignal):Promise<{url:string;bytes:number}>{
  if(Number(m.size)>100*1024*1024)throw new Error('This image is too large for an in-browser preview. Open it in Drive.');
  const deadline=AbortSignal.timeout(30000);
  try{
    const response=await checked(await request(m.email,`drive/v3/files/${encodeURIComponent(m.id)}?alt=media`,{},AbortSignal.any([signal,deadline])));
    const blob=await response.blob();return {url:URL.createObjectURL(blob),bytes:blob.size};
  }catch(e){if(deadline.aborted)throw new Error('The original is taking too long to download. Retry or open it in Drive.');throw e;}
}
export function allConnected(accounts:Account[]){return accounts.length>0&&accounts.every(a=>connected(a.email));}
