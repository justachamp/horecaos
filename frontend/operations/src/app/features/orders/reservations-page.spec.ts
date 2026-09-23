import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { zonedTimeToInstant } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import {
  LocationView,
  LocationsApi,
  ModeBindingView,
  ServiceSummaryResponse,
} from '../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { ReservationResponse, ReservationsApi, TableAvailability } from './reservations-api';
import { ReservationsPage } from './reservations-page';
import { SessionView, TableSessionsApi } from './table-sessions-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const TODAY = new Date().toISOString().slice(0, 10);
/** The fixture zone every test's fixtures are built in — matches the component's own fallback, so a test that never overrides `LocationsApi.profile` still reads a stable, known zone rather than the browser's. */
const ZONE = 'Asia/Tashkent';

/** `HH:mm` in {@link ZONE} on {@link TODAY}, as an RFC 3339 instant — the fixture-building half of `zonedTimeToInstant`. */
function localIso(hhmm: string): string {
  const [hour, minute] = hhmm.split(':').map(Number);
  return zonedTimeToInstant(TODAY, hour + minute / 60, ZONE).toISOString();
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
    guestName: null,
    guestPhone: null,
    note: null,
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
    locationCount: 0,
    enabledPaymentMethodCount: 0,
    enabledFulfillmentModes: [],
    ...overrides,
  };
}

function locationView(overrides: Partial<LocationView> = {}): LocationView {
  return {
    id: 'l1',
    tenantId: 't1',
    brandId: 'b1',
    code: 'CENTRE',
    slug: 'centre',
    displayName: 'Centre',
    timezone: ZONE,
    status: 'ACTIVE',
    addressLine: null,
    district: null,
    city: null,
    landmark: null,
    contactPhone: null,
    latitude: null,
    longitude: null,
    coordinateSource: 'NOT_GEOCODED',
    ...overrides,
  };
}

function dineInBinding(overrides: Partial<ModeBindingView> = {}): ModeBindingView {
  return {
    fulfillmentMode: 'DINE_IN',
    scheduleId: 'schedule-1',
    scheduleName: 'Standard hours',
    acceptsScheduledOrders: false,
    sharedWithLocationCount: 1,
    rules: [],
    exceptions: [],
    scheduleVersion: 1,
    ...overrides,
  };
}

function serviceSummary(bindings: readonly ModeBindingView[] = []): ServiceSummaryResponse {
  return {
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
    maxConcurrentOrders: null,
    openOrderCount: 0,
    bindings,
    preparationBands: [],
  };
}

function sessionView(overrides: Partial<SessionView> = {}): SessionView {
  return {
    sessionId: 'session-1',
    reservationId: 'res-1',
    partySize: 4,
    businessDate: TODAY,
    openedAt: new Date().toISOString(),
    status: 'OPEN',
    serviceChargeRateBp: null,
    currency: 'UZS',
    settledTotalMinor: null,
    closedAt: null,
    closeReasonCode: null,
    version: 1,
    ...overrides,
  };
}

