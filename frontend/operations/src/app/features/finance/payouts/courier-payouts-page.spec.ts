import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { CourierFinanceApi, CourierLedgerView, SettlementPeriodView } from '../courier-finance-api';
import { CourierPayoutsPage } from './courier-payouts-page';

function period(overrides: Partial<SettlementPeriodView> = {}): SettlementPeriodView {
  return {
    periodId: 'period-1',
    courierId: 'courier-1',
    periodStart: '2026-08-25',
    periodEnd: '2026-08-31',
    status: 'OPEN',
    currency: 'UZS',
    grossEarningsMinor: 500_000,
    adjustmentsMinor: 0,
    cashHeldMinor: 0,
    amountPayableMinor: 500_000,
    deliveredCount: 42,
    onTimeCount: 40,
    complianceFlag: false,
    statementHash: null,
    closedAt: null,
    settledAt: null,
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

describe('CourierPayoutsPage', () => {
  let fixture: ComponentFixture<CourierPayoutsPage>;
  let api: {
    settlementPeriods: ReturnType<typeof vi.fn>;
    closeSettlementPeriod: ReturnType<typeof vi.fn>;
    authorisePayout: ReturnType<typeof vi.fn>;
    courierLedger: ReturnType<typeof vi.fn>;
  };

  async function render(
    listResult: readonly SettlementPeriodView[] | (() => Promise<readonly SettlementPeriodView[]>),
    tenant: FakeCurrentTenant = new FakeCurrentTenant(),
  ): Promise<void> {
    api = {
      settlementPeriods: vi.fn(
        typeof listResult === 'function' ? listResult : async () => listResult,
      ),
      closeSettlementPeriod: vi.fn().mockResolvedValue(undefined),
      authorisePayout: vi.fn().mockResolvedValue(undefined),
      courierLedger: vi.fn(),
    };
    // Some tests call render() more than once (a fresh scope, a fresh
    // status) to compare states side by side, so each call gets its own
    // testing module rather than erroring on a second configure.
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [CourierPayoutsPage],
      providers: [
        { provide: CourierFinanceApi, useValue: api },
        { provide: CurrentTenant, useValue: tenant },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierPayoutsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('lists a settlement period with the stored payable figure, never a recomputed one', async () => {
    await render([period()]);
    const text = host().textContent ?? '';

    expect(api.settlementPeriods).toHaveBeenCalledWith('tenant-1');
    expect(text).toContain('42'); // delivered
    expect(text).toContain('40'); // on time
    expect(text).toContain('500'); // payable, from formatMoney
  });

  it('shows the honest empty state, not a zero-row table, when no period exists', async () => {
    await render([]);
    expect(host().textContent).toContain('No settlement periods yet.');
    expect(host().querySelectorAll('tbody tr td.empty')).toHaveLength(1);
  });

  it('flags a period needing a second signature rather than hiding the requirement', async () => {
    await render([period({ complianceFlag: true })]);
    expect(host().querySelector('.compliance-flag')).not.toBeNull();
  });

  it('shows the denied state when the operator covers no tenant at all — the persona-unreachable failure mode', async () => {
    const denied = new FakeCurrentTenant();
    denied.tenantId.set(null);
    denied.denied.set(true);
    await render([], denied);

    expect(host().textContent).toContain('No location in scope');
    expect(api.settlementPeriods).not.toHaveBeenCalled();
  });

  it('surfaces a 403 mid-load as denied rather than the generic error band', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });
    expect(host().textContent).toContain('No location in scope');
  });

  it('surfaces a load failure as an honest retry band, never a raw error code', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, 'corr-1');
    });
    expect(host().textContent).not.toContain('NETWORK_UNREACHABLE');
    expect(host().querySelector('.error-band')).not.toBeNull();
  });

  describe('offering the close/payout actions only on the right status', () => {
    it('offers "Close period" for OPEN and CLOSING, never for CLOSED or SETTLED', async () => {
      await render([period({ status: 'OPEN' })]);
      expect(host().textContent).toContain('Close period');

      await render([period({ status: 'CLOSED', periodId: 'p2' })]);
      expect(host().textContent).not.toContain('Close period');

      await render([period({ status: 'SETTLED', periodId: 'p3' })]);
      expect(host().textContent).not.toContain('Close period');
      expect(host().textContent).not.toContain('Authorise payout');
    });

    it('offers "Authorise payout" only once CLOSED, never while still OPEN', async () => {
      await render([period({ status: 'CLOSED' })]);
      expect(host().textContent).toContain('Authorise payout');

      await render([period({ status: 'OPEN', periodId: 'p2' })]);
      expect(host().textContent).not.toContain('Authorise payout');
    });
  });

  describe('closing a period — an authorization-gated action', () => {
    beforeEach(async () => {
      await render([period({ status: 'OPEN' })]);
      (host().querySelector('td.actions button') as HTMLButtonElement).click();
      fixture.detectChanges();
    });

    function reasonInput(): HTMLInputElement {
      return host().querySelector('.panel input[type="text"]') as HTMLInputElement;
    }

    function confirmButton(): HTMLButtonElement {
      return host().querySelector('.panel .panel__actions .primary') as HTMLButtonElement;
    }

    it('never closes the period while the reason is blank', async () => {
      expect(confirmButton().disabled).toBe(true);
      confirmButton().click();
      await flushMicrotasks();
      expect(api.closeSettlementPeriod).not.toHaveBeenCalled();
    });

    it('closes with exactly the tenant, period and typed reason once complete', async () => {
      reasonInput().value = 'End of week close';
      reasonInput().dispatchEvent(new Event('input'));
      fixture.detectChanges();

      confirmButton().click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.closeSettlementPeriod).toHaveBeenCalledWith(
        'tenant-1',
        'period-1',
        'End of week close',
      );
      expect(api.authorisePayout).not.toHaveBeenCalled();
      expect(host().querySelector('.panel')).toBeNull();
    });

    it('surfaces a close failure honestly and keeps the panel open, never silently dropping the attempt', async () => {
      reasonInput().value = 'End of week close';
      reasonInput().dispatchEvent(new Event('input'));
      fixture.detectChanges();

      api.closeSettlementPeriod.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
      );
      confirmButton().click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host().querySelector('.panel')).not.toBeNull();
      const message = host().querySelector('.panel .error-text')?.textContent ?? '';
      expect(message.length).toBeGreaterThan(0);
      expect(message).not.toContain('INSUFFICIENT_CAPABILITY');
    });
  });

  describe('authorising a payout — money actually moving', () => {
    beforeEach(async () => {
      await render([period({ status: 'CLOSED' })]);
      (host().querySelector('td.actions button') as HTMLButtonElement).click();
      fixture.detectChanges();
    });

    function methodSelect(): HTMLSelectElement {
      return host().querySelector('.panel select') as HTMLSelectElement;
    }

    function reasonInput(): HTMLInputElement {
      return host().querySelector('.panel input[type="text"]') as HTMLInputElement;
    }

    function confirmButton(): HTMLButtonElement {
      return host().querySelector('.panel .panel__actions .primary') as HTMLButtonElement;
    }

    it('defaults to CASH_AT_BRANCH and never authorises while the reason is blank', async () => {
      expect(methodSelect().value).toBe('CASH_AT_BRANCH');
      expect(confirmButton().disabled).toBe(true);

      confirmButton().click();
      await flushMicrotasks();
      expect(api.authorisePayout).not.toHaveBeenCalled();
    });

    it('authorises with exactly the chosen method and typed reason, never the close endpoint', async () => {
      methodSelect().value = 'BANK_TRANSFER';
      methodSelect().dispatchEvent(new Event('change'));
      reasonInput().value = 'Weekly settlement approved by finance';
      reasonInput().dispatchEvent(new Event('input'));
      fixture.detectChanges();

      confirmButton().click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.authorisePayout).toHaveBeenCalledWith(
        'tenant-1',
        'period-1',
        'BANK_TRANSFER',
        'Weekly settlement approved by finance',
      );
      expect(api.closeSettlementPeriod).not.toHaveBeenCalled();
    });

    it('surfaces an authorise failure honestly rather than pretending the payout went through', async () => {
      reasonInput().value = 'Weekly settlement';
      reasonInput().dispatchEvent(new Event('input'));
      fixture.detectChanges();

      api.authorisePayout.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.RESOURCE_CONFLICT, 409, null, null),
      );
      confirmButton().click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host().querySelector('.panel')).not.toBeNull();
      const message = host().querySelector('.panel .error-text')?.textContent ?? '';
      expect(message.length).toBeGreaterThan(0);
      expect(message).not.toContain('RESOURCE_CONFLICT');
    });
  });

  describe('the courier ledger lookup', () => {
    it('looks up exactly the typed courier id under the operator’s own tenant', async () => {
      await render([]);
      const ledger: CourierLedgerView = {
        balanceMinor: 75_000,
        entries: [
          {
            entryId: 'entry-1',
            entryType: 'EARNING',
            amountMinor: 75_000,
            currency: 'UZS',
            reasonCode: null,
            occurredAt: '2026-09-01T10:00:00Z',
          },
        ],
      };
      api.courierLedger.mockResolvedValue(ledger);

      const input = host().querySelector('.lookup-row input') as HTMLInputElement;
      input.value = 'courier-42';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      (host().querySelector('.lookup-row button') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.courierLedger).toHaveBeenCalledWith('tenant-1', 'courier-42');
      expect(host().textContent).toContain('75');
    });

    it('surfaces a ledger lookup failure honestly rather than a blank panel', async () => {
      await render([]);
      api.courierLedger.mockRejectedValue(
        new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null),
      );

      const input = host().querySelector('.lookup-row input') as HTMLInputElement;
      input.value = 'courier-ghost';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      (host().querySelector('.lookup-row button') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      const message = host().querySelector('.ledger-lookup .error-text')?.textContent ?? '';
      expect(message.length).toBeGreaterThan(0);
      expect(message).not.toContain('RESOURCE_NOT_FOUND');
    });
  });
});
