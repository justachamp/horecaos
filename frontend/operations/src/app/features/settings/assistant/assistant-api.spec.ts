import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { AssistantApi, formatUsdCents, spendCents } from './assistant-api';

describe('AssistantApi', () => {
  it('reads the month at the tenant-scoped usage path', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), AssistantApi],
    });
    const api = TestBed.inject(AssistantApi);
    const http = TestBed.inject(HttpTestingController);

    const pending = api.usage('t1');
    const request = http.expectOne(
      `${environment.apiBaseUrl}/api/v1/operations/tenants/t1/assistant/usage`,
    );
    expect(request.request.method).toBe('GET');
    request.flush({ month: '2026-10', turns: 3 });

    expect((await pending).turns).toBe(3);
  });
});

describe('formatUsdCents', () => {
  it('writes whole cents as dollars with two decimals, with integer arithmetic', () => {
    expect(formatUsdCents(2_500)).toBe('$25.00');
    expect(formatUsdCents(2_499)).toBe('$24.99');
    expect(formatUsdCents(5)).toBe('$0.05');
    expect(formatUsdCents(0)).toBe('$0.00');
    expect(formatUsdCents(123_456)).toBe('$1234.56');
  });
});

describe('spendCents', () => {
  it('turns millionths of a dollar into cents, rounding a fraction of a cent up', () => {
    expect(spendCents(0)).toBe(0);
    expect(spendCents(10_000)).toBe(1);
    expect(spendCents(10_001)).toBe(2);
    expect(spendCents(25_000_000)).toBe(2_500);
  });
});
