import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { BrandProfileApi, BrandView } from '../brand-profile/brand-profile-api';
import { TermsApi, TermsVersionSummaryView, TermsVersionView } from './terms-api';
import { TermsPage } from './terms-page';

const BRAND: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'RAYHON',
  slug: 'rayhon',
  displayName: 'Rayhon',
  status: 'ACTIVE',
  contactPhone: null,
  telegramHandle: null,
  logoAssetId: null,
  bannerAssetId: null,
  locales: [],
  version: 0,
};

const BRAND_2: BrandView = {
  id: 'brand-2',
  tenantId: 'tenant-1',
  code: 'OSHXONA',
  slug: 'oshxona',
  displayName: 'Oshxona',
  status: 'ACTIVE',
  contactPhone: null,
  telegramHandle: null,
  logoAssetId: null,
  bannerAssetId: null,
  locales: [],
  version: 0,
};

/** Offers uz-Latn (the default) and en; ru is a platform language this brand does not offer. */
const UZ_EN_BRAND: BrandView = {
  ...BRAND,
  locales: [
    { locale: 'en', description: null, isDefault: false },
    { locale: 'uz-Latn', description: null, isDefault: true },
  ],
};

/** Offers ru alone. */
const RU_ONLY_BRAND: BrandView = {
  ...BRAND_2,
  locales: [{ locale: 'ru', description: null, isDefault: true }],
};

const NEVER_PUBLISHED: TermsVersionView = {
  published: false,
  id: null,
  version: null,
  contentsByLocale: {},
  publishedBy: null,
  publishedAt: null,
};

