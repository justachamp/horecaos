import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { DeliveryTariffsApi } from '../../delivery/delivery-tariffs-api';
import { PaidHoldApi } from '../../kitchen/paid-hold-api';
import { ConfigurationApi } from '../configuration-api';
import { LocationsApi } from '../locations/locations-api';
import { SalesChannelsApi } from '../sales-channels/sales-channels-api';
import { OrderPolicyNeighboursCard } from './order-policy-neighbours-card';

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface Fakes {
  tariffs?: unknown;
  summary?: unknown;
  resolution?: unknown;
  hold?: unknown;
  channels?: unknown;
}

async function mount(
  inputs: { scopeType: 'TENANT' | 'BRAND' | 'LOCATION'; locationId: string | null },
  fakes: Fakes = {},
): Promise<{
  fixture: ComponentFixture<OrderPolicyNeighboursCard>;
  summary: ReturnType<typeof vi.fn>;
}> {
  const summary = vi.fn().mockResolvedValue(fakes.summary ?? { maxConcurrentOrders: 7 });
  await TestBed.configureTestingModule({
    imports: [OrderPolicyNeighboursCard],
    providers: [
      provideRouter([]),
      {
        provide: DeliveryTariffsApi,
        useValue: { list: () => Promise.resolve(fakes.tariffs ?? []) },
      },
      { provide: LocationsApi, useValue: { serviceSummary: summary } },
      {
        provide: ConfigurationApi,
        useValue: {
          resolution: () => Promise.resolve(fakes.resolution ?? { value: 'OFFER_PICKUP' }),
        },
      },
      {
        provide: PaidHoldApi,
        useValue: { policy: () => Promise.resolve(fakes.hold ?? { mode: 'SHADOW' }) },
      },
      {
        provide: SalesChannelsApi,
        useValue: { list: () => Promise.resolve(fakes.channels ?? []) },
      },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(OrderPolicyNeighboursCard);
  fixture.componentRef.setInput('tenantId', 't1');
  fixture.componentRef.setInput('scopeType', inputs.scopeType);
  fixture.componentRef.setInput('brandId', 'b1');
  fixture.componentRef.setInput('locationId', inputs.locationId);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return { fixture, summary };
}

const value = (fixture: ComponentFixture<OrderPolicyNeighboursCard>, id: string): string =>
  (
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="neighbour-value-${id}"]`)
      ?.textContent ?? ''
  ).trim();

/**
 * ADR 0177: the settings the information architecture lists on the order policy that live on a screen of
 * their own, as read-only echoes. The page spec covers the whole card in place; these cover the branches
 * of each read.
 */
describe('OrderPolicyNeighboursCard', () => {
  it('reads a branch’s own order limit when the bar is on that branch', async () => {
    const { fixture, summary } = await mount({ scopeType: 'LOCATION', locationId: 'loc-1' });

    expect(summary).toHaveBeenCalledWith({ tenantId: 't1', brandId: 'b1', locationId: 'loc-1' });
    expect(value(fixture, 'branchLimit')).toBe('7');
  });

  it('says a branch with no limit has none', async () => {
    const { fixture } = await mount(
      { scopeType: 'LOCATION', locationId: 'loc-1' },
      { summary: { maxConcurrentOrders: null } },
    );

    expect(value(fixture, 'branchLimit')).toBe('No limit');
  });

  it('does not read a branch’s limit above the branch level, and says where it is set', async () => {
    const { fixture, summary } = await mount({ scopeType: 'BRAND', locationId: null });

    expect(summary).not.toHaveBeenCalled();
    expect(value(fixture, 'branchLimit')).toContain('Set for each branch');
  });

  it('says the brand has no standard tariff in force when none is', async () => {
    const { fixture } = await mount(
      { scopeType: 'BRAND', locationId: null },
      {
        tariffs: [
          { tariffId: 'x', code: 'OLD', name: 'Old', status: 'ARCHIVED', brandDefault: true },
          { tariffId: 'y', code: 'EXTRA', name: 'Extra', status: 'ACTIVE', brandDefault: false },
        ],
      },
    );

    expect(value(fixture, 'distanceMode')).toBe('No standard tariff in force for this brand');
  });

  it('reads the distance mode of the active standard tariff', async () => {
    const { fixture } = await mount(
      { scopeType: 'BRAND', locationId: null },
      {
        tariffs: [
          {
            tariffId: 'y',
            code: 'STD',
            name: 'Standard',
            status: 'ACTIVE',
            brandDefault: true,
            distanceMode: 'RADIUS',
          },
        ],
      },
    );

    expect(value(fixture, 'distanceMode')).toContain('straight line');
  });

  it('names no call-centre channel when the tenant has none, and lists the order types of the one it has', async () => {
    const none = await mount({ scopeType: 'BRAND', locationId: null });
    expect(value(none.fixture, 'callCentre')).toBe('No active call-centre channel');
    TestBed.resetTestingModule();

    const has = await mount(
      { scopeType: 'BRAND', locationId: null },
      {
        channels: [
          {
            id: 'c',
            code: 'cc',
            systemType: 'CALL_CENTRE',
            status: 'ACTIVE',
            enabledPaymentMethodCount: 3,
            enabledFulfillmentModes: ['DINE_IN'],
          },
          {
            id: 'w',
            code: 'web',
            systemType: 'WEB',
            status: 'ACTIVE',
            enabledPaymentMethodCount: 9,
            enabledFulfillmentModes: [],
          },
        ],
      },
    );
    expect(value(has.fixture, 'callCentre')).toContain('3 payment methods');
    expect(value(has.fixture, 'callCentre')).toContain('Dine-in');
  });
});
