import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { carouselItem, imageMedia, uuid, videoMedia } from '../../testing/content-fixtures';
import { ContentCarouselComponent } from './content-carousel.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string) => key;
  current = () => ({});
}

function render(items: readonly ContentCarouselItem[], reduced = false) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ContentEventsService, useValue: { report: vi.fn() } },
      { provide: ReducedMotionService, useValue: { reduced: signal(reduced) } },
    ],
  });
  const fixture = TestBed.createComponent(ContentCarouselComponent);
  fixture.componentRef.setInput('items', items);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host };
}

const tiles = (host: HTMLElement) => [...host.querySelectorAll('app-content-tile')];

describe('ContentCarouselComponent', () => {
  it('draws nothing at all when there is nothing to show', () => {
    const { host } = render([]);

    expect(host.querySelector('[data-testid="content-carousel"]')).toBeNull();
    expect(host.textContent?.trim()).toBe('');
  });

  it('is a labelled region holding one tile per item, in order', () => {
    const { host } = render([
      carouselItem({ id: uuid(1), title: 'First' }),
      carouselItem({ id: uuid(2), title: 'Second', kind: 'PROMO_CARD' }),
      carouselItem({ id: uuid(3), title: 'Third' }),
    ]);
    const region = host.querySelector('[data-testid="content-carousel"]');

    expect(region?.tagName).toBe('SECTION');
    expect(region?.getAttribute('aria-label')).toBe('content.carouselLabel');
    expect(
      tiles(host).map((tile) => tile.querySelector('figcaption')?.textContent?.trim()),
    ).toEqual(['First', 'Second', 'Third']);
  });

  it('scrolls sideways as a snapping list that the keyboard can reach', () => {
    const { host } = render([carouselItem()]);
    const track = host.querySelector('[data-testid="content-carousel-track"]');

    expect(track?.tagName).toBe('UL');
    expect(track?.getAttribute('tabindex')).toBe('0');
    expect(track?.querySelectorAll(':scope > li')).toHaveLength(1);
  });

  it('loads the first image eagerly and every other one lazily', () => {
    const { host } = render([
      carouselItem({ id: uuid(1) }),
      carouselItem({ id: uuid(2) }),
      carouselItem({ id: uuid(3) }),
    ]);

    expect([...host.querySelectorAll('img')].map((img) => img.getAttribute('loading'))).toEqual([
      'eager',
      'lazy',
      'lazy',
    ]);
  });

  it('removes a tile whose image fails to load, and keeps the others where they were', () => {
    const { fixture, host } = render([
      carouselItem({ id: uuid(1), title: 'First' }),
      carouselItem({ id: uuid(2), title: 'Second' }),
      carouselItem({ id: uuid(3), title: 'Third' }),
    ]);

    host.querySelectorAll('img')[1].dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(
      tiles(host).map((tile) => tile.querySelector('figcaption')?.textContent?.trim()),
    ).toEqual(['First', 'Third']);
  });

  it('draws nothing once every tile has failed', () => {
    const { fixture, host } = render([carouselItem({ id: uuid(1) })]);

    host.querySelector('img')?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="content-carousel"]')).toBeNull();
  });

  it('leaves out a tile that has no media it can draw', () => {
    const { host } = render([
      carouselItem({ id: uuid(1), title: 'No picture', media: imageMedia({ renditions: [] }) }),
      carouselItem({ id: uuid(2), title: 'Fine' }),
    ]);

    expect(tiles(host)).toHaveLength(1);
    expect(host.textContent).not.toContain('No picture');
  });

  it('leaves out a video with no poster for a visitor who asked for reduced motion', () => {
    const noPoster = videoMedia({ posterUrl: undefined });

    expect(tiles(render([carouselItem({ media: noPoster })], true).host)).toHaveLength(0);
    TestBed.resetTestingModule();
    expect(tiles(render([carouselItem({ media: noPoster })], false).host)).toHaveLength(1);
  });
});
