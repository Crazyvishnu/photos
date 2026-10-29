import {afterEach,expect,it,vi} from 'vitest';
afterEach(()=>{vi.unstubAllGlobals();vi.unstubAllEnvs();vi.resetModules();});
it('restores backend accounts without exposing an access token to the browser',async()=>{
 vi.stubEnv('VITE_AUTH_BACKEND','true');vi.resetModules();
 const fetch=vi.fn(async()=>Response.json({accounts:[{email:'a@example.test',name:'A',usage:0,limit:null},{email:'b@example.test',name:'B',needsReconnect:true}]}));vi.stubGlobal('fetch',fetch);
 const auth=await import('./auth');const accounts=await auth.restoreAccounts([]);expect(accounts).toHaveLength(2);expect(auth.connected('a@example.test')).toBe(true);expect(auth.connected('b@example.test')).toBe(false);expect(fetch).toHaveBeenCalledWith('/api/session',expect.objectContaining({credentials:'same-origin'}));
});
it('removes persistent authorization before forgetting a connected account',async()=>{
 vi.stubEnv('VITE_AUTH_BACKEND','true');vi.resetModules();const fetch=vi.fn(async()=>Response.json({accounts:[{email:'a@example.test',name:'A'}]}));vi.stubGlobal('fetch',fetch);const auth=await import('./auth');await auth.loadGoogle();await auth.removeAuthorization('a@example.test');expect(auth.connected('a@example.test')).toBe(false);expect(fetch).toHaveBeenLastCalledWith('/api/account?email=a%40example.test',expect.objectContaining({method:'DELETE',headers:{'X-Photos-CSRF':'1'}}));
});
it('retains authorization when server-side disconnect fails',async()=>{
 vi.stubEnv('VITE_AUTH_BACKEND','true');vi.resetModules();const fetch=vi.fn().mockResolvedValueOnce(Response.json({accounts:[{email:'a@example.test',name:'A'}]})).mockResolvedValueOnce(new Response('',{status:503}));vi.stubGlobal('fetch',fetch);const auth=await import('./auth');await auth.loadGoogle();await expect(auth.removeAuthorization('a@example.test')).rejects.toThrow();expect(auth.connected('a@example.test')).toBe(true);
});
it('rejects a sign-in response that tries to redirect credentials to another host',async()=>{
 vi.stubEnv('VITE_AUTH_BACKEND','true');vi.resetModules();vi.stubGlobal('fetch',async()=>Response.json({url:'https://evil.test/steal'}));const auth=await import('./auth');await expect(auth.authorize('public-client')).rejects.toThrow('Unexpected sign-in destination');
});
