import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { LocationScope } from '../../../core/api/operations-paths';
import { FiscalizationApi } from './fiscalization-api';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

/**
 * `fiscalization-page.spec.ts` and `fiscal-backfill-editor.spec.ts` mock
 * `FiscalizationApi`, so nothing else builds the real request. The backfill's
 * whole safety rests on the body: `MERGE` mode, and a row that leaves a code
 * out sends no key for it, so the platform keeps what the dish holds.
 */
describe('FiscalizationApi.backfillCodes', () => {
  let http: HttpTestingController;
  let api: FiscalizationApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(FiscalizationApi);
  });

  afterEach(() => http.verify());

  it('puts a MERGE batch to the bulk classification endpoint with an idempotency key', async () => {
    const pending = api.backfillCodes(SCOPE, [
      {
        nodeType: 'VARIANT',
        nodeId: 'both',
        mxikCode: '10706001001000000',
        packageCode: '1500316',
      },
      { nodeType: 'VARIANT', nodeId: 'package-only', packageCode: '1500175' },
      { nodeType: 'VARIANT', nodeId: 'mxik-only', mxikCode: '10101001001000000' },
    ]);

    const request = http.expectOne(
      `${environment.apiBaseUrl}/api/v1/control-plane/tenants/tenant-1/brands/brand-1/catalog/fiscal-classifications/bulk`,
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({
      mode: 'MERGE',
      items: [
        {
          nodeType: 'VARIANT',
          nodeId: 'both',
          fiscal: { mxikCode: '10706001001000000', packageCode: '1500316' },
        },
        { nodeType: 'VARIANT', nodeId: 'package-only', fiscal: { packageCode: '1500175' } },
        { nodeType: 'VARIANT', nodeId: 'mxik-only', fiscal: { mxikCode: '10101001001000000' } },
      ],
    });
    expect(JSON.stringify(request.request.body)).not.toContain('markingRequired');

    request.flush({
      outcomes: [
        { nodeType: 'VARIANT', nodeId: 'both', status: 'CLASSIFIED' },
        { nodeType: 'VARIANT', nodeId: 'package-only', status: 'UNCHANGED' },
        { nodeType: 'VARIANT', nodeId: 'mxik-only', status: 'NOT_FOUND' },
      ],
    });

    expect((await pending).map((outcome) => outcome.status)).toEqual([
      'CLASSIFIED',
      'UNCHANGED',
      'NOT_FOUND',
    ]);
  });

  it('names a modifier option as one, in the same batch as the dishes', async () => {
    const pending = api.backfillCodes(SCOPE, [
      { nodeType: 'VARIANT', nodeId: 'dish', packageCode: '1500175' },
      { nodeType: 'MODIFIER_OPTION', nodeId: 'cheese', mxikCode: '10101001001000000' },
    ]);

    const request = http.expectOne(
      `${environment.apiBaseUrl}/api/v1/control-plane/tenants/tenant-1/brands/brand-1/catalog/fiscal-classifications/bulk`,
    );
    expect(request.request.body).toEqual({
      mode: 'MERGE',
      items: [
        { nodeType: 'VARIANT', nodeId: 'dish', fiscal: { packageCode: '1500175' } },
        {
          nodeType: 'MODIFIER_OPTION',
          nodeId: 'cheese',
          fiscal: { mxikCode: '10101001001000000' },
        },
      ],
    });
    request.flush({
      outcomes: [
        { nodeType: 'VARIANT', nodeId: 'dish', status: 'CLASSIFIED' },
        { nodeType: 'MODIFIER_OPTION', nodeId: 'cheese', status: 'CLASSIFIED' },
      ],
    });
    expect((await pending).map((outcome) => outcome.nodeType)).toEqual([
      'VARIANT',
      'MODIFIER_OPTION',
    ]);
  });
});
