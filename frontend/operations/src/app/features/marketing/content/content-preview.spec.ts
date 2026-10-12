import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ContentApi, PreviewDocument } from './content-api';
import { ContentPreview } from './content-preview';
import { ContentReferences } from './content-references';

const SCOPE = { tenantId: 't', brandId: 'b' };
const CHANNELS = [
  { code: 'web', displayName: 'Web' },
  { code: 'tg', displayName: 'Telegram' },
];

const IMAGE = (path: string) => ({
  kind: 'IMAGE' as const,
  renditions: [
    { widthPx: 200, url: `${path}?variant=w200` },
    { widthPx: 800, url: `${path}?variant=w800` },
  ],
});

const DOCUMENT: PreviewDocument = {
  locale: 'ru',
  carousel: [
    {
      id: 'card-1',
      kind: 'BANNER',
      title: 'Скидка',
      subtitle: 'Только сегодня',
      ctaLabel: 'Заказать',
      textLocale: 'ru',
      media: IMAGE('/api/v1/storefront/m/1'),
      link: { type: 'NONE' },
    },
    {
      id: 'card-2',
      kind: 'PROMO_CARD',
      title: 'Видео',
      textLocale: 'ru',
      media: {
        kind: 'VIDEO',
        renditions: [],
        posterUrl: '/api/v1/storefront/m/poster?variant=w800',
      },
      link: { type: 'NONE' },
    },
  ],
  groups: [{ id: 'group-1', title: 'Популярное', textLocale: 'ru', productIds: ['a', 'b'] }],
  stories: [{ id: 'story-1', title: 'Новинки', slides: [] }],
  promoGrid: [
    {
      id: 'grid-1',
      kind: 'PROMO_CARD',
      title: 'Комбо',
      textLocale: 'ru',
      media: IMAGE('/api/v1/storefront/m/2'),
      link: { type: 'NONE' },
    },
  ],
  popup: { id: 'popup-1', title: 'Добро пожаловать', showAfterSeconds: 5 },
};

class FakeApi {
  readonly calls: { channel: string; locale: string | null; asOf: string | null }[] = [];
  result: Promise<PreviewDocument> = Promise.resolve(DOCUMENT);
  preview(
    _scope: unknown,
    channel: string,
    locale: string | null,
    asOf: string | null,
  ): Promise<PreviewDocument> {
    this.calls.push({ channel, locale, asOf });
    return this.result;
  }
}

function setup(
  highlightId: string | null = null,
  channels: readonly { code: string; displayName: string }[] = CHANNELS,
): {
  fixture: ReturnType<typeof TestBed.createComponent<ContentPreview>>;
  api: FakeApi;
  root: HTMLElement;
} {
  const api = new FakeApi();
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: api },
      { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
      {
        provide: ContentReferences,
        useValue: { contentChannels: () => channels, categoryLabel: (id: string) => `cat:${id}` },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentPreview);
  fixture.componentRef.setInput('locales', ['ru', 'uz-Latn', 'en']);
  fixture.componentRef.setInput('startLocale', 'uz-Latn');
  fixture.componentRef.setInput('highlightId', highlightId);
  fixture.detectChanges();
  return { fixture, api, root: fixture.nativeElement as HTMLElement };
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

describe('ContentPreview (ADR 0157: the storefront document built from drafts)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('builds the preview on the first content channel, in the language being edited', async () => {
    const { fixture, api } = setup();
    await settle();
    fixture.detectChanges();

    expect(api.calls).toEqual([{ channel: 'web', locale: 'uz-Latn', asOf: null }]);
  });

  it('draws the carousel, the grid, the groups, the stories and the pop-up, in the document’s order', async () => {
    const { fixture, root } = setup();
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-preview-carousel"]')?.textContent).toContain(
      'Скидка',
    );
    expect(root.querySelector('[data-testid="content-preview-carousel"]')?.textContent).toContain(
      'Заказать',
    );
    expect(root.querySelector('[data-testid="content-preview-grid"]')?.textContent).toContain(
      'Комбо',
    );
    expect(root.querySelector('[data-testid="content-preview-group"]')?.textContent).toContain('2');
    expect(root.querySelector('[data-testid="content-preview-stories"]')?.textContent).toContain(
      'Новинки',
    );
    expect(root.querySelector('[data-testid="content-preview-popup"]')?.textContent).toContain(
      'Pop-up after 5 s',
    );
    expect(root.querySelector('[data-testid="content-preview-empty"]')).toBeNull();
  });

  it('draws an image from a rendition, never the original, and a video by its poster', async () => {
    const { fixture, root } = setup();
    await settle();
    fixture.detectChanges();

    const sources = Array.from(root.querySelectorAll('img')).map((i) => i.getAttribute('src'));
    expect(sources).toContain(`${environment.apiBaseUrl}/api/v1/storefront/m/1?variant=w800`);
    expect(sources).toContain(`${environment.apiBaseUrl}/api/v1/storefront/m/poster?variant=w800`);
    expect(sources.every((s) => s !== null && s.includes('variant='))).toBe(true);
    expect(root.querySelector('video')).toBeNull();
  });

  it('outlines the item being edited and says nothing about it being absent', async () => {
    const { fixture, root } = setup('card-1');
    await settle();
    fixture.detectChanges();

    expect(root.querySelectorAll('.preview__hit')).toHaveLength(1);
    expect(root.querySelector('[data-testid="content-preview-absent"]')).toBeNull();
  });

  it('says why an item might be missing when it is not in the document', async () => {
    const { fixture, root } = setup('not-in-document');
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-preview-absent"]')).toBeTruthy();
    expect(root.querySelectorAll('.preview__hit')).toHaveLength(0);
  });

  it('asks again for another channel, another language and a chosen day', async () => {
    const { fixture, api, root } = setup();
    await settle();

    const channel = root.querySelector<HTMLSelectElement>(
      '[data-testid="content-preview-channel"]',
    )!;
    channel.value = 'tg';
    channel.dispatchEvent(new Event('change'));
    await settle();

    const locale = root.querySelector<HTMLSelectElement>('[data-testid="content-preview-locale"]')!;
    locale.value = 'en';
    locale.dispatchEvent(new Event('change'));
    await settle();

    const day = root.querySelector<HTMLInputElement>('[data-testid="content-preview-day"]')!;
    day.value = '2026-12-01';
    day.dispatchEvent(new Event('change'));
    await settle();
    fixture.detectChanges();

    expect(api.calls.map((c) => [c.channel, c.locale])).toEqual([
      ['web', 'uz-Latn'],
      ['tg', 'uz-Latn'],
      ['tg', 'en'],
      ['tg', 'en'],
    ]);
    expect(api.calls.at(-1)?.asOf).toBe(new Date('2026-12-01T00:00:00').toISOString());
  });

  it('says the home screen is empty rather than drawing a blank phone', async () => {
    const { fixture, api, root } = setup();
    api.result = Promise.resolve({ locale: 'ru', carousel: [], groups: [] });
    (root.querySelector('[data-testid="content-preview-refresh"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-preview-empty"]')).toBeTruthy();
  });

  it('shows a refused preview as a sentence', async () => {
    const { fixture, api, root } = setup();
    api.result = Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));
    (root.querySelector('[data-testid="content-preview-refresh"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[role="alert"]')?.textContent?.trim()).not.toBe('');
  });

  it('says there is nothing to preview when no channel can show content', async () => {
    const { fixture, api, root } = setup(null, []);
    await settle();
    fixture.detectChanges();

    expect(api.calls).toEqual([]);
    expect(root.querySelector('[data-testid="content-preview-no-channel"]')).toBeTruthy();
  });
});
