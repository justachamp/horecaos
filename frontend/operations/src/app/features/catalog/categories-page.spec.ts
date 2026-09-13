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
    description: null,
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

  it('moving a node through the tree calls updateCategory with the new parent', async () => {
    const updateCategory = vi.fn().mockReturnValue(of(undefined));
    configure({
      listCatalogs: () =>
        of([{ catalogId: 'catalog-1', code: 'MAIN', name: 'Основной', status: 'ACTIVE' }]),
      listCategories: vi
        .fn()
        .mockReturnValue(
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
      listCategories: vi
        .fn()
        .mockReturnValue(
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
