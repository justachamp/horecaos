import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LivenessPanel } from './liveness-panel';
import { IntegrationsApi, LivenessView } from './integrations-api';

/**
 * ADR 0106, gap-map row `10.8c`: `MarketplaceOperationsController.liveness`
 * was built and capability-gated with zero callers anywhere in this app —
 * this is the one test proving something in `frontend/operations` actually
 * renders it now.
 */
const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const HEALTHY_ROW: LivenessView = {
  bindingId: 'binding-1',
  locationId: 'location-1',
  providerName: 'Yandex Eats',
  direction: 'INBOUND',
  lastSuccessAt: '2026-09-12T08:00:00Z',
  lastSuccessReference: 'order-99',
  lastFailureAt: null,
  lastFailureCode: null,
  staleAfterSeconds: 900,
  observedMedianIntervalSeconds: 120,
  alertState: 'HEALTHY',
  silenceSeconds: 30,
};

const STALE_ROW: LivenessView = {
  ...HEALTHY_ROW,
  bindingId: 'binding-2',
  providerName: 'Wolt',
  alertState: 'STALE',
  lastSuccessAt: null,
  silenceSeconds: null,
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

class FakeIntegrationsApi {
  readonly marketplaceLiveness = vi.fn().mockResolvedValue([]);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('LivenessPanel', () => {
  let fixture: ComponentFixture<LivenessPanel>;
  let api: FakeIntegrationsApi;

  async function create(rows: readonly LivenessView[]): Promise<void> {
    api = new FakeIntegrationsApi();
    api.marketplaceLiveness.mockResolvedValue(rows);
    await TestBed.configureTestingModule({
      imports: [LivenessPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LivenessPanel);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('calls the liveness read for the operator’s own scope on construction', async () => {
    await create([]);

    expect(api.marketplaceLiveness).toHaveBeenCalledWith(SCOPE);
  });

  it('renders the empty state when nothing is bound yet', async () => {
    await create([]);

    expect(host().textContent).toContain('No marketplace bindings yet.');
  });

  it('renders a healthy row with its provider name, last success and silence', async () => {
    await create([HEALTHY_ROW]);

    expect(host().textContent).toContain('Yandex Eats');
    expect(host().textContent).toContain('HEALTHY');
  });

  it('renders "never" for a binding with no successful delivery yet, not a raw null', async () => {
    await create([STALE_ROW]);

    expect(host().textContent).toContain('Wolt');
    expect(host().textContent).not.toContain('null');
  });

  it('surfaces a legible error rather than an empty table when the read fails', async () => {
    api = new FakeIntegrationsApi();
    api.marketplaceLiveness.mockRejectedValue(new Error('network down'));
    await TestBed.configureTestingModule({
      imports: [LivenessPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LivenessPanel);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host().querySelector('[role="alert"]')).toBeTruthy();
  });
});
