import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { InboxApi } from './inbox-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = `${environment.apiBaseUrl}/api/v1/operations/tenants/t1/brands/b1/conversations`;

describe('InboxApi', () => {
  let api: InboxApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), InboxApi],
    });
    api = TestBed.inject(InboxApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('lists at the brand-scoped path, never the location one — a conversation has no location column', () => {
    api.list(SCOPE, 100).subscribe();
    const request = http.expectOne(`${BASE}?limit=100`);
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('reads one conversation’s detail', () => {
    api.detail(SCOPE, 'conv-1').subscribe();
    const request = http.expectOne(`${BASE}/conv-1`);
    expect(request.request.method).toBe('GET');
    request.flush({ conversation: {}, messages: [] });
  });

  it('sends a reply with the body and an Idempotency-Key, but no If-Match', () => {
    api.reply(SCOPE, 'conv-1', 'On it!').subscribe();
    const request = http.expectOne(`${BASE}/conv-1/replies`);

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ body: 'On it!' });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.headers.has('If-Match')).toBe(false);
    request.flush({});
  });

  it('takes over with If-Match against the expected version and an optional reason as a query param', () => {
    api.takeover(SCOPE, 'conv-1', 5, 'stepping in').subscribe();
    const request = http.expectOne(`${BASE}/conv-1/takeover?reason=stepping%20in`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"5"');
    request.flush({});
  });

  it('takes over with no reason param when none is given', () => {
    api.takeover(SCOPE, 'conv-1', 5).subscribe();
    http.expectOne(`${BASE}/conv-1/takeover`);
  });

  it('returns to the flow with If-Match and no body', () => {
    api.returnToFlow(SCOPE, 'conv-1', 5).subscribe();
    const request = http.expectOne(`${BASE}/conv-1/return-to-flow`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"5"');
    request.flush({});
  });

  it('closes with If-Match and an optional reason', () => {
    api.close(SCOPE, 'conv-1', 5, 'resolved').subscribe();
    const request = http.expectOne(`${BASE}/conv-1/close?reason=resolved`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"5"');
    request.flush({});
  });
});
