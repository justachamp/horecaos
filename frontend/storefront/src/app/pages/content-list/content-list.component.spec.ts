import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { EditorialService, type Loaded } from '../../services/editorial.service';
import { LangService } from '../../services/lang.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { EditorialListItem, EditorialPage } from '../../types/editorial.types';
import { ContentListComponent } from './content-list.component';

function item(slug: string, overrides: Partial<EditorialListItem> = {}): EditorialListItem {
  return {
    slug,
    title: `Title ${slug}`,
    summary: `Summary ${slug}`,
    textLocale: 'uz-Latn',
    publishedAt: '2026-10-01T09:00:00Z',
    ...overrides,
  };
}

function ok(items: EditorialListItem[], nextCursor?: string): Loaded<EditorialPage> {
  return { status: 'ok', data: { items, ...(nextCursor ? { nextCursor } : {}) } };
}

const UNAVAILABLE: Loaded<EditorialPage> = { status: 'unavailable' };

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}|${Object.values(params).join('|')}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

async function open(kind: string, answer: () => Promise<Loaded<EditorialPage>>) {
  const editorial = { list: vi.fn(answer), detail: vi.fn(), features: signal({}) };
  const lang = { langId: signal('en') };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'news', component: ContentListComponent, data: { kind } },
        { path: 'careers', component: ContentListComponent, data: { kind } },
        { path: 'weird', component: ContentListComponent, data: { kind } },
        { path: '**', children: [] },
      ]),
      { provide: EditorialService, useValue: editorial },
      { provide: LangService, useValue: lang },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: { reduced: () => false } },
    ],
  });
  const harness = await RouterTestingHarness.create();
  const path = kind === 'careers' ? '/careers' : kind === 'news' ? '/news' : '/weird';
  await harness.navigateByUrl(path, ContentListComponent);
  const host = harness.routeNativeElement as HTMLElement;
  const settle = async () => {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  };
  await settle();
  return {
    harness,
    host,
    editorial,
    lang,
    settle,
    cards: () => Array.from(host.querySelectorAll('li a')),
    text: () => host.textContent ?? '',
    loadMore: () => host.querySelector<HTMLButtonElement>('[data-testid="load-more"]'),
  };
}

