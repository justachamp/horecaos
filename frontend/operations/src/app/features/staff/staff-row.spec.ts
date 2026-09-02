import { describe, expect, it } from 'vitest';

import { GrantView, ScopeDirectory } from './staff-api';
import {
  COMPANY_WIDE_GROUP,
  groupIntoPeople,
  groupsFor,
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
