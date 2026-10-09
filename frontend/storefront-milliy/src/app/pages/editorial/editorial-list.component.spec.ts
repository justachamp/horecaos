import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EditorialService, type EditorialResult } from '../../services/editorial.service';
import { LangService } from '../../services/lang.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { EditorialPage, EditorialSummary } from '../../types/editorial.types';
import { imageMedia } from '../../testing/content-fixtures';
import { EditorialListComponent } from './editorial-list.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string) => key;
  current = () => ({});
}
class FakeLangService {
  langId = () => 'en';
}

function entry(overrides: Partial<EditorialSummary> = {}): EditorialSummary {
  return {
    slug: 'plov-week',
    title: 'Plov week',
    summary: 'Seven days of plov',
    textLocale: 'en',
    publishedAt: '2026-10-01T08:30:00Z',
    ...overrides,
  };
}

const ok = (page: EditorialPage): EditorialResult<EditorialPage> => ({ status: 'ok', value: page });
const UNAVAILABLE: EditorialResult<EditorialPage> = { status: 'unavailable' };

async function render(kind: string | undefined, ...responses: EditorialResult<EditorialPage>[]) {
  const list = vi.fn();
  responses.forEach((response) => list.mockResolvedValueOnce(response));
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: EditorialService, useValue: { list } },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useClass: FakeLangService },
      { provide: ReducedMotionService, useValue: { reduced: signal(false) } },
    ],
  });
  const fixture = TestBed.createComponent(EditorialListComponent);
  fixture.componentRef.setInput('kind', kind);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const cards = () => [
    ...host.querySelectorAll<HTMLAnchorElement>('[data-testid="editorial-card"]'),
  ];
  return { fixture, host, list, cards };
}

const settle = async (fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) => {
  await fixture.whenStable();
  fixture.detectChanges();
};

