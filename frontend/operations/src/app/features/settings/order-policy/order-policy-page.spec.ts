import { signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { Toasts } from '../../../shared/ui/toast';
import { CouriersApi } from '../../couriers/couriers-api';
import { DeliveryTariffsApi } from '../../delivery/delivery-tariffs-api';
import { DispatchRulesApi } from '../../delivery/dispatch-rules-api';
import { PaidHoldApi } from '../../kitchen/paid-hold-api';
import { ConfigurationApi } from '../configuration-api';
import { LocationsApi } from '../locations/locations-api';
import { SalesChannelsApi } from '../sales-channels/sales-channels-api';
import { SavedTarget } from '../settings-saved';
import { SettingsScope } from '../settings-scope';
import { LatenessPolicyCard } from './lateness-policy-card';
import { LatenessEditorView, LatenessPolicyEditorApi } from './lateness-policy-editor-api';
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

/** One default value per card 2-5 key, matching `OrderingConfigurationKeys` (ADR 0177 retired five and added two). */
const CARD2_5_DEFAULTS: Readonly<Record<string, unknown>> = {
  'ordering.late_order_threshold_minutes': 45,
  'ordering.at_risk_before_minutes': 5,
  'ordering.late_colour': '',
  'ordering.minimum_order_amount_minor': 0,
  'ordering.auto_accept_eligible_channels': 'ALL',
  'ordering.auto_accept_min_prior_orders': 0,
  'ordering.preorder_branch_resolution': 'BY_DISTANCE',
  'ordering.operator_promo_code_allowed': true,
  'ordering.operator_callback_flag_allowed': true,
  'ordering.operator_change_due_allowed': true,
  'delivery.out_of_zone_policy': 'REJECT',
};

/** The five card-2 scalars nothing read, retired by ADR 0177: the page neither shows nor reads them. */
const RETIRED_CODES = [
  'ordering.business_day_start_hour',
  'ordering.average_order_minutes',
  'ordering.maximum_order_minutes',
  'ordering.vat_rate_percent',
  'ordering.routing_poll_interval_minutes',
];

/** Nothing authored anywhere: the lateness card renders the platform default. */
const LATENESS_DEFAULT: LatenessEditorView = {
  delivery: {
    atRiskBeforeSeconds: null,
    effectiveAtRiskBeforeSeconds: 300,
    lateAfterSeconds: 0,
    noPromiseFallbackSeconds: null,
    effectiveNoPromiseFallbackSeconds: 2700,
  },
  pickup: {
    atRiskBeforeSeconds: null,
    effectiveAtRiskBeforeSeconds: 300,
    lateAfterSeconds: 0,
    noPromiseFallbackSeconds: null,
    effectiveNoPromiseFallbackSeconds: 2700,
  },
  dineIn: {
    atRiskBeforeSeconds: null,
    effectiveAtRiskBeforeSeconds: 300,
    lateAfterSeconds: 0,
    noPromiseFallbackSeconds: null,
    effectiveNoPromiseFallbackSeconds: 2700,
  },
  atRiskDefault: { seconds: 300, source: 'PLATFORM_DEFAULT' },
  noPromiseDefault: { seconds: 2700, source: 'PLATFORM_DEFAULT' },
  isPlatformDefault: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  currentVersionAtScope: 0,
  inspectedLevels: [{ scopeType: 'BRAND', outcome: 'NOT_SET' }],
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
  /** What the real bar names for the level it is at (`SettingsScope.target`). */
  readonly target = signal<SavedTarget>({ level: 'BRAND', name: 'Rayhon' });
  readonly targetFor = vi.fn(
    (level: 'TENANT' | 'BRAND' | 'LOCATION', _brandId: string | null, locationId: string | null) =>
      level === 'LOCATION'
        ? { level, name: locationId === 'loc-1' ? 'Chilanzar' : null }
        : level === 'TENANT'
          ? { level, name: null }
          : { level, name: 'Rayhon' },
  );
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
  let latenessApi: { get: ReturnType<typeof vi.fn>; publish: ReturnType<typeof vi.fn> };
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

    latenessApi = { get: vi.fn().mockResolvedValue(LATENESS_DEFAULT), publish: vi.fn() };

    settingsScope = new FakeSettingsScope();
    await TestBed.configureTestingModule({
      imports: [OrderPolicyPage],
      providers: [
        { provide: OrderPolicyApi, useValue: policyApi },
        { provide: ConfigurationApi, useValue: configApi },
        { provide: LatenessPolicyEditorApi, useValue: latenessApi },
        // Card 3's read-only dispatch rules summary (ADR 0142): nothing published, so it only links.
        {
          provide: DispatchRulesApi,
          useValue: {
            rules: () =>
              Promise.resolve({
                value: { rules: [], isBuiltIn: true },
                version: 0,
              }),
          },
        },
        // The neighbours card's reads (ADR 0177): each is a read-only echo of a setting that lives elsewhere.
        {
          provide: DeliveryTariffsApi,
          useValue: {
            list: () =>
              Promise.resolve([
                {
                  tariffId: 't-1',
                  code: 'STD',
                  name: 'Standard',
                  status: 'ACTIVE',
                  brandDefault: true,
                  distanceMode: 'ROAD',
                },
              ]),
          },
        },
        {
          provide: LocationsApi,
          useValue: {
            serviceSummary: () => Promise.resolve({ maxConcurrentOrders: 12 }),
          },
        },
        {
          provide: SalesChannelsApi,
          useValue: {
            list: () =>
              Promise.resolve([
                {
                  id: 'ch-1',
                  code: 'call-centre',
                  systemType: 'CALL_CENTRE',
                  status: 'ACTIVE',
                  enabledPaymentMethodCount: 2,
                  enabledFulfillmentModes: ['PICKUP', 'DELIVERY'],
                },
              ]),
          },
        },
        {
          provide: PaidHoldApi,
          useValue: { policy: () => Promise.resolve({ mode: 'HOLD_UNTIL_PAID' }) },
        },
        {
          provide: CouriersApi,
          useValue: { policy: () => Promise.resolve({ kitchenReadyOnly: true }) },
        },
        provideRouter([]),
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

  it('renders the card 2-5 fields with their resolved values, not a not-built note', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('notBuilt');
    expect(text).toContain('45'); // late order threshold minutes
    expect(text).toContain('Nearest branch'); // BY_DISTANCE
    expect(text).toContain('Yes'); // operator promo code allowed, boolean true
  });

  // ADR 0177 decision 2: five scalars nothing read are gone, and the page no longer shows a setting that
  // changes nothing.
  it('no longer offers or reads the five retired card 2 scalars', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    for (const label of [
      'Business day starts',
      'Average order time',
      'Maximum order time',
      'VAT rate',
      'Routing poll interval',
    ]) {
      expect(text, label).not.toContain(label);
    }
    const fieldRows = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.field-row'),
    ).map((row) => row.textContent ?? '');
    expect(fieldRows).toHaveLength(10);
    expect(
      fieldRows.filter((row) => row.includes('Not applied yet')),
      'every field on Cards 2 to 5 is read by something',
    ).toEqual([]);
    for (const code of RETIRED_CODES) {
      expect(configApi.resolution, code).not.toHaveBeenCalledWith(
        TENANT_ID,
        code,
        expect.anything(),
        expect.anything(),
        expect.anything(),
      );
    }
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
    const lateThresholdRow = fieldRows[0] as HTMLElement; // late threshold, at-risk, colour, minimum
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
          value: false,
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
    const promoCodeRow = fieldRows[fieldRows.length - 3] as HTMLElement; // Card 5: promo, callback, change due
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

  const AT_RISK_ROW = 1; // late threshold, at-risk, colour, minimum
  const LATE_COLOUR_ROW = 2;

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

  // ADR 0150: «An order with no promised time is late after» is the tenant-wide default for the no-promise
  // fallback of the boundaries card; it is read now. The screen says which orders it governs and carries
  // «Not applied yet» only on the two siblings no record has given a reader.
  const LATE_THRESHOLD_ROW = 0;
  const MINIMUM_ROW = 3;

  it('words the late-order scalar for the orders it governs, and says it is read', () => {
    const row = rowAt(LATE_THRESHOLD_ROW);

    expect(row.textContent).toContain('An order with no promised time is late after (minutes)');
    expect(row.textContent).toContain('Only for orders with no promised time');
    expect(row.textContent).toContain('does not touch it');
    expect(row.textContent).not.toContain('Not applied yet');
  });

  it('keeps that wording in front of the operator while the scalar is being edited, still without the old note', () => {
    (rowAt(LATE_THRESHOLD_ROW).querySelector('.field__action') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(rowAt(LATE_THRESHOLD_ROW).querySelector('input')).not.toBeNull();
    expect(rowAt(LATE_THRESHOLD_ROW).textContent).toContain(
      'Only for orders with no promised time',
    );
    expect(rowAt(LATE_THRESHOLD_ROW).textContent).not.toContain('Not applied yet');
  });

  it('puts no such note on the scalars that are applied', () => {
    expect(rowAt(AT_RISK_ROW).textContent).not.toContain('Not applied yet');
    expect(rowAt(LATE_THRESHOLD_ROW).textContent).not.toContain('Not applied yet');
    expect(rowAt(LATE_COLOUR_ROW).textContent).not.toContain('Not applied yet');
    expect(
      rowAt(MINIMUM_ROW).textContent,
      'the minimum order amount is read at checkout',
    ).not.toContain('Not applied yet');
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

  // Rows X.39 / 10.3b: the ordering.lateness document's editor, embedded under the timing card.

  it('embeds the lateness editor under the timing card, reading the document at the scope bar’s own scope', () => {
    expect(latenessApi.get).toHaveBeenCalledWith(TENANT_ID, 'BRAND', BRAND_ID, null);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('When an order counts as late');
    expect(text).toContain('Delivery');
    expect(text).toContain('Pickup');
    expect(text).toContain('Dine-in');
  });

  it('re-reads the lateness document at TENANT level when the scope bar switches to it', async () => {
    latenessApi.get.mockClear();
    settingsScope.level.set('TENANT');
    fixture.detectChanges();
    await flushMicrotasks();

    expect(latenessApi.get).toHaveBeenCalledWith(TENANT_ID, 'TENANT', BRAND_ID, null);
  });

  it('re-reads the lateness document when the at-risk scalar it defaults to is saved', async () => {
    latenessApi.get.mockClear();
    (rowAt(AT_RISK_ROW).querySelector('.field__action') as HTMLButtonElement).click(); // Override
    fixture.detectChanges();
    type('[id="field-ordering.at_risk_before_minutes"]', '12');
    type('[id="field-reason-ordering.at_risk_before_minutes"]', 'Warn everyone earlier');
    publishButton().click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.at_risk_before_minutes',
      expect.objectContaining({ integerValue: 12 }),
    );
    expect(latenessApi.get).toHaveBeenCalledTimes(1);
  });

  it('re-reads the lateness document when the late-order threshold it defaults to is saved (ADR 0150)', async () => {
    latenessApi.get.mockClear();
    (rowAt(LATE_THRESHOLD_ROW).querySelector('.field__action') as HTMLButtonElement).click(); // late threshold
    fixture.detectChanges();
    type('[id="field-ordering.late_order_threshold_minutes"]', '20');
    type('[id="field-reason-ordering.late_order_threshold_minutes"]', 'aggregator orders sooner');
    publishButton().click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(configApi.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'ordering.late_order_threshold_minutes',
      expect.objectContaining({ integerValue: 20 }),
    );
    expect(latenessApi.get).toHaveBeenCalledTimes(1);
  });

  it('does not re-read the lateness document for an unrelated field', async () => {
    latenessApi.get.mockClear();
    (rowAt(MINIMUM_ROW).querySelector('.field__action') as HTMLButtonElement).click(); // minimum order sum
    fixture.detectChanges();
    type('[id="field-ordering.minimum_order_amount_minor"]', '5000');
    type('[id="field-reason-ordering.minimum_order_amount_minor"]', 'unrelated');
    publishButton().click();
    await flushMicrotasks();

    expect(configApi.setValue).toHaveBeenCalled();
    expect(latenessApi.get).not.toHaveBeenCalled();
  });

  it('overrides a Card 2 field at TENANT scope, authoring the company-wide default', async () => {
    settingsScope.level.set('TENANT');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const fieldRows = fixture.nativeElement.querySelectorAll('.field-row');
    const lateThresholdRow = fieldRows[0] as HTMLElement;
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

  it('shows the dispatch rules summary on the automation card and links to where rules are written (ADR 0142)', () => {
    const host = fixture.nativeElement as HTMLElement;

    const card = host.querySelector('[data-testid="dispatch-summary"]');
    expect(card).not.toBeNull();
    expect(host.querySelector('[data-testid="dispatch-summary-link"]')?.getAttribute('href')).toBe(
      '/delivery/dispatch-rules',
    );
  });
  // ---------------------------------- ADR 0177 decision 3: Card 1's hold-back path, Card 3's gates

  async function renderWith(policy: Partial<AcceptancePolicyResponse>): Promise<void> {
    policyApi.getEffective.mockResolvedValue({ ...POLICY, ...policy });
    fixture = TestBed.createComponent(OrderPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  const byTestId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  function selectValue(selector: string, value: string): void {
    const select = fixture.nativeElement.querySelector(selector) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function startEditingCard1(): void {
    (fixture.nativeElement.querySelector('.card .primary') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  it('says an auto-confirm policy with nobody to confirm holds nothing back, and that the gates therefore cannot', async () => {
    await renderWith({ mode: 'AUTO_CONFIRM', approvalChannel: 'NONE', approvalTimeoutSeconds: 0 });

    expect(byTestId('hold-back-none')?.textContent).toContain('cannot hold an order');
    expect(byTestId('gates-no-path')?.textContent).toContain('cannot hold anything');
  });

  it('shows the hold-back path of an auto-confirm policy that names one, and stops warning about the gates', async () => {
    await renderWith({ mode: 'AUTO_CONFIRM', approvalChannel: 'POS', approvalTimeoutSeconds: 120 });

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(byTestId('hold-back-none')).toBeNull();
    expect(byTestId('gates-no-path')).toBeNull();
    expect(text).toContain('Who confirms');
    expect(text).toContain('POS');
    expect(text).toContain('120');
  });

  it('leaves the gates their path under restaurant approval', () => {
    expect(byTestId('gates-no-path')).toBeNull();
  });

  it('starts an auto-confirm draft with nobody to confirm, and a channel brings a timeout the held order can wait on', async () => {
    startEditingCard1();
    selectValue('#policy-mode', 'AUTO_CONFIRM');

    expect(
      (fixture.nativeElement.querySelector('#policy-approval-channel') as HTMLSelectElement).value,
    ).toBe('NONE');
    expect(fixture.nativeElement.querySelector('#policy-timeout')).toBeNull();
    expect(byTestId('hold-back-hint')).not.toBeNull();

    selectValue('#policy-approval-channel', 'HORECAOS_OPERATIONS');

    expect((fixture.nativeElement.querySelector('#policy-timeout') as HTMLInputElement).value).toBe(
      '300',
    );
    type('#policy-reason', 'Hold back unknown channels for a look');
    publishButton().click();
    await flushMicrotasks();

    expect(policyApi.publish).toHaveBeenCalledWith(
      TENANT_ID,
      BRAND_ID,
      null,
      expect.objectContaining({
        mode: 'AUTO_CONFIRM',
        approvalChannel: 'HORECAOS_OPERATIONS',
        approvalTimeoutSeconds: 300,
      }),
    );
  });

  it('will not publish a hold-back path whose timeout is outside 30 seconds to 30 minutes', () => {
    startEditingCard1();
    selectValue('#policy-mode', 'AUTO_CONFIRM');
    selectValue('#policy-approval-channel', 'POS');
    type('#policy-reason', 'A reason');
    expect(publishButton().disabled).toBe(false);

    type('#policy-timeout', '10');
    expect(publishButton().disabled).toBe(true);

    type('#policy-timeout', '1801');
    expect(publishButton().disabled).toBe(true);

    type('#policy-timeout', '1800');
    expect(publishButton().disabled).toBe(false);
  });

  it('publishes a plain auto-confirm with no path when nobody is chosen, whatever timeout is on the form', async () => {
    startEditingCard1();
    selectValue('#policy-mode', 'AUTO_CONFIRM');
    type('#policy-reason', 'Everything is accepted on its own');
    expect(publishButton().disabled).toBe(false);
    publishButton().click();
    await flushMicrotasks();

    expect(policyApi.publish).toHaveBeenCalledWith(
      TENANT_ID,
      BRAND_ID,
      null,
      expect.objectContaining({ mode: 'AUTO_CONFIRM', approvalChannel: 'NONE' }),
    );
  });

  it('puts the operator toggles on Card 5, each saying what turning it off does', () => {
    const labels = [7, 8, 9].map((index) => rowAt(index).textContent ?? '');

    expect(labels[0]).toContain('Operator may apply a promo code');
    expect(labels[0]).toContain('refused');
    expect(labels[1]).toContain('call the customer back');
    expect(labels[1]).toContain('hides the checkbox');
    expect(labels[2]).toContain('cash the customer will hand over');
    expect(labels[2]).toContain('refuses a cash amount');
  });

  it('words the pre-order branch selection for what it does, including the pickup fallback', () => {
    const text = rowAt(6).textContent ?? '';

    expect(text).toContain('Pre-order branch selection');
    expect(text).toContain('no candidate branch is open');
    expect(text).toContain('soonest to open');
  });

  // -------------------------------------- ADR 0177: the settings that live elsewhere, as read-only echoes

  it('shows what is in force for the settings that live on other screens, each with the way to its screen', () => {
    expect(byTestId('neighbour-value-distanceMode')?.textContent).toContain('By road');
    expect(byTestId('neighbour-value-branchLimit')?.textContent).toContain('Set for each branch');
    expect(byTestId('neighbour-value-outOfZone')?.textContent).toContain('Reject');
    expect(byTestId('neighbour-value-courierVisibility')?.textContent).toContain(
      'Only orders the kitchen has finished',
    );
    expect(byTestId('neighbour-value-paidHold')?.textContent).toContain('Holds an unpaid order');
    expect(byTestId('neighbour-value-callCentre')?.textContent).toContain('2 payment methods');
    expect(byTestId('neighbour-value-callCentre')?.textContent).toContain('Pickup, Delivery');

    const links = [
      'distanceMode',
      'branchLimit',
      'outOfZone',
      'courierVisibility',
      'paidHold',
      'callCentre',
    ].map((id) => byTestId(`neighbour-link-${id}`)?.getAttribute('href'));
    expect(links).toEqual([
      '/delivery/tariffs',
      '/settings/locations',
      '/delivery/courier-policy',
      '/delivery/courier-policy',
      '/kitchen/buffer',
      '/settings/sales-channels',
    ]);
  });

  it('is honest that the out-of-zone policy is not applied yet', () => {
    expect(byTestId('neighbour-outOfZone')?.textContent).toContain('Not applied yet');
    expect(byTestId('neighbour-distanceMode')?.textContent).not.toContain('Not applied yet');
  });

  it('says a neighbour cannot be read, rather than leaving a blank, when the role cannot read it', async () => {
    TestBed.inject(ConfigurationApi);
    configApi.resolution.mockImplementation((_tenantId: string, code: string) =>
      code === 'delivery.out_of_zone_policy'
        ? Promise.reject(new Error('403'))
        : Promise.resolve(defaultResolution(code)),
    );
    fixture = TestBed.createComponent(OrderPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(byTestId('neighbour-value-outOfZone')?.textContent).toContain('your role cannot read');
    expect(byTestId('neighbour-value-distanceMode')?.textContent).toContain('By road');
  });

  // -------------------------------------------------- settings.md §1.3: a toast names the level

  describe('confirming a save in the toast host (row X.1)', () => {
    let toasts: Toasts;

    function clickPublish(): void {
      (
        Array.from(
          (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
        ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement
      ).click();
    }

    beforeEach(() => {
      toasts = TestBed.inject(Toasts);
      toasts.clear();
    });

    const messages = (): string[] => toasts.visible().map((toast) => toast.message);

    it('says which brand Card 1 was published for', async () => {
      (fixture.nativeElement.querySelector('.card .primary') as HTMLButtonElement).click();
      fixture.detectChanges();
      const reason = fixture.nativeElement.querySelector('#policy-reason') as HTMLInputElement;
      reason.value = 'Peak hours';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      clickPublish();
      await flushMicrotasks();

      expect(messages()).toEqual(['Order acceptance — new version published for brand “Rayhon”']);
    });

    // Card 1 is written at a brand or a branch, never tenant-wide (`OrderPolicyApi.publish` takes a
    // brand and an optional branch), so with the bar at the company-wide level the toast must name
    // where the version really went. «For the whole company» would be the mistake §1.3 exists to prevent.
    async function publishCard1(): Promise<void> {
      (fixture.nativeElement.querySelector('.card .primary') as HTMLButtonElement).click();
      fixture.detectChanges();
      const reason = fixture.nativeElement.querySelector('#policy-reason') as HTMLInputElement;
      reason.value = 'Peak hours';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      clickPublish();
      await flushMicrotasks();
    }

    it('names the brand Card 1 landed on, not the company, while the bar is at the company-wide level', async () => {
      settingsScope.level.set('TENANT');
      settingsScope.target.set({ level: 'TENANT', name: null });
      fixture.detectChanges();
      // The level change re-reads the cards; the edit button is back once that has settled.
      await flushMicrotasks();
      fixture.detectChanges();

      await publishCard1();

      expect(messages()).toEqual(['Order acceptance — new version published for brand “Rayhon”']);
      expect(policyApi.publish).toHaveBeenCalledWith(TENANT_ID, BRAND_ID, null, expect.anything());
    });

    it('names the branch Card 1 landed on while the bar is at the company-wide level with a branch in the query', async () => {
      settingsScope.level.set('TENANT');
      settingsScope.target.set({ level: 'TENANT', name: null });
      settingsScope.locationId.set('loc-1');
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      await publishCard1();

      expect(messages()).toEqual([
        'Order acceptance — new version published for branch “Chilanzar”',
      ]);
      expect(policyApi.publish).toHaveBeenCalledWith(
        TENANT_ID,
        BRAND_ID,
        'loc-1',
        expect.anything(),
      );
    });

    it('names the setting, its new value and the level when a field is overridden', async () => {
      const row = fixture.nativeElement.querySelectorAll('.field-row')[0] as HTMLElement;
      (row.querySelector('.field__action') as HTMLButtonElement).click();
      fixture.detectChanges();
      const value = fixture.nativeElement.querySelector(
        '[id="field-ordering.late_order_threshold_minutes"]',
      ) as HTMLInputElement;
      value.value = '20';
      value.dispatchEvent(new Event('input'));
      const reason = fixture.nativeElement.querySelector(
        '[id="field-reason-ordering.late_order_threshold_minutes"]',
      ) as HTMLInputElement;
      reason.value = 'Faster kitchen';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      configApi.resolution.mockImplementation((_tenantId: string, code: string) =>
        Promise.resolve({
          ...defaultResolution(code),
          value: code === 'ordering.late_order_threshold_minutes' ? 20 : CARD2_5_DEFAULTS[code],
        }),
      );

      clickPublish();
      await flushMicrotasks();

      expect(messages()).toHaveLength(1);
      expect(messages()[0]).toMatch(/: 20 — set for brand “Rayhon”$/);
    });

    it('says the value went back to the inherited one when a field is reverted', async () => {
      configApi.resolution.mockImplementation((_tenantId: string, code: string) =>
        Promise.resolve(
          code === 'ordering.operator_promo_code_allowed'
            ? {
                ...defaultResolution(code),
                value: false,
                cameFromDefault: false,
                source: 'SCOPED_VALUE' as const,
                winningScope: 'BRAND' as const,
                inspectedLevels: [{ scopeType: 'BRAND' as const, outcome: 'VALUE' as const }],
                currentVersionAtScope: 1,
              }
            : defaultResolution(code),
        ),
      );
      fixture = TestBed.createComponent(OrderPolicyPage);
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
      const rows = fixture.nativeElement.querySelectorAll('.field-row');
      const revert = Array.from(
        (rows[rows.length - 3] as HTMLElement).querySelectorAll('.field__action'),
      ).find((button) => button.textContent?.includes('Revert')) as HTMLButtonElement;

      revert.click();
      await flushMicrotasks();

      expect(messages()).toHaveLength(1);
      expect(messages()[0]).toMatch(/ — back to the inherited value for brand “Rayhon”$/);
    });

    it('stays silent when the save fails: the error belongs beside the field, not in a toast', async () => {
      configApi.setValue.mockRejectedValue(new Error('nope'));
      const row = fixture.nativeElement.querySelectorAll('.field-row')[0] as HTMLElement;
      (row.querySelector('.field__action') as HTMLButtonElement).click();
      fixture.detectChanges();
      const reason = fixture.nativeElement.querySelector(
        '[id="field-reason-ordering.late_order_threshold_minutes"]',
      ) as HTMLInputElement;
      reason.value = 'Faster kitchen';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      clickPublish();
      await flushMicrotasks();

      expect(messages()).toEqual([]);
    });

    it('names the scope the lateness form was opened at, not the one the bar shows by now', async () => {
      const card = fixture.debugElement.query(By.directive(LatenessPolicyCard))
        .componentInstance as LatenessPolicyCard;

      card.published.emit({ scopeType: 'LOCATION', brandId: BRAND_ID, locationId: 'loc-1' });

      expect(messages()).toEqual([
        'When an order counts as late — new version published for branch “Chilanzar”',
      ]);
    });
  });
});
