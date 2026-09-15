import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { FiscalizationApi } from '../settings/fiscalization/fiscalization-api';
import { LocationsApi } from '../settings/locations/locations-api';
import { PaymentMethodsApi } from '../settings/payment-methods/payment-methods-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { ReportsShell } from './reports-shell';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

@Component({ template: '' })
class DummyTabComponent {}

/**
 * Wave W02's own trap: "the filter bar renders above this tab and the page
 * ignores it entirely — either wire it or hide it". This proves the second
 * half — the bar is not merely unread on `/statistics/forecast`, it is not
 * rendered there at all — leaving "wired" to `demand-forecast-page.spec.ts`'s
 * own branch-selector coverage, since that screen's filter lives on the page
 * itself rather than on this shared bar (see `reports-shell.html`'s own
 * comment on the `@if`).
 */
describe('ReportsShell filter bar visibility (wave W02)', () => {
  let fixture: ComponentFixture<ReportsShell>;
  let router: Router;

  async function render(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ReportsShell],
      providers: [
        provideRouter([
          {
            path: 'statistics',
            children: [
              { path: 'overview', component: DummyTabComponent },
              { path: 'forecast', component: DummyTabComponent },
              { path: 'geography', component: DummyTabComponent },
            ],
          },
        ]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        {
          provide: FiscalizationApi,
          useValue: { listLegalEntities: vi.fn().mockResolvedValue([]) },
        },
        { provide: PaymentMethodsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(ReportsShell);
    fixture.detectChanges();
  }

  it('renders the filter bar on an ordinary tab', async () => {
    await render();
    await router.navigateByUrl('/statistics/overview');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="reports-shell-filter-bar"]',
      ),
    ).not.toBeNull();
  });

  it('hides the filter bar entirely on the forecast tab, rather than leaving it rendered and ignored', async () => {
    await render();
    await router.navigateByUrl('/statistics/forecast');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="reports-shell-filter-bar"]',
      ),
    ).toBeNull();
  });

  it('shows the bar again after navigating away from the forecast tab', async () => {
    await render();
    await router.navigateByUrl('/statistics/forecast');
    fixture.detectChanges();
    await router.navigateByUrl('/statistics/overview');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="reports-shell-filter-bar"]',
      ),
    ).not.toBeNull();
  });

  // Wave W04: §7.10's own axes (a fixed window, a weekday sample) fit this
  // bar no better than 7.8's do — see `geography-page.ts`'s own doc.
  it('hides the filter bar entirely on the geography tab too', async () => {
    await render();
    await router.navigateByUrl('/statistics/geography');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="reports-shell-filter-bar"]',
      ),
    ).toBeNull();
  });
});
