import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { OrderRevealApi } from './order-reveal-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/orders/o1`;

describe('OrderRevealApi', () => {
  let api: OrderRevealApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrderRevealApi],
    });
    api = TestBed.inject(OrderRevealApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('reveals the phone, carrying the purpose as a query param — never in the path', () => {
    let value: { phone: string | null } | undefined;
    api.revealPhone(SCOPE, 'o1', 'call the customer back').subscribe((v) => (value = v));

    const request = http.expectOne((r) => r.url === `${BASE}/customer/phone`);
    expect(request.request.params.get('purpose')).toBe('call the customer back');
    request.flush({ phone: '+998901234567' });

    expect(value).toEqual({ phone: '+998901234567' });
  });

  it('makes an independent call each time it is invoked — no caching of the revealed value', () => {
    api.revealPhone(SCOPE, 'o1', 'first').subscribe();
    http.expectOne((r) => r.url === `${BASE}/customer/phone`).flush({ phone: '+998901234567' });

    api.revealPhone(SCOPE, 'o1', 'second').subscribe();
    const second = http.expectOne((r) => r.url === `${BASE}/customer/phone`);
    expect(second.request.params.get('purpose')).toBe('second');
    second.flush({ phone: '+998901234567' });
  });

  it('reveals the address with its structured fields', () => {
    let value: unknown;
    api.revealAddress(SCOPE, 'o1', 'delivery dispatch').subscribe((v) => (value = v));

    const request = http.expectOne((r) => r.url === `${BASE}/customer/address`);
    expect(request.request.params.get('purpose')).toBe('delivery dispatch');
    request.flush({
      line1: 'Amir Temur 1',
      entrance: '2',
      floor: '5',
      apartment: '42',
      latitude: 41.31,
      longitude: 69.28,
    });

    expect(value).toMatchObject({ line1: 'Amir Temur 1', entrance: '2' });
  });

  it('reveals one line’s note at the line-scoped path', () => {
    let value: { lineId: string; note: string | null } | undefined;
    api.revealLineNote(SCOPE, 'o1', 'line-9', 'kitchen ticket').subscribe((v) => (value = v));

    const request = http.expectOne((r) => r.url === `${BASE}/lines/line-9/note`);
    expect(request.request.params.get('purpose')).toBe('kitchen ticket');
    request.flush({ lineId: 'line-9', note: 'No onions please' });

    expect(value).toEqual({ lineId: 'line-9', note: 'No onions please' });
  });
});
