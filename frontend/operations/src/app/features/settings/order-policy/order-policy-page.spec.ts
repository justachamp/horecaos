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
  'ordering.at_risk_before_minutes': 5,
  'ordering.late_colour': '',
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
  readonly level = signal<'TENANT' | 'BRAND' | 'LOCATION'>('BRAND');
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
  let settingsScope: FakeSettingsScope;

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

    settingsScope = new FakeSettingsScope();
    await TestBed.configureTestingModule({
      imports: [OrderPolicyPage],
      providers: [
        { provide: OrderPolicyApi, useValue: policyApi },
        { provide: ConfigurationApi, useValue: configApi },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: SettingsScope, useValue: settingsScope },
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

  // Row X.39: the at-risk threshold and the tenant's late colour, both read by
  // the order board and the kitchen board.

  const AT_RISK_ROW = 4; // business day, average, maximum, late, at-risk
  const LATE_COLOUR_ROW = 5;

  function rowAt(index: number): HTMLElement {
    return fixture.nativeElement.querySelectorAll('.field-row')[index] as HTMLElement;
  }

  function publishButton(): HTMLButtonElement {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement;
  }

  function type(selector: string, value: string): void {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('offers the at-risk threshold and the late colour on the timing card, colour reading Standard while blank', () => {
    expect(rowAt(AT_RISK_ROW).textContent).toContain('Warn before the promised time');
    expect(rowAt(AT_RISK_ROW).textContent).toContain('5');
    expect(rowAt(LATE_COLOUR_ROW).textContent).toContain('Late-order colour');
    expect(rowAt(LATE_COLOUR_ROW).textContent).toContain('Standard');
    expect(configApi.resolution).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.at_risk_before_minutes',
      'BRAND',
      BRAND_ID,
      null,
    );
    expect(configApi.resolution).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_colour',
      'BRAND',
      BRAND_ID,
      null,
    );
  });

  it('sets the at-risk threshold at the scope bar’s scope, sending the version it read as the concurrency check', async () => {
    configApi.resolution.mockImplementation((_tenantId: string, code: string) =>
      Promise.resolve(
        code === 'ordering.at_risk_before_minutes'
          ? {
              ...defaultResolution(code),
              value: 8,
              cameFromDefault: false,
              source: 'SCOPED_VALUE',
              winningScope: 'BRAND',
              inspectedLevels: [{ scopeType: 'BRAND', outcome: 'VALUE' }],
              currentVersionAtScope: 4,
            }
          : defaultResolution(code),
      ),
    );
    fixture = TestBed.createComponent(OrderPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    (rowAt(AT_RISK_ROW).querySelector('.field__action') as HTMLButtonElement).click(); // Edit
    fixture.detectChanges();
    type('[id="field-ordering.at_risk_before_minutes"]', '10');
    type('[id="field-reason-ordering.at_risk_before_minutes"]', 'Warn the kitchen earlier');
    publishButton().click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.at_risk_before_minutes',
      expect.objectContaining({
        scopeType: 'BRAND',
        brandId: BRAND_ID,
        locationId: null,
        explicitNull: false,
        integerValue: 10,
        expectedVersion: 4,
        reason: 'Warn the kitchen earlier',
      }),
    );
  });

  it('bounds the at-risk input to a day, mirroring the server’s rule', () => {
    (rowAt(AT_RISK_ROW).querySelector('.field__action') as HTMLButtonElement).click(); // Override
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector(
      '[id="field-ordering.at_risk_before_minutes"]',
    ) as HTMLInputElement;
    expect(input.min).toBe('0');
    expect(input.max).toBe('1440');
  });

  it('warns under the swatch when the chosen late colour fails WCAG AA against a board surface — and still lets the operator save it', async () => {
    (rowAt(LATE_COLOUR_ROW).querySelector('.field__action') as HTMLButtonElement).click(); // Override
    fixture.detectChanges();

    // Light yellow: about 1.2:1 against white.
    type('[data-testid="q-color-input-text"]', '#ffee58');
    const warnings = fixture.nativeElement.querySelector(
      '[data-testid="q-color-input-warnings"]',
    ) as HTMLElement;
    expect(warnings).not.toBeNull();
    expect(warnings.textContent).toContain('Low contrast');
    expect(warnings.textContent).toContain('4.5:1');
    expect(warnings.textContent).toContain('the page background');
    expect(warnings.textContent).toContain('a late-order row');

    type('[id="field-reason-ordering.late_colour"]', 'Matches our brand guideline');
    expect(publishButton().disabled).toBe(false); // a warning, never a refusal
    publishButton().click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_colour',
      expect.objectContaining({
        scopeType: 'BRAND',
        explicitNull: false,
        stringValue: '#ffee58',
        expectedVersion: null,
        reason: 'Matches our brand guideline',
      }),
    );
  });

  it('shows no contrast warning for a colour that clears 4.5:1 on every reference surface', () => {
    (rowAt(LATE_COLOUR_ROW).querySelector('.field__action') as HTMLButtonElement).click();
    fixture.detectChanges();

    type('[data-testid="q-color-input-text"]', '#000000');

    expect(
      fixture.nativeElement.querySelector('[data-testid="q-color-input-warnings"]'),
    ).toBeNull();
  });

  it('shows no warning, and saves a blank value, when the operator goes back to the standard colour', async () => {
    (rowAt(LATE_COLOUR_ROW).querySelector('.field__action') as HTMLButtonElement).click();
    fixture.detectChanges();
    type('[data-testid="q-color-input-text"]', '#ffee58');
    expect(
      fixture.nativeElement.querySelector('[data-testid="q-color-input-warnings"]'),
    ).not.toBeNull();

    const useStandard = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form button'),
    ).find((button) =>
      button.textContent?.includes('Use the standard colour'),
    ) as HTMLButtonElement;
    useStandard.click();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="q-color-input-warnings"]'),
    ).toBeNull();
    type('[id="field-reason-ordering.late_colour"]', 'Back to the design-system red');
    publishButton().click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_colour',
      expect.objectContaining({ stringValue: '', explicitNull: false }),
    );
  });

  it('reads a chosen colour back as its hex, and reverts it to the inherited value in one click', async () => {
    configApi.resolution.mockImplementation((_tenantId: string, code: string) =>
      Promise.resolve(
        code === 'ordering.late_colour'
          ? {
              ...defaultResolution(code),
              value: '#8a3ffc',
              cameFromDefault: false,
              source: 'SCOPED_VALUE',
              winningScope: 'BRAND',
              inspectedLevels: [{ scopeType: 'BRAND', outcome: 'VALUE' }],
              currentVersionAtScope: 2,
            }
          : defaultResolution(code),
      ),
    );
    fixture = TestBed.createComponent(OrderPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(rowAt(LATE_COLOUR_ROW).textContent).toContain('#8a3ffc');
    const revert = Array.from(rowAt(LATE_COLOUR_ROW).querySelectorAll('.field__action')).find(
      (button) => button.textContent?.includes('Revert'),
    ) as HTMLButtonElement;
    revert.click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_colour',
      expect.objectContaining({ explicitNull: true, expectedVersion: 2 }),
    );
  });

  // Row 10.3b: TENANT is a third editing level, reachable from the scope
  // bar's own toggle (`ScopeBar`/`SettingsScope.setTenantLevel`). These
  // prove this page — the row's own named consumer — actually authors at
  // it once `scope.level()` answers `'TENANT'`, not only that the type
  // admits the value.

  it('re-reads every card 2-5 field at TENANT scope when the scope bar switches to it', async () => {
    configApi.resolution.mockClear();
    settingsScope.level.set('TENANT');
    fixture.detectChanges();
    await flushMicrotasks();

    expect(configApi.resolution).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_order_threshold_minutes',
      'TENANT',
      BRAND_ID,
      null,
    );
  });

  it('overrides a Card 2 field at TENANT scope, authoring the company-wide default', async () => {
    settingsScope.level.set('TENANT');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const fieldRows = fixture.nativeElement.querySelectorAll('.field-row');
    const lateThresholdRow = fieldRows[3] as HTMLElement;
    const overrideButton = lateThresholdRow.querySelector('.field__action') as HTMLButtonElement;
    overrideButton.click();
    fixture.detectChanges();

    const valueInput = fixture.nativeElement.querySelector(
      '[id="field-ordering.late_order_threshold_minutes"]',
    ) as HTMLInputElement;
    valueInput.value = '25';
    valueInput.dispatchEvent(new Event('input'));

    const reasonInput = fixture.nativeElement.querySelector(
      '[id="field-reason-ordering.late_order_threshold_minutes"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Company-wide default, every brand inherits unless overridden';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_order_threshold_minutes',
      expect.objectContaining({ scopeType: 'TENANT', integerValue: 25 }),
    );
  });
});
