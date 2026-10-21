import {afterEach,expect,it,vi} from 'vitest';
afterEach(()=>{vi.unstubAllGlobals();vi.resetModules();});
it('restores valid account sessions after refresh and removes disconnected accounts',async()=>{
 const entries=new Map<string,string>();entries.set('photos-access-session-v1',JSON.stringify([['a@example.test',{value:'test-token',until:Date.now()+600000}],['expired@example.test',{value:'expired',until:Date.now()-1}]]));
 vi.stubGlobal('sessionStorage',{getItem:(k:string)=>entries.get(k)||null,setItem:(k:string,v:string)=>entries.set(k,v)});vi.resetModules();
 const auth=await import('./auth');expect(auth.connected('a@example.test')).toBe(true);expect(auth.connected('expired@example.test')).toBe(false);expect(auth.token('a@example.test')).toBe('test-token');auth.disconnect('a@example.test');expect(entries.get('photos-access-session-v1')).toBe('[]');
});
it('survives unavailable or corrupted browser session storage',async()=>{vi.stubGlobal('sessionStorage',{getItem:()=>'{bad'});vi.resetModules();const auth=await import('./auth');expect(auth.connected('a@example.test')).toBe(false);});
