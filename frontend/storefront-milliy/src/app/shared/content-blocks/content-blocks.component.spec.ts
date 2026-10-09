import { TestBed } from '@angular/core/testing';

import type { EditorialBlock, Inline } from '../../types/editorial.types';
import { imageMedia } from '../../testing/content-fixtures';
import { ContentBlocksComponent } from './content-blocks.component';

function render(blocks: readonly EditorialBlock[], lang: string | null = null) {
  const fixture = TestBed.createComponent(ContentBlocksComponent);
  fixture.componentRef.setInput('blocks', blocks);
  fixture.componentRef.setInput('lang', lang);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

const run = (text: string, extra: Partial<Inline> = {}): Inline => ({ text, ...extra });
const paragraph = (...inlines: Inline[]): EditorialBlock => ({ type: 'PARAGRAPH', inlines });

describe('ContentBlocksComponent: a body, block by block', () => {
  it('draws nothing for no blocks', () => {
    const { host } = render([]);

    expect(host.querySelector('[data-testid="content-blocks"]')?.children.length ?? 0).toBe(0);
  });

  it('draws a paragraph as text', () => {
    const { host } = render([paragraph(run('Plov is '), run('ready'))]);

    expect(host.querySelector('p')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Plov is ready');
  });

  it('shows markup in a run as the characters it is made of, never as markup', () => {
    const { host } = render([
      paragraph(run('<b>bold?</b> <script>alert(1)</script> <img src=x onerror=alert(1)>')),
    ]);

    expect(host.querySelector('b')).toBeNull();
    expect(host.querySelector('script')).toBeNull();
    expect(host.querySelector('img')).toBeNull();
    expect(host.textContent).toContain('<b>bold?</b> <script>alert(1)</script>');
  });

  it('marks bold and italic runs', () => {
    const { host } = render([
      paragraph(run('plain'), run('b', { bold: true }), run('i', { italic: true })),
    ]);
    const spans = [...host.querySelectorAll('p span')];

    expect(
      spans.map((span) => [
        span.classList.contains('is-bold'),
        span.classList.contains('is-italic'),
      ]),
    ).toEqual([
      [false, false],
      [true, false],
      [false, true],
    ]);
  });

  it('draws a heading at level 2 or 3', () => {
    const { host } = render([
      { type: 'HEADING', level: 2, inlines: [run('Two')] },
      { type: 'HEADING', level: 3, inlines: [run('Three')] },
    ]);

    expect(host.querySelector('h2')?.textContent?.trim()).toBe('Two');
    expect(host.querySelector('h3')?.textContent?.trim()).toBe('Three');
  });

  it('draws a quote', () => {
    const { host } = render([{ type: 'QUOTE', inlines: [run('Eat well')] }]);

    expect(host.querySelector('blockquote')?.textContent?.trim()).toBe('Eat well');
  });

  it('draws an unordered and an ordered list', () => {
    const { host } = render([
      { type: 'LIST', ordered: false, items: [[run('rice')], [run('carrots')]] },
      { type: 'LIST', ordered: true, items: [[run('wash')], [run('fry')], [run('serve')]] },
    ]);

    expect([...host.querySelectorAll('ul > li')].map((li) => li.textContent?.trim())).toEqual([
      'rice',
      'carrots',
    ]);
    expect([...host.querySelectorAll('ol > li')].map((li) => li.textContent?.trim())).toEqual([
      'wash',
      'fry',
      'serve',
    ]);
  });

  it('draws a divider', () => {
    const { host } = render([paragraph(run('a')), { type: 'DIVIDER' }, paragraph(run('b'))]);

    expect(host.querySelectorAll('hr')).toHaveLength(1);
  });

  it('draws an image from its renditions, with its caption', () => {
    const { host } = render([
      {
        type: 'IMAGE',
        media: imageMedia(),
        caption: [run('Fresh '), run('out', { italic: true })],
      },
    ]);
    const img = host.querySelector('figure img');

    expect(img?.getAttribute('srcset')).toContain('400w');
    expect(img?.getAttribute('alt')).toBe('A plate of plov');
    expect(host.querySelector('figcaption')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Fresh out',
    );
  });

  it('draws an image without a caption as a figure with no caption', () => {
    const { host } = render([{ type: 'IMAGE', media: imageMedia() }]);

    expect(host.querySelector('figure')).not.toBeNull();
    expect(host.querySelector('figcaption')).toBeNull();
  });

  it('draws nothing for an image that has nothing to draw', () => {
    const { host } = render([{ type: 'IMAGE', media: imageMedia({ renditions: [] }) }]);

    expect(host.querySelector('figure')).toBeNull();
  });

  it('removes an image that cannot be loaded', () => {
    const { fixture, host } = render([
      paragraph(run('before')),
      { type: 'IMAGE', media: imageMedia() },
      paragraph(run('after')),
    ]);

    host.querySelector('figure img')?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(host.querySelector('figure')).toBeNull();
    expect(host.textContent).toContain('before');
    expect(host.textContent).toContain('after');
  });

  it('skips a block of a type it does not know and draws the rest in order', () => {
    const { host } = render([
      paragraph(run('first')),
      { type: 'EMBED', src: 'https://example.uz' } as unknown as EditorialBlock,
      { type: 'SCRIPT', code: 'alert(1)' } as unknown as EditorialBlock,
      paragraph(run('last')),
    ]);

    expect([...host.querySelectorAll('p')].map((p) => p.textContent?.trim())).toEqual([
      'first',
      'last',
    ]);
    expect(host.querySelector('script')).toBeNull();
  });

  it('puts the language of the body on the body', () => {
    expect(
      render([paragraph(run('x'))], 'ru')
        .host.querySelector('[data-testid="content-blocks"]')
        ?.getAttribute('lang'),
    ).toBe('ru');
    TestBed.resetTestingModule();
    expect(
      render([paragraph(run('x'))], null)
        .host.querySelector('[data-testid="content-blocks"]')
        ?.hasAttribute('lang'),
    ).toBe(false);
  });
});

describe('ContentBlocksComponent: links in a body', () => {
  const linked = (href: string) => render([paragraph(run('link text', { href }))]);

  it('opens an https link in a new tab that cannot reach back', () => {
    const anchor = linked('https://example.uz/page').host.querySelector('a');

    expect(anchor?.getAttribute('href')).toBe('https://example.uz/page');
    expect(anchor?.getAttribute('target')).toBe('_blank');
    expect(anchor?.getAttribute('rel')).toBe('noopener noreferrer');
    expect(anchor?.textContent?.trim()).toBe('link text');
  });

  it('makes a telephone number a call link in the same tab', () => {
    const anchor = linked('tel:+998901234567').host.querySelector('a');

    expect(anchor?.getAttribute('href')).toBe('tel:+998901234567');
    expect(anchor?.hasAttribute('target')).toBe(false);
  });

  it('makes an e-mail address a mail link in the same tab', () => {
    const anchor = linked('mailto:info@example.uz').host.querySelector('a');

    expect(anchor?.getAttribute('href')).toBe('mailto:info@example.uz');
    expect(anchor?.hasAttribute('target')).toBe(false);
  });

  it.each([
    'javascript:alert(1)',
    'JAVASCRIPT:alert(1)',
    'data:text/html,<b>x</b>',
    'vbscript:msgbox(1)',
    'http://example.uz/plain',
    'file:///etc/passwd',
    '//example.uz/x',
    '/relative',
    'https://user:pw@example.uz/',
    'mailto:a@b.uz?bcc=evil@example.uz',
    '',
  ])('shows the words of a link to %j as plain text, with nothing to click', (href) => {
    const { host } = linked(href);

    expect(host.querySelector('a')).toBeNull();
    expect(host.querySelector('[href]')).toBeNull();
    expect(host.textContent).toContain('link text');
  });

  it('keeps a linked run bold and italic', () => {
    const { host } = render([
      paragraph(run('x', { href: 'tel:+998901234567', bold: true, italic: true })),
    ]);
    const anchor = host.querySelector('a');

    expect(anchor?.classList.contains('is-bold')).toBe(true);
    expect(anchor?.classList.contains('is-italic')).toBe(true);
  });
});

describe('ContentBlocksComponent: the renderer creates nothing outside the block set (ADR 0157)', () => {
  const ELEMENTS = new Set([
    'APP-CONTENT-BLOCKS',
    'APP-CONTENT-MEDIA',
    'DIV',
    'P',
    'H2',
    'H3',
    'BLOCKQUOTE',
    'UL',
    'OL',
    'LI',
    'FIGURE',
    'FIGCAPTION',
    'HR',
    'A',
    'SPAN',
    'IMG',
  ]);
  const ATTRIBUTES: Record<string, ReadonlySet<string>> = {
    A: new Set(['href', 'target', 'rel', 'class']),
    IMG: new Set(['src', 'srcset', 'sizes', 'alt', 'loading', 'decoding', 'class']),
    'APP-CONTENT-MEDIA': new Set(['class', 'style', 'sizes']),
  };
  const COMMON = new Set(['class', 'lang', 'data-testid', 'ng-version']);

  const hostile =
    '<script>alert(1)</script><img src=x onerror=alert(1)><a href="javascript:alert(1)">x</a>';
  const CORPUS: readonly EditorialBlock[] = [
    paragraph(run(hostile), run(hostile, { bold: true, href: 'javascript:alert(1)' })),
    paragraph(
      run('ok', { href: 'https://example.uz/' }),
      run('tel', { href: 'tel:+998901234567' }),
    ),
    { type: 'HEADING', level: 2, inlines: [run(hostile)] },
    { type: 'HEADING', level: 3, inlines: [run(hostile, { href: 'data:text/html,x' })] },
    { type: 'QUOTE', inlines: [run(hostile, { italic: true })] },
    { type: 'LIST', ordered: true, items: [[run(hostile)], [run('b', { href: 'mailto:a@b.uz' })]] },
    { type: 'LIST', ordered: false, items: [[run(hostile, { href: 'vbscript:x' })]] },
    { type: 'IMAGE', media: imageMedia({ alt: hostile }), caption: [run(hostile)] },
    { type: 'DIVIDER' },
    { type: 'EMBED', html: hostile } as unknown as EditorialBlock,
    { type: 'IMAGE', media: imageMedia({ renditions: [] }) },
  ];

  it('makes only the elements and attributes of the block set, from a corpus of hostile text', () => {
    const { host } = render(CORPUS, 'ru');
    const found: string[] = [];

    // The fixture's own root element is the test's, not the renderer's.
    for (const element of host.querySelectorAll('*')) {
      if (!ELEMENTS.has(element.tagName)) {
        found.push(`<${element.tagName.toLowerCase()}>`);
      }
      const allowed = ATTRIBUTES[element.tagName] ?? new Set<string>();
      for (const attribute of element.getAttributeNames()) {
        const framework = attribute.startsWith('_ngcontent') || attribute.startsWith('_nghost');
        if (!framework && !COMMON.has(attribute) && !allowed.has(attribute)) {
          found.push(`<${element.tagName.toLowerCase()} ${attribute}>`);
        }
        if (attribute.startsWith('on')) {
          found.push(`<${element.tagName.toLowerCase()} ${attribute}>`);
        }
      }
    }

    expect(found).toEqual([]);
  });

  it('gives a link only an address that passed the scheme rule', () => {
    const { host } = render(CORPUS);

    expect([...host.querySelectorAll('a')].map((a) => a.getAttribute('href'))).toEqual([
      'https://example.uz/',
      'tel:+998901234567',
      'mailto:a@b.uz',
    ]);
  });

  it('shows the hostile text itself, as text', () => {
    const { host } = render(CORPUS);

    expect(host.textContent).toContain('<script>alert(1)</script>');
  });
});
