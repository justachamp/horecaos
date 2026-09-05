import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CapacityApi, CapacityWindowResponse } from './capacity-api';
import { CapacityPage } from './capacity-page';
import { KitchenApi, StationResponse } from './kitchen-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function station(overrides: Partial<StationResponse> = {}): StationResponse {
  return {
    stationId: 'station-1',
    code: 'GRILL',
    role: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Gril',
    displayNameEn: 'Grill',
    sortOrder: 1,
    fallback: false,
    status: 'ACTIVE',
    version: 1,
    ...overrides,
  };
}

function window_(overrides: Partial<CapacityWindowResponse> = {}): CapacityWindowResponse {
  return {
    capacityWindowId: 'window-1',
    stationId: 'station-1',
    weekday: 5,
    windowStart: '18:00:00',
    windowEnd: '22:00:00',
    portionsPerHour: 40,
    version: 1,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CapacityPage', () => {
  let fixture: ComponentFixture<CapacityPage>;

  async function render(
    capacityApi: Partial<CapacityApi>,
    kitchenApi: Partial<KitchenApi> = { stations: () => Promise.resolve([station()]) },
    locationOverrides: { scope?: LocationScope | null; denied?: boolean } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [CapacityPage],
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
        { provide: CapacityApi, useValue: capacityApi },
        { provide: KitchenApi, useValue: kitchenApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CapacityPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('shows the denied state when the location grant is missing', async () => {
    await render(
      { list: vi.fn() },
      { stations: () => Promise.resolve([]) },
      { scope: null, denied: true },
    );

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="capacity-denied"]'),
    ).not.toBeNull();
  });

  it('lists the branch’s throughput ceilings with station and weekday labels', async () => {
    await render({ list: () => Promise.resolve([window_()]) });

    const host = fixture.nativeElement as HTMLElement;
    const table = host.querySelector('[data-testid="capacity-table"]') as HTMLElement;
    expect(table.textContent).toContain('Grill');
    expect(table.textContent).toContain('Friday');
    expect(table.textContent).toContain('18:00');
    expect(table.textContent).toContain('40');
  });

  it('shows an honest empty state when no stations are configured', async () => {
    await render({ list: () => Promise.resolve([]) }, { stations: () => Promise.resolve([]) });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="capacity-no-stations"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="capacity-table"]')).toBeNull();
  });

  it('adds a throughput ceiling from the form', async () => {
    const create = vi.fn().mockReturnValue(of(window_({ capacityWindowId: 'window-new' })));
    await render({ list: () => Promise.resolve([]), create });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="capacity-form-portions"]') as HTMLInputElement).value = '30';
    host
      .querySelector('[data-testid="capacity-form-portions"]')
      ?.dispatchEvent(new Event('input'));

    (host.querySelector('[data-testid="capacity-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(create).toHaveBeenCalledTimes(1);
    const [, body] = create.mock.calls[0];
    expect(body.stationId).toBe('station-1');
    expect(body.portionsPerHour).toBe(30);
    expect(body.windowStart).toMatch(/^\d\d:\d\d:00$/);
    expect(host.querySelector('[data-testid="capacity-form-error"]')).toBeNull();
  });

  it('surfaces a refused overlapping window without crashing', async () => {
    const error = new ApiError('RESOURCE_CONFLICT', 409, null, 'corr-1');
    const create = vi.fn().mockReturnValue(throwError(() => error));
    await render({ list: () => Promise.resolve([]), create });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="capacity-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="capacity-form-error"]')).not.toBeNull();
  });

  it('names the cook headcount gap rather than rendering a fabricated number', async () => {
    await render({ list: () => Promise.resolve([]) });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Cook headcount output');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Not built');
  });
});
