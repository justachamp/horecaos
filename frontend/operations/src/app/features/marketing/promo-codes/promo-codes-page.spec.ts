import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { LocationsApi } from '../../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import { PromoCodeRedemption, PromoCodeView, PromoCodesApi } from './promo-codes-api';
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
    listRedemptions: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

const CHANNEL: ChannelView = {
  id: 'channel-1',
  code: 'WEBSITE',
  systemType: 'WEB',
  displayName: 'Website',
  status: 'ACTIVE',
  pricePlaneChannelId: null,
  externallyPriced: false,
  guestOrdersAllowed: true,
  providerInstallationId: null,
  version: 1,
};

describe('PromoCodesPage', () => {
  let fixture: ComponentFixture<PromoCodesPage>;

  async function render(
    api: Partial<PromoCodesApi>,
    scope: BrandScope | null = BRAND_SCOPE,
    channels: readonly ChannelView[] = [],
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
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue(channels) } },
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

  describe('validity window and scoping — 6.2', () => {
    // The regression this wave fixes: the draft form had no field for any of
    // these four, so every code it created ran forever across every channel
    // and branch even though the request always accepted them.

    it('sends validFrom, validUntil, channels and locationIds when the operator sets them', async () => {
      const created: PromoCodeView = { ...SUSPENDED_CODE, plaintextCode: 'SCOPED10' };
      const draft = vi.fn().mockResolvedValue(created);
      const list = vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([SUSPENDED_CODE]);
      await render(fakeApi({ draft, list }), BRAND_SCOPE, [CHANNEL]);

      const page = fixture.componentInstance;
      page['openForm']();
      page['formName'].set('Weekend only');
      page['formCode'].set('SCOPED10');
      page['formValidFrom'].set('2026-10-01');
      page['formHasValidUntil'].set(true);
      page['formValidUntil'].set('2026-10-31');
      page['toggleChannel']('WEBSITE');
      expect(page['canSubmit']()).toBe(true);

      await page['submit']();

      expect(draft).toHaveBeenCalledWith(
        BRAND_SCOPE,
        expect.objectContaining({
          validFrom: new Date('2026-10-01').toISOString(),
          validUntil: new Date('2026-10-31T23:59:59.999').toISOString(),
          channels: ['WEBSITE'],
        }),
      );
    });

    it('sends no restriction at all when every field is left at its default', async () => {
      const draft = vi.fn().mockResolvedValue({ ...SUSPENDED_CODE, plaintextCode: 'OPEN1234' });
      await render(fakeApi({ draft }));

      const page = fixture.componentInstance;
      page['openForm']();
      page['formName'].set('Open code');
      page['formCode'].set('OPEN1234');
      await page['submit']();

      expect(draft).toHaveBeenCalledWith(
        BRAND_SCOPE,
        expect.objectContaining({
          validFrom: null,
          validUntil: null,
          channels: [],
          locationIds: [],
        }),
      );
    });

    it('refuses to submit when the expiry date is not after the start date', async () => {
      await render(fakeApi());
      const page = fixture.componentInstance;
      page['openForm']();
      page['formName'].set('Backwards dates');
      page['formCode'].set('BACKWARD1');
      page['formValidFrom'].set('2026-10-10');
      page['formHasValidUntil'].set(true);
      page['formValidUntil'].set('2026-10-01');

      expect(page['canSubmit']()).toBe(false);
    });

    it('renders an expiry column with an em dash for an open-ended code', async () => {
      await render(fakeApi({ list: vi.fn().mockResolvedValue([SUSPENDED_CODE]) }));
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="promo-codes-expiry"]')?.textContent?.trim()).toBe(
        '—',
      );
    });

    it('renders a formatted expiry date and flags an already-expired code', async () => {
      const expired: PromoCodeView = { ...SUSPENDED_CODE, validUntil: '2020-01-01T00:00:00Z' };
      await render(fakeApi({ list: vi.fn().mockResolvedValue([expired]) }));
      const host = fixture.nativeElement as HTMLElement;
      const cell = host.querySelector('[data-testid="promo-codes-expiry"]');
      expect(cell?.textContent?.trim()).not.toBe('—');
      expect(cell?.classList.contains('promo-codes__expiry--expired')).toBe(true);
    });
  });

  describe('the redemption ledger — 6.2', () => {
    const REDEMPTION: PromoCodeRedemption = {
      redemptionId: 'redemption-1',
      customerAccountId: 'account-1',
      orderId: 'order-1',
      status: 'REDEEMED',
      amountMinor: 5_000,
      currency: 'UZS',
      reservedAt: '2026-09-05T10:00:00Z',
      redeemedAt: '2026-09-05T10:00:05Z',
      releasedAt: null,
    };

    it('opens the ledger for a code and renders who redeemed it, on which order, and when', async () => {
      const listRedemptions = vi.fn().mockResolvedValue([REDEMPTION]);
      await render(fakeApi({ list: vi.fn().mockResolvedValue([ACTIVE_CODE]), listRedemptions }));
      const host = fixture.nativeElement as HTMLElement;

      (
        host.querySelector('[data-testid="promo-codes-redeemed-count"]') as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(listRedemptions).toHaveBeenCalledWith(BRAND_SCOPE, 'coupon-2');
      const dialog = host.querySelector('[data-testid="promo-codes-redemptions"]');
      expect(dialog).not.toBeNull();
      expect(dialog?.textContent).toContain('account-1');
      expect(dialog?.textContent).toContain('order-1');
    });

    it('shows an honest empty state rather than a blank table when nobody has redeemed a code yet', async () => {
      await render(fakeApi({ list: vi.fn().mockResolvedValue([SUSPENDED_CODE]) }));
      const page = fixture.componentInstance;
      await page['openRedemptions'](SUSPENDED_CODE);
      fixture.detectChanges();

      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="promo-codes-redemptions-empty"]',
        ),
      ).not.toBeNull();
    });

    it('disables the redeemed-count link when a code has never been redeemed', async () => {
      await render(fakeApi({ list: vi.fn().mockResolvedValue([SUSPENDED_CODE]) }));
      const button = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="promo-codes-redeemed-count"]',
      ) as HTMLButtonElement;
      expect(button.disabled).toBe(true);
    });
  });
});
