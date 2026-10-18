import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';

// All API requests use browser-local fixtures; no business records are written.
const browser=await chromium.launch({channel:'msedge',headless:true});
const context=await browser.newContext({viewport:{width:1280,height:900}});
const page=await context.newPage();
const errors=[],checks=[];
page.on('pageerror',error=>errors.push(error.message));
const accounts={
  admin:{id:'mock-admin',username:'admin',name:'同步测试管理员',role:'ADMIN',orgId:'mock-org'},
  tianbo:{id:'mock-inspector',username:'tianbo',name:'同步测试检查员',role:'INSPECTOR',orgId:'mock-org'},
  zhangsan:{id:'mock-reviewer',username:'zhangsan',name:'同步测试审核员',role:'REVIEWER',orgId:'mock-org'},
  lisi:{id:'mock-rechecker',username:'lisi',name:'同步测试复查员',role:'RECHECKER',orgId:'mock-org'}
};
let account=accounts.admin,stateRequests=0,failState=false,expireState=false,blockState=false,releaseState;
let activeState=0,maxActiveState=0;
const unit={id:'mock-unit',name:'自动同步测试单位',type:'学校',address:'测试地址',people:'20人',buildings:'一栋',risk:'低风险',active:true,updatedAt:'2026-10-13T10:00:00+08:00'};
const task={id:'mock-task',unitId:unit.id,orgId:'mock-org',source:'日常抽查',risk:'低风险',due:'2030-10-20T12:00:00+08:00',status:'进行中',version:1,progress:0,focus:['现场核验'],createdAt:'2026-10-13T10:00:00+08:00',site:{type:'学校',height:12,floors:3,people:20},inspectorId:accounts.tianbo.id,reviewerId:accounts.zhangsan.id,recheckerId:accounts.lisi.id};
const checklist=[{id:'mock-item',taskId:task.id,title:'检查项目',tip:'检查要求',group:'现场检查',status:null,note:''}];
function visible(){return account.role==='ADMIN'||account.role==='INSPECTOR'||(account.role==='REVIEWER'&&['待审核','整改中','已完成'].includes(task.status))||(account.role==='RECHECKER'&&['整改中','已完成'].includes(task.status));}
function state(){return {user:account,tasks:visible()?[task]:[],units:[unit],checklists:visible()?{[task.id]:checklist}:{},issues:[],events:[]};}
function detail(){return {...task,unit,checklist,issues:[],events:[],history:{reviews:[],rectifications:[],rechecks:[],logs:[]}};}
function dashboard(){const actionable=visible()&&(account.role==='ADMIN'||(account.role==='INSPECTOR'&&['待检查','进行中'].includes(task.status))||(account.role==='REVIEWER'&&task.status==='待审核')||(account.role==='RECHECKER'&&task.status==='整改中'));return {pending:task.status==='待检查'?1:0,inProgress:task.status==='进行中'?1:0,review:visible()&&task.status==='待审核'?1:0,rectifying:visible()&&task.status==='整改中'?1:0,completed:visible()&&task.status==='已完成'?1:0,reviewed:['整改中','已完成'].includes(task.status)?1:0,highRisk:0,priorityTasks:actionable?[task]:[],unfinishedTotal:actionable?1:0};}
await page.route('http://localhost:8081/api/**',async route=>{
  const request=route.request(),path=new URL(request.url()).pathname.slice(4);
  const respond=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:status,message:status===200?'成功':'测试失败',data})});
  if(path==='/auth/login'){account=accounts[request.postDataJSON().username];return respond({user:account,token:'mock-token-'+account.id});}
  if(path==='/auth/me')return respond(account);
  if(path==='/auth/logout')return respond({});
  assert.equal(request.method(),'GET','只读验收不能写业务数据');
  if(path==='/state'){
    stateRequests++;activeState++;maxActiveState=Math.max(maxActiveState,activeState);
    try{
      if(blockState){blockState=false;await new Promise(resolve=>{releaseState=resolve;});}
      if(expireState)return respond({},401);
      if(failState)return respond({},503);
      return respond(state());
    }finally{activeState--;}
  }
  if(path==='/dashboard')return respond(dashboard());
  if(path==='/users')return respond(Object.values(accounts).map(user=>({...user,enabled:true})));
  if(path==='/tasks/'+task.id)return respond(detail());
  if(path==='/units/'+unit.id)return respond({...unit,tasks:visible()?[task]:[],issues:[],events:[]});
  throw new Error('未预期接口：'+path);
});

