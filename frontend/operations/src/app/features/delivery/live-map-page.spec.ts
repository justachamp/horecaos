import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CourierPositionsApi, FleetResponse } from './courier-positions-api';
import { LiveMapPage } from './live-map-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('LiveMapPage', () => {
  let fixture: ComponentFixture<LiveMapPage>;

  async function render(fleet: FleetResponse): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [LiveMapPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CourierPositionsApi, useValue: { fleet: () => Promise.resolve(fleet) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LiveMapPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders every drawable pin and every coarse courier honestly', async () => {
    await render({
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

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="live-map-pin"]')).toHaveLength(1);
    expect(host.querySelectorAll('[data-testid="live-map-coarse"]')).toHaveLength(1);
    expect(host.textContent).toContain('courier-1');
    expect(host.textContent).toContain('54%');
    expect(host.textContent).toContain('courier-2');
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
          },
        },
        { provide: CourierPositionsApi, useValue: { fleet: vi.fn() } },
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
});
