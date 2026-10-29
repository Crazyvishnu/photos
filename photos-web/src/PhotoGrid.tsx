import { memo } from 'react';
import { Check, CheckCircle2, Play, Star } from 'lucide-react';
import { favorite, groups, keyOf, type Media } from './model';
import { Thumbnail } from './Thumbnail';
import { photoPreviews } from './preview-cache';
export const PhotoGrid=memo(function PhotoGrid({items,selection,badges,onOpen,onToggle,onSelectDay}:{items:Media[];selection:Set<string>;badges:boolean;onOpen:(m:Media)=>void;onToggle:(m:Media)=>void;onSelectDay:(items:Media[])=>void}){
  // Every item is present; the browser lazily paints off-screen days and thumbnails.
  return groups(items).map(group=><section className="day-group" key={group.label}><div className="day-heading"><h2>{group.label}</h2><button aria-label={`Select all from ${group.label}`} onClick={()=>onSelectDay(group.items)}><Check size={15}/></button></div><div className="photo-grid">{group.items.map(m=><article key={keyOf(m)} className={`photo-card ${selection.has(keyOf(m))?'checked':''}`}><button className="photo-open" aria-label={`Open ${m.name}`} onMouseEnter={()=>{if(!selection.size)photoPreviews.prefetch(m);}} onFocus={()=>{if(!selection.size)photoPreviews.prefetch(m);}} onClick={()=>onOpen(m)}><Thumbnail media={m}/></button><input type="checkbox" aria-label={`Select ${m.name}`} checked={selection.has(keyOf(m))} onChange={()=>onToggle(m)}/>{m.mimeType.startsWith('video/')&&<span className="video-badge"><Play size={14} fill="currentColor"/>Video</span>}{favorite(m)&&<span className="favorite-badge"><Star size={15} fill="currentColor"/></span>}{badges&&<span className="backup-badge"><CheckCircle2 size={13}/>Backed up</span>}</article>)}</div></section>);
});
