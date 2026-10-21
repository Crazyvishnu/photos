export function screenPreviewPixels(width:number,height:number,dpr:number):number {
  const viewport=Math.max(1,width,height);
  const scale=Math.max(1,Math.min(Number.isFinite(dpr)?dpr:1,2));
  const needed=viewport*scale;
  if(needed>1200) return 1600;
  if(needed>960) return 1200;
  return 960;
}

export function screenPreviewUrl(link:string,pixels:number):string {
  const size=pixels>=1400?1600:pixels>=1080?1200:960;
  if(/=s\d+(?:-[a-z]+)?$/i.test(link)) return link.replace(/=s\d+(?:-[a-z]+)?$/i,`=s${size}`);
  return link+`=s${size}`;
}

export function screenPreviewCandidates(link:string,pixels:number):string[] {
  const target=pixels>=1400?1600:pixels>=1080?1200:960;
  const sizes=target===1600?[1600,1200,960]:target===1200?[1200,960]:[960];
  return [...new Set([...sizes.map(size=>screenPreviewUrl(link,size)),link])];
}
