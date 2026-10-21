type CachedDerivative={email:string;id:string;blob:Blob;bytes:number;lastAccess:number};
const DB_NAME='photos-derivatives-v1',STORE='derivatives',MAX_BYTES=256*1024*1024,MAX_ENTRIES=1200;
const dbPromise=typeof indexedDB==='undefined'?null:new Promise<IDBDatabase>((resolve,reject)=>{
  const req=indexedDB.open(DB_NAME,1);
  req.onupgradeneeded=()=>req.result.createObjectStore(STORE,{keyPath:['email','id']});
  req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
});
function txGet(db:IDBDatabase,email:string,id:string){return new Promise<CachedDerivative|undefined>((resolve,reject)=>{const req=db.transaction(STORE).objectStore(STORE).get([email,id]);req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});}
export async function cachedDerivative(email:string,id:string):Promise<Blob|null>{
  if(!dbPromise)return null;
  const db=await dbPromise;const value=await txGet(db,email,id);if(!value)return null;
  value.lastAccess=Date.now();
  const tx=db.transaction(STORE,'readwrite');tx.objectStore(STORE).put(value);
  return value.blob;
}
export async function storeDerivative(email:string,id:string,blob:Blob){
  if(!dbPromise)return;
  const db=await dbPromise;
  await new Promise<void>((resolve,reject)=>{const tx=db.transaction(STORE,'readwrite');tx.objectStore(STORE).put({email,id,blob,bytes:blob.size,lastAccess:Date.now()} satisfies CachedDerivative);tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});
  void trim().catch(()=>{});
}
async function trim(){
  if(!dbPromise)return;const db=await dbPromise;
  const all=await new Promise<CachedDerivative[]>((resolve,reject)=>{const req=db.transaction(STORE).objectStore(STORE).getAll();req.onsuccess=()=>resolve(req.result||[]);req.onerror=()=>reject(req.error);});
  let bytes=all.reduce((n,e)=>n+e.bytes,0),count=all.length;if(bytes<=MAX_BYTES&&count<=MAX_ENTRIES)return;
  const doomed=all.sort((a,b)=>a.lastAccess-b.lastAccess).filter(e=>{if(bytes<=MAX_BYTES&&count<=MAX_ENTRIES)return false;bytes-=e.bytes;count--;return true;});
  await new Promise<void>((resolve,reject)=>{const tx=db.transaction(STORE,'readwrite');const store=tx.objectStore(STORE);for(const e of doomed)store.delete([e.email,e.id]);tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error);});
}
export async function clearDerivativeAccount(email:string){
  if(!dbPromise)return;const db=await dbPromise;
  const all=await new Promise<CachedDerivative[]>((resolve,reject)=>{const req=db.transaction(STORE).objectStore(STORE).getAll();req.onsuccess=()=>resolve(req.result||[]);req.onerror=()=>reject(req.error);});
  await new Promise<void>((resolve,reject)=>{const tx=db.transaction(STORE,'readwrite');const store=tx.objectStore(STORE);for(const e of all)if(e.email===email)store.delete([e.email,e.id]);tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error);});
}
