const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const root = __dirname + '/../extension/';
let handlers = {}, sent = [], saved = {}, time = 10000;
const input = {
  innerText: '',
  textContent: '',
  value: undefined,
  contains: n => n === input || n?.inside === true
};
const button = {
  disabled: false,
  getAttribute: k => ({ 'aria-label': 'Send prompt', 'aria-disabled': 'false' }[k] ?? null)
};
const child = { closest: selector => selector.includes('send-button') || selector.includes('Send prompt') ? button : null };
const doc = {
  querySelector: selector => selector.includes('[data-composer-markdown]') || selector.includes('.ProseMirror[aria-label="Ask ChatGPT"]') ? input : null,
  addEventListener: (type, cb) => { handlers[type] = cb; }
};
const ctx = {
  document: doc,
  location: { hostname: 'chatgpt.com' },
  Date: class extends Date { static now() { return time; } },
  chrome: {
    storage: { local: { set: async d => Object.assign(saved, d), get: async () => ({ armed: true }) } },
    runtime: {
      sendMessage: async m => { sent.push(m); return { accepted: true }; },
      onMessage: { addListener: fn => { ctx.ping = fn; } }
    }
  }
};
vm.createContext(ctx);
for (const f of ['parser.js', 'content.js']) vm.runInContext(fs.readFileSync(root + f, 'utf8'), ctx);
const tick = () => new Promise(r => setImmediate(r));
const key = (more = {}) => handlers.keydown({ isTrusted: true, key: 'Enter', target: input, ...more });
(async () => {
  assert.equal(saved.scriptVersion, '0.10');
  assert.equal(saved.scriptState.includes('ProseMirror'), true);
  assert.equal(sent.length, 0);
  input.innerText = '**به J A N زنگ بزن**';
  key({ shiftKey: true }); key({ isTrusted: false });
  await tick(); assert.equal(sent.length, 0);
  key(); await tick();
  assert.equal(sent.length, 1); assert.equal(sent[0].contact, 'JAN');
  time += 2000; input.innerText = 'لطفا با J A N تماس بگیر';
  handlers.click({ isTrusted: true, target: child }); await tick();
  assert.equal(sent.length, 2); assert.equal(sent[1].contact, 'JAN');
  time += 2000; input.innerText = 'سلام'; key(); await tick();
  assert.equal(sent.length, 2);
  time += 2000; input.innerText = 'به مریم زنگ بزن'; key({ isComposing: true }); await tick();
  assert.equal(sent.length, 2);
  const p = []; ctx.ping({ type: 'magic_ping' }, null, r => p.push(r));
  assert.equal(p[0].version, '0.10'); assert.equal(p[0].composer, true);
  console.log('PASS: observed ProseMirror composer, typed command, spaced initials, click, Enter, no history replay, IME and ping');
})().catch(e => { console.error(e); process.exitCode = 1; });
