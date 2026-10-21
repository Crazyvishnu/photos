import type { Account } from './model';
const tokens=new Map<string,{value:string;until:number}>();
type TokenResponse={access_token:string;expires_in:number;error?:string};
type GoogleLibrary={ accounts:{oauth2:{initTokenClient:(v:{client_id:string;scope:string;hint?:string;callback:(r:TokenResponse)=>void;error_callback:(e:{type:string})=>void})=>{requestAccessToken:(v:{prompt:string})=>void};hasGrantedAllScopes:(r:TokenResponse,...scopes:string[])=>boolean}} };
declare global { interface Window { google?:GoogleLibrary } }
export function connected(email:string) { const t=tokens.get(email);return !!t && t.until>Date.now()+30000; }
export function disconnect(email:string) { tokens.delete(email); }
export function token(email:string) { const t=tokens.get(email); if(!t || !connected(email)) throw new Error(`Reconnect ${email} to continue. Your access has expired.`); return t.value; }
export function loadGoogle():Promise<void> {
  if(window.google) return Promise.resolve();
  return new Promise((resolve,reject)=>{ let script=document.querySelector<HTMLScriptElement>('#google-identity'); if(!script){script=document.createElement('script');script.id='google-identity';script.src='https://accounts.google.com/gsi/client';script.async=true;document.head.append(script);}script.addEventListener('load',()=>resolve(),{once:true});script.addEventListener('error',()=>reject(new Error('Google sign-in could not load. Check your connection.')),{once:true}); });
}
export function authorize(clientId:string, hint?:string):Promise<Account> {
  if(!window.google) return Promise.reject(new Error('Google sign-in is still loading. Try again shortly.'));
  return new Promise((resolve,reject)=>{
    const google=window.google!;
    const client=google.accounts.oauth2.initTokenClient({client_id:clientId,scope:'https://www.googleapis.com/auth/drive.file',hint,
      error_callback: e=>reject(new Error(e.type==='popup_closed' ? 'Sign-in was cancelled.' : 'Allow the Google sign-in popup and try again.')),
      callback: async r=>{try{
        if(r.error || !r.access_token || !google.accounts.oauth2.hasGrantedAllScopes(r,'https://www.googleapis.com/auth/drive.file')) throw new Error('Google Drive permission was not granted.');
        const res=await fetch('https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,photoLink),storageQuota',{headers:{Authorization:`Bearer ${r.access_token}`}});
        if(!res.ok) throw new Error('Could not read the Google account. Enable Drive API for the OAuth project.');
        const data=await res.json();const email=data.user?.emailAddress;if(!email) throw new Error('Google did not return an account email.');
        tokens.set(email,{value:r.access_token,until:Date.now()+Number(r.expires_in)*1000});
        resolve({email,name:data.user.displayName||email,picture:data.user.photoLink,limit:data.storageQuota.limit ? Number(data.storageQuota.limit):null,usage:Number(data.storageQuota.usage)||0});
      }catch(e){reject(e);}}
    }); client.requestAccessToken({prompt:hint ? '' : 'select_account'});
  });
}
