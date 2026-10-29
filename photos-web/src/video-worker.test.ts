import {it,expect} from 'vitest';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
it('streams authenticated byte ranges without buffering or caching the original',async()=>{
 const handlers:Record<string,Function>={};let forwarded:any;
 class Channel{port1:any={close(){}};port2:any=this.port1;}
 const body=new ReadableStream({start(c){c.enqueue(new Uint8Array([1,2]));c.close();}});
 const context={URL,Headers,Response,MessageChannel:Channel,setTimeout,clearTimeout,
 self:{location:{origin:'https://example.test'},addEventListener:(name:string,fn:Function)=>handlers[name]=fn,clients:{get:async()=>({postMessage:(_:unknown,ports:any[])=>ports[0].onmessage({data:'test-token'})})}},
 fetch:async(url:string,init:any)=>{forwarded={url,init};return new Response(body,{status:206,headers:{'Content-Range':'bytes 0-1/100','Content-Type':'video/mp4'}});}};
 runInNewContext(readFileSync('public/media-worker.js','utf8'),context);
 let result!:Promise<Response>;handlers.fetch({clientId:'tab',request:new Request('https://example.test/photos/private-video?id=one&email=a',{headers:{Range:'bytes=0-1'}}),respondWith:(r:Promise<Response>)=>result=r});
 const response=await result;expect(response.status).toBe(206);expect(response.headers.get('Content-Range')).toBe('bytes 0-1/100');expect(response.headers.get('Cache-Control')).toBe('no-store');expect(forwarded.init.headers).toEqual({Authorization:'Bearer test-token',Range:'bytes=0-1'});expect(await response.arrayBuffer()).toHaveProperty('byteLength',2);
});
