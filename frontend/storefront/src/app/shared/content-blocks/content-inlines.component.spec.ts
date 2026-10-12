import { TestBed } from '@angular/core/testing';

import type { EditorialInline } from '../../types/editorial.types';
import { ContentInlinesComponent } from './content-inlines.component';

function render(inlines: EditorialInline[]) {
  const fixture = TestBed.createComponent(ContentInlinesComponent);
  fixture.componentRef.setInput('inlines', inlines);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { host, anchors: () => Array.from(host.querySelectorAll('a')) };
}

describe('ContentInlinesComponent: text only by interpolation, links only by the scheme rule', () => {
  it('draws the runs in order with their text exactly', () => {
    const { host } = render([{ text: 'Hello ' }, { text: 'big ', bold: true }, { text: 'world' }]);

    expect(host.textContent).toBe('Hello big world');
  });

  it('bold is <strong>, italic is <em>, both is both', () => {
    const { host } = render([
      { text: 'a', bold: true },
      { text: 'b', italic: true },
      { text: 'c', bold: true, italic: true },
    ]);

    expect(host.querySelectorAll('strong')).toHaveLength(2);
    expect(host.querySelectorAll('em')).toHaveLength(2);
    expect(host.querySelector('strong em')?.textContent).toBe('c');
  });

  it('shows markup as the characters it is, never as elements', () => {
    const { host } = render([
      { text: '<img src=x onerror=alert(1)>' },
      { text: '<script>alert(2)</script>', bold: true },
    ]);

    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('script')).toBeNull();
    expect(host.textContent).toBe('<img src=x onerror=alert(1)><script>alert(2)</script>');
  });

  describe('links', () => {
    it('an https href is a link that opens in a new tab and cannot reach back to the page', () => {
      const { anchors } = render([{ text: 'site', href: 'https://example.com/a?b=1' }]);

      expect(anchors()).toHaveLength(1);
      expect(anchors()[0].getAttribute('href')).toBe('https://example.com/a?b=1');
      expect(anchors()[0].getAttribute('target')).toBe('_blank');
      expect(anchors()[0].getAttribute('rel')).toBe('noopener noreferrer');
      expect(anchors()[0].textContent).toBe('site');
    });

    it('a tel: href is a link that stays on the page', () => {
      const { anchors } = render([{ text: 'call', href: 'tel:+998901234567' }]);

      expect(anchors()[0].getAttribute('href')).toBe('tel:+998901234567');
      expect(anchors()[0].hasAttribute('target')).toBe(false);
    });

    it('a mailto: href is a link that stays on the page', () => {
      const { anchors } = render([{ text: 'write', href: 'mailto:hello@example.uz' }]);

      expect(anchors()[0].getAttribute('href')).toBe('mailto:hello@example.uz');
      expect(anchors()[0].hasAttribute('target')).toBe(false);
    });

    it.each([
      'http://example.com/',
      'javascript:alert(1)',
      'JAVASCRIPT:alert(1)',
      'data:text/html,<b>x</b>',
      'vbscript:x',
      'file:///etc/passwd',
      '//example.com/',
      '/relative',
      'java\tscript:alert(1)',
      'not a url',
    ])('an href of %j is not a link: the text is drawn plain', (href) => {
      const { host, anchors } = render([{ text: 'click me', href }]);

      expect(anchors()).toHaveLength(0);
      expect(host.textContent).toBe('click me');
      expect(host.innerHTML).not.toContain('href');
    });

    it('a bold link keeps its emphasis', () => {
      const { anchors } = render([{ text: 'site', href: 'https://example.com', bold: true }]);

      expect(anchors()[0].className).toContain('font-bold');
    });
  });
});
