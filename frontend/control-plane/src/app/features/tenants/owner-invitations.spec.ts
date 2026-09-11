import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { OwnerInvitations } from './owner-invitations';
import { OwnerInvitationOverviewRow, TenantsApi } from './tenants-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

/** Sent, and nobody has opened it. */
const WAITING: OwnerInvitationOverviewRow = {
  tenantId: 'tenant-waiting',
  tenantSlug: 'waiting-kafe',
  tenantName: 'Waiting Kafe',
  tenantStatus: 'PROVISIONING',
  state: 'SENT',
  recipient: 'dilnoza.karimova@example.uz',
  emailMasked: 'd***a@example.uz',
  locale: 'ru',
  attempts: 2,
  lastErrorCode: 'SMTP_UNAVAILABLE',
  queuedAt: '2026-09-11T04:00:00Z',
  sentAt: '2026-09-11T04:05:00Z',
  openedAt: null,
  acceptedAt: null,
  expiresAt: '2026-09-14T04:05:00Z',
};

/**
 * The owner was linked by an onboarding run that predates invitations, so no
 * invitation row exists for this tenant at all -- the case a list built from
 * `owner_invitations` would not contain.
 */
const NEVER_TOLD: OwnerInvitationOverviewRow = {
  tenantId: 'tenant-untold',
  tenantSlug: 'never-told-osh',
  tenantName: 'Never Told Osh',
  tenantStatus: 'PROVISIONING',
  state: 'NONE',
  recipient: 'sardor@example.uz',
  emailMasked: 's***r@example.uz',
  locale: null,
  attempts: 0,
  lastErrorCode: null,
  queuedAt: null,
  sentAt: null,
  openedAt: null,
  acceptedAt: null,
  expiresAt: null,
};

const DONE: OwnerInvitationOverviewRow = {
  ...WAITING,
  tenantId: 'tenant-done',
  tenantSlug: 'accepted-osh',
  tenantName: 'Accepted Osh',
  tenantStatus: 'ACTIVE',
  state: 'ACCEPTED',
  lastErrorCode: null,
  openedAt: '2026-09-11T05:00:00Z',
  acceptedAt: '2026-09-11T05:02:00Z',
};

class FakeTenantsApi {
  readonly ownerInvitations = vi
    .fn<(state?: string) => Promise<readonly OwnerInvitationOverviewRow[]>>()
    .mockResolvedValue([]);
  readonly resendOwnerInvitation = vi
    .fn<(...args: unknown[]) => Promise<void>>()
    .mockResolvedValue(undefined);
}

describe('OwnerInvitations', () => {
  let fixture: ComponentFixture<OwnerInvitations>;
  let api: FakeTenantsApi;

  beforeEach(async () => {
    api = new FakeTenantsApi();
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [OwnerInvitations],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
      ],
    }).compileComponents();
  });

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(OwnerInvitations);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  /**
   * The screen is opened to answer one question, so it opens already asking it
   * rather than showing every tenant and making the operator filter.
   */
  it('opens on the tenants still waiting on an owner', async () => {
    api.ownerInvitations.mockResolvedValue([WAITING, NEVER_TOLD]);
    await render();

    expect(api.ownerInvitations).toHaveBeenCalledWith('OUTSTANDING');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain(
      ru['ownerInvitations.outstanding'].replace('{count}', '2'),
    );
  });

  it('shows the tenant never invited as such, with no times and a link to its onboarding', async () => {
    api.ownerInvitations.mockResolvedValue([NEVER_TOLD]);
    await render();

    const row = fixture.nativeElement.querySelector('tbody tr') as HTMLElement;
    expect(row.dataset['state']).toBe('NONE');
    expect(row.textContent).toContain(ru['ownerInvitations.state.NONE']);
    expect(row.querySelector('a')?.getAttribute('href')).toBe('/tenants/tenant-untold/onboarding');
    // Three time columns, all absent, all rendered as a dash rather than blank.
    expect(row.textContent?.match(/—/g) ?? []).toHaveLength(4);
  });

  it('shows the whole address when the server sends one and dims the mask when it does not', async () => {
    api.ownerInvitations.mockResolvedValue([WAITING, { ...NEVER_TOLD, recipient: null }]);
    await render();

    const cells = fixture.nativeElement.querySelectorAll('.recipient');
    expect(cells[0].textContent).toContain('dilnoza.karimova@example.uz');
    expect(cells[0].classList.contains('masked')).toBe(false);
    expect(cells[1].textContent).toContain('s***r@example.uz');
    expect(cells[1].classList.contains('masked')).toBe(true);
  });

  it('asks the server again when the filter changes', async () => {
    api.ownerInvitations.mockResolvedValue([WAITING, NEVER_TOLD, DONE]);
    await render();

    const all = [...fixture.nativeElement.querySelectorAll('.filter')].find(
      (button) => (button as HTMLElement).dataset['filter'] === 'ALL',
    ) as HTMLButtonElement;
    all.click();
    await settle();

    expect(api.ownerInvitations).toHaveBeenLastCalledWith('');
    expect(all.getAttribute('aria-pressed')).toBe('true');
  });

  it('resends with a reason and reloads, and offers no resend once the owner has accepted', async () => {
    api.ownerInvitations.mockResolvedValue([WAITING, DONE]);
    await render();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows[1].querySelector('.actions button')).toBeNull();

    (rows[0].querySelector('.actions button') as HTMLButtonElement).click();
    await settle();

    const form = fixture.nativeElement.querySelector('.resendForm') as HTMLFormElement;
    const reason = form.querySelector('input[type="text"]') as HTMLInputElement;
    reason.value = 'the owner says nothing arrived';
    reason.dispatchEvent(new Event('input'));
    const language = form.querySelector('select') as HTMLSelectElement;
    language.value = 'uz';
    language.dispatchEvent(new Event('change'));
    await settle();

    form.dispatchEvent(new Event('submit'));
    await settle();

    expect(api.resendOwnerInvitation).toHaveBeenCalledWith(
      'tenant-waiting',
      'the owner says nothing arrived',
      'uz',
    );
    expect(api.ownerInvitations).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('.resendForm')).toBeNull();
    // The row goes back to QUEUED, which is resendable, so the confirmation has
    // to live beside the button rather than in place of it.
    const after = fixture.nativeElement.querySelectorAll('tbody tr')[0] as HTMLElement;
    expect(after.querySelector('.success')?.textContent).toContain(ru['ownerInvitations.resent']);
    expect(after.querySelector('.actions button')).not.toBeNull();
  });

  it('will not resend without a reason', async () => {
    api.ownerInvitations.mockResolvedValue([WAITING]);
    await render();

    (fixture.nativeElement.querySelector('.actions button') as HTMLButtonElement).click();
    await settle();

    const form = fixture.nativeElement.querySelector('.resendForm') as HTMLFormElement;
    expect((form.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(true);
    form.dispatchEvent(new Event('submit'));
    await settle();

    expect(api.resendOwnerInvitation).not.toHaveBeenCalled();
  });

  it('reports a refusal rather than an empty list', async () => {
    api.ownerInvitations.mockRejectedValue(
      new ApiError({
        status: 403,
        code: 'INSUFFICIENT_CAPABILITY',
        detail: 'Requires tenant.onboarding.manage',
      }),
    );
    await render();

    expect(fixture.nativeElement.querySelector('.state.error')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('says so when nothing matches the filter', async () => {
    api.ownerInvitations.mockResolvedValue([]);
    await render();

    expect(fixture.nativeElement.textContent).toContain(ru['ownerInvitations.empty']);
  });
});
