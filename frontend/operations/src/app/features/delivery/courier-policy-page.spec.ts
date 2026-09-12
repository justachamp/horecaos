import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { CouriersApi, CourierPolicyView } from '../couriers/couriers-api';
import { I18n } from '../../core/i18n/i18n';
import { CourierPolicyPage } from './courier-policy-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const POLICY: CourierPolicyView = {
  reverificationDays: 180,
  warningDays: 30,
  settlementPeriodDays: 14,
  cashCeilingMinor: 5_000_000,
  penaltyApprovalThresholdMinor: 200_000,
  shiftEnforcement: 'ADVISORY',
  graceSeconds: 300,
  confirmationPointRetentionDays: 30,
  winningScope: 'TENANT',
  policyId: '00000000-0000-0000-0000-000000000042',
  policyVersion: 1,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CourierPolicyPage', () => {
  let fixture: ComponentFixture<CourierPolicyPage>;

  async function render(api: Partial<CouriersApi>): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [CourierPolicyPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the resolved policy with its winning scope', async () => {
    const host = await render({ policy: () => Promise.resolve(POLICY) });

    expect(host.querySelector('[data-testid="policy-scope"]')?.textContent).toContain('TENANT');
    // Thin-space grouped, orders.md §1.3's own UZS convention — never comma-grouped.
    expect(host.textContent?.replace(/\s/g, '')).toContain('5000000');
    expect(host.textContent).toContain('30');
  });

  // Row 3.9: "renders neither policyId nor policyVersion although both are
  // on the wire".
  it('renders the policy identity — policyId and policyVersion', async () => {
    const host = await render({ policy: () => Promise.resolve(POLICY) });

    const identity = host.querySelector('[data-testid="policy-identity"]')?.textContent ?? '';
    expect(identity).toContain('00000000-0000-0000-0000-000000000042');
    expect(identity).toContain('1');
  });

  // Row 3.9: "the resolution scope selector (tenant, brand, location) so a
  // manager can compare an override against the default, the inherited
  // value beside each overridden field".
  it('shows the inherited value beside an overridden field, and re-resolves when the scope selector changes', async () => {
    const tenantPolicy: CourierPolicyView = { ...POLICY, winningScope: 'TENANT' };
    const brandPolicy: CourierPolicyView = { ...POLICY, winningScope: 'TENANT' };
    const locationPolicy: CourierPolicyView = {
      ...POLICY,
      cashCeilingMinor: 3_000_000,
      winningScope: 'LOCATION',
      policyId: '00000000-0000-0000-0000-000000000099',
      policyVersion: 2,
    };
    const policy = vi
      .fn<(tenantId: string, brandId?: string, locationId?: string) => Promise<CourierPolicyView>>()
      .mockImplementation(async (_tenantId, brandId, locationId) => {
        if (locationId) {
          return locationPolicy;
        }
        if (brandId) {
          return brandPolicy;
        }
        return tenantPolicy;
      });

    const host = await render({ policy });

    // Default resolution level is LOCATION, compared against its parent, BRAND.
    expect(policy).toHaveBeenCalledWith('t1', 'b1', 'l1');
    expect(policy).toHaveBeenCalledWith('t1', 'b1');
    const row = host.querySelector('[data-testid="policy-row-cashCeilingMinor"]')!;
    expect(row.querySelector('q-status-pill')).not.toBeNull();
    expect(row.textContent?.replace(/\s/g, '')).toContain('3000000');
    expect(
      row.querySelector('[data-testid="policy-row-inherited"]')?.textContent?.replace(/\s/g, ''),
    ).toContain('5000000');
    // A field neither level overrides carries no inherited annotation.
    expect(
      host
        .querySelector('[data-testid="policy-row-warningDays"]')
        ?.querySelector('[data-testid="policy-row-inherited"]'),
    ).toBeNull();

    policy.mockClear();
    host.querySelector<HTMLButtonElement>('[data-testid="policy-scope-option-TENANT"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    // TENANT has no level above it — resolved alone, no inherited comparison at all.
    expect(policy).toHaveBeenCalledWith('t1');
    expect(policy).not.toHaveBeenCalledWith('t1', 'b1', 'l1');
    expect(host.querySelector('[data-testid="policy-row-inherited"]')).toBeNull();
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [CourierPolicyPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: { policy: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="policy-denied"]'),
    ).not.toBeNull();
  });
});
