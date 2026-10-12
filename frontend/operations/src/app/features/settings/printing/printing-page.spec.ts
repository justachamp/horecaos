import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { KitchenApi } from '../../kitchen/kitchen-api';
import { ConfigurationApi } from '../configuration-api';
import { SettingsScope } from '../settings-scope';
import { PrintingApi } from './printing-api';
import { el, flush } from './printing-dom';
import { agent, printer, profile, route, station } from './printing-fixtures';
import { PrintingPage } from './printing-page';

class FakeTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

class FakeScope {
  readonly brandId = signal<string | null>('b1');
  readonly locationId = signal<string | null>('l1');
}

class FakeFlags {
  constructor(private readonly on: boolean) {}
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
  isOn = (code: string): boolean => this.on && code === 'feature.printing';
}

function fakeApi(overrides: Partial<PrintingApi> = {}): Partial<PrintingApi> {
  return {
    printers: vi.fn().mockResolvedValue([printer()]),
    routes: vi.fn().mockResolvedValue([route()]),
    agents: vi.fn().mockResolvedValue([agent()]),
    posExportActive: vi.fn().mockResolvedValue(false),
    receiptProfile: vi.fn().mockResolvedValue(profile()),
    jobs: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    ...overrides,
  };
}

async function render(
  api: Partial<PrintingApi>,
  options: {
    scope?: FakeScope;
    tenant?: FakeTenant;
    flagsOn?: boolean;
    stations?: ReturnType<typeof vi.fn>;
  } = {},
): Promise<ComponentFixture<PrintingPage>> {
  await TestBed.configureTestingModule({
    imports: [PrintingPage],
    providers: [
      { provide: PrintingApi, useValue: api },
      {
        provide: ConfigurationApi,
        useValue: {
          resolution: vi.fn().mockResolvedValue({
            keyCode: 'printing.document_locale',
            value: 'ru',
            cameFromDefault: true,
            source: 'CODE_DEFAULT',
            winningScope: null,
            inspectedLevels: [],
            describe: '',
            currentVersionAtScope: null,
          }),
          setValue: vi.fn(),
        },
      },
      { provide: CurrentTenant, useValue: options.tenant ?? new FakeTenant() },
      { provide: SettingsScope, useValue: options.scope ?? new FakeScope() },
      { provide: FeatureFlags, useValue: new FakeFlags(options.flagsOn ?? true) },
      {
        provide: KitchenApi,
        useValue: { stations: options.stations ?? vi.fn().mockResolvedValue([station()]) },
      },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(PrintingPage);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('PrintingPage', () => {
  it('opens with the line that matters: nothing prints until a route is added', async () => {
    const fixture = await render(fakeApi());
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Nothing prints at a branch until you add a route',
    );
  });

  it('reads the branch’s printers, routes and agents and hands each card what it needs', async () => {
    const api = fakeApi();
    const fixture = await render(api);

    expect(api.printers).toHaveBeenCalledWith({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    expect(el(fixture, '[data-printer="printer-1"]')).not.toBeNull();
    expect(el(fixture, '[data-route="route-1"]')).not.toBeNull();
    expect(el(fixture, '[data-agent="agent-1"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-profile"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-queue"]')).not.toBeNull();
  });

  it('asks for a branch when none is chosen, and still shows the brand’s receipt footer', async () => {
    const scope = new FakeScope();
    scope.locationId.set(null);
    const api = fakeApi();
    const fixture = await render(api, { scope });

    expect(el(fixture, '[data-testid="print-page-pick-branch"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-printers"]')).toBeNull();
    expect(el(fixture, '[data-testid="print-profile"]')).not.toBeNull();
    expect(api.printers).not.toHaveBeenCalled();
  });

  it('says printing is not switched on while the flag is off, and still shows what exists', async () => {
    const fixture = await render(fakeApi(), { flagsOn: false });
    expect(el(fixture, '[data-testid="print-page-off"]').textContent).toContain('not switched on');
    expect(el(fixture, '[data-testid="print-printers"]')).not.toBeNull();
  });

  it('shows no banner when the flag is on', async () => {
    const fixture = await render(fakeApi());
    expect(el(fixture, '[data-testid="print-page-off"]')).toBeNull();
  });

  it('keeps the page when the station list cannot be read, and still passes the till statement on', async () => {
    const api = fakeApi({ posExportActive: vi.fn().mockResolvedValue(true) });
    const fixture = await render(api, {
      stations: vi.fn().mockRejectedValue(new Error('no kitchen access')),
    });
    expect(el(fixture, '[data-printer="printer-1"]')).not.toBeNull();
    expect(el(fixture, '[data-route="route-1"]').textContent).toContain(
      'point of sale may already print this',
    );
  });

  it('is denied for a manager the platform refuses', async () => {
    const api = fakeApi({
      printers: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });
    const fixture = await render(api);
    expect(el(fixture, '[data-testid="print-page-denied"]')).not.toBeNull();
  });

  it('reads the lists again after a card changes something', async () => {
    const api = fakeApi();
    const fixture = await render(api);
    expect(api.printers).toHaveBeenCalledTimes(1);

    (fixture.componentInstance as unknown as { reload(): void }).reload();
    await flush();

    expect(api.printers).toHaveBeenCalledTimes(2);
    expect(api.routes).toHaveBeenCalledTimes(2);
  });
});
