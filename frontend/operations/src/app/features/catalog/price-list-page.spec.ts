import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { PriceListPage } from './price-list-page';
import { PricingApi } from './pricing-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PriceListPage', () => {
  let fixture: ComponentFixture<PriceListPage>;

  async function render(api: Partial<PricingApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [PriceListPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PricingApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PriceListPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists price books and offers assign/activate on a draft', async () => {
    await render({
      listPriceBooks: () =>
        of([
          {
            priceBookId: 'book-1',
            name: 'Base',
            currency: 'UZS',
            status: 'DRAFT',
            priority: 0,
            validFrom: new Date().toISOString(),
            validUntil: null,
            version: 1,
          },
        ]),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="price-book-row"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="price-book-assign"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="price-book-activate"]')).not.toBeNull();
  });

  it('activates a draft book with its version as If-Match', async () => {
    const activate = vi.fn().mockReturnValue(
      of({
        priceBookId: 'book-1',
        name: 'Base',
        currency: 'UZS',
        status: 'ACTIVE',
        priority: 0,
        validFrom: '',
        validUntil: null,
        version: 2,
      }),
    );
    await render({
      listPriceBooks: () =>
        of([
          {
            priceBookId: 'book-1',
            name: 'Base',
            currency: 'UZS',
            status: 'DRAFT',
            priority: 0,
            validFrom: new Date().toISOString(),
            validUntil: null,
            version: 1,
          },
        ]),
      activate,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="price-book-activate"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(activate).toHaveBeenCalledWith(SCOPE, 'book-1', 1);
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [PriceListPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PricingApi, useValue: { listPriceBooks: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PriceListPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="price-list-denied"]'),
    ).not.toBeNull();
  });
});
