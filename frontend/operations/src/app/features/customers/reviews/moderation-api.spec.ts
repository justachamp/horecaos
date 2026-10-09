import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { firstPage } from '../../../core/api/page';
import { MODERATION_REASONS, ModerationApi } from './moderation-api';

const SCOPE = { tenantId: 't1', brandId: 'b1' };
const REVIEWS = '/api/v1/operations/tenants/t1/brands/b1/reviews';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

describe('ModerationApi', () => {
  let api: ModerationApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ModerationApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the queue for one state, with the counts of all of them', async () => {
    const promise = api.queue(SCOPE, 'HIDDEN', { cursor: 'c-2', limit: 25 });

    const request = http.expectOne((r) => r.url === url(`${REVIEWS}/moderation-queue`));
    expect(request.request.params.get('state')).toBe('HIDDEN');
    expect(request.request.params.get('cursor')).toBe('c-2');
    expect(request.request.params.get('limit')).toBe('25');
    request.flush({ items: [], nextCursor: null, counts: { PENDING: 3, HIDDEN: 1 } });

    expect((await promise).counts).toEqual({ PENDING: 3, HIDDEN: 1 });
  });

  it('sends no cursor on the first page', async () => {
    const promise = api.queue(SCOPE, 'PENDING', firstPage(10));

    const request = http.expectOne((r) => r.url === url(`${REVIEWS}/moderation-queue`));
    expect(request.request.params.has('cursor')).toBe(false);
    request.flush({ items: [], nextCursor: null, counts: {} });

    await promise;
  });

  it('publishes with the version the queue returned and no reason', async () => {
    const promise = api.publish(SCOPE, 'r1', 3);

    const request = http.expectOne(url(`${REVIEWS}/r1/moderation-decisions`));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"3"');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ to: 'PUBLISHED' });
    request.flush({ reviewId: 'r1', state: 'PUBLISHED', reasonCode: null, version: 4 });

    expect((await promise).state).toBe('PUBLISHED');
  });

  it('hides with one coded reason and nothing a person typed', async () => {
    const promise = api.hide(SCOPE, 'r1', 'NAMES_AN_INDIVIDUAL', 4);

    const request = http.expectOne(url(`${REVIEWS}/r1/moderation-decisions`));
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    expect(request.request.body).toEqual({ to: 'HIDDEN', reasonCode: 'NAMES_AN_INDIVIDUAL' });
    request.flush({
      reviewId: 'r1',
      state: 'HIDDEN',
      reasonCode: 'NAMES_AN_INDIVIDUAL',
      version: 5,
    });

    await promise;
  });

  it('offers exactly the five reasons the platform accepts, and none a system gives', () => {
    expect([...MODERATION_REASONS].sort()).toEqual([
      'ABUSIVE_LANGUAGE',
      'LEGAL_REQUEST',
      'NAMES_AN_INDIVIDUAL',
      'PERSONAL_DATA_OF_ANOTHER',
      'SPAM_OR_UNRELATED',
    ]);
    expect(MODERATION_REASONS).not.toContain('CUSTOMER_WITHDREW' as never);
    expect(MODERATION_REASONS).not.toContain('CUSTOMER_ERASED' as never);
  });
});
