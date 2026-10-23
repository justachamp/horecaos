import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { LocationScope } from '../../core/api/operations-paths';
import { MaterialisationRun, StopMaterialisationApi } from './stop-materialisation-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/inventory/stop-materialisation-runs`;

const RUN: MaterialisationRun = {
  id: 'run-1',
  status: 'COMPLETED',
  startedAt: '2026-10-14T09:00:00Z',
  completedAt: '2026-10-14T09:00:05Z',
  stopsSeen: 1,
  positionsWritten: 1,
  positionsAlreadyUnavailable: 0,
  notCarried: 0,
  failedStops: 0,
  acknowledged: false,
  acknowledgedAt: null,
  version: 3,
};

/**
 * The panel spec fakes this class, so nothing else builds the real requests: the brand-scoped path, the
 * version an acknowledgement quotes in `If-Match`, and the cursor the report is paged under.
 */
describe('StopMaterialisationApi', () => {
  let http: HttpTestingController;
  let api: StopMaterialisationApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(StopMaterialisationApi);
  });

  afterEach(() => http.verify());

  it('reads the newest run: the first of a page of one, or none', async () => {
    const found = api.latest(SCOPE);
    const request = http.expectOne((candidate) => candidate.url === BASE);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('limit')).toBe('1');
    request.flush({ items: [RUN], nextCursor: null });
    expect(await found).toEqual(RUN);

    const none = api.latest(SCOPE);
    http.expectOne((candidate) => candidate.url === BASE).flush({ items: [], nextCursor: null });
    expect(await none).toBeNull();
  });

  it('starts a run with an idempotency key and no version', async () => {
    const started = api.start(SCOPE);
    const request = http.expectOne(BASE);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.headers.has('If-Match')).toBe(false);
    request.flush(RUN);
    expect(await started).toEqual(RUN);
  });

  it('pages the report under the cursor the platform gave', async () => {
    const first = api.report(SCOPE, 'run-1', null);
    const one = http.expectOne((candidate) => candidate.url === `${BASE}/run-1/report`);
    expect(one.request.params.has('cursor')).toBe(false);
    expect(one.request.params.get('limit')).toBe('50');
    one.flush({ items: [], nextCursor: 'c1' });
    expect((await first).nextCursor).toBe('c1');

    const second = api.report(SCOPE, 'run-1', 'c1');
    const two = http.expectOne((candidate) => candidate.url === `${BASE}/run-1/report`);
    expect(two.request.params.get('cursor')).toBe('c1');
    two.flush({ items: [], nextCursor: null });
    await second;
  });

  it('acknowledges under the version the screen read', async () => {
    const accepted = api.acknowledge(SCOPE, RUN);
    const request = http.expectOne(`${BASE}/run-1/acknowledgement`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"3"');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush({ ...RUN, acknowledged: true });
    expect((await accepted).acknowledged).toBe(true);
  });
});