describe('EditorialListComponent', () => {
  it('shows the listing with the section title and a way back', async () => {
    const { host, list } = await render('news', ok({ items: [entry()] }));

    expect(list).toHaveBeenCalledWith('news', {});
    expect(host.querySelector('[data-testid="editorial-title"]')?.textContent?.trim()).toBe(
      'editorial.news',
    );
    expect(host.querySelector('[data-testid="editorial-back"]')?.getAttribute('href')).toBe(
      '/home',
    );
  });

  it.each([
    ['news', 'editorial.news'],
    ['gallery', 'editorial.gallery'],
    ['recipes', 'editorial.recipes'],
    ['careers', 'editorial.careers'],
  ])('titles the %s listing', async (kind, title) => {
    const { host } = await render(kind, ok({ items: [entry()] }));

    expect(host.querySelector('h1')?.textContent?.trim()).toBe(title);
  });

  it('draws a card for each entry, as a link to its page, with title, summary and date', async () => {
    const { host, cards } = await render(
      'recipes',
      ok({
        items: [
          entry({ slug: 'plov', title: 'Plov', summary: 'Rice and carrots' }),
          entry({ slug: 'samsa', title: 'Samsa', summary: undefined }),
        ],
      }),
    );

    expect(cards().map((card) => card.getAttribute('href'))).toEqual([
      '/recipes/plov',
      '/recipes/samsa',
    ]);
    expect(cards()[0].textContent).toContain('Plov');
    expect(cards()[0].textContent).toContain('Rice and carrots');
    expect(cards()[1].textContent).toContain('Samsa');
    const time = host.querySelector('time');
    expect(time?.getAttribute('datetime')).toBe('2026-10-01T08:30:00Z');
    expect(time?.textContent).toContain('2026');
  });

  it('puts a slug into the link as one segment', async () => {
    const { cards } = await render('news', ok({ items: [entry({ slug: 'a b/c' })] }));

    expect(cards()[0].getAttribute('href')).toBe('/news/a%20b%2Fc');
  });

  it('draws the cover from its renditions, lazily, and drops it when it cannot be loaded', async () => {
    const { fixture, cards } = await render(
      'news',
      ok({ items: [entry({ cover: imageMedia() })] }),
    );
    const img = cards()[0].querySelector('img');

    expect(img?.getAttribute('srcset')).toContain('800w');
    expect(img?.getAttribute('loading')).toBe('lazy');

    img?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(cards()[0].querySelector('img')).toBeNull();
    expect(cards()[0].textContent).toContain('Plov week');
  });

  it('draws text as text, never as markup', async () => {
    const { host, cards } = await render(
      'news',
      ok({ items: [entry({ title: '<img src=x onerror=alert(1)>', summary: '<b>hi</b>' })] }),
    );

    expect(host.querySelector('[data-testid="editorial-list"] img')).toBeNull();
    expect(host.querySelector('[data-testid="editorial-list"] b')).toBeNull();
    expect(cards()[0].textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('says which language an entry is in when it differs from the page’s own', async () => {
    const { cards } = await render('news', ok({ items: [entry({ textLocale: 'ru' })] }));

    expect(cards()[0].querySelector('[lang="ru"]')).not.toBeNull();
  });

  it('shows the employment type on a vacancy', async () => {
    const { cards } = await render(
      'careers',
      ok({ items: [entry({ slug: 'cook', employmentType: 'SHIFT' })] }),
    );

    expect(cards()[0].textContent).toContain('editorial.employment.SHIFT');
  });

  it('says there is nothing here yet, when there is nothing', async () => {
    const { host, cards } = await render('news', ok({ items: [] }));

    expect(cards()).toEqual([]);
    expect(host.querySelector('[data-testid="editorial-empty"]')?.textContent).toContain(
      'editorial.empty',
    );
    expect(host.querySelector('[data-testid="editorial-unavailable"]')).toBeNull();
  });

  it('shows "loading" until the first page arrives', async () => {
    const list = vi.fn(() => new Promise<EditorialResult<EditorialPage>>(() => undefined));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: EditorialService, useValue: { list } },
        { provide: TranslateService, useClass: FakeTranslateService },
        { provide: LangService, useClass: FakeLangService },
        { provide: ReducedMotionService, useValue: { reduced: signal(false) } },
      ],
    });
    const fixture = TestBed.createComponent(EditorialListComponent);
    fixture.componentRef.setInput('kind', 'news');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('common.loading');
  });

  describe('more pages', () => {
    it('offers "show more" only while the server has a next page', async () => {
      const { host } = await render('news', ok({ items: [entry()], nextCursor: 'c1' }));
      expect(host.querySelector('[data-testid="editorial-more"]')?.textContent).toContain(
        'editorial.loadMore',
      );

      TestBed.resetTestingModule();
      const last = await render('news', ok({ items: [entry()] }));
      expect(last.host.querySelector('[data-testid="editorial-more"]')).toBeNull();
    });

    it('adds the next page after the first, by its cursor', async () => {
      const { fixture, host, list, cards } = await render(
        'news',
        ok({ items: [entry({ slug: 'one', title: 'One' })], nextCursor: 'c1' }),
        ok({ items: [entry({ slug: 'two', title: 'Two' })] }),
      );

      host.querySelector<HTMLButtonElement>('[data-testid="editorial-more"]')?.click();
      await settle(fixture);

      expect(list).toHaveBeenLastCalledWith('news', { cursor: 'c1' });
      expect(cards().map((card) => card.getAttribute('href'))).toEqual(['/news/one', '/news/two']);
      expect(host.querySelector('[data-testid="editorial-more"]')).toBeNull();
    });

    it('keeps what is shown, and lets the visitor try again, when the next page cannot be read', async () => {
      const { fixture, host, cards } = await render(
        'news',
        ok({ items: [entry({ slug: 'one', title: 'One' })], nextCursor: 'c1' }),
        UNAVAILABLE,
      );

      host.querySelector<HTMLButtonElement>('[data-testid="editorial-more"]')?.click();
      await settle(fixture);

      expect(cards()).toHaveLength(1);
      expect(host.querySelector('[data-testid="editorial-unavailable"]')).toBeNull();
      expect(
        host.querySelector<HTMLButtonElement>('[data-testid="editorial-more"]')?.disabled,
      ).toBe(false);
    });
  });

  describe('a section that is not there', () => {
    it('is a plain "not available" page when the server answers 404 or the kind is off', async () => {
      const { host, cards } = await render('news', UNAVAILABLE);

      expect(host.querySelector('[data-testid="editorial-unavailable"]')).not.toBeNull();
      expect(host.textContent).toContain('editorial.unavailableBody');
      expect(cards()).toEqual([]);
      expect(host.querySelector('[role="alert"]')).toBeNull();
      expect(host.querySelector('[data-testid="editorial-title"]')).toBeNull();
    });

    it.each([undefined, 'blog', '', 'NEWS', '../home'])(
      'is the same page, with no request, for a route kind of %j',
      async (kind) => {
        const { host, list } = await render(kind);

        expect(host.querySelector('[data-testid="editorial-unavailable"]')).not.toBeNull();
        expect(list).not.toHaveBeenCalled();
      },
    );
  });

  it('reads the listing again when the kind changes under it', async () => {
    const { fixture, host, list } = await render(
      'news',
      ok({ items: [entry({ title: 'A news item' })] }),
      ok({ items: [entry({ slug: 'g', title: 'A gallery album' })] }),
    );

    fixture.componentRef.setInput('kind', 'gallery');
    fixture.detectChanges();
    await settle(fixture);

    expect(list).toHaveBeenLastCalledWith('gallery', {});
    expect(host.textContent).toContain('A gallery album');
    expect(host.textContent).not.toContain('A news item');
  });
});
