import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { SettingsEditingLevel, SettingsScope } from '../settings-scope';
import { ApprovalPolicies } from './approval-policies';
import { ApprovalPoliciesApi, PolicyCoverage, PolicyVersion } from './approval-policies-api';

const TENANT_ID = 'tenant-1';
const BRAND_ID = 'brand-1';
const LOCATION_ID = 'location-1';

function version(overrides: Partial<PolicyVersion> = {}): PolicyVersion {
  return {
    id: 'policy-1',
    actionCode: 'payments.remedy.record',
    scopeType: 'TENANT',
    brandId: null,
    locationId: null,
    legacyScopeWide: false,
    thresholdDescription: 'A refund above 1 000 000 UZS',
    requiredApproverCapability: 'refund.approve',
    validFrom: '2026-10-01T00:00:00Z',
    validUntil: null,
    version: 1,
    authoredBy: 'owner',
    createdAt: '2026-10-01T00:00:00Z',
    governsNow: true,
    ...overrides,
  };
}

function coverage(overrides: Partial<PolicyCoverage> & { actionCode: string }): PolicyCoverage {
  const configuredScopes = overrides.configuredScopes ?? [];
  return {
    missingPolicyMode: 'ALLOW_WITHOUT_APPROVAL',
    worklist: 'TENANT',
    configuredAnywhere: configuredScopes.length > 0,
    configuredScopes,
    ...overrides,
  };
}

const COVERAGE: readonly PolicyCoverage[] = [
  coverage({ actionCode: 'payments.remedy.record', configuredScopes: [version()] }),
  coverage({
    actionCode: 'courier.adjustment.create.manual-penalty',
    missingPolicyMode: 'REQUIRE_CONFIGURED_POLICY',
  }),
  coverage({ actionCode: 'customer.pii.export' }),
  // A decision HorecaOS takes about a tenant: not the tenant's to publish a policy for.
  coverage({
    actionCode: 'tenant.activate',
    worklist: 'PLATFORM',
    missingPolicyMode: 'REQUIRE_CONFIGURED_POLICY',
  }),
];

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

class FakeScope {
  readonly level = signal<SettingsEditingLevel>('TENANT');
  readonly brandId = signal<string | null>(null);
  readonly locationId = signal<string | null>(null);
  readonly brands = signal([{ id: BRAND_ID, displayName: 'Oqtepa' }]);
  readonly locations = signal([{ id: LOCATION_ID, displayName: 'Chilonzor' }]);
  readonly denied = signal(false);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ApprovalPolicies', () => {
  let fixture: ComponentFixture<ApprovalPolicies>;
  let scope: FakeScope;
  let api: {
    coverage: ReturnType<typeof vi.fn>;
    author: ReturnType<typeof vi.fn>;
    endDate: ReturnType<typeof vi.fn>;
  };

