import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { applyRegionalFormats, resetRegionalFormats } from '../../core/format/regional-format';
import { I18n } from '../../core/i18n/i18n';
import { GrantView } from './staff-api';
import { StaffApi } from './staff-api';
import { StaffMember } from '../../core/api/staff-member';
import { StaffMembersApi } from './staff-members-api';
import { staffMember } from './staff-member.testing';
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { StaffPage } from './staff-page';

function grant(overrides: Partial<GrantView>): GrantView {
  return {
    id: 'g1',
    principalSubject: 'subject-1',
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
  /** A tenant-wide grant-manage holder, so every job in the fixture's `roles()` is offerable in the job dialog. */
  readonly scopes = signal<readonly unknown[]>([
    {
      scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
      roleCode: 'tenant-owner',
      capabilities: ['order.read', 'iam.grant.manage'],
    },
  ]);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function makeApi(
  grants: readonly GrantView[],
  invitations: readonly {
    invitationId: string;
    principalSubject: string;
    state: string;
    invitedAt: string;
  }[] = [],
) {
  return {
    listGrants: vi.fn().mockResolvedValue(grants),
    roles: vi
      .fn()
      .mockResolvedValue([
        { code: 'location-staff', scopeType: 'LOCATION', capabilities: ['order.read'] },
      ]),
    scopeDirectory: vi.fn().mockResolvedValue({
      brands: [{ id: 'b1', displayName: 'Milliy' }],
      locations: [{ id: 'l1', brandId: 'b1', displayName: 'Chilonzor' }],
    }),
    telegramLinks: vi.fn().mockResolvedValue([]),
    grant: vi.fn().mockResolvedValue({ grantId: 'new-grant' }),
    revoke: vi.fn().mockResolvedValue({ changed: true, outcome: 'revoked' }),
    staffInvitations: vi.fn().mockResolvedValue(invitations),
    invite: vi.fn(),
    resendStaffInvitation: vi
      .fn()
      .mockResolvedValue({ inviteLink: 'https://ops.example.uz/invite#token=fresh' }),
    revokeStaffInvitation: vi.fn().mockResolvedValue({ changed: true }),
  };
}

async function setUp(
  grants: readonly GrantView[],
  subject = 'the-operator',
  invitations: readonly {
    invitationId: string;
    principalSubject: string;
    state: string;
    invitedAt: string;
  }[] = [],
  members: readonly StaffMember[] | Error = [],
) {
  const api = makeApi(grants, invitations);
  const membersApi = {
    list: vi
      .fn()
      .mockImplementation(() =>
        members instanceof Error ? Promise.reject(members) : Promise.resolve(members),
      ),
  };
  await TestBed.configureTestingModule({
    imports: [StaffPage],
    providers: [
      provideRouter([]),
      { provide: StaffApi, useValue: api },
      { provide: StaffMembersApi, useValue: membersApi },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      { provide: Auth, useValue: { subject: signal(subject) } },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<StaffPage> = TestBed.createComponent(StaffPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api, membersApi };
}

describe('StaffPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows the §0 rule sentence as the empty state when nobody has a job yet', async () => {
    const { fixture } = await setUp([]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('одна или несколько должностей');
  });

  it('lists a person derived from their grant, with the role and scope shown', async () => {
    const { fixture } = await setUp([
      grant({ principalSubject: 'staff-1', roleCode: 'location-staff' }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('staff-1');
    expect(text).toContain('Chilonzor');
  });

  it('captions a fully-revoked person with the revocation reason, not a bare badge', async () => {
    const { fixture } = await setUp([
      grant({
        principalSubject: 'staff-2',
        status: 'REVOKED',
        revokedAt: '2026-08-30T00:00:00Z',
        revokedBy: 'owner-1',
        revokedReason: 'Left the company',
      }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Left the company');
  });

  it('computes status pill counts before filtering, so they do not move when a filter narrows the table', async () => {
    const { fixture } = await setUp([
      grant({ principalSubject: 'staff-1', status: 'ACTIVE' }),
      grant({
        principalSubject: 'staff-2',
        status: 'REVOKED',
        revokedAt: '2026-08-30T00:00:00Z',
        revokedBy: 'owner-1',
        revokedReason: 'Left',
      }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('2'); // "Все (2)"
  });

  it('hides destructive actions on the signed-in operator’s own row', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'the-operator', status: 'ACTIVE' })],
      'the-operator',
    );
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-suspend"]')).toBeNull();
  });

  it('suspending fans out one revoke call per active grant and reloads', async () => {
    const { fixture, api } = await setUp([
      grant({ id: 'g1', principalSubject: 'staff-1', roleCode: 'location-staff', scopeId: 'l1' }),
      grant({
        id: 'g2',
        principalSubject: 'staff-1',
        roleCode: 'location-staff',
        scopeId: 'l1',
        scopeType: 'BRAND',
      }),
    ]);

    (
      fixture.nativeElement.querySelector('[data-testid="staff-row-suspend"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const reasonInput = fixture.nativeElement.querySelector(
      '[data-testid="staff-access-dialog-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Left the company';
    reasonInput.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.revoke).toHaveBeenCalledTimes(2);
    expect(api.revoke).toHaveBeenCalledWith('t1', 'g1', 'Left the company', expect.any(String));
    expect(api.revoke).toHaveBeenCalledWith('t1', 'g2', 'Left the company', expect.any(String));
    // Staff 9.3c: a bulk suspension's N revokes must correlate as one action.
    const [firstCall, secondCall] = (api.revoke as ReturnType<typeof vi.fn>).mock.calls;
    expect(firstCall[3]).toBe(secondCall[3]);
    expect(api.listGrants).toHaveBeenCalledTimes(2); // initial load + reload after suspend
  });

  it('opens the job dialog and grants on submit', async () => {
    const { fixture, api } = await setUp([grant({ principalSubject: 'staff-1' })]);

    (
      fixture.nativeElement.querySelector('[data-testid="staff-row-add-job"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="staff-job-dialog"]')).not.toBeNull();

    const roleSelect = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-role"]',
    ) as HTMLSelectElement;
    roleSelect.value = 'location-staff';
    roleSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const reason = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-reason"]',
    ) as HTMLInputElement;
    reason.value = 'Second branch';
    reason.dispatchEvent(new Event('input'));
    // Otherwise the confirm button's `[disabled]` binding is still the
    // pre-reason render, and a genuinely `disabled` DOM button ignores `.click()`.
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-job-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.grant).toHaveBeenCalledWith(
      't1',
      expect.objectContaining({
        principalSubject: 'staff-1',
        roleCode: 'location-staff',
        reason: 'Second branch',
      }),
    );
  });

  it('shows «Приглашён» and resend/revoke instead of add-job/suspend for a person with an open invitation (ADR 0116)', async () => {
    const { fixture, api } = await setUp(
      [grant({ principalSubject: 'staff-1', roleCode: 'location-staff' })],
      'the-operator',
      [
        {
          invitationId: 'inv-1',
          principalSubject: 'staff-1',
          state: 'SENT',
          invitedAt: '2026-09-14T09:00:00Z',
        },
      ],
    );

    expect(fixture.nativeElement.textContent).toContain('Приглашён');
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-add-job"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-suspend"]')).toBeNull();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-row-revoke-invite"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const reasonInput = fixture.nativeElement.querySelector(
      '[data-testid="staff-access-dialog-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Changed their mind';
    reasonInput.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.revokeStaffInvitation).toHaveBeenCalledWith('t1', 'inv-1', 'Changed their mind');
  });

  it('shows the fresh one-time link a resend returns, reusing the invite dialog (S01 finding fix)', async () => {
    const { fixture, api } = await setUp(
      [grant({ principalSubject: 'staff-1', roleCode: 'location-staff' })],
      'the-operator',
      [
        {
          invitationId: 'inv-1',
          principalSubject: 'staff-1',
          state: 'SENT',
          invitedAt: '2026-09-14T09:00:00Z',
        },
      ],
    );

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-row-resend-invite"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const reasonInput = fixture.nativeElement.querySelector(
      '[data-testid="staff-access-dialog-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Lost the original link';
    reasonInput.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.resendStaffInvitation).toHaveBeenCalledWith('t1', 'inv-1', 'Lost the original link');
    // Before this fix the returned link was discarded entirely and the
    // operator had no way to see it -- the access-confirmation dialog
    // simply closed. Now the invite dialog reopens showing it.
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-access-dialog-reason"]'),
    ).toBeNull();
    const linkField = fixture.nativeElement.querySelector(
      '[data-testid="staff-invite-dialog-link"]',
    ) as HTMLInputElement | null;
    expect(linkField?.value).toBe('https://ops.example.uz/invite#token=fresh');
  });
});

describe('StaffPage: the tenant’s record of each person (ADR 0139)', () => {
  function textOf(fixture: ComponentFixture<StaffPage>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function names(fixture: ComponentFixture<StaffPage>): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="staff-row-name"]'),
    ).map((element) => element.textContent?.trim() ?? '');
  }

  it('shows the person’s name, masked phone and reference, not the account identifier', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'subject-1' })],
      'the-operator',
      [],
      [staffMember({ principalSubject: 'subject-1' })],
    );

    const text = textOf(fixture);
    expect(text).toContain('Aziza Karimova');
    expect(text).toContain('+998 90 ••• •• 42');
    expect(text).toContain('S-0001');
    expect(text).not.toContain('subject-1');
  });

  it('writes the masked phone in the brand’s pattern, the bullets staying where they are', async () => {
    // A mask bullet counts as a digit slot (phone.ts), so the pattern re-writes the masked string and
    // never reveals a digit the platform withheld.
    applyRegionalFormats({ phoneDisplayPattern: '+### (##) ###-##-##' });
    try {
      const { fixture } = await setUp(
        [grant({ principalSubject: 'subject-1' })],
        'the-operator',
        [],
        [staffMember({ principalSubject: 'subject-1' })],
      );

      expect(textOf(fixture)).toContain('+998 (90) •••-••-42');
    } finally {
      resetRegionalFormats();
    }
  });

  it('never shows a full phone number on the list, even if the payload carried one', async () => {
    // The platform sends `phone: null` on a list. This pins that the template
    // reads only `maskedPhone`, so a payload that ever carried the full number
    // would still not print it.
    const { fixture } = await setUp(
      [grant({ principalSubject: 'subject-1' })],
      'the-operator',
      [],
      [staffMember({ principalSubject: 'subject-1', phone: '+998901234542' })],
    );

    expect(textOf(fixture)).not.toContain('+998901234542');
    expect(textOf(fixture)).not.toContain('901234542');
  });

  it('lists an account with a job and no record by its identifier, and says it has no profile yet', async () => {
    const { fixture } = await setUp([grant({ principalSubject: 'legacy-subject' })]);

    expect(textOf(fixture)).toContain('legacy-subject');
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-row-no-record"]')?.textContent,
    ).toContain('Профиль ещё не создан');
  });

  it('shows a record with no name by its S-reference, never by the subject', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'subject-1' })],
      'the-operator',
      [],
      [
        staffMember({
          principalSubject: 'subject-1',
          firstName: null,
          lastName: null,
          displayName: 'S-0042',
          displayReference: 'S-0042',
        }),
      ],
    );

    expect(names(fixture)).toEqual(['S-0042']);
    expect(textOf(fixture)).not.toContain('subject-1');
  });

  it('lists a former colleague who has a record and no job, as «Не работает» with the end date', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'someone-else' })],
      'the-operator',
      [],
      [
        staffMember({
          memberId: 'm9',
          principalSubject: 'former-1',
          firstName: 'Bobur',
          lastName: 'Aliyev',
          displayName: 'Bobur Aliyev',
          employmentStatus: 'ENDED',
          employedUntil: '2026-09-20',
          hasActiveAccess: false,
        }),
      ],
    );

    const text = textOf(fixture);
    expect(text).toContain('Bobur Aliyev');
    expect(text).toContain('Не работает');
    expect(text).toContain('Работа завершена 2026-09-20');
  });

  it('flags a person whose employment ended while a job is still active, and offers to take the job away, not to add one', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'subject-1', status: 'ACTIVE' })],
      'the-operator',
      [],
      [
        staffMember({
          principalSubject: 'subject-1',
          employmentStatus: 'ENDED',
          employedUntil: '2026-09-20',
          accessDrift: true,
        }),
      ],
    );

    expect(textOf(fixture)).toContain('Доступ остался');
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-suspend"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-add-job"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-restore"]')).toBeNull();
  });

  it('counts a person whose employment has ended and who has no job among the suspended', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'subject-1' })],
      'the-operator',
      [],
      [
        staffMember({ principalSubject: 'subject-1' }),
        staffMember({
          memberId: 'm9',
          principalSubject: 'former-1',
          employmentStatus: 'ENDED',
          employedUntil: '2026-09-20',
          hasActiveAccess: false,
        }),
      ],
    );

    expect(textOf(fixture)).toContain('Приостановлены (1)');
    expect(textOf(fixture)).toContain('Активные (1)');
  });

  it('marks someone on leave', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'subject-1' })],
      'the-operator',
      [],
      [staffMember({ principalSubject: 'subject-1', employmentStatus: 'ON_LEAVE' })],
    );

    expect(textOf(fixture)).toContain('В отпуске');
  });

  it('finds a person by a piece of their name and by their reference', async () => {
    const { fixture } = await setUp(
      [grant({ id: 'g1', principalSubject: 'a' }), grant({ id: 'g2', principalSubject: 'b' })],
      'the-operator',
      [],
      [
        staffMember({ memberId: 'm1', principalSubject: 'a', displayReference: 'S-0001' }),
        staffMember({
          memberId: 'm2',
          principalSubject: 'b',
          firstName: 'Bobur',
          lastName: 'Aliyev',
          displayName: 'Bobur Aliyev',
          displayReference: 'S-0002',
        }),
      ],
    );
    const search = fixture.nativeElement.querySelector('[data-testid="staff-search"]');

    search.value = 'aliy';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(names(fixture)).toEqual(['Bobur Aliyev']);

    search.value = 's-0001';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(names(fixture)).toEqual(['Aziza Karimova']);
  });

  it('does not match a typed phone number, which would put it where an access log keeps it', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'a' })],
      'the-operator',
      [],
      [staffMember({ principalSubject: 'a', maskedPhone: '+998 90 ••• •• 42' })],
    );
    const search = fixture.nativeElement.querySelector('[data-testid="staff-search"]');

    search.value = '+998 90';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(names(fixture)).toEqual([]);
  });

  it('orders people of the same standing by name with Russian collation', async () => {
    const { fixture } = await setUp(
      [
        grant({ id: 'g1', principalSubject: 'a' }),
        grant({ id: 'g2', principalSubject: 'b' }),
        grant({ id: 'g3', principalSubject: 'c' }),
      ],
      'the-operator',
      [],
      [
        staffMember({
          memberId: 'm1',
          principalSubject: 'a',
          firstName: 'Ёлкин',
          lastName: null,
          displayName: 'Ёлкин',
        }),
        staffMember({
          memberId: 'm2',
          principalSubject: 'b',
          firstName: 'Яна',
          lastName: null,
          displayName: 'Яна',
        }),
        staffMember({
          memberId: 'm3',
          principalSubject: 'c',
          firstName: 'Анна',
          lastName: null,
          displayName: 'Анна',
        }),
      ],
    );

    expect(names(fixture)).toEqual(['Анна', 'Ёлкин', 'Яна']);
  });

  it('still lists everyone, by identifier, when the read of the records fails', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'subject-1' })],
      'the-operator',
      [],
      new Error('refused'),
    );

    expect(textOf(fixture)).toContain('subject-1');
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-suspend"]')).not.toBeNull();
  });
});

