import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker, { digest, seal, unseal, driveURL } from '../worker.mjs';

const origin='https://photos.test';
function fixture(t){
 const db=new DatabaseSync(':memory:');db.exec('PRAGMA foreign_keys=ON;'+readFileSync(new URL('../migrations/0001_sessions.sql',import.meta.url),'utf8'));
 const adapt=(sql,args=[])=>({bind:(...values)=>adapt(sql,values),first:async()=>db.prepare(sql).get(...args)||null,all:async()=>({results:db.prepare(sql).all(...args)}),run:async()=>db.prepare(sql).run(...args)});
 const env={PUBLIC_ORIGIN:origin,GOOGLE_CLIENT_ID:'test-client',GOOGLE_CLIENT_SECRET:'test-secret',TOKEN_ENCRYPTION_KEY:Buffer.alloc(32,7).toString('base64url'),DB:{prepare:adapt,batch:async statements=>{for(const s of statements)await s.run();}},ASSETS:{fetch:async()=>new Response('app')}};
 const original=globalThis.fetch;t.after(()=>{globalThis.fetch=original;db.close();});return {db,env};
}
async function owner(db,raw='a'.repeat(43)){const id=await digest(raw);db.prepare('INSERT INTO sessions VALUES(?,?)').run(id,Math.floor(Date.now()/1000)+3600);return {id,cookie:'__Host-photos-session='+raw};}
async function account(env,db,session,email='a@example.test',expired=false){const aad=session.id+':'+email;db.prepare('INSERT INTO accounts(owner,email,profile,refresh,access,expires) VALUES(?,?,?,?,?,?)').run(session.id,email,JSON.stringify({email,name:'Test'}),await seal(env,'refresh-secret',aad+':refresh'),await seal(env,'access-secret',aad+':access'),expired?0:Math.floor(Date.now()/1000)+3600);}
function req(path,options={}){return new Request(origin+path,options);}
function mutation(cookie,body={}){return {method:'POST',headers:{Cookie:cookie,Origin:origin,'X-Photos-CSRF':'1','Content-Type':'application/json'},body:JSON.stringify(body)};}

