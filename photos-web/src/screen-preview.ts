export function screenPreviewPixels(width:number,height:number,dpr:number):number {
  const viewport=Math.max(1,width,height);
  const scale=Math.max(1,Math.min(Number.isFinite(dpr)?dpr:1,2));
  // 2K is enough to look full-resolution on a phone/normal laptop while staying far smaller than camera originals.
  return Math.max(1200,Math.min(2560,Math.ceil(viewport*scale/64)*64));
}

export function screenPreviewUrl(link:string,pixels:number):string {
  const size=Math.max(256,Math.min(4096,Math.round(pixels)));
  if(/=s\d+(?:-[a-z]+)?$/i.test(link)) return link.replace(/=s\d+(?:-[a-z]+)?$/i,`=s${size}`);
  return link+`=s${size}`;
}
