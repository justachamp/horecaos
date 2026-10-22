import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { Page } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { DeliveriesApi, DeliveryRow } from './deliveries-api';
import { DeliveriesPage } from './deliveries-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const BRANCHES: readonly LocationOption[] = [
  { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
  { id: 'l2', displayName: 'Sergeli', status: 'ACTIVE' },
];

const ROSTER: readonly RosterEntryResponse[] = [
  {
    courierId: 'c1',
    displayReference: 'K-014',
    status: 'ACTIVE',
    courierTypeId: 'type-1',
    courierTypeName: 'Scooter',
    vehicleClass: 'SCOOTER',
    activeAssignments: 0,
    concurrencyCeiling: 2,
  },
];

function row(overrides: Partial<DeliveryRow> = {}): DeliveryRow {
  return {
    planId: 'p1',
    orderId: 'o1',
    orderReference: 'DL-0001',
    brandId: 'b1',
    locationId: 'l1',
    branchName: 'Chilonzor',
    planStatus: 'COMPLETED',
    shipmentId: 's1',
    shipmentStatus: 'DELIVERED',
    sourceType: 'INTERNAL',
    courierId: 'c1',
    providerType: null,
    destinationLabel: 'Yunusobod',
    distanceMeters: 2100,
    confirmedAt: '2026-10-20T08:00:00Z',
    promisedDeliveryStart: '2026-10-20T08:30:00Z',
    promisedDeliveryEnd: '2026-10-20T08:50:00Z',
    pickedUpAt: '2026-10-20T08:20:00Z',
    deliveredAt: '2026-10-20T08:45:00Z',
    orderTotalMinor: 20_000,
    currency: 'UZS',
    prepaid: false,
    cashToCollectMinor: 15_000,
    cost: { basis: 'ACCRUED', path: 'INTERNAL', amountMinor: 7_500, currency: 'UZS' },
    ...overrides,
  };
}

function page(items: readonly DeliveryRow[], nextCursor: string | null = null): Page<DeliveryRow> {
  return { items, nextCursor };
}

describe('DeliveriesPage (ADR 0164 slice two)', () => {
  let fixture: ComponentFixture<DeliveriesPage>;

  async function render(
    api: Partial<DeliveriesApi>,
    roster: Partial<CouriersApi> = { roster: () => Promise.resolve(ROSTER) },
  ): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [DeliveriesPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
            options: () => BRANCHES,
          },
        },
        { provide: DeliveriesApi, useValue: api },
        { provide: CouriersApi, useValue: roster },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveriesPage);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('.deliveries__skeleton')).toBeNull();
    });
    return host;
  }

  function choose(host: HTMLElement, testId: string, value: string): void {
    const select = host.querySelector<HTMLSelectElement | HTMLInputElement>(
      `[data-testid="${testId}"]`,
    )!;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('lists a delivery with its carrier by reference, the cash and the cost at its basis', async () => {
    const list = vi.fn().mockResolvedValue(page([row()]));
    const host = await render({ list });

    expect(list.mock.calls[0][0]).toBe('t1');
    expect(list.mock.calls[0][1]).toEqual({});
    const cells = host.querySelector('[data-testid="delivery-row"]')!;
    expect(cells.textContent).toContain('DL-0001');
    expect(cells.textContent).toContain('Chilonzor');
    expect(cells.textContent).toContain('Yunusobod');
    expect(host.querySelector('[data-testid="delivery-state"]')?.textContent).toContain(
      'Delivered',
    );
    expect(host.querySelector('[data-testid="delivery-courier"]')?.textContent?.trim()).toBe(
      'K-014',
    );
    expect(host.querySelector('[data-testid="delivery-cash"]')?.textContent).toContain('15');
    expect(host.querySelector('[data-testid="delivery-cost"]')?.textContent).toContain('accrued');
  });

  it('opens the order’s own detail pane from a row, and shows no recipient anywhere', async () => {
    const host = await render({ list: vi.fn().mockResolvedValue(page([row()])) });

    const open = host.querySelector<HTMLAnchorElement>('[data-testid="delivery-open"]')!;
    expect(open.getAttribute('href')).toBe('/orders/o1');
    expect(host.textContent).not.toMatch(/\+998|@/);
  });

  it('shows a partner by its type and an unassigned plan as nobody yet', async () => {
    const host = await render({
      list: vi.fn().mockResolvedValue(
        page([
          row({ courierId: null, sourceType: 'PARTNER', providerType: 'YANDEX_GO' }),
          row({
            planId: 'p2',
            orderId: 'o2',
            courierId: null,
            sourceType: null,
            shipmentId: null,
            cashToCollectMinor: null,
            cost: null,
            planStatus: 'SOURCING',
          }),
        ]),
      ),
    });

    const couriers = [...host.querySelectorAll('[data-testid="delivery-courier"]')].map((cell) =>
      cell.textContent?.trim(),
    );
    expect(couriers).toEqual(['YANDEX_GO', '—']);
  });

  it('narrows by branch, state, courier and date, restarting from the first page each time', async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce(page([row()], 'cursor-1'))
      .mockResolvedValue(page([]));
    const host = await render({ list });

    choose(host, 'deliveries-filter-branch', 'l2');
    choose(host, 'deliveries-filter-state', 'COMPLETED');
    choose(host, 'deliveries-filter-courier', 'c1');
    choose(host, 'deliveries-filter-from', '2026-10-01');
    choose(host, 'deliveries-filter-to', '2026-10-20');
    await vi.waitFor(() => expect(list.mock.calls.length).toBeGreaterThanOrEqual(6));

    const [, filters, state] = list.mock.calls.at(-1)!;
    expect(filters).toEqual({
      brandId: 'b1',
      locationId: 'l2',
      states: ['COMPLETED'],
      courierId: 'c1',
      from: '2026-09-30T19:00:00.000Z',
      to: '2026-10-20T19:00:00.000Z',
    });
    expect(state.cursor).toBeNull();
  });

  it('pages by cursor and appends, never replacing what is on screen', async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce(page([row()], 'cursor-1'))
      .mockResolvedValueOnce(
        page([row({ planId: 'p2', orderId: 'o2', orderReference: 'DL-0002' })]),
      );
    const host = await render({ list });

    host.querySelector<HTMLButtonElement>('[data-testid="deliveries-more"]')!.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelectorAll('[data-testid="delivery-row"]')).toHaveLength(2);
    });

    expect(list.mock.calls[1][2].cursor).toBe('cursor-1');
    expect(host.querySelector('[data-testid="deliveries-more"]')).toBeNull();
  });

  it('falls back to the operator’s own branch when the whole company is refused, and says so', async () => {
    const refused = new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null);
    const list = vi
      .fn()
      .mockRejectedValueOnce(refused)
      .mockResolvedValueOnce(page([row()]));
    const host = await render({ list });

    expect(list.mock.calls[1][1]).toEqual({ brandId: 'b1', locationId: 'l1' });
    expect(host.querySelector('[data-testid="deliveries-own-branch"]')).not.toBeNull();
    expect(host.querySelectorAll('[data-testid="delivery-row"]')).toHaveLength(1);
  });

  it('shows the denied state when even the operator’s own branch is refused', async () => {
    const refused = new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null);
    const host = await render({ list: vi.fn().mockRejectedValue(refused) });

    expect(host.querySelector('[data-testid="deliveries-denied"]')).not.toBeNull();
  });

  it('says so when there is nothing to show', async () => {
    const host = await render({ list: vi.fn().mockResolvedValue(page([])) });

    expect(host.querySelector('[data-testid="deliveries-empty"]')).not.toBeNull();
  });
});
