// Capture only explicit user submissions from the current ChatGPT composer.
(() => {
  if (globalThis.__magicPhoneContent010) return;
  globalThis.__magicPhoneContent010 = true;
  const VERSION = '0.10';
  const parse = globalThis.MagicParseCall;

  // Current ChatGPT composer (observed DOM):
  // <div contenteditable="true" role="textbox" class="ProseMirror" data-composer-markdown aria-label="Ask ChatGPT">
  const composerSelector = [
    '#prompt-textarea',
    'textarea[data-testid="prompt-textarea"]',
    '[contenteditable="true"][data-testid="prompt-textarea"]',
    '[contenteditable="true"][data-composer-markdown]',
    '[contenteditable="true"].ProseMirror[aria-label="Ask ChatGPT"]',
    '[contenteditable="true"][role="textbox"][aria-label="Ask ChatGPT"]'
  ].join(',');

  const sendSelector = [
    'button[data-testid="send-button"]',
    'button[aria-label="Send prompt"]',
    'button[aria-label="Send message"]',
    'button[aria-label="Send"]',
    'button[aria-label="ارسال پیام"]',
    'button[aria-label="ارسال"]'
  ].join(',');

  let last = null;
  let numSeen = 0;
  const composer = () => document.querySelector(composerSelector);
  const read = node => {
    if (!node) return '';
    if (typeof node.value === 'string') return node.value.trim();
    return String(node.innerText || node.textContent || '').trim();
  };

  function report(detail) {
    chrome.storage.local.set({
      scriptLoadedAt: new Date().toLocaleTimeString('fa-IR'),
      scriptState: detail,
      scriptHost: location.hostname,
      scriptVersion: VERSION
    }).catch(() => {});
  }

  async function dispatch(text, source) {
    const name = typeof parse === 'function' ? parse(text) : null;
    numSeen++;
    const now = new Date().toLocaleTimeString('fa-IR');
    if (!name) {
      await chrome.storage.local.set({
        lastDetected: `ارسال پیام غیرتماسی (${now})`,
        lastMessageMode: 'composer_submit',
        lastResult: 'پیام ارسال شد؛ فرمان تماس تشخیص داده نشد.'
      });
      return;
    }

    try {
      await chrome.storage.local.set({
        lastDetected: `فرمان تماس: ${name} (${now})`,
        lastMessageMode: 'composer_submit',
        lastResult: `فرمان از ${source} تشخیص داده شد؛ بررسی کنترلر...`
      });
      const { armed } = await chrome.storage.local.get('armed');
      if (armed !== true) {
        await chrome.storage.local.set({ lastResult: 'فرمان تشخیص داده شد؛ افزونه خاموش است.' });
        return;
      }
      const ack = await chrome.runtime.sendMessage({ type: 'call_intent', contact: name });
      if (!ack?.accepted) {
        await chrome.storage.local.set({ lastResult: `فرمان رد شد: ${ack?.reason || 'no_ack'}` });
      }
    } catch (_) {
      await chrome.storage.local.set({ lastResult: 'ارتباط با افزونه قطع شد؛ صفحه را Refresh کن.' }).catch(() => {});
    }
  }

  function capture(source) {
    const text = read(composer());
    if (!text) return;
    const stamp = Date.now();
    // Avoid duplicate handling when Enter and the form submit event describe one action.
    if (last && last.text === text && stamp - last.stamp < 1800) return;
    last = { text, stamp };
    void dispatch(text, source);
  }

  // ChatGPT's default Enter action submits the prompt. Shift+Enter remains a newline.
  // Do not gate this on finding a send button: the observed UI changes that button's DOM.
  document.addEventListener('keydown', event => {
    if (!event.isTrusted || event.key !== 'Enter' || event.shiftKey || event.altKey ||
        event.ctrlKey || event.metaKey || event.isComposing || event.keyCode === 229 || event.repeat) return;
    const input = composer();
    if (!input || !input.contains(event.target)) return;
    capture('Enter');
  }, true);

  // Button click support, scoped to the known ChatGPT send-button labels/test id.
  document.addEventListener('click', event => {
    if (!event.isTrusted || !event.target?.closest) return;
    const input = composer();
    const button = event.target.closest(sendSelector);
    if (!input || !button || button.disabled || button.getAttribute('aria-disabled') === 'true') return;
    capture('Send');
  }, true);

  // Submit event is a fallback for composer implementations that use a form.
  document.addEventListener('submit', event => {
    if (!event.isTrusted) return;
    const input = composer();
    if (input && event.target?.contains?.(input)) capture('Submit');
  }, true);

  chrome.runtime.onMessage.addListener((message, _sender, reply) => {
    if (message?.type === 'magic_ping') {
      reply({
        alive: true,
        version: VERSION,
        seen: numSeen,
        parser: typeof parse === 'function',
        composer: !!composer()
      });
    }
  });

  report(typeof parse === 'function'
    ? (composer() ? 'فعال؛ کادر ProseMirror پیدا شد؛ آماده دریافت ارسال' : 'فعال؛ منتظر ساخته‌شدن کادر پیام')
    : 'خطا: parser.js بارگذاری نشده');
})();