async function login(username){await page.locator('[name="username"]').fill(username);await page.locator('[name="password"]').fill('fixture-password');await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'你好，'+accounts[username].name,exact:true}).waitFor();}
async function logout(){await page.locator('.sidebar [data-view="profile"]').click();await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.getByRole('heading',{name:'登录工作台',exact:true}).waitFor();}
async function advance(milliseconds=5000){await page.clock.runFor(milliseconds);}
async function waitUntil(predicate){for(let attempt=0;attempt<200;attempt++){if(predicate())return;await new Promise(resolve=>setTimeout(resolve,10));}assert.fail('等待浏览器请求处理超时');}
async function settle(){await page.waitForFunction(()=>document.querySelector('#app')?.dataset.syncAt===new Date().toISOString());}
async function expectSync(){const previous=await page.locator('#app').getAttribute('data-sync-at');await advance();await page.waitForFunction(value=>{const stamp=document.querySelector('#app')?.dataset.syncAt;return !!stamp&&stamp!==value;},previous);}
async function remember(selector){await page.evaluate(selector=>{window.testRoot=document.querySelector('#app');window.testNode=document.querySelector(selector);window.testNavigation=performance.getEntriesByType('navigation').length;},selector);}
async function expectSame(selector){assert.equal(await page.evaluate(selector=>document.querySelector('#app')===window.testRoot&&document.querySelector(selector)===window.testNode&&performance.getEntriesByType('navigation').length===window.testNavigation,selector),true);}

