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

const ACTIVE_CODE: PromoCodeView = {
  ...SUSPENDED_CODE,
  couponId: 'coupon-2',
  status: 'ACTIVE',
  redeemedCount: 3,
};

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

  async function render(
    api: Partial<PromoCodesApi>,
    scope: BrandScope | null = BRAND_SCOPE,
  ): Promise<void> {
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
    page['formBasisPoints'].set(1_000);
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

  it('switches to a fixed amount through q-money-or-percent’s own toggle and submits it', async () => {
    const created: PromoCodeView = { ...SUSPENDED_CODE, plaintextCode: 'FIXED10' };
    const draft = vi.fn().mockResolvedValue(created);
    const list = vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([SUSPENDED_CODE]);
    await render(fakeApi({ draft, list }));

    const host = fixture.nativeElement as HTMLElement;
    const page = fixture.componentInstance;
    page['openForm']();
    page['formName'].set('Fixed off');
    page['formCode'].set('FIXED10');
    fixture.detectChanges();

    // The widget's own AMOUNT/PERCENT toggle drives `formShape` itself — no
    // separate interaction with the shape `<select>` above it is needed.
    (
      host.querySelector('[data-testid="q-money-or-percent-amount-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(page['formShape']()).toBe('FIXED_AMOUNT_OFF_ORDER');

    const amountField = host.querySelector<HTMLInputElement>(
      '[data-testid="q-money-input-field"]',
    )!;
    amountField.value = '25 000';
    amountField.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    await page['submit']();
    fixture.detectChanges();

    expect(draft).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ shape: 'FIXED_AMOUNT_OFF_ORDER', value: 25_000 }),
    );
  });

  it('hides the money-or-percent value editor entirely for free delivery', async () => {
    // Free delivery carries no amount and no percentage at all — the widget
    // must not render for a shape it cannot represent.
    await render(fakeApi());
    const host = fixture.nativeElement as HTMLElement;
    const page = fixture.componentInstance;
    page['openForm']();
    page['formShape'].set('FREE_DELIVERY');
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="promo-codes-value"]')).toBeNull();
  });

  it('refuses to submit a code shorter than four characters before it ever reaches the server', async () => {
    await render(fakeApi());
    const page = fixture.componentInstance;
    page['openForm']();
    page['formName'].set('Too short');
    page['formCode'].set('AB1');

    expect(page['canSubmit']()).toBe(false);
  });

  it('reflects a cleared percent field rather than resubmitting the pre-edit amount', async () => {
    const draft = vi.fn();
    await render(fakeApi({ draft }));
    const host = fixture.nativeElement as HTMLElement;
    const page = fixture.componentInstance;
    page['openForm']();
    page['formName'].set('Clear test');
    page['formCode'].set('CLEARTST');
    fixture.detectChanges();

    const percentField = host.querySelector<HTMLInputElement>(
      '[data-testid="q-percent-input-field"]',
    )!;
    percentField.value = '';
    percentField.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // Before PercentInput.onInput emitted on empty input, it returned early
    // and left `formBasisPoints` at its stale default (1000 — the form's own
    // opening default) — `canSubmit` would wrongly stay true, and hitting
    // submit right after clearing would silently draft the pre-edit 10%
    // discount as if the operator had never touched the field.
    expect(page['formBasisPoints']()).toBe(0);
    expect(page['canSubmit']()).toBe(false);

    await page['submit']();

    expect(draft).not.toHaveBeenCalled();
  });

  it('refuses a percentage outside 0-100% before it ever reaches the server', async () => {
    await render(fakeApi());
    const page = fixture.componentInstance;
    page['openForm']();
    page['formName'].set('Too much');
    page['formCode'].set('TOOMUCH1');
    page['formBasisPoints'].set(15_000);

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
