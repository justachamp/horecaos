import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView } from '../../core/api/configuration';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { applyRegionalFormats, resetRegionalFormats } from '../../core/format/regional-format';
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
  onlineWithinMinutes: 10,
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

  afterEach(() => resetRegionalFormats());

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

  // Row 10.12: the Formats card says it controls how operators read amounts across the console.
  it('groups the money rows the way the brand chose, not always with a no-break space', async () => {
    applyRegionalFormats({ moneyGrouping: 'COMMA' });
    const host = await render({ policy: () => Promise.resolve(POLICY) });

    const ceiling = host.querySelector('[data-testid="policy-row-cashCeilingMinor"]')?.textContent;
    const threshold = host.querySelector(
      '[data-testid="policy-row-penaltyApprovalThresholdMinor"]',
    )?.textContent;
    expect(ceiling).toContain('5,000,000');
    expect(threshold).toContain('200,000');
  });

  // Row 3.3: onlineWithinMinutes is genuinely enforced (the roster reads it),
  // so unlike the four fields above it stays an ordinary editable row.
  it('renders and edits onlineWithinMinutes as an ordinary field, not a locked one', async () => {
    const writePolicy = vi
      .fn()
      .mockResolvedValue({ ...POLICY, onlineWithinMinutes: 3, policyVersion: 2 });
    const host = await render({ policy: () => Promise.resolve(POLICY), writePolicy });

    const row = host.querySelector('[data-testid="policy-row-onlineWithinMinutes"]')!;
    expect(row.textContent).toContain('10');
    expect(row.querySelector('q-status-pill')).toBeNull();

    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Edit')!
      .click();
    fixture.detectChanges();

    const input = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-onlineWithinMinutes"]',
    )!;
    input.value = '3';
    input.dispatchEvent(new Event('input'));

    const reason = host.querySelector<HTMLInputElement>('[data-testid="policy-input-reason"]')!;
    reason.value = 'tighten the online threshold';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    host.querySelector<HTMLButtonElement>('[data-testid="policy-publish"]')!.click();
    await flushMicrotasks();

    expect(writePolicy).toHaveBeenCalledTimes(1);
    expect(writePolicy.mock.calls[0][1].onlineWithinMinutes).toBe(3);
  });

  // Row 3.9: "renders neither policyId nor policyVersion although both are
  // on the wire".
  it('renders the policy identity — policyId and policyVersion', async () => {
    const host = await render({ policy: () => Promise.resolve(POLICY) });

    const identity = host.querySelector('[data-testid="policy-identity"]')?.textContent ?? '';
    expect(identity).toContain('00000000-0000-0000-0000-000000000042');
    expect(identity).toContain('1');
  });

  const ENFORCED_POLICY: CourierPolicyView = {
    ...POLICY,
    gpsVerificationEnabled: true,
    gpsAcceptRadiusMeters: 2000,
    gpsStatusChangeRadiusMeters: 120,
    kitchenReadyOnly: true,
    revealCustomerLocationTiming: 'BEFORE_ACCEPT',
    postDeliveryPaymentCheckRequired: true,
  };

  async function openEditor(host: HTMLElement): Promise<void> {
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Edit')!
      .click();
    fixture.detectChanges();
  }

  async function publishWithReason(host: HTMLElement): Promise<void> {
    const reason = host.querySelector<HTMLInputElement>('[data-testid="policy-input-reason"]')!;
    reason.value = 'Pilot branch is going live';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('[data-testid="policy-publish"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  // Row 3.9 (batch 18): the courier app's endpoints read these four switches where the courier
  // acts, so they are ordinary rows with a consequence line, not locked ones with a reason why
  // nothing reads them.
  it('renders the GPS, kitchen-ready, reveal-timing and payment-check rows as enforced, each with its consequence line', async () => {
    const host = await render({ policy: () => Promise.resolve(ENFORCED_POLICY) });

    const kitchenReadyOnly = host.querySelector('[data-testid="policy-row-kitchenReadyOnly"]')!;
    expect(kitchenReadyOnly.textContent).toContain('Yes');
    expect(kitchenReadyOnly.textContent).toContain('only orders the kitchen has finished');

    const revealTiming = host.querySelector(
      '[data-testid="policy-row-revealCustomerLocationTiming"]',
    )!;
    expect(revealTiming.textContent).toContain('Before accept');
    expect(revealTiming.textContent).toContain('written to the audit log');

    const paymentCheck = host.querySelector(
      '[data-testid="policy-row-postDeliveryPaymentCheckRequired"]',
    )!;
    expect(paymentCheck.textContent).toContain('Yes');
    expect(paymentCheck.textContent).toContain('cannot mark a cash order delivered');

    const gps = host.querySelector('[data-testid="policy-row-gpsVerificationEnabled"]')!;
    expect(gps.textContent?.replace(/\s/g, '')).toContain('2km');
    expect(gps.textContent).toContain('120');
    expect(gps.textContent).toContain('farther from the branch than the accept radius');

    for (const key of [
      'kitchenReadyOnly',
      'revealCustomerLocationTiming',
      'postDeliveryPaymentCheckRequired',
      'gpsVerificationEnabled',
    ]) {
      const row = host.querySelector(`[data-testid="policy-row-${key}"]`)!;
      expect(row.textContent).not.toContain('Not yet enforced');
      expect(row.querySelector('input, select')).toBeNull();
    }
  });

  it('shows the GPS gate as off when the master toggle is off, whatever the stored radii are', async () => {
    const host = await render({
      policy: () => Promise.resolve({ ...POLICY, gpsAcceptRadiusMeters: 2000 }),
    });

    const gps = host.querySelector('[data-testid="policy-row-gpsVerificationEnabled"]')!;
    expect(gps.textContent).toContain('No');
    expect(gps.textContent).not.toContain('km');
  });

  it('flags a location override of the GPS gate against its parent, comparing the radii as well as the toggle', async () => {
    const parent = { ...POLICY, gpsVerificationEnabled: true, gpsStatusChangeRadiusMeters: 150 };
    const child = { ...parent, gpsStatusChangeRadiusMeters: 60, winningScope: 'LOCATION' };
    const policy = vi
      .fn<(tenantId: string, brandId?: string, locationId?: string) => Promise<CourierPolicyView>>()
      .mockImplementation(async (_tenantId, _brandId, locationId) => (locationId ? child : parent));

    const host = await render({ policy });

    const gps = host.querySelector('[data-testid="policy-row-gpsVerificationEnabled"]')!;
    expect(gps.querySelector('q-status-pill')).not.toBeNull();
    expect(gps.querySelector('[data-testid="policy-row-inherited"]')?.textContent).toContain('150');
    expect(gps.textContent).toContain('60');
  });

  it('offers a control for each of the four switches and publishes what the operator chose', async () => {
    const writePolicy = vi.fn().mockResolvedValue({ ...ENFORCED_POLICY, policyVersion: 2 });
    const host = await render({ policy: () => Promise.resolve(POLICY), writePolicy });
    await openEditor(host);

    const kitchen = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-kitchenReadyOnly"]',
    )!;
    kitchen.checked = true;
    kitchen.dispatchEvent(new Event('change'));

    const reveal = host.querySelector<HTMLSelectElement>(
      '[data-testid="policy-input-revealTiming"]',
    )!;
    reveal.value = 'BEFORE_ACCEPT';
    reveal.dispatchEvent(new Event('change'));

    const payment = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-postDeliveryPaymentCheckRequired"]',
    )!;
    payment.checked = true;
    payment.dispatchEvent(new Event('change'));

    const gps = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-gpsVerificationEnabled"]',
    )!;
    gps.checked = true;
    gps.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const accept = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-gpsAcceptRadiusKm"]',
    )!;
    accept.value = '2';
    accept.dispatchEvent(new Event('input'));
    const status = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-gpsStatusChangeRadiusMeters"]',
    )!;
    status.value = '120';
    status.dispatchEvent(new Event('input'));

    await publishWithReason(host);

    expect(writePolicy).toHaveBeenCalledTimes(1);
    const input = writePolicy.mock.calls[0][1];
    expect(input.kitchenReadyOnly).toBe(true);
    expect(input.revealCustomerLocationTiming).toBe('BEFORE_ACCEPT');
    expect(input.postDeliveryPaymentCheckRequired).toBe(true);
    expect(input.gpsVerificationEnabled).toBe(true);
    // Kilometres in the form, metres on the wire.
    expect(input.gpsAcceptRadiusMeters).toBe(2000);
    expect(input.gpsStatusChangeRadiusMeters).toBe(120);
  });

  it("shows each switch's consequence line beside its control while editing", async () => {
    const host = await render({ policy: () => Promise.resolve(POLICY), writePolicy: vi.fn() });
    await openEditor(host);

    const form = host.querySelector('.form')!;
    expect(form.textContent).toContain('only orders the kitchen has finished');
    expect(form.textContent).toContain('written to the audit log');
    expect(form.textContent).toContain('cannot mark a cash order delivered');
    expect(form.textContent).toContain('positions are not checked');
  });

  it('asks for the two radii only while the GPS gate is on, and will not publish a radius of zero', async () => {
    const writePolicy = vi.fn().mockResolvedValue(POLICY);
    const host = await render({ policy: () => Promise.resolve(POLICY), writePolicy });
    await openEditor(host);

    expect(host.querySelector('[data-testid="policy-input-gpsAcceptRadiusKm"]')).toBeNull();
    expect(
      host.querySelector('[data-testid="policy-input-gpsStatusChangeRadiusMeters"]'),
    ).toBeNull();

    const gps = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-gpsVerificationEnabled"]',
    )!;
    gps.checked = true;
    gps.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const status = host.querySelector<HTMLInputElement>(
      '[data-testid="policy-input-gpsStatusChangeRadiusMeters"]',
    )!;
    status.value = '0';
    status.dispatchEvent(new Event('input'));
    const reason = host.querySelector<HTMLInputElement>('[data-testid="policy-input-reason"]')!;
    reason.value = 'Pilot branch is going live';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="policy-gps-radius-error"]')).not.toBeNull();
    expect(host.querySelector<HTMLButtonElement>('[data-testid="policy-publish"]')!.disabled).toBe(
      true,
    );

    // Switching the gate off again makes the radii irrelevant, so a zero in the hidden field
    // must not hold the publish hostage.
    gps.checked = false;
    gps.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(host.querySelector<HTMLButtonElement>('[data-testid="policy-publish"]')!.disabled).toBe(
      false,
    );
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

  // Wave P38 built the write path; this wave (row 3.9) adds the If-Match
  // concurrency token it was missing.
  it('publishes a whole-document write with If-Match carrying the read version, and shows the updated policy', async () => {
    const writePolicy = vi.fn().mockResolvedValue({
      ...POLICY,
      shiftEnforcement: 'ENFORCED',
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

    const reason = host.querySelector<HTMLInputElement>('[data-testid="policy-input-reason"]')!;
    reason.value = 'Tightened for the pilot branch';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    host.querySelector<HTMLButtonElement>('[data-testid="policy-publish"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(writePolicy).toHaveBeenCalledTimes(1);
    const [tenantId, input, expectedVersion] = writePolicy.mock.calls[0];
    expect(tenantId).toBe('t1');
    expect(input.shiftEnforcement).toBe('ENFORCED');
    // An untouched switch is sent back as it was read: the whole document is republished.
    expect(input.gpsVerificationEnabled).toBe(POLICY.gpsVerificationEnabled);
    expect(input.gpsAcceptRadiusMeters).toBe(POLICY.gpsAcceptRadiusMeters);
    expect(input.gpsStatusChangeRadiusMeters).toBe(POLICY.gpsStatusChangeRadiusMeters);
    expect(input.kitchenReadyOnly).toBe(POLICY.kitchenReadyOnly);
    expect(input.revealCustomerLocationTiming).toBe(POLICY.revealCustomerLocationTiming);
    expect(input.postDeliveryPaymentCheckRequired).toBe(POLICY.postDeliveryPaymentCheckRequired);
    expect(input.reason).toBe('Tightened for the pilot branch');
    // ADR 0031: If-Match carries the version this screen's own read resolved,
    // so a concurrent editor's write in another tab surfaces as STALE_VERSION
    // instead of being silently overwritten.
    expect(expectedVersion).toBe(POLICY.policyVersion);
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
