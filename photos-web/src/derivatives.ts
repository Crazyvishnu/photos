import { checked, fields, json, listing, request } from './drive';
import { DERIVATIVE_FOLDER_MARKER, DERIVATIVE_MARKER, DERIVATIVE_VERSION, GRID_DERIVATIVE_KEY, SCREEN_DERIVATIVE_KEY, type DriveFile, type Media } from './model';

type Kind='grid'|'screen';
type Derivative={kind:Kind;blob:Blob};

async function imageBitmap(file:Blob):Promise<ImageBitmap>{
  if(typeof createImageBitmap==='undefined')throw new Error('This browser cannot create optimized photo previews.');
  return createImageBitmap(file);
}
function resizedBlob(bitmap:ImageBitmap,maxEdge:number,quality:number):Promise<Blob>{
  const scale=Math.min(1,maxEdge/Math.max(bitmap.width,bitmap.height));
  const width=Math.max(1,Math.round(bitmap.width*scale)),height=Math.max(1,Math.round(bitmap.height*scale));
  const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;
  const ctx=canvas.getContext('2d',{alpha:true});if(!ctx)throw new Error('Photo preview encoder is unavailable.');
  ctx.drawImage(bitmap,0,0,width,height);
  return new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('Photo preview encoding failed.')),'image/webp',quality));
}
export async function makeImageDerivatives(file:Blob):Promise<Derivative[]>{
  const bitmap=await imageBitmap(file);
  try{
    const [grid,screen]=await Promise.all([resizedBlob(bitmap,512,.76),resizedBlob(bitmap,2048,.84)]);
    return [{kind:'grid',blob:grid},{kind:'screen',blob:screen}];
  }finally{bitmap.close();}
}
async function findExisting(email:string,originalId:string,kind:Kind,_signal:AbortSignal):Promise<string|undefined>{
  const q=`appProperties has { key='library' and value='${DERIVATIVE_MARKER}' } and appProperties has { key='originalId' and value='${originalId}' } and appProperties has { key='kind' and value='${kind}' } and trashed=false`;
  return (await listing(email,q))[0]?.id;
}
async function ensureFolder(email:string,signal:AbortSignal):Promise<string>{
  const q=`mimeType='application/vnd.google-apps.folder' and appProperties has { key='library' and value='${DERIVATIVE_FOLDER_MARKER}' } and trashed=false`;
  const existing=(await listing(email,q))[0];if(existing)return existing.id;
  const metadata={name:'Photos App optimized previews',mimeType:'application/vnd.google-apps.folder',appProperties:{library:DERIVATIVE_FOLDER_MARKER,version:DERIVATIVE_VERSION}};
  return (await json<DriveFile>(email,'drive/v3/files?fields=id,name,mimeType,appProperties',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(metadata)},signal)).id;
}
async function uploadDerivative(email:string,folderId:string,originalId:string,kind:Kind,blob:Blob,signal:AbortSignal):Promise<string>{
  const id=(await json<{ids:string[]}>(email,'drive/v3/files/generateIds?count=1&space=drive&type=files',{},signal)).ids[0];
  const boundary=`photos_derivative_${crypto.randomUUID()}`;
  const metadata={id,name:`Photos preview ${originalId} ${kind}.webp`,mimeType:'image/webp',parents:[folderId],appProperties:{library:DERIVATIVE_MARKER,version:DERIVATIVE_VERSION,originalId,kind}};
  const body=new Blob([
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n`,
    `--${boundary}\r\nContent-Type: image/webp\r\n\r\n`,blob,`\r\n--${boundary}--`
  ],{type:`multipart/related; boundary=${boundary}`});
  const response=await checked(await request(email,'upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,size,appProperties',{
    method:'POST',headers:{'Content-Type':`multipart/related; boundary=${boundary}`},body
  },signal));
  const uploaded=await response.json() as DriveFile;
  if(uploaded.id!==id)throw new Error('Drive returned an unexpected preview file.');
  return id;
}
export async function ensureImageDerivatives(file:File,media:Media,signal:AbortSignal):Promise<Media>{
  if(!file.type.startsWith('image/'))return media;
  const props={...(media.appProperties||{})};
  let grid:string|undefined=props[GRID_DERIVATIVE_KEY],screen:string|undefined=props[SCREEN_DERIVATIVE_KEY];
  if(grid&&screen&&props.derivativeVersion===DERIVATIVE_VERSION)return media;

  if(!grid)grid=await findExisting(media.email,media.id,'grid',signal);
  if(!screen)screen=await findExisting(media.email,media.id,'screen',signal);
  let generated:Derivative[]|undefined;
  if(!grid||!screen)generated=await makeImageDerivatives(file);
  const folderId=(!grid||!screen)?await ensureFolder(media.email,signal):'';
  if(!grid)grid=await uploadDerivative(media.email,folderId,media.id,'grid',generated!.find(x=>x.kind==='grid')!.blob,signal);
  if(!screen)screen=await uploadDerivative(media.email,folderId,media.id,'screen',generated!.find(x=>x.kind==='screen')!.blob,signal);

  const appProperties={...props,[GRID_DERIVATIVE_KEY]:grid,[SCREEN_DERIVATIVE_KEY]:screen,derivativeVersion:DERIVATIVE_VERSION};
  const updated=await json<DriveFile>(media.email,`drive/v3/files/${encodeURIComponent(media.id)}?fields=${fields}`,{
    method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({appProperties})
  },signal);
  return {...updated,email:media.email};
}
