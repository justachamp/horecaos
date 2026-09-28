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
    expect(filters).toEqual({ categoryId: 'cat-1', locale: 'en' });
  });

  it("sends the operator's own UI locale, not the server's uz default", async () => {
    const matrixPage: Page<PriceBookMatrixRow> = { items: [row()], nextCursor: null };
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
      ) => of<Page<PriceBookMatrixRow>>(matrixPage),
    );
    await render(
      {
        readPriceBook: () => of(BOOK),
        matrix: matrixSpy,
      },
      {
        listCatalogs: () => of([]),
        listCategories: () => of([]),
      },
      BOOK_ID,
      'ru',
    );

    // A console set to Russian must not silently fall back to
    // PriceAuthoringController.matrix's own `uz` default: displayName and
    // categoryName come from whichever locale is sent, and every other
    // catalog screen's own locale-bearing call already sends the operator's.
    expect(matrixSpy).toHaveBeenCalledTimes(1);
    const [, , , filters] = matrixSpy.mock.calls[0];
    expect(filters).toMatchObject({ locale: 'ru' });
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
