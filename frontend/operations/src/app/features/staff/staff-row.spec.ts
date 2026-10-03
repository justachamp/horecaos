import { describe, expect, it } from 'vitest';

import { GrantView, ScopeDirectory } from './staff-api';
import { staffMember } from './staff-member.testing';
import {
  COMPANY_WIDE_GROUP,
  groupIntoPeople,
  groupsFor,
  hasNoAccess,
  initialsOf,
  matchesQuery,
  nameOf,
  referenceOf,
  sortByAttention,
  statusOf,
} from './staff-row';

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

describe('groupIntoPeople', () => {
  it('groups multiple grants under one person', () => {
    const people = groupIntoPeople([
      grant({ id: 'g1', principalSubject: 'a' }),
      grant({ id: 'g2', principalSubject: 'a', scopeId: 'l2' }),
      grant({ id: 'g3', principalSubject: 'b' }),
    ]);

    expect(people).toHaveLength(2);
    expect(people.find((p) => p.principalSubject === 'a')?.grants).toHaveLength(2);
    expect(people.find((p) => p.principalSubject === 'b')?.grants).toHaveLength(1);
  });

  it('an empty grant list produces no people', () => {
    expect(groupIntoPeople([])).toEqual([]);
  });
});

describe('statusOf', () => {
  const now = new Date('2026-09-02T10:00:00Z');

  it('is OK when at least one active grant has no near expiry', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ status: 'ACTIVE', validUntil: null })],
    };
    expect(statusOf(person, now)).toEqual({ kind: 'OK', weight: 5 });
  });

  it('is ALL_REVOKED when every grant is revoked, carrying the most recent revocation reason', () => {
    const person = {
      principalSubject: 'a',
      grants: [
        grant({
          id: 'g1',
          status: 'REVOKED',
          revokedAt: '2026-08-01T00:00:00Z',
          revokedReason: 'First reason',
        }),
        grant({
          id: 'g2',
          status: 'REVOKED',
          revokedAt: '2026-08-20T00:00:00Z',
          revokedReason: 'Left the company',
        }),
      ],
    };

    expect(statusOf(person, now)).toEqual({
      kind: 'ALL_REVOKED',
      weight: 0,
      lastRevokedReason: 'Left the company',
    });
  });

  it('is ALL_REVOKED with a null reason when there is no revoked grant to explain it', () => {
    // Not reachable through groupIntoPeople(activeOnly) today, but statusOf must not throw on it.
    const person = { principalSubject: 'a', grants: [] };
    expect(statusOf(person, now)).toEqual({
      kind: 'ALL_REVOKED',
      weight: 0,
      lastRevokedReason: null,
    });
  });

  it('is EXPIRING_SOON when an active grant lapses within 7 days', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ status: 'ACTIVE', validUntil: '2026-09-05T00:00:00Z' })],
    };
    expect(statusOf(person, now)).toEqual({
      kind: 'EXPIRING_SOON',
      weight: 2,
      validUntil: '2026-09-05T00:00:00Z',
    });
  });

  it('is not EXPIRING_SOON for a grant lapsing more than 7 days out', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ status: 'ACTIVE', validUntil: '2026-10-01T00:00:00Z' })],
    };
    expect(statusOf(person, now)).toEqual({ kind: 'OK', weight: 5 });
  });

  it('picks the soonest expiry among several active grants', () => {
    const person = {
      principalSubject: 'a',
      grants: [
        grant({ id: 'g1', status: 'ACTIVE', validUntil: '2026-09-06T00:00:00Z' }),
        grant({ id: 'g2', status: 'ACTIVE', validUntil: '2026-09-03T00:00:00Z' }),
      ],
    };
    expect(statusOf(person, now)).toEqual({
      kind: 'EXPIRING_SOON',
      weight: 2,
      validUntil: '2026-09-03T00:00:00Z',
    });
  });
});

