// Parse only explicit call commands from a fresh user message. Never dials by itself.
(() => {
  function parseCall(input) {
    let text = String(input ?? '').normalize('NFKC')
      .replace(/[\u200b\u200c\u200d\u200e\u200f\ufeff]/gu, ' ')
      .replace(/ي/g, 'ی').replace(/ك/g, 'ک')
      .replace(/\s+/gu, ' ').trim();

    // Remove common Markdown wrappers and leading polite/addressing phrases.
    text = text.replace(/^\*\*(.+)\*\*$/su, '$1').trim();
    text = text.replace(/^جان\s*جان[،,:\s-]*/u, '');
    text = text.replace(/^(?:لطفا(?:ً)?|لطفأ)[،,:\s-]*/u, '');
    text = text.replace(/^["'«]+|["'»]+$/gu, '').trim();

    const match = text.match(/^(?:به|با)\s+(.+?)\s+(?:زنگ\s+بزن|تماس\s+بگیر|تلفن\s+بزن|تماس\s+بزن)[.!؟?،\s]*$/u);
    if (!match) return null;

    let name = match[1].trim().replace(/\s+/gu, ' ');
    if (!name || name.length > 65 || !/^[\p{L}\p{N}\s-]+$/u.test(name)) return null;

    // Treat spaced Latin initials (J A N) as the same contact key as JAN.
    const parts = name.split(/\s+/u);
    if (parts.length >= 2 && parts.every(part => /^[a-z]$/i.test(part))) {
      name = parts.join('');
    }
    return name;
  }
  globalThis.MagicParseCall = parseCall;
})();
