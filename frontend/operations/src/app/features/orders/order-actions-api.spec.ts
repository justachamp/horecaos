import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { OrderActionsApi } from './order-actions-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/orders/o1`;

describe('OrderActionsApi', () => {
  let api: OrderActionsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrderActionsApi],
    });
    api = TestBed.inject(OrderActionsApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('approves with the decisionId in the body and no If-Match — the decision is CAS by id, not by version', () => {
    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const request = http.expectOne(`${BASE}/approval-decisions`);

    expect(request.request.body).toEqual({ decisionId: 'decision-1', action: 'APPROVE' });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.headers.has('If-Match')).toBe(false);
    request.flush({
      orderId: 'o1',
      status: 'CONFIRMED',
      version: 2,
      applied: true,
      effectiveDecisionId: null,
      effectiveAction: null,
    });
  });

  it('rejects with the reason code', () => {
    api.reject(SCOPE, 'o1', 'decision-1', 'NO_STOCK').subscribe();
    const request = http.expectOne(`${BASE}/approval-decisions`);

    expect(request.request.body).toEqual({
      decisionId: 'decision-1',
      action: 'REJECT',
      reasonCode: 'NO_STOCK',
    });
    request.flush({});
  });

  it('reuses the same decisionId sent in across two calls when the caller passes the same value', () => {
    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const first = http.expectOne(`${BASE}/approval-decisions`);
    first.flush({});

    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const second = http.expectOne(`${BASE}/approval-decisions`);
    expect(second.request.body).toEqual({ decisionId: 'decision-1', action: 'APPROVE' });
    second.flush({});
  });

  it('advances with a target status, a synthesised reason, and If-Match carrying the expected version', () => {
    api.advance(SCOPE, 'o1', 'PREPARING', 4).subscribe();
    const request = http.expectOne(`${BASE}/state-actions`);

    expect(request.request.body).toEqual({
      targetStatus: 'PREPARING',
      reasonCode: 'OPERATIONS_ADVANCE_PREPARING',
    });
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    request.flush({});
  });

  it('cancels with the reason code, an optional note, and If-Match', () => {
    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND', 'Called back to cancel').subscribe();
    const request = http.expectOne(`${BASE}/cancellations`);

    expect(request.request.body).toEqual({
      reasonCode: 'CUSTOMER_CHANGED_MIND',
      note: 'Called back to cancel',
    });
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    request.flush({});
  });

  it('omits note entirely rather than sending an empty string', () => {
    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND').subscribe();
    const request = http.expectOne(`${BASE}/cancellations`);

    expect(request.request.body).toEqual({ reasonCode: 'CUSTOMER_CHANGED_MIND' });
    request.flush({});
  });

  it('overrides with the target status the caller resolved, a mandatory reasonId, and If-Match', () => {
    api.override(SCOPE, 'o1', 'PREPARING', 4, 'reason-override-1').subscribe();
    const request = http.expectOne(`${BASE}/state-overrides`);

    expect(request.request.body).toEqual({ targetStatus: 'PREPARING', reasonId: 'reason-override-1' });
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    request.flush({});
  });

  // ---------------------------------------------------------- Idempotency-Key stability
  //
  // HK: an operator's manual retry of the SAME intent (a lost response to a
  // click, or a second click before the row shows busy) must reuse the same
  // Idempotency-Key, not mint a new one — a fresh key on retry makes the
  // platform treat the retry as an independent action instead of replaying
  // the first attempt. Every method here used to mint its key inline via
  // `command(request)` on every call; it now holds one per order through
  // `IntentCommandRegistry` and only rotates on a body change or a
  // successful response.

  it('reuses the same Idempotency-Key across two approve() calls for the same order and decisionId', () => {
    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const first = http.expectOne(`${BASE}/approval-decisions`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    expect(firstKey).toBeTruthy();

    // No flush yet: this is a retry of the SAME unresolved intent (a lost
    // response, or the operator clicking again before the row is busy).
    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const second = http.expectOne(`${BASE}/approval-decisions`);
    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush({});
    second.flush({});
  });

  it('mints a fresh Idempotency-Key once DecisionIdRegistry rotates the decisionId after the first attempt settled', () => {
    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const first = http.expectOne(`${BASE}/approval-decisions`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({
      orderId: 'o1',
      status: 'CONFIRMED',
      version: 2,
      applied: true,
      effectiveDecisionId: null,
      effectiveAction: null,
    });

    // A later, unrelated decision on the same order gets its own decisionId
    // (DecisionIdRegistry) — a genuinely new intent, so a new key too.
    api.approve(SCOPE, 'o1', 'decision-2').subscribe();
    const second = http.expectOne(`${BASE}/approval-decisions`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({});
  });

  it('mints a fresh Idempotency-Key for advance() once the server confirms the first advance completed', () => {
    api.advance(SCOPE, 'o1', 'PREPARING', 4).subscribe();
    const first = http.expectOne(`${BASE}/state-actions`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({});

    api.advance(SCOPE, 'o1', 'READY', 5).subscribe();
    const second = http.expectOne(`${BASE}/state-actions`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({});
  });

  it('reuses the same Idempotency-Key across a retried cancel() with the same reason and note', () => {
    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND', 'Called back to cancel').subscribe();
    const first = http.expectOne(`${BASE}/cancellations`);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND', 'Called back to cancel').subscribe();
    const second = http.expectOne(`${BASE}/cancellations`);
    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush({});
    second.flush({});
  });

  it('mints a fresh Idempotency-Key for cancel() once the operator edits the note before retrying', () => {
    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND', 'first note').subscribe();
    const first = http.expectOne(`${BASE}/cancellations`);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND', 'edited note').subscribe();
    const second = http.expectOne(`${BASE}/cancellations`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    first.flush({});
    second.flush({});
  });

  // -------------------------------------------------- STALE_VERSION retry (H1)
  //
  // A 409 STALE_VERSION is stored by the platform's idempotency store as a
  // COMPLETED, replayable record keyed on the request body only — headers,
  // including If-Match, are never part of that key. So when the operator
  // corrects `expectedVersion` (after re-reading the row) and resubmits an
  // otherwise-unchanged reason/note, the tracked body the registry compares
  // must change too, or the same Idempotency-Key goes out again and the
  // platform replays the stale 409 forever instead of ever reaching the
  // controller with the corrected version.

  it('mints a fresh Idempotency-Key for cancelWithReason() once expectedVersion is corrected after a STALE_VERSION 409, even though the reason is unchanged', () => {
    api.cancelWithReason(SCOPE, 'o1', 4, 'reason-1', 'CUSTOMER_CHANGED_MIND').subscribe({
      error: () => {
        /* expected: the row was stale */
      },
    });
    const first = http.expectOne(`${BASE}/cancellations`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    expect(first.request.headers.get('If-Match')).toBe('W/"4"');
    first.flush(
      { status: 409, code: 'STALE_VERSION', title: 'Stale version' },
      { status: 409, statusText: 'Conflict' },
    );

    // Same reason, same note — only the corrected version differs.
    api.cancelWithReason(SCOPE, 'o1', 5, 'reason-1', 'CUSTOMER_CHANGED_MIND').subscribe();
    const second = http.expectOne(`${BASE}/cancellations`);
    expect(second.request.headers.get('If-Match')).toBe('W/"5"');
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({});
  });

  it('mints a fresh Idempotency-Key for cancel() once expectedVersion is corrected after a STALE_VERSION 409', () => {
    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND', 'Called back to cancel').subscribe({
      error: () => {
        /* expected: the row was stale */
      },
    });
    const first = http.expectOne(`${BASE}/cancellations`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush(
      { status: 409, code: 'STALE_VERSION', title: 'Stale version' },
      { status: 409, statusText: 'Conflict' },
    );

    api.cancel(SCOPE, 'o1', 5, 'CUSTOMER_CHANGED_MIND', 'Called back to cancel').subscribe();
    const second = http.expectOne(`${BASE}/cancellations`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({});
  });

  it('mints a fresh Idempotency-Key for advance() once expectedVersion is corrected after a STALE_VERSION 409', () => {
    api.advance(SCOPE, 'o1', 'PREPARING', 4).subscribe({
      error: () => {
        /* expected: the row was stale */
      },
    });
    const first = http.expectOne(`${BASE}/state-actions`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush(
      { status: 409, code: 'STALE_VERSION', title: 'Stale version' },
      { status: 409, statusText: 'Conflict' },
    );

    // Same target status — only the corrected version differs.
    api.advance(SCOPE, 'o1', 'PREPARING', 5).subscribe();
    const second = http.expectOne(`${BASE}/state-actions`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({});
  });

  it('mints a fresh Idempotency-Key for complete() once expectedVersion is corrected after a STALE_VERSION 409', () => {
    api.complete(SCOPE, 'o1', 4, 'reason-1').subscribe({
      error: () => {
        /* expected: the row was stale */
      },
    });
    const first = http.expectOne(`${BASE}/completion`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush(
      { status: 409, code: 'STALE_VERSION', title: 'Stale version' },
      { status: 409, statusText: 'Conflict' },
    );

    api.complete(SCOPE, 'o1', 5, 'reason-1').subscribe();
    const second = http.expectOne(`${BASE}/completion`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({});
  });

  it('mints a fresh Idempotency-Key for override() once expectedVersion is corrected after a STALE_VERSION 409', () => {
    api.override(SCOPE, 'o1', 'PREPARING', 4, 'reason-override-1').subscribe({
      error: () => {
        /* expected: the row was stale */
      },
    });
    const first = http.expectOne(`${BASE}/state-overrides`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush(
      { status: 409, code: 'STALE_VERSION', title: 'Stale version' },
      { status: 409, statusText: 'Conflict' },
    );

    // Same target and reason — only the corrected version differs.
    api.override(SCOPE, 'o1', 'PREPARING', 5, 'reason-override-1').subscribe();
    const second = http.expectOne(`${BASE}/state-overrides`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({});
  });
});
