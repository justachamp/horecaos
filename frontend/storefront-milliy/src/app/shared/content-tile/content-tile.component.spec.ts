import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentCarouselItem } from '../../types/content.types';
import { carouselItem, imageMedia, uuid, videoMedia } from '../../testing/content-fixtures';
import { ContentTileComponent } from './content-tile.component';

class FakeEvents {
  report = vi.fn();
}

function render(item: ContentCarouselItem, options: { reduced?: boolean; eager?: boolean } = {}) {
  const events = new FakeEvents();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: ContentEventsService, useValue: events },
      { provide: ReducedMotionService, useValue: { reduced: signal(options.reduced ?? false) } },
    ],
  });
  const fixture = TestBed.createComponent(ContentTileComponent);
  fixture.componentRef.setInput('item', item);
  if (options.eager) {
    fixture.componentRef.setInput('eager', true);
  }
  const failed = vi.fn();
  fixture.componentInstance.failed.subscribe(failed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, events, failed };
}

const clicksOn = (host: HTMLElement) =>
  host.querySelector<HTMLElement>('[data-testid="content-tile"]');

describe('ContentTileComponent: one banner or promo card', () => {
  it('shows the title and subtitle, and the image from its renditions', () => {
    const { host } = render(carouselItem({ title: 'Plov week', subtitle: 'Every day' }));
    const img = host.querySelector('img');

    expect(host.textContent).toContain('Plov week');
    expect(host.textContent).toContain('Every day');
    expect(img?.getAttribute('srcset')).toContain('variant=w400 400w');
    expect(img?.getAttribute('srcset')).toContain('variant=w1600 1600w');
    expect(img?.getAttribute('src')).toContain('variant=w1600');
    expect(img?.getAttribute('alt')).toBe('A plate of plov');
  });

  it('puts the language of the text on the text, so a fallback language is read in its own voice', () => {
    const { host } = render(carouselItem({ textLocale: 'ru' }));

    expect(host.querySelector('figcaption')?.getAttribute('lang')).toBe('ru');
  });

  it('leaves a language tag it cannot trust off the page', () => {
    const { host } = render(carouselItem({ textLocale: '"><script>' }));

    expect(host.querySelector('figcaption')?.hasAttribute('lang')).toBe(false);
  });

  it('draws content as text, never as markup', () => {
    const { host } = render(
      carouselItem({ title: '<img src=x onerror=alert(1)>', subtitle: '<b>bold</b>' }),
    );

    expect(host.querySelector('figcaption img')).toBeNull();
    expect(host.querySelector('figcaption b')).toBeNull();
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(host.textContent).toContain('<b>bold</b>');
  });

  it('is a link, with its call to action, when the link goes somewhere', () => {
    const { host } = render(
      carouselItem({ link: { type: 'PRODUCT', targetId: uuid(9) }, ctaLabel: 'Order' }),
    );

    expect(host.querySelector('a')?.getAttribute('href')).toBe(`/product/${uuid(9)}`);
    expect(host.querySelector('[data-testid="content-tile-cta"]')?.textContent?.trim()).toBe(
      'Order',
    );
  });

  it.each([
    ['NONE', { type: 'NONE' }],
    [
      'PROMOTION, which this storefront has no screen for',
      { type: 'PROMOTION', targetId: uuid(9) },
    ],
    ['an EXTERNAL address that is not https', { type: 'EXTERNAL', url: 'javascript:alert(1)' }],
  ] as const)('is a plain figure with no link and no call to action for %s', (_label, link) => {
    const { host } = render(carouselItem({ link, ctaLabel: 'Order' }));

    expect(host.querySelector('a')).toBeNull();
    expect(host.querySelector('[href]')).toBeNull();
    expect(host.querySelector('[data-testid="content-tile-cta"]')).toBeNull();
    expect(host.querySelector('figure')).not.toBeNull();
    expect(host.textContent).toContain('Plov week');
  });

  it('reports a click when a linked tile is tapped', () => {
    const { host, events } = render(
      carouselItem({ id: uuid(4), revisionNo: 7, link: { type: 'CART' } }),
    );

    host.querySelector('a')?.click();

    expect(events.report).toHaveBeenCalledWith(uuid(4), 7, 'CLICK');
    expect(events.report.mock.calls.filter(([, , event]) => event === 'CLICK')).toHaveLength(1);
  });

  it('reports no click for a tile that is not a link', () => {
    const { host, events } = render(carouselItem({ link: { type: 'NONE' } }));

    clicksOn(host)?.click();
    host.querySelector('figure')?.click();

    expect(events.report.mock.calls.filter(([, , event]) => event === 'CLICK')).toEqual([]);
  });

  it('reports an impression for this item and revision', () => {
    const { events } = render(carouselItem({ id: uuid(4), revisionNo: 7 }));

    expect(events.report).toHaveBeenCalledWith(uuid(4), 7, 'IMPRESSION');
  });

  it('says so when its image cannot be loaded, so the tile can go', () => {
    const { host, failed } = render(carouselItem());

    host.querySelector('img')?.dispatchEvent(new Event('error'));

    expect(failed).toHaveBeenCalledTimes(1);
  });

  it('plays a video tile muted and inline', () => {
    const { host } = render(carouselItem({ media: videoMedia() }));

    expect(host.querySelector('video')?.muted).toBe(true);
  });

  it('shows only the poster of a video tile under reduced motion', () => {
    const { host } = render(carouselItem({ media: videoMedia() }), { reduced: true });

    expect(host.querySelector('video')).toBeNull();
    expect(host.querySelector('img')?.getAttribute('src')).toBe('https://api.example.uz/m/p.jpg');
  });

  it('loads the first tile eagerly and the rest lazily', () => {
    expect(
      render(carouselItem(), { eager: true }).host.querySelector('img')?.getAttribute('loading'),
    ).toBe('eager');
    TestBed.resetTestingModule();
    expect(render(carouselItem()).host.querySelector('img')?.getAttribute('loading')).toBe('lazy');
  });

  it('keeps the shape of a banner whose image is wider than tall', () => {
    const { host } = render(carouselItem({ media: imageMedia({ widthPx: 1600, heightPx: 533 }) }));

    expect(
      host
        .querySelector<HTMLElement>('app-content-media')
        ?.style.getPropertyValue('--media-aspect'),
    ).toBe('1600 / 533');
  });
});
