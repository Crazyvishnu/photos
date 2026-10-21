export function screenPreviewPixels(width:number,height:number,dpr:number):number {
  const viewport=Math.max(1,width,height);
  const scale=Math.max(1,Math.min(Number.isFinite(dpr)?dpr:1,2));
  // Drive thumbnail delivery is most reliable at <=1600px. This is still enough for a sharp phone/tablet preview.
  return Math.max(960,Math.min(1600,Math.ceil(viewport*scale/64)*64));
}

export function screenPreviewUrl(link:string,pixels:number):string {
  const size=Math.max(256,Math.min(1600,Math.round(pixels)));
  if(/=s\d+(?:-[a-z]+)?$/i.test(link)) return link.replace(/=s\d+(?:-[a-z]+)?$/i,`=s${size}`);
  return link+`=s${size}`;
}

export function screenPreviewCandidates(link:string,pixels:number):string[] {
  const target=Math.max(960,Math.min(1600,Math.round(pixels)));
  const sizes=[target,1600,1280,1024,960];
  return [...new Set([...sizes.map(size=>screenPreviewUrl(link,size)),link])];
}