/** No `DINE_IN` binding at all — the component falls back to its own 08:00-23:00 guess, matching this screen's behaviour before W01. */
function defaultLocationsApi(overrides: Partial<LocationsApi> = {}): Partial<LocationsApi> {
  return {
    profile: () => Promise.resolve(locationView()),
    serviceSummary: () => Promise.resolve(serviceSummary()),
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
    options: {
      channelsApi?: Partial<SalesChannelsApi>;
      locationsApi?: Partial<LocationsApi>;
      sessionsApi?: Partial<TableSessionsApi>;
      locationOverrides?: { scope?: LocationScope | null; denied?: boolean };
    } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ReservationsPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'scope' in (options.locationOverrides ?? {})
                ? (options.locationOverrides?.scope ?? null)
                : SCOPE,
            ),
            denied: signal(options.locationOverrides?.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReservationsApi, useValue: reservationsApi },
        {
          provide: SalesChannelsApi,
          useValue: options.channelsApi ?? { list: () => Promise.resolve([channel()]) },
        },
        { provide: LocationsApi, useValue: options.locationsApi ?? defaultLocationsApi() },
        { provide: TableSessionsApi, useValue: options.sessionsApi ?? {} },
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
      {
        channelsApi: { list: () => Promise.resolve([]) },
        locationOverrides: { scope: null, denied: true },
      },
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

  it('shows the grid’s own table code, not the raw table id, in the detail pane', async () => {
    await render({
      availability: () => Promise.resolve([table({ tableId: 'table-1', code: 'T7' })]),
      listForDay: () => Promise.resolve([reservation({ tableIds: ['table-1'] })]),
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="reservations-cell-booked"]') as HTMLElement).click();
    fixture.detectChanges();

    const tables = host.querySelector('[data-testid="reservations-detail-tables"]');
    expect(tables?.textContent).toContain('T7');
    expect(tables?.textContent).not.toContain('table-1');
  });

  it('binds the grid’s hour range to the location’s own DINE_IN schedule, past 23:00', async () => {
    await render(
      {
        availability: () => Promise.resolve([table()]),
        // A booking at 23:30, which the old hard-coded 08:00-23:00 window
        // would have cut off the grid entirely.
        listForDay: () =>
          Promise.resolve([
            reservation({ requestedFrom: localIso('23:30'), requestedTo: localIso('24:30') }),
          ]),
      },
      {
        locationsApi: defaultLocationsApi({
          serviceSummary: () =>
            Promise.resolve(
              serviceSummary([
                dineInBinding({
                  rules: [
                    { dayOfWeek: isoDayOfWeek(TODAY), opensAt: '18:00:00', closesAt: '02:00:00' },
                  ],
                }),
              ]),
            ),
        }),
      },
    );

    const host = fixture.nativeElement as HTMLElement;
    const hourHeaders = [...host.querySelectorAll('.reservations__hour-header')].map((node) =>
      node.textContent?.trim(),
    );
    // 18:00-02:00 wraps past midnight; the grid must reach the small hours,
    // not stop at 23:00.
    expect(hourHeaders).toContain('1:00');
    expect(
      host.querySelectorAll('[data-testid="reservations-cell-booked"]').length,
    ).toBeGreaterThan(0);
  });

  it('renders a closed-today message when the branch’s own schedule has no rule for today, rather than a fabricated grid', async () => {
    await render(
      {
        availability: () => Promise.resolve([table()]),
        listForDay: () => Promise.resolve([]),
      },
      {
        locationsApi: defaultLocationsApi({
          serviceSummary: () =>
            Promise.resolve(
              serviceSummary([
                // A rule for every day except today.
                dineInBinding({
                  rules: [
                    {
                      dayOfWeek: otherIsoDayOfWeek(TODAY),
                      opensAt: '10:00:00',
                      closesAt: '22:00:00',
                    },
                  ],
                }),
              ]),
            ),
        }),
      },
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="reservations-closed"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="reservations-grid"]')).toBeNull();
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

    (
      host.querySelector('[data-testid="reservations-action-CONFIRMED"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'Table confirmed available';
    code.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(stateAction).toHaveBeenCalledWith(
      SCOPE,
      'res-1',
      'CONFIRMED',
      'Table confirmed available',
      1,
    );
    expect(host.querySelector('[data-testid="reservations-detail"]')?.textContent).toContain(
      'Confirmed',
    );
  });

  it('offers "mark completed" for a seated booking', async () => {
    const stateAction = vi
      .fn()
      .mockReturnValue(of(reservation({ status: 'COMPLETED', version: 3 })));
    await render({
      availability: () => Promise.resolve([table()]),
      listForDay: () => Promise.resolve([reservation({ status: 'SEATED', version: 2 })]),
      stateAction,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="reservations-cell-booked"]') as HTMLElement).click();
    fixture.detectChanges();

    const completeButton = host.querySelector(
      '[data-testid="reservations-action-COMPLETED"]',
    ) as HTMLButtonElement | null;
    expect(completeButton).not.toBeNull();
    completeButton?.click();
    fixture.detectChanges();

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'Table cleared';
    code.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(stateAction).toHaveBeenCalledWith(SCOPE, 'res-1', 'COMPLETED', 'Table cleared', 2);
  });

  it('seats a confirmed booking by opening a table session, then re-reads it', async () => {
    const open = vi.fn().mockReturnValue(of(sessionView()));
    const find = vi
      .fn()
      .mockReturnValue(Promise.resolve(reservation({ status: 'SEATED', version: 2 })));
    await render(
      {
        availability: () => Promise.resolve([table()]),
        listForDay: () => Promise.resolve([reservation({ status: 'CONFIRMED' })]),
        find,
      },
      { sessionsApi: { open } },
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="reservations-cell-booked"]') as HTMLElement).click();
    fixture.detectChanges();

    (host.querySelector('[data-testid="reservations-seat"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'Party arrived';
    code.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(open).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({
        reservationId: 'res-1',
        tableIds: ['table-1'],
        reason: 'Party arrived',
      }),
    );
    expect(find).toHaveBeenCalledWith(SCOPE, 'res-1');
    expect(host.querySelector('[data-testid="reservations-detail"]')?.textContent).toContain(
      'Seated',
    );
  });

  it('reveals a booking’s guest name, phone and note behind a stated purpose, only on request', async () => {
    const find = vi
      .fn()
      .mockReturnValue(
        Promise.resolve(
          reservation({ guestName: 'Dilnoza', guestPhone: '998901234567', note: 'Window seat' }),
        ),
      );
    await render({
      availability: () => Promise.resolve([table()]),
      listForDay: () => Promise.resolve([reservation()]),
      find,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="reservations-cell-booked"]') as HTMLElement).click();
    fixture.detectChanges();

    // Not fetched, and not rendered, until the host asks.
    expect(find).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="reservations-guest"]')?.textContent).not.toContain(
      'Dilnoza',
    );

    (host.querySelector('[data-testid="reservations-reveal-guest"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(find).toHaveBeenCalledWith(SCOPE, 'res-1', expect.stringMatching(/purpose|walk-in/i));
    const guest = host.querySelector('[data-testid="reservations-guest"]');
    expect(guest?.textContent).toContain('Dilnoza');
    expect(guest?.textContent).toContain('998901234567');
    expect(guest?.textContent).toContain('Window seat');
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

  it('amends a booking’s party size and time, leaving the guest fields blank sends no correction', async () => {
    const amend = vi.fn().mockReturnValue(of(reservation({ partySize: 6, version: 2 })));
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

    // The guest fields render (they are now editable), but start blank.
    const name = host.querySelector('[data-testid="reservations-form-name"]') as HTMLInputElement;
    expect(name).not.toBeNull();
    expect(name.value).toBe('');

    const party = host.querySelector('[data-testid="reservations-form-party"]') as HTMLInputElement;
    party.value = '6';
    party.dispatchEvent(new Event('input'));
    const reason = host.querySelector(
      '[data-testid="reservations-form-reason"]',
    ) as HTMLInputElement;
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
    // Left blank — must not be sent as a correction.
    expect(body.guestName).toBeUndefined();
    expect(body.guestPhone).toBeUndefined();
    expect(expectedVersion).toBe(1);
    expect(host.querySelector('[data-testid="reservations-form"]')).toBeNull();
  });

  it('amends a booking’s guest name when the host actually fills it in', async () => {
    const amend = vi.fn().mockReturnValue(of(reservation({ version: 2 })));
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

    const name = host.querySelector('[data-testid="reservations-form-name"]') as HTMLInputElement;
    name.value = 'Dilnoza Karimova';
    name.dispatchEvent(new Event('input'));
    const reason = host.querySelector(
      '[data-testid="reservations-form-reason"]',
    ) as HTMLInputElement;
    reason.value = 'Corrected the spelling';
    reason.dispatchEvent(new Event('input'));

    (host.querySelector('[data-testid="reservations-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const [, , body] = amend.mock.calls[0];
    expect(body.guestName).toBe('Dilnoza Karimova');
    expect(body.guestPhone).toBeUndefined();
  });
});

/** ISO day of week, 1 (Monday) to 7 (Sunday), for a `YYYY-MM-DD` date. */
function isoDayOfWeek(dateIso: string): number {
  const [year, month, day] = dateIso.split('-').map(Number);
  const sundayZero = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return sundayZero === 0 ? 7 : sundayZero;
}

/** Any ISO day of week other than today's — for a "the schedule has a rule, just not for today" fixture. */
function otherIsoDayOfWeek(dateIso: string): number {
  const today = isoDayOfWeek(dateIso);
  return (today % 7) + 1;
}
