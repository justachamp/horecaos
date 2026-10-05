import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../configuration-api';
import { SettingsEditingLevel, SettingsScope } from '../settings-scope';
import { ApprovalsSettingsPage } from './approvals-settings-page';

const TENANT_ID = 'tenant-1';
const BRAND_ID = 'brand-1';

const PERCENTAGE = 'pricing.promotion.approval.percentage_over_bp';
const AMOUNT = 'pricing.promotion.approval.amount_over_minor';
const MARKUP = 'pricing.promotion.approval.always_for_markup';
const EXPORT_ROWS = 'customers.pii_export_approval_threshold_rows';

function resolution(
  code: string,
  value: unknown,
  overrides: Partial<ConfigurationResolutionView> = {},
): ConfigurationResolutionView {
  return {
    keyCode: code,
    value,
    cameFromDefault: true,
    source: 'CODE_DEFAULT',
    winningScope: null,
    inspectedLevels: [{ scopeType: 'TENANT', outcome: 'NOT_SET' }],
    describe: `${code} -> CODE_DEFAULT`,
    currentVersionAtScope: null,
    ...overrides,
  };
}

const DEFAULTS: Readonly<Record<string, unknown>> = {
  [PERCENTAGE]: 3000,
  [AMOUNT]: 100000,
  [MARKUP]: true,
  [EXPORT_ROWS]: 500,
};

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

class FakeScope {
  readonly level = signal<SettingsEditingLevel>('BRAND');
  readonly brandId = signal<string | null>(BRAND_ID);
  readonly locationId = signal<string | null>(null);
  readonly denied = signal(false);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ApprovalsSettingsPage', () => {
  let fixture: ComponentFixture<ApprovalsSettingsPage>;
  let scope: FakeScope;
  let api: { resolution: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };

