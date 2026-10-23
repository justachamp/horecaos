import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, RouterLink, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { FiscalizationApi } from '../settings/fiscalization/fiscalization-api';
import { LocationsApi } from '../settings/locations/locations-api';
import { PaymentMethodsApi } from '../settings/payment-methods/payment-methods-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { ReportsFilterState } from './reports-filter-state';
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

  // Row X.11: DateRangePicker was built and tested with no live call site —
  // this proves the reports filter bar's "Custom" period is q-date-range-picker
  // itself, not a second pair of raw <input type="date"> fields.
  it('renders q-date-range-picker for the custom period, and a chosen range reaches ReportsFilterState', async () => {
    await render();
    await router.navigateByUrl('/statistics/overview');
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const periodButtons = Array.from(
      root.querySelectorAll('[data-testid^="reports-filter-period-"]'),
    );
    const customButton = periodButtons.find(
      (button) => button.getAttribute('data-testid') === 'reports-filter-period-custom',
    ) as HTMLButtonElement | undefined;
    expect(customButton).toBeDefined();
    customButton!.click();
    fixture.detectChanges();

    const picker = root.querySelector('[data-testid="reports-filter-custom-range"]');
    expect(picker).not.toBeNull();
    expect(picker?.querySelector('[data-testid="q-date-range-picker-start"]')).not.toBeNull();

    const startInput = picker!.querySelector(
      '[data-testid="q-date-range-picker-start"]',
    ) as HTMLInputElement;
    startInput.value = '2026-01-01';
    startInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const shell = fixture.componentInstance as unknown as {
      state: { customRange: () => { from: string; to: string } };
    };
    expect(shell.state.customRange().from).toBe('2026-01-01');
  });
});

@Component({
  template: '<input type="search" data-testid="dummy-search" />',
})
class DummySearchTab {}

@Component({
  template:
    '<a routerLink="/statistics/exports" data-testid="order-reports-export-link">Export</a>',
  imports: [RouterLink],
})
class DummyOrdersTab {}

describe('ReportsShell: the keys (statistics.md §1.4, gap map row X.1)', () => {
  let fixture: ComponentFixture<ReportsShell>;
  let router: Router;
  let state: ReportsFilterState;

  async function render(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ReportsShell],
      providers: [
        provideRouter([
          {
            path: 'statistics',
            children: [
              { path: 'overview', component: DummyTabComponent },
              { path: 'orders', component: DummyOrdersTab },
              { path: 'branches', component: DummyTabComponent },
              { path: 'couriers', component: DummyTabComponent },
              { path: 'products', component: DummyTabComponent },
              { path: 'marketing', component: DummySearchTab },
              { path: 'forecast', component: DummyTabComponent },
              { path: 'exports', component: DummyTabComponent },
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
    state = fixture.debugElement.injector.get(ReportsFilterState);
    fixture.detectChanges();
  }

  function key(value: string, init: KeyboardEventInit = {}): boolean {
    const event = new KeyboardEvent('keydown', {
      key: value,
      bubbles: true,
      cancelable: true,
      ...init,
    });
    Object.defineProperty(event, 'target', { value: document.body });
    return TestBed.inject(ShortcutRegistry).dispatch(event);
  }

  async function open(path: string): Promise<void> {
    await render();
    await router.navigateByUrl(path);
    fixture.detectChanges();
  }

  it('is on the cheat-sheet with the keys of statistics.md §1.4', async () => {
    await open('/statistics/overview');
    const scope = TestBed.inject(ShortcutRegistry)
      .scopes()
      .find((candidate) => candidate.id === 'reports')!;

    expect(scope.title()).toBe('Reports');
    expect(scope.shortcuts.map((shortcut) => shortcut.caps.join(' '))).toEqual([
      'd',
      'w',
      'm',
      '[',
      ']',
      'g o',
      'g s',
      'g b',
      'g c',
      'g p',
      'g m',
      '/',
      'e',
      'Esc',
    ]);
  });

  it('d, w and m set the period by pressing the pill the bar draws', async () => {
    await open('/statistics/overview');

    expect(key('w')).toBe(true);
    expect(state.period()).toBe('7d');
    expect(key('m')).toBe(true);
    expect(state.period()).toBe('month');
    expect(key('d')).toBe(true);
    expect(state.period()).toBe('today');
  });

  it('[ and ] shift the period by its own length, back and forward', async () => {
    await open('/statistics/overview');
    key('w');
    const before = state.range();

    expect(key('[')).toBe(true);
    const back = state.range();
    expect(state.period()).toBe('custom');
    expect(back.to < before.from).toBe(true);

    expect(key(']')).toBe(true);
    expect(state.range()).toEqual(before);
  });

  it('g then a letter goes to that report through the tab the nav already draws', async () => {
    await open('/statistics/overview');

    expect(key('g')).toBe(true);
    expect(key('s')).toBe(true);
    await fixture.whenStable();
    expect(router.url).toBe('/statistics/orders');

    key('g');
    key('m');
    await fixture.whenStable();
    expect(router.url).toBe('/statistics/marketing');

    key('g');
    key('o');
    await fixture.whenStable();
    expect(router.url).toBe('/statistics/overview');
  });

  it('keeps the period keys off the tabs that hide the bar, where there is no pill to press', async () => {
    await open('/statistics/forecast');

    expect(key('d')).toBe(false);
    expect(key('[')).toBe(false);
  });

  it('/ goes to the view’s search box where it has one, and nowhere else', async () => {
    await open('/statistics/overview');
    expect(key('/')).toBe(false);

    await router.navigateByUrl('/statistics/marketing');
    fixture.detectChanges();
    expect(key('/')).toBe(true);
    expect(document.activeElement?.getAttribute('data-testid')).toBe('dummy-search');
  });

  it('e goes to the export from the view that links it, and is not offered on one that does not', async () => {
    await open('/statistics/overview');
    expect(key('e')).toBe(false);

    await router.navigateByUrl('/statistics/orders');
    fixture.detectChanges();
    expect(key('e')).toBe(true);
    await fixture.whenStable();
    expect(router.url).toBe('/statistics/exports');
  });

  it('Esc clears the slice, once, and has nothing to do when there is none', async () => {
    await open('/statistics/overview');
    expect(key('Escape'), 'no slice yet').toBe(false);

    state.setFulfilmentType('DELIVERY');
    state.setGranularity('week');
    expect(key('Escape')).toBe(true);
    expect(state.fulfilmentType()).toBe('ALL');
    expect(state.granularity()).toBe('day');
    expect(key('Escape')).toBe(false);
  });
});
