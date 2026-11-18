const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync(__dirname+'/index.html','utf8');const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const ids=new Map();
function element(tag='div'){
 const obj={tag,value:'',textContent:'',disabled:false,children:[],events:{},addEventListener(type,fn){this.events[type]=fn},replaceChildren(){this.children=[];if(this.tag==='select')this.value=''},append(child){this.children.push(child);if(this.tag==='select'&&this.children.length===1)this.value=child.value}};
 Object.defineProperty(obj,'options',{get(){return obj.children}});return obj;
}
function el(id){if(!ids.has(id))ids.set(id,element(['source','target','sourceKind'].includes(id)?'select':'div'));return ids.get(id)}
el('sourceKind').value='input';
let requests=[];let status={routingAvailable:false};let discovery={available:true,partial:false,endpoints:[{id:'mic',name:'<img src=x onerror=alert(1)>',direction:'input'},{id:'sp1',name:'Speakers',direction:'output'},{id:'sp2',name:'Virtual cable',direction:'output'}],sessions:[{name:'chrome.exe',state:'active'}]};
const storage=new Map();const ctx={document:{getElementById:el,createElement:tag=>element(tag),documentElement:{dataset:{theme:'dark'}}},location:{origin:'http://127.0.0.1:18764'},localStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v)},AbortSignal,fetch:async(path,opts)=>{requests.push({path,opts});return {ok:true,json:async()=>path==='/api/status'?status:path==='/api/route'?{routes:[]}:path==='/api/discovery'?discovery:{token:'not-real',enabled:true}}}};
vm.createContext(ctx);vm.runInContext(script,ctx);
(async()=>{
 await new Promise(r=>setImmediate(r));assert.equal(el('connection').textContent,'Agent محلی: انتقال غیرفعال');assert.equal(el('start').disabled,true);
 await el('scan').events.click();assert.equal(el('devices').children.length,3);assert.equal(el('devices').children[0].textContent,'ورودی: <img src=x onerror=alert(1)>');assert.equal(el('source').value,'mic');assert.equal(el('target').value,'sp1');
 el('target').value='sp2';el('target').events.change();assert.equal(el('target').value,'sp2','selection should persist');
 el('sourceKind').value='loopback';el('sourceKind').events.change();assert.equal(el('source').value,'sp1');assert.equal(el('start').disabled,true);
 assert.equal(requests.filter(r=>r.path.includes('start')).length,0,'no automatic audio');
 el('theme').events.click();assert.equal(storage.get('magic-phone.theme.v030'),'light');
 console.log('PASS: route panel disabled, no auto capture, safe text, native device selectors, change persistence, theme');
})().catch(e=>{console.error(e);process.exitCode=1});
