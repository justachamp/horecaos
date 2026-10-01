import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { StaffMembersApi } from './staff-members-api';
import { staffMember, staffMemberDetail } from './staff-member.testing';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

const MEMBERS = '/api/v1/operations/tenants/t1/staff/members';

describe('StaffMembersApi', () => {
  let api: StaffMembersApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), StaffMembersApi],
    });
    api = TestBed.inject(StaffMembersApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the tenant’s people from the tenant route and unwraps the page', async () => {
    const promise = api.list('t1');
    const request = http.expectOne(url(MEMBERS));
    expect(request.request.method).toBe('GET');
    // No filter is ever sent: names are ciphertext, so the console filters in memory,
    // and a phone or a name must never be a query string.
    expect(request.request.params.keys()).toEqual([]);
    request.flush({ items: [staffMember()], nextCursor: null });

    expect(await promise).toEqual([staffMember()]);
  });

  it('lists the people of one branch from the branch route', async () => {
    const promise = api.listAtLocation({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    const request = http.expectOne(
      url('/api/v1/operations/tenants/t1/brands/b1/locations/l1/staff/members'),
    );
    request.flush({ items: [staffMember()], nextCursor: null });

    expect(await promise).toHaveLength(1);
  });

  it('reads one person in full from the member route', async () => {
    const promise = api.detail('t1', 'm1');
    const request = http.expectOne(url(`${MEMBERS}/m1`));
    request.flush(staffMemberDetail());

    expect((await promise).phone).toBe('+998901234542');
  });

  it('replaces a person with the record’s version in If-Match and a fresh Idempotency-Key', async () => {
    const promise = api.update('t1', 'm1', { firstName: 'Aziza', lastName: null }, 4);
    const request = http.expectOne(url(`${MEMBERS}/m1`));

    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ firstName: 'Aziza', lastName: null });
    request.flush(staffMemberDetail({ version: 5 }));

    expect((await promise).version).toBe(5);
  });

  it('ends employment on its own route with the version and the reason', async () => {
    const promise = api.endEmployment('t1', 'm1', { reason: 'Resigned', employedUntil: null }, 2);
    const request = http.expectOne(url(`${MEMBERS}/m1/end-employment`));

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"2"');
    expect(request.request.body).toEqual({ reason: 'Resigned', employedUntil: null });
    request.flush({ member: staffMemberDetail(), revokedGrants: 2, remainingGrants: 0 });

    expect((await promise).revokedGrants).toBe(2);
  });

  it('reads emergency contacts only when asked, from the contacts route', async () => {
    const promise = api.emergencyContacts('t1', 'm1');
    const request = http.expectOne(url(`${MEMBERS}/m1/emergency-contacts`));
    expect(request.request.method).toBe('GET');
    request.flush({ contacts: [], memberVersion: 3 });

    expect((await promise).memberVersion).toBe(3);
  });

  it('replaces the whole set of contacts with the member’s version', async () => {
    const promise = api.replaceEmergencyContacts(
      't1',
      'm1',
      [{ relationshipCode: 'SPOUSE', name: 'Dilnoza', phone: '+998901112233' }],
      3,
    );
    const request = http.expectOne(url(`${MEMBERS}/m1/emergency-contacts`));

    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"3"');
    expect(request.request.body).toEqual({
      contacts: [{ relationshipCode: 'SPOUSE', name: 'Dilnoza', phone: '+998901112233' }],
    });
    request.flush({ contacts: [], memberVersion: 4 });

    expect((await promise).memberVersion).toBe(4);
  });

  it('reads my own record from the me route, which has no member id in it', async () => {
    const promise = api.me('t1');
    const request = http.expectOne(url('/api/v1/operations/tenants/t1/staff/me'));
    request.flush(staffMemberDetail());

    expect((await promise)?.memberId).toBe('m1');
  });

  it('answers null, not an error, when the tenant keeps no record for the account (404)', async () => {
    const promise = api.me('t1');
    http
      .expectOne(url('/api/v1/operations/tenants/t1/staff/me'))
      .flush(
        { type: 'about:blank', title: 'Not found', status: 404, code: 'RESOURCE_NOT_FOUND' },
        { status: 404, statusText: 'Not Found' },
      );

    expect(await promise).toBeNull();
  });

  it('lets any other failure of the own record through', async () => {
    const promise = api.me('t1');
    http
      .expectOne(url('/api/v1/operations/tenants/t1/staff/me'))
      .flush(
        { type: 'about:blank', title: 'Boom', status: 500, code: 'INTERNAL_ERROR' },
        { status: 500, statusText: 'Server Error' },
      );

    await expect(promise).rejects.toMatchObject({ status: 500 });
  });

  it('updates my own record with If-Match and sends no member id', async () => {
    const promise = api.updateMe('t1', { firstName: 'Aziza', removePhoto: null }, 7);
    const request = http.expectOne(url('/api/v1/operations/tenants/t1/staff/me'));

    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"7"');
    expect(request.request.body).toEqual({ firstName: 'Aziza', removePhoto: null });
    request.flush(staffMemberDetail({ version: 8 }));

    expect((await promise).version).toBe(8);
  });

  it('sends the photo as the image itself with its own content type', async () => {
    const image = new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' });
    const promise = api.setMyPhoto('t1', image, 7);
    const request = http.expectOne(url('/api/v1/operations/tenants/t1/staff/me/photo'));

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"7"');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toBe(image);
    request.flush(staffMemberDetail({ hasPhoto: true }));

    expect((await promise).hasPhoto).toBe(true);
  });
});
