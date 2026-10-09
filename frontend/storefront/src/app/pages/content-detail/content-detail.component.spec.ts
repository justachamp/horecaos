import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { EditorialService, type Loaded } from '../../services/editorial.service';
import { LangService } from '../../services/lang.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { EditorialDetail } from '../../types/editorial.types';
import { ContentDetailComponent } from './content-detail.component';

function doc(overrides: Partial<EditorialDetail> = {}): EditorialDetail {
  return {
    slug: 'summer-menu',
    title: 'Summer menu',
    summary: 'What is new this season',
    textLocale: 'uz-Latn',
    publishedAt: '2026-10-01T09:00:00Z',
    blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Body text' }] }],
    images: [],
    ...overrides,
  };
}

const ok = (data: EditorialDetail): Loaded<EditorialDetail> => ({ status: 'ok', data });
const UNAVAILABLE: Loaded<EditorialDetail> = { status: 'unavailable' };

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

async function open(
  kind: string,
  slug: string,
  answer: (kind: string, slug: string) => Promise<Loaded<EditorialDetail>>,
) {
  const editorial = { detail: vi.fn(answer), list: vi.fn(), features: signal({}) };
  const lang = { langId: signal('en') };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'news/:slug', component: ContentDetailComponent, data: { kind } },
        { path: 'careers/:slug', component: ContentDetailComponent, data: { kind } },
        { path: 'weird/:slug', component: ContentDetailComponent, data: { kind } },
        { path: '**', children: [] },
      ]),
      { provide: EditorialService, useValue: editorial },
      { provide: LangService, useValue: lang },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: { reduced: () => false } },
    ],
  });
  const harness = await RouterTestingHarness.create();
  const prefix = kind === 'careers' ? 'careers' : kind === 'news' ? 'news' : 'weird';
  await harness.navigateByUrl(`/${prefix}/${slug}`, ContentDetailComponent);
  const host = harness.routeNativeElement as HTMLElement;
  const settle = async () => {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  };
  await settle();
  return { harness, host, editorial, lang, settle, text: () => host.textContent ?? '' };
}