describe('StaffPage: the keys (staff-and-access.md §2, gap map row X.1)', () => {
  function key(value: string, target?: HTMLElement): boolean {
    const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true });
    Object.defineProperty(event, 'target', {
      value: target ?? document.activeElement ?? document.body,
    });
    return TestBed.inject(ShortcutRegistry).dispatch(event);
  }

  const two = [
    grant({ principalSubject: 'staff-1', roleCode: 'location-staff' }),
    grant({ id: 'g2', principalSubject: 'staff-2', roleCode: 'location-staff' }),
  ];

  const rows = (fixture: ComponentFixture<StaffPage>): HTMLElement[] => [
    ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('tbody tr.row'),
  ];

  it('is on the cheat-sheet while the list is, and binds nothing that suspends, restores or removes a person', async () => {
    const { fixture } = await setUp(two);
    const scope = TestBed.inject(ShortcutRegistry)
      .scopes()
      .find((candidate) => candidate.id === 'staff-list')!;

    expect(scope.title()).toBe('Люди');
    expect(scope.shortcuts.flatMap((shortcut) => shortcut.caps)).toEqual([
      '/',
      'j',
      '↓',
      'k',
      '↑',
      'Enter',
      'Esc',
    ]);
    fixture.destroy();
    expect(
      TestBed.inject(ShortcutRegistry)
        .scopes()
        .some((candidate) => candidate.id === 'staff-list'),
    ).toBe(false);
  });

  it('/ goes to the search box, and j, k and the arrows move along the rows', async () => {
    const { fixture } = await setUp(two);
    const host = fixture.nativeElement as HTMLElement;

    expect(key('/', document.body)).toBe(true);
    expect(document.activeElement).toBe(host.querySelector('[data-testid="staff-search"]'));
    (document.activeElement as HTMLElement).blur();

    key('j');
    expect(document.activeElement).toBe(rows(fixture)[0]);
    key('j');
    expect(document.activeElement).toBe(rows(fixture)[1]);
    key('k');
    expect(document.activeElement).toBe(rows(fixture)[0]);
  });

  it('Enter opens the person the cursor is on', async () => {
    const { fixture } = await setUp(two);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const opened = vi.fn();
    rows(fixture)[1].addEventListener('click', opened);
    key('j');
    key('j');

    expect(key('Enter')).toBe(true);

    expect(opened).toHaveBeenCalledOnce();
  });

  it('Esc in the search box empties it, and a second Esc lets go of it', async () => {
    const { fixture } = await setUp(two);
    const search = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="staff-search"]',
    )!;
    search.focus();
    search.value = 'staff-2';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(rows(fixture)).toHaveLength(1);

    expect(key('Escape', search)).toBe(true);
    fixture.detectChanges();
    expect(search.value).toBe('');
    expect(rows(fixture)).toHaveLength(2);
    expect(document.activeElement).toBe(search);

    key('Escape', search);
    expect(document.activeElement).not.toBe(search);
  });

  it('leaves a letter typed in the search box to the box', async () => {
    const { fixture } = await setUp(two);
    const search = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="staff-search"]',
    )!;
    search.focus();

    expect(key('j', search)).toBe(false);
  });
});
