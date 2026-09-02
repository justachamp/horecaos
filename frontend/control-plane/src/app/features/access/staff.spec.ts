import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { AccessApi, PendingApprovalResponse, PlatformGrantResponse, PlatformGrantView } from './access-api';
import { Staff } from './staff';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const GRANT: PlatformGrantView = {
  id: 'grant-1',
  principalSubject: 'staff-a',
  roleCode: 'platform-support',
  status: 'ACTIVE',
  grantedBy: 'owner',
};

const PENDING: PendingApprovalResponse = {
  id: 'req-1',
  actionCode: 'tenant.activate',
  parametersHash: 'hash',
  scopeType: 'TENANT',
  scopeId: 'tenant-1',
  thresholdDescription: 'Any tenant activation needs a second signature.',
  policyVersion: 1,
  requiredApproverCapability: 'TENANT_WRITE',
  requestedBy: 'staff-a',
  requestedAt: '2026-09-01T00:00:00Z',
  expiresAt: '2026-09-02T00:00:00Z',
  mayDecide: true,
};

class FakeAccessApi {
  readonly listPlatformGrants = vi.fn<() => Promise<PlatformGrantView[]>>();
  readonly grantPlatform = vi.fn<() => Promise<PlatformGrantResponse>>();
  readonly revokePlatformGrant = vi.fn<() => Promise<PlatformGrantResponse>>();
  readonly grantTenant = vi.fn();
  readonly pendingApprovals = vi.fn<() => Promise<{ items: PendingApprovalResponse[]; nextCursor: string | null }>>();
  readonly decide = vi.fn();
}

describe('Staff', () => {
  let fixture: ComponentFixture<Staff>;
  let api: FakeAccessApi;

  beforeEach(async () => {
    api = new FakeAccessApi();
    api.listPlatformGrants.mockResolvedValue([GRANT]);
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [Staff],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessApi, useValue: api },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Staff);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('lists platform-scope grants', () => {
    expect(fixture.nativeElement.textContent).toContain('staff-a');
    expect(fixture.nativeElement.textContent).toContain('platform-support');
  });

  it('shows the awaiting-approval outcome distinctly when granting is policy-gated', async () => {
    api.grantPlatform.mockResolvedValue({ outcome: 'AWAITING_APPROVAL', grantId: null, approvalRequestId: 'req-9' });
    api.listPlatformGrants.mockResolvedValue([GRANT]);

    const form = fixture.nativeElement.querySelectorAll('.panel')[0].querySelector('form');
    const [principal, role, reason] = form.querySelectorAll('input');
    principal.value = 'new-admin';
    principal.dispatchEvent(new Event('input'));
    role.value = 'platform-support';
    role.dispatchEvent(new Event('input'));
    reason.value = 'onboarding a new support engineer';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    form.dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.grantPlatform).toHaveBeenCalledWith(
      'new-admin',
      'platform-support',
      'onboarding a new support engineer',
    );
    expect(fixture.nativeElement.textContent).toContain('Ожидает вторую подпись');
  });

  it('loads and decides a pending approval, removing it from the list once decided', async () => {
    api.pendingApprovals.mockResolvedValue({ items: [PENDING], nextCursor: null });
    api.decide.mockResolvedValue({ id: 'req-1', actionCode: 'tenant.activate', status: 'APPROVED' });

    const approvalsPanel = fixture.nativeElement.querySelectorAll('.panel')[2];
    const tenantInput = approvalsPanel.querySelector('input');
    tenantInput.value = 'tenant-1';
    tenantInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    approvalsPanel.querySelector('form').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('tenant.activate');

    const reasonInput = fixture.nativeElement.querySelector('.decideReason');
    reasonInput.value = 'reviewed, looks correct';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ) as HTMLButtonElement[];
    const approveButton = buttons.find((button) => button.textContent?.trim() === 'Одобрить')!;
    approveButton.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.decide).toHaveBeenCalledWith('tenant-1', 'req-1', 'APPROVE', 'reviewed, looks correct');
    expect(fixture.nativeElement.textContent).not.toContain('tenant.activate');
  });

  it('refuses to let the requester decide their own request', async () => {
    api.pendingApprovals.mockResolvedValue({
      items: [{ ...PENDING, mayDecide: false }],
      nextCursor: null,
    });

    const approvalsPanel = fixture.nativeElement.querySelectorAll('.panel')[2];
    const tenantInput = approvalsPanel.querySelector('input');
    tenantInput.value = 'tenant-1';
    tenantInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    approvalsPanel.querySelector('form').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Вы запросили это');
  });
});
