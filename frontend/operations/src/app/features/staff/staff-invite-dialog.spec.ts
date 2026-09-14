import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ScopeGrant } from '../../core/auth/session-context';
import { RoleDescriptor, ScopeDirectory, StaffInvitationRequest } from './staff-api';
import { StaffInviteDialog } from './staff-invite-dialog';

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
  const fixture = TestBed.createComponent(StaffInviteDialog);
  fixture.componentRef.setInput('tenantId', TENANT);
  fixture.componentRef.setInput('roles', ROLES);
  fixture.componentRef.setInput('directory', DIRECTORY);
  fixture.componentRef.setInput('myScopes', scopes);
  fixture.detectChanges();
  return fixture;
}

function fillValidForm(fixture: ReturnType<typeof render>): void {
  const nativeElement = fixture.nativeElement as HTMLElement;
  const name = nativeElement.querySelector(
    '[data-testid="staff-invite-dialog-name"]',
  ) as HTMLInputElement;
  name.value = 'Aziza Karimova';
  name.dispatchEvent(new Event('input'));

  const phone = nativeElement.querySelector(
    '[data-testid="staff-invite-dialog-phone"]',
  ) as HTMLInputElement;
  phone.value = '90 123 45 67';
  phone.dispatchEvent(new Event('input'));

  const role = nativeElement.querySelector(
    '[data-testid="staff-invite-dialog-role"]',
  ) as HTMLSelectElement;
  role.value = 'location-manager';
  role.dispatchEvent(new Event('change'));
  fixture.detectChanges();

  const reason = nativeElement.querySelector(
    '[data-testid="staff-invite-dialog-reason"]',
  ) as HTMLInputElement;
  reason.value = 'New hire at Chilonzor';
  reason.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

describe('StaffInviteDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(I18n).setLocale('ru');
  });

  it("lists only jobs the operator could confer somewhere — staff-and-access.md §0's corollary", () => {
    const fixture = render();
    const options = [
      ...fixture.nativeElement.querySelectorAll('[data-testid="staff-invite-dialog-role"] option'),
    ]
      .map((o: HTMLOptionElement) => o.value)
      .filter(Boolean);

    expect(options).toEqual(['location-manager']);
  });

  it('auto-selects and fixes the scope when the job has exactly one grantable location', () => {
    const fixture = render();
    const select = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-role"]',
    ) as HTMLSelectElement;
    select.value = 'location-manager';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-invite-dialog-scope"]'),
    ).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Chilonzor');
  });

  it('shows the Сможет/Не сможет preview for the selected job', () => {
    const fixture = render();
    const select = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-role"]',
    ) as HTMLSelectElement;
    select.value = 'location-manager';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const preview = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-preview"]',
    ) as HTMLElement;
    expect(preview).not.toBeNull();
    expect(preview.textContent).toContain('Принимать заказы');
  });

  it('masks the phone as +998 __ ___ __ __ and keeps only nine digits', () => {
    const fixture = render();
    const phone = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-phone"]',
    ) as HTMLInputElement;

    phone.value = '901234567extra';
    phone.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(phone.value).toBe('90 123 45 67');
    expect(fixture.nativeElement.textContent).toContain('+998');
  });

  it('keeps the confirm button disabled — and so submits nothing — until a name, a complete phone, a job and a reason are all present', () => {
    const fixture = render();
    const submissions: StaffInvitationRequest[] = [];
    fixture.componentInstance.submitted.subscribe((s) => submissions.push(s));

    const confirm = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-confirm"]',
    ) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);

    confirm.click();
    expect(submissions).toEqual([]);

    fillValidForm(fixture);
    expect(confirm.disabled).toBe(false);
  });

  it('emits a StaffInvitationRequest with the split name, full phone and resolved scope', () => {
    const fixture = render();
    const submissions: StaffInvitationRequest[] = [];
    fixture.componentInstance.submitted.subscribe((s) => submissions.push(s));

    fillValidForm(fixture);
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-invite-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([
      {
        firstName: 'Aziza',
        lastName: 'Karimova',
        phone: '+998901234567',
        email: undefined,
        roleCode: 'location-manager',
        brandId: 'b1',
        locationId: 'l1',
        reason: 'New hire at Chilonzor',
        validUntil: undefined,
        locale: 'ru',
      },
    ]);
  });

  it('locks every field while submitting', () => {
    const fixture = render();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();

    const name = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-name"]',
    ) as HTMLInputElement;
    const confirm = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-confirm"]',
    ) as HTMLButtonElement;

    expect(name.disabled).toBe(true);
    expect(confirm.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Приглашаем');
  });

  it('shows the server error banner', () => {
    const fixture = render();
    fixture.componentRef.setInput('serverError', 'Something went wrong');
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-invite-dialog-serverError"]')
        ?.textContent,
    ).toContain('Something went wrong');
  });

  it('renders the duplicate-phone state as an inline field error with a link to the person, never a generic conflict', () => {
    const fixture = render();
    fixture.componentRef.setInput('duplicatePhoneSubject', 'existing-subject-1');
    fixture.detectChanges();

    const duplicate = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-duplicate"]',
    ) as HTMLElement;
    expect(duplicate.textContent).toContain('уже есть доступ');
    const link = duplicate.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/staff/existing-subject-1');
  });

  it('shows the created link with a copy affordance once the invite succeeds, instead of the form', () => {
    const fixture = render();
    fixture.componentRef.setInput('createdLink', 'https://ops.example.uz/invite#token=abc123');
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-invite-dialog-name"]'),
    ).toBeNull();
    const linkField = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-link"]',
    ) as HTMLInputElement;
    expect(linkField.value).toBe('https://ops.example.uz/invite#token=abc123');
  });

  it('closes without confirming when nothing was typed', () => {
    const confirmSpy = vi.spyOn(window, 'confirm');
    const fixture = render();
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    (fixture.nativeElement.querySelector('.invite-dialog__dismiss') as HTMLButtonElement).click();

    expect(confirmSpy).not.toHaveBeenCalled();
    expect(dismissed).toBe(true);
  });

  it('confirms before closing once something was typed, and aborts on decline', () => {
    const fixture = render();
    const name = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-name"]',
    ) as HTMLInputElement;
    name.value = 'Aziza';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    vi.spyOn(window, 'confirm').mockReturnValue(false);
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    (fixture.nativeElement.querySelector('.invite-dialog__dismiss') as HTMLButtonElement).click();

    expect(dismissed).toBe(false);
  });

  it('shows a job with no covering scope as simply absent, not disabled', () => {
    const fixture = render([]);
    const options = [
      ...fixture.nativeElement.querySelectorAll('[data-testid="staff-invite-dialog-role"] option'),
    ]
      .map((o: HTMLOptionElement) => o.value)
      .filter(Boolean);

    expect(options).toEqual([]);
  });
});
