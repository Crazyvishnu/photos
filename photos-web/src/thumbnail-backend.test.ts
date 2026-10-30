import {afterEach,expect,it,vi} from 'vitest';
vi.mock('./auth',()=>({backendEnabled:true,connected:()=>true}));
vi.mock('./drive',()=>({json:vi.fn()}));
vi.mock('./derivative-cache',()=>({derivativeBlob:vi.fn()}));
import {derivativeBlob} from './derivative-cache';
import {loadThumbnail} from './thumbnail-cache';
const photo={id:'photo',email:'a@example.test',name:'photo.jpg',mimeType:'image/jpeg',appProperties:{gridDerivativeId:'grid-copy'}};
function imageStub(){
 class TestImage{onload:(()=>void)|null=null;onerror:(()=>void)|null=null;dataset:Record<string,string>={};value='';set src(value:string){this.value=value;queueMicrotask(()=>this.onload?.());}get src(){return this.value;}removeAttribute(){}}
 vi.stubGlobal('Image',TestImage);
}
afterEach(()=>{vi.unstubAllGlobals();vi.clearAllMocks();});
it('uses an existing optimized preview before requesting another Cloudflare thumbnail',async()=>{
 imageStub();vi.mocked(derivativeBlob).mockResolvedValue(new Blob(['image'],{type:'image/jpeg'}));
 const image=await loadThumbnail(photo,new AbortController().signal,0);
 expect(image.src).toMatch(/^blob:/);expect(derivativeBlob).toHaveBeenCalledWith(photo.email,'grid-copy',expect.any(AbortSignal));URL.revokeObjectURL(image.src);
});
it('falls back to the authenticated backend when an optimized copy is missing',async()=>{
 imageStub();vi.mocked(derivativeBlob).mockRejectedValue(new Error('not found'));
 const image=await loadThumbnail(photo,new AbortController().signal,0);
 expect(image.src).toContain('/api/thumbnail?');expect(image.src).not.toContain('access_token');
});
