import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView } from '../../core/api/configuration';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { CourierPolicyView, CouriersApi } from '../couriers/couriers-api';
import { I18n } from '../../core/i18n/i18n';
import { ConfigurationApi } from '../settings/configuration-api';
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
  gpsVerificationEnabled: false,
  gpsAcceptRadiusMeters: 1000,
  gpsStatusChangeRadiusMeters: 150,
  kitchenReadyOnly: false,
  revealCustomerLocationTiming: 'AFTER_ACCEPT',
  postDeliveryPaymentCheckRequired: false,
  winningScope: 'TENANT',
  policyId: '00000000-0000-0000-0000-000000000042',
  policyVersion: 1,
};

const OUT_OF_ZONE_RESOLUTION: ConfigurationResolutionView = {
  keyCode: 'delivery.out_of_zone_policy',
  value: 'REJECT',
  cameFromDefault: true,
  source: 'CODE_DEFAULT',
  winningScope: null,
  inspectedLevels: [],
  describe: 'code default',
  currentVersionAtScope: null,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CourierPolicyPage', () => {
  let fixture: ComponentFixture<CourierPolicyPage>;

  async function render(
    api: Partial<CouriersApi>,
    configApi: Partial<ConfigurationApi> = {
      resolution: () => Promise.resolve(OUT_OF_ZONE_RESOLUTION),
    },
  ): Promise<HTMLElement> {
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
        { provide: ConfigurationApi, useValue: configApi },
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

  // Wave P38: the five/six fields couriers.md §16 named with no document
  // field before this wave now render as ordinary rows.
  it('renders the new GPS, kitchen-ready, reveal-timing and payment-check rows', async () => {
    const host = await render({
      policy: () =>
        Promise.resolve({
          ...POLICY,
          gpsVerificationEnabled: true,
          gpsAcceptRadiusMeters: 2000,
          gpsStatusChangeRadiusMeters: 120,
          kitchenReadyOnly: true,
          revealCustomerLocationTiming: 'BEFORE_ACCEPT',
          postDeliveryPaymentCheckRequired: true,
        }),
    });

    expect(
      host.querySelector('[data-testid="policy-row-kitchenReadyOnly"]')?.textContent,
    ).toContain('Yes');
    expect(
      host.querySelector('[data-testid="policy-row-revealCustomerLocationTiming"]')?.textContent,
    ).toContain('Before accept');
    expect(
      host.querySelector('[data-testid="policy-row-postDeliveryPaymentCheckRequired"]')
        ?.textContent,
    ).toContain('Yes');
  });

  // couriers.md §16 / settings.md §10.13: courier billing mode is refused by
  // ADR 0042, not missing — it must render disabled with its reason, never
  // silently absent.
  it('renders courier billing mode as a fixed, refused row rather than an editable field', async () => {
    const host = await render({ policy: () => Promise.resolve(POLICY) });

    const row = host.querySelector('[data-testid="policy-row-billingMode"]');
    expect(row).not.toBeNull();
    expect(row!.textContent).toContain('Refused by ADR 0042');
    expect(row!.querySelector('input, select')).toBeNull();
  });

  // The telemetry collection gate is a registered ADR 0030 key, but not
  // tenant-visible — it renders as platform-only, not as a missing feature.
  it('renders the telemetry collection gate as platform-only, with no tenant-scoped fetch attempted', async () => {
    const host = await render({ policy: () => Promise.resolve(POLICY) });

    const row = host.querySelector('[data-testid="policy-row-telemetryGate"]');
    expect(row).not.toBeNull();
    expect(row!.textContent).toContain('Platform-only');
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
        { provide: ConfigurationApi, useValue: { resolution: vi.fn() } },
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

  // Wave P38: the write path this screen's own doc comment said "lands in P38".
  it('publishes a whole-document write and shows the updated policy', async () => {
    const writePolicy = vi.fn().mockResolvedValue({
      ...POLICY,
      shiftEnforcement: 'ENFORCED',
      gpsVerificationEnabled: true,
      gpsAcceptRadiusMeters: 500,
      policyVersion: 2,
    });
    const host = await render({
      policy: () => Promise.resolve(POLICY),
      writePolicy,
    });

    // The "Edit" button on the courier-policy card (the first one on the page).
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Edit')!
      .click();
    fixture.detectChanges();

    host.querySelector<HTMLSelectElement>('[data-testid="policy-input-shiftEnforcement"]')!.value =
      'ENFORCED';
    host
      .querySelector<HTMLSelectElement>('[data-testid="policy-input-shiftEnforcement"]')!
      .dispatchEvent(new Event('change'));

    const gpsCheckbox = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-gpsVerificationEnabled"] input',
    )!;
    gpsCheckbox.checked = true;
    gpsCheckbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const acceptKm = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-gpsAcceptRadiusKm"]',
    )!;
    acceptKm.value = '0.5';
    acceptKm.dispatchEvent(new Event('input'));

    const reason = host.querySelector<HTMLInputElement>('[data-testid="policy-input-reason"]')!;
    reason.value = 'Tightened for the pilot branch';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    host.querySelector<HTMLButtonElement>('[data-testid="policy-publish"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(writePolicy).toHaveBeenCalledTimes(1);
    const [tenantId, input] = writePolicy.mock.calls[0];
    expect(tenantId).toBe('t1');
    expect(input.shiftEnforcement).toBe('ENFORCED');
    expect(input.gpsVerificationEnabled).toBe(true);
    // 0.5 km entered, must publish as 500 metres — the unit conversion this
    // wave's own doc names (settings.md §10.13 Card 3's unit asymmetry).
    expect(input.gpsAcceptRadiusMeters).toBe(500);
    expect(input.reason).toBe('Tightened for the pilot branch');
    expect(host.querySelector('[data-testid="policy-input-reason"]')).toBeNull();
  });

  // Row 10.13 Card 1: the out-of-zone address policy, a registered ADR 0030
  // ConfigurationKey rendered on the same screen.
  it('renders and saves the out-of-zone address policy', async () => {
    const setValue = vi.fn().mockResolvedValue({
      id: 'v1',
      keyCode: 'delivery.out_of_zone_policy',
      scopeType: 'LOCATION',
      value: 'OFFER_PICKUP',
      explicitNull: false,
      version: 1,
    });
    const host = await render(
      { policy: () => Promise.resolve(POLICY) },
      { resolution: () => Promise.resolve(OUT_OF_ZONE_RESOLUTION), setValue },
    );

    expect(host.querySelector('[data-testid="policy-outOfZone-value"]')?.textContent).toContain(
      'Reject',
    );

    const card = host.querySelector('[data-testid="policy-outOfZone-card"]')!;
    Array.from(card.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Edit')!
      .click();
    fixture.detectChanges();

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="policy-outOfZone-select"]',
    )!;
    select.value = 'OFFER_PICKUP';
    select.dispatchEvent(new Event('change'));

    const reasonInputs = card.querySelectorAll<HTMLInputElement>('input[type="text"]');
    const reasonInput = reasonInputs[reasonInputs.length - 1];
    reasonInput.value = 'Offer pickup instead of refusing outright';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    host.querySelector<HTMLButtonElement>('[data-testid="policy-outOfZone-save"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(setValue).toHaveBeenCalledTimes(1);
    const [tenantId, code, input] = setValue.mock.calls[0];
    expect(tenantId).toBe('t1');
    expect(code).toBe('delivery.out_of_zone_policy');
    expect(input.stringValue).toBe('OFFER_PICKUP');
    expect(input.scopeType).toBe('LOCATION');
  });
});
