import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { CurrentBrand } from '../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
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
    sortOrder: 0,
    status: 'ACTIVE',
    productCount: 3,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(catalogApi: Partial<CatalogApi>): void {
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
      { provide: CatalogApi, useValue: catalogApi },
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
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

    const nodes = [
      ...harness.routeNativeElement!.querySelectorAll('[data-testid="category-node"]'),
    ];
    expect(
      nodes.map((n) => n.querySelector('span:not(.categories__count)')?.textContent?.trim()),
    ).toEqual(expect.arrayContaining(['Еда', 'Супы']));
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

    (host.querySelector('[data-testid="category-node"]') as HTMLButtonElement).click();
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
});