try{
  await page.clock.install({time:new Date('2026-10-13T10:00:00+08:00')});
  await page.clock.pauseAt(new Date('2026-10-13T10:00:01+08:00'));
  await page.goto('http://localhost:5173');
  await login('admin');
  const initial=stateRequests;
  await advance(4900);assert.equal(stateRequests,initial);
  await advance(100);await settle();assert.equal(stateRequests,initial+1);
  assert.equal(await page.getByText('每5秒自动同步',{exact:true}).count(),0);
  checks.push('每5秒后台同步，未到5秒不轮询');

  await remember('.hero-band');await expectSync();await expectSame('.hero-band');
  assert.equal(await page.locator('[data-action="refresh"]').count(),0);
  checks.push('数据未变化不重绘页面，无整页刷新');

  task.status='待审核';task.version++;await expectSync();
  assert.equal(await page.locator('.metric').filter({hasText:'待审核'}).locator('.metric-value').textContent(),'1');
  await expectSame('.hero-band');checks.push('远端任务移交自动更新工作台待办，现有节点保留');

  await page.locator('.sidebar [data-view="tasks"]').click();
  await page.locator('[data-task-search]').fill('自动同步');
  await remember('[data-task-search]');
  await page.evaluate(()=>{const input=document.querySelector('[data-task-search]');input.setSelectionRange(1,3);});
  unit.name='自动同步测试单位—已更新';await expectSync();
  await page.getByRole('heading',{name:unit.name,exact:true}).waitFor();
  await expectSame('[data-task-search]');assert.equal(await page.locator('[data-task-search]').inputValue(),'自动同步');
  assert.deepEqual(await page.locator('[data-task-search]').evaluate(input=>[document.activeElement===input,input.selectionStart,input.selectionEnd]),[true,1,3]);
  checks.push('搜索词、焦点和光标保留，列表按数据变化局部更新');

  await page.locator('.sidebar [data-view="units"]').click();
  await page.locator('[data-action="edit-unit"]').click();
  await page.locator('[name="name"]').fill('未保存的单位草稿');const beforeModal=stateRequests;
  await advance(10000);assert.equal(stateRequests,beforeModal);assert.equal(await page.locator('[name="name"]').inputValue(),'未保存的单位草稿');
  await page.getByRole('button',{name:'取消',exact:true}).click();await expectSync();
  checks.push('编辑弹窗暂缓同步，未保存文字保留，关闭后自动继续');

  failState=true;await advance();await page.getByText('数据暂时无法更新',{exact:true}).waitFor();
  assert.equal(await page.locator('.unit-card').count(),1);failState=false;unit.address='恢复网络后更新的地址';await expectSync();
  assert.equal(await page.locator('#connection-state').textContent(),'');await page.getByText('学校 · '+unit.address,{exact:true}).waitFor();
  checks.push('同步失败保留页面，恢复后自动更新且不弹成功提示');

  blockState=true;await advance();await waitUntil(()=>activeState===1);const slowCount=stateRequests;
  await advance();assert.equal(stateRequests,slowCount);assert.equal(maxActiveState,1);
  releaseState();await settle();checks.push('慢请求期间不启动第二轮同步');

  await page.evaluate(()=>{Object.defineProperty(document,'visibilityState',{configurable:true,value:'hidden'});document.dispatchEvent(new Event('visibilitychange'));});
  const hiddenCount=stateRequests;await advance(10000);assert.equal(stateRequests,hiddenCount);
  await page.evaluate(()=>{Object.defineProperty(document,'visibilityState',{configurable:true,value:'visible'});document.dispatchEvent(new Event('visibilitychange'));});
  await settle();assert.equal(stateRequests,hiddenCount+1);checks.push('后台页面停止轮询，回到前台立即同步');

  await logout();const loggedOutCount=stateRequests;await advance(10000);assert.equal(stateRequests,loggedOutCount);
  checks.push('退出登录停止数据请求');

  task.status='进行中';await login('tianbo');await page.locator('[data-action="task"][data-id="mock-task"]').click();
  await page.locator('[data-action="check-state"][data-state="abnormal"]').first().click();await page.locator('[name="finding"]').fill('一层通道堆物待核对');await page.locator('[name="finding"]').blur();const beforeForm=stateRequests;
  await advance(10000);assert.equal(stateRequests,beforeForm);assert.equal(await page.locator('[name="finding"]').inputValue(),'一层通道堆物待核对');
  await page.getByRole('button',{name:'取消',exact:true}).click();await page.getByRole('button',{name:'返回任务列表',exact:true}).click();await expectSync();
  checks.push('问题表单失去焦点后仍保留草稿，取消并离开编辑后继续同步');

  await logout();task.status='进行中';await login('zhangsan');await page.locator('.sidebar [data-view="tasks"]').click();assert.equal(await page.locator('.task-row').count(),0);
  task.status='待审核';await expectSync();assert.equal(await page.locator('.task-row').count(),1);
  await page.getByRole('button',{name:'查看任务',exact:true}).click();await page.locator('.flow-step[data-phase="inspect"]').click();
  unit.address='审核员同步后的地址';await expectSync();assert.equal(await page.locator('.flow-step.active').getAttribute('data-phase'),'inspect');
  task.status='进行中';await expectSync();await page.getByRole('heading',{name:'检查任务',exact:true}).waitFor();assert.equal(await page.locator('.task-row').count(),0);
  checks.push('审核员自动接收待审核任务，退回后自动移出；手动选择的查看阶段保留');

  await logout();task.status='待审核';await login('lisi');await page.locator('.sidebar [data-view="tasks"]').click();assert.equal(await page.locator('.task-row').count(),0);
  task.status='整改中';await expectSync();assert.equal(await page.locator('.task-row').count(),1);checks.push('复查员自动接收进入整改阶段的任务');

  await page.setViewportSize({width:390,height:844});await expectSync();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await mkdir('test-results',{recursive:true});await page.screenshot({path:'test-results/auto-sync-mobile.png',fullPage:true});checks.push('390像素手机布局无横向溢出');
  expireState=true;await advance();await page.getByRole('heading',{name:'登录工作台',exact:true}).waitFor();const expiredCount=stateRequests;
  await advance(10000);assert.equal(stateRequests,expiredCount);assert.equal(await page.evaluate(()=>sessionStorage.getItem('fire-assistant-session')),null);checks.push('会话到期回到登录并停止自动同步');
  assert.equal(errors.length,0,errors.join('\n'));
  const report={passed:true,fixtureOnly:true,businessWrites:0,checks,stateRequests,maxActiveState,errors};
  await writeFile('test-results/auto-sync-report.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
}catch(error){
  console.error(error);console.error(JSON.stringify({checks,stateRequests,activeState,errors}));await mkdir('test-results',{recursive:true});await page.screenshot({path:'test-results/auto-sync-failure.png',fullPage:true}).catch(()=>{});process.exitCode=1;
}finally{if(releaseState)releaseState();await browser.close();}
