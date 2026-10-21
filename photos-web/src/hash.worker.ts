import { digestBlob } from './digest';
self.onmessage=async ({data}:{data:File})=>{try{postMessage({hash:await digestBlob(data,p=>postMessage({progress:p}))});}catch{postMessage({error:'Could not read the selected file.'});}};
