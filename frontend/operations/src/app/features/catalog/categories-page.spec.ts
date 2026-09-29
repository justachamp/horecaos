import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { CurrentBrand } from '../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n, Locale } from '../../core/i18n/i18n';
import { LocaleSet } from '../../core/i18n/locale-set';
import { CatalogApi } from './catalog-api';
import { CategoriesPage } from './categories-page';
import { CategorySummary } from './catalog-domain';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1' };

function category(overrides: Partial<CategorySummary>): CategorySummary {
  return {
    categoryId: 'cat-1',
    parentCategoryId: null,
    code: 'SALADS',
    name: 'Салаты',
    description: null,
    sortOrder: 0,
    status: 'ACTIVE',
    productCount: 3,
    translations: {},
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/** Row 10.12: a brand's resolved locale set, defaulting to the platform's own fallback triple — same fake `location-detail-pane.spec.ts` uses. */
class FakeLocaleSet {
  readonly locales = signal<readonly Locale[]>(['ru', 'uz-Latn', 'en']);
  readonly defaultLocale = signal<Locale>('ru');
  readonly isConfigured = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
  supports(locale: Locale): boolean {
    return this.locales().includes(locale);
  }
}

function configure(
  catalogApi: Partial<CatalogApi>,
  localeSet: FakeLocaleSet = new FakeLocaleSet(),
): FakeLocaleSet {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'catalog/categories', component: CategoriesPage }]),
      {
        provide: CurrentBrand,
        useValue: {
          scope: signal(FAKE_SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: LocaleSet, useValue: localeSet },
      {
        provide: CatalogApi,
        useValue: {
          listProducts: () => of({ items: [], nextCursor: null }),
          ...catalogApi,
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
  return localeSet;
}

describe('CategoriesPage', () => {
  it('renders a parent and its child indented beneath it', async () => {
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: () =>
        of([
          category({ categoryId: 'parent', name: 'Еда', sortOrder: 0 }),
          category({ categoryId: 'child', parentCategoryId: 'parent', name: 'Супы', sortOrder: 0 }),
        ]),
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();

    const nodes = [...harness.routeNativeElement!.querySelectorAll('[data-testid="tree-node"]')];
    expect(nodes.map((n) => n.getAttribute('data-node-id'))).toEqual(['parent', 'child']);
    const parentRow = nodes.find((n) => n.getAttribute('data-node-id') === 'parent') as HTMLElement;
    const childRow = nodes.find((n) => n.getAttribute('data-node-id') === 'child') as HTMLElement;
    expect(childRow.style.paddingLeft).not.toBe(parentRow.style.paddingLeft);
  });

  it('shows the selected category’s detail panel on click', async () => {
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: () => of([category({ categoryId: 'cat-1', name: 'Салаты' })]),
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="tree-node"]') as HTMLElement).click();
    await flushMicrotasks();

    expect(host.querySelector('.categories__detail')?.textContent).toContain('Салаты');
  });

  it('renders the denied state on a 403', async () => {
    configure({
      listCatalogs: () =>
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="categories-denied"]'),
    ).toBeTruthy();
  });

  it('creates a category under the selected parent', async () => {
    const createCategory = vi.fn().mockReturnValue(of({ id: 'new-cat' }));
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: vi
        .fn()
        .mockReturnValueOnce(of([]))
        .mockReturnValueOnce(of([category({ categoryId: 'new-cat', name: 'Десерты' })])),
      createCategory,
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="categories-create"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const nameInput = host.querySelector(
      '[data-testid="create-category-dialog-name"]',
    ) as HTMLInputElement;
    const codeInput = host.querySelector(
      '[data-testid="create-category-dialog-code"]',
    ) as HTMLInputElement;
    nameInput.value = 'Десерты';
    nameInput.dispatchEvent(new Event('input'));
    codeInput.value = 'DESSERTS';
    codeInput.dispatchEvent(new Event('input'));
    await flushMicrotasks();

    (
      host.querySelector('[data-testid="create-category-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(createCategory).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'catalog-1',
      expect.objectContaining({ code: 'DESSERTS', name: 'Десерты', parentCategoryId: null }),
    );
  });

  it('renaming a node through the tree preserves the category’s existing description', async () => {
    const setTranslation = vi.fn().mockReturnValue(of(undefined));
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: () =>
        of([category({ categoryId: 'cat-1', name: 'Салаты', description: 'Свежие овощи' })]),
      setTranslation,
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const row = host.querySelector('[data-node-id="cat-1"]') as HTMLElement;
    row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await flushMicrotasks();

    const input = host.querySelector('[data-testid="tree-node-rename-input"]') as HTMLInputElement;
    input.value = 'Овощи';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await flushMicrotasks();

    expect(setTranslation).toHaveBeenCalledWith(
      FAKE_SCOPE,
      expect.objectContaining({
        entityType: 'CATEGORY',
        entityId: 'cat-1',
        name: 'Овощи',
        description: 'Свежие овощи',
      }),
    );
  });

  it('renaming through the tree writes the server’s configured locale for a brand with no set, never the operator’s own console language', async () => {
    // Row 10.12's fix: the tree's own label resolves the locale
    // CatalogQueryService reads — for a brand that has configured no set,
    // the server's own `uz` — so a rename written anywhere else, such as
    // wherever the operator's own UI happens to be set, used to leave the
    // tree looking untouched. LocaleSet's platform fallback (`ru`) is NOT
    // that locale, which is why this brand still writes `uz`.
    const setTranslation = vi.fn().mockReturnValue(of(undefined));
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: () => of([category({ categoryId: 'cat-1', name: 'Салаты' })]),
      setTranslation,
    });
    // The operator's own console language, English here — deliberately not
    // the locale a rename should land in.
    TestBed.inject(I18n).setLocale('en');

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const row = host.querySelector('[data-node-id="cat-1"]') as HTMLElement;
    row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await flushMicrotasks();
    const input = host.querySelector('[data-testid="tree-node-rename-input"]') as HTMLInputElement;
    input.value = 'Vegetables';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await flushMicrotasks();

    expect(setTranslation).toHaveBeenCalledWith(
      FAKE_SCOPE,
      expect.objectContaining({ locale: 'uz', name: 'Vegetables' }),
    );
  });

  it('renaming through the tree writes the brand’s own default locale once it has chosen one', async () => {
    // Batch 14 (the CatalogQueryService mismatch): the tree label is now
    // resolved in the brand's own default language, so a rename must land
    // there. The server's `uz` no longer applies to this brand at all.
    for (const [brandDefault, wire] of [
      ['ru', 'ru'],
      ['en', 'en'],
      ['uz-Latn', 'uz'],
    ] as const) {
      TestBed.resetTestingModule();
      const setTranslation = vi.fn().mockReturnValue(of(undefined));
      const localeSet = new FakeLocaleSet();
      localeSet.isConfigured.set(true);
      localeSet.locales.set([
        brandDefault,
        ...(['ru', 'uz-Latn', 'en'] as const).filter((l) => l !== brandDefault),
      ]);
      localeSet.defaultLocale.set(brandDefault);
      configure(
        {
          listCatalogs: () =>
            of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
          listCategories: () => of([category({ categoryId: 'cat-1', name: 'Салаты' })]),
          setTranslation,
        },
        localeSet,
      );
      TestBed.inject(I18n).setLocale('en');

      const harness = await RouterTestingHarness.create('/catalog/categories');
      await flushMicrotasks();
      const host = harness.routeNativeElement!;
      const row = host.querySelector('[data-node-id="cat-1"]') as HTMLElement;
      row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await flushMicrotasks();
      const input = host.querySelector(
        '[data-testid="tree-node-rename-input"]',
      ) as HTMLInputElement;
      input.value = 'Vegetables';
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await flushMicrotasks();

      expect(setTranslation, `brand default ${brandDefault}`).toHaveBeenCalledWith(
        FAKE_SCOPE,
        expect.objectContaining({ locale: wire, name: 'Vegetables' }),
      );
    }
  });

  it('creating a category always authors it in the catalog’s default locale, not the operator’s own console language', async () => {
    const createCategory = vi.fn().mockReturnValue(of({ id: 'new-cat' }));
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: vi.fn().mockReturnValue(of([])),
      createCategory,
    });
    TestBed.inject(I18n).setLocale('en');

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="categories-create"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    const nameInput = host.querySelector(
      '[data-testid="create-category-dialog-name"]',
    ) as HTMLInputElement;
    const codeInput = host.querySelector(
      '[data-testid="create-category-dialog-code"]',
    ) as HTMLInputElement;
    nameInput.value = 'Desserts';
    nameInput.dispatchEvent(new Event('input'));
    codeInput.value = 'DESSERTS';
    codeInput.dispatchEvent(new Event('input'));
    await flushMicrotasks();
    (
      host.querySelector('[data-testid="create-category-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(createCategory).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'catalog-1',
      expect.objectContaining({ locale: 'uz', name: 'Desserts' }),
    );
  });

  it('creating a category authors it in the brand’s own default locale once it has chosen one', async () => {
    const createCategory = vi.fn().mockReturnValue(of({ id: 'new-cat' }));
    const localeSet = new FakeLocaleSet();
    localeSet.isConfigured.set(true);
    localeSet.locales.set(['en', 'ru']);
    localeSet.defaultLocale.set('en');
    configure(
      {
        listCatalogs: () =>
          of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
        listCategories: vi.fn().mockReturnValue(of([])),
        createCategory,
      },
      localeSet,
    );
    TestBed.inject(I18n).setLocale('ru');

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="categories-create"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    const nameInput = host.querySelector(
      '[data-testid="create-category-dialog-name"]',
    ) as HTMLInputElement;
    const codeInput = host.querySelector(
      '[data-testid="create-category-dialog-code"]',
    ) as HTMLInputElement;
    nameInput.value = 'Desserts';
    nameInput.dispatchEvent(new Event('input'));
    codeInput.value = 'DESSERTS';
    codeInput.dispatchEvent(new Event('input'));
    await flushMicrotasks();
    (
      host.querySelector('[data-testid="create-category-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(createCategory).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'catalog-1',
      expect.objectContaining({ locale: 'en', name: 'Desserts' }),
    );
  });

  it('the content grid shows one row per brand-supported locale, brand default first', async () => {
    const localeSet = new FakeLocaleSet();
    // The fake, like LocaleSet's own real contract, returns the default
    // locale first — so `en` (the brand's chosen default here) is listed
    // before `ru`, its own canonical-order position notwithstanding.
    // uz-Latn is included too (uz never dropping out of the grid is its own
    // spec, below), so this one stays purely about ordering.
    localeSet.locales.set(['en', 'ru', 'uz-Latn']);
    localeSet.defaultLocale.set('en');
    configure(
      {
        listCatalogs: () =>
          of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
        listCategories: () =>
          of([
            category({
              categoryId: 'cat-1',
              name: 'Салаты',
              translations: {
                ru: { name: 'Салаты', description: 'Свежие овощи' },
                en: { name: 'Salads', description: null },
              },
            }),
          ]),
      },
      localeSet,
    );
    TestBed.inject(I18n).setLocale('en');

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="tree-node"]') as HTMLElement).click();
    await flushMicrotasks();

    // en is the brand's own default, so it sorts first even though ru comes
    // first in LocaleSet's own canonical ordering; uz-Latn — untranslated —
    // trails with an empty field.
    const nameInputs = [
      ...host.querySelectorAll('.categories__locale-row input'),
    ] as HTMLInputElement[];
    expect(nameInputs.map((el) => el.value)).toEqual(['Salads', 'Салаты', '']);
  });

  it('marks the brand’s own default locale as the default row — the locale the tree label and every list read now resolve', async () => {
    // Batch 14: CatalogQueryService resolves a list screen's names in the
    // brand's own default language. The marker (batch 13 pinned it to the
    // server's `uz` while the read ignored the brand) follows the brand's
    // choice again, so an operator who fills in the row marked "Default"
    // sees the name in the tree and everywhere else in the console.
    const localeSet = new FakeLocaleSet();
    localeSet.isConfigured.set(true);
    localeSet.locales.set(['en', 'ru', 'uz-Latn']);
    localeSet.defaultLocale.set('en');
    configure(
      {
        listCatalogs: () =>
          of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
        listCategories: () =>
          of([
            category({
              categoryId: 'cat-1',
              name: 'Salads',
              translations: {
                en: { name: 'Salads', description: null },
                uz: { name: 'Salatlar', description: null },
              },
            }),
          ]),
      },
      localeSet,
    );
    TestBed.inject(I18n).setLocale('en');

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="tree-node"]') as HTMLElement).click();
    await flushMicrotasks();

    const rows = [...host.querySelectorAll('.categories__locale-row')] as HTMLElement[];
    const markedLocales = rows
      .filter((row) => row.querySelector('legend')!.textContent!.includes('Default'))
      .map((row) => row.querySelector('input')!.getAttribute('data-testid'));
    expect(markedLocales).toEqual(['category-locale-name-en']);
  });

  it('marks uz-Latn as the default row for a brand with no set — the locale the server reads for it', async () => {
    // An unconfigured brand reports LocaleSet's platform default (`ru`), which
    // is not what CatalogQueryService reads for it (the server's `uz`).
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: () => of([category({ categoryId: 'cat-1', name: 'Салаты' })]),
    });
    TestBed.inject(I18n).setLocale('en');

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="tree-node"]') as HTMLElement).click();
    await flushMicrotasks();

    const rows = [...host.querySelectorAll('.categories__locale-row')] as HTMLElement[];
    const markedLocales = rows
      .filter((row) => row.querySelector('legend')!.textContent!.includes('Default'))
      .map((row) => row.querySelector('input')!.getAttribute('data-testid'));
    expect(markedLocales).toEqual(['category-locale-name-uz-Latn']);
  });

  it('saves every non-blank locale row and never touches a locale the brand no longer supports', async () => {
    const setTranslation = vi.fn().mockReturnValue(of(undefined));
    const localeSet = new FakeLocaleSet();
    localeSet.locales.set(['ru']);
    localeSet.defaultLocale.set('ru');
    configure(
      {
        listCatalogs: () =>
          of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
        listCategories: vi.fn().mockReturnValue(
          of([
            category({
              categoryId: 'cat-1',
              name: 'Салаты',
              translations: {
                ru: { name: 'Салаты', description: null },
                // A locale the brand narrowed away from, but still carries
                // stored content — must never be part of this save.
                en: { name: 'Salads', description: 'Fresh vegetables' },
              },
            }),
          ]),
        ),
        setTranslation,
      },
      localeSet,
    );

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="tree-node"]') as HTMLElement).click();
    await flushMicrotasks();

    // One row: the brand's one supported locale. Batch 13 forced uz-Latn in as
    // a second row because the list reads resolved the server's `uz`; they
    // now resolve the brand's own default, so nothing is forced. en never
    // appears: the brand narrowed its own set away from it.
    expect(host.querySelectorAll('.categories__locale-row').length).toBe(1);
    expect(host.querySelector('[data-testid="category-locale-name-en"]')).toBeNull();
    expect(host.querySelector('[data-testid="category-locale-name-uz-Latn"]')).toBeNull();
    const nameInput = host.querySelector(
      '[data-testid="category-locale-name-ru"]',
    ) as HTMLInputElement;
    nameInput.value = 'Свежие салаты';
    nameInput.dispatchEvent(new Event('input'));
    await flushMicrotasks();

    (host.querySelector('[data-testid="category-content-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setTranslation).toHaveBeenCalledTimes(1);
    expect(setTranslation).toHaveBeenCalledWith(
      FAKE_SCOPE,
      expect.objectContaining({ locale: 'ru', name: 'Свежие салаты' }),
    );
    expect(setTranslation).not.toHaveBeenCalledWith(
      FAKE_SCOPE,
      expect.objectContaining({ locale: 'en' }),
    );
  });

  it('resolves LocaleSet on load, so the content grid never sticks on the platform fallback for a configured brand', async () => {
    const localeSet = new FakeLocaleSet();
    configure(
      {
        listCatalogs: () => of([]),
      },
      localeSet,
    );

    await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();

    expect(localeSet.ensureLoaded).toHaveBeenCalled();
  });

  it('moving a node through the tree calls updateCategory with the new parent', async () => {
    const updateCategory = vi.fn().mockReturnValue(of(undefined));
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: vi.fn().mockReturnValue(
        of([
          category({ categoryId: 'food', name: 'Еда', sortOrder: 0 }),
          category({
            categoryId: 'drinks',
            parentCategoryId: null,
            name: 'Напитки',
            sortOrder: 1,
          }),
        ]),
      ),
      updateCategory,
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    function dragEvent(type: string): Event {
      return new Event(type, { bubbles: true, cancelable: true });
    }
    const dragged = host.querySelector('[data-node-id="drinks"]') as HTMLElement;
    const target = host.querySelector('[data-node-id="food"]') as HTMLElement;
    dragged.dispatchEvent(dragEvent('dragstart'));
    target.dispatchEvent(dragEvent('dragover'));
    target.dispatchEvent(dragEvent('drop'));
    await flushMicrotasks();

    expect(updateCategory).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'catalog-1',
      'drinks',
      expect.objectContaining({ parentCategoryId: 'food' }),
    );
  });

  it('a move the server refuses as a cycle shows the same finding copy a blocked publication renders', async () => {
    // The tree's own client-side guard already refuses an *obviously* cyclic
    // drop (see tree-view.spec.ts) before this page's onMove ever runs, so
    // this exercises the page's handling of the server's refusal directly:
    // an otherwise ordinary reparent that CatalogAuthoringService.updateCategory
    // rejects — exactly what happens when a second operator's concurrent edit
    // has made this session's view of the tree stale.
    const updateCategory = vi
      .fn()
      .mockReturnValue(
        throwError(
          () =>
            new ApiError(
              'UNPROCESSABLE_STATE',
              422,
              { status: 422, findingCode: 'CATEGORY_TREE_HAS_CYCLE' },
              null,
            ),
        ),
      );
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: vi.fn().mockReturnValue(
        of([
          category({ categoryId: 'food', name: 'Еда', sortOrder: 0 }),
          category({
            categoryId: 'drinks',
            parentCategoryId: null,
            name: 'Напитки',
            sortOrder: 1,
          }),
        ]),
      ),
      updateCategory,
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    function dragEvent(type: string): Event {
      return new Event(type, { bubbles: true, cancelable: true });
    }
    const dragged = host.querySelector('[data-node-id="drinks"]') as HTMLElement;
    const target = host.querySelector('[data-node-id="food"]') as HTMLElement;
    dragged.dispatchEvent(dragEvent('dragstart'));
    target.dispatchEvent(dragEvent('dragover'));
    target.dispatchEvent(dragEvent('drop'));
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="categories-tree-error"]')?.textContent).toContain(
      'Цикл в дереве категорий',
    );
  });

  it('archiving asks for confirmation, then calls archiveCategory', async () => {
    const archiveCategory = vi.fn().mockReturnValue(of(undefined));
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: () => of([category({ categoryId: 'cat-1', name: 'Салаты' })]),
      archiveCategory,
    });

    const harness = await RouterTestingHarness.create('/catalog/categories');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="tree-node"]') as HTMLElement).click();
    await flushMicrotasks();
    (host.querySelector('[data-testid="category-archive"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(archiveCategory).not.toHaveBeenCalled();
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(archiveCategory).toHaveBeenCalledWith(FAKE_SCOPE, 'catalog-1', 'cat-1');
  });
});