function published(overrides: Partial<TermsVersionView> = {}): TermsVersionView {
  return {
    published: true,
    id: 'terms-1',
    version: 2,
    contentsByLocale: { ru: 'Правила', en: 'Terms' },
    publishedBy: 'owner-1',
    publishedAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function summary(overrides: Partial<TermsVersionSummaryView> = {}): TermsVersionSummaryView {
  return {
    id: 'terms-1',
    version: 2,
    locales: ['ru', 'en'],
    publishedBy: 'owner-1',
    publishedAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('tenant-1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function render(
  brandsApi: Partial<BrandProfileApi>,
  termsApi: Partial<TermsApi>,
  tenant: FakeCurrentTenant = new FakeCurrentTenant(),
): Promise<ComponentFixture<TermsPage>> {
  await TestBed.configureTestingModule({
    imports: [TermsPage],
    providers: [
      provideRouter([]),
      { provide: BrandProfileApi, useValue: brandsApi },
      { provide: TermsApi, useValue: termsApi },
      { provide: CurrentTenant, useValue: tenant },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<TermsPage> = TestBed.createComponent(TermsPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('TermsPage', () => {
  it('names the platform default when the brand has never published', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('platform’s own default terms text');
  });

  it('does not render a brand picker with only one brand', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );
    expect(fixture.nativeElement.querySelector('#terms-brand')).toBeFalsy();
  });

  it('renders a brand picker with more than one brand', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND, BRAND_2]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );
    const select = fixture.nativeElement.querySelector('#terms-brand') as HTMLSelectElement;
    expect(select).toBeTruthy();
    expect(select.querySelectorAll('option').length).toBe(2);
  });

  it('renders the publish history newest first, with its locales and who published', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        current: vi.fn().mockResolvedValue(published()),
        list: vi
          .fn()
          .mockResolvedValue([
            summary(),
            summary({ id: 'terms-0', version: 1, locales: ['ru'], publishedBy: 'owner-0' }),
          ]),
      },
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('owner-1');
    expect(text).toContain('owner-0');
    expect(text).toContain('ru, en');
  });

  it('loads a historical version read-only when a history row is clicked', async () => {
    const versionFn = vi
      .fn()
      .mockResolvedValue(
        published({ version: 1, contentsByLocale: { ru: 'Старый текст' }, publishedBy: 'owner-0' }),
      );
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        current: vi.fn().mockResolvedValue(published()),
        list: vi
          .fn()
          .mockResolvedValue([summary({ version: 1, locales: ['ru'], publishedBy: 'owner-0' })]),
        version: versionFn,
      },
    );
    const row = fixture.nativeElement.querySelector('.row--clickable') as HTMLElement;
    row.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(versionFn).toHaveBeenCalledWith('tenant-1', 'brand-1', 1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Старый текст');
  });

  it('publishes a new version from whatever locales were filled in, and shows the result', async () => {
    const publish = vi.fn().mockResolvedValue(published({ version: 3 }));
    const listFn = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([summary({ version: 3 })]);
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        current: vi.fn().mockResolvedValue(NEVER_PUBLISHED),
        list: listFn,
        publish,
      },
    );

    // The three `q-rich-text` editors render in ru/uz/en order — the first
    // `q-rich-text-textarea` on the page is the Russian one.
    const ruEditor = fixture.nativeElement.querySelectorAll(
      '[data-testid="q-rich-text-textarea"]',
    )[0] as HTMLTextAreaElement;
    ruEditor.value = 'Новые правила';
    ruEditor.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = fixture.nativeElement.querySelector(
      '.form__actions button',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    // `q-rich-text` always emits sanitized HTML, even for one plain block —
    // see this file's own note above the `openIssues` this wave reports on
    // the storefront's still-plain-text rendering of this same field.
    expect(publish).toHaveBeenCalledWith('tenant-1', 'brand-1', {
      contentsByLocale: { ru: '<p>Новые правила</p>' },
      note: undefined,
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Published as version 3');
  });

  /**
   * `describeApiError` (`order-errors.ts`) now uniformly renders `detail`
   * plus the correlation id for any unmapped 4xx — the fix for the two
   * pre-production "Unknown provider environment" incidents where an
   * operator saw only a bare "Something went wrong. Reference …" with the
   * server's own detail nowhere on screen. This screen's own `describe()`
   * special-cases `VALIDATION_FAILED` ahead of that shared helper; it must
   * still carry the reference along, not drop it, so a VALIDATION_FAILED
   * publish failure renders exactly as every other screen's does.
   */
  it('shows the server detail and the correlation id when publishing fails validation', async () => {
    const publish = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          ApiErrorCode.VALIDATION_FAILED,
          400,
          { status: 400, detail: 'At least one locale is required.' },
          'corr-42',
        ),
      );
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        current: vi.fn().mockResolvedValue(NEVER_PUBLISHED),
        list: vi.fn().mockResolvedValue([]),
        publish,
      },
    );

    const ruEditor = fixture.nativeElement.querySelectorAll(
      '[data-testid="q-rich-text-textarea"]',
    )[0] as HTMLTextAreaElement;
    ruEditor.value = 'Новые правила';
    ruEditor.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = fixture.nativeElement.querySelector(
      '.form__actions button',
    ) as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    const message = fixture.nativeElement.querySelector('.error')?.textContent ?? '';
    expect(message).toContain('At least one locale is required.');
    expect(message).toContain('corr-42');
  });

  it('shows the denied state on a 403 rather than an empty page', async () => {
    const fixture = await render(
      {
        list: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      },
      { current: vi.fn(), list: vi.fn() },
    );
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });

  it('shows the denied state when the tenant itself resolves to none', async () => {
    const tenant = new FakeCurrentTenant();
    tenant.tenantId.set(null);
    tenant.denied.set(true);
    const list = vi.fn();
    const fixture = await render({ list }, { current: vi.fn(), list: vi.fn() }, tenant);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
    expect(list).not.toHaveBeenCalled();
  });

  // ------------------------------------------------------------------ 10.12

  function editors(fixture: ComponentFixture<TermsPage>): HTMLElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
        '[data-testid^="terms-editor-"]',
      ),
    );
  }

  function typeInto(fixture: ComponentFixture<TermsPage>, locale: string, text: string): void {
    const textarea = (fixture.nativeElement as HTMLElement).querySelector(
      `[data-testid="terms-editor-${locale}"] [data-testid="q-rich-text-textarea"]`,
    ) as HTMLTextAreaElement;
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it("offers the brand's own languages, default first and marked, instead of the fixed triple", async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([UZ_EN_BRAND]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );

    const blocks = editors(fixture);
    expect(blocks.map((block) => block.dataset['testid'])).toEqual([
      'terms-editor-uz-Latn',
      'terms-editor-en',
    ]);
    expect(blocks[0].textContent).toContain('Uzbek');
    expect(blocks[0].querySelector('[data-testid="terms-default-marker"]')).toBeTruthy();
    expect(blocks[1].querySelector('[data-testid="terms-default-marker"]')).toBeFalsy();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Russian');
  });

  it('falls back to the platform triple for a brand that has chosen no languages', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );

    expect(editors(fixture).map((block) => block.dataset['testid'])).toEqual([
      'terms-editor-ru',
      'terms-editor-uz-Latn',
      'terms-editor-en',
    ]);
  });

  it('carries a language the brand does not offer into the new version unchanged', async () => {
    const publish = vi.fn().mockResolvedValue(published({ version: 3 }));
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([UZ_EN_BRAND]) },
      {
        current: vi.fn().mockResolvedValue(
          published({
            contentsByLocale: { ru: 'Правила', 'uz-Latn': 'Qoidalar', en: 'Terms' },
          }),
        ),
        list: vi.fn().mockResolvedValue([]),
        publish,
      },
    );

    typeInto(fixture, 'en', 'New terms');
    (fixture.nativeElement.querySelector('.form__actions button') as HTMLButtonElement).click();
    await flushMicrotasks();

    // A version is a whole new document — leaving ru out would drop it from
    // the live terms, though this editor never showed it.
    expect(publish).toHaveBeenCalledWith('tenant-1', 'brand-1', {
      contentsByLocale: { ru: 'Правила', 'uz-Latn': 'Qoidalar', en: '<p>New terms</p>' },
      note: undefined,
    });
  });

  it('lets an operator clear an offered language while the hidden one stays', async () => {
    const publish = vi.fn().mockResolvedValue(published({ version: 3 }));
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([UZ_EN_BRAND]) },
      {
        current: vi.fn().mockResolvedValue(
          published({
            contentsByLocale: { ru: 'Правила', 'uz-Latn': 'Qoidalar', en: 'Terms' },
          }),
        ),
        list: vi.fn().mockResolvedValue([]),
        publish,
      },
    );

    typeInto(fixture, 'en', '');
    (fixture.nativeElement.querySelector('.form__actions button') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(publish).toHaveBeenCalledWith('tenant-1', 'brand-1', {
      contentsByLocale: { ru: 'Правила', 'uz-Latn': 'Qoidalar' },
      note: undefined,
    });
  });

  it('keeps Publish off when every offered language is blank, even if a hidden one has text', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([RU_ONLY_BRAND]) },
      {
        current: vi
          .fn()
          .mockResolvedValue(
            published({ contentsByLocale: { 'uz-Latn': 'Qoidalar', en: 'Terms' } }),
          ),
        list: vi.fn().mockResolvedValue([]),
      },
    );

    const submit = fixture.nativeElement.querySelector(
      '.form__actions button',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);

    typeInto(fixture, 'ru', 'Правила');
    expect(submit.disabled).toBe(false);
  });

  it('re-offers the languages of the brand picked in the brand picker', async () => {
    const current = vi
      .fn()
      .mockImplementation(async (_tenant: string, brandId: string) =>
        brandId === 'brand-2'
          ? published({ contentsByLocale: { ru: 'Правила Oshxona', en: 'Oshxona terms' } })
          : NEVER_PUBLISHED,
      );
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([UZ_EN_BRAND, RU_ONLY_BRAND]) },
      { current, list: vi.fn().mockResolvedValue([]) },
    );
    expect(editors(fixture).map((block) => block.dataset['testid'])).toEqual([
      'terms-editor-uz-Latn',
      'terms-editor-en',
    ]);

    const select = fixture.nativeElement.querySelector('#terms-brand') as HTMLSelectElement;
    select.value = 'brand-2';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(editors(fixture).map((block) => block.dataset['testid'])).toEqual(['terms-editor-ru']);
    const textarea = fixture.nativeElement.querySelector(
      '[data-testid="terms-editor-ru"] [data-testid="q-rich-text-textarea"]',
    ) as HTMLTextAreaElement;
    expect(textarea.value).toContain('Правила Oshxona');
  });

  it("previews a past version's hidden language and names only the brand's own gaps", async () => {
    const versionFn = vi
      .fn()
      .mockResolvedValue(
        published({ version: 1, contentsByLocale: { ru: 'Старый текст', en: 'Old text' } }),
      );
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([UZ_EN_BRAND]) },
      {
        current: vi.fn().mockResolvedValue(published()),
        list: vi
          .fn()
          .mockResolvedValue([
            summary({ version: 1, locales: ['ru', 'en'], publishedBy: 'owner-0' }),
          ]),
        version: versionFn,
      },
    );

    (fixture.nativeElement.querySelector('.row--clickable') as HTMLElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const panels = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.preview__locale'),
    );
    // ru (in the version, not offered) + uz-Latn (offered, missing) + en (offered, present).
    expect(panels.map((panel) => panel.dataset['locale'])).toEqual(['ru', 'uz-Latn', 'en']);
    expect(panels[0].textContent).toContain('Старый текст');
    expect(panels[1].textContent).toContain('Not included');
    expect(panels[2].textContent).toContain('Old text');
  });
});
