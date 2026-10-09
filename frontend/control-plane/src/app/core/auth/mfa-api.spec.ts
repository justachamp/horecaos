import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG, AppConfig } from '../config/app-config';
import { MFA_BASE, MfaApi } from './mfa-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};
const url = (path: string): string => `${CONFIG.apiBaseUrl}${path}`;

describe('MfaApi (control plane, ADR 0148)', () => {
  let api: MfaApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: CONFIG },
      ],
    });
    api = TestBed.inject(MfaApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('begins an enrolment with the password and the ticket, under this console’s own prefix', async () => {
    const promise = api.begin('pw', 'a-ticket');
    const request = http.expectOne(url(`${MFA_BASE}/enrolments`));
    expect(MFA_BASE).toBe('/api/v1/control-plane/auth/mfa');
    expect(request.request.body).toEqual({ password: 'pw', enrolmentTicket: 'a-ticket' });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush({ sealedSecret: 's', otpauthUri: 'u', secret: 'k', expiresAt: 'x' });

    expect(await promise).toMatchObject({ sealedSecret: 's' });
  });

  it('confirms with the sealed secret, the first code, the password, the name and the ticket, and answers the session', async () => {
    const promise = api.confirm({
      sealedSecret: 'sealed',
      code: '123456',
      password: 'pw',
      label: ' my phone ',
      enrolmentTicket: 'a-ticket',
    });
    const request = http.expectOne(url(`${MFA_BASE}/enrolments/confirm`));
    expect(request.request.body).toEqual({
      sealedSecret: 'sealed',
      code: '123456',
      password: 'pw',
      label: 'my phone',
      enrolmentTicket: 'a-ticket',
    });
    request.flush({
      accessToken: 'a',
      refreshToken: 'r',
      accessTokenExpiresAt: 'x',
      tokenType: 'Bearer',
    });

    expect(await promise).toMatchObject({ accessToken: 'a' });
  });
});
