import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { ContentCarouselComponent } from './content-carousel.component';

function item(n: number, overrides: Partial<ContentCarouselItem> = {}): ContentCarouselItem {
  return {
    id: `item-${n}`,
    kind: 'BANNER',
    revisionNo: 1,
    title: `Banner ${n}`,
    textLocale: 'uz-Latn',
    media: {
      kind: 'IMAGE',
      alt: `Alt ${n}`,
      renditions: [{ widthPx: 800, url: `/m/${n}?variant=w800` }],
    },
    link: { type: 'NONE' },
    ...overrides,
  };
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

function render(items: ContentCarouselItem[]) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: ContentEventsService, useValue: { impression: vi.fn(), click: vi.fn() } },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: { reduced: () => false } },
    ],
  });
  const fixture = TestBed.createComponent(ContentCarouselComponent);
  fixture.componentRef.setInput('items', items);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    tiles: () => host.querySelectorAll('li'),
    images: () => Array.from(host.querySelectorAll('img')),
  };
}

describe('ContentCarouselComponent', () => {
  it('is a labelled region', () => {
    const { host } = render([item(1)]);

    const region = host.querySelector('section');
    expect(region?.getAttribute('aria-label')).toBe('content.carousel');
  });

  it('is a horizontal CSS scroll-snap scroller, not a script-driven slider', () => {
    const { host } = render([item(1), item(2)]);

    const scroller = host.querySelector('ul');
    expect(scroller?.className).toContain('overflow-x-auto');
    expect(scroller?.className).toContain('snap-x');
    expect(scroller?.className).toContain('snap-mandatory');
    expect(host.querySelector('li')?.className).toContain('snap-center');
  });

  it('can be scrolled from the keyboard', () => {
    const { host } = render([item(1), item(2)]);

    expect(host.querySelector('ul')?.getAttribute('tabindex')).toBe('0');
  });

  it('draws one tile per item, in the order of the document', () => {
    const { images } = render([item(3), item(1), item(2)]);

    expect(images().map((i) => i.getAttribute('alt'))).toEqual(['Alt 3', 'Alt 1', 'Alt 2']);
  });

  it('loads the first image eagerly and every other one lazily', () => {
    const { images } = render([item(1), item(2), item(3)]);

    expect(images().map((i) => i.getAttribute('loading'))).toEqual(['eager', 'lazy', 'lazy']);
  });

  it('gives the images a sizes hint', () => {
    const { images } = render([item(1)]);

    expect(images()[0].getAttribute('sizes')).toContain('vw');
  });

  it('renders nothing at all when there are no items', () => {
    const { host } = render([]);

    expect(host.querySelector('section')).toBeNull();
    expect(host.textContent?.trim()).toBe('');
  });

  it('a tile whose image fails to load disappears and the others stay', () => {
    const { fixture, images, tiles } = render([item(1), item(2), item(3)]);

    images()[1].dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(tiles()).toHaveLength(2);
    expect(images().map((i) => i.getAttribute('alt'))).toEqual(['Alt 1', 'Alt 3']);
  });

  it('when the first one disappears the next becomes the eager one', () => {
    const { fixture, images } = render([item(1), item(2)]);

    images()[0].dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(images().map((i) => i.getAttribute('loading'))).toEqual(['eager']);
  });

  it('when every tile fails the whole section disappears', () => {
    const { fixture, host, images } = render([item(1), item(2)]);

    images().forEach((i) => i.dispatchEvent(new Event('error')));
    fixture.detectChanges();

    expect(host.querySelector('section')).toBeNull();
  });
});
