import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderRowsTable, OrderTableColumn } from './order-rows-table';
import { OrderRowResponse } from './reporting-api';

function row(overrides: Partial<OrderRowResponse> = {}): OrderRowResponse {
  return {
    orderId: '018f6f4e-0000-7000-8000-000000000001',
    businessDate: '2026-08-21',
    locationId: 'loc-1',
    legalEntityId: null,
    channelCode: 'TELEGRAM',
    fulfilmentType: 'DELIVERY',
    terminalStatus: 'COMPLETED',
    grossRevenueSom: 120_000,
    discountSom: 0,
    deliveryFeeSom: 10_000,
    taxSom: 0,
    netRevenueSom: 120_000,
    itemCount: 3,
    occurredAt: '2026-08-21T08:00:00Z',
    closedAt: '2026-08-21T08:40:00Z',
    secondsToConfirm: 60,
    secondsToReady: 900,
    secondsTotal: 2_400,
    secondsLate: null,
    cancellationReasonCode: null,
    secondsToAccept: 660,
    secondsPreparing: 540,
    publicOrderNumber: '0042',
    isPreorder: false,
    ...overrides,
  };
}

function render(
  rows: readonly OrderRowResponse[],
  columns: readonly OrderTableColumn[],
  locationNames?: ReadonlyMap<string, string>,
) {
  const fixture = TestBed.createComponent(OrderRowsTable);
  fixture.componentRef.setInput('rows', rows);
  fixture.componentRef.setInput('columns', columns);
  if (locationNames) {
    fixture.componentRef.setInput('locationNames', locationNames);
  }
  fixture.detectChanges();
  return fixture;
}

describe('OrderRowsTable', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders only the requested columns', () => {
    const fixture = render([row()], ['orderId', 'total']);
    const headerText =
      (fixture.nativeElement as HTMLElement).querySelector('thead')!.textContent ?? '';
    expect(headerText).toContain('Order');
    expect(headerText).toContain('Total time');
    expect(headerText).not.toContain('Minutes late');
  });

  it('shows the empty message when there are no rows', () => {
    const fixture = render([], ['orderId']);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No data for the selected period');
  });

  it('renders a red severity rail past the 60-minute cut', () => {
    const fixture = render([row({ secondsTotal: 3_700 })], ['orderId', 'total']);
    const rail = (fixture.nativeElement as HTMLElement).querySelector('.order-table__rail--red');
    expect(rail).not.toBeNull();
  });

  it('renders an amber rail between 30 and 60 minutes, and none below 30', () => {
    const amber = render([row({ secondsTotal: 1_900 })], ['orderId', 'total']);
    expect(
      (amber.nativeElement as HTMLElement).querySelector('.order-table__rail--amber'),
    ).not.toBeNull();

    const normal = render([row({ secondsTotal: 600 })], ['orderId', 'total']);
    expect(
      (normal.nativeElement as HTMLElement).querySelector('.order-table__rail--amber'),
    ).toBeNull();
    expect(
      (normal.nativeElement as HTMLElement).querySelector('.order-table__rail--red'),
    ).toBeNull();
  });

  it('renders — for a duration that is null rather than a bare zero', () => {
    const fixture = render([row({ secondsTotal: null })], ['orderId', 'total']);
    const cells = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody td'));
    expect(cells.some((cell) => cell.textContent?.trim() === '—')).toBe(true);
  });

  it('signs a positive lateness with a plus', () => {
    const fixture = render([row({ secondsLate: 840 })], ['orderId', 'late']);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('+14 min');
  });

  // ----------------------------------------------------------- wave P27 (7.2/7.2a)

  it('prints the public order number instead of a UUID fragment when the fact carries one', () => {
    const fixture = render([row({ publicOrderNumber: '0137' })], ['orderId']);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('0137');
    expect(text).not.toContain('018f6f4e');
  });

  it('falls back to the eight-character UUID fragment when a row has no public order number', () => {
    const fixture = render([row({ publicOrderNumber: null })], ['orderId']);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('018f6f4e');
  });

  it('splits branch acceptance (accept) from actual cooking (cooking), never the conflated seconds_to_ready', () => {
    const fixture = render(
      [row({ secondsToAccept: 11 * 60, secondsPreparing: 9 * 60, secondsToReady: 20 * 60 })],
      ['orderId', 'accept', 'cooking'],
    );
    const headerText =
      (fixture.nativeElement as HTMLElement).querySelector('thead')!.textContent ?? '';
    expect(headerText).toContain('Branch accepted in');
    expect(headerText).toContain('Cooked in');

    const bodyText =
      (fixture.nativeElement as HTMLElement).querySelector('tbody')!.textContent ?? '';
    expect(bodyText).toContain('11'); // 11 minutes waiting for the branch
    expect(bodyText).toContain('9'); // 9 minutes actually cooking
  });

  it('resolves the branch column from the caller-supplied locationId -> name map', () => {
    const fixture = render(
      [row({ locationId: 'loc-42' })],
      ['orderId', 'branch'],
      new Map([['loc-42', 'Chorsu']]),
    );
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Chorsu');
  });

  it('falls back to the raw locationId when the caller has no name for it', () => {
    const fixture = render([row({ locationId: 'loc-unknown' })], ['orderId', 'branch']);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('loc-unknown');
  });

  it('marks a pre-order row and renders a dash for every other one', () => {
    const withPreorder = render([row({ isPreorder: true })], ['orderId', 'preorder']);
    expect(
      withPreorder.nativeElement.querySelector('[data-testid="order-row-preorder"]'),
    ).not.toBeNull();

    const without = render([row({ isPreorder: false })], ['orderId', 'preorder']);
    expect(without.nativeElement.querySelector('[data-testid="order-row-preorder"]')).toBeNull();
  });
});
