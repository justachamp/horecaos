import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderDeliveryResponse } from './order-detail';
import { OrderDetailMoney } from './order-detail-money';
import { MoneyReconciliation } from './order-money';
import { OrderSummaryResponse } from './order-summary';

const SUMMARY = {
  orderId: 'o1',
  publicOrderNumber: '0001',
  status: 'CONFIRMED',
  createdAt: '2026-09-30T09:00:00Z',
  totalMinor: 1_050_000,
  currency: 'UZS',
  feeMinor: 20_000,
  discountMinor: 50_000,
} as OrderSummaryResponse;

const RECONCILED: MoneyReconciliation = {
  lineSumMinor: 1_000_000,
  subtotalMinor: 1_000_000,
  totalMinor: 1_050_000,
  reconciles: true,
};

function delivery(overrides: Partial<OrderDeliveryResponse> = {}): OrderDeliveryResponse {
  return {
    planId: 'p1',
    planVersion: 1,
    planStatus: 'ACTIVE',
    estimatedReadyAt: '2026-09-30T09:30:00Z',
    customerDeliveryFeeMinor: 15_000,
    providerCostMinor: null,
    currency: 'UZS',
    shipment: null,
    exceptions: [],
    ...overrides,
  } as OrderDeliveryResponse;
}

interface Inputs {
  moneyReconciliation: MoneyReconciliation | null;
  delivery: OrderDeliveryResponse | null;
}

function render(overrides: Partial<Inputs> = {}) {
  const inputs: Inputs = { moneyReconciliation: RECONCILED, delivery: null, ...overrides };
  const fixture = TestBed.createComponent(OrderDetailMoney);
  fixture.componentRef.setInput('summary', SUMMARY);
  fixture.componentRef.setInput('taxMinor', 112_500);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const digits = (element: Element | null | undefined) =>
    (element?.textContent ?? '').replace(/\D/g, '');
  return { host, byTestId, digits };
}

describe('OrderDetailMoney', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('draws nothing before the order has loaded', () => {
    const { host } = render({ moneyReconciliation: null });

    expect(host.querySelector('.pane__money')).toBeNull();
    expect(host.querySelector('.pane__money-error')).toBeNull();
  });

  it('lists subtotal, discount, fees, VAT and total in the fixed order', () => {
    const { host } = render();

    const rows = [...host.querySelectorAll('.pane__money-row')];
    expect(rows.map((row) => row.querySelector('dt')?.textContent?.trim())).toEqual([
      'Items subtotal',
      'Discount',
      'Fees',
      'VAT (included)',
      'Total',
    ]);
    expect(rows[4].classList.contains('pane__money-row--total')).toBe(true);
  });

  it('shows the discount as a deduction and every other figure as itself', () => {
    const { host, byTestId, digits } = render();

    const rows = [...host.querySelectorAll('.pane__money-row')];
    expect(digits(rows[0])).toBe('1000000');
    expect(byTestId('order-detail-money-discount')?.textContent).toMatch(/[-−–]/);
    expect(digits(byTestId('order-detail-money-discount'))).toBe('50000');
    expect(digits(byTestId('order-detail-money-fee'))).toBe('20000');
    expect(digits(rows[3])).toBe('112500');
    expect(digits(rows[4])).toBe('1050000');
  });

  it('refuses to show a total that does not add up, and shows both figures', () => {
    const { host, byTestId } = render({
      moneyReconciliation: { ...RECONCILED, lineSumMinor: 900_000, reconciles: false },
    });

    expect(host.querySelector('.pane__money')).toBeNull();
    const error = byTestId('order-detail-money-error');
    expect(error?.getAttribute('role')).toBe('alert');
    expect(error?.textContent).toContain('The money does not add up');
    expect(error?.textContent?.replace(/\D/g, '')).toContain('900000');
  });

  it('shows the customer’s delivery fee for an own-fleet delivery and no provider rows', () => {
    const { byTestId, digits } = render({ delivery: delivery() });

    const block = byTestId('order-detail-delivery-money');
    expect(block?.querySelectorAll('.pane__money-row')).toHaveLength(1);
    expect(digits(block)).toBe('15000');
  });

  it('shows what a partner billed and the margin, and says when the cost is not tracked', () => {
    const partner = { shipmentId: 's1', status: 'ASSIGNED', sourceType: 'PARTNER', version: 1 };
    const tracked = render({
      delivery: delivery({ shipment: partner, providerCostMinor: 18_000 }),
    });
    const trackedRows = [
      ...(tracked.byTestId('order-detail-delivery-money')?.querySelectorAll('.pane__money-row') ??
        []),
    ];
    expect(trackedRows).toHaveLength(3);
    expect(tracked.digits(trackedRows[1])).toBe('18000');
    expect(tracked.digits(trackedRows[2])).toBe('3000');
    expect(trackedRows[2].textContent).toMatch(/[-−–]/);

    const untracked = render({
      delivery: delivery({ shipment: partner, providerCostMinor: null }),
    });
    const untrackedRows = untracked
      .byTestId('order-detail-delivery-money')
      ?.querySelectorAll('.pane__money-row');
    expect(untrackedRows).toHaveLength(2);
    expect(untrackedRows?.[1].textContent).toContain('not tracked');
  });

  it('lists each open delivery exception as an alert, with its reason worded and its detail', () => {
    const { byTestId } = render({
      delivery: delivery({
        exceptions: [
          {
            exceptionId: 'x1',
            reasonCode: 'NO_PROVIDER',
            severity: 'HIGH',
            status: 'OPEN',
            detail: 'Nobody answered',
            raisedAt: '2026-09-30T09:10:00Z',
          },
          {
            exceptionId: 'x2',
            reasonCode: 'SOMETHING_NEW',
            severity: 'LOW',
            status: 'OPEN',
            detail: null,
            raisedAt: '2026-09-30T09:11:00Z',
          },
        ],
      }),
    });

    const items = [...(byTestId('order-detail-delivery-exceptions')?.querySelectorAll('li') ?? [])];
    expect(items).toHaveLength(2);
    expect(items.every((item) => item.getAttribute('role') === 'alert')).toBe(true);
    expect(items[0].textContent).toContain('Nobody answered');
    expect(items[1].textContent?.trim()).toBe('SOMETHING_NEW');
  });

  it('draws no exception band when there is nothing to attend to', () => {
    const { byTestId } = render({ delivery: delivery() });

    expect(byTestId('order-detail-delivery-exceptions')).toBeNull();
  });
});
