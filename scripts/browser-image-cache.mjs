import assert from 'node:assert/strict';
import {mkdir,writeFile,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomBytes} from 'node:crypto';
import {chromium} from 'playwright';

// Actual Vite modules, browser-local fixtures only; no business database is accessed.
const base=(process.env.CHECK_WEB_URL||'http://localhost:5173').replace(/\/$/,'');
const directory=await mkdtemp(join(tmpdir(),'fire-image-cache-'));
const image=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7YQAAAAASUVORK5CYII=','base64');
const largeImage=randomBytes(2*1024**2);
const checks=[],errors=[],reads=new Map(),auxiliaryBrowsers=[];
let context,page,release,started,offline=false;
async function setup(user='one',tasks=['task']){
  await page.evaluate(async({user,tasks})=>{
    window.api=await import('/src/api.js');window.cache=await import('/src/image-cache.js');
    api.setToken('cache-session-'+user);api.setAttachmentAccess({id:user,orgId:'org',role:'INSPECTOR'},tasks.map(id=>({id})));
  },{user,tasks});
}
async function launch(){
  context=await chromium.launchPersistentContext(directory,{channel:'msedge',headless:true});
  await context.route('**/cache-harness',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><body>Cache verification</body>'}));
  await context.route('**/api/files/photos/**',async route=>{
    const id=decodeURIComponent(new URL(route.request().url()).pathname.split('/').at(-1));
    reads.set(id,(reads.get(id)||0)+1);
    if(offline)return route.abort();
    if(id==='slow'){started=true;await new Promise(resolve=>{release=resolve;});}
    if(id==='missing')return route.fulfill({status:404,contentType:'application/json',body:'{"message":"附件不存在"}'});
    return route.fulfill({contentType:id==='document'?'application/pdf':'image/png',body:id==='document'?Buffer.from('%PDF-1.4'):image});
  });
  page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
  await page.goto(base+'/cache-harness');await setup();
}
const read=(id,task='task')=>page.evaluate(async({id,task})=>{
  const url=await api.attachmentURL(id,task);return (await (await fetch(url)).blob()).size;
},{id,task});
const entries=()=>page.evaluate(async()=>new Promise((resolve,reject)=>{
  const request=indexedDB.open('fire-assistant-image-cache',1);
  request.onerror=()=>reject(request.error);request.onsuccess=()=>{
    const db=request.result,lookup=db.transaction('entries').objectStore('entries').getAll();
    lookup.onsuccess=()=>{db.close();resolve(lookup.result);};
  };
}));
const count=id=>reads.get(id)||0;
async function waitForDownload(){
  const end=Date.now()+10000;
  while(!started&&Date.now()<end)await new Promise(resolve=>setTimeout(resolve,10));
  assert.ok(started,'Download did not start');
}
try{
  await launch();assert.equal(await page.evaluate(()=>cache.IMAGE_CACHE_LIMIT),1073741824);
  assert.deepEqual(await Promise.all([read('first'),read('first'),read('first')]),[image.length,image.length,image.length]);assert.equal(count('first'),1);
  assert.equal(await page.evaluate(()=>api.attachmentCacheBytes()),image.length);
  await page.reload();await setup();assert.equal(await read('first'),image.length);assert.equal(count('first'),1);
  await context.close();await launch();assert.equal(await read('first'),image.length);assert.equal(count('first'),1);
  offline=true;await page.evaluate(()=>api.clearAttachmentCache());assert.equal(await read('first'),image.length);assert.equal(count('first'),1);offline=false;
  checks.push('同图并发只下载一次；刷新、浏览器关闭重开、断网读取均复用持久图片');

  await setup('two');assert.equal(await read('first'),image.length);assert.equal(count('first'),2);
  assert.ok((await entries()).every(entry=>entry.scope.includes('two')));
  await page.evaluate(()=>api.setAttachmentAccess({id:'two',orgId:'org',role:'INSPECTOR'},[]));
  await assert.rejects(read('first'),/无权查看/);assert.equal((await entries()).length,0);
  await setup('two');await read('permitted');await page.reload();await setup('two',[]);assert.equal((await entries()).length,0);
  checks.push('不同账号不复用缓存；任务权限撤销、重新进入时无可见任务均清理失权图片');

  await setup('two');await read('clearable');const token=await page.evaluate(()=>api.getToken());
  await page.evaluate(()=>api.clearImageCache());assert.equal((await entries()).length,0);
  assert.equal(await page.evaluate(()=>api.attachmentCacheBytes()),0);assert.equal(await page.evaluate(()=>api.getToken()),token);
  await read('clearable');assert.equal(count('clearable'),2);
  await read('document');assert.ok(!(await entries()).some(entry=>entry.id==='document'));
  await assert.rejects(read('missing'),/附件不存在/);assert.ok(!(await entries()).some(entry=>entry.id==='missing'));
  checks.push('清除后用量归零且登录保留，再次查看重新下载；PDF和失败响应不持久缓存');

  // Logical-size metadata exercises the exact boundary without writing 1 GiB of fixture bytes.
  await page.evaluate(async()=>{await api.clearImageCache();await api.attachmentURL('recent','task');await api.attachmentURL('older','task');});
  await page.evaluate(async()=>new Promise(resolve=>{
    const request=indexedDB.open('fire-assistant-image-cache',1);request.onsuccess=()=>{
      const db=request.result,tx=db.transaction('entries','readwrite'),store=tx.objectStore('entries'),lookup=store.getAll();
      lookup.onsuccess=()=>{for(const entry of lookup.result)store.put({...entry,size:512*1024**2,lastUsed:entry.id==='recent'?1:2});};
      tx.oncomplete=()=>{db.close();resolve();};
    };
  }));
  await read('recent');await page.evaluate(()=>api.clearAttachmentCache());await read('new');
  const survivors=await entries();assert.deepEqual(survivors.map(e=>e.id).sort(),['new','recent']);
  assert.ok(survivors.reduce((sum,e)=>sum+e.size,0)<=1073741824);
  await page.evaluate(()=>api.clearAttachmentCache());await read('recent');assert.equal(count('recent'),1);
  await read('older');assert.equal(count('older'),2);
  checks.push('1 GiB逻辑容量边界：重新访问的旧图保留，最久未使用图片淘汰；命中不请求服务器、淘汰后重下');

  const pending=page.evaluate(()=>api.attachmentURL('slow','task').then(()=>false,error=>error.message));
  await waitForDownload();await page.evaluate(()=>api.clearImageCache());release();assert.match(await pending,/取消/);assert.equal((await entries()).length,0);started=false;
  const pendingLogout=page.evaluate(()=>api.attachmentURL('slow','task').then(()=>false,error=>error.message));
  await waitForDownload();await page.evaluate(()=>api.setToken(null));release();assert.match(await pendingLogout,/取消/);assert.equal((await entries()).length,0);
  checks.push('下载中清除或退出登录，迟到响应不能回填缓存或继续显示');

  await setup();await read('across-tabs');
  const second=await context.newPage();await second.goto(base+'/cache-harness');
  await second.evaluate(async()=>{
    const api=await import('/src/api.js');api.setToken('cache-session-one');api.setAttachmentAccess({id:'one',orgId:'org',role:'INSPECTOR'},[{id:'task'}]);
    await api.attachmentURL('across-tabs','task');await api.clearImageCache();
  });
  await page.waitForFunction(()=>api.attachmentCacheBytes().then(bytes=>bytes===0));await read('across-tabs');assert.equal(count('across-tabs'),2);await second.close();
  checks.push('同账号跨标签页清除同时使另一页的内存副本失效');

  const scope=(await entries())[0].scope,epoch=await page.evaluate(scope=>cache.imageCacheEpoch(scope),scope);
  await page.evaluate(scope=>cache.removeCachedImages(scope),scope);
  assert.equal(await page.evaluate(({scope,epoch})=>cache.storeImage(scope,'late-write','task',new Blob(['x'],{type:'image/png'}),()=>true,epoch),{scope,epoch}),false);
  checks.push('数据库清除代数阻止跨标签页旧下载写回');

  // Override before opening a fresh database; Chromium caches prior grants on existing connections.
  const limitedBrowser=await chromium.launch({channel:'msedge',headless:true});auxiliaryBrowsers.push(limitedBrowser);
  const limitedPage=await limitedBrowser.newPage();limitedPage.on('pageerror',error=>errors.push(error.message));
  await limitedPage.route('**/cache-harness',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><body>Quota</body>'}));
  await limitedPage.route('**/api/files/photos/**',route=>route.fulfill({contentType:'image/png',body:largeImage}));
  const devtools=await limitedPage.context().newCDPSession(limitedPage);
  await devtools.send('Storage.overrideQuotaForOrigin',{origin:new URL(base).origin,quotaSize:1});await limitedPage.goto(base+'/cache-harness');
  assert.deepEqual(await limitedPage.evaluate(async()=>{
    const api=await import('/src/api.js');api.setToken('limited');api.setAttachmentAccess({id:'one',orgId:'org',role:'INSPECTOR'},[{id:'task'}]);
    const url=await api.attachmentURL('limited','task');const size=(await(await fetch(url)).blob()).size,bytes=await api.attachmentCacheBytes();
    const entries=await new Promise(resolve=>{const request=indexedDB.open('fire-assistant-image-cache',1);request.onsuccess=()=>{const db=request.result,lookup=db.transaction('entries').objectStore('entries').getAll();lookup.onsuccess=()=>{db.close();resolve(lookup.result);};};});
    return {size,bytes,entries:entries.length};
  }),{size:largeImage.length,bytes:largeImage.length,entries:0});await limitedBrowser.close();
  checks.push('实际浏览器配额耗尽时退回内存，图片仍可查看，不留下半条缓存');

  const fallbackBrowser=await chromium.launch({channel:'msedge',headless:true});auxiliaryBrowsers.push(fallbackBrowser);
  const fallbackPage=await fallbackBrowser.newPage();fallbackPage.on('pageerror',error=>errors.push(error.message));
  await fallbackPage.addInitScript(()=>Object.defineProperty(window,'indexedDB',{configurable:true,value:null}));
  await fallbackPage.route('**/cache-harness',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><body>Fallback</body>'}));
  await fallbackPage.route('**/api/files/photos/**',route=>route.fulfill({contentType:'image/png',body:image}));await fallbackPage.goto(base+'/cache-harness');
  assert.equal(await fallbackPage.evaluate(async()=>{
    const api=await import('/src/api.js');api.setToken('fallback');api.setAttachmentAccess({id:'one',orgId:'org',role:'INSPECTOR'},[{id:'task'}]);
    const url=await api.attachmentURL('fallback','task'),size=(await(await fetch(url)).blob()).size;await api.clearImageCache();return size;
  }),image.length);await fallbackBrowser.close();checks.push('设备不支持本地存储时仍能在线查看、清除内存副本');
  assert.deepEqual(errors,[]);await mkdir('test-results/image-cache',{recursive:true});
  const report={passed:true,fixtureOnly:true,businessWrites:0,capacityBytes:1073741824,lruBoundaryUsesLogicalMetadata:true,checks,errors};
  await writeFile('test-results/image-cache/cache-report.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
}catch(error){console.error(error);console.error({checks,errors});process.exitCode=1;}
finally{release?.();await context?.close();await Promise.allSettled(auxiliaryBrowsers.map(browser=>browser.close()));await rm(directory,{recursive:true,force:true});}
