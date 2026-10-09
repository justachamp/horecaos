import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EditorialService, type EditorialResult } from '../../services/editorial.service';
import { LangService } from '../../services/lang.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { EditorialDetail } from '../../types/editorial.types';
import { imageMedia } from '../../testing/content-fixtures';
import { EditorialDetailComponent } from './editorial-detail.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string, params?: Record<string, string | number>) =>
    params ? `${key}|${Object.values(params).join('|')}` : key;
  current = () => ({});
}
class FakeLangService {
  langId = () => 'en';
}

function entry(overrides: Partial<EditorialDetail> = {}): EditorialDetail {
  return {
    slug: 'plov-week',
    kind: 'NEWS',
    title: 'Plov week',
    summary: 'Seven days of plov',
    textLocale: 'en',
    publishedAt: '2026-10-01T08:30:00Z',
    blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Plov is ready.' }] }],
    images: [],
    ...overrides,
  };
}

const ok = (value: EditorialDetail): EditorialResult<EditorialDetail> => ({ status: 'ok', value });
const UNAVAILABLE: EditorialResult<EditorialDetail> = { status: 'unavailable' };

async function render(
  kind: string | undefined,
  slug: string | undefined,
  ...responses: EditorialResult<EditorialDetail>[]
) {
  const detail = vi.fn();
  responses.forEach((response) => detail.mockResolvedValueOnce(response));
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: EditorialService, useValue: { detail } },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useClass: FakeLangService },
      { provide: ReducedMotionService, useValue: { reduced: signal(false) } },
    ],
  });
  const fixture = TestBed.createComponent(EditorialDetailComponent);
  fixture.componentRef.setInput('kind', kind);
  fixture.componentRef.setInput('slug', slug);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  return { fixture, host, detail, q };
}

