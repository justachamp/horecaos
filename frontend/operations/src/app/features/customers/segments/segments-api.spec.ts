import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { BrandScope } from '../../../core/api/catalog-paths';
import { AudiencePredicate, SegmentsApi } from './segments-api';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const PREDICATE: AudiencePredicate = {
  type: 'RECENCY_DAYS',
  operator: 'AT_LEAST',
  numericLow: 30,
  numericHigh: null,
  dateLow: null,
  dateHigh: null,
  textValues: null,
  audienceId: null,
};

describe('SegmentsApi', () => {
  let api: SegmentsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), SegmentsApi],
    });
    api = TestBed.inject(SegmentsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // The regression this wave was warned about: redefine() must PUT to the
  // audience's own /predicates sub-resource, not to the audience itself and
  // not with POST — a stale client would silently create a new audience
  // instead of updating the one the operator is editing.
  it('redefines by PUTting the predicate list to the audience’s /predicates path', async () => {
    const promise = api.redefine(SCOPE, 'audience-1', [PREDICATE]);
    const request = http.expectOne(
      url('/api/v1/tenants/t1/brands/b1/marketing/audiences/audience-1/predicates'),
    );

    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ predicates: [PREDICATE] });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);

    request.flush({ definitionVersion: 3 });
    expect(await promise).toBe(3);
  });

  it('defines a new audience with POST to the audiences collection, distinct from redefine’s path', async () => {
    const promise = api.define(SCOPE, {
      name: 'Big spenders',
      description: null,
      predicates: [PREDICATE],
    });
    const request = http.expectOne(url('/api/v1/tenants/t1/brands/b1/marketing/audiences'));

    expect(request.request.method).toBe('POST');
    request.flush({ audienceId: 'audience-new' });
    expect(await promise).toBe('audience-new');
  });

  it('lists audiences, defaulting a null envelope to an empty array rather than throwing', async () => {
    const promise = api.list(SCOPE);
    http.expectOne(url('/api/v1/tenants/t1/brands/b1/marketing/audiences')).flush(null);
    expect(await promise).toEqual([]);
  });

  it('builds a snapshot at the audience’s own /snapshots path with the channel and consent purpose', async () => {
    const promise = api.buildSnapshot(SCOPE, 'audience-1', 'SMS', 'test purpose');
    const request = http.expectOne(
      url('/api/v1/tenants/t1/brands/b1/marketing/audiences/audience-1/snapshots'),
    );
    expect(request.request.body).toEqual({ channel: 'SMS', consentPurpose: 'test purpose' });
    request.flush({ snapshotId: 's1', candidates: 10, members: 8, excluded: 2 });
    expect(await promise).toEqual({ snapshotId: 's1', candidates: 10, members: 8, excluded: 2 });
  });
});
