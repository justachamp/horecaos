import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MapUnavailableError } from '../../shared/ui/map/map-provider';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { Toasts } from '../../shared/ui/toast';
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
});