  async function create(): Promise<void> {
    fixture = TestBed.createComponent(ApprovalsSettingsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function row(code: string): HTMLElement {
    return el().querySelector(`[data-testid="approval-field-${code}"]`) as HTMLElement;
  }

  async function edit(code: string, value: string | null, reason: string): Promise<void> {
    const open = row(code).querySelector('.field__action') as HTMLButtonElement | null;
    if (open) {
      open.click();
      fixture.detectChanges();
    }
    if (value !== null) {
      const input = row(code).querySelector('input[type="text"]') as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    const reasonInput = row(code).querySelector(`[id="reason-${code}"]`) as HTMLInputElement;
    reasonInput.value = reason;
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function publishButton(code: string): HTMLButtonElement {
    return row(code).querySelector('[data-testid="approval-publish"]') as HTMLButtonElement;
  }

  beforeEach(async () => {
    scope = new FakeScope();
    api = {
      resolution: vi
        .fn()
        .mockImplementation((_tenantId: string, code: string) =>
          Promise.resolve(resolution(code, DEFAULTS[code])),
        ),
      setValue: vi.fn().mockResolvedValue({
        id: 'value-1',
        keyCode: PERCENTAGE,
        scopeType: 'BRAND',
        value: 2500,
        explicitNull: false,
        version: 1,
      }),
    };
    await TestBed.configureTestingModule({
      imports: [ApprovalsSettingsPage],
      providers: [
        provideRouter([]),
        { provide: ConfigurationApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: SettingsScope, useValue: scope },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
  });

  it('reads the three promotion limits at the brand and the export limit at the tenant', async () => {
    await create();

    for (const code of [PERCENTAGE, AMOUNT, MARKUP]) {
      expect(api.resolution).toHaveBeenCalledWith(TENANT_ID, code, 'BRAND', BRAND_ID, null);
    }
    expect(api.resolution).toHaveBeenCalledWith(TENANT_ID, EXPORT_ROWS, 'TENANT', null, null);
    expect(el().querySelectorAll('q-inherited-field').length).toBe(4);
    expect(el().textContent).toContain('3000');
  });

  it('follows the scope bar to the tenant default for the promotion limits', async () => {
    scope.level.set('TENANT');
    await create();

    expect(api.resolution).toHaveBeenCalledWith(TENANT_ID, PERCENTAGE, 'TENANT', null, null);
    expect(api.resolution).not.toHaveBeenCalledWith(TENANT_ID, PERCENTAGE, 'BRAND', BRAND_ID, null);
  });

  it('shows the brand limit, and says so, when the scope bar sits on a branch', async () => {
    scope.level.set('LOCATION');
    scope.locationId.set('location-1');
    await create();

    expect(api.resolution).toHaveBeenCalledWith(TENANT_ID, PERCENTAGE, 'BRAND', BRAND_ID, null);
    expect(api.resolution).not.toHaveBeenCalledWith(
      TENANT_ID,
      PERCENTAGE,
      'LOCATION',
      expect.anything(),
      expect.anything(),
    );
    expect(el().querySelector('[data-testid="approvals-branch-note"]')).toBeTruthy();
  });

  it('does not show the branch note at the brand or the tenant', async () => {
    await create();

    expect(el().querySelector('[data-testid="approvals-branch-note"]')).toBeNull();
  });

  it('publishes a promotion limit at the brand with the version the trace reported and a required reason', async () => {
    api.resolution.mockImplementation((_tenantId: string, code: string) =>
      Promise.resolve(
        code === PERCENTAGE
          ? resolution(code, 3500, {
              cameFromDefault: false,
              source: 'SCOPED_VALUE',
              winningScope: 'BRAND',
              inspectedLevels: [{ scopeType: 'BRAND', outcome: 'VALUE' }],
              currentVersionAtScope: 4,
            })
          : resolution(code, DEFAULTS[code]),
      ),
    );
    await create();

    await edit(PERCENTAGE, '2500', '');
    expect(publishButton(PERCENTAGE).disabled).toBe(true);

    await edit(PERCENTAGE, '2500', 'Finance asked for a lower bar');
    expect(publishButton(PERCENTAGE).disabled).toBe(false);
    publishButton(PERCENTAGE).click();
    await flushMicrotasks();

    expect(api.setValue).toHaveBeenCalledWith(TENANT_ID, PERCENTAGE, {
      scopeType: 'BRAND',
      brandId: BRAND_ID,
      locationId: null,
      explicitNull: false,
      expectedVersion: 4,
      reason: 'Finance asked for a lower bar',
      integerValue: 2500,
    });
  });

  it('creates the first value with no expected version', async () => {
    await create();

    await edit(AMOUNT, '50000', 'First time for this brand');
    publishButton(AMOUNT).click();
    await flushMicrotasks();

    expect(api.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      AMOUNT,
      expect.objectContaining({ integerValue: 50000, expectedVersion: null }),
    );
  });

  it('refuses a draft that is not a whole number, or is outside the range, before asking the server', async () => {
    await create();

    for (const bad of ['', '12.5', '-3', 'abc', '10001']) {
      await edit(PERCENTAGE, bad, 'a reason');
      expect(publishButton(PERCENTAGE).disabled, `draft "${bad}"`).toBe(true);
    }
    await edit(PERCENTAGE, '10000', 'a reason');
    expect(publishButton(PERCENTAGE).disabled).toBe(false);
  });

  it('refuses an export limit the server cannot store, before asking it', async () => {
    // customers.pii_export_approval_threshold_rows is an Integer key: the server narrows the
    // request's number with Math.toIntExact, so anything above 2^31 - 1 used to be a 500 on Publish.
    await create();

    for (const tooBig of ['2147483648', '3000000000', '9007199254740991']) {
      await edit(EXPORT_ROWS, tooBig, 'a reason');
      expect(publishButton(EXPORT_ROWS).disabled, `draft "${tooBig}"`).toBe(true);
      expect(row(EXPORT_ROWS).querySelector('.invalid'), `draft "${tooBig}"`).not.toBeNull();
    }
    await edit(EXPORT_ROWS, '2147483647', 'a reason');
    expect(publishButton(EXPORT_ROWS).disabled).toBe(false);
    expect(row(EXPORT_ROWS).querySelector('.invalid')).toBeNull();
  });

  it('turns the markup switch on or off with a boolean value', async () => {
    await create();

    await edit(MARKUP, null, 'Markups are reviewed by finance already');
    const checkbox = row(MARKUP).querySelector('input[type="checkbox"]') as HTMLInputElement;
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    publishButton(MARKUP).click();
    await flushMicrotasks();

    expect(api.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      MARKUP,
      expect.objectContaining({ booleanValue: false, scopeType: 'BRAND' }),
    );
  });

  it('writes the export limit at the tenant whatever the scope bar says', async () => {
    scope.level.set('LOCATION');
    scope.locationId.set('location-1');
    await create();

    await edit(EXPORT_ROWS, '100', 'Our data protection officer asked for a lower bar');
    publishButton(EXPORT_ROWS).click();
    await flushMicrotasks();

    expect(api.setValue).toHaveBeenCalledWith(TENANT_ID, EXPORT_ROWS, {
      scopeType: 'TENANT',
      brandId: null,
      locationId: null,
      explicitNull: false,
      expectedVersion: null,
      reason: 'Our data protection officer asked for a lower bar',
      integerValue: 100,
    });
  });

  it('hands a limit back to what it inherits with an explicit null', async () => {
    api.resolution.mockImplementation((_tenantId: string, code: string) =>
      Promise.resolve(
        code === EXPORT_ROWS
          ? resolution(code, 100, {
              cameFromDefault: false,
              source: 'SCOPED_VALUE',
              winningScope: 'TENANT',
              inspectedLevels: [{ scopeType: 'TENANT', outcome: 'VALUE' }],
              currentVersionAtScope: 2,
            })
          : resolution(code, DEFAULTS[code]),
      ),
    );
    await create();

    const revert = Array.from(row(EXPORT_ROWS).querySelectorAll('button')).find((button) =>
      /inherit|revert/i.test(button.textContent ?? ''),
    );
    expect(revert, 'the set-here state offers a way back').toBeTruthy();
    revert?.click();
    await flushMicrotasks();

    expect(api.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      EXPORT_ROWS,
      expect.objectContaining({ scopeType: 'TENANT', explicitNull: true, expectedVersion: 2 }),
    );
  });

  it('keeps the form open and says why when another tab saved first', async () => {
    api.setValue.mockRejectedValue(
      new ApiError(ApiErrorCode.STALE_VERSION, 409, { status: 409, code: 'STALE_VERSION' }, null),
    );
    await create();

    await edit(PERCENTAGE, '2000', 'A reason');
    publishButton(PERCENTAGE).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(row(PERCENTAGE).querySelector('[role="alert"]')).toBeTruthy();
    expect(publishButton(PERCENTAGE)).toBeTruthy();
    expect(
      row(AMOUNT).querySelector('[role="alert"]'),
      'the refusal belongs to the field that met it',
    ).toBeNull();
  });

  it('says plainly that a limit is not the policy that makes a signature required', async () => {
    await create();

    expect(el().querySelector('[data-testid="approvals-policy-note"]')?.textContent).toContain(
      'approval policy',
    );
  });

  it('links the worklist', async () => {
    await create();

    const link = el().querySelector('[data-testid="approvals-worklist-link"]') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/staff/approvals');
  });

  it('shows the denied state when the caller may not read configuration', async () => {
    api.resolution.mockRejectedValue(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, { status: 403 }, null),
    );
    await create();

    expect(el().querySelector('.denied')).toBeTruthy();
    expect(el().querySelectorAll('q-inherited-field').length).toBe(0);
  });
});
