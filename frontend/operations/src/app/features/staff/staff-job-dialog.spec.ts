import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ScopeGrant } from '../../core/auth/session-context';
import { GrantRequest, RoleDescriptor, ScopeDirectory } from './staff-api';
import { StaffJobDialog } from './staff-job-dialog';

const TENANT = 't1';

const ROLES: readonly RoleDescriptor[] = [
  { code: 'tenant-owner', scopeType: 'TENANT', capabilities: ['tenant.write', 'iam.grant.manage'] },
  {
    code: 'location-manager',
    scopeType: 'LOCATION',
    capabilities: ['order.approve', 'order.cancel'],
  },
  { code: 'courier-dispatcher', scopeType: 'BRAND', capabilities: ['courier.duty.manage'] },
];

const DIRECTORY: ScopeDirectory = {
  brands: [{ id: 'b1', displayName: 'Milliy' }],
  locations: [{ id: 'l1', brandId: 'b1', displayName: 'Chilonzor' }],
};

/** An operator holding location-manager's two capabilities at l1 only — never courier-dispatcher's, never tenant-owner's. */
const MY_SCOPES: readonly ScopeGrant[] = [
  {
    scope: { type: 'LOCATION', tenantId: TENANT, brandId: 'b1', locationId: 'l1' },
    roleCode: 'location-manager',
    capabilities: ['order.approve', 'order.cancel'],
  },
];

function render(scopes: readonly ScopeGrant[] = MY_SCOPES) {
  const fixture = TestBed.createComponent(StaffJobDialog);
  fixture.componentRef.setInput('tenantId', TENANT);
  fixture.componentRef.setInput('principalSubject', 'staff-1');
  fixture.componentRef.setInput('roles', ROLES);
  fixture.componentRef.setInput('directory', DIRECTORY);
  fixture.componentRef.setInput('myScopes', scopes);
  fixture.detectChanges();
  return fixture;
}

describe('StaffJobDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('ru');
  });

  it("lists only jobs the operator could confer somewhere — staff-and-access.md §0's corollary", () => {
    const fixture = render();
    const options = [
      ...fixture.nativeElement.querySelectorAll('[data-testid="staff-job-dialog-role"] option'),
    ]
      .map((o: HTMLOptionElement) => o.value)
      .filter(Boolean);

    expect(options).toEqual(['location-manager']);
  });

  it('auto-selects and fixes the scope when the job has exactly one grantable location', () => {
    const fixture = render();
    const select = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-role"]',
    ) as HTMLSelectElement;
    select.value = 'location-manager';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-job-dialog-scope"]'),
    ).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Chilonzor');
  });

  it('offers every grantable brand when the operator holds the job at more than one', () => {
    const twoBrands: ScopeDirectory = {
      brands: [
        { id: 'b1', displayName: 'Milliy' },
        { id: 'b2', displayName: 'Second' },
      ],
      locations: [],
    };
    const scopes: readonly ScopeGrant[] = [
      {
        scope: { type: 'BRAND', tenantId: TENANT, brandId: 'b1', locationId: null },
        roleCode: 'courier-dispatcher',
        capabilities: ['courier.duty.manage'],
      },
      {
        scope: { type: 'BRAND', tenantId: TENANT, brandId: 'b2', locationId: null },
        roleCode: 'courier-dispatcher',
        capabilities: ['courier.duty.manage'],
      },
    ];
    const fixture = TestBed.createComponent(StaffJobDialog);
    fixture.componentRef.setInput('tenantId', TENANT);
    fixture.componentRef.setInput('principalSubject', 'staff-1');
    fixture.componentRef.setInput('roles', ROLES);
    fixture.componentRef.setInput('directory', twoBrands);
    fixture.componentRef.setInput('myScopes', scopes);
    fixture.detectChanges();

    const roleSelect = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-role"]',
    ) as HTMLSelectElement;
    roleSelect.value = 'courier-dispatcher';
    roleSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const scopeOptions = [
      ...fixture.nativeElement.querySelectorAll('[data-testid="staff-job-dialog-scope"] option'),
    ]
      .filter((o: HTMLOptionElement) => o.value !== '')
      .map((o: HTMLOptionElement) => o.textContent);
    expect(scopeOptions).toEqual(['Milliy', 'Second']);
  });

  it('refuses to submit without a reason', () => {
    const fixture = render();
    const submissions: GrantRequest[] = [];
    fixture.componentInstance.submitted.subscribe((s) => submissions.push(s));

    const select = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-role"]',
    ) as HTMLSelectElement;
    select.value = 'location-manager';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-job-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([]);
  });

  it('emits a GrantRequest carrying the resolved scope and trimmed reason', () => {
    const fixture = render();
    const submissions: GrantRequest[] = [];
    fixture.componentInstance.submitted.subscribe((s) => submissions.push(s));

    const roleSelect = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-role"]',
    ) as HTMLSelectElement;
    roleSelect.value = 'location-manager';
    roleSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const reason = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-reason"]',
    ) as HTMLInputElement;
    reason.value = '  Hired for Chilonzor  ';
    reason.dispatchEvent(new Event('input'));
    // Otherwise the confirm button's `[disabled]` binding is still the
    // pre-reason render, and a genuinely `disabled` DOM button ignores `.click()`.
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-job-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([
      {
        principalSubject: 'staff-1',
        roleCode: 'location-manager',
        brandId: 'b1',
        locationId: 'l1',
        reason: 'Hired for Chilonzor',
        validUntil: undefined,
      },
    ]);
  });

  it('shows a job with no covering scope as simply absent, not disabled', () => {
    const fixture = render([]);
    const options = [
      ...fixture.nativeElement.querySelectorAll('[data-testid="staff-job-dialog-role"] option'),
    ]
      .map((o: HTMLOptionElement) => o.value)
      .filter(Boolean);

    expect(options).toEqual([]);
  });
});
