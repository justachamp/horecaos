import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { firstPage } from '../../../core/api/page';
import { RecoveryApi } from './recovery-api';

const SCOPE = { tenantId: 't1', brandId: 'b1' };
const CASES = '/api/v1/operations/tenants/t1/brands/b1/recovery-cases';
const BRANCH_CASES = '/api/v1/operations/tenants/t1/brands/b1/locations/l1/recovery-cases';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

describe('RecoveryApi', () => {
  let api: RecoveryApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(RecoveryApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the brand worklist on the brand route, narrowed by state, branch and clock', async () => {
    const promise = api.list(SCOPE, firstPage(25), {
      state: 'IN_PROGRESS',
      locationId: 'l1',
      slaState: 'BREACHED',
    });

    const request = http.expectOne((r) => r.url === url(CASES));
    expect(request.request.params.get('state')).toBe('IN_PROGRESS');
    expect(request.request.params.get('locationId')).toBe('l1');
    expect(request.request.params.get('slaState')).toBe('BREACHED');
    expect(request.request.params.get('limit')).toBe('25');
    expect(request.request.params.has('cursor')).toBe(false);
    request.flush({ items: [], nextCursor: null });

    expect((await promise).items).toEqual([]);
  });

  it('omits a filter nobody set instead of sending the word undefined', async () => {
    const promise = api.list(SCOPE, firstPage(), {});

    const request = http.expectOne((r) => r.url === url(CASES));
    expect(request.request.params.keys().sort()).toEqual(['limit']);
    request.flush({ items: [], nextCursor: null });

    await promise;
  });

  it('reads the counts, optionally for one branch', async () => {
    const promise = api.counts(SCOPE, 'l1');

    const request = http.expectOne((r) => r.url === url(`${CASES}/summary`));
    expect(request.request.params.get('locationId')).toBe('l1');
    request.flush({ byState: { NEW: 2 }, bySla: { BREACHED: 1 } });

    expect(await promise).toEqual({ byState: { NEW: 2 }, bySla: { BREACHED: 1 } });
  });

  it('reads one case at its own branch and keeps the version the ETag carried', async () => {
    const promise = api.detail(SCOPE, 'l1', 'c1');

    const request = http.expectOne(url(`${BRANCH_CASES}/c1`));
    expect(request.request.method).toBe('GET');
    request.flush(
      { summary: { id: 'c1', version: 2 }, tags: [], timeline: [], compensations: [] },
      { headers: { ETag: 'W/"5"' } },
    );

    const detail = await promise;
    expect(detail.version).toBe(5);
    expect(detail.summary.id).toBe('c1');
  });

  it('moves a case with the version it was read at in If-Match and an Idempotency-Key', async () => {
    const promise = api.transition(SCOPE, 'l1', 'c1', { to: 'IN_PROGRESS' }, 4);

    const request = http.expectOne(url(`${BRANCH_CASES}/c1/transitions`));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ to: 'IN_PROGRESS' });
    request.flush({
      id: 'c1',
      state: 'IN_PROGRESS',
      outcomeCode: null,
      ownerStaffMemberId: 'm1',
      version: 5,
    });

    expect((await promise).version).toBe(5);
  });

  it('closes with an outcome, and the outcome is the only thing besides the target in the body', async () => {
    const promise = api.transition(
      SCOPE,
      'l1',
      'c1',
      { to: 'RESOLVED', outcomeCode: 'REMEDY_GRANTED' },
      5,
    );

    const request = http.expectOne(url(`${BRANCH_CASES}/c1/transitions`));
    expect(request.request.body).toEqual({ to: 'RESOLVED', outcomeCode: 'REMEDY_GRANTED' });
    request.flush({
      id: 'c1',
      state: 'RESOLVED',
      outcomeCode: 'REMEDY_GRANTED',
      ownerStaffMemberId: 'm1',
      version: 6,
    });

    await promise;
  });

  it('adds a note in the body, never in the path, and reads back only its id', async () => {
    const promise = api.addNote(SCOPE, 'l1', 'c1', 'Phoned her; she wants a call back tomorrow');

    const request = http.expectOne(url(`${BRANCH_CASES}/c1/notes`));
    expect(request.request.method).toBe('POST');
    expect(request.request.url).not.toContain('Phoned');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ note: 'Phoned her; she wants a call back tomorrow' });
    request.flush({ caseId: 'c1', entryId: 'e1' });

    expect(await promise).toBe('e1');
  });

  it('links a compensation by kind and identifier, and never pays one', async () => {
    const promise = api.attach(SCOPE, 'l1', 'c1', 'ORDER_REMEDY', 'remedy-1');

    const request = http.expectOne(url(`${BRANCH_CASES}/c1/compensations`));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ kind: 'ORDER_REMEDY', refId: 'remedy-1' });
    request.flush({ caseId: 'c1', kind: 'ORDER_REMEDY', refId: 'remedy-1', orderId: 'o1' });

    expect((await promise).refId).toBe('remedy-1');
  });

  it('opens a case by hand from a review, at the review’s branch', async () => {
    const promise = api.open(SCOPE, 'l1', 'review-9');

    const request = http.expectOne(url(BRANCH_CASES));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ reviewId: 'review-9' });
    request.flush({
      id: 'c9',
      state: 'NEW',
      outcomeCode: null,
      ownerStaffMemberId: null,
      version: 1,
    });

    expect((await promise).state).toBe('NEW');
  });
});
