import { useEffect,useRef,useState } from 'react';
import { Image as ImageIcon,Video } from 'lucide-react';
import { keyOf,type Media } from './model';
import { thumbnails } from './thumbnail-cache';
import { observeThumbnail } from './thumbnail-viewport';

export function Thumbnail({media,className=''}:{media:Media;className?:string}){
  const host=useRef<HTMLDivElement>(null),[state,setState]=useState<'waiting'|'ready'|'failed'>('waiting');
  const identity=keyOf(media);
  useEffect(()=>{
    const node=host.current!;let active=true,near=false,lease:ReturnType<typeof thumbnails.acquire>|undefined;
    setState('waiting');
    const unobserve=observeThumbnail(node,position=>{
      near=position.near;
      if(!near){lease?.release();lease=undefined;node.replaceChildren();setState('waiting');return;}
      if(lease){if(position.visible)lease.promote();return;}
      const current=thumbnails.acquire(media,position.visible?0:1);lease=current;
      void current.promise.then(source=>{if(!active||!near||lease!==current)return;const image=source.isConnected?source.cloneNode() as HTMLImageElement:source;image.alt=media.name;image.className='thumbnail-image';node.replaceChildren(image);setState('ready');}).catch(error=>{if(active&&near&&lease===current&&error?.name!=='AbortError')setState('failed');});
    });
    return()=>{active=false;unobserve();lease?.release();node.replaceChildren();};
  },[identity,media.thumbnailLink,media.name]);
  return <div className={`thumbnail-host ${className}`} data-preview-state={state}><div ref={host} className="thumbnail-image-host"/>{state!=='ready'&&<div className={`thumbnail-placeholder ${state==='failed'?'failed':''}`} aria-label={state==='failed'?`Preview unavailable for ${media.name}`:`Preview for ${media.name}`}><span>{media.mimeType.startsWith('video/')?<Video/>:<ImageIcon/>}</span>{state==='failed'&&<small>Open to view</small>}</div>}</div>;
}
