import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { PromoCodeView, PromoCodesApi } from './promo-codes-api';
import { PromoCodesPage } from './promo-codes-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const SUSPENDED_CODE: PromoCodeView = {
  couponId: 'coupon-1',
  name: 'Welcome 10%',
  plaintextCode: null,
  codeHint: 'E10A',
  actionType: 'ORDER_PERCENTAGE_DISCOUNT',
  value: 1_000,
  minBasketMinor: 0,
  maximumDiscountMinor: null,
  currency: 'UZS',
  channels: [],
  locationIds: [],
  totalLimit: null,
  perCustomerLimit: 1,
  redeemedCount: 0,
  status: 'SUSPENDED',
  version: 1,
  validFrom: '2026-09-05T00:00:00Z',
  validUntil: null,
};

const ACTIVE_CODE: PromoCodeView = { ...SUSPENDED_CODE, couponId: 'coupon-2', status: 'ACTIVE', redeemedCount: 3 };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function fakeApi(overrides: Partial<PromoCodesApi> = {}): Partial<PromoCodesApi> {
  return {
    list: vi.fn().mockResolvedValue([]),
    draft: vi.fn(),
    activate: vi.fn(),
    retire: vi.fn(),
    ...overrides,
  };
}

describe('PromoCodesPage', () => {
  let fixture: ComponentFixture<PromoCodesPage>;

  async function render(api: Partial<PromoCodesApi>, scope: BrandScope | null = BRAND_SCOPE): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [PromoCodesPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(scope),
            denied: signal(scope === null),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PromoCodesApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PromoCodesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists an authored promo code with its shape, value and redemption count', async () => {
    await render(fakeApi({ list: vi.fn().mockResolvedValue([ACTIVE_CODE]) }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="promo-code-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('10.00%');
    expect(host.textContent).toContain('Welcome 10%');
    expect(host.textContent).toContain('E10A');
    // The plaintext is never shown on a listing row — only the hint.
    expect(host.textContent).not.toContain(SUSPENDED_CODE.plaintextCode ?? 'UNUSED');
  });

  it('shows an empty state when the brand has authored no promo codes', async () => {
    await render(fakeApi());
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="promo-codes-empty"]')).not.toBeNull();
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await render(fakeApi(), null);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="promo-codes-denied"]'),
    ).not.toBeNull();
  });

  it('drafts a promo code, reveals its plaintext once, and reloads the list', async () => {
    const created: PromoCodeView = { ...SUSPENDED_CODE, plaintextCode: 'WELCOME10' };
    const draft = vi.fn().mockResolvedValue(created);
    const list = vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([SUSPENDED_CODE]);
    await render(fakeApi({ draft, list }));

    const page = fixture.componentInstance;
    page['openForm']();
    page['formName'].set('Welcome 10%');
    page['formCode'].set('WELCOME10');
    page['formPercent'].set(10);
    expect(page['canSubmit']()).toBe(true);

    await page['submit']();
    fixture.detectChanges();

    expect(draft).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({
        name: 'Welcome 10%',
        code: 'WELCOME10',
        shape: 'PERCENTAGE_OFF_ORDER',
        value: 1_000,
        perCustomerLimit: 1,
      }),
    );
    expect(list).toHaveBeenCalledTimes(2);
    expect(page['showForm']()).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('WELCOME10');
  });

  it('refuses to submit a code shorter than four characters before it ever reaches the server', async () => {
    await render(fakeApi());
    const page = fixture.componentInstance;
    page['openForm']();
    page['formName'].set('Too short');
    page['formCode'].set('AB1');

    expect(page['canSubmit']()).toBe(false);
  });

  it('refuses a percentage outside 0-100% before it ever reaches the server', async () => {
    await render(fakeApi());
    const page = fixture.componentInstance;
    page['openForm']();
    page['formName'].set('Too much');
    page['formCode'].set('TOOMUCH1');
    page['formPercent'].set(150);

    expect(page['canSubmit']()).toBe(false);
  });

  it('activates a suspended code and reloads the list', async () => {
    const activate = vi.fn().mockResolvedValue(undefined);
    const list = vi
      .fn()
      .mockResolvedValueOnce([SUSPENDED_CODE])
      .mockResolvedValueOnce([{ ...SUSPENDED_CODE, status: 'ACTIVE' }]);
    await render(fakeApi({ activate, list }));

    await fixture.componentInstance['activate'](SUSPENDED_CODE);
    fixture.detectChanges();

    expect(activate).toHaveBeenCalledWith(BRAND_SCOPE, 'coupon-1');
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('retires a live code and reloads the list', async () => {
    const retire = vi.fn().mockResolvedValue(undefined);
    const list = vi
      .fn()
      .mockResolvedValueOnce([ACTIVE_CODE])
      .mockResolvedValueOnce([{ ...ACTIVE_CODE, status: 'ARCHIVED' }]);
    await render(fakeApi({ retire, list }));

    await fixture.componentInstance['retire'](ACTIVE_CODE);
    fixture.detectChanges();

    expect(retire).toHaveBeenCalledWith(BRAND_SCOPE, 'coupon-2');
    expect(list).toHaveBeenCalledTimes(2);
  });
});
