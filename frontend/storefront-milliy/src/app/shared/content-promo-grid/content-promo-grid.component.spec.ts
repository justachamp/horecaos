import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { carouselItem, uuid } from '../../testing/content-fixtures';
import { ContentPromoGridComponent } from './content-promo-grid.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string) => key;
  current = () => ({});
}

function render(items: readonly ContentCarouselItem[]) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ContentEventsService, useValue: { report: vi.fn() } },
      { provide: ReducedMotionService, useValue: { reduced: signal(false) } },
    ],
  });
  const fixture = TestBed.createComponent(ContentPromoGridComponent);
  fixture.componentRef.setInput('items', items);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

const promo = (n: number, title: string) =>
  carouselItem({ id: uuid(n), kind: 'PROMO_CARD', title });

describe('ContentPromoGridComponent', () => {
  it('draws nothing when there are no promo cards', () => {
    const { host } = render([]);

    expect(host.querySelector('[data-testid="content-promo-grid"]')).toBeNull();
  });

  it('is a labelled grid of the same tiles, in order', () => {
    const { host } = render([promo(1, 'One'), promo(2, 'Two'), promo(3, 'Three')]);
    const grid = host.querySelector('[data-testid="content-promo-grid"]');

    expect(grid?.tagName).toBe('SECTION');
    expect(grid?.getAttribute('aria-label')).toBe('content.promoGridLabel');
    expect(
      [...host.querySelectorAll('app-content-tile figcaption')].map((c) => c.textContent?.trim()),
    ).toEqual(['One', 'Two', 'Three']);
    expect(host.querySelector('ul[data-testid="content-promo-grid-list"]')).not.toBeNull();
  });

  it('loads every image lazily: a grid sits below the first screen', () => {
    const { host } = render([promo(1, 'One'), promo(2, 'Two')]);

    expect([...host.querySelectorAll('img')].map((img) => img.getAttribute('loading'))).toEqual([
      'lazy',
      'lazy',
    ]);
  });

  it('removes a tile whose image fails to load', () => {
    const { fixture, host } = render([promo(1, 'One'), promo(2, 'Two')]);

    host.querySelectorAll('img')[0].dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(host.querySelectorAll('app-content-tile')).toHaveLength(1);
    expect(host.textContent).toContain('Two');
    expect(host.textContent).not.toContain('One');
  });
});
