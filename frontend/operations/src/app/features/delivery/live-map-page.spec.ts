import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MapUnavailableError } from '../../shared/ui/map/map-provider';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { Toasts } from '../../shared/ui/toast';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { CourierPositionsApi, FleetResponse, TrackRevealResponse } from './courier-positions-api';
import { DeliveryZonesApi, ZoneOutlineResponse } from './delivery-zones-api';
import { LiveMapPage } from './live-map-page';
import { MapRegionService } from './map-region';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

const ZONE_OUTLINE: ZoneOutlineResponse = {
  zoneId: 'zone-1',
  code: 'CENTRE',
  role: 'DELIVERY',
  version: 1,
  status: 'ACTIVE',
  shapeKind: 'POLYGON',
  polygons: [
    {
      ring: [
        { latitude: 41.3, longitude: 69.2 },
        { latitude: 41.3, longitude: 69.3 },
        { latitude: 41.4, longitude: 69.25 },
      ],
      holes: [],
    },
  ],
};

function rosterEntry(courierId: string, displayReference: string): RosterEntryResponse {
  return {
    courierId,
    displayReference,
    status: 'ACTIVE',
    courierTypeId: 'type-1',
    courierTypeName: 'Courier',
    vehicleClass: 'BIKE',
    activeAssignments: 0,
    concurrencyCeiling: 2,
  };
}

function coarseOf(courierId: string, activeAssignmentCount: number) {
  return {
    courierId,
    activeAssignmentCount,
    lastFixAt: new Date().toISOString(),
    reason: 'LAST_FIX_TOO_OLD',
  };
}

function pinCarrying(courierId: string, orders: number, latitude: number, longitude: number) {
  return { ...pinOf(courierId, latitude, longitude), activeAssignmentCount: orders };
}

function pinOf(courierId: string, latitude: number, longitude: number) {
  return {
    courierId,
    latitude,
    longitude,
    accuracyMeters: 10,
    activeAssignmentCount: 2,
    capturedAt: new Date().toISOString(),
  };
}

