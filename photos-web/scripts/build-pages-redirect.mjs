// GitHub Pages cannot run the credential-renewal backend. Keep old bookmarks
// working by moving navigation to the canonical same-origin Worker deployment.
import { mkdir, writeFile } from 'node:fs/promises';
const destination='https://photos-app.0143crazy.workers.dev/';
const html=`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Opening Photos App</title><link rel="canonical" href="${destination}">
<meta http-equiv="refresh" content="0;url=${destination}">
<script>window.location.replace(${JSON.stringify(destination)});</script>
<style>body{font:18px system-ui,sans-serif;max-width:38rem;margin:15vh auto;padding:24px;color:#202124}a{color:#1a73e8}</style>
</head><body><h1>Photos App has moved</h1><p>Opening the website with automatic account renewal.</p><p><a href="${destination}">Continue to Photos App</a></p><p>Connect your backup accounts once on the new address. Your originals remain in Google Drive.</p></body></html>`;
await mkdir(new URL('../dist/',import.meta.url),{recursive:true});
await Promise.all(['index.html','404.html'].map(name=>writeFile(new URL('../dist/'+name,import.meta.url),html)));
console.log('GitHub Pages redirect prepared: '+destination);
