type State={near:boolean;visible:boolean};
const targets=new Map<Element,{state:State;notify:(state:State)=>void}>();
let nearby:IntersectionObserver|undefined,visible:IntersectionObserver|undefined;
export function observeThumbnail(element:Element,notify:(state:State)=>void){
  if(typeof IntersectionObserver==='undefined'){notify({near:true,visible:true});return()=>{};}
  if(!nearby){
    nearby=new IntersectionObserver(entries=>{for(const e of entries){const t=targets.get(e.target);if(t){t.state.near=e.isIntersecting;t.state.visible=e.isIntersecting&&e.boundingClientRect.bottom>0&&e.boundingClientRect.top<innerHeight;t.notify({...t.state});}}},{rootMargin:'400px 0px'});
    visible=new IntersectionObserver(entries=>{for(const e of entries){const t=targets.get(e.target);if(t){t.state.visible=e.isIntersecting;if(e.isIntersecting)t.state.near=true;t.notify({...t.state});}}});
  }
  targets.set(element,{state:{near:false,visible:false},notify});nearby.observe(element);visible!.observe(element);
  return()=>{nearby!.unobserve(element);visible!.unobserve(element);targets.delete(element);};
}
