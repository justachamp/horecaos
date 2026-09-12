/**
 * A sanitized block editor closes the XSS surface Delever's parity behaviour
 * left open — "accepting raw tenant HTML" (row `X.31`). This file is the
 * sanitizer half, independent of `q-rich-text`'s own block model so it can
 * be proven correct on its own: **every** dangerous construct below is
 * rejected, not merely escaped somewhere downstream.
 */

/** Block-level tags a caller's markup may use. Everything else's content survives as text; the tag itself does not. */
const ALLOWED_BLOCK_TAGS: ReadonlySet<string> = new Set([
  'P',
  'H1',
  'H2',
  'H3',
  'UL',
  'OL',
  'LI',
  'BLOCKQUOTE',
]);
/** Inline tags kept as themselves. Nothing here can carry an attribute an editor cares about. */
const ALLOWED_INLINE_TAGS: ReadonlySet<string> = new Set(['STRONG', 'EM', 'B', 'I', 'BR']);
/** Tags whose *content* is discarded outright — a script's text is code, not a caption. */
const DROP_WITH_CONTENT_TAGS: ReadonlySet<string> = new Set([
  'SCRIPT',
  'STYLE',
  'IFRAME',
  'OBJECT',
  'EMBED',
]);

const SAFE_URL_SCHEMES: ReadonlySet<string> = new Set(['http:', 'https:', 'mailto:', 'tel:']);

/**
 * Whether `href` is safe to keep on an anchor. Rejects `javascript:`,
 * `data:`, and anything else this editor has no reason to link to — the
 * allowlist is the safety property, not a blocklist of what to catch.
 */
function isSafeHref(href: string): boolean {
  const trimmed = href.trim();
  if (trimmed === '' || trimmed.startsWith('#') || trimmed.startsWith('/')) {
    return true;
  }
  try {
    const url = new URL(trimmed, 'https://example.invalid');
    return SAFE_URL_SCHEMES.has(url.protocol);
  } catch {
    return false;
  }
}

function sanitizeElement(source: Element, target: Node, doc: Document): void {
  for (const child of Array.from(source.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      target.appendChild(doc.createTextNode(child.textContent ?? ''));
      continue;
    }
    if (child.nodeType !== Node.ELEMENT_NODE) {
      // Comments, processing instructions — never carried through.
      continue;
    }
    const element = child as Element;
    const tag = element.tagName;

    if (DROP_WITH_CONTENT_TAGS.has(tag)) {
      continue;
    }

    if (ALLOWED_BLOCK_TAGS.has(tag) || ALLOWED_INLINE_TAGS.has(tag)) {
      const clean = doc.createElement(tag);
      sanitizeElement(element, clean, doc);
      target.appendChild(clean);
      continue;
    }

    if (tag === 'A') {
      const href = element.getAttribute('href') ?? '';
      const clean = doc.createElement('a');
      if (isSafeHref(href)) {
        clean.setAttribute('href', href.trim());
        clean.setAttribute('rel', 'noopener noreferrer');
      }
      sanitizeElement(element, clean, doc);
      target.appendChild(clean);
      continue;
    }

    // Every other element (div, span, table, img, style/class-bearing markup
    // Delever's export carries, …): the tag is dropped, its inline content
    // is kept by recursing straight into the target — a tenant's paragraph
    // wrapped in a `<div style="...">` still reads as a paragraph.
    sanitizeElement(element, target, doc);
  }
}

/**
 * Rejects a `<script>` tag, `javascript:`/`data:` hrefs, event-handler
 * attributes and every tag outside a small block/inline allowlist — keeping
 * the text content of everything else. Idempotent: sanitizing already-clean
 * output changes nothing, which is what lets `q-rich-text` run it on both
 * ingestion and emission without double-processing drift.
 */
export function sanitizeRichHtml(dirty: string): string {
  const doc = new DOMParser().parseFromString(`<div>${dirty}</div>`, 'text/html');
  const source = doc.body.firstElementChild;
  if (!source) {
    return '';
  }
  const output = doc.createElement('div');
  sanitizeElement(source, output, doc);
  return output.innerHTML;
}