test('encrypts tokens with randomized ciphertext and account-bound authenticated data',async t=>{
 const {env}=fixture(t);const one=await seal(env,'private','owner:a'),two=await seal(env,'private','owner:a');assert.notEqual(one,two);assert.ok(!one.includes('private'));assert.equal(await unseal(env,one,'owner:a'),'private');await assert.rejects(unseal(env,one,'owner:b'));
});
test('rejects unexpected upstream hosts, credentials and non-Drive paths',()=>{
 for(const path of ['https://evil.test/drive/v3/files','https://www.googleapis.com.evil.test/drive/v3/files','https://user@www.googleapis.com/drive/v3/files','https://www.googleapis.com/oauth2/token'])assert.throws(()=>driveURL(path));assert.equal(driveURL('drive/v3/files').hostname,'www.googleapis.com');
});
test('session response never reveals tokens and is private',async t=>{
 const {env,db}=fixture(t),session=await owner(db);await account(env,db,session);const response=await worker.fetch(req('/api/session',{headers:{Cookie:session.cookie}}),env);assert.equal(response.headers.get('Cache-Control'),'no-store');const text=await response.text();assert.ok(text.includes('a@example.test'));assert.ok(!text.includes('secret'));assert.ok(!text.includes('refresh'));
});
test('missing, invented and expired sessions cannot retrieve media',async t=>{
 const {env,db}=fixture(t);const session=await owner(db);db.prepare('UPDATE sessions SET expires=0').run();for(const cookie of ['',session.cookie,'__Host-photos-session='+'z'.repeat(43)])assert.equal((await worker.fetch(req('/api/media?email=a&id=1',{headers:{Cookie:cookie}}),env)).status,401);
});
test('one browser session cannot read another session account',async t=>{
 const {env,db}=fixture(t),first=await owner(db),second=await owner(db,'b'.repeat(43));await account(env,db,first);globalThis.fetch=async()=>{throw new Error('Must not reach Google');};assert.equal((await worker.fetch(req('/api/media?email=a%40example.test&id=one',{headers:{Cookie:second.cookie}}),env)).status,401);
});
test('refreshes an expired token automatically and relays exact video range headers',async t=>{
 const {env,db}=fixture(t),session=await owner(db);await account(env,db,session,'a@example.test',true);let refreshCalls=0;
 globalThis.fetch=async(input,init)=>{const url=String(input);if(url==='https://oauth2.googleapis.com/token'){refreshCalls++;assert.equal(new URLSearchParams(init.body).get('refresh_token'),'refresh-secret');return Response.json({access_token:'renewed',expires_in:3600});}assert.equal(new Headers(init.headers).get('Authorization'),'Bearer renewed');assert.equal(new Headers(init.headers).get('Range'),'bytes=100-102');return new Response(new Uint8Array([1,2,3]),{status:206,headers:{'Content-Range':'bytes 100-102/1000','Content-Length':'3','Content-Type':'video/mp4'}});};
 const response=await worker.fetch(req('/api/media?email=a%40example.test&id=one',{headers:{Cookie:session.cookie,Range:'bytes=100-102'}}),env);assert.equal(response.status,206);assert.equal(response.headers.get('Content-Range'),'bytes 100-102/1000');assert.equal((await response.arrayBuffer()).byteLength,3);assert.equal(refreshCalls,1);const row=db.prepare('SELECT access,refresh FROM accounts').get();assert.ok(!row.access.includes('renewed'));assert.ok(!row.refresh.includes('refresh-secret'));
});
test('revoked authorization marks only the affected account',async t=>{
 const {env,db}=fixture(t),session=await owner(db);await account(env,db,session,'a@example.test',true);await account(env,db,session,'b@example.test');globalThis.fetch=async()=>Response.json({error:'invalid_grant'},{status:400});assert.equal((await worker.fetch(req('/api/media?email=a%40example.test&id=x',{headers:{Cookie:session.cookie}}),env)).status,401);assert.equal(db.prepare('SELECT needs_reconnect FROM accounts WHERE email=?').get('a@example.test').needs_reconnect,1);assert.equal(db.prepare('SELECT needs_reconnect FROM accounts WHERE email=?').get('b@example.test').needs_reconnect,0);
});
test('cross-origin requests cannot start authorization or mutate accounts',async t=>{
 const {env,db}=fixture(t),session=await owner(db);await account(env,db,session);for(const path of ['/api/auth/start','/api/account?email=a%40example.test']){const options=mutation(session.cookie);options.method=path.includes('account?')?'DELETE':'POST';options.headers.Origin='https://evil.test';assert.equal((await worker.fetch(req(path,options),env)).status,403);}assert.equal(db.prepare('SELECT count(*) AS n FROM accounts').get().n,1);
});
test('authorization uses offline access, PKCE, secure cookie and one-use state',async t=>{
 const {env,db}=fixture(t);const response=await worker.fetch(req('/api/auth/start',mutation('')),env),data=await response.json(),target=new URL(data.url);assert.equal(target.origin,'https://accounts.google.com');assert.equal(target.searchParams.get('access_type'),'offline');assert.equal(target.searchParams.get('code_challenge_method'),'S256');assert.match(response.headers.get('Set-Cookie'),/HttpOnly; Secure; SameSite=Lax/);const cookie=response.headers.get('Set-Cookie').split(';')[0],path='/api/auth/callback?error=access_denied&state='+target.searchParams.get('state');assert.equal((await worker.fetch(req(path,{headers:{Cookie:cookie}}),env)).status,303);assert.equal((await worker.fetch(req(path,{headers:{Cookie:cookie}}),env)).status,400);assert.equal(db.prepare('SELECT count(*) AS n FROM auth_states').get().n,0);
});
test('OAuth state cannot be consumed by a different browser',async t=>{
 const {env,db}=fixture(t),session=await owner(db),other=await owner(db,'c'.repeat(43));const data=await (await worker.fetch(req('/api/auth/start',mutation(session.cookie)),env)).json();const state=new URL(data.url).searchParams.get('state');assert.equal((await worker.fetch(req('/api/auth/callback?state='+state+'&code=x',{headers:{Cookie:other.cookie}}),env)).status,400);assert.equal(db.prepare('SELECT count(*) AS n FROM auth_states').get().n,1);
});
test('disconnect removes only the selected browser account',async t=>{
 const {env,db}=fixture(t),session=await owner(db),other=await owner(db,'d'.repeat(43));await account(env,db,session);await account(env,db,other);const options=mutation(session.cookie);options.method='DELETE';assert.equal((await worker.fetch(req('/api/account?email=a%40example.test',options),env)).status,200);assert.equal(db.prepare('SELECT count(*) AS n FROM accounts WHERE owner=?').get(other.id).n,1);
});
test('setup health is public but protected endpoints fail closed without secrets',async t=>{
 const {env}=fixture(t);delete env.GOOGLE_CLIENT_SECRET;assert.equal((await (await worker.fetch(req('/api/health'),env)).json()).configured,false);assert.equal((await worker.fetch(req('/api/session'),env)).status,503);
});
test('expired session cleanup deletes associated credentials and OAuth states',async t=>{
 const {env,db}=fixture(t),session=await owner(db);await account(env,db,session);db.prepare('UPDATE sessions SET expires=0').run();await worker.scheduled({},env);assert.equal(db.prepare('SELECT count(*) AS n FROM accounts').get().n,0);
});
