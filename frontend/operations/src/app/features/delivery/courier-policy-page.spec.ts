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

  async function render(api: Partial<CouriersApi>): Promise<void> {
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
  }

  it('renders the resolved policy with its winning scope', async () => {
    await render({ policy: () => Promise.resolve(POLICY) });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="policy-scope"]')?.textContent).toContain('TENANT');
    // Thin-space grouped, orders.md §1.3's own UZS convention — never comma-grouped.
    expect(host.textContent?.replace(/\s/g, '')).toContain('5000000');
    expect(host.textContent).toContain('30');
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
