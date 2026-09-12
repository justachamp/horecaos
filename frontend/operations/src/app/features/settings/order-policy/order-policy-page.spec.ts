import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../configuration-api';
import { SettingsScope } from '../settings-scope';
import { AcceptancePolicyResponse, OrderPolicyApi } from './order-policy-api';
import { OrderPolicyPage } from './order-policy-page';

const TENANT_ID = 'tenant-1';
const BRAND_ID = 'brand-1';

const POLICY: AcceptancePolicyResponse = {
  mode: 'RESTAURANT_APPROVAL',
  approvalChannel: 'HORECAOS_OPERATIONS',
  approvalTimeoutSeconds: 300,
  timeoutAction: 'AUTO_REJECT',
  rejectionReasonRequired: true,
  notifyCustomerWhilePending: true,
  isPlatformDefault: false,
  policyId: 'policy-1',
  policyVersion: 7,
};

/** One default value per wave-P46 key, matching `OrderingConfigurationKeys`. */
const CARD2_5_DEFAULTS: Readonly<Record<string, unknown>> = {
  'ordering.business_day_start_hour': 6,
  'ordering.average_order_minutes': 30,
  'ordering.maximum_order_minutes': 60,
  'ordering.late_order_threshold_minutes': 45,
  'ordering.minimum_order_amount_minor': 0,
  'ordering.vat_rate_percent': '12',
  'ordering.routing_poll_interval_minutes': 2,
  'ordering.auto_accept_eligible_channels': 'ALL',
  'ordering.auto_accept_min_prior_orders': 0,
  'ordering.preorder_branch_resolution': 'BY_DISTANCE',
  'ordering.operator_promo_code_allowed': false,
};

function defaultResolution(code: string): ConfigurationResolutionView {
  return {
    keyCode: code,
    value: CARD2_5_DEFAULTS[code],
    cameFromDefault: true,
    source: 'CODE_DEFAULT',
    winningScope: null,
    inspectedLevels: [{ scopeType: 'BRAND', outcome: 'NOT_SET' }],
    describe: `${code} -> CODE_DEFAULT`,
    currentVersionAtScope: null,
  };
}