  async function create(held: readonly Capability[] = ['APPROVAL_POLICY_MANAGE']): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ApprovalPolicies],
      providers: [
        { provide: ApprovalPoliciesApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: SettingsScope, useValue: scope },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.includes(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ApprovalPolicies);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function byId(testId: string): HTMLElement {
    const found = el().querySelector(`[data-testid="${testId}"]`);
    expect(found, testId).not.toBeNull();
    return found as HTMLElement;
  }

  function type(testId: string, value: string): void {
    const input = byId(testId) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function click(element: HTMLElement): Promise<void> {
    element.click();
    await flush();
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    scope = new FakeScope();
    api = {
      coverage: vi.fn().mockResolvedValue(COVERAGE),
      author: vi.fn().mockResolvedValue(version({ id: 'policy-2' })),
      endDate: vi.fn().mockResolvedValue(version({ validUntil: '2026-10-14T05:00:00Z' })),
    };
  });

  it('lists the actions the tenant decides, the blocked ones first, and leaves out the platform’s own', async () => {
    await create();

    const rows = [...el().querySelectorAll('[data-testid^="who-row-"]')].map((row) =>
      row.getAttribute('data-testid'),
    );
    expect(rows).toEqual([
      'who-row-courier.adjustment.create.manual-penalty',
      'who-row-customer.pii.export',
      'who-row-payments.remedy.record',
    ]);
  });

  it('says in words what happens with no policy: a manual penalty is refused, an export needs one signature', async () => {
    await create();

    const penalty = byId('who-row-courier.adjustment.create.manual-penalty');
    expect(penalty.querySelector('[data-testid="who-without"]')?.textContent).toContain(
      'Refused until a policy is published',
    );
    expect(penalty.textContent).toContain('None published');
    const exports = byId('who-row-customer.pii.export');
    expect(exports.querySelector('[data-testid="who-without"]')?.textContent).toContain(
      'One signature is enough',
    );
  });

  it('shows a published policy in force with its sentence and the capability the second person holds', async () => {
    await create();

    const refund = byId('who-row-payments.remedy.record');
    expect(refund.textContent).toContain('Refund');
    const published = refund.querySelector('[data-testid="who-version"]')!;
    expect(published.textContent).toContain('Whole company');
    expect(published.textContent).toContain('In force');
    expect(published.textContent).toContain('A refund above 1 000 000 UZS');
    expect(published.textContent).toContain('refund.approve');
  });

  it('tells anyone without approval.policy.manage so, and asks the platform for nothing', async () => {
    await create([]);

    expect(byId('who-denied').textContent).toContain('Only the account owner');
    expect(api.coverage).not.toHaveBeenCalled();
    expect(el().querySelector('[data-testid="who-publish"]')).toBeNull();
  });

  it('shows a refused read as a denial, not as an error', async () => {
    api.coverage.mockRejectedValue(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'c-1'),
    );
    await create();

    expect(el().querySelector('[data-testid="who-denied"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="who-error"]')).toBeNull();
  });

  it('publishes a company-wide policy for the manual penalty and reads the coverage again', async () => {
    await create();
    await click(
      byId('who-row-courier.adjustment.create.manual-penalty').querySelector(
        '[data-testid="who-publish"]',
      ) as HTMLElement,
    );

    expect(byId('who-form-scope').textContent).toContain('Whole company');
    expect((byId('who-submit') as HTMLButtonElement).disabled).toBe(true);
    type('who-threshold', 'Any manual penalty');
    type('who-capability', 'courier.adjustment.approve');
    type('who-reason', 'Finance wants every penalty signed');
    expect((byId('who-submit') as HTMLButtonElement).disabled).toBe(false);
    api.coverage.mockClear();
    await click(byId('who-submit'));

    expect(api.author).toHaveBeenCalledWith(TENANT_ID, {
      actionCode: 'courier.adjustment.create.manual-penalty',
      scopeType: 'TENANT',
      brandId: null,
      locationId: null,
      thresholdDescription: 'Any manual penalty',
      requiredApproverCapability: 'courier.adjustment.approve',
      validFrom: null,
      reason: 'Finance wants every penalty signed',
    });
    expect(api.coverage).toHaveBeenCalledWith(TENANT_ID);
    expect(byId('who-notice').textContent).toContain('Published');
    expect(el().querySelector('[data-testid="who-publish-form"]')).toBeNull();
  });

  it('follows the scope bar: a brand and a branch name the policy’s scope', async () => {
    scope.level.set('BRAND');
    scope.brandId.set(BRAND_ID);
    await create();
    await click(
      byId('who-row-customer.pii.export').querySelector(
        '[data-testid="who-publish"]',
      ) as HTMLElement,
    );
    expect(byId('who-form-scope').textContent).toContain('Brand Oqtepa');
    type('who-threshold', 'Any export');
    type('who-capability', 'approval.decide');
    type('who-reason', 'Brand rule');
    await click(byId('who-submit'));
    expect(api.author.mock.calls[0][1]).toMatchObject({
      scopeType: 'BRAND',
      brandId: BRAND_ID,
      locationId: null,
    });

    scope.level.set('LOCATION');
    scope.locationId.set(LOCATION_ID);
    fixture.detectChanges();
    await click(
      byId('who-row-customer.pii.export').querySelector(
        '[data-testid="who-publish"]',
      ) as HTMLElement,
    );
    expect(byId('who-form-scope').textContent).toContain('Branch Chilonzor');
    type('who-threshold', 'Any export');
    type('who-capability', 'approval.decide');
    type('who-reason', 'Branch rule');
    await click(byId('who-submit'));
    expect(api.author.mock.calls[1][1]).toMatchObject({
      scopeType: 'LOCATION',
      brandId: BRAND_ID,
      locationId: LOCATION_ID,
    });
  });

  it('sends a start time as an instant, and none as "now"', async () => {
    await create();
    await click(
      byId('who-row-customer.pii.export').querySelector(
        '[data-testid="who-publish"]',
      ) as HTMLElement,
    );
    type('who-threshold', 'Any export');
    type('who-capability', 'approval.decide');
    type('who-reason', 'Start tomorrow');
    type('who-valid-from', '2030-01-02T09:30');
    await click(byId('who-submit'));

    expect(api.author.mock.calls[0][1].validFrom).toBe(new Date('2030-01-02T09:30').toISOString());
  });

  it('keeps the form open and shows the server’s sentence when a capability is refused', async () => {
    api.author.mockRejectedValue(
      new ApiError(
        ApiErrorCode.VALIDATION_FAILED,
        422,
        {
          type: 'about:blank',
          title: 'Validation failed',
          status: 422,
          detail:
            'No such capability: made.up. A policy naming one nobody can hold is unapprovable.',
        } as never,
        'c-2',
      ),
    );
    await create();
    await click(
      byId('who-row-customer.pii.export').querySelector(
        '[data-testid="who-publish"]',
      ) as HTMLElement,
    );
    type('who-threshold', 'Any export');
    type('who-capability', 'made.up');
    type('who-reason', 'Typo');
    await click(byId('who-submit'));

    expect(byId('who-publish-form')).not.toBeNull();
    expect(byId('who-form-error').textContent).toBeTruthy();
    expect(el().querySelector('[data-testid="who-notice"]')).toBeNull();
  });

  it('ends a policy in force with a reason, after saying what the action falls back to', async () => {
    await create();
    await click(
      byId('who-row-payments.remedy.record').querySelector(
        '[data-testid="who-end"]',
      ) as HTMLElement,
    );

    expect(byId('who-end-warning').textContent).toContain('One signature is enough');
    expect((byId('who-end-submit') as HTMLButtonElement).disabled).toBe(true);
    type('who-end-reason', 'No longer needed');
    await click(byId('who-end-submit'));

    expect(api.endDate).toHaveBeenCalledWith(TENANT_ID, 'policy-1', 'No longer needed');
    expect(byId('who-notice').textContent).toContain('Ended');
  });

  it('shows a version scheduled for later as starting then, not as in force', async () => {
    api.coverage.mockResolvedValue([
      coverage({
        actionCode: 'customer.pii.export',
        configuredScopes: [
          version({
            id: 'policy-9',
            actionCode: 'customer.pii.export',
            governsNow: false,
            validFrom: '2030-01-02T09:30:00Z',
          }),
        ],
      }),
    ]);
    await create();

    const text = byId('who-row-customer.pii.export').textContent ?? '';
    expect(text).toContain('Starts');
    expect(text).not.toContain('In force');
  });
});
