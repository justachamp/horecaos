import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { ScopeGrant } from '../../core/auth/session-context';
import { GrantView, StaffApi } from './staff-api';
import { StaffMembersApi } from './staff-members-api';

/**
 * ADR 0103: a location manager's `iam.grant.manage` stops at her branch, so the
 * Staff section reaches the grants through the branch's own routes. Every spec
 * here is the wire: the paths the manager's session produces, not a mock of
 * `StaffApi`.
 */

const TENANT = 'tenant-1';
const BRAND = 'brand-1';
const CHILONZOR = 'location-chilonzor';

const LOCATION_MANAGER: ScopeGrant = {
  scope: { type: 'LOCATION', tenantId: TENANT, brandId: BRAND, locationId: CHILONZOR },
  roleCode: 'location-manager',
  // The session writes enum names, not codes.
  capabilities: ['ORDER_APPROVE', 'IAM_GRANT_MANAGE', 'STAFF_PROFILE_READ'],
};

const OWNER: ScopeGrant = {
  scope: { type: 'TENANT', tenantId: TENANT, brandId: null, locationId: null },
  roleCode: 'tenant-owner',
  capabilities: ['IAM_GRANT_MANAGE'],
};

/** Lets the promise chain behind a flushed request run before the next request is looked for. */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

const BRANCH = `/api/v1/operations/tenants/${TENANT}/brands/${BRAND}/locations/${CHILONZOR}`;

const COOK: GrantView = {
  id: 'g-cook',
  principalSubject: 'cook-1',
  roleCode: 'location-staff',
  scopeType: 'LOCATION',
  scopeId: CHILONZOR,
  status: 'ACTIVE',
  grantedBy: 'manager-1',
  reason: 'Hired',
  validFrom: '2026-10-01T10:00:00Z',
  validUntil: null,
  revokedAt: null,
  revokedBy: null,
  revokedReason: null,
};

describe('StaffApi for a branch manager', () => {
  let api: StaffApi;
  let http: HttpTestingController;

  function configure(scopes: readonly ScopeGrant[]): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: CurrentTenant, useValue: { scopes: signal(scopes) } },
      ],
    });
    api = TestBed.inject(StaffApi);
    http = TestBed.inject(HttpTestingController);
  }

  afterEach(() => http.verify());

  describe('with a grant at one branch', () => {
    beforeEach(() => configure([LOCATION_MANAGER]));

    it("lists the branch's own grants, never the company-wide route", async () => {
      const promise = api.listGrants(TENANT, true);
      http.expectOne(url(`${BRANCH}/grant-places`)).flush({
        brands: [{ id: BRAND, displayName: 'Main' }],
        locations: [{ id: CHILONZOR, brandId: BRAND, displayName: 'Chilonzor' }],
      });
      // The directory resolves first, then the list.
      await settle();
      const list = http.expectOne((r) => r.url === url(`${BRANCH}/grants`));
      expect(list.request.params.get('includeInactive')).toBe('true');
      list.flush([COOK]);

      expect(await promise).toEqual([COOK]);
    });

    it('gives a job through the branch route and sends the place in the path, not the body', async () => {
      const promise = api.grant(TENANT, {
        principalSubject: 'new-hire',
        roleCode: 'location-staff',
        brandId: BRAND,
        locationId: CHILONZOR,
        reason: 'Hired this morning',
      });

      const request = http.expectOne(url(`${BRANCH}/grants`));
      expect(request.request.method).toBe('POST');
      expect(request.request.headers.has('Idempotency-Key')).toBe(true);
      expect(request.request.body).toEqual({
        principalSubject: 'new-hire',
        roleCode: 'location-staff',
        reason: 'Hired this morning',
      });
      request.flush({ grantId: 'g-new' });

      expect(await promise).toEqual({ grantId: 'g-new' });
    });

    it('refuses to ask for a company-level job, which no branch route covers', async () => {
      await expect(
        api.grant(TENANT, {
          principalSubject: 'someone',
          roleCode: 'tenant-finance',
          reason: 'no',
        }),
      ).rejects.toThrow(/never company-wide/);
    });

    it('takes a job away through the route of the place the grant lies at', async () => {
      const listed = api.listGrants(TENANT, false);
      http.expectOne(url(`${BRANCH}/grant-places`)).flush({
        brands: [{ id: BRAND, displayName: 'Main' }],
        locations: [{ id: CHILONZOR, brandId: BRAND, displayName: 'Chilonzor' }],
      });
      await settle();
      http.expectOne((r) => r.url === url(`${BRANCH}/grants`)).flush([COOK]);
      await listed;

      const promise = api.revoke(TENANT, COOK.id, 'Left the branch');
      const request = http.expectOne(url(`${BRANCH}/grants/${COOK.id}`));
      expect(request.request.method).toBe('DELETE');
      request.flush({ changed: true, outcome: 'revoked' });

      expect(await promise).toEqual({ changed: true, outcome: 'revoked' });
    });

    it("reads the jobs on offer from the branch's own catalogue", async () => {
      const promise = api.roles(TENANT);
      http.expectOne(url(`${BRANCH}/grant-roles`)).flush([
        {
          code: 'location-staff',
          scopeType: 'LOCATION',
          capabilities: ['order.approve'],
          grantable: true,
        },
      ]);

      expect((await promise)[0].grantable).toBe(true);
    });

    it('invites into the branch through the branch route', async () => {
      const promise = api.invite(TENANT, {
        firstName: 'Aziza',
        lastName: 'Karimova',
        phone: '+998901234567',
        roleCode: 'location-staff',
        brandId: BRAND,
        locationId: CHILONZOR,
        reason: 'new hire',
      });

      const request = http.expectOne(url(`${BRANCH}/staff/invitations`));
      expect(request.request.body).not.toHaveProperty('locationId');
      expect(request.request.body).not.toHaveProperty('brandId');
      request.flush({ invitationId: 'i1', principalSubject: 's1', grantId: 'g1', inviteLink: 'x' });

      expect((await promise).invitationId).toBe('i1');
    });

    it('does not ask for the company-wide lists a branch grant never covers', async () => {
      expect(await api.telegramLinks(TENANT)).toEqual([]);
      expect(await api.staffInvitations(TENANT)).toEqual([]);
      // http.verify() in afterEach proves no request was made.
    });
  });

  describe('with a grant at the company', () => {
    beforeEach(() => configure([OWNER, LOCATION_MANAGER]));

    it('still uses the company-wide route, whatever else she holds', async () => {
      const promise = api.listGrants(TENANT);
      http
        .expectOne((r) => r.url === url(`/api/v1/control-plane/tenants/${TENANT}/grants`))
        .flush([COOK]);

      expect(await promise).toEqual([COOK]);
    });
  });
});

describe('StaffMembersApi for a branch manager', () => {
  let members: StaffMembersApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: CurrentTenant, useValue: { scopes: signal([LOCATION_MANAGER]) } },
      ],
    });
    members = TestBed.inject(StaffMembersApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it("reads the branch's people, not the company's", async () => {
    const promise = members.list(TENANT);
    http
      .expectOne(url(`${BRANCH}/staff/members`))
      .flush({ items: [{ memberId: 'm1', displayName: 'Cook' }], nextCursor: null });

    expect((await promise).map((m) => m.memberId)).toEqual(['m1']);
  });
});
