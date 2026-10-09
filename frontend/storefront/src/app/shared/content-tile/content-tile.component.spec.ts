import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentCarouselItem, ContentLink } from '../../types/content.types';
import { ContentTileComponent } from './content-tile.component';

const ID = '00000000-0000-0000-0000-000000000009';

function item(overrides: Partial<ContentCarouselItem> = {}): ContentCarouselItem {
  return {
    id: 'item-1',
    kind: 'BANNER',
    revisionNo: 4,
    title: 'Summer offer',
    textLocale: 'ru',
    media: {
      kind: 'IMAGE',
      alt: 'Cold drinks',
      renditions: [
        { widthPx: 400, url: '/m/a?variant=w400' },
        { widthPx: 800, url: '/m/a?variant=w800' },
      ],
    },
    link: { type: 'NONE' },
    ...overrides,
  };
}

class FakeEvents {
  impression = vi.fn();
  click = vi.fn();
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function render(content: ContentCarouselItem, inputs: Record<string, unknown> = {}) {
  const events = new FakeEvents();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: ContentEventsService, useValue: events },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: { reduced: () => false } },
    ],
  });
  const fixture = TestBed.createComponent(ContentTileComponent);
  fixture.componentRef.setInput('item', content);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  const failed = vi.fn();
  fixture.componentInstance.failed.subscribe(failed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, events, failed };
}

describe('ContentTileComponent: the picture', () => {
  it('draws the rendition srcset with the alt text from the document', () => {
    const { host } = render(item());

    const img = host.querySelector('img');
    expect(img?.getAttribute('srcset')).toBe('/m/a?variant=w400 400w, /m/a?variant=w800 800w');
    expect(img?.getAttribute('alt')).toBe('Cold drinks');
  });

  it('is lazy by default and eager when it is the first', () => {
    expect(render(item()).host.querySelector('img')?.getAttribute('loading')).toBe('lazy');
    TestBed.resetTestingModule();
    expect(render(item(), { eager: true }).host.querySelector('img')?.getAttribute('loading')).toBe(
      'eager',
    );
  });

  it('says it failed when its image does not load', () => {
    const { host, failed } = render(item());

    host.querySelector('img')?.dispatchEvent(new Event('error'));

    expect(failed).toHaveBeenCalledTimes(1);
  });

  it('draws a video as a muted inline video with its poster', () => {
    const { host } = render(
      item({
        media: { kind: 'VIDEO', alt: 'clip', url: '/m/v.mp4', posterUrl: '/m/p.jpg' },
      }),
    );

    const video = host.querySelector('video');
    expect(video?.hasAttribute('muted')).toBe(true);
    expect(video?.hasAttribute('playsinline')).toBe(true);
    expect(video?.getAttribute('preload')).toBe('metadata');
    expect(video?.getAttribute('poster')).toBe('/m/p.jpg');
  });
});

describe('ContentTileComponent: the words', () => {
  it('shows the title, subtitle and call to action in the language they are written in', () => {
    const { host } = render(
      item({
        subtitle: 'Ice cold',
        ctaLabel: 'Order now',
        link: { type: 'CART' },
      }),
    );

    expect(host.querySelector('h3')?.textContent).toContain('Summer offer');
    expect(host.textContent).toContain('Ice cold');
    expect(host.textContent).toContain('Order now');
    expect(host.querySelector('[lang="ru"]')).not.toBeNull();
  });

  it('writes text as text: markup in a title is shown, not parsed', () => {
    const { host } = render(item({ title: '<img src=x onerror=alert(1)><b>bold</b>' }));

    expect(host.querySelector('h3 b')).toBeNull();
    expect(host.querySelectorAll('img')).toHaveLength(1);
    expect(host.querySelector('h3')?.textContent).toBe('<img src=x onerror=alert(1)><b>bold</b>');
  });

  it('shows no call to action on a tile that is not a link, even if the document has a label', () => {
    const { host } = render(item({ ctaLabel: 'Order now', link: { type: 'NONE' } }));

    expect(host.textContent).not.toContain('Order now');
  });
});

describe('ContentTileComponent: links', () => {
  const link = (l: ContentLink) => render(item({ link: l, ctaLabel: 'Go' }));

  it('NONE is a plain figure, not a link', () => {
    const { host } = link({ type: 'NONE' });

    expect(host.querySelector('a')).toBeNull();
    expect(host.querySelector('figure')).not.toBeNull();
  });

  it('PROMOTION is treated as NONE: this storefront has no promotion screen', () => {
    const { host } = link({ type: 'PROMOTION', targetId: ID });

    expect(host.querySelector('a')).toBeNull();
    expect(host.querySelector('figure')).not.toBeNull();
    expect(host.textContent).not.toContain('Go');
  });

  it('PRODUCT goes to the product page', () => {
    const { host } = link({ type: 'PRODUCT', targetId: ID });

    expect(host.querySelector('a')?.getAttribute('href')).toBe(`/product/${ID}`);
    expect(host.textContent).toContain('Go');
  });

  it('CATEGORY goes to the category page', () => {
    const { host } = link({ type: 'CATEGORY', targetId: ID });

    expect(host.querySelector('a')?.getAttribute('href')).toBe('/category');
  });

  it('CART goes to the cart', () => {
    const { host } = link({ type: 'CART' });

    expect(host.querySelector('a')?.getAttribute('href')).toBe('/cart');
  });

  it('EXTERNAL https is a new-tab link with noopener noreferrer', () => {
    const { host } = link({ type: 'EXTERNAL', url: 'https://example.com/page' });

    const anchor = host.querySelector('a');
    expect(anchor?.getAttribute('href')).toBe('https://example.com/page');
    expect(anchor?.getAttribute('target')).toBe('_blank');
    expect(anchor?.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it.each(['http://example.com/', 'javascript:alert(1)', 'data:text/html,x'])(
    'EXTERNAL %s is re-checked in the client and is not a link',
    (url) => {
      const { host } = link({ type: 'EXTERNAL', url });

      expect(host.querySelector('a')).toBeNull();
      expect(host.querySelector('figure')).not.toBeNull();
    },
  );
});

describe('ContentTileComponent: the counters', () => {
  it('counts an impression of this item and revision', () => {
    const { events } = render(item());

    expect(events.impression).toHaveBeenCalledWith('item-1', 4);
  });

  it('counts a click when a linked tile is activated, once', async () => {
    const { fixture, host, events } = render(item({ link: { type: 'CART' } }));

    host.querySelector<HTMLAnchorElement>('a')?.click();
    await fixture.whenStable();

    expect(events.click).toHaveBeenCalledTimes(1);
    expect(events.click).toHaveBeenCalledWith('item-1', 4);
  });

  it('counts no click on a tile that is not a link', () => {
    const { host, events } = render(item());

    host.querySelector('figure')?.click();

    expect(events.click).not.toHaveBeenCalled();
  });
});
