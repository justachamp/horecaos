import { describe, expect, it } from 'vitest';

import { sanitizeRichHtml } from './rich-text-sanitizer';

describe('sanitizeRichHtml', () => {
  it('rejects a script tag entirely — not merely escaped, gone', () => {
    // The row's whole point: the parity behaviour being replaced is Delever
    // accepting raw tenant HTML, and this is the XSS surface that closes.
    const output = sanitizeRichHtml('<p>Hello</p><script>alert(document.cookie)</script>');

    expect(output).not.toContain('<script');
    expect(output).not.toContain('alert(document.cookie)');
    expect(output).toContain('Hello');
  });

  it('rejects a javascript: href, keeping the link text but not the link', () => {
    const output = sanitizeRichHtml('<p><a href="javascript:alert(1)">click me</a></p>');

    expect(output).not.toContain('javascript:');
    expect(output).not.toContain('href');
    expect(output).toContain('click me');
  });

  it('rejects a data: href the same way', () => {
    const output = sanitizeRichHtml('<a href="data:text/html,<script>alert(1)</script>">go</a>');

    expect(output).not.toContain('data:');
    expect(output).not.toContain('<script');
  });

  it('keeps a real https href, with rel="noopener noreferrer" added', () => {
    const output = sanitizeRichHtml('<a href="https://horecaos.uz/terms">terms</a>');

    expect(output).toContain('href="https://horecaos.uz/terms"');
    expect(output).toContain('rel="noopener noreferrer"');
  });

  it('strips an inline event-handler attribute along with every other attribute', () => {
    const output = sanitizeRichHtml('<p onclick="alert(1)" style="color:red">text</p>');

    expect(output).not.toContain('onclick');
    expect(output).not.toContain('style');
    expect(output).toContain('text');
  });

  it('drops an iframe and its content, not just the tag', () => {
    const output = sanitizeRichHtml('<iframe src="https://evil.example">trapped text</iframe>');

    expect(output).not.toContain('iframe');
    expect(output).not.toContain('trapped text');
  });

  it('keeps block structure — headings, paragraphs and lists — through the allowlist', () => {
    const output = sanitizeRichHtml(
      '<h2>Заголовок</h2><ul><li>Пункт один</li><li>Пункт два</li></ul>',
    );

    expect(output).toContain('<h2>Заголовок</h2>');
    expect(output).toContain('<li>Пункт один</li>');
  });

  it('drops a disallowed tag but keeps its text, so a Delever <div style="..."> still reads as a paragraph', () => {
    const output = sanitizeRichHtml(
      '<div class="delever-block" style="font-size:40px">Условия оказания услуг</div>',
    );

    expect(output).not.toContain('<div');
    expect(output).not.toContain('style');
    expect(output).toContain('Условия оказания услуг');
  });

  it('is idempotent — sanitizing its own output changes nothing', () => {
    const once = sanitizeRichHtml('<p>Hello <strong>world</strong></p><script>alert(1)</script>');
    const twice = sanitizeRichHtml(once);

    expect(twice).toBe(once);
  });

  it('returns an empty string for an empty document', () => {
    expect(sanitizeRichHtml('')).toBe('');
  });
});