describe('ContentListComponent', () => {
  it('asks for the first page of its kind, with no cursor', async () => {
    const { editorial } = await open('news', async () => ok([item('a')]));

    expect(editorial.list).toHaveBeenCalledTimes(1);
    expect(editorial.list).toHaveBeenCalledWith('news');
  });

  it('shows a spinner while the first page is on the way', async () => {
    const { host } = await open('news', () => new Promise(() => {}));

    expect(host.querySelector('[data-testid="loading"]')).not.toBeNull();
    expect(host.querySelector('li')).toBeNull();
  });

  it('is titled by its kind', async () => {
    const { host } = await open('news', async () => ok([item('a')]));

    expect(host.querySelector('h1')?.textContent).toContain('content.editorial.news');
  });

  it('draws a card per item with its title, summary, date and a link to the document', async () => {
    const { cards, text } = await open('news', async () => ok([item('a'), item('b')]));

    expect(cards().map((a) => a.getAttribute('href'))).toEqual(['/news/a', '/news/b']);
    expect(text()).toContain('Title a');
    expect(text()).toContain('Summary b');
  });

  it('writes the date as a <time> with its machine value', async () => {
    const { host } = await open('news', async () => ok([item('a')]));

    const time = host.querySelector('time');
    expect(time?.getAttribute('datetime')).toBe('2026-10-01T09:00:00Z');
    expect(time?.textContent?.trim()).not.toBe('');
  });

  it('writes the title as text: markup in it is shown as characters', async () => {
    const { host } = await open('news', async () => ok([item('a', { title: '<b>Bold</b>' })]));

    expect(host.querySelector('li h2 b')).toBeNull();
    expect(host.querySelector('li h2')?.textContent?.trim()).toBe('<b>Bold</b>');
  });

  it('draws a cover from its renditions, and loses only the cover if it fails to load', async () => {
    const cover = {
      kind: 'IMAGE' as const,
      alt: 'Cover',
      renditions: [{ widthPx: 400, url: '/m/c?variant=w400' }],
    };
    const { host, settle } = await open('news', async () => ok([item('a', { cover })]));
    expect(host.querySelector('li img')?.getAttribute('srcset')).toBe('/m/c?variant=w400 400w');

    host.querySelector('li img')?.dispatchEvent(new Event('error'));
    await settle();

    expect(host.querySelector('li img')).toBeNull();
    expect(host.textContent).toContain('Title a');
  });

  it('a vacancy card shows its employment type', async () => {
    const { text } = await open('careers', async () =>
      ok([item('cook', { employmentType: 'SHIFT' })]),
    );

    expect(text()).toContain('content.employment.SHIFT');
  });

  describe('paging', () => {
    it('has no "show more" when there is no next page', async () => {
      const { loadMore } = await open('news', async () => ok([item('a')]));

      expect(loadMore()).toBeNull();
    });

    it('"show more" asks for the next page by its cursor and adds it to the list', async () => {
      const { editorial, loadMore, cards, settle } = await open('news', async () =>
        ok([item('a')], 'cursor-2'),
      );
      editorial.list.mockResolvedValueOnce(ok([item('b'), item('c')]));

      loadMore()?.click();
      await settle();

      expect(editorial.list).toHaveBeenLastCalledWith('news', { cursor: 'cursor-2' });
      expect(cards().map((a) => a.getAttribute('href'))).toEqual(['/news/a', '/news/b', '/news/c']);
      expect(loadMore()).toBeNull();
    });

    it('keeps offering "show more" while the server keeps handing out cursors', async () => {
      const { editorial, loadMore, settle } = await open('news', async () => ok([item('a')], 'c2'));
      editorial.list.mockResolvedValueOnce(ok([item('b')], 'c3'));

      loadMore()?.click();
      await settle();

      expect(loadMore()).not.toBeNull();
    });

    it('does not ask twice for the same page when it is tapped twice', async () => {
      const { editorial, loadMore, settle } = await open('news', async () => ok([item('a')], 'c2'));
      let resolve!: (page: Loaded<EditorialPage>) => void;
      editorial.list.mockReturnValueOnce(new Promise((r) => (resolve = r)));

      loadMore()?.click();
      loadMore()?.click();
      resolve(ok([item('b')]));
      await settle();

      expect(editorial.list).toHaveBeenCalledTimes(2);
    });

    it('never shows the same document twice', async () => {
      const { editorial, loadMore, cards, settle } = await open('news', async () =>
        ok([item('a')], 'c2'),
      );
      editorial.list.mockResolvedValueOnce(ok([item('a'), item('b')]));

      loadMore()?.click();
      await settle();

      expect(cards().map((a) => a.getAttribute('href'))).toEqual(['/news/a', '/news/b']);
    });

    it('a page that cannot be loaded leaves the list as it was and keeps the button for another try', async () => {
      const { editorial, loadMore, cards, settle } = await open('news', async () =>
        ok([item('a')], 'c2'),
      );
      editorial.list.mockResolvedValueOnce(UNAVAILABLE);

      loadMore()?.click();
      await settle();

      expect(cards()).toHaveLength(1);
      expect(loadMore()).not.toBeNull();
    });
  });

  describe('when there is nothing to show', () => {
    it('says so plainly for an empty list', async () => {
      const { text } = await open('news', async () => ok([]));

      expect(text()).toContain('content.empty');
    });

    it('says "not available" when the kind is off, gone or failing, as a plain page and not as an error', async () => {
      const { host, text, cards } = await open('news', async () => UNAVAILABLE);

      expect(text()).toContain('content.unavailable');
      expect(cards().map((a) => a.getAttribute('href'))).toEqual([]);
      expect(host.querySelector('[role="alert"]')).toBeNull();
      expect(host.querySelector('[class*="text-red"]')).toBeNull();
      expect(host.querySelector('a[href="/home"]')).not.toBeNull();
    });

    it('a route that names a kind it does not know is "not available" and asks nothing', async () => {
      const { text, editorial } = await open('podcasts', async () => ok([item('a')]));

      expect(text()).toContain('content.unavailable');
      expect(editorial.list).not.toHaveBeenCalled();
    });
  });

  it('reads again in the new language when the language changes', async () => {
    const { editorial, lang, settle, cards } = await open('news', async () => ok([item('a')]));
    editorial.list.mockResolvedValueOnce(ok([item('ru-doc')]));

    lang.langId.set('ru');
    await settle();

    expect(editorial.list).toHaveBeenCalledTimes(2);
    expect(cards().map((a) => a.getAttribute('href'))).toEqual(['/news/ru-doc']);
  });
});