describe('ContentDetailComponent', () => {
  it('asks for the document named by the address', async () => {
    const { editorial } = await open('news', 'summer-menu', async () => ok(doc()));

    expect(editorial.detail).toHaveBeenCalledTimes(1);
    expect(editorial.detail).toHaveBeenCalledWith('news', 'summer-menu');
  });

  it('shows a spinner while the document is on the way', async () => {
    const { host } = await open('news', 'summer-menu', () => new Promise(() => {}));

    expect(host.querySelector('[data-testid="loading"]')).not.toBeNull();
    expect(host.querySelector('article')).toBeNull();
  });

  it('draws the head of the document: title, date, summary and cover', async () => {
    const cover = {
      kind: 'IMAGE' as const,
      alt: 'Cover',
      renditions: [{ widthPx: 800, url: '/m/c?variant=w800' }],
    };
    const { host } = await open('news', 'summer-menu', async () => ok(doc({ cover })));

    expect(host.querySelector('h1')?.textContent).toContain('Summer menu');
    expect(host.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-01T09:00:00Z');
    expect(host.textContent).toContain('What is new this season');
    expect(host.querySelector('article img')?.getAttribute('srcset')).toBe(
      '/m/c?variant=w800 800w',
    );
    expect(host.querySelector('article img')?.getAttribute('loading')).toBe('eager');
  });

  it('draws the body through the block renderer', async () => {
    const { host } = await open('news', 'summer-menu', async () =>
      ok(
        doc({
          blocks: [
            { type: 'HEADING', level: 2, inlines: [{ text: 'Chapter' }] },
            { type: 'PARAGRAPH', inlines: [{ text: 'Body text' }] },
          ],
        }),
      ),
    );

    expect(host.querySelector('app-content-blocks h2')?.textContent).toContain('Chapter');
    expect(host.querySelector('app-content-blocks p')?.textContent).toContain('Body text');
  });

  it('marks the language of the body separately from the title when it differs', async () => {
    const { host } = await open('news', 'summer-menu', async () =>
      ok(doc({ textLocale: 'uz-Latn', bodyLocale: 'ru' })),
    );

    expect(host.querySelector('article')?.getAttribute('lang')).toBe('uz-Latn');
    expect(host.querySelector('[lang="ru"]')).not.toBeNull();
  });

  it('writes the title and the summary as text', async () => {
    const { host } = await open('news', 'summer-menu', async () =>
      ok(doc({ title: '<img src=x onerror=alert(1)>', summary: '<b>sum</b>' })),
    );

    expect(host.querySelector('article img')).toBeNull();
    expect(host.querySelector('article b')).toBeNull();
    expect(host.querySelector('h1')?.textContent?.trim()).toBe('<img src=x onerror=alert(1)>');
  });

  it('a cover that does not load disappears and the document stays', async () => {
    const cover = {
      kind: 'IMAGE' as const,
      alt: 'Cover',
      renditions: [{ widthPx: 800, url: '/m/c?variant=w800' }],
    };
    const { host, settle } = await open('news', 'summer-menu', async () => ok(doc({ cover })));

    host.querySelector('article img')?.dispatchEvent(new Event('error'));
    await settle();

    expect(host.querySelector('article img')).toBeNull();
    expect(host.querySelector('h1')).not.toBeNull();
  });

  describe('when it cannot be shown', () => {
    it('says "not available" as a plain page with a way home, not as an error', async () => {
      const { host, text } = await open('news', 'gone', async () => UNAVAILABLE);

      expect(text()).toContain('content.unavailable');
      expect(host.querySelector('article')).toBeNull();
      expect(host.querySelector('[role="alert"]')).toBeNull();
      expect(host.querySelector('[class*="text-red"]')).toBeNull();
      expect(host.querySelector('a[href="/home"]')).not.toBeNull();
    });

    it('a route that names a kind it does not know is "not available" and asks nothing', async () => {
      const { text, editorial } = await open('podcasts', 'x', async () => ok(doc()));

      expect(text()).toContain('content.unavailable');
      expect(editorial.detail).not.toHaveBeenCalled();
    });
  });

  it('reads the new document when the address changes to another slug', async () => {
    const { harness, editorial, settle, host } = await open('news', 'first', async (_k, slug) =>
      ok(doc({ slug, title: `Title ${slug}` })),
    );
    expect(host.querySelector('h1')?.textContent).toContain('Title first');

    await harness.navigateByUrl('/news/second');
    await settle();

    expect(editorial.detail).toHaveBeenLastCalledWith('news', 'second');
    expect(host.querySelector('h1')?.textContent).toContain('Title second');
  });

  it('reads again in the new language when the language changes', async () => {
    const { editorial, lang, settle } = await open('news', 'summer-menu', async () => ok(doc()));

    lang.langId.set('ru');
    await settle();

    expect(editorial.detail).toHaveBeenCalledTimes(2);
  });

  it('does not keep a document from a slower read the visitor has already left', async () => {
    let resolveFirst!: (value: Loaded<EditorialDetail>) => void;
    const answers = [
      new Promise<Loaded<EditorialDetail>>((r) => (resolveFirst = r)),
      Promise.resolve(ok(doc({ slug: 'second', title: 'Second doc' }))),
    ];
    const { harness, settle, host } = await open('news', 'first', () => answers.shift()!);

    await harness.navigateByUrl('/news/second');
    await settle();
    resolveFirst(ok(doc({ slug: 'first', title: 'First doc' })));
    await settle();

    expect(host.querySelector('h1')?.textContent).toContain('Second doc');
  });

  describe('a gallery album', () => {
    const images = [1, 2, 3].map((n) => ({
      media: {
        kind: 'IMAGE' as const,
        alt: `Photo ${n}`,
        renditions: [{ widthPx: 400, url: `/m/${n}?variant=w400` }],
      },
      ...(n === 2 ? { caption: 'Second photo' } : {}),
    }));

    it('draws every photo from its renditions, with its caption', async () => {
      const { host } = await open('news', 'album', async () => ok(doc({ blocks: [], images })));

      expect(
        Array.from(host.querySelectorAll('[data-testid="album"] img')).map((i) =>
          i.getAttribute('alt'),
        ),
      ).toEqual(['Photo 1', 'Photo 2', 'Photo 3']);
      expect(host.querySelector('[data-testid="album"] figcaption')?.textContent).toContain(
        'Second photo',
      );
    });

    it('a photo that does not load disappears, the rest stay', async () => {
      const { host, settle } = await open('news', 'album', async () =>
        ok(doc({ blocks: [], images })),
      );

      host.querySelectorAll('[data-testid="album"] img')[0].dispatchEvent(new Event('error'));
      await settle();

      expect(host.querySelectorAll('[data-testid="album"] img')).toHaveLength(2);
    });

    it('a document with no photos has no album', async () => {
      const { host } = await open('news', 'album', async () => ok(doc()));

      expect(host.querySelector('[data-testid="album"]')).toBeNull();
    });
  });

  describe('a vacancy', () => {
    const vacancyDoc = (vacancy: EditorialDetail['vacancy']) => ok(doc({ vacancy }));
    const contact = (host: HTMLElement) => host.querySelector('[data-testid="vacancy-contact"]');

    it('shows its employment type and its branch', async () => {
      const { host } = await open('careers', 'cook', async () =>
        vacancyDoc({
          employmentType: 'SHIFT',
          branch: {
            locationId: 'l1',
            name: 'Chilanzar',
            addressLine: 'Bunyodkor 1',
            city: 'Tashkent',
          },
        }),
      );

      const section = host.querySelector('[data-testid="vacancy"]');
      expect(section?.textContent).toContain('content.employment.SHIFT');
      expect(section?.textContent).toContain('Chilanzar');
      expect(section?.textContent).toContain('Bunyodkor 1');
      expect(section?.textContent).toContain('Tashkent');
    });

    it('a phone contact is a tel: link', async () => {
      const { host } = await open('careers', 'cook', async () =>
        vacancyDoc({
          employmentType: 'SHIFT',
          apply: { type: 'PHONE', value: '+998 90 123 45 67' },
        }),
      );

      expect(contact(host)?.querySelector('a')?.getAttribute('href')).toBe('tel:+998901234567');
      expect(contact(host)?.textContent).toContain('+998 90 123 45 67');
    });

    it('an e-mail contact is a mailto: link', async () => {
      const { host } = await open('careers', 'cook', async () =>
        vacancyDoc({ apply: { type: 'EMAIL', value: 'jobs@example.uz' } }),
      );

      expect(contact(host)?.querySelector('a')?.getAttribute('href')).toBe(
        'mailto:jobs@example.uz',
      );
    });

    it('a Telegram contact opens t.me in a new tab', async () => {
      const { host } = await open('careers', 'cook', async () =>
        vacancyDoc({ apply: { type: 'TELEGRAM', value: '@horeca_jobs' } }),
      );

      const link = contact(host)?.querySelector('a');
      expect(link?.getAttribute('href')).toBe('https://t.me/horeca_jobs');
      expect(link?.getAttribute('target')).toBe('_blank');
      expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('an https address opens in a new tab', async () => {
      const { host } = await open('careers', 'cook', async () =>
        vacancyDoc({ apply: { type: 'URL', value: 'https://example.uz/jobs/7' } }),
      );

      const link = contact(host)?.querySelector('a');
      expect(link?.getAttribute('href')).toBe('https://example.uz/jobs/7');
      expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it.each([
      { type: 'URL' as const, value: 'javascript:alert(1)' },
      { type: 'URL' as const, value: 'http://example.uz/' },
      { type: 'PHONE' as const, value: 'ask at the till' },
      { type: 'EMAIL' as const, value: 'not an address' },
    ])(
      'a contact that fails its rule (%j) is shown as plain text, never as a link',
      async (apply) => {
        const { host } = await open('careers', 'cook', async () => vacancyDoc({ apply }));

        expect(contact(host)?.querySelector('a')).toBeNull();
        expect(contact(host)?.textContent).toContain(apply.value);
      },
    );

    it('has no application form: no form, no field, no submit', async () => {
      const { host } = await open('careers', 'cook', async () =>
        vacancyDoc({
          employmentType: 'FULL_TIME',
          apply: { type: 'PHONE', value: '+998901234567' },
          branch: { locationId: 'l1', name: 'Chilanzar' },
        }),
      );

      expect(host.querySelector('form')).toBeNull();
      expect(host.querySelector('input')).toBeNull();
      expect(host.querySelector('textarea')).toBeNull();
      expect(host.querySelector('select')).toBeNull();
      expect(host.querySelector('button[type="submit"]')).toBeNull();
    });

    it('a document with no vacancy block shows none', async () => {
      const { host } = await open('careers', 'cook', async () => ok(doc()));

      expect(host.querySelector('[data-testid="vacancy"]')).toBeNull();
    });
  });

  it('is reachable from the router with a real URL', async () => {
    const { harness } = await open('news', 'summer-menu', async () => ok(doc()));

    expect(TestBed.inject(Router).url).toBe('/news/summer-menu');
    expect(harness.routeNativeElement).not.toBeNull();
  });
});
