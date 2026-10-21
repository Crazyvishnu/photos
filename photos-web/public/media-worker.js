self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));

const MAX_CHUNK=4*1024*1024;

function totalSize(url){
 const raw=url.searchParams.get('size');
 const value=Number(raw);
 return Number.isSafeInteger(value)&&value>0?value:0;
}
function requestedRange(range,total){
 if(!range||!total||range.includes(','))return null;
 let m=/^bytes=(\d+)-(\d*)$/i.exec(range.trim());
 if(m){
  const start=Number(m[1]);
  if(!Number.isSafeInteger(start)||start<0||start>=total)return null;
  const requestedEnd=m[2]?Number(m[2]):total-1;
  if(!Number.isSafeInteger(requestedEnd)||requestedEnd<start)return null;
  return {start,end:Math.min(requestedEnd,total-1,start+MAX_CHUNK-1)};
 }
 m=/^bytes=-(\d+)$/i.exec(range.trim());
 if(m){
  const count=Number(m[1]);
  if(!Number.isSafeInteger(count)||count<=0)return null;
  const bounded=Math.min(count,MAX_CHUNK,total);
  return {start:total-bounded,end:total-1};
 }
 return null;
}

self.addEventListener('fetch',event=>{
 const url=new URL(event.request.url);
 if(url.origin!==self.location.origin||!url.pathname.endsWith('/private-video'))return;
 if(event.request.method!=='GET'&&event.request.method!=='HEAD'){
  event.respondWith(new Response('Method not allowed',{status:405,headers:{Allow:'GET, HEAD'}}));
  return;
 }
 event.respondWith((async()=>{
  const client=await self.clients.get(event.clientId);
  if(!client)return new Response('Reopen this video',{status:401});
  const channel=new MessageChannel();
  const authorization=await new Promise(resolve=>{
   const timer=setTimeout(()=>resolve(null),5000);
   channel.port1.onmessage=e=>{clearTimeout(timer);channel.port1.close();resolve(e.data);};
   client.postMessage({type:'video-authorization',email:url.searchParams.get('email')},[channel.port2]);
  });
  if(!authorization)return new Response('Reconnect this account',{status:401});
  const id=url.searchParams.get('id');
  if(!id)return new Response('Missing file',{status:400});

  const total=totalSize(url);
  const incomingRange=event.request.headers.get('Range');
  const parsed=requestedRange(incomingRange,total);
  const forwardedRange=parsed?`bytes=${parsed.start}-${parsed.end}`:incomingRange;
  const headers={Authorization:'Bearer '+authorization};
  if(forwardedRange)headers.Range=forwardedRange;

  const upstream=await fetch('https://www.googleapis.com/drive/v3/files/'+encodeURIComponent(id)+'?alt=media',{
   method:event.request.method,
   headers,
   credentials:'omit',
   signal:event.request.signal
  });

  const output=new Headers();
  for(const name of ['Content-Type','Content-Length','Content-Range','Accept-Ranges','ETag','Last-Modified']){
   const value=upstream.headers.get(name);if(value)output.set(name,value);
  }

  // Drive can hide Content-Range from JavaScript. Rebuild it from the exact capped range we sent.
  if(upstream.status===206&&!output.has('Content-Range')&&parsed){
   output.set('Content-Range',`bytes ${parsed.start}-${parsed.end}/${total}`);
   if(!output.has('Content-Length'))output.set('Content-Length',String(parsed.end-parsed.start+1));
  }
  if(upstream.status===206&&!output.has('Accept-Ranges'))output.set('Accept-Ranges','bytes');
  output.set('Cache-Control','no-store');

  return new Response(event.request.method==='HEAD'?null:upstream.body,{
   status:upstream.status,
   statusText:upstream.statusText,
   headers:output
  });
 })().catch(()=>new Response('Video connection interrupted',{status:502,headers:{'Cache-Control':'no-store'}})));
});
