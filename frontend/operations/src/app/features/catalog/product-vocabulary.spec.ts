import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { ProductVocabulary } from './product-vocabulary';
import { VocabularyApi, VocabularyEntry } from './vocabulary-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

function entry(id: string, code: string, status = 'ACTIVE'): VocabularyEntry {
  return {
    id,
    code,
    iconKey: null,
    showAsBadge: true,
    showAsFilter: false,
    sortOrder: 0,
    status,
    version: 1,
    names: { ru: code.toUpperCase(), en: code },
  };
}

const SPICY = entry('1', 'spicy');
const VEGAN = entry('2', 'vegan');
const NEW = entry('3', 'new');
const RETIRED = entry('4', 'retired', 'ARCHIVED');

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ProductVocabulary', () => {
  let fixture: ComponentFixture<ProductVocabulary>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    forProduct: ReturnType<typeof vi.fn>;
    setForProduct: ReturnType<typeof vi.fn>;
  };

  async function render(kind: 'TAG' | 'INGREDIENT' = 'TAG'): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ProductVocabulary],
      providers: [{ provide: VocabularyApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ProductVocabulary);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('productId', 'p1');
    fixture.componentRef.setInput('kind', kind);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(testId: string): T {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as T;
  }

  function codes(): string[] {
    return [
      ...fixture.nativeElement.querySelectorAll('[data-testid="vocabulary-selected"] li'),
    ].map((li) => (li as HTMLElement).getAttribute('data-code') ?? '');
  }

  function pick(id: string): void {
    const select = el<HTMLSelectElement>('vocabulary-picker');
    select.value = id;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    api = {
      list: vi.fn().mockResolvedValue([SPICY, VEGAN, NEW, RETIRED]),
      forProduct: vi.fn().mockResolvedValue([SPICY, RETIRED]),
      setForProduct: vi.fn(),
    };
  });

  it('shows the product’s own set in order, archived entries included and marked, from the tenant’s registry', async () => {
    await render();

    expect(api.list).toHaveBeenCalledWith('t1', 'TAG');
    expect(api.forProduct).toHaveBeenCalledWith(SCOPE, 'TAG', 'p1');
    expect(codes()).toEqual(['spicy', 'retired']);
    expect(el('vocabulary-archived')).not.toBeNull();
  });

  it('offers only active entries not already chosen', async () => {
    await render();

    const options = [...el<HTMLSelectElement>('vocabulary-picker').options].map((o) =>
      o.textContent?.trim(),
    );
    expect(options).toContain('vegan');
    expect(options).toContain('new');
    expect(options).not.toContain('spicy');
    expect(options).not.toContain('retired');
  });

  it('adds, reorders and removes, and saves the whole set in that order', async () => {
    api.setForProduct.mockResolvedValue([VEGAN, SPICY, RETIRED]);
    await render();
    expect(el<HTMLButtonElement>('vocabulary-save').disabled).toBe(true);

    pick('2');
    expect(codes()).toEqual(['spicy', 'retired', 'vegan']);
    el<HTMLButtonElement>('vocabulary-save').click();
    await flush();
    fixture.detectChanges();

    expect(api.setForProduct).toHaveBeenCalledWith(SCOPE, 'TAG', 'p1', ['1', '4', '2']);
    expect(el('vocabulary-saved')).not.toBeNull();
    expect(el<HTMLButtonElement>('vocabulary-save').disabled).toBe(true);

    const ups = fixture.nativeElement.querySelectorAll('[data-testid="vocabulary-up"]');
    (ups[2] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(codes()).toEqual(['vegan', 'retired', 'spicy']);
    const removes = fixture.nativeElement.querySelectorAll('[data-testid="vocabulary-remove"]');
    (removes[2] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(codes()).toEqual(['vegan', 'retired']);
    expect(el<HTMLButtonElement>('vocabulary-save').disabled).toBe(false);
  });

  it('refuses to add past the bound instead of waiting for the server to say so', async () => {
    const twelve = Array.from({ length: 12 }, (_, n) => entry(`t${n}`, `tag-${n}`));
    api.list.mockResolvedValue([...twelve, VEGAN]);
    api.forProduct.mockResolvedValue(twelve);
    await render();

    expect(el('vocabulary-full').textContent).toContain('12');
    expect(el('vocabulary-picker')).toBeNull();
  });

  it('allows forty ingredients, not twelve', async () => {
    const twelve = Array.from({ length: 12 }, (_, n) => entry(`i${n}`, `ing-${n}`));
    api.list.mockResolvedValue([...twelve, VEGAN]);
    api.forProduct.mockResolvedValue(twelve);
    await render('INGREDIENT');

    expect(el('vocabulary-full')).toBeNull();
    expect(el('vocabulary-picker')).not.toBeNull();
  });

  it('names the refusal in the operator’s language when the server says the set is full', async () => {
    api.setForProduct.mockRejectedValue(
      new ApiError(
        ApiErrorCode.UNPROCESSABLE_STATE,
        422,
        {
          status: 422,
          code: ApiErrorCode.UNPROCESSABLE_STATE,
          findingCode: 'VOCABULARY_PRODUCT_LIMIT_REACHED',
        } as never,
        null,
      ),
    );
    await render();
    pick('2');

    el<HTMLButtonElement>('vocabulary-save').click();
    await flush();
    fixture.detectChanges();

    expect(el('vocabulary-error').textContent).toContain('limited number');
  });
});
