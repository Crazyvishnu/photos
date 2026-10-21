self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('fetch',event=>{
 const url=new URL(event.request.url);
 if(url.origin!==self.location.origin||!url.pathname.endsWith('/private-video'))return;
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
  const headers={Authorization:'Bearer '+authorization};
  const range=event.request.headers.get('Range');if(range)headers.Range=range;
  const upstream=await fetch('https://www.googleapis.com/drive/v3/files/'+encodeURIComponent(id)+'?alt=media',{headers,signal:event.request.signal});
  const output=new Headers();for(const name of ['Content-Type','Content-Length','Content-Range','Accept-Ranges']){const value=upstream.headers.get(name);if(value)output.set(name,value);}
  output.set('Cache-Control','no-store');
  return new Response(upstream.body,{status:upstream.status,headers:output});
 })().catch(()=>new Response('Video connection interrupted',{status:502})));
});
