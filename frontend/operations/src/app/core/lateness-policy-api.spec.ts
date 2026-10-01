import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from './api/api-client';
import { LatenessPolicyApi } from './lateness-policy-api';
import { PLATFORM_DEFAULT_LATENESS_POLICY } from './lateness-policy';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const THRESHOLDS = {
  atRiskBeforeSeconds: 600,
  lateAfterSeconds: 0,
  noPromiseFallbackSeconds: 2700,
};

function wire(extra: Record<string, unknown> = {}): { value: unknown; version: null } {
  return {
    value: {
      delivery: THRESHOLDS,
      pickup: THRESHOLDS,
      dineIn: THRESHOLDS,
      isPlatformDefault: true,
      policyId: null,
      policyVersion: 0,
      ...extra,
    },
    version: null,
  };
}

function apiReturning(response: unknown): LatenessPolicyApi {
  TestBed.configureTestingModule({
    providers: [{ provide: ApiClient, useValue: { get: vi.fn().mockReturnValue(of(response)) } }],
  });
  return TestBed.inject(LatenessPolicyApi);
}

/**
 * Row `X.39`: the tenant's late colour arrives on the same document both
 * boards already read. It ends up in a style binding, so the client checks the
 * shape again rather than trusting an endpoint that already checked it.
 */
describe('LatenessPolicyApi: the tenant late colour', () => {
  it('carries the at-risk minutes the server overlaid, per mode, unchanged', async () => {
    const policy = await apiReturning(wire()).resolve(SCOPE);

    expect(policy.delivery.atRiskBeforeSeconds).toBe(600);
    expect(policy.pickup.atRiskBeforeSeconds).toBe(600);
    expect(policy.dineIn.atRiskBeforeSeconds).toBe(600);
  });

  it('keeps each mode’s own numbers apart, as a tenant’s lateness document serves them (rows X.39 / 10.3b)', async () => {
    const policy = await apiReturning(
      wire({
        delivery: {
          atRiskBeforeSeconds: 900,
          lateAfterSeconds: 60,
          noPromiseFallbackSeconds: 3600,
        },
        pickup: { atRiskBeforeSeconds: 120, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1800 },
        dineIn: { atRiskBeforeSeconds: 0, lateAfterSeconds: 30, noPromiseFallbackSeconds: 1200 },
        isPlatformDefault: false,
        policyVersion: 2,
      }),
    ).resolve(SCOPE);

    expect(policy.delivery).toEqual({
      atRiskBeforeSeconds: 900,
      lateAfterSeconds: 60,
      noPromiseFallbackSeconds: 3600,
    });
    expect(policy.pickup).toEqual({
      atRiskBeforeSeconds: 120,
      lateAfterSeconds: 0,
      noPromiseFallbackSeconds: 1800,
    });
    expect(policy.dineIn).toEqual({
      atRiskBeforeSeconds: 0,
      lateAfterSeconds: 30,
      noPromiseFallbackSeconds: 1200,
    });
  });

  it('reads a #rrggbb colour, lower-cased', async () => {
    const policy = await apiReturning(wire({ lateColour: '#8A3FFC' })).resolve(SCOPE);

    expect(policy.lateColour).toBe('#8a3ffc');
  });

  it('reads no colour when the server sends null or nothing', async () => {
    expect((await apiReturning(wire({ lateColour: null })).resolve(SCOPE)).lateColour).toBeNull();
    TestBed.resetTestingModule();
    expect((await apiReturning(wire()).resolve(SCOPE)).lateColour).toBeNull();
  });

  it.each([
    ['a named colour', 'red'],
    ['a short hex', '#fff'],
    ['a non-hex digit', '#12345g'],
    ['a value with trailing css', '#8a3ffc; background: url(x)'],
    ['a url()', 'url(javascript:alert(1))'],
    ['an empty string', ''],
    ['a number', 12],
  ])('drops %s rather than binding it to a style', async (_label, lateColour) => {
    const policy = await apiReturning(wire({ lateColour })).resolve(SCOPE);

    expect(policy.lateColour).toBeNull();
    // Only the colour is discarded; the thresholds the board needs still arrive.
    expect(policy.delivery.atRiskBeforeSeconds).toBe(600);
  });

  it('falls back to the platform default, with no colour, when the read fails', async () => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ApiClient,
          useValue: { get: vi.fn().mockReturnValue(throwError(() => new Error('offline'))) },
        },
      ],
    });

    const policy = await TestBed.inject(LatenessPolicyApi).resolve(SCOPE);

    expect(policy).toBe(PLATFORM_DEFAULT_LATENESS_POLICY);
    expect(policy.lateColour ?? null).toBeNull();
  });

  it('says there was no answer, instead of answering with the fallback, when the read fails (tryResolve)', async () => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ApiClient,
          useValue: { get: vi.fn().mockReturnValue(throwError(() => new Error('offline'))) },
        },
      ],
    });

    expect(await TestBed.inject(LatenessPolicyApi).tryResolve(SCOPE)).toBeNull();
  });

  it('says there was no answer for a malformed payload too, and still answers a good one (tryResolve)', async () => {
    expect(
      await apiReturning({ value: { delivery: 'soon' }, version: null }).tryResolve(SCOPE),
    ).toBeNull();
    TestBed.resetTestingModule();

    const policy = await apiReturning(wire()).tryResolve(SCOPE);

    expect(policy?.delivery.atRiskBeforeSeconds).toBe(600);
  });
});
