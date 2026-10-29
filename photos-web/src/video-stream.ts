import { backendEnabled, token } from './auth';
import type { Media } from './model';
let ready:Promise<void>|undefined;
export function videoStream(m:Media):Promise<string>{
 if(backendEnabled)return Promise.resolve('/api/media?'+new URLSearchParams({email:m.email,id:m.id}));
 if(!('serviceWorker' in navigator))return Promise.reject(new Error('Video streaming needs a supported browser with HTTPS.'));
 if(!ready)ready=(async()=>{
  navigator.serviceWorker.addEventListener('message',event=>{
   if(event.data?.type!=='video-authorization'||!event.ports[0])return;
   let value:string|null=null;try{value=token(event.data.email);}catch{}
   event.ports[0].postMessage(value);
  });
  await navigator.serviceWorker.register(import.meta.env.BASE_URL+'media-worker.js',{scope:import.meta.env.BASE_URL});
  await navigator.serviceWorker.ready;
  if(!navigator.serviceWorker.controller)await new Promise<void>((resolve,reject)=>{
   const changed=()=>{clearTimeout(timer);resolve();};
   const timer=setTimeout(()=>{navigator.serviceWorker.removeEventListener('controllerchange',changed);reject(new Error('Reload once to enable video streaming.'));},10000);
   navigator.serviceWorker.addEventListener('controllerchange',changed,{once:true});
  });
 })().catch(e=>{ready=undefined;throw e;});
 return ready.then(()=>{token(m.email);return import.meta.env.BASE_URL+'private-video?'+new URLSearchParams({id:m.id,email:m.email,size:String(m.size||''),type:m.mimeType});});
}