describe('sortByAttention', () => {
  it('orders ALL_REVOKED, then EXPIRING_SOON, then OK, then alphabetically within a weight', () => {
    const now = new Date('2026-09-02T10:00:00Z');
    const ok = { principalSubject: 'zed', grants: [grant({ status: 'ACTIVE' })] };
    const revoked = {
      principalSubject: 'aaa',
      grants: [grant({ status: 'REVOKED', revokedAt: '2026-08-01T00:00:00Z', revokedReason: 'r' })],
    };
    const expiring = {
      principalSubject: 'mmm',
      grants: [grant({ status: 'ACTIVE', validUntil: '2026-09-03T00:00:00Z' })],
    };

    const ordered = sortByAttention([ok, expiring, revoked], now).map((p) => p.principalSubject);
    expect(ordered).toEqual(['aaa', 'mmm', 'zed']);
  });
});

const DIRECTORY: ScopeDirectory = {
  brands: [{ id: 'b1', displayName: 'Milliy' }],
  locations: [{ id: 'l1', brandId: 'b1', displayName: 'Chilonzor' }],
};

describe('groupsFor', () => {
  it('places a TENANT-scope job in the company-wide group', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ scopeType: 'TENANT', scopeId: null })],
    };
    expect(groupsFor(person, DIRECTORY)).toEqual([COMPANY_WIDE_GROUP]);
  });

  it('resolves a LOCATION-scope job to the location display name', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ scopeType: 'LOCATION', scopeId: 'l1' })],
    };
    expect(groupsFor(person, DIRECTORY)).toEqual(['Chilonzor']);
  });

  it('a person with jobs at two scopes appears in both groups', () => {
    const person = {
      principalSubject: 'a',
      grants: [
        grant({ id: 'g1', scopeType: 'TENANT', scopeId: null }),
        grant({ id: 'g2', scopeType: 'LOCATION', scopeId: 'l1' }),
      ],
    };
    expect(groupsFor(person, DIRECTORY)).toEqual([COMPANY_WIDE_GROUP, 'Chilonzor']);
  });

  it('ignores a revoked grant — only active jobs place a person in a group', () => {
    const person = {
      principalSubject: 'a',
      grants: [
        grant({
          scopeType: 'LOCATION',
          scopeId: 'l1',
          status: 'REVOKED',
          revokedAt: 'x',
          revokedReason: 'y',
        }),
      ],
    };
    expect(groupsFor(person, DIRECTORY)).toEqual([]);
  });
});

