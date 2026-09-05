import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { CashHandoverView, CourierFinanceApi } from '../courier-finance-api';
import { CashReconciliationPage } from './cash-reconciliation-page';

function handover(overrides: Partial<CashHandoverView> = {}): CashHandoverView {
  return {
    handoverId: 'handover-1',
    shiftId: 'shift-1',
    courierId: 'courier-1',
    locationId: 'location-1',
    status: 'DECLARED',
    currency: 'UZS',
    expectedMinor: 150_000,
    declaredMinor: 148_000,
    confirmedMinor: null,
    varianceMinor: null,
    declaredAt: '2026-09-04T20:00:00Z',
    confirmedBy: null,
    confirmedAt: null,
    reasonCode: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('tenant-1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CashReconciliationPage', () => {
  let fixture: ComponentFixture<CashReconciliationPage>;
  let api: { cashHandovers: ReturnType<typeof vi.fn>; confirmCash: ReturnType<typeof vi.fn> };

  async function render(
    listResult: readonly CashHandoverView[] | (() => Promise<readonly CashHandoverView[]>),
    tenant: FakeCurrentTenant = new FakeCurrentTenant(),
  ): Promise<void> {
    api = {
      cashHandovers: vi.fn(typeof listResult === 'function' ? listResult : async () => listResult),
      confirmCash: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [CashReconciliationPage],
      providers: [
        { provide: CourierFinanceApi, useValue: api },
        { provide: CurrentTenant, useValue: tenant },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CashReconciliationPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('lists a handover with its expected and declared amounts', async () => {
    await render([handover()]);
    const text = host().textContent ?? '';

    expect(api.cashHandovers).toHaveBeenCalledWith('tenant-1');
    expect(text).toContain('Declared');
    // formatMoney renders the whole-som figure without inventing decimals.
    expect(text).toContain('150');
    expect(text).toContain('148');
  });

  it('shows the honest empty state, not a zero-row table, when nothing is outstanding', async () => {
    await render([]);
    expect(host().textContent).toContain('No cash handovers in this range.');
    expect(host().querySelectorAll('tbody tr td.empty')).toHaveLength(1);
  });

  it('shows the denied state when the operator covers no tenant at all — the persona-unreachable failure mode', async () => {
    const denied = new FakeCurrentTenant();
    denied.tenantId.set(null);
    denied.denied.set(true);
    await render([], denied);

    expect(host().textContent).toContain('No location in scope');
    expect(api.cashHandovers).not.toHaveBeenCalled();
  });

  it('surfaces a 403 mid-load as denied rather than the generic error band — COURIER_CASH_READ vs COURIER_CASH_CONFIRM is a real split', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });
    expect(host().textContent).toContain('No location in scope');
  });

  it('surfaces a network failure as an honest retry band, never a raw error code', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, 'corr-9');
    });
    const text = host().textContent ?? '';
    expect(text).not.toContain('NETWORK_UNREACHABLE');
    expect(host().querySelector('.error-band')).not.toBeNull();

    api.cashHandovers.mockResolvedValueOnce([handover()]);
    (host().querySelector('.error-band button') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host().querySelector('.error-band')).toBeNull();
  });

  it('never offers a confirm action on a row that is not DECLARED or PENDING — CONFIRMED is not re-confirmable', async () => {
    await render([handover({ status: 'CONFIRMED', confirmedMinor: 148_000 })]);
    expect(host().querySelector('td.actions button')).toBeNull();
  });

  describe('confirming a handover — this is money changing hands', () => {
    beforeEach(async () => {
      await render([handover()]);
      (host().querySelector('td.actions button') as HTMLButtonElement).click();
      fixture.detectChanges();
    });

    function amountInput(): HTMLInputElement {
      return host().querySelector('.panel input[type="number"]') as HTMLInputElement;
    }

    function submitButton(): HTMLButtonElement {
      return host().querySelector('.panel .panel__actions .primary') as HTMLButtonElement;
    }

    it('pre-fills the confirm amount from the courier’s own declared figure, not a blank field', () => {
      expect(amountInput().value).toBe('148000');
    });

    it('confirms with exactly the typed integer amount, defaulting the reason when left blank', async () => {
      amountInput().value = '148000';
      amountInput().dispatchEvent(new Event('input'));
      fixture.detectChanges();

      submitButton().click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.confirmCash).toHaveBeenCalledWith('tenant-1', 'handover-1', {
        confirmedMinor: 148_000,
        reason: 'Confirmed at reconciliation',
      });
      expect(host().querySelector('.panel')).toBeNull();
    });

    it('never confirms a non-integer amount — cash cannot be confirmed in fractional som', async () => {
      amountInput().value = '148000.5';
      amountInput().dispatchEvent(new Event('input'));
      fixture.detectChanges();

      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      await flushMicrotasks();
      expect(api.confirmCash).not.toHaveBeenCalled();
    });

    it('never confirms a negative amount', async () => {
      amountInput().value = '-1';
      amountInput().dispatchEvent(new Event('input'));
      fixture.detectChanges();

      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      await flushMicrotasks();
      expect(api.confirmCash).not.toHaveBeenCalled();
    });

    it('surfaces a confirm failure honestly and keeps the panel open, never silently dropping the attempt', async () => {
      api.confirmCash.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null),
      );
      submitButton().click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host().querySelector('.panel')).not.toBeNull();
      const message = host().querySelector('.panel .error-text')?.textContent ?? '';
      expect(message.length).toBeGreaterThan(0);
      expect(message).not.toContain('STALE_VERSION');
    });
  });
});
