import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CursorState, Page } from '../../core/api/page';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n, Locale } from '../../core/i18n/i18n';
import { LocaleSet } from '../../core/i18n/locale-set';
import { CatalogApi } from './catalog-api';
import { PriceBookMatrixRow, PriceBookSummary } from './catalog-domain';
import { PriceBookMatrixPage } from './price-book-matrix-page';
import { PricingApi } from './pricing-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const BOOK_ID = 'book-1';

const BOOK: PriceBookSummary = {
  priceBookId: BOOK_ID,
  name: 'Draft menu',
  currency: 'UZS',
  status: 'DRAFT',
  priority: 0,
  validFrom: new Date().toISOString(),
  validUntil: null,
  version: 2,
};

function row(overrides: Partial<PriceBookMatrixRow> = {}): PriceBookMatrixRow {
  return {
    variantId: 'v1',
    productId: 'p1',
    displayName: 'Burger',
    categoryId: 'cat-1',
    categoryName: 'Mains',
    bookPriceMinor: 50_000,
    bookPriceVersion: 1,
    basePriceMinor: 45_000,
    deltaMinor: 5_000,
    currency: 'UZS',
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/** Row 10.12: a brand's resolved locale set; unconfigured by default, the way `categories-page.spec.ts` fakes it. */
class FakeLocaleSet {
  readonly locales = signal<readonly Locale[]>(['ru', 'uz-Latn', 'en']);
  readonly defaultLocale = signal<Locale>('ru');
  readonly isConfigured = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

describe('PriceBookMatrixPage', () => {
  let fixture: ComponentFixture<PriceBookMatrixPage>;

  async function render(
    pricing: Partial<PricingApi>,
    catalog: Partial<CatalogApi> = {
      listCatalogs: () => of([]),
      listCategories: () => of([]),
    },
    priceBookId: string | null = BOOK_ID,
    locale: Locale = 'en',
    localeSet: FakeLocaleSet = new FakeLocaleSet(),
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [PriceBookMatrixPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap(priceBookId ? { priceBookId } : {}) },
          },
        },
        { provide: LocaleSet, useValue: localeSet },
        { provide: PricingApi, useValue: pricing },
        { provide: CatalogApi, useValue: catalog },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale(locale);
    fixture = TestBed.createComponent(PriceBookMatrixPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function query(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  it('loads the book, its rows, and shows the base price and delta', async () => {
    const matrixPage: Page<PriceBookMatrixRow> = { items: [row()], nextCursor: null };
    await render({
      readPriceBook: () => of(BOOK),
      matrix: vi.fn(() => of(matrixPage)),
    });

    expect(text()).toContain('Draft menu');
    expect(text()).toContain('Burger');
    expect(query('[data-testid="price-matrix-table"]')).not.toBeNull();
    expect(text()).toContain('45 000');
    expect(text()).toContain('+5 000');
  });

  it('marks the price of a variant sold by weight as per its quantum, and no other', async () => {
    const matrixPage: Page<PriceBookMatrixRow> = {
      items: [
        row({ variantId: 'cake', displayName: 'Cake', catchweightQuantumGrams: 100 }),
        row({ variantId: 'soda', displayName: 'Soda' }),
        row({ variantId: 'ham', displayName: 'Ham', catchweightQuantumGrams: 1_000 }),
      ],
      nextCursor: null,
    };
    await render({ readPriceBook: () => of(BOOK), matrix: vi.fn(() => of(matrixPage)) });

    expect(query('[data-testid="price-matrix-unit-cake"]')?.textContent?.trim()).toBe(
      'per 100\u00a0g',
    );
    expect(query('[data-testid="price-matrix-unit-ham"]')?.textContent?.trim()).toBe(
      'per 1\u00a0kg',
    );
    expect(query('[data-testid="price-matrix-unit-soda"]')).toBeNull();
  });

  it('words the unit in the console language', async () => {
    const matrixPage: Page<PriceBookMatrixRow> = {
      items: [row({ variantId: 'cake', displayName: 'Торт', catchweightQuantumGrams: 100 })],
      nextCursor: null,
    };
    await render(
      { readPriceBook: () => of(BOOK), matrix: vi.fn(() => of(matrixPage)) },
      undefined,
      BOOK_ID,
      'ru',
    );

    expect(query('[data-testid="price-matrix-unit-cake"]')?.textContent?.trim()).toBe(
      'за 100\u00a0г',
    );
  });

  it('shows the denied state when the book read is refused', async () => {
    await render({
      readPriceBook: () =>
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });

    expect(query('[data-testid="price-matrix-denied"]')).not.toBeNull();
  });

  it('shows the not-found state for a book that does not exist', async () => {
    await render({
      readPriceBook: () => throwError(() => new ApiError('RESOURCE_NOT_FOUND', 404, null, null)),
    });

    expect(query('[data-testid="price-matrix-not-found"]')).not.toBeNull();
  });

  it('re-requests the matrix with the chosen category and resets the cursor', async () => {
    const matrixSpy = vi.fn(
      (
        _scope: BrandScope,
        _priceBookId: string,
        _state: CursorState,
        _filters?: {
          readonly categoryId?: string;
          readonly differsFromBase?: boolean;
          readonly locale?: string;
        },
      ) => of<Page<PriceBookMatrixRow>>({ items: [row()], nextCursor: null }),
    );
    await render(
      {
        readPriceBook: () => of(BOOK),
        matrix: matrixSpy,
      },
      {
        listCatalogs: () =>
          of([{ catalogId: 'cat-catalog', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
        listCategories: () =>
          of([
            {
              categoryId: 'cat-1',
              code: 'MAINS',
              name: 'Mains',
              sortOrder: 0,
              status: 'ACTIVE',
              productCount: 1,
              translations: {},
            },
          ]),
      },
    );
    matrixSpy.mockClear();

    const select = query('[data-testid="price-matrix-category"]') as HTMLSelectElement;
    select.value = 'cat-1';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(matrixSpy).toHaveBeenCalledTimes(1);
    const [, , state, filters] = matrixSpy.mock.calls[0];
    expect(state.cursor).toBeNull();
    // The brand has configured no set here, so the matrix reads the locale the server
    // resolves for it (`uz`), whatever the console (`en`) is set to.
    expect(filters).toEqual({ categoryId: 'cat-1', locale: 'uz' });
  });

  /** Renders the page and returns the filters the first matrix read carried. */
  async function firstMatrixFilters(
    consoleLocale: Locale,
    localeSet: FakeLocaleSet,
  ): Promise<{ readonly locale?: string } | undefined> {
    const matrixSpy = vi.fn(
      (
        _scope: BrandScope,
        _priceBookId: string,
        _state: CursorState,
        _filters?: {
          readonly categoryId?: string;
          readonly differsFromBase?: boolean;
          readonly locale?: string;
        },
      ) => of<Page<PriceBookMatrixRow>>({ items: [row()], nextCursor: null }),
    );
    await render(
      { readPriceBook: () => of(BOOK), matrix: matrixSpy },
      { listCatalogs: () => of([]), listCategories: () => of([]) },
      BOOK_ID,
      consoleLocale,
      localeSet,
    );
    expect(matrixSpy).toHaveBeenCalledTimes(1);
    return matrixSpy.mock.calls[0][3];
  }

  it("reads names in the brand's own default language, not the operator's console language", async () => {
    // The console is Russian; the brand's menu is in English. displayName and categoryName
    // come from whichever locale is sent, so two operators of one brand must send the same one.
    const localeSet = new FakeLocaleSet();
    localeSet.locales.set(['en', 'ru']);
    localeSet.defaultLocale.set('en');
    localeSet.isConfigured.set(true);

    expect(await firstMatrixFilters('ru', localeSet)).toMatchObject({ locale: 'en' });
  });

  it('maps a brand default of uz-Latn to the catalog’s uz, and an unconfigured brand to the server’s uz', async () => {
    const uzbekBrand = new FakeLocaleSet();
    uzbekBrand.locales.set(['uz-Latn']);
    uzbekBrand.defaultLocale.set('uz-Latn');
    uzbekBrand.isConfigured.set(true);
    expect(await firstMatrixFilters('ru', uzbekBrand)).toMatchObject({ locale: 'uz' });

    TestBed.resetTestingModule();
    // LocaleSet's platform fallback is `ru`; PriceAuthoringController.matrix reads `uz` for a
    // brand that has chosen nothing, and a console set to Russian must not change that.
    expect(await firstMatrixFilters('ru', new FakeLocaleSet())).toMatchObject({ locale: 'uz' });
  });

  it('saves a row through the existing per-variant write, sending the row’s own version as If-Match', async () => {
    const setVariantPrice = vi.fn(() => of(BOOK));
    const matrixSpy = vi
      .fn()
      .mockReturnValueOnce(of<Page<PriceBookMatrixRow>>({ items: [row()], nextCursor: null }))
      .mockReturnValue(
        of<Page<PriceBookMatrixRow>>({
          items: [row({ bookPriceMinor: 60_000, bookPriceVersion: 2, deltaMinor: 15_000 })],
          nextCursor: null,
        }),
      );
    await render({
      readPriceBook: () => of(BOOK),
      matrix: matrixSpy,
      setVariantPrice,
    });

    const input = query('[data-testid="price-matrix-input-v1"]') as HTMLInputElement;
    input.value = '60000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = query('[data-testid="price-matrix-save-v1"]') as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(setVariantPrice).toHaveBeenCalledWith(SCOPE, BOOK_ID, 'v1', 60_000, 1);
    // The row is re-read after a successful save rather than guessed at
    // locally — the new version (2) is what a second edit would send back,
    // shown here as the input's own value reflecting the reloaded row.
    expect(matrixSpy).toHaveBeenCalledTimes(2);
    const reloadedInput = query('[data-testid="price-matrix-input-v1"]') as HTMLInputElement;
    expect(reloadedInput.value).toBe('60000');
  });

  it('shows a conflict message and reloads on a stale write', async () => {
    const setVariantPrice = vi.fn(() =>
      throwError(
        () =>
          new ApiError(
            ApiErrorCode.STALE_VERSION,
            409,
            { status: 409, expected: 1, actual: 2 },
            null,
          ),
      ),
    );
    const matrixSpy = vi.fn(() =>
      of<Page<PriceBookMatrixRow>>({ items: [row()], nextCursor: null }),
    );
    await render({
      readPriceBook: () => of(BOOK),
      matrix: matrixSpy,
      setVariantPrice,
    });

    const saveButton = query('[data-testid="price-matrix-save-v1"]') as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(query('[data-testid="price-matrix-error-v1"]')).not.toBeNull();
    // One reload for the initial load, one more after the conflict.
    expect(matrixSpy).toHaveBeenCalledTimes(2);
  });

  it('offers "load more" only while a next cursor exists, and appends the next page', async () => {
    const firstPageRow = row({ variantId: 'v1' });
    const secondPageRow = row({ variantId: 'v2', displayName: 'Fries' });
    const matrixSpy = vi
      .fn()
      .mockReturnValueOnce(
        of<Page<PriceBookMatrixRow>>({ items: [firstPageRow], nextCursor: 'c1' }),
      )
      .mockReturnValueOnce(
        of<Page<PriceBookMatrixRow>>({ items: [secondPageRow], nextCursor: null }),
      );
    await render({
      readPriceBook: () => of(BOOK),
      matrix: matrixSpy,
    });

    const loadMore = query('[data-testid="price-matrix-load-more"]') as HTMLButtonElement;
    expect(loadMore).not.toBeNull();
    loadMore.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(text()).toContain('Burger');
    expect(text()).toContain('Fries');
    expect(query('[data-testid="price-matrix-load-more"]')).toBeNull();
  });
});
