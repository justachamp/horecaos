import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import { CurrentLocation } from '../core/auth/current-location';
import { I18n } from '../core/i18n/i18n';
import { PLATFORM_DEFAULT_LATENESS_POLICY } from '../core/lateness-policy';
import { LatenessPolicyApi } from '../core/lateness-policy-api';
import { RealtimeClient, RealtimeFrame } from '../core/realtime/realtime-client';
import {
  KitchenApi,
  StationResponse,
  VduBoardResponse,
  VduTicketResponse,
} from '../features/kitchen/kitchen-api';
import { WallboardVduPage } from './wallboard-vdu-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function ticket(overrides: Partial<VduTicketResponse> = {}): VduTicketResponse {
  return {
    ticketId: 'ticket-1',
    sequenceLabel: 'A-014',
    fulfilmentMode: 'DELIVERY',
    status: 'FIRED',
    targetReadyAt: null,
    createdAt: new Date().toISOString(),
    items: [],
    ...overrides,
  };
}

function station(overrides: Partial<StationResponse> = {}): StationResponse {
  return {
    stationId: 'station-1',
    code: 'GRILL',
    role: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Grill',
    displayNameEn: 'Grill',
    sortOrder: 1,
    fallback: false,
    status: 'ACTIVE',
    version: 1,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('WallboardVduPage', () => {
  let fixture: ComponentFixture<WallboardVduPage>;

  function setUp(
    vdu: VduBoardResponse,
    overrides: {
      readonly scope?: LocationScope | null;
      readonly denied?: boolean;
      readonly stations?: readonly StationResponse[];
      readonly vduSpy?: ReturnType<typeof vi.fn>;
    } = {},
  ): { frameListeners: Array<(frame: RealtimeFrame) => void>; vduSpy: ReturnType<typeof vi.fn> } {
    const frameListeners: Array<(frame: RealtimeFrame) => void> = [];
    const onFrame = vi.fn((listener: (frame: RealtimeFrame) => void) => {
      frameListeners.push(listener);
      return () => undefined;
    });
    const vduSpy = overrides.vduSpy ?? vi.fn().mockResolvedValue(vdu);

    TestBed.configureTestingModule({
      imports: [WallboardVduPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>('scope' in overrides ? overrides.scope! : SCOPE),
            denied: signal(overrides.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: KitchenApi,
          useValue: {
            vdu: vduSpy,
            stations: vi.fn().mockResolvedValue(overrides.stations ?? []),
          },
        },
        {
          provide: LatenessPolicyApi,
          useValue: { resolve: vi.fn().mockResolvedValue(PLATFORM_DEFAULT_LATENESS_POLICY) },
        },
        { provide: RealtimeClient, useValue: { onFrame, state: signal('open') } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(WallboardVduPage);
    return { frameListeners, vduSpy };
  }

  it('renders every live ticket from the dedicated VDU projection, not the desk board endpoint', async () => {
    const { vduSpy } = setUp({
      tickets: [
        ticket({ ticketId: 'a', sequenceLabel: 'A-002' }),
        ticket({ ticketId: 'b', sequenceLabel: 'A-001' }),
      ],
    });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(vduSpy).toHaveBeenCalledWith(SCOPE, undefined);
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="wallboard-vdu-card"]',
    );
    expect(cards).toHaveLength(2);
  });

  it('the provider-assigned reference renders beside the sequence label', async () => {
    setUp({ tickets: [ticket({ externalReference: 'YE-2291-04' })] });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const reference = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="wallboard-vdu-external-reference"]',
    );
    expect(reference?.textContent?.trim()).toBe('YE-2291-04');
  });

  it('the station filter narrows the wall to one station, re-fetching with it set', async () => {
    const { vduSpy } = setUp(
      { tickets: [ticket()] },
      {
        stations: [
          station({ stationId: 'grill-1' }),
          station({ stationId: 'cold-1', code: 'COLD' }),
        ],
      },
    );
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      '[data-testid="wallboard-vdu-station-filter"]',
    );
    expect(select).not.toBeNull();

    select!.value = 'grill-1';
    select!.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(vduSpy).toHaveBeenLastCalledWith(SCOPE, 'grill-1');
  });

  it('shows the denied state when the location grant is missing', async () => {
    setUp({ tickets: [] }, { scope: null, denied: true });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="wallboard-vdu-denied"]'),
    ).not.toBeNull();
  });

  it('refreshes at once on a KITCHEN_BOARD frame, the ADR 0045 accelerator', async () => {
    const { frameListeners, vduSpy } = setUp({ tickets: [] });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    vduSpy.mockClear();

    for (const listener of frameListeners) {
      listener({
        kind: 'signal',
        channel: 'kitchen_board',
        scope: 'LOCATION:l1',
        resourceType: 'KitchenTicket',
        resourceId: 'ticket-1',
        version: 3,
        occurredAt: '2026-09-25T09:00:00Z',
      });
    }
    await flushMicrotasks();

    expect(vduSpy).toHaveBeenCalled();
  });

  it('the offline/disconnected banner names how long ago the wall last refreshed', async () => {
    vi.useFakeTimers();
    try {
      const { vduSpy } = setUp({ tickets: [] });
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      vduSpy.mockImplementation(() => new Promise(() => undefined));

      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="wallboard-vdu-offline-banner"]',
        ),
      ).toBeNull();

      await vi.advanceTimersByTimeAsync(31_000);
      fixture.detectChanges();

      const banner = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="wallboard-vdu-offline-banner"]',
      );
      expect(banner).not.toBeNull();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="wallboard-vdu-last-updated"]',
        )?.textContent,
      ).toMatch(/Updated/);
    } finally {
      vi.useRealTimers();
    }
  });
});
