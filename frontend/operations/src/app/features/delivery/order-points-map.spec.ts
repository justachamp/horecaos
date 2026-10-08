import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { OrderMapPointsApi, OrderMapPointsResponse } from '../../core/api/order-map-points-api';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { OrderPointsMap } from './order-points-map';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const CENTRE = { latitude: 41.3, longitude: 69.25 };

function point(orderId: string, number: string, status: string, latitude: number) {
  return {
    orderId,
    publicOrderNumber: number,
    status,
    createdAt: '2026-10-07T08:00:00Z',
    latitude,
    longitude: 69.24,
  };
}

function revealed(overrides: Partial<OrderMapPointsResponse> = {}): OrderMapPointsResponse {
  return {
    windowFrom: '2026-10-07T00:00:00Z',
    windowTo: '2026-10-08T00:00:00Z',
    points: [
      point('a', 'F-1', 'PREPARING', 41.31),
      point('b', 'F-2', 'FULFILLING', 41.32),
      point('c', 'F-3', 'COMPLETED', 41.33),
    ],
    withoutPoint: 0,
    truncated: false,
    ...overrides,
  };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

describe('OrderPointsMap (rows 7.10a, 3.1; ADR 0145 decision 8)', () => {
  let provider: NullMapProvider;
  let api: { reveal: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    provider = new NullMapProvider();
    api = { reveal: vi.fn().mockResolvedValue(revealed()) };
    TestBed.configureTestingModule({
      providers: [provideNullMapProvider(provider), { provide: OrderMapPointsApi, useValue: api }],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<OrderPointsMap> {
    const fixture = TestBed.createComponent(OrderPointsMap);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('purpose', 'Operations console: a test (row 7.10a)');
    fixture.componentRef.setInput('center', CENTRE);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (fixture: ComponentFixture<unknown>, id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  it('reads nothing on its own, says the act is recorded, and draws no empty map', async () => {
    const fixture = create();
    await settle(fixture);

    expect(api.reveal).not.toHaveBeenCalled();
    expect(el(fixture, 'order-points-audit')?.textContent).toContain('recorded');
    expect(el(fixture, 'order-points-canvas')).toBeNull();
    expect(provider.maps).toHaveLength(0);
  });

  it('opens the day when asked, with the screen’s purpose, and pins only the orders still in progress', async () => {
    const fixture = create();
    (el(fixture, 'order-points-reveal') as HTMLButtonElement).click();
    await settle(fixture);

    expect(api.reveal).toHaveBeenCalledWith(SCOPE, 'Operations console: a test (row 7.10a)');
    expect(provider.map.livePins.map((pin) => pin.tone)).toEqual(['order', 'order']);
    expect(provider.map.livePins[0].label).toBe('Order F-1: Preparing');
    expect(el(fixture, 'order-points-summary')?.textContent).toContain('2 of 3');
  });

  it('shows the finished orders as closed pins when asked, and fits the map to what it shows', async () => {
    const fixture = create();
    (el(fixture, 'order-points-reveal') as HTMLButtonElement).click();
    await settle(fixture);
    expect(provider.map.fitted).toHaveLength(1);

    const toggle = el(fixture, 'order-points-open-only') as HTMLInputElement;
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(provider.map.livePins.map((pin) => pin.tone)).toEqual(['order', 'order', 'closed']);
    expect(el(fixture, 'order-points-legend')?.textContent).toContain('finished or cancelled');
  });

  it('puts the host’s own pins (couriers) on the same map, before anything is revealed, when asked to', async () => {
    const fixture = create({
      alwaysShowMap: true,
      extraMarkers: [
        { id: 'courier:1', position: { latitude: 41.3, longitude: 69.2 }, tone: 'courier' },
      ],
    });
    await settle(fixture);

    expect(provider.map.livePins).toHaveLength(1);
    expect(provider.map.livePins[0].tone).toBe('courier');
    expect(el(fixture, 'order-points-legend')?.textContent).toContain('courier');
    expect(api.reveal).not.toHaveBeenCalled();
  });

  it('counts the orders it could not place and says when the day was longer than the map opens', async () => {
    api.reveal.mockResolvedValue(revealed({ withoutPoint: 2, truncated: true }));
    const fixture = create();
    (el(fixture, 'order-points-reveal') as HTMLButtonElement).click();
    await settle(fixture);

    expect(el(fixture, 'order-points-without')?.textContent).toContain('2');
    expect(el(fixture, 'order-points-truncated')).not.toBeNull();
  });

  it('is told, in one sentence, that the caller may not open doorsteps, and not given an error', async () => {
    api.reveal.mockRejectedValue(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));
    const fixture = create();
    (el(fixture, 'order-points-reveal') as HTMLButtonElement).click();
    await settle(fixture);

    expect(el(fixture, 'order-points-denied')).not.toBeNull();
    expect(el(fixture, 'order-points-failed')).toBeNull();
  });

  it('says a failure is a failure, and can be asked again', async () => {
    api.reveal.mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 500, null, null));
    const fixture = create();
    (el(fixture, 'order-points-reveal') as HTMLButtonElement).click();
    await settle(fixture);
    expect(el(fixture, 'order-points-failed')).not.toBeNull();

    (el(fixture, 'order-points-reveal') as HTMLButtonElement).click();
    await settle(fixture);

    expect(el(fixture, 'order-points-failed')).toBeNull();
    expect(provider.map.livePins.length).toBeGreaterThan(0);
  });

  it('cannot be pressed again while it is opening the day, so one press is one audited reveal', async () => {
    let release: (value: OrderMapPointsResponse) => void = () => undefined;
    api.reveal.mockImplementationOnce(
      () =>
        new Promise<OrderMapPointsResponse>((resolve) => {
          release = resolve;
        }),
    );
    const fixture = create();
    const button = el(fixture, 'order-points-reveal') as HTMLButtonElement;

    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBe(true);
    button.click();
    release(revealed());
    await settle(fixture);

    expect(api.reveal).toHaveBeenCalledTimes(1);
    expect(button.disabled).toBe(false);
  });
});