class FakeSettingsScope {
  readonly brandId = signal<string | null>(BRAND_ID);
  readonly locationId = signal<string | null>(null);
  readonly level = signal<'BRAND' | 'LOCATION'>('BRAND');
  readonly denied = signal(false);
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('OrderPolicyPage', () => {
  let fixture: ComponentFixture<OrderPolicyPage>;
  let policyApi: { getEffective: ReturnType<typeof vi.fn>; publish: ReturnType<typeof vi.fn> };
  let configApi: { resolution: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    policyApi = {
      getEffective: vi.fn().mockResolvedValue(POLICY),
      publish: vi.fn().mockResolvedValue({ ...POLICY, policyVersion: 8 }),
    };
    configApi = {
      resolution: vi
        .fn()
        .mockImplementation((_tenantId: string, code: string) =>
          Promise.resolve(defaultResolution(code)),
        ),
      setValue: vi.fn().mockResolvedValue({
        id: 'value-1',
        keyCode: 'ordering.late_order_threshold_minutes',
        scopeType: 'BRAND',
        value: 20,
        explicitNull: false,
        version: 1,
      }),
    };

    await TestBed.configureTestingModule({
      imports: [OrderPolicyPage],
      providers: [
        { provide: OrderPolicyApi, useValue: policyApi },
        { provide: ConfigurationApi, useValue: configApi },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: SettingsScope, useValue: new FakeSettingsScope() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(OrderPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('reads Card 1 at the scope bar’s own brand/location, not a fixed CurrentLocation', () => {
    expect(policyApi.getEffective).toHaveBeenCalledWith(TENANT_ID, BRAND_ID, null);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Restaurant approval');
    expect(text).toContain('version 7');
  });

  it('reads every Card 2-5 field at the same scope', () => {
    expect(configApi.resolution).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_order_threshold_minutes',
      'BRAND',
      BRAND_ID,
      null,
    );
    expect(configApi.resolution).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.operator_promo_code_allowed',
      'BRAND',
      BRAND_ID,
      null,
    );
  });

  it('renders the eleven card 2-5 fields with their resolved values, not a not-built note', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('notBuilt');
    expect(text).toContain('45'); // late order threshold minutes
    expect(text).toContain('12'); // VAT rate
    expect(text).toContain('Nearest branch'); // BY_DISTANCE
    expect(text).toContain('No'); // operator promo code allowed, boolean false
  });

  it('publishes Card 1 with a required reason', async () => {
    const editButton = fixture.nativeElement.querySelector('.card .primary') as HTMLButtonElement;
    editButton.click();
    fixture.detectChanges();

    const publishButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement;
    expect(publishButton.disabled).toBe(true); // no reason typed yet

    const reasonInput = fixture.nativeElement.querySelector('#policy-reason') as HTMLInputElement;
    reasonInput.value = 'Switching to manual confirmation during peak hours';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(publishButton.disabled).toBe(false);
    publishButton.click();
    await flushMicrotasks();

    expect(policyApi.publish).toHaveBeenCalledWith(
      TENANT_ID,
      BRAND_ID,
      null,
      expect.objectContaining({ reason: 'Switching to manual confirmation during peak hours' }),
    );
  });

  it('overrides a Card 2 integer field at the scope bar’s own scope, with a required reason', async () => {
    const fieldRows = fixture.nativeElement.querySelectorAll('.field-row');
    const lateThresholdRow = fieldRows[3] as HTMLElement; // business day, average, maximum, late
    const overrideButton = lateThresholdRow.querySelector('.field__action') as HTMLButtonElement;
    overrideButton.click();
    fixture.detectChanges();

    const valueInput = fixture.nativeElement.querySelector(
      '[id="field-ordering.late_order_threshold_minutes"]',
    ) as HTMLInputElement;
    valueInput.value = '20';
    valueInput.dispatchEvent(new Event('input'));

    const reasonInput = fixture.nativeElement.querySelector(
      '[id="field-reason-ordering.late_order_threshold_minutes"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Kitchen is faster than the platform default assumed';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);
    saveButton.click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_order_threshold_minutes',
      expect.objectContaining({
        scopeType: 'BRAND',
        brandId: BRAND_ID,
        locationId: null,
        explicitNull: false,
        integerValue: 20,
        reason: 'Kitchen is faster than the platform default assumed',
      }),
    );
  });

  it('reverts a field to the inherited value with no separate reason prompt', async () => {
    // First set it, so the row's own state carries an override to revert.
    configApi.resolution.mockImplementation((_tenantId: string, code: string) => {
      if (code === 'ordering.operator_promo_code_allowed') {
        return Promise.resolve({
          ...defaultResolution(code),
          value: true,
          cameFromDefault: false,
          source: 'SCOPED_VALUE',
          winningScope: 'BRAND',
          inspectedLevels: [{ scopeType: 'BRAND', outcome: 'VALUE' }],
          currentVersionAtScope: 1,
        });
      }
      return Promise.resolve(defaultResolution(code));
    });
    fixture = TestBed.createComponent(OrderPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const fieldRows = fixture.nativeElement.querySelectorAll('.field-row');
    const promoCodeRow = fieldRows[fieldRows.length - 1] as HTMLElement; // Card 5's only field
    const revertButton = Array.from(promoCodeRow.querySelectorAll('.field__action')).find(
      (button) => button.textContent?.includes('Revert'),
    ) as HTMLButtonElement;
    revertButton.click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.operator_promo_code_allowed',
      expect.objectContaining({
        scopeType: 'BRAND',
        brandId: BRAND_ID,
        locationId: null,
        explicitNull: true,
      }),
    );
  });
});
