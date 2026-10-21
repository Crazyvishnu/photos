const dbPromise = typeof indexedDB==='undefined' ? null : new Promise<IDBDatabase>((resolve,reject)=>{
  const req=indexedDB.open('photos-web-v1',1);
  req.onupgradeneeded=()=>req.result.createObjectStore('data'); req.onsuccess=()=>resolve(req.result); req.onerror=()=>reject(req.error);
});
export async function load<T>(key:string, fallback:T): Promise<T> {
  if(!dbPromise) return fallback;
  const db=await dbPromise; return new Promise((resolve,reject)=>{const req=db.transaction('data').objectStore('data').get(key);req.onsuccess=()=>resolve(req.result ?? fallback);req.onerror=()=>reject(req.error);});
}
export async function save(key:string, value:unknown) {
  if(!dbPromise) throw new Error('Browser storage is unavailable. Enable site storage to sync.');
  const db=await dbPromise; return new Promise<void>((resolve,reject)=>{const tx=db.transaction('data','readwrite');const s=tx.objectStore('data'); if(value===undefined) s.delete(key); else s.put(value,key);tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});
}