describe('the staff record joined to the jobs (ADR 0139)', () => {
  const now = new Date('2026-09-02T10:00:00Z');

  it('makes one person of a subject that has jobs and a record, carrying both', () => {
    const people = groupIntoPeople(
      [grant({ principalSubject: 'a' })],
      [staffMember({ principalSubject: 'a' })],
    );

    expect(people).toHaveLength(1);
    expect(people[0].grants).toHaveLength(1);
    expect(people[0].member?.displayReference).toBe('S-0001');
  });

  it('keeps a subject that has jobs and no record, with no member', () => {
    const people = groupIntoPeople([grant({ principalSubject: 'legacy' })], []);

    expect(people).toHaveLength(1);
    expect(people[0].member).toBeNull();
  });

  it('keeps a record that has no job at all: the list is where an owner looks for a former colleague', () => {
    const people = groupIntoPeople([], [staffMember({ principalSubject: 'former' })]);

    expect(people).toHaveLength(1);
    expect(people[0].grants).toEqual([]);
    expect(people[0].member?.principalSubject).toBe('former');
  });

  it('names a person only from a name that was typed, never from the S-reference fallback', () => {
    const named = { principalSubject: 'a', grants: [], member: staffMember() };
    const unnamed = {
      principalSubject: 'b',
      grants: [],
      member: staffMember({
        firstName: null,
        lastName: null,
        displayName: 'S-0007',
        displayReference: 'S-0007',
      }),
    };
    const none = { principalSubject: 'c', grants: [], member: null };

    expect(nameOf(named)).toBe('Aziza Karimova');
    expect(nameOf(unnamed)).toBeNull();
    expect(referenceOf(unnamed)).toBe('S-0007');
    expect(nameOf(none)).toBeNull();
    expect(referenceOf(none)).toBeNull();
  });

  it('takes the initials from the first letters of the first and last name', () => {
    expect(initialsOf({ principalSubject: 'a', grants: [], member: staffMember() })).toBe('AK');
    expect(
      initialsOf({
        principalSubject: 'a',
        grants: [],
        member: staffMember({ firstName: 'ёлка', lastName: null }),
      }),
    ).toBe('Ё');
    expect(initialsOf({ principalSubject: 'a', grants: [], member: null })).toBe('');
  });

  it('is ENDED once employment has ended and no job is left', () => {
    const person = {
      principalSubject: 'a',
      grants: [
        grant({ status: 'REVOKED', revokedAt: '2026-09-01T00:00:00Z', revokedReason: 'Ended' }),
      ],
      member: staffMember({ employmentStatus: 'ENDED', employedUntil: '2026-09-01' }),
    };

    expect(statusOf(person, now)).toEqual({ kind: 'ENDED', weight: 0, endedOn: '2026-09-01' });
    expect(hasNoAccess(statusOf(person, now))).toBe(true);
  });

  it('is ACCESS_DRIFT, not ENDED, when employment has ended and a job is still active', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ status: 'ACTIVE' })],
      member: staffMember({ employmentStatus: 'ENDED', employedUntil: '2026-09-01' }),
    };

    expect(statusOf(person, now)).toEqual({
      kind: 'ACCESS_DRIFT',
      weight: 0,
      endedOn: '2026-09-01',
    });
    expect(hasNoAccess(statusOf(person, now))).toBe(false);
  });

  it('is INVITED while the record is PENDING, even with no open invitation to say so', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ status: 'ACTIVE' })],
      member: staffMember({ employmentStatus: 'PENDING' }),
    };

    expect(statusOf(person, now)).toEqual({ kind: 'INVITED', weight: 3 });
  });

  it('is ON_LEAVE for someone on leave who holds a job, ranking below an ordinary person needing nothing', () => {
    const person = {
      principalSubject: 'a',
      grants: [grant({ status: 'ACTIVE' })],
      member: staffMember({ employmentStatus: 'ON_LEAVE' }),
    };

    expect(statusOf(person, now)).toEqual({ kind: 'ON_LEAVE', weight: 4 });
  });

  it('puts an access drift ahead of everything else, and orders the rest by name', () => {
    const drift = {
      principalSubject: 'z-drift',
      grants: [grant({ status: 'ACTIVE' })],
      member: staffMember({
        principalSubject: 'z-drift',
        firstName: 'Яна',
        lastName: null,
        employmentStatus: 'ENDED',
        employedUntil: '2026-09-01',
      }),
    };
    const ana = {
      principalSubject: 'a',
      grants: [grant({ status: 'ACTIVE' })],
      member: staffMember({ principalSubject: 'a', firstName: 'Анна', lastName: null }),
    };
    const bob = {
      principalSubject: 'b',
      grants: [grant({ status: 'ACTIVE' })],
      member: staffMember({ principalSubject: 'b', firstName: 'Боб', lastName: null }),
    };

    const ordered = sortByAttention([bob, ana, drift], now).map((p) => p.principalSubject);

    expect(ordered).toEqual(['z-drift', 'a', 'b']);
  });
});

describe('matchesQuery', () => {
  const person = {
    principalSubject: 'Subject-XYZ',
    grants: [],
    member: staffMember({ maskedPhone: '+998 90 ••• •• 42' }),
  };

  it('matches a substring of the name, the reference or the subject, ignoring case', () => {
    expect(matchesQuery(person, 'KARIM')).toBe(true);
    expect(matchesQuery(person, 's-00')).toBe(true);
    expect(matchesQuery(person, 'subject-x')).toBe(true);
  });

  it('matches everyone on a blank query', () => {
    expect(matchesQuery(person, '   ')).toBe(true);
  });

  it('does not match the phone', () => {
    expect(matchesQuery(person, '+998 90')).toBe(false);
    expect(matchesQuery(person, '42')).toBe(false);
  });
});
