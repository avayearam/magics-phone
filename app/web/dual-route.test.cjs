'use strict';
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const html=fs.readFileSync(__dirname+'/index.html','utf8');
const match=html.match(/<script>([\s\S]*?)<\/script>/);
assert.ok(match,'inline application script exists');
const code=match[1];
new vm.Script(code,{filename:'MagicPhone/v0.4/ui'});
const ids=new Map(), network=[];let statusEnabled=false, online=true, denyStop='', discovery, routes=[];
let token='test-token-for-ui';const saved=new Map();
function element(type='div'){
 const x={type,value:'',disabled:false,textContent:'',children:[],events:{},
 addEventListener(name,fn){this.events[name]=fn},replaceChildren(){this.children=[];if(type==='select')this.value=''},
 append(child){this.children.push(child);if(type==='select'&&this.children.length===1)this.value=child.value}};
 Object.defineProperty(x,'options',{get(){return this.children}});
 return x;
}
function byId(id){if(!ids.has(id)){const isSelect=/^(sourceKind|source|target)[AB]$/.test(id);const n=element(isSelect?'select':'div');if(id.startsWith('sourceKind'))n.value='input';ids.set(id,n);}return ids.get(id)}
const ctx={
 document:{getElementById:byId,createElement:element,documentElement:{dataset:{theme:'dark'}}},
 location:{origin:'http://127.0.0.1:18764'},
 localStorage:{getItem:k=>saved.get(k)||null,setItem:(k,v)=>saved.set(k,v)},
 AbortSignal,
 fetch:async(path,opts={})=>{
  network.push({path,method:opts.method||'GET',body:opts.body});
  if(!online)throw Error('offline');
  if(path==='/api/status')return ok({routingAvailable:statusEnabled});
  if(path==='/api/control-session')return ok({token,enabled:statusEnabled});
  if(path==='/api/discovery')return ok(discovery);
  if(path==='/api/route')return ok({enabled:statusEnabled,routes:routes.map(x=>({...x}))});
  if(path==='/api/route/start'&&opts.method==='POST'){
   const s=JSON.parse(opts.body);if(routes.some(x=>x.slot===s.slot&&x.status==='running'))return bad(409,'slot_busy');
   if(routes.some(x=>x.status==='running'&&x.targetId===s.targetId))return bad(409,'target_busy');
   routes=routes.filter(x=>x.slot!==s.slot).concat({...s,status:'running'});return ok({ok:true});
  }
  if(path==='/api/route/stop'&&opts.method==='POST'){
   const {slot}=JSON.parse(opts.body);if(denyStop===slot)return bad(409,'stop_failed');
   const r=routes.find(x=>x.slot===slot);if(!r||r.status!=='running')return bad(409,'inactive');r.status='stopped';return ok({ok:true});
  }
  return bad(404,'unknown');
 }
};
function ok(data){return {ok:true,json:async()=>data,text:async()=>JSON.stringify(data)}}
function bad(status,data){return {ok:false,status,text:async()=>data}}
vm.createContext(ctx);vm.runInContext(code,ctx);
const tick=()=>new Promise(r=>setImmediate(r));
const event=async id=>{const e=byId(id);assert.ok(e.events.click,id+' has click');await e.events.click();await tick()};
function countMutations(){return network.filter(x=>x.method==='POST').length}
function set(id,val){byId(id).value=val;byId(id).events.change()}
async function run(){
 await tick();await tick();
 assert.equal(countMutations(),0,'never auto-start');
 assert.equal(byId('startA').disabled,true);assert.equal(byId('startB').disabled,true);
 assert.equal(byId('stopBoth').disabled,true);
 // Disabled mode cannot start a route, even when a click handler is called explicitly.
 await event('startA');assert.equal(countMutations(),0);
 statusEnabled=true;await vm.runInContext('check()',ctx);await tick();
 discovery={available:true,partial:false,endpoints:[
  {id:'mic1',name:'<img src=x onerror=alert(1)>',direction:'input'},
  {id:'mic2',name:'Microphone B',direction:'input'},
  {id:'out1',name:'Virtual Cable',direction:'output'},
  {id:'out2',name:'Speakers',direction:'output'},
  {id:'out3',name:'VoiceMeeter',direction:'output'}],sessions:[{name:'chrome.exe',state:'active'}]};
 await event('scan');
 assert.equal(byId('devices').children[0].textContent,'ورودی: <img src=x onerror=alert(1)>','escapes unsafe device names');
 assert.equal(byId('sourceA').value,'mic1'); assert.equal(byId('sourceB').value,'mic1');
 assert.equal(byId('targetA').value,'out1');assert.equal(byId('targetB').value,'out1');
 assert.equal(byId('startA').disabled,false);assert.equal(byId('startB').disabled,false);
 await event('startA');assert.equal(countMutations(),1);assert.equal(routes[0].status,'running');
 assert.equal(byId('startA').disabled,true);assert.equal(byId('stopA').disabled,false);
 assert.equal(byId('sourceA').disabled,true,'active route config must be locked');
 assert.equal(byId('startB').disabled,true,'conflicting target blocked in UI');
 await event('startB');assert.equal(countMutations(),1,'no POST on conflicting target');
 set('targetB','out3');assert.equal(byId('startB').disabled,false);
 await event('startB');assert.equal(routes.length,2);assert.equal(countMutations(),2);
 assert.equal(byId('stopBoth').disabled,false,'dual stop available');
 await event('stopBoth');assert.deepEqual(routes.map(x=>x.status),['stopped','stopped']);assert.equal(countMutations(),4);
 assert.equal(byId('stopBoth').disabled,true);
 // Direct loopback feedback A out1->out2 ; B out2->out1 must be blocked.
 set('sourceKindA','loopback');set('sourceA','out1');set('targetA','out2');
 await event('startA');assert.equal(routes.find(x=>x.slot==='A').status,'running');
 set('sourceKindB','loopback');set('sourceB','out2');set('targetB','out1');
 assert.equal(byId('startB').disabled,true,'cycle rejected before request');
 const postBefore=countMutations();await event('startB');assert.equal(countMutations(),postBefore);
 // Stop failures must be visible and not fabricated as success.
 denyStop='A';await event('stopBoth');assert.match(byId('globalStatus').textContent,/تأیید نشد/);
 assert.equal(routes.find(x=>x.slot==='A').status,'running');denyStop='';
 await event('stopBoth');assert.equal(routes.find(x=>x.slot==='A').status,'stopped');
 set('sourceKindA','input');assert.ok(saved.has('magic-phone.routes.v040'));
 await event('theme');assert.equal(saved.get('magic-phone.theme.v040'),'light');
 // Network outage must disable further start attempts.
 online=false;await event('refresh');assert.equal(byId('startA').disabled,true);assert.equal(byId('startB').disabled,true);
 assert.equal(byId('stopBoth').disabled,true);
 assert.equal(countMutations(),postBefore+2,'no hidden mutating requests after outage');
 console.log('PASS: no auto-route, two independent paths, target collision, feedback cycle, stop-both, stop errors, offline safe, XSS safe text, persistence, theme');
}
run().catch(e=>{console.error(e);process.exitCode=1});
