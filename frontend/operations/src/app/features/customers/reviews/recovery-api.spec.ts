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

  it('sends the rating range the review’s stars were filtered to, and only when set', async () => {
    const promise = api.list(SCOPE, firstPage(25), { minRating: 1, maxRating: 2 });

    const request = http.expectOne((r) => r.url === url(CASES));
    expect(request.request.params.get('minRating')).toBe('1');
    expect(request.request.params.get('maxRating')).toBe('2');
    request.flush({ items: [], nextCursor: null });
    await promise;

    const branch = api.listAtLocation(SCOPE, 'l1', firstPage(25), { maxRating: 3 });
    const second = http.expectOne((r) => r.url === url(BRANCH_CASES));
    expect(second.request.params.get('maxRating')).toBe('3');
    expect(second.request.params.has('minRating')).toBe(false);
    second.flush({ items: [], nextCursor: null });
    await branch;
  });

  it('omits a filter nobody set instead of sending the word undefined', async () => {
    const promise = api.list(SCOPE, firstPage(), {});

    const request = http.expectOne((r) => r.url === url(CASES));
    expect(request.request.params.keys().sort()).toEqual(['limit']);
    request.flush({ items: [], nextCursor: null });

    await promise;
  });

  it("lists one branch's worklist on the branch route, with the branch in the path and not in the query", async () => {
    const promise = api.listAtLocation(SCOPE, 'l1', firstPage(25), {
      state: 'NEW',
      slaState: 'AT_RISK',
    });

    const request = http.expectOne((r) => r.url === url(BRANCH_CASES));
    expect(request.request.params.get('state')).toBe('NEW');
    expect(request.request.params.get('slaState')).toBe('AT_RISK');
    expect(request.request.params.has('locationId')).toBe(false);
    request.flush({ items: [], nextCursor: null });

    expect((await promise).items).toEqual([]);
  });

  it("reads one branch's counts on the branch route", async () => {
    const promise = api.countsAtLocation(SCOPE, 'l1');

    http.expectOne(url(`${BRANCH_CASES}/summary`)).flush({ byState: { NEW: 1 }, bySla: {} });

    expect(await promise).toEqual({ byState: { NEW: 1 }, bySla: {} });
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

  it('reads what a case could be linked to at the case’s own branch, with the code never in it', async () => {
    const promise = api.candidates(SCOPE, 'l1', 'c1');

    const request = http.expectOne(url(`${BRANCH_CASES}/c1/compensation-candidates`));
    expect(request.request.method).toBe('GET');
    request.flush({
      remedies: [],
      grants: [
        {
          grantId: 'g1',
          benefitType: 'FREE_DELIVERY',
          value: 0,
          currency: null,
          status: 'ACTIVE',
          sourceType: 'RECOVERY_CASE',
          codeHint: 'K7Q2',
          createdAt: '2026-10-14T05:00:00Z',
          expiresAt: null,
          taken: false,
        },
      ],
      orderId: 'o1',
      customerAccountId: 'cu1',
    });

    const found = await promise;
    expect(found.grants[0].codeHint).toBe('K7Q2');
    expect(found.orderId).toBe('o1');
  });

  it('mints an apology code at brand scope with the case as its source and a fresh idempotency key', async () => {
    const promise = api.mintApologyCode(SCOPE, {
      customerAccountId: 'cu1',
      shape: 'PERCENTAGE_OFF_ORDER',
      value: 1_000,
      currency: 'UZS',
      minBasketMinor: 0,
      sourceId: 'c1',
      expiresAt: '2026-11-13T05:00:00.000Z',
    });

    const request = http.expectOne(url('/api/v1/operations/tenants/t1/brands/b1/benefit-grants'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({
      customerAccountId: 'cu1',
      shape: 'PERCENTAGE_OFF_ORDER',
      value: 1_000,
      maximumDiscountMinor: null,
      currency: 'UZS',
      minBasketMinor: 0,
      sourceType: 'RECOVERY_CASE',
      sourceId: 'c1',
      validFrom: null,
      expiresAt: '2026-11-13T05:00:00.000Z',
    });
    request.flush({
      grantId: 'g1',
      plaintextCode: 'SORRY-1',
      codeHint: 'RY-1',
      validFrom: '2026-10-14T05:00:00Z',
    });

    expect((await promise).plaintextCode).toBe('SORRY-1');
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