describe('LiveMapPage', () => {
  let fixture: ComponentFixture<LiveMapPage>;
  let mapProvider: NullMapProvider;
  let activeOutlines: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    activeOutlines = vi.fn().mockResolvedValue([]);
  });

  async function render(
    fleet: FleetResponse,
    overrides: {
      provider?: NullMapProvider;
      options?: readonly LocationOption[];
      selectLocation?: (locationId: string) => void;
      revealTrack?: (...args: unknown[]) => Promise<TrackRevealResponse>;
      fleetFn?: () => Promise<FleetResponse>;
      /** The roster the page identifies couriers by; an Error is a caller who may not read it. */
      roster?: readonly RosterEntryResponse[] | Error;
    } = {},
  ): Promise<HTMLElement> {
    mapProvider = overrides.provider ?? new NullMapProvider();
    await TestBed.configureTestingModule({
      imports: [LiveMapPage],
      providers: [
        provideNullMapProvider(mapProvider),
        { provide: DeliveryZonesApi, useValue: { activeOutlines } },
        {
          provide: MapRegionService,
          useValue: { ensureLoaded: () => Promise.resolve(), primary: () => null },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
            options: () => overrides.options ?? [],
            selectLocation: overrides.selectLocation ?? vi.fn(),
          },
        },
        {
          provide: CourierPositionsApi,
          useValue: {
            fleet: overrides.fleetFn ?? (() => Promise.resolve(fleet)),
            revealTrack: overrides.revealTrack ?? vi.fn(),
          },
        },
        {
          provide: CouriersApi,
          useValue: {
            roster: () =>
              overrides.roster instanceof Error
                ? Promise.reject(overrides.roster)
                : Promise.resolve(overrides.roster ?? []),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LiveMapPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders every drawable pin and every coarse courier honestly', async () => {
    const host = await render({
      pins: [
        {
          courierId: 'courier-1',
          latitude: 41.31,
          longitude: 69.28,
          accuracyMeters: 20,
          activeAssignmentCount: 2,
          batteryPercent: 54,
          capturedAt: new Date().toISOString(),
        },
      ],
      withoutPin: [
        {
          courierId: 'courier-2',
          activeAssignmentCount: 0,
          lastFixAt: new Date().toISOString(),
          reason: 'LAST_FIX_TOO_OLD',
        },
      ],
    });

    expect(host.querySelectorAll('[data-testid="live-map-pin"]')).toHaveLength(1);
    expect(host.querySelectorAll('[data-testid="live-map-coarse"]')).toHaveLength(1);
    expect(host.textContent).toContain('courier-1');
    expect(host.textContent).toContain('54%');
    expect(host.textContent).toContain('courier-2');
  });

  // Row 3.2: "Render accuracyMeters, headingDegrees and speedMps, which the
  // table drops" — all three were on the wire since ADR 0045 and none of
  // them reached the screen before this wave.
  it('renders accuracyMeters, headingDegrees and speedMps for a drawable pin', async () => {
    const host = await render({
      pins: [
        {
          courierId: 'courier-1',
          latitude: 41.31,
          longitude: 69.28,
          accuracyMeters: 12.4,
          headingDegrees: 87,
          speedMps: 3.2,
          activeAssignmentCount: 1,
          capturedAt: new Date().toISOString(),
        },
      ],
      withoutPin: [],
    });

    expect(host.textContent).toContain('±12 m');
    expect(host.textContent).toContain('87°');
    expect(host.textContent).toContain('3.2 m/s');
  });

  it('shows a dash for heading and speed when the device did not report them', async () => {
    const host = await render({
      pins: [
        {
          courierId: 'courier-1',
          latitude: 41.31,
          longitude: 69.28,
          accuracyMeters: 12,
          activeAssignmentCount: 0,
          capturedAt: new Date().toISOString(),
        },
      ],
      withoutPin: [],
    });

    const row = host.querySelector('[data-testid="live-map-pin"]')!;
    const cells = [...row.querySelectorAll('td')].map((cell) => cell.textContent?.trim());
    // Column order: courier(0), position(1), accuracy(2), heading(3), speed(4), activeOrders(5), ...
    expect(cells[3]).toBe('—');
    expect(cells[4]).toBe('—');
  });

  // Row 3.2: "add a branch filter" — it reuses the shared `CurrentLocation`
  // picker rather than a page-local scope, exactly like `shell.html`'s own.
  it('offers a branch filter once there is more than one option, and switches on selection', async () => {
    const selectLocation = vi.fn();
    const fleetFn = vi.fn().mockResolvedValue({ pins: [], withoutPin: [] });
    const host = await render(
      { pins: [], withoutPin: [] },
      {
        options: [
          { id: 'l1', displayName: 'Chilanzar', status: 'ACTIVE' },
          { id: 'l2', displayName: 'Yunusabad', status: 'ACTIVE' },
        ],
        selectLocation,
        fleetFn,
      },
    );

    const select = host.querySelector<HTMLSelectElement>('[data-testid="live-map-branch-filter"]');
    expect(select).not.toBeNull();
    expect([...select!.options].map((option) => option.value)).toEqual(['l1', 'l2']);

    select!.value = 'l2';
    select!.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    expect(selectLocation).toHaveBeenCalledWith('l2');
    // Once on load, once after the branch switch.
    expect(fleetFn).toHaveBeenCalledTimes(2);
  });

  it('hides the branch filter for an operator with at most one location', async () => {
    const host = await render(
      { pins: [], withoutPin: [] },
      { options: [{ id: 'l1', displayName: 'Chilanzar', status: 'ACTIVE' }] },
    );

    expect(host.querySelector('[data-testid="live-map-branch-filter"]')).toBeNull();
  });

  // Row 3.2: "wire the reveal with a stated purpose" — the audited
  // `courier.track.reveal` path helper had zero call sites before this wave.
  it('reveals a stored track with a stated purpose and shows the result', async () => {
    const revealTrack = vi.fn().mockResolvedValue({
      courierId: 'courier-1',
      from: '2026-09-12T10:00:00.000Z',
      to: '2026-09-12T11:00:00.000Z',
      purpose: 'Customer says the order never arrived',
      windows: [
        {
          windowStart: '2026-09-12T10:00:00.000Z',
          windowEnd: '2026-09-12T10:05:00.000Z',
          observationCount: 3,
          distanceMeters: 120,
          observations: [],
        },
      ],
    } satisfies TrackRevealResponse);

    const host = await render(
      {
        pins: [
          {
            courierId: 'courier-1',
            latitude: 41.31,
            longitude: 69.28,
            accuracyMeters: 10,
            activeAssignmentCount: 0,
            capturedAt: new Date().toISOString(),
          },
        ],
        withoutPin: [],
      },
      { revealTrack },
    );

    host.querySelector<HTMLButtonElement>('[data-testid="live-map-reveal-trigger"]')!.click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="track-reveal-dialog"]')).not.toBeNull();

    const purposeField = host.querySelector<HTMLTextAreaElement>(
      '[data-testid="track-reveal-dialog-purpose"]',
    )!;
    purposeField.value = 'Customer says the order never arrived';
    purposeField.dispatchEvent(new Event('input'));
    host.querySelector<HTMLButtonElement>('[data-testid="track-reveal-dialog-confirm"]')!.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(revealTrack).toHaveBeenCalledWith(
      SCOPE,
      'courier-1',
      expect.any(String),
      expect.any(String),
      'Customer says the order never arrived',
    );
    expect(host.querySelector('[data-testid="live-map-reveal-result"]')?.textContent).toContain(
      'Customer says the order never arrived',
    );
    // The dialog closes on success and the outcome is announced (ADR 0101).
    expect(host.querySelector('[data-testid="track-reveal-dialog"]')).toBeNull();
    const announced = TestBed.inject(Toasts).visible();
    expect(announced).toHaveLength(1);
    expect(announced[0].tone).toBe('success');
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [LiveMapPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
            options: () => [],
            selectLocation: vi.fn(),
          },
        },
        { provide: CourierPositionsApi, useValue: { fleet: vi.fn(), revealTrack: vi.fn() } },
        { provide: CouriersApi, useValue: { roster: () => Promise.resolve([]) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LiveMapPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="live-map-denied"]'),
    ).not.toBeNull();
  });

  // ------------------------------------------------ ADR 0145, row 3.2: on a map

  it('puts every drawable courier on the map as a pin of their own, and the others only in the list', async () => {
    const host = await render({
      pins: [pinOf('courier-1', 41.31, 69.28), pinOf('courier-2', 41.33, 69.22)],
      withoutPin: [
        {
          courierId: 'courier-3',
          activeAssignmentCount: 1,
          lastFixAt: new Date().toISOString(),
          reason: 'ACCURACY_BELOW_MAP_FLOOR',
        },
      ],
    });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="live-map-canvas"]')).not.toBeNull();
    expect(mapProvider.map.livePins.map((pin) => pin.position)).toEqual([
      { latitude: 41.31, longitude: 69.28 },
      { latitude: 41.33, longitude: 69.22 },
    ]);
    expect(mapProvider.map.livePins.every((pin) => !pin.draggable)).toBe(true);
    expect(mapProvider.map.livePins[0].label).toContain('courier-1');
    expect(mapProvider.map.livePins[0].label).toContain('2');
    expect(host.querySelectorAll('[data-testid="live-map-coarse"]')).toHaveLength(1);
    expect(mapProvider.map.livePins.some((pin) => pin.label?.includes('courier-3'))).toBe(false);
  });

  it('moves a courier’s pin on the next refresh instead of drawing a second one', async () => {
    let fleet: FleetResponse = { pins: [pinOf('courier-1', 41.31, 69.28)], withoutPin: [] };
    const host = await render(fleet, { fleetFn: () => Promise.resolve(fleet) });
    await flushMicrotasks();
    fixture.detectChanges();
    const pin = mapProvider.map.livePins[0];

    fleet = { pins: [pinOf('courier-1', 41.32, 69.3)], withoutPin: [] };
    (host.querySelector('[data-testid="live-map-refresh"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();

    expect(mapProvider.map.livePins).toEqual([pin]);
    expect(pin.position).toEqual({ latitude: 41.32, longitude: 69.3 });
  });

  it('does not re-fit the map on every ten-second refresh, which would fight a dispatcher who has panned', async () => {
    let fleet: FleetResponse = {
      pins: [pinOf('courier-1', 41.31, 69.28), pinOf('courier-2', 41.33, 69.22)],
      withoutPin: [],
    };
    const host = await render(fleet, { fleetFn: () => Promise.resolve(fleet) });
    await flushMicrotasks();
    fixture.detectChanges();
    const fits = mapProvider.map.fitted.length;
    expect(fits).toBe(1);

    fleet = {
      pins: [pinOf('courier-1', 41.4, 69.4), pinOf('courier-2', 41.2, 69.1)],
      withoutPin: [],
    };
    (host.querySelector('[data-testid="live-map-refresh"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();

    expect(mapProvider.map.fitted).toHaveLength(fits);
  });

  it('draws the brand’s live zones under the couriers, as outlines nobody can edit', async () => {
    activeOutlines = vi.fn().mockResolvedValue([ZONE_OUTLINE]);
    await render({ pins: [pinOf('courier-1', 41.31, 69.28)], withoutPin: [] });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(activeOutlines).toHaveBeenCalled();
    expect(mapProvider.map.livePolygons).toHaveLength(1);
    expect(mapProvider.map.livePolygons[0].editable).toBe(false);
    expect(mapProvider.map.livePolygons[0].label).toBe('CENTRE');
  });

  it('still shows the couriers when the caller may not read zones', async () => {
    activeOutlines = vi.fn().mockRejectedValue(new Error('403'));
    const host = await render({ pins: [pinOf('courier-1', 41.31, 69.28)], withoutPin: [] });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(mapProvider.map.livePins).toHaveLength(1);
    expect(mapProvider.map.livePolygons).toHaveLength(0);
    expect(host.querySelectorAll('[data-testid="live-map-pin"]')).toHaveLength(1);
  });

  it('keeps the table, and says why there is no map, when no provider is set up', async () => {
    const provider = new NullMapProvider();
    provider.loadError = new MapUnavailableError('NOT_CONFIGURED');

    const host = await render(
      { pins: [pinOf('courier-1', 41.31, 69.28)], withoutPin: [] },
      { provider },
    );
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('No map provider is set up');
    expect(host.querySelectorAll('[data-testid="live-map-pin"]')).toHaveLength(1);
    expect(host.textContent).toContain('courier-1');
  });

  // ------------------------------------------- row 3.2: couriers by their reference, not their UUID

  describe('identifying a courier (ADR 0029: no name on a pin, and no UUID either)', () => {
    const UUID_A = '0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b';
    const UUID_B = '0190a1b2-c3d4-7e5f-8a6b-aaaaaaaaaaaa';

    it('shows the roster’s reference on the pin, in the table and on the no-signal row', async () => {
      const host = await render(
        { pins: [pinOf(UUID_A, 41.31, 69.28)], withoutPin: [coarseOf(UUID_B, 1)] },
        { roster: [rosterEntry(UUID_A, 'K-100'), rosterEntry(UUID_B, 'K-200')] },
      );
      await flushMicrotasks();
      fixture.detectChanges();

      expect(mapProvider.map.livePins[0].label).toContain('K-100');
      expect(mapProvider.map.livePins[0].label).not.toContain(UUID_A);
      const row = host.querySelector('[data-testid="live-map-pin"]')!;
      expect(row.querySelector('td')!.textContent?.trim()).toBe('K-100');
      const coarse = host.querySelector('[data-testid="live-map-coarse"]')!;
      expect(coarse.querySelector('td')!.textContent?.trim()).toBe('K-200');
      expect(host.textContent).not.toContain(UUID_A);
      expect(host.textContent).not.toContain(UUID_B);
    });

    it('names the courier by reference on the revealed track too', async () => {
      const revealTrack = vi.fn().mockResolvedValue({
        courierId: UUID_A,
        from: '2026-09-12T10:00:00.000Z',
        to: '2026-09-12T11:00:00.000Z',
        purpose: 'Customer says the order never arrived',
        windows: [],
      } satisfies TrackRevealResponse);
      const host = await render(
        { pins: [pinOf(UUID_A, 41.31, 69.28)], withoutPin: [] },
        { revealTrack, roster: [rosterEntry(UUID_A, 'K-100')] },
      );
      host.querySelector<HTMLButtonElement>('[data-testid="live-map-reveal-trigger"]')!.click();
      fixture.detectChanges();
      const purpose = host.querySelector<HTMLTextAreaElement>(
        '[data-testid="track-reveal-dialog-purpose"]',
      )!;
      purpose.value = 'Customer says the order never arrived';
      purpose.dispatchEvent(new Event('input'));
      host.querySelector<HTMLButtonElement>('[data-testid="track-reveal-dialog-confirm"]')!.click();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      const result = host.querySelector('[data-testid="live-map-reveal-result"]')!;
      expect(result.textContent).toContain('K-100');
      expect(result.textContent).not.toContain(UUID_A);
    });

    it('falls back to a short id when the caller may not read the roster, and still shows the fleet', async () => {
      const host = await render(
        { pins: [pinOf(UUID_A, 41.31, 69.28)], withoutPin: [coarseOf(UUID_B, 0)] },
        { roster: new Error('403') },
      );
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.querySelectorAll('[data-testid="live-map-pin"]')).toHaveLength(1);
      expect(host.querySelector('[data-testid="live-map-pin"] td')!.textContent?.trim()).toBe(
        '0190a1b2',
      );
      expect(mapProvider.map.livePins[0].label).toContain('0190a1b2');
      expect(mapProvider.map.livePins[0].label).not.toContain(UUID_A);
      expect(host.textContent).not.toContain(UUID_A);
    });

    it('falls back to the short id for a courier the roster does not list', async () => {
      const host = await render(
        { pins: [pinOf(UUID_A, 41.31, 69.28), pinOf(UUID_B, 41.32, 69.29)], withoutPin: [] },
        { roster: [rosterEntry(UUID_A, 'K-100')] },
      );
      await flushMicrotasks();
      fixture.detectChanges();

      const cells = [...host.querySelectorAll('[data-testid="live-map-pin"]')].map((row) =>
        row.querySelector('td')!.textContent?.trim(),
      );
      expect(cells).toEqual(['K-100', '0190a1b2']);
    });
  });

  // -------------------------------- row 3.2: work-state filters (couriers.md §4 "Filters")

  describe('work-state filters', () => {
    const fleet: FleetResponse = {
      pins: [
        pinCarrying('courier-1', 2, 41.31, 69.28),
        pinCarrying('courier-2', 0, 41.33, 69.22),
        pinCarrying('courier-3', 1, 41.35, 69.2),
      ],
      withoutPin: [coarseOf('courier-4', 1), coarseOf('courier-5', 0)],
    };

    function chip(host: HTMLElement, state: string): HTMLButtonElement {
      return host.querySelector<HTMLButtonElement>(`[data-testid="live-map-state-${state}"]`)!;
    }

    function listed(host: HTMLElement): string[] {
      return [
        ...host.querySelectorAll('[data-testid="live-map-pin"], [data-testid="live-map-coarse"]'),
      ].map((row) => row.querySelector('td')!.textContent!.trim());
    }

    async function press(host: HTMLElement, state: string): Promise<void> {
      chip(host, state).click();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
    }

    it('offers On shift, Carrying, Idle and No signal with how many couriers are in each', async () => {
      const host = await render(fleet);

      expect(chip(host, 'ON_SHIFT').textContent).toContain('On shift');
      expect(chip(host, 'ON_SHIFT').textContent).toContain('5');
      expect(chip(host, 'CARRYING').textContent).toContain('2');
      expect(chip(host, 'IDLE').textContent).toContain('1');
      expect(chip(host, 'NO_SIGNAL').textContent).toContain('2');
      expect(chip(host, 'ON_SHIFT').getAttribute('aria-pressed')).toBe('true');
      expect(chip(host, 'CARRYING').getAttribute('aria-pressed')).toBe('false');
      expect(listed(host)).toEqual([
        'courier-1',
        'courier-2',
        'courier-3',
        'courier-4',
        'courier-5',
      ]);
    });

    it('Carrying keeps the drawable couriers with an order in hand, on the map and in the table', async () => {
      const host = await render(fleet);

      await press(host, 'CARRYING');

      expect(listed(host)).toEqual(['courier-1', 'courier-3']);
      expect(mapProvider.map.livePins.map((pin) => pin.label)).toEqual([
        expect.stringContaining('courier-1'),
        expect.stringContaining('courier-3'),
      ]);
      expect(chip(host, 'CARRYING').getAttribute('aria-pressed')).toBe('true');
      expect(chip(host, 'ON_SHIFT').getAttribute('aria-pressed')).toBe('false');
    });

    it('Idle keeps the drawable couriers carrying nothing', async () => {
      const host = await render(fleet);

      await press(host, 'IDLE');

      expect(listed(host)).toEqual(['courier-2']);
      expect(mapProvider.map.livePins).toHaveLength(1);
    });

    it('No signal is the band the platform could not draw, whatever it is carrying, and puts nothing on the map', async () => {
      const host = await render(fleet);

      await press(host, 'NO_SIGNAL');

      expect(listed(host)).toEqual(['courier-4', 'courier-5']);
      expect(mapProvider.map.livePins).toHaveLength(0);
    });

    it('goes back to every courier on shift, and a filter with nobody in it says so without an error', async () => {
      const host = await render({
        pins: [pinCarrying('courier-1', 1, 41.31, 69.28)],
        withoutPin: [],
      });

      await press(host, 'IDLE');
      expect(listed(host)).toEqual([]);
      expect(host.querySelector('[data-testid="live-map-state-empty"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="live-map-empty"]')).toBeNull();

      await press(host, 'ON_SHIFT');
      expect(listed(host)).toEqual(['courier-1']);
      expect(host.querySelector('[data-testid="live-map-state-empty"]')).toBeNull();
    });

    it('keeps the chosen filter through the ten-second refresh', async () => {
      let current: FleetResponse = fleet;
      const host = await render(fleet, { fleetFn: () => Promise.resolve(current) });
      await press(host, 'CARRYING');

      current = {
        pins: [pinCarrying('courier-1', 0, 41.31, 69.28), pinCarrying('courier-3', 3, 41.35, 69.2)],
        withoutPin: [],
      };
      (host.querySelector('[data-testid="live-map-refresh"]') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();
      await flushMicrotasks();

      expect(chip(host, 'CARRYING').getAttribute('aria-pressed')).toBe('true');
      expect(listed(host)).toEqual(['courier-3']);
    });
  });
});
