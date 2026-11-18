const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const parser = fs.readFileSync(__dirname + '/../extension/parser.js','utf8');
const content = fs.readFileSync(__dirname + '/../extension/content.js','utf8');
const pending=[]; const sent=[]; const saved={};
let nodes=[{innerText:'به مریم زنگ بزن'}];
const context={
 document:{querySelectorAll:()=>nodes,documentElement:{}},
 location:{hostname:'chatgpt.com'},
 MutationObserver:class{constructor(cb){context.notify=cb;}observe(){}disconnect(){}},
 setTimeout:fn=>{pending.push(fn);return pending.length}, clearTimeout:()=>{},
 chrome:{storage:{local:{get:async()=>({armed:true}),set:async data=>{Object.assign(saved,data)}}},
 runtime:{sendMessage:async msg=>{sent.push(msg);return {accepted:true}},onMessage:{addListener:fn=>{context.onMessage=fn}}}}
};
vm.createContext(context); vm.runInContext(parser,context);
const parse=vm.runInContext('MagicParseCall',context);
assert.strictEqual(parse('لطفا به J A N زنگ بزن'),'JAN');
assert.strictEqual(parse('به JAN زنگ بزن'),'JAN');
assert.strictEqual(parse('لطفاً با J A N تماس بگیر'),'JAN');
assert.strictEqual(parse('جان‌جان به مریم تماس بگیر'),'مریم');
assert.strictEqual(parse('**به J A N زنگ بزن**'),'JAN');
assert.strictEqual(parse('به J A N تماس بزن'),'JAN');
assert.strictEqual(parse('در توضیحات گفتی به JAN زنگ بزن'),null);
vm.runInContext(content,context);
const pong=[]; context.onMessage({type:'magic_ping'},null,r=>pong.push(r));
assert.strictEqual(pong[0].alive,true); assert.strictEqual(pong[0].version,'0.8');
assert.strictEqual(pong[0].seen,1); assert.strictEqual(pending.length,0,'Historical message must never trigger call');
(async()=>{
 nodes=[...nodes,{innerText:'به J A N زنگ بزن'}]; context.notify();
 for(const fn of pending.splice(0)) await fn(); await new Promise(r=>setImmediate(r));
 assert.strictEqual(sent.length,1); assert.strictEqual(sent[0].contact,'JAN');
 assert.match(saved.lastDetected,/فرمان تماس شناسایی شد/u);
 const delayed={innerText:''}; nodes=[...nodes,delayed]; context.notify();
 delayed.innerText='لطفاً به مریم زنگ بزن'; context.notify();
 for(const fn of pending.splice(0)) await fn(); await new Promise(r=>setImmediate(r));
 assert.strictEqual(sent.length,2); assert.strictEqual(sent[1].contact,'مریم');
 nodes=[...nodes,{innerText:'سلام جان جان'}]; context.notify();
 for(const fn of pending.splice(0)) await fn(); await new Promise(r=>setImmediate(r));
 assert.strictEqual(sent.length,2); assert.match(saved.lastResult,/فرمان تماس شناخته نشد/u);
 console.log('Parser + typed/voice-transcript message detection + history safety tests PASSED');
})().catch(e=>{console.error(e);process.exitCode=1});