describe('EditorialDetailComponent', () => {
  it('reads the entry named by the route and shows it', async () => {
    const { host, q, detail } = await render('news', 'plov-week', ok(entry()));

    expect(detail).toHaveBeenCalledWith('news', 'plov-week');
    expect(q('editorial-title')?.tagName).toBe('H1');
    expect(q('editorial-title')?.textContent?.trim()).toBe('Plov week');
    expect(host.textContent).toContain('Seven days of plov');
    expect(host.textContent).toContain('Plov is ready.');
  });

  it('leads back to the listing it came from', async () => {
    const { q } = await render('recipes', 'plov', ok(entry({ kind: 'RECIPE' })));

    expect(q('editorial-back')?.getAttribute('href')).toBe('/recipes');
    expect(q('editorial-back')?.textContent).toContain('common.back');
  });

  it('shows the date it was published, as a time', async () => {
    const { q } = await render('news', 'plov-week', ok(entry()));

    expect(q('editorial-date')?.tagName).toBe('TIME');
    expect(q('editorial-date')?.getAttribute('datetime')).toBe('2026-10-01T08:30:00Z');
    expect(q('editorial-date')?.textContent).toContain('2026');
  });

  it('draws the cover first, eagerly, and drops it when it cannot be loaded', async () => {
    const { fixture, q } = await render('news', 'plov-week', ok(entry({ cover: imageMedia() })));
    const img = q('editorial-cover')?.querySelector('img');

    expect(img?.getAttribute('loading')).toBe('eager');
    expect(img?.getAttribute('srcset')).toContain('800w');

    img?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(q('editorial-cover')).toBeNull();
    expect(q('editorial-title')).not.toBeNull();
  });

  it('draws the body through the one block renderer, in the body’s own language', async () => {
    const { q } = await render(
      'news',
      'plov-week',
      ok(
        entry({
          bodyLocale: 'ru',
          blocks: [
            { type: 'HEADING', level: 2, inlines: [{ text: 'Подзаголовок' }] },
            { type: 'PARAGRAPH', inlines: [{ text: 'Текст', href: 'https://example.uz/' }] },
          ],
        }),
      ),
    );

    expect(q('content-blocks')?.getAttribute('lang')).toBe('ru');
    expect(q('content-blocks')?.querySelector('h2')?.textContent?.trim()).toBe('Подзаголовок');
    expect(q('content-blocks')?.querySelector('a')?.getAttribute('href')).toBe(
      'https://example.uz/',
    );
  });

  it('puts the title’s language on the title and summary', async () => {
    const { q } = await render('news', 'plov-week', ok(entry({ textLocale: 'uz-Latn' })));

    expect(q('editorial-heading')?.getAttribute('lang')).toBe('uz-Latn');
  });

  it('draws text as text, never as markup', async () => {
    const { host } = await render(
      'news',
      'plov-week',
      ok(entry({ title: '<img src=x onerror=alert(1)>', summary: '<script>alert(1)</script>' })),
    );

    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('script')).toBeNull();
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  describe('a gallery album', () => {
    it('shows its pictures with their captions', async () => {
      const { q } = await render(
        'gallery',
        'opening',
        ok(
          entry({
            kind: 'GALLERY_ALBUM',
            blocks: [],
            images: [
              { media: imageMedia({ alt: 'The hall' }), caption: 'The hall at night' },
              { media: imageMedia({ alt: 'The bar' }) },
            ],
          }),
        ),
      );
      const figures = q('editorial-images')?.querySelectorAll('figure') ?? [];

      expect(figures).toHaveLength(2);
      expect(figures[0].querySelector('img')?.getAttribute('alt')).toBe('The hall');
      expect(figures[0].querySelector('figcaption')?.textContent?.trim()).toBe('The hall at night');
      expect(figures[1].querySelector('figcaption')).toBeNull();
    });

    it('loads the pictures lazily, and removes one that cannot be loaded', async () => {
      const { fixture, q } = await render(
        'gallery',
        'opening',
        ok(
          entry({
            kind: 'GALLERY_ALBUM',
            blocks: [],
            images: [{ media: imageMedia() }, { media: imageMedia() }],
          }),
        ),
      );
      const imgs = q('editorial-images')?.querySelectorAll('img') ?? [];
      expect([...imgs].map((img) => img.getAttribute('loading'))).toEqual(['lazy', 'lazy']);

      imgs[0].dispatchEvent(new Event('error'));
      fixture.detectChanges();

      expect(q('editorial-images')?.querySelectorAll('figure')).toHaveLength(1);
    });

    it('shows no gallery grid for an entry without pictures', async () => {
      const { q } = await render('news', 'plov-week', ok(entry()));

      expect(q('editorial-images')).toBeNull();
    });
  });

  describe('a vacancy', () => {
    const vacancy = (overrides: Partial<NonNullable<EditorialDetail['vacancy']>> = {}) =>
      entry({
        kind: 'VACANCY',
        vacancy: {
          employmentType: 'SHIFT',
          apply: { type: 'PHONE', value: '+998 90 123-45-67' },
          branch: {
            locationId: '10000000-0000-0000-0000-000000000003',
            name: 'Chilonzor',
            addressLine: 'Bunyodkor 1',
            city: 'Tashkent',
          },
          ...overrides,
        },
      });

    it('shows the employment type and the branch', async () => {
      const { q } = await render('careers', 'cook', ok(vacancy()));

      expect(q('editorial-vacancy')?.textContent).toContain('editorial.employment.SHIFT');
      expect(q('editorial-branch')?.textContent).toContain('Chilonzor');
      expect(q('editorial-branch')?.textContent).toContain('Bunyodkor 1');
      expect(q('editorial-branch')?.textContent).toContain('Tashkent');
    });

    it('offers its one contact as a call link', async () => {
      const { q } = await render('careers', 'cook', ok(vacancy()));
      const anchor = q('editorial-contact')?.querySelector('a');

      expect(anchor?.getAttribute('href')).toBe('tel:+998901234567');
      expect(anchor?.textContent?.trim()).toBe('+998 90 123-45-67');
      expect(anchor?.hasAttribute('target')).toBe(false);
    });

    it('offers an e-mail contact as a mail link', async () => {
      const { q } = await render(
        'careers',
        'cook',
        ok(vacancy({ apply: { type: 'EMAIL', value: 'hr@example.uz' } })),
      );

      expect(q('editorial-contact')?.querySelector('a')?.getAttribute('href')).toBe(
        'mailto:hr@example.uz',
      );
    });

    it('offers a web or Telegram contact as a link to a new tab that cannot reach back', async () => {
      const { q } = await render(
        'careers',
        'cook',
        ok(vacancy({ apply: { type: 'TELEGRAM', value: 'https://t.me/example_hr' } })),
      );
      const anchor = q('editorial-contact')?.querySelector('a');

      expect(anchor?.getAttribute('href')).toBe('https://t.me/example_hr');
      expect(anchor?.getAttribute('target')).toBe('_blank');
      expect(anchor?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('shows a contact it cannot trust as plain text, with nothing to click', async () => {
      const { q } = await render(
        'careers',
        'cook',
        ok(vacancy({ apply: { type: 'URL', value: 'javascript:alert(1)' } })),
      );

      expect(q('editorial-contact')?.querySelector('a')).toBeNull();
      expect(q('editorial-contact')?.textContent).toContain('javascript:alert(1)');
    });

    it('has no application form: the platform keeps no applicant data', async () => {
      const { host } = await render('careers', 'cook', ok(vacancy()));

      expect(host.querySelector('form, input, textarea, select')).toBeNull();
      expect(host.querySelector('button[type="submit"]')).toBeNull();
    });

    it('shows no contact section for a vacancy that publishes none', async () => {
      const { q } = await render('careers', 'cook', ok(vacancy({ apply: undefined })));

      expect(q('editorial-contact')).toBeNull();
      expect(q('editorial-vacancy')).not.toBeNull();
    });

    it('shows no vacancy section on any other kind of entry', async () => {
      const { q } = await render('news', 'plov-week', ok(entry()));

      expect(q('editorial-vacancy')).toBeNull();
    });
  });

  describe('an entry that is not there', () => {
    it('is a plain "not available" page, never an error screen, for a 404 or a kind that is off', async () => {
      const { host, q } = await render('news', 'plov-week', UNAVAILABLE);

      expect(q('editorial-unavailable')).not.toBeNull();
      expect(host.textContent).toContain('editorial.unavailableBody');
      expect(host.querySelector('[role="alert"]')).toBeNull();
      expect(q('editorial-title')).toBeNull();
    });

    it.each([
      ['a route kind that is not one of the four', 'blog', 'plov-week'],
      ['no route kind', undefined, 'plov-week'],
      ['no slug', 'news', undefined],
      ['an empty slug', 'news', ''],
    ])('is the same page, with no request, for %s', async (_label, kind, slug) => {
      const { q, detail } = await render(kind, slug);

      expect(q('editorial-unavailable')).not.toBeNull();
      expect(detail).not.toHaveBeenCalled();
    });
  });

  it('shows "loading" until the entry arrives', async () => {
    const detail = vi.fn(() => new Promise<EditorialResult<EditorialDetail>>(() => undefined));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: EditorialService, useValue: { detail } },
        { provide: TranslateService, useClass: FakeTranslateService },
        { provide: LangService, useClass: FakeLangService },
        { provide: ReducedMotionService, useValue: { reduced: signal(false) } },
      ],
    });
    const fixture = TestBed.createComponent(EditorialDetailComponent);
    fixture.componentRef.setInput('kind', 'news');
    fixture.componentRef.setInput('slug', 'plov-week');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('common.loading');
  });

  it('reads the new entry when the route changes under it', async () => {
    const { fixture, host, detail } = await render(
      'news',
      'first',
      ok(entry({ title: 'First entry' })),
      ok(entry({ slug: 'second', title: 'Second entry' })),
    );

    fixture.componentRef.setInput('slug', 'second');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(detail).toHaveBeenLastCalledWith('news', 'second');
    expect(host.textContent).toContain('Second entry');
    expect(host.textContent).not.toContain('First entry');
  });

  it('ignores an answer that arrives after the route has moved on', async () => {
    let releaseFirst!: (value: EditorialResult<EditorialDetail>) => void;
    const detail = vi
      .fn()
      .mockImplementationOnce(() => new Promise((resolve) => (releaseFirst = resolve)))
      .mockResolvedValueOnce(ok(entry({ slug: 'second', title: 'Second entry' })));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: EditorialService, useValue: { detail } },
        { provide: TranslateService, useClass: FakeTranslateService },
        { provide: LangService, useClass: FakeLangService },
        { provide: ReducedMotionService, useValue: { reduced: signal(false) } },
      ],
    });
    const fixture = TestBed.createComponent(EditorialDetailComponent);
    fixture.componentRef.setInput('kind', 'news');
    fixture.componentRef.setInput('slug', 'first');
    fixture.detectChanges();

    fixture.componentRef.setInput('slug', 'second');
    fixture.detectChanges();
    await fixture.whenStable();
    releaseFirst(ok(entry({ title: 'First entry' })));
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Second entry');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('First entry');
  });
});
