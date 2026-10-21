import { describe,it,expect } from 'vitest';
import { createHash } from 'node:crypto';
import { digestBlob } from './digest';
import { rangeOffset } from './upload';
import { capturedAt, ordered, isMedia, validateAlbum, MEDIA_MARKER, type Media } from './model';
describe('Original-byte hashing',()=>{
  it('matches SHA-256 across multiple chunks without reading the entire file at once',async()=>{
    const data=new Uint8Array(5*1024*1024+37);for(let i=0;i<data.length;i++)data[i]=i%251;
    const blob=new Blob([data]);blob.arrayBuffer=()=>{throw new Error('Whole-file allocation is forbidden');};const progress:number[]=[];
    expect(await digestBlob(blob,p=>progress.push(p))).toBe(createHash('sha256').update(data).digest('hex'));expect(progress.length).toBe(3);expect(progress.at(-1)).toBe(1);
  });
  it('handles empty bytes deterministically',async()=>{expect(await digestBlob(new Blob([]))).toBe(createHash('sha256').digest('hex'));});
});
describe('Drive upload acknowledgments',()=>{
  it('resumes only from the server-acknowledged range',()=>{expect(rangeOffset('bytes=0-262143',1000000)).toBe(262144);expect(rangeOffset(null,1000000)).toBe(0);});
  it('rejects malformed and impossible offsets',()=>{for(const range of ['bytes=3-12','bytes=0-1000000','0-12','bytes=0-NaN'])expect(()=>rangeOffset(range,1000000)).toThrow();});
});
describe('Android-compatible metadata',()=>{
  const base:Media={id:'a',email:'one@example.test',name:'old.jpg',mimeType:'image/jpeg',createdTime:'2026-10-20T12:00:00Z',appProperties:{library:MEDIA_MARKER,capturedAt:'1640995200'}};
  it('places an edited old capture date ahead of import date when ordering',()=>{expect(capturedAt(base)).toBe(1640995200000);const recent={...base,id:'b',appProperties:{...base.appProperties,capturedAt:'1704067200'}};expect(ordered([base,recent],'newest').map(f=>f.id)).toEqual(['b','a']);expect(ordered([base,recent],'oldest')[0].id).toBe('a');});
  it('ignores unrelated Drive files and album JSON as media',()=>{expect(isMedia(base)).toBe(true);expect(isMedia({...base,mimeType:'application/json'})).toBe(false);expect(isMedia({...base,appProperties:{}})).toBe(false);});
  it('validates and deduplicates stable album identities without including tokens',()=>{const a=validateAlbum({id:'album-1',title:'A trip',members:['drive:one@example.test:a','drive:one@example.test:a'],updatedAt:10,deleted:false},'one@example.test','json-1');expect(a.members).toHaveLength(1);expect(a.fileId).toBe('json-1');expect(()=>validateAlbum({...a,members:['content://device/local']},a.email,'json-1')).toThrow();});
});
