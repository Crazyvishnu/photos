export const MEDIA_MARKER = 'photosapp.library.v1';
export const ALBUM_MARKER = 'photosapp.albums.v1';
export const DERIVATIVE_MARKER = 'photosapp.derivative.v1';
export const DERIVATIVE_FOLDER_MARKER = 'photosapp.derivative.folder.v1';
export const DERIVATIVE_VERSION = '1';
export const GRID_DERIVATIVE_KEY = 'gridDerivativeId';
export const SCREEN_DERIVATIVE_KEY = 'screenDerivativeId';
export interface DriveFile { id: string; name: string; mimeType: string; size?: string; createdTime?: string; thumbnailLink?: string; trashed?: boolean; appProperties?: Record<string,string>; imageMediaMetadata?: { width?: number; height?: number; time?: string }; videoMediaMetadata?: { durationMillis?: string }; }
export interface Media extends DriveFile { email: string }
export interface Account { email: string; name: string; picture?: string; limit: number | null; usage: number }
export interface Album { id: string; title: string; createdAt: number; updatedAt: number; members: string[]; deleted: boolean; fileId?: string; email: string }
export interface Cache { media: Media[]; albums: Album[]; cursor?: string }
export interface Settings { theme: 'system'|'light'|'dark'; density: 'comfortable'|'compact'; sort: 'newest'|'oldest'; refresh: boolean; badges: boolean; uploadAccount: string; clientId: string }
export const defaultSettings: Settings = { theme:'system', density:'comfortable', sort:'newest', refresh:true, badges:true, uploadAccount:'auto', clientId: import.meta.env.VITE_GOOGLE_CLIENT_ID || '163251818347-4vqh8gvrdp546qaj8b3ic1qvk994j8n8.apps.googleusercontent.com' };
export const keyOf = (m: Media) => `drive:${m.email}:${m.id}`;
export const capturedAt = (m: Media) => { const raw=m.appProperties?.capturedAt;const n=Number(raw); return raw!==undefined&&raw!==''&&Number.isFinite(n) ? n*1000 : Date.parse(m.createdTime || '') || 0; };
export const favorite = (m: Media) => m.appProperties?.favorite === 'true';
export const isMedia = (f: DriveFile) => f.appProperties?.library===MEDIA_MARKER && /^(image|video)\//.test(f.mimeType);
export function bytes(n: number) { if (!n) return '0 B'; const i=Math.min(4,Math.floor(Math.log(n)/Math.log(1000))); return `${(n/1000**i).toFixed(i ? 1 : 0)} ${['B','KB','MB','GB','TB'][i]}`; }
export function ordered(items: Media[], sort: Settings['sort']) { return [...items].sort((a,b)=>(sort==='newest' ? -1:1)*(capturedAt(a)-capturedAt(b)) || keyOf(a).localeCompare(keyOf(b))); }
export function groups(items: Media[]) { const result: { label:string; items:Media[] }[]=[]; for(const m of items) { const d=new Date(capturedAt(m)); const label=capturedAt(m) ? d.toLocaleDateString(undefined,{weekday:'short',year:'numeric',month:'long',day:'numeric'}) : 'Date unknown'; const last=result.at(-1); if(last?.label===label) last.items.push(m); else result.push({label,items:[m]}); } return result; }
export const sourceAlbum = (name: string) => /screenshot|screen_/i.test(name) ? 'screenshots' : /document|scan|receipt/i.test(name) ? 'documents' : '';
export function validateAlbum(raw: unknown, email: string, fileId: string): Album {
  const a=raw as Album;
  if(!a || typeof a.id!=='string' || !/^[\w-]{1,100}$/.test(a.id) || typeof a.title!=='string' || !Array.isArray(a.members) || a.members.length>100000 || !a.members.every(x=>typeof x==='string' && x.startsWith('drive:')) || !Number.isFinite(a.updatedAt)) throw new Error('Invalid cloud album');
  return {id:a.id,title:a.title.slice(0,100),createdAt:Number(a.createdAt)||a.updatedAt,updatedAt:a.updatedAt,members:[...new Set(a.members)],deleted:a.deleted===true,email,fileId};
}
