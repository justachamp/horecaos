import { TestBed } from '@angular/core/testing';

import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { EditorialBlock } from '../../types/editorial.types';
import { ContentBlocksComponent } from './content-blocks.component';

function render(blocks: EditorialBlock[], locale = '') {
  TestBed.configureTestingModule({
    providers: [{ provide: ReducedMotionService, useValue: { reduced: () => false } }],
  });
  const fixture = TestBed.createComponent(ContentBlocksComponent);
  fixture.componentRef.setInput('blocks', blocks);
  fixture.componentRef.setInput('locale', locale);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host };
}

const text = (s: string) => [{ text: s }];

describe('ContentBlocksComponent: one fixed template per block type', () => {
  it('a paragraph is a <p>', () => {
    const { host } = render([{ type: 'PARAGRAPH', inlines: text('Hello') }]);

    expect(host.querySelector('p')?.textContent).toBe('Hello');
  });

  it('a heading is an <h2> or an <h3> by its level', () => {
    const { host } = render([
      { type: 'HEADING', level: 2, inlines: text('Big') },
      { type: 'HEADING', level: 3, inlines: text('Small') },
    ]);

    expect(host.querySelector('h2')?.textContent).toBe('Big');
    expect(host.querySelector('h3')?.textContent).toBe('Small');
  });

  it('a quote is a <blockquote>', () => {
    const { host } = render([{ type: 'QUOTE', inlines: text('Said') }]);

    expect(host.querySelector('blockquote')?.textContent).toBe('Said');
  });

  it('an ordered list is an <ol> and an unordered one a <ul>, an <li> per item', () => {
    const { host } = render([
      { type: 'LIST', ordered: true, items: [text('one'), text('two')] },
      { type: 'LIST', ordered: false, items: [text('dot')] },
    ]);

    expect(Array.from(host.querySelectorAll('ol > li')).map((li) => li.textContent)).toEqual([
      'one',
      'two',
    ]);
    expect(Array.from(host.querySelectorAll('ul > li')).map((li) => li.textContent)).toEqual([
      'dot',
    ]);
  });

  it('a divider is an <hr>', () => {
    const { host } = render([{ type: 'DIVIDER' }]);

    expect(host.querySelector('hr')).not.toBeNull();
  });

  it('draws the blocks in the order of the document', () => {
    const { host } = render([
      { type: 'HEADING', level: 2, inlines: text('A') },
      { type: 'PARAGRAPH', inlines: text('B') },
      { type: 'DIVIDER' },
      { type: 'QUOTE', inlines: text('C') },
    ]);

    expect(
      Array.from(host.querySelectorAll('h2, p, hr, blockquote')).map((e) => e.tagName),
    ).toEqual(['H2', 'P', 'HR', 'BLOCKQUOTE']);
  });

  it('skips a block whose type it does not know and draws the rest', () => {
    const unknown = { type: 'TABLE', rows: [['x']] } as unknown as EditorialBlock;
    const { host } = render([
      { type: 'PARAGRAPH', inlines: text('before') },
      unknown,
      { type: 'PARAGRAPH', inlines: text('after') },
    ]);

    expect(Array.from(host.querySelectorAll('p')).map((p) => p.textContent)).toEqual([
      'before',
      'after',
    ]);
    expect(host.querySelector('table')).toBeNull();
  });

  it('draws text as text: a block can carry the characters of markup and no markup', () => {
    const { host } = render([
      { type: 'PARAGRAPH', inlines: text('<img src=x onerror=alert(1)>') },
      { type: 'LIST', ordered: false, items: [text('<script>alert(1)</script>')] },
    ]);

    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('script')).toBeNull();
    expect(host.querySelector('p')?.textContent).toBe('<img src=x onerror=alert(1)>');
  });

  it('draws a link inside a paragraph only after the scheme rule', () => {
    const { host } = render([
      {
        type: 'PARAGRAPH',
        inlines: [
          { text: 'good', href: 'https://example.com' },
          { text: 'bad', href: 'javascript:alert(1)' },
        ],
      },
    ]);

    const anchors = host.querySelectorAll('a');
    expect(anchors).toHaveLength(1);
    expect(anchors[0].getAttribute('href')).toBe('https://example.com/');
    expect(host.querySelector('p')?.textContent).toBe('goodbad');
  });

  it('marks the language of the body', () => {
    const { host } = render([{ type: 'PARAGRAPH', inlines: text('Привет') }], 'ru');

    expect(host.querySelector('[lang="ru"]')).not.toBeNull();
  });

  describe('images', () => {
    const image = (n = 1): EditorialBlock => ({
      type: 'IMAGE',
      media: {
        kind: 'IMAGE',
        alt: `Photo ${n}`,
        renditions: [
          { widthPx: 400, url: `/m/${n}?variant=w400` },
          { widthPx: 800, url: `/m/${n}?variant=w800` },
        ],
      },
      caption: text(`Caption ${n}`),
    });

    it('is a figure with the rendition srcset, the alt text and the caption', () => {
      const { host } = render([image()]);

      const img = host.querySelector('figure img');
      expect(img?.getAttribute('srcset')).toBe('/m/1?variant=w400 400w, /m/1?variant=w800 800w');
      expect(img?.getAttribute('alt')).toBe('Photo 1');
      expect(img?.getAttribute('loading')).toBe('lazy');
      expect(host.querySelector('figcaption')?.textContent).toBe('Caption 1');
    });

    it('a figure with no caption has no figcaption', () => {
      const bare: EditorialBlock = {
        ...(image() as Extract<EditorialBlock, { type: 'IMAGE' }>),
        caption: undefined,
      };
      const { host } = render([bare]);

      expect(host.querySelector('figure')).not.toBeNull();
      expect(host.querySelector('figcaption')).toBeNull();
    });

    it('a picture that does not load takes its block with it; the rest of the body stays', () => {
      const { fixture, host } = render([
        { type: 'PARAGRAPH', inlines: text('before') },
        image(1),
        image(2),
      ]);

      host.querySelectorAll('figure img')[0].dispatchEvent(new Event('error'));
      fixture.detectChanges();

      expect(host.querySelectorAll('figure')).toHaveLength(1);
      expect(host.querySelector('figure img')?.getAttribute('alt')).toBe('Photo 2');
      expect(host.querySelector('p')?.textContent).toBe('before');
    });
  });
});
