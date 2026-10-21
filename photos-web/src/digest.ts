import { sha256 } from '@noble/hashes/sha2.js';
export async function digestBlob(data:Blob,progress:(p:number)=>void=()=>{}):Promise<string>{
  const hash=sha256.create(),chunk=2*1024*1024;
  for(let at=0;at<data.size;at+=chunk){hash.update(new Uint8Array(await data.slice(at,at+chunk).arrayBuffer()));progress(Math.min(data.size,at+chunk)/data.size);}
  return Array.from(hash.digest(),n=>n.toString(16).padStart(2,'0')).join('');
}
