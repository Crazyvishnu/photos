const encoder = new TextEncoder();
const SCOPE = 'https://www.googleapis.com/auth/drive.file';
const COOKIE = '__Host-photos-session';
const AGE = 90 * 86400;
const refreshes = new Map();
const now = () => Math.floor(Date.now() / 1000);
const b64 = bytes => btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');
const un64 = value => Uint8Array.from(atob(value.replaceAll('-','+').replaceAll('_','/')), x => x.charCodeAt(0));
const random = () => b64(crypto.getRandomValues(new Uint8Array(32)));
export async function digest(value) { return b64(new Uint8Array(await crypto.subtle.digest('SHA-256',encoder.encode(value)))); }
async function aes(env) { const bytes = un64(env.TOKEN_ENCRYPTION_KEY); if(bytes.length!==32)throw new Error('Invalid encryption key');return crypto.subtle.importKey('raw',bytes,'AES-GCM',false,['encrypt','decrypt']); }
export async function seal(env,value,aad) {
 const iv=crypto.getRandomValues(new Uint8Array(12));
 const data=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:encoder.encode(aad)},await aes(env),encoder.encode(value));
 return b64(iv)+'.'+b64(new Uint8Array(data));
}
export async function unseal(env,value,aad) {
 const [iv,data]=value.split('.');
 return new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:un64(iv),additionalData:encoder.encode(aad)},await aes(env),un64(data)));
}
class Failure extends Error { constructor(status,message){super(message);this.status=status;} }
function json(value,status=200,extra={}) {return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...extra}});}
function originGuard(req,env) {
 if(req.headers.get('Origin')!==env.PUBLIC_ORIGIN||req.headers.get('X-Photos-CSRF')!=='1')throw new Failure(403,'Request origin was rejected.');
}
function sessionCookie(req) { return req.headers.get('Cookie')?.split(';').map(x=>x.trim()).find(x=>x.startsWith(COOKIE+'='))?.slice(COOKIE.length+1); }
async function session(req,env) {
 const raw=sessionCookie(req);if(!raw||!/^[A-Za-z0-9_-]{43}$/.test(raw))return null;
 const id=await digest(raw);return await env.DB.prepare('SELECT id FROM sessions WHERE id=? AND expires>?').bind(id,now()).first();
}
async function requireSession(req,env) {const value=await session(req,env);if(!value)throw new Failure(401,'Sign in to this browser again.');return value.id;}
async function googleToken(env,params) {
 const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({client_id:env.GOOGLE_CLIENT_ID,client_secret:env.GOOGLE_CLIENT_SECRET,...params}),signal:AbortSignal.timeout(20000)});
 const value=await response.json();if(!response.ok||!value.access_token)throw new Failure(value.error==='invalid_grant'?401:502,value.error==='invalid_grant'?'Google access was revoked or expired. Reconnect this account.':'Google authorization is temporarily unavailable.');return value;
}
async function access(env,owner,email,force=false) {
 const key=owner+':'+email;
 if(refreshes.has(key))return refreshes.get(key);
 const work=(async()=>{
  const row=await env.DB.prepare('SELECT * FROM accounts WHERE owner=? AND email=?').bind(owner,email).first();
  if(!row||row.needs_reconnect)throw new Failure(401,'Reconnect this Google account.');
  if(!force&&row.expires>now()+60)return unseal(env,row.access,key+':access');
  let value;try{value=await googleToken(env,{grant_type:'refresh_token',refresh_token:await unseal(env,row.refresh,key+':refresh')});}
  catch(error){if(error.status===401)await env.DB.prepare('UPDATE accounts SET needs_reconnect=1 WHERE owner=? AND email=?').bind(owner,email).run();throw error;}
  const refresh=value.refresh_token?await seal(env,value.refresh_token,key+':refresh'):row.refresh;
  await env.DB.prepare('UPDATE accounts SET access=?,refresh=?,expires=?,needs_reconnect=0 WHERE owner=? AND email=?')
   .bind(await seal(env,value.access_token,key+':access'),refresh,now()+Number(value.expires_in||3600),owner,email).run();
  return value.access_token;
 })();refreshes.set(key,work);try{return await work;}finally{if(refreshes.get(key)===work)refreshes.delete(key);}
}
export function driveURL(path) {
 const url=new URL(path,'https://www.googleapis.com/');
 if(url.origin!=='https://www.googleapis.com'||!/^\/(?:upload\/)?drive\/v3\//.test(url.pathname)||url.username||url.password)throw new Failure(400,'Unexpected Drive endpoint.');
 return url;
}
async function drive(env,owner,email,path,init={}) {
 const url=driveURL(path);const credential=await access(env,owner,email);
 const make=token=>fetch(url,{...init,redirect:'manual',headers:{...Object.fromEntries(new Headers(init.headers)),Authorization:'Bearer '+token}});
 // Retry only reads: mutation replay could create duplicates or consume streams.
 let response=await make(credential);
 if(response.status===401&&(!init.method||init.method==='GET'||init.method==='HEAD')){await response.body?.cancel();response=await make(await access(env,owner,email,true));}
 if(response.status===401)await env.DB.prepare('UPDATE accounts SET expires=0 WHERE owner=? AND email=?').bind(owner,email).run();
 return response;
}
function relay(response) {
 const headers=new Headers();for(const name of ['Content-Type','Content-Length','Content-Range','Accept-Ranges','Range','Location','Retry-After']){const v=response.headers.get(name);if(v)headers.set(name,v);}
 headers.set('Cache-Control','no-store');headers.set('X-Content-Type-Options','nosniff');
 return new Response(response.body,{status:response.status,headers});
}
async function route(req,env) {
 const url=new URL(req.url);if(!url.pathname.startsWith('/api/'))return env.ASSETS.fetch(req);
 if(url.origin!==env.PUBLIC_ORIGIN)throw new Failure(403,'Unexpected application origin.');
 if(url.pathname==='/api/health')return json({ok:true,configured:!!(env.GOOGLE_CLIENT_SECRET&&env.TOKEN_ENCRYPTION_KEY),version:'3.0.0'});
 if(!env.GOOGLE_CLIENT_SECRET||!env.TOKEN_ENCRYPTION_KEY)throw new Failure(503,'Sign-in setup is incomplete. Configure the Worker secrets.');
 if(url.pathname==='/api/session'&&req.method==='GET') {
  const owner=await session(req,env);if(!owner)return json({accounts:[]});
  const rows=await env.DB.prepare('SELECT profile,needs_reconnect FROM accounts WHERE owner=?').bind(owner.id).all();
  return json({accounts:rows.results.map(x=>({...JSON.parse(x.profile),needsReconnect:!!x.needs_reconnect}))});
 }
 if(url.pathname==='/api/auth/start'&&req.method==='POST') {
  originGuard(req,env);let owner=await session(req,env),cookie;
  if(!owner){const raw=random();owner={id:await digest(raw)};await env.DB.prepare('INSERT INTO sessions(id,expires) VALUES(?,?)').bind(owner.id,now()+AGE).run();cookie=COOKIE+'='+raw+'; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age='+AGE;}
  const body=await req.json();const hint=typeof body.email==='string'?body.email.slice(0,320):'';
  const state=random(),verifier=random();
  await env.DB.prepare('INSERT INTO auth_states(id,owner,verifier,hint,expires) VALUES(?,?,?,?,?)').bind(await digest(state),owner.id,verifier,hint,now()+600).run();
  const target=new URL('https://accounts.google.com/o/oauth2/v2/auth');target.search=new URLSearchParams({client_id:env.GOOGLE_CLIENT_ID,redirect_uri:env.PUBLIC_ORIGIN+'/api/auth/callback',response_type:'code',scope:SCOPE,access_type:'offline',prompt:'consent select_account',state,code_challenge:await digest(verifier),code_challenge_method:'S256',...(hint?{login_hint:hint}:{})}).toString();
  return json({url:target.href},200,cookie?{'Set-Cookie':cookie}:{});
 }
 if(url.pathname==='/api/auth/callback'&&req.method==='GET') {
  const owner=await requireSession(req,env),state=url.searchParams.get('state');if(!state)throw new Failure(400,'Missing authorization state.');
  const saved=await env.DB.prepare('DELETE FROM auth_states WHERE id=? AND owner=? AND expires>? RETURNING *').bind(await digest(state),owner,now()).first();
  if(!saved)throw new Failure(400,'Authorization expired or was already used. Start sign-in again.');
  if(url.searchParams.has('error'))return Response.redirect(env.PUBLIC_ORIGIN+'/?auth=cancelled',303);
  const code=url.searchParams.get('code');if(!code)throw new Failure(400,'Missing authorization code.');
  const value=await googleToken(env,{grant_type:'authorization_code',code,code_verifier:saved.verifier,redirect_uri:env.PUBLIC_ORIGIN+'/api/auth/callback'});
  if(!String(value.scope||'').split(' ').includes(SCOPE))throw new Failure(403,'Google Drive permission was not granted.');
  const about=await fetch('https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,photoLink),storageQuota',{headers:{Authorization:'Bearer '+value.access_token},signal:AbortSignal.timeout(15000)});
  if(!about.ok)throw new Failure(502,'Unable to read this Google account.');const data=await about.json(),email=data.user?.emailAddress;
  if(!email||(saved.hint&&saved.hint!==email))throw new Failure(400,'The selected Google account did not match. Start sign-in again.');
  const key=owner+':'+email,old=await env.DB.prepare('SELECT refresh FROM accounts WHERE owner=? AND email=?').bind(owner,email).first();
  const refresh=value.refresh_token?await seal(env,value.refresh_token,key+':refresh'):old?.refresh;
  if(!refresh)throw new Failure(400,'Offline access was not granted. Start sign-in again.');
  const profile={email,name:data.user.displayName||email,picture:data.user.photoLink,limit:data.storageQuota.limit?Number(data.storageQuota.limit):null,usage:Number(data.storageQuota.usage)||0};
  await env.DB.prepare('INSERT INTO accounts(owner,email,profile,refresh,access,expires) VALUES(?,?,?,?,?,?) ON CONFLICT(owner,email) DO UPDATE SET profile=excluded.profile,refresh=excluded.refresh,access=excluded.access,expires=excluded.expires,needs_reconnect=0')
   .bind(owner,email,JSON.stringify(profile),refresh,await seal(env,value.access_token,key+':access'),now()+Number(value.expires_in||3600)).run();
  return Response.redirect(env.PUBLIC_ORIGIN+'/?auth=connected',303);
 }
 const owner=await requireSession(req,env),email=url.searchParams.get('email');
 if(!email)throw new Failure(400,'Select a Google account.');
 if(url.pathname==='/api/account'&&req.method==='DELETE'){originGuard(req,env);await env.DB.prepare('DELETE FROM accounts WHERE owner=? AND email=?').bind(owner,email).run();return json({ok:true});}
 if(url.pathname==='/api/drive') {
  if(!['GET','HEAD','POST','PATCH','PUT','DELETE'].includes(req.method))throw new Failure(405,'Method not allowed.');
  if(!['GET','HEAD'].includes(req.method))originGuard(req,env);
  const headers=new Headers();for(const name of ['Content-Type','Content-Range','Range','If-Match']){const v=req.headers.get(name);if(v)headers.set(name,v);}
  return relay(await drive(env,owner,email,url.searchParams.get('path')||'',{method:req.method,headers,body:['GET','HEAD'].includes(req.method)?undefined:req.body,signal:req.signal}));
 }
 const id=url.searchParams.get('id');if(!id||!['GET','HEAD'].includes(req.method))throw new Failure(400,'Invalid media request.');
 if(url.pathname==='/api/media') {
  const headers=new Headers();if(req.headers.has('Range'))headers.set('Range',req.headers.get('Range'));
  return relay(await drive(env,owner,email,'drive/v3/files/'+encodeURIComponent(id)+'?alt=media',{method:req.method,headers,signal:req.signal}));
 }
 if(url.pathname==='/api/thumbnail') {
  const metadata=await drive(env,owner,email,'drive/v3/files/'+encodeURIComponent(id)+'?fields=thumbnailLink');
  if(!metadata.ok)return relay(metadata);const file=await metadata.json();if(!file.thumbnailLink)throw new Failure(404,'Preview is not ready.');
  const size=Math.max(160,Math.min(1600,Number(url.searchParams.get('size'))||320));let target=new URL(file.thumbnailLink.replace(/=s\d+(?:-[a-z]+)?$/i,'=s'+size));
  for(let count=0;count<4;count++){
   if(target.protocol!=='https:'||!(target.hostname==='googleusercontent.com'||target.hostname.endsWith('.googleusercontent.com')))throw new Failure(502,'Unexpected preview host.');
   const image=await fetch(target,{redirect:'manual',signal:req.signal});
   if([301,302,303,307,308].includes(image.status)&&image.headers.has('Location')){await image.body?.cancel();target=new URL(image.headers.get('Location'),target);continue;}
   return relay(image);
  }throw new Failure(502,'Preview redirect limit reached.');
 }
 throw new Failure(404,'Endpoint not found.');
}
export default {
 async fetch(req,env) {try{return await route(req,env);}catch(error){return json({error:{message:error instanceof Failure?error.message:'The service could not complete this request. Retry shortly.'}},error.status||503);}},
 async scheduled(_event,env) {await env.DB.batch([env.DB.prepare('DELETE FROM auth_states WHERE expires<=?').bind(now()),env.DB.prepare('DELETE FROM sessions WHERE expires<=?').bind(now())]);}
};
