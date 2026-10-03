import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { StaffMember } from '../../core/api/staff-member';
import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { OwnProfile } from '../../core/auth/own-profile';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { GrantView, StaffApi } from './staff-api';
import { StaffMemberDetailPane } from './staff-member-detail-pane';
import { StaffMembersApi } from './staff-members-api';
import { staffMember, staffMemberDetail } from './staff-member.testing';

function grant(overrides: Partial<GrantView>): GrantView {
  return {
    id: 'g1',
    principalSubject: 'staff-1',
    roleCode: 'location-staff',
    scopeType: 'LOCATION',
    scopeId: 'l1',
    status: 'ACTIVE',
    grantedBy: 'owner-1',
    reason: 'Onboarded',
    validFrom: '2026-08-01T00:00:00Z',
    validUntil: null,
    revokedAt: null,
    revokedBy: null,
    revokedReason: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly unknown[]>([]);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function makeApi(grants: readonly GrantView[]) {
  return {
    listGrants: vi.fn().mockResolvedValue(grants),
    roles: vi.fn().mockResolvedValue([
      {
        code: 'location-staff',
        scopeType: 'LOCATION',
        capabilities: ['order.read', 'order.cancel'],
      },
    ]),
    scopeDirectory: vi.fn().mockResolvedValue({
      brands: [{ id: 'b1', displayName: 'Milliy' }],
      locations: [{ id: 'l1', brandId: 'b1', displayName: 'Chilonzor' }],
    }),
    telegramLinks: vi.fn().mockResolvedValue([]),
    grant: vi.fn().mockResolvedValue({ grantId: 'new-grant' }),
    revoke: vi.fn().mockResolvedValue({ changed: true, outcome: 'revoked' }),
    issueTelegramLinkCode: vi.fn().mockResolvedValue({ code: 'ABC123', command: '/link ABC123' }),
    revokeTelegramLink: vi.fn().mockResolvedValue({ changed: true, outcome: 'revoked' }),
    operatorTodayOrderCounts: vi.fn().mockResolvedValue({
      createdCount: 0,
      acceptedCount: 0,
      businessDayFrom: '2026-09-25T00:00:00Z',
      businessDayTo: '2026-09-26T00:00:00Z',
    }),
  };
}

interface SetUpOptions {
  /** What the list returns (masked); defaults to no records at all. */
  readonly listed?: readonly StaffMember[];
  /** What the single read returns for the first listed member. */
  readonly detail?: StaffMember;
  /** The capabilities the viewer holds, as the wire names them. */
  readonly held?: readonly string[];
  /** The signed-in subject. */
  readonly viewer?: string;
  readonly membersApi?: Record<string, unknown>;
}

async function setUp(
  grants: readonly GrantView[],
  subjectId = 'staff-1',
  options: SetUpOptions = {},
) {
  const api = makeApi(grants);
  const membersApi = {
    list: vi.fn().mockResolvedValue(options.listed ?? []),
    detail: vi.fn().mockResolvedValue(options.detail ?? (options.listed ?? [])[0]),
    update: vi.fn(),
    endEmployment: vi.fn(),
    emergencyContacts: vi.fn().mockResolvedValue({ contacts: [], memberVersion: 3 }),
    replaceEmergencyContacts: vi.fn(),
    ...options.membersApi,
  };
  const held = new Set(options.held ?? []);
  const ownProfile = { apply: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [StaffMemberDetailPane],
    providers: [
      provideRouter([]),
      { provide: StaffApi, useValue: api },
      { provide: StaffMembersApi, useValue: membersApi },
      {
        provide: SessionCapabilities,
        useValue: { has: (capability: string) => held.has(capability) },
      },
      { provide: OwnProfile, useValue: ownProfile },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      { provide: Auth, useValue: { subject: signal(options.viewer ?? 'someone-else') } },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<StaffMemberDetailPane> =
    TestBed.createComponent(StaffMemberDetailPane);
  fixture.componentRef.setInput('subjectId', subjectId);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api, membersApi, ownProfile };
}

describe('StaffMemberDetailPane', () => {
  it('shows the honest not-found state for a subject with no grant at all', async () => {
    const { fixture } = await setUp([grant({ principalSubject: 'somebody-else' })], 'staff-1');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Такого сотрудника нет');
  });

  it('renders the assignment card with the role, scope, and reason', async () => {
    const { fixture } = await setUp([grant({})]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilonzor');
    expect(text).toContain('Onboarded');
  });

  it('expands "Что можно делать" into plain sentences grouped by area, never a dotted code', async () => {
    const { fixture } = await setUp([grant({})]);
    (fixture.nativeElement.querySelector('.card button.text-button') as HTMLButtonElement).click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('order.read');
    expect(text).not.toContain('order.cancel');
    expect(text).toContain('Заказы');
  });

  it('offers "Убрать" only when it is not the operator locking themselves out of their own last assignment', async () => {
    const { fixture } = await setUp([grant({})], 'staff-1'); // signed-in subject is 'someone-else'
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-remove"]'),
    ).not.toBeNull();
  });

  it('removes a grant via the access dialog and reloads', async () => {
    const { fixture, api } = await setUp([grant({ id: 'g1' })]);

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-detail-remove"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const reason = fixture.nativeElement.querySelector(
      '[data-testid="staff-access-dialog-reason"]',
    ) as HTMLInputElement;
    reason.value = 'Moved to another branch';
    reason.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.revoke).toHaveBeenCalledWith('t1', 'g1', 'Moved to another branch');
    expect(api.listGrants).toHaveBeenCalledTimes(2);
  });

  it('shows the Telegram link state and the telegramUserId on the Безопасность tab', async () => {
    const api = makeApi([grant({})]);
    api.telegramLinks.mockResolvedValue([
      {
        id: 'link-1',
        principalSubject: 'staff-1',
        telegramUserId: 555,
        linkedAt: '2026-09-01T00:00:00Z',
      },
    ]);
    await TestBed.configureTestingModule({
      imports: [StaffMemberDetailPane],
      providers: [
        provideRouter([]),
        { provide: StaffApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: Auth, useValue: { subject: signal('someone-else') } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    const fixture = TestBed.createComponent(StaffMemberDetailPane);
    fixture.componentRef.setInput('subjectId', 'staff-1');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const tabs = [...fixture.nativeElement.querySelectorAll('.tab')] as HTMLButtonElement[];
    tabs[1].click(); // «Безопасность»
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Telegram привязан');
    // The console already receives telegramUserId (operations-gap-map.md
    // 9/X.1's own complaint was that nothing rendered it) — the raw number
    // is the only identifying value this table carries (V0105: no display
    // name or username is stored).
    expect(text).toContain('555');
  });

  it('renders today’s created/accepted counts on the card (Staff 9.2d)', async () => {
    const api = makeApi([grant({})]);
    api.operatorTodayOrderCounts.mockResolvedValue({
      createdCount: 4,
      acceptedCount: 2,
      businessDayFrom: '2026-09-25T00:00:00Z',
      businessDayTo: '2026-09-26T00:00:00Z',
    });
    await TestBed.configureTestingModule({
      imports: [StaffMemberDetailPane],
      providers: [
        provideRouter([]),
        { provide: StaffApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: Auth, useValue: { subject: signal('someone-else') } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    const fixture = TestBed.createComponent(StaffMemberDetailPane);
    fixture.componentRef.setInput('subjectId', 'staff-1');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.operatorTodayOrderCounts).toHaveBeenCalledWith('t1', 'staff-1');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Создано сегодня: 4');
    expect(text).toContain('Принято сегодня: 2');
  });

  it('navigates to the person’s own activity on «Смотреть журнал действий» (Staff 9.3)', async () => {
    const { fixture } = await setUp([grant({})]);
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-detail-view-activity"]',
      ) as HTMLButtonElement
    ).click();

    expect(navigateSpy).toHaveBeenCalledWith(['/staff/activity'], {
      queryParams: { actor: 'staff-1' },
    });
  });

  it('unlinks a staff member’s Telegram account with a reason, and reloads the links', async () => {
    const api = makeApi([grant({})]);
    api.telegramLinks.mockResolvedValueOnce([
      {
        id: 'link-1',
        principalSubject: 'staff-1',
        telegramUserId: 555,
        linkedAt: '2026-09-01T00:00:00Z',
      },
    ]);
    api.telegramLinks.mockResolvedValueOnce([]);
    await TestBed.configureTestingModule({
      imports: [StaffMemberDetailPane],
      providers: [
        provideRouter([]),
        { provide: StaffApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: Auth, useValue: { subject: signal('someone-else') } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    const fixture = TestBed.createComponent(StaffMemberDetailPane);
    fixture.componentRef.setInput('subjectId', 'staff-1');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const tabs = [...fixture.nativeElement.querySelectorAll('.tab')] as HTMLButtonElement[];
    tabs[1].click(); // «Безопасность»
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="telegram-unlink"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const reason = fixture.nativeElement.querySelector(
      '[data-testid="telegram-unlink-reason"]',
    ) as HTMLInputElement;
    reason.value = 'Device was lost';
    reason.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="telegram-unlink-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.revokeTelegramLink).toHaveBeenCalledWith('t1', 'link-1', 'Device was lost');
    expect(api.telegramLinks).toHaveBeenCalledTimes(2);
  });
});

describe('StaffMemberDetailPane: the tenant’s record of the person (ADR 0139)', () => {
  const GRANT = grant({ principalSubject: 'staff-1' });

  function text(fixture: ComponentFixture<StaffMemberDetailPane>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function click(fixture: ComponentFixture<StaffMemberDetailPane>, testId: string): void {
    (fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function type(
    fixture: ComponentFixture<StaffMemberDetailPane>,
    testId: string,
    value: string,
  ): void {
    const input = fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as
      HTMLInputElement | HTMLSelectElement;
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  }

  it('titles the card with the name the tenant keeps, and shows the S-reference, not the identifier', async () => {
    const { fixture } = await setUp([GRANT], 'staff-1', {
      listed: [staffMember({ principalSubject: 'staff-1' })],
      detail: staffMemberDetail({ principalSubject: 'staff-1' }),
    });

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-title"]')?.textContent,
    ).toContain('Aziza Karimova');
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-reference"]')?.textContent,
    ).toContain('S-0001');
  });

  it('shows the photo from the single read when the person has one, else initials', async () => {
    const withPhoto = await setUp([GRANT], 'staff-1', {
      listed: [staffMember({ principalSubject: 'staff-1', hasPhoto: true })],
      detail: staffMemberDetail({
        principalSubject: 'staff-1',
        hasPhoto: true,
        photoUrl: 'https://files.example.uz/p.jpg?sig=1',
      }),
    });
    expect(
      withPhoto.fixture.nativeElement
        .querySelector('[data-testid="staff-detail-photo"]')
        ?.getAttribute('src'),
    ).toBe('https://files.example.uz/p.jpg?sig=1');
  });

  it('falls back to the identifier and says there is no profile when the tenant keeps no record', async () => {
    const { fixture } = await setUp([GRANT], 'staff-1');

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-title"]')?.textContent,
    ).toContain('staff-1');
    expect(text(fixture)).toContain('Профиль ещё не создан');
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-tab-profile"]'),
    ).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-tab-contacts"]'),
    ).toBeNull();
  });

  it('opens a card for a former colleague who has a record and no job', async () => {
    const { fixture } = await setUp([], 'former-1', {
      listed: [
        staffMember({
          principalSubject: 'former-1',
          employmentStatus: 'ENDED',
          employedUntil: '2026-09-20',
          hasActiveAccess: false,
        }),
      ],
    });

    expect(text(fixture)).not.toContain('Такого сотрудника нет');
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-employment"]')?.textContent,
    ).toContain('Не работает');
  });

  it('shows who granted a job by name when the tenant keeps one for them, else by identifier', async () => {
    const { fixture } = await setUp([GRANT], 'staff-1', {
      listed: [
        staffMember({ principalSubject: 'staff-1' }),
        staffMember({
          memberId: 'm2',
          principalSubject: 'owner-1',
          firstName: 'Olim',
          lastName: 'Rahimov',
          displayName: 'Olim Rahimov',
        }),
      ],
      detail: staffMemberDetail({ principalSubject: 'staff-1' }),
    });

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-granted-by"]')?.textContent,
    ).toContain('Olim Rahimov');
  });

  it('shows the full phone on the Профиль tab as a call link, with the sign-in number kept apart', async () => {
    const { fixture } = await setUp([GRANT], 'staff-1', {
      listed: [staffMember({ principalSubject: 'staff-1' })],
      detail: staffMemberDetail({ principalSubject: 'staff-1', phone: '+998901234542' }),
    });

    click(fixture, 'staff-detail-tab-profile');

    const phone = fixture.nativeElement.querySelector('[data-testid="staff-detail-phone"]');
    expect(phone?.textContent).toContain('+998901234542');
    expect(phone?.getAttribute('href')).toBe('tel:+998901234542');
    expect(text(fixture)).toContain('Номер для входа задаётся при приглашении');
  });

  it('offers editing and ending employment only to a viewer who may manage profiles', async () => {
    const member = staffMemberDetail({ principalSubject: 'staff-1' });
    const without = await setUp([GRANT], 'staff-1', {
      listed: [member],
      detail: member,
      held: [],
    });
    click(without.fixture, 'staff-detail-tab-profile');
    expect(
      without.fixture.nativeElement.querySelector('[data-testid="staff-detail-edit-profile"]'),
    ).toBeNull();
    expect(
      without.fixture.nativeElement.querySelector('[data-testid="staff-detail-end-employment"]'),
    ).toBeNull();
  });

  it('does not offer to end the viewer’s own employment', async () => {
    const member = staffMemberDetail({ principalSubject: 'staff-1' });
    const { fixture } = await setUp([GRANT], 'staff-1', {
      listed: [member],
      detail: member,
      held: ['STAFF_PROFILE_MANAGE'],
      viewer: 'staff-1',
    });

    click(fixture, 'staff-detail-tab-profile');

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-edit-profile"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-end-employment"]'),
    ).toBeNull();
  });

  it('saves an edit as a replace carrying the record’s version, then shows the new name and tells the shell chip when it is the viewer', async () => {
    const member = staffMemberDetail({ principalSubject: 'staff-1', version: 4 });
    const updated = staffMemberDetail({
      principalSubject: 'staff-1',
      firstName: 'Azizakhon',
      displayName: 'Azizakhon Karimova',
      version: 5,
    });
    const { fixture, membersApi, ownProfile } = await setUp([GRANT], 'staff-1', {
      listed: [member],
      detail: member,
      held: ['STAFF_PROFILE_MANAGE'],
      viewer: 'staff-1',
      membersApi: { update: vi.fn().mockResolvedValue(updated) },
    });
    click(fixture, 'staff-detail-tab-profile');
    click(fixture, 'staff-detail-edit-profile');

    type(fixture, 'staff-profile-first-name', 'Azizakhon');
    click(fixture, 'staff-profile-save');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(membersApi.update).toHaveBeenCalledTimes(1);
    const [tenantId, memberId, request, version] = membersApi.update.mock.calls[0];
    expect(tenantId).toBe('t1');
    expect(memberId).toBe('m1');
    expect(version).toBe(4);
    expect(request).toMatchObject({ firstName: 'Azizakhon', lastName: 'Karimova' });
    // Employment untouched: the status goes only when it changed.
    expect(request.employmentStatus).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-title"]')?.textContent,
    ).toContain('Azizakhon Karimova');
    expect(ownProfile.apply).toHaveBeenCalledWith(updated);
  });

  it('shows the platform’s refusal in the form and keeps it open when the record changed under the editor', async () => {
    const member = staffMemberDetail({ principalSubject: 'staff-1' });
    const { fixture } = await setUp([GRANT], 'staff-1', {
      listed: [member],
      detail: member,
      held: ['STAFF_PROFILE_MANAGE'],
      membersApi: {
        update: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr-3')),
      },
    });
    click(fixture, 'staff-detail-tab-profile');
    click(fixture, 'staff-detail-edit-profile');
    click(fixture, 'staff-profile-save');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-profile-error"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-profile-form"]'),
    ).not.toBeNull();
  });

  it('does not offer to edit a person whose full record could not be read: the masked list has no phone and no employee number, and a replace would clear them', async () => {
    const listed = staffMember({ principalSubject: 'staff-1', version: 4 });
    const full = staffMemberDetail({ principalSubject: 'staff-1', version: 4 });
    const detail = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(ApiErrorCode.INTERNAL_ERROR, 503, null, 'corr-9'))
      .mockResolvedValue(full);
    const { fixture, membersApi } = await setUp([GRANT], 'staff-1', {
      listed: [listed],
      held: ['STAFF_PROFILE_MANAGE'],
      membersApi: { detail, update: vi.fn().mockResolvedValue(full) },
    });

    // The card still opens on what the list knows, and says what it could not read.
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-title"]')?.textContent,
    ).toContain('Aziza Karimova');
    click(fixture, 'staff-detail-tab-profile');
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-edit-profile"]'),
    ).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-profile-unavailable"]'),
    ).not.toBeNull();
    expect(membersApi.update).not.toHaveBeenCalled();

    // Trying again reads the full record, and only then is editing offered.
    click(fixture, 'staff-detail-profile-retry');
    await flushMicrotasks();
    fixture.detectChanges();
    expect(detail).toHaveBeenCalledTimes(2);
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-profile-unavailable"]'),
    ).toBeNull();
    click(fixture, 'staff-detail-edit-profile');
    click(fixture, 'staff-profile-save');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(membersApi.update).toHaveBeenCalledTimes(1);
    const request = membersApi.update.mock.calls[0][2];
    expect(request.phone).toBe('+998901234542');
    expect(request.employeeNumber).toBe('E-17');
  });

  it('ends employment with a reason and the last day, then reloads the jobs and reports how many were taken away', async () => {
    const member = staffMemberDetail({ principalSubject: 'staff-1', version: 2 });
    const ended = staffMemberDetail({
      principalSubject: 'staff-1',
      employmentStatus: 'ENDED',
      employedUntil: '2026-09-30',
      version: 3,
      hasActiveAccess: false,
    });
    const { fixture, api, membersApi } = await setUp([GRANT], 'staff-1', {
      listed: [member],
      detail: member,
      held: ['STAFF_PROFILE_MANAGE'],
      membersApi: {
        endEmployment: vi
          .fn()
          .mockResolvedValue({ member: ended, revokedGrants: 1, remainingGrants: 0 }),
      },
    });
    click(fixture, 'staff-detail-tab-profile');
    click(fixture, 'staff-detail-end-employment');

    type(fixture, 'staff-access-dialog-reason', 'Resigned');
    type(fixture, 'staff-access-dialog-until', '2026-09-30');
    click(fixture, 'staff-access-dialog-confirm');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(membersApi.endEmployment).toHaveBeenCalledWith(
      't1',
      'm1',
      { reason: 'Resigned', employedUntil: '2026-09-30' },
      2,
    );
    expect(api.listGrants).toHaveBeenCalledTimes(2); // initial load + reload after the act
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-notice"]')?.textContent,
    ).toContain('Снято должностей: 1');
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-employment"]')?.textContent,
    ).toContain('Не работает');
  });

  it('says so, and offers to finish, when ending employment left a job behind', async () => {
    const member = staffMemberDetail({ principalSubject: 'staff-1', version: 2 });
    const endedWithAccess = staffMemberDetail({
      principalSubject: 'staff-1',
      employmentStatus: 'ENDED',
      employedUntil: '2026-09-30',
      version: 3,
      accessDrift: true,
    });
    const { fixture } = await setUp([GRANT], 'staff-1', {
      listed: [member],
      detail: member,
      held: ['STAFF_PROFILE_MANAGE'],
      membersApi: {
        endEmployment: vi
          .fn()
          .mockResolvedValue({ member: endedWithAccess, revokedGrants: 1, remainingGrants: 2 }),
      },
    });
    click(fixture, 'staff-detail-tab-profile');
    click(fixture, 'staff-detail-end-employment');
    type(fixture, 'staff-access-dialog-reason', 'Resigned');
    click(fixture, 'staff-access-dialog-confirm');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-notice"]')?.textContent,
    ).toContain('не удалось снять должностей: 2');
    // The grant is still active in the reloaded list, so the drift banner is up.
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-drift"]'),
    ).not.toBeNull();
  });

  it('does not read the emergency contacts when the card opens, only when asked', async () => {
    const member = staffMemberDetail({ principalSubject: 'staff-1' });
    const { fixture, membersApi } = await setUp([GRANT], 'staff-1', {
      listed: [member],
      detail: member,
      held: ['STAFF_EMERGENCY_CONTACT_READ'],
    });

    click(fixture, 'staff-detail-tab-contacts');
    expect(membersApi.emergencyContacts).not.toHaveBeenCalled();

    click(fixture, 'staff-emergency-show');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(membersApi.emergencyContacts).toHaveBeenCalledTimes(1);
    expect(membersApi.emergencyContacts).toHaveBeenCalledWith('t1', 'm1');
  });
});
