import {spawnSync} from 'node:child_process';
const result=spawnSync(process.platform==='win32'?'npm.cmd':'npm',['run','build'],{
 cwd:new URL('../photos-web/',import.meta.url),env:{...process.env,VITE_AUTH_BACKEND:'true'},
 shell:process.platform==='win32',stdio:'inherit'
});
process.exit(result.status??1);
