import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { ReservationResponse, ReservationsApi, TableAvailability } from './reservations-api';
import { ReservationsPage } from './reservations-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const TODAY = new Date().toISOString().slice(0, 10);

function localIso(hhmm: string): string {
  return new Date(`${TODAY}T${hhmm}:00`).toISOString();
}

function table(overrides: Partial<TableAvailability> = {}): TableAvailability {
  return {
    tableId: 'table-1',
    code: 'T1',
    seats: 4,
    sectionId: 'section-1',
    booked: false,
    occupied: false,
    ...overrides,
  };
}

function reservation(overrides: Partial<ReservationResponse> = {}): ReservationResponse {
  return {
    reservationId: 'res-1',
    partySize: 4,
    requestedFrom: localIso('18:00'),
    requestedTo: localIso('20:00'),
    turnaroundMinutes: 15,
    status: 'REQUESTED',
    tableIds: ['table-1'],
    version: 1,
    ...overrides,
  };
}

function channel(overrides: Partial<ChannelView> = {}): ChannelView {
  return {
    id: 'channel-1',
    code: 'CALL_CENTRE',
    systemType: 'CALL_CENTRE',
    displayName: 'Call centre',
    status: 'ACTIVE',
    pricePlaneChannelId: null,
    externallyPriced: false,
    guestOrdersAllowed: false,
    providerInstallationId: null,
    version: 1,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ReservationsPage', () => {
  let fixture: ComponentFixture<ReservationsPage>;

  async function render(
    reservationsApi: Partial<ReservationsApi>,
    channelsApi: Partial<SalesChannelsApi> = { list: () => Promise.resolve([channel()]) },
    locationOverrides: { scope?: LocationScope | null; denied?: boolean } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ReservationsPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'scope' in locationOverrides ? (locationOverrides.scope ?? null) : SCOPE,
            ),
            denied: signal(locationOverrides.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReservationsApi, useValue: reservationsApi },
        { provide: SalesChannelsApi, useValue: channelsApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReservationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('shows the denied state when the location grant is missing', async () => {
    await render(
      { availability: vi.fn(), listForDay: vi.fn() },
      { list: () => Promise.resolve([]) },
      { scope: null, denied: true },
    );

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="reservations-denied"]'),
    ).not.toBeNull();
  });

  it('renders the day grid and opens a booking on a booked cell', async () => {
    await render({
      availability: () => Promise.resolve([table()]),
      listForDay: () => Promise.resolve([reservation()]),
    });

    const host = fixture.nativeElement as HTMLElement;
    const bookedCells = host.querySelectorAll('[data-testid="reservations-cell-booked"]');
    expect(bookedCells.length).toBeGreaterThan(0);

    (bookedCells[0] as HTMLElement).click();
    fixture.detectChanges();

    const detail = host.querySelector('[data-testid="reservations-detail"]');
    expect(detail).not.toBeNull();
    expect(detail?.textContent).toContain('4');
  });

  it('confirms a requested booking through the reason dialog', async () => {
    const stateAction = vi
      .fn()
      .mockReturnValue(of(reservation({ status: 'CONFIRMED', version: 2 })));
    await render({
      availability: () => Promise.resolve([table()]),
      listForDay: () => Promise.resolve([reservation()]),
      stateAction,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="reservations-cell-booked"]') as HTMLElement).click();
    fixture.detectChanges();

    (host.querySelector('[data-testid="reservations-action-CONFIRMED"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'Table confirmed available';
    code.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(stateAction).toHaveBeenCalledWith(SCOPE, 'res-1', 'CONFIRMED', 'Table confirmed available', 1);
    expect(host.querySelector('[data-testid="reservations-detail"]')?.textContent).toContain('Confirmed');
  });

  it('creates a new booking from the form', async () => {
    const create = vi.fn().mockReturnValue(of(reservation({ reservationId: 'res-new' })));
    await render({
      availability: () => Promise.resolve([table()]),
      listForDay: () => Promise.resolve([]),
      create,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="reservations-new"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const name = host.querySelector('[data-testid="reservations-form-name"]') as HTMLInputElement;
    name.value = 'Dilnoza';
    name.dispatchEvent(new Event('input'));
    const phone = host.querySelector('[data-testid="reservations-form-phone"]') as HTMLInputElement;
    phone.value = '998901234567';
    phone.dispatchEvent(new Event('input'));

    const tableCheckbox = host.querySelector(
      '.reservations__table-option input[type="checkbox"]',
    ) as HTMLInputElement;
    tableCheckbox.click();
    fixture.detectChanges();

    (host.querySelector('[data-testid="reservations-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(create).toHaveBeenCalledTimes(1);
    const [, body] = create.mock.calls[0];
    expect(body.guestName).toBe('Dilnoza');
    expect(body.guestPhone).toBe('998901234567');
    expect(body.tableIds).toEqual(['table-1']);
    expect(body.sourceChannelId).toBe('channel-1');
    expect(host.querySelector('[data-testid="reservations-form"]')).toBeNull();
  });

  it('amends a booking’s party size and time, never its guest details', async () => {
    const amend = vi
      .fn()
      .mockReturnValue(of(reservation({ partySize: 6, version: 2 })));
    await render({
      availability: () => Promise.resolve([table()]),
      listForDay: () => Promise.resolve([reservation()]),
      amend,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="reservations-cell-booked"]') as HTMLElement).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="reservations-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    // The guest's own fields are not even rendered while editing.
    expect(host.querySelector('[data-testid="reservations-form-name"]')).toBeNull();

    const party = host.querySelector('[data-testid="reservations-form-party"]') as HTMLInputElement;
    party.value = '6';
    party.dispatchEvent(new Event('input'));
    const reason = host.querySelector('[data-testid="reservations-form-reason"]') as HTMLInputElement;
    reason.value = 'Party grew by two';
    reason.dispatchEvent(new Event('input'));

    (host.querySelector('[data-testid="reservations-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(amend).toHaveBeenCalledTimes(1);
    const [scope, id, body, expectedVersion] = amend.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(id).toBe('res-1');
    expect(body.partySize).toBe(6);
    expect(body.reason).toBe('Party grew by two');
    expect(expectedVersion).toBe(1);
    expect(host.querySelector('[data-testid="reservations-form"]')).toBeNull();
  });
});
