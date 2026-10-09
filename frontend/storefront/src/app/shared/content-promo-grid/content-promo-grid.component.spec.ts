import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { ContentPromoGridComponent } from './content-promo-grid.component';

function card(n: number): ContentCarouselItem {
  return {
    id: `card-${n}`,
    kind: 'PROMO_CARD',
    revisionNo: 1,
    title: `Card ${n}`,
    textLocale: 'en',
    media: {
      kind: 'IMAGE',
      alt: `Alt ${n}`,
      renditions: [{ widthPx: 400, url: `/m/${n}?variant=w400` }],
    },
    link: { type: 'CART' },
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
  const fixture = TestBed.createComponent(ContentPromoGridComponent);
  fixture.componentRef.setInput('items', items);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, images: () => Array.from(host.querySelectorAll('img')) };
}

describe('ContentPromoGridComponent', () => {
  it('is a two-column grid of promo cards', () => {
    const { host } = render([card(1), card(2), card(3)]);

    expect(host.querySelector('ul')?.className).toContain('grid-cols-2');
    expect(host.querySelectorAll('li')).toHaveLength(3);
  });

  it('is a labelled region', () => {
    const { host } = render([card(1)]);

    expect(host.querySelector('section')?.getAttribute('aria-label')).toBe('content.promoGrid');
  });

  it('draws the same tile as the carousel: srcset, alt and a link', () => {
    const { host, images } = render([card(1)]);

    expect(images()[0].getAttribute('srcset')).toBe('/m/1?variant=w400 400w');
    expect(images()[0].getAttribute('alt')).toBe('Alt 1');
    expect(host.querySelector('a')?.getAttribute('href')).toBe('/cart');
  });

  it('renders nothing when there are no cards', () => {
    const { host } = render([]);

    expect(host.querySelector('section')).toBeNull();
  });

  it('a card whose image fails disappears', () => {
    const { fixture, images } = render([card(1), card(2)]);

    images()[0].dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(images().map((i) => i.getAttribute('alt'))).toEqual(['Alt 2']);
  });
});
