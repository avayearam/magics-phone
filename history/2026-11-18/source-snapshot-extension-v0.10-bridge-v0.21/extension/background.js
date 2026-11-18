let busy = false;
const CHAT_URL = /^https:\/\/(chatgpt\.com|chat\.openai\.com)\//;
async function attachToTab(tab) {
  if (!tab?.id || !CHAT_URL.test(tab.url || '')) return {ok:false,reason:'not_chatgpt_tab'};
  try {
    const pong = await chrome.tabs.sendMessage(tab.id,{type:'magic_ping'});
    if (pong?.alive && pong.parser && pong.version === '0.10') return {ok:true,version:pong.version,seen:pong.seen,composer:pong.composer,reused:true};
  } catch (_) {}
  try {
    await chrome.scripting.executeScript({target:{tabId:tab.id},files:['parser.js','content.js']});
    const pong = await chrome.tabs.sendMessage(tab.id,{type:'magic_ping'});
    if (pong?.alive && pong.parser && pong.version === '0.10') return {ok:true,version:pong.version,seen:pong.seen,composer:pong.composer,reused:false};
    return {ok:false,reason:'no_response_after_injection'};
  } catch (e) {
    return {ok:false,reason:'injection_denied_or_failed'};
  }
}
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.type === 'probe_tab') {
    (async () => {
      const tabs=await chrome.tabs.query({active:true,currentWindow:true});
      return attachToTab(tabs[0]);
    })().then(sendResponse).catch(() => sendResponse({ok:false,reason:'tab_query_failed'}));
    return true;
  }
  if (msg?.type !== 'call_intent') return;
  if (!sender.tab || !CHAT_URL.test(sender.tab.url || '')) {
    sendResponse({accepted:false,reason:'invalid_source'});return;
  }
  if (busy || typeof msg.contact !== 'string' || msg.contact.length > 65) {
    sendResponse({accepted:false,reason:'busy_or_invalid'});return;
  }
  busy = true;
  sendResponse({accepted:true});
  (async () => {
    try {
      const {armed,token} = await chrome.storage.local.get(['armed','token']);
      if (armed !== true || typeof token !== 'string' || token.length < 32) {
        await chrome.storage.local.set({lastResult:'افزونه خاموش یا کد اتصال نامعتبر است.'});return;
      }
      await chrome.storage.local.set({lastResult:'در حال بررسی نام در دفترچه محلی...' });
      const inspect = await fetch('http://127.0.0.1:18763/check-contact', {
        method:'POST', headers:{'Content-Type':'application/json','X-Magic-Token':token},
        body:JSON.stringify({contact:msg.contact}),signal:AbortSignal.timeout(5000)
      });
      const lookup = await inspect.json();
      if (!inspect.ok || !lookup.ok) {
        const status = lookup.reason === 'contact_not_found_in_local_address_book'
          ? 'این نام در دفترچه محلی پیدا نشد یا چند مخاطب با نام یکسان وجود دارد.'
          : `خطا در بررسی نام: ${lookup.reason || inspect.status}`;
        await chrome.storage.local.set({lastResult:status});
        return;
      }
      await chrome.storage.local.set({lastResult:`مخاطب پیدا شد: ${lookup.display_name}؛ منتظر تأیید تماس...` });
      const response = await fetch('http://127.0.0.1:18763/call', {
        method:'POST',headers:{'Content-Type':'application/json','X-Magic-Token':token},
        body:JSON.stringify({contact:msg.contact}),signal:AbortSignal.timeout(90000)
      });
      const data = await response.json();
      const labels={
        contact_not_found_in_local_address_book:'مخاطب با این نام در دفترچه محلی پیدا نشد (یا نام تکراری است).',
        pairing_required:'کد اتصال افزونه با کنترلر یکسان نیست.',
        declined_by_user:'تماس توسط کاربر رد شد.',
        invalid_origin_or_path:'کنترلر مسیر فرمان را رد کرد.',
      };
      const result=data.reason || data.result || `HTTP ${response.status}`;
      await chrome.storage.local.set({lastResult:labels[result] || result});
    } catch (err) {
      await chrome.storage.local.set({lastResult:'کنترلر پاسخ نداد؛ پنجره START_PHONE_CONTROL را بررسی کن.'});
    } finally {busy=false;}
  })();
});
