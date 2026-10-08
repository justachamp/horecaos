import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { MFA_BASE, MfaApi } from './mfa-api';

const url = (path: string): string => `${environment.apiBaseUrl}${path}`;

describe('MfaApi (ADR 0148)', () => {
  let api: MfaApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(MfaApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('begins an enrolment with the password alone from a session, and adds the ticket when it has one', async () => {
    const fromSession = api.begin('pw', null);
    const first = http.expectOne(url(`${MFA_BASE}/enrolments`));
    expect(first.request.body).toEqual({ password: 'pw' });
    expect(first.request.headers.get('Idempotency-Key')).toBeTruthy();
    first.flush({ sealedSecret: 's', otpauthUri: 'u', secret: 'k', expiresAt: 'x' });
    await fromSession;

    const fromTicket = api.begin('pw', 'a-ticket');
    const second = http.expectOne(url(`${MFA_BASE}/enrolments`));
    expect(second.request.body).toEqual({ password: 'pw', enrolmentTicket: 'a-ticket' });
    second.flush({ sealedSecret: 's', otpauthUri: 'u', secret: 'k', expiresAt: 'x' });
    await fromTicket;
  });

  it('answers the new session when the platform answers 201 (a ticket), and null on 204 (a session)', async () => {
    const request = {
      sealedSecret: 'sealed',
      code: '123456',
      password: 'pw',
      label: ' my phone ',
      enrolmentTicket: null,
    };
    const fromSession = api.confirm(request);
    const first = http.expectOne(url(`${MFA_BASE}/enrolments/confirm`));
    expect(first.request.body).toEqual({
      sealedSecret: 'sealed',
      code: '123456',
      password: 'pw',
      label: 'my phone',
    });
    first.flush(null, { status: 204, statusText: 'No Content' });
    expect(await fromSession).toBeNull();

    const fromTicket = api.confirm({ ...request, label: null, enrolmentTicket: 'a-ticket' });
    const second = http.expectOne(url(`${MFA_BASE}/enrolments/confirm`));
    expect(second.request.body).toEqual({
      sealedSecret: 'sealed',
      code: '123456',
      password: 'pw',
      enrolmentTicket: 'a-ticket',
    });
    second.flush(
      { accessToken: 'a', refreshToken: 'r', accessTokenExpiresAt: 'x', tokenType: 'Bearer' },
      { status: 201, statusText: 'Created' },
    );
    expect(await fromTicket).toMatchObject({ accessToken: 'a' });
  });

  it('reads the caller’s own authenticators', async () => {
    const promise = api.own();
    http.expectOne(url(`${MFA_BASE}/authenticators`)).flush({
      enrolled: true,
      authenticators: [{ id: 'c1', label: 'phone', createdAt: '2026-10-07T10:00:00Z' }],
      requirement: 'REQUIRED',
      maximum: 2,
    });

    expect(await promise).toMatchObject({ enrolled: true, maximum: 2 });
  });

  it('removes an authenticator with the password and a code in the body, never the URL', async () => {
    const promise = api.remove('a b/c', 'pw', '123456');
    const request = http.expectOne(url(`${MFA_BASE}/authenticators/a%20b%2Fc`));
    expect(request.request.method).toBe('DELETE');
    expect(request.request.body).toEqual({ password: 'pw', code: '123456' });
    expect(request.request.url).not.toContain('pw');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await promise;
  });
});
