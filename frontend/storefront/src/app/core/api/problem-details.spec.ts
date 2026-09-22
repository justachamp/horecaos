import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import {
  HorecaOSApiError,
  isNotFound,
  isSessionExpired,
  isUnauthenticated,
  messageKeyFor,
  reasonMessageKey,
  toHorecaOSApiError,
} from './problem-details';

describe('toHorecaOSApiError', () => {
  it('maps an RFC 9457 problem document to a HorecaOSApiError', () => {
    const response = new HttpErrorResponse({
      status: 409,
      error: {
        status: 409,
        code: 'STALE_VERSION',
        title: 'Conflict',
        detail: 'The cart moved under you.',
        correlationId: 'corr-1',
        errors: [{ field: 'quantity', code: 'TOO_LOW' }],
        currentVersion: 4,
        expectedVersion: 2,
      },
    });

    const error = toHorecaOSApiError(response);

    expect(error).toBeInstanceOf(HorecaOSApiError);
    expect(error.status).toBe(409);
    expect(error.code).toBe('STALE_VERSION');
    expect(error.detail).toBe('The cart moved under you.');
    expect(error.correlationId).toBe('corr-1');
    expect(error.fieldErrors).toEqual([{ field: 'quantity', code: 'TOO_LOW' }]);
    expect(error.problem?.currentVersion).toBe(4);
    expect(error.isStaleVersion).toBe(true);
  });

  it('falls back to title when detail is absent, and to statusText when both are', () => {
    const withTitle = toHorecaOSApiError(
      new HttpErrorResponse({ status: 400, error: { status: 400, code: 'VALIDATION_FAILED', title: 'Bad input' } }),
    );
    expect(withTitle.detail).toBe('Bad input');

    const withNeither = toHorecaOSApiError(
      new HttpErrorResponse({ status: 400, error: { status: 400 }, statusText: 'Bad Request' }),
    );
    expect(withNeither.detail).toBe('Bad Request');
  });

  it('defaults code to INTERNAL_ERROR when the problem document omits it', () => {
    const error = toHorecaOSApiError(
      new HttpErrorResponse({ status: 500, error: { status: 500, title: 'Oops' } }),
    );
    expect(error.code).toBe('INTERNAL_ERROR');
  });

  it('reads Retry-After when present and positive, and omits it otherwise', () => {
    const withHeader = toHorecaOSApiError(
      new HttpErrorResponse({
        status: 429,
        error: { status: 429, code: 'RATE_LIMIT_EXCEEDED' },
        headers: new HttpHeaders({ 'Retry-After': '30' }),
      }),
    );
    expect(withHeader.retryAfterSeconds).toBe(30);

    const withoutHeader = toHorecaOSApiError(
      new HttpErrorResponse({ status: 429, error: { status: 429, code: 'RATE_LIMIT_EXCEEDED' } }),
    );
    expect(withoutHeader.retryAfterSeconds).toBeUndefined();

    const zeroHeader = toHorecaOSApiError(
      new HttpErrorResponse({
        status: 429,
        error: { status: 429, code: 'RATE_LIMIT_EXCEEDED' },
        headers: new HttpHeaders({ 'Retry-After': '0' }),
      }),
    );
    expect(zeroHeader.retryAfterSeconds).toBeUndefined();
  });

  it('maps status 0 (network failure) to NETWORK_UNREACHABLE, never guessing a reason', () => {
    const error = toHorecaOSApiError(new HttpErrorResponse({ status: 0 }));

    expect(error.status).toBe(0);
    expect(error.code).toBe('NETWORK_UNREACHABLE');
    expect(error.detail).toBe('The request did not reach the platform.');
  });

  it('maps a bare 401 with no problem document to INTERNAL_ERROR at status 401', () => {
    const error = toHorecaOSApiError(
      new HttpErrorResponse({ status: 401, statusText: 'Unauthorized', error: 'plain text body' }),
    );

    expect(error.status).toBe(401);
    expect(error.code).toBe('INTERNAL_ERROR');
  });

  it('treats a body without "code" or "title", or without "status", as not a problem document', () => {
    const noCodeOrTitle = toHorecaOSApiError(
      new HttpErrorResponse({ status: 502, error: { status: 502 }, statusText: 'Bad Gateway' }),
    );
    // No `code`/`title` at all -> isProblemDetails is false -> falls to the
    // generic branch, which still reports the real HTTP status.
    expect(noCodeOrTitle.status).toBe(502);
    expect(noCodeOrTitle.code).toBe('INTERNAL_ERROR');

    const noStatusField = toHorecaOSApiError(
      new HttpErrorResponse({ status: 400, error: { code: 'VALIDATION_FAILED' }, statusText: 'Bad Request' }),
    );
    expect(noStatusField.code).toBe('INTERNAL_ERROR');
    expect(noStatusField.status).toBe(400);
  });
});

describe('isUnauthenticated / isSessionExpired / isNotFound', () => {
  it('isUnauthenticated is true for status 401, for UNAUTHENTICATED, and for SESSION_EXPIRED', () => {
    expect(isUnauthenticated(new HorecaOSApiError({ status: 401, code: 'INTERNAL_ERROR', detail: '' }))).toBe(
      true,
    );
    expect(
      isUnauthenticated(new HorecaOSApiError({ status: 200, code: 'UNAUTHENTICATED', detail: '' })),
    ).toBe(true);
    expect(
      isUnauthenticated(new HorecaOSApiError({ status: 200, code: 'SESSION_EXPIRED', detail: '' })),
    ).toBe(true);
  });

  it('isUnauthenticated is false for an unrelated error, and for a non-HorecaOSApiError', () => {
    expect(
      isUnauthenticated(new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: '' })),
    ).toBe(false);
    expect(isUnauthenticated(new Error('plain'))).toBe(false);
    expect(isUnauthenticated(null)).toBe(false);
  });

  it('isSessionExpired is true only for the SESSION_EXPIRED code, not for a bare 401 or UNAUTHENTICATED', () => {
    expect(
      isSessionExpired(new HorecaOSApiError({ status: 401, code: 'SESSION_EXPIRED', detail: '' })),
    ).toBe(true);
    expect(isSessionExpired(new HorecaOSApiError({ status: 401, code: 'INTERNAL_ERROR', detail: '' }))).toBe(
      false,
    );
    expect(
      isSessionExpired(new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: '' })),
    ).toBe(false);
  });

  /**
   * Not a disjoint pair: `SESSION_EXPIRED` is narrower than "unauthenticated
   * at all" and is one of the three arms `isUnauthenticated` checks (see its
   * doc comment). Every failure `isSessionExpired` accepts is therefore also
   * accepted by `isUnauthenticated` -- the two only diverge the other way,
   * for a bare 401 or a plain `UNAUTHENTICATED` that is not a session ending.
   */
  it('every SESSION_EXPIRED failure is also isUnauthenticated (subset, not disjoint)', () => {
    const expired = new HorecaOSApiError({ status: 401, code: 'SESSION_EXPIRED', detail: '' });
    expect(isSessionExpired(expired)).toBe(true);
    expect(isUnauthenticated(expired)).toBe(true);
  });

  it('isNotFound is disjoint from both: true only at status 404, regardless of code', () => {
    const notFound = new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: '' });
    expect(isNotFound(notFound)).toBe(true);
    expect(isUnauthenticated(notFound)).toBe(false);
    expect(isSessionExpired(notFound)).toBe(false);

    const expired = new HorecaOSApiError({ status: 401, code: 'SESSION_EXPIRED', detail: '' });
    expect(isNotFound(expired)).toBe(false);

    const unauthenticated401 = new HorecaOSApiError({ status: 401, code: 'INTERNAL_ERROR', detail: '' });
    expect(isNotFound(unauthenticated401)).toBe(false);
  });

  it('isNotFound is false for a non-HorecaOSApiError', () => {
    expect(isNotFound(new Error('plain'))).toBe(false);
    expect(isNotFound(undefined)).toBe(false);
  });
});

describe('messageKeyFor', () => {
  it.each([
    ['STALE_VERSION', 'errors.staleVersion'],
    ['PRICE_CHANGED', 'errors.priceChanged'],
    ['INSUFFICIENT_CAPABILITY', 'errors.insufficientCapability'],
    ['TENANT_ACCESS_DENIED', 'errors.insufficientCapability'],
    ['ENTITLEMENT_REQUIRED', 'errors.entitlementRequired'],
    ['RESOURCE_NOT_FOUND', 'errors.notFound'],
    ['RATE_LIMIT_EXCEEDED', 'errors.rateLimited'],
    ['NETWORK_UNREACHABLE', 'errors.offline'],
    ['SOME_UNKNOWN_FUTURE_CODE', 'errors.generic'],
  ] as const)('maps %s to %s', (code, key) => {
    const error = new HorecaOSApiError({ status: 400, code, detail: 'x' });
    expect(messageKeyFor(error)).toBe(key);
  });

  it('a business reason wins over the code -- RESOURCE_CONFLICT alone would say nothing about why', () => {
    const error = new HorecaOSApiError({
      status: 409,
      code: 'RESOURCE_CONFLICT',
      detail: 'x',
      problem: { status: 409, reason: 'DELIVERY_FEE_UNRESOLVED' },
    });
    expect(messageKeyFor(error)).toBe('errors.reason.deliveryFeeUnresolved');
  });

  it('falls back to the code when the reason is present but unmapped', () => {
    const error = new HorecaOSApiError({
      status: 409,
      code: 'RESOURCE_CONFLICT',
      detail: 'x',
      problem: { status: 409, reason: 'SOME_FUTURE_REASON' },
    });
    expect(messageKeyFor(error)).toBe('errors.generic');
  });
});

describe('reasonMessageKey', () => {
  it.each([
    ['DELIVERY_FEE_UNRESOLVED', 'errors.reason.deliveryFeeUnresolved'],
    ['DELIVERY_MINIMUM_BASKET_NOT_MET', 'errors.reason.minimumBasketNotMet'],
    ['BELOW_MINIMUM_BASKET', 'errors.reason.minimumBasketNotMet'],
    ['DELIVERY_DESTINATION_REQUIRED', 'errors.reason.destinationRequired'],
    ['OUT_OF_ZONE', 'errors.reason.outOfZone'],
    ['OUTSIDE_CATCHMENT', 'errors.reason.outOfZone'],
    ['BEYOND_MAX_DISTANCE', 'errors.reason.outOfZone'],
    ['NO_TARIFF', 'errors.reason.deliveryFeeUnresolved'],
    ['LOCATION_NOT_LOCATED', 'errors.reason.deliveryFeeUnresolved'],
    ['NOT_SERVICEABLE', 'errors.reason.notServiceable'],
    ['CHANNEL_NOT_SELLABLE', 'errors.reason.notServiceable'],
    ['GUEST_ORDERS_NOT_ALLOWED', 'errors.reason.signInRequired'],
    ['CUSTOMER_BLACKLISTED', 'errors.reason.accountBlocked'],
    ['CART_EXPIRED', 'errors.reason.cartExpired'],
    ['CART_NOT_EDITABLE', 'errors.reason.cartNotEditable'],
    ['ADDRESS_NOT_FOUND', 'errors.reason.addressNotFound'],
    ['CODE_NOT_FOUND', 'errors.reason.codeNotFound'],
    ['CODE_NOT_ACTIVE', 'errors.reason.codeNotActive'],
    ['CODE_NOT_YET_ACTIVE', 'errors.reason.codeNotActive'],
    ['CODE_EXPIRED', 'errors.reason.codeExpired'],
    ['REDEMPTION_LIMIT_REACHED', 'errors.reason.codeLimitReached'],
    ['PER_CUSTOMER_LIMIT_REACHED', 'errors.reason.codeLimitReached'],
    ['CHANNEL_NOT_ENABLED', 'errors.reason.channelNotEnabled'],
    ['FULFILMENT_MODE_UNAVAILABLE', 'errors.reason.modeUnavailable'],
    ['MANUALLY_CLOSED', 'errors.reason.closed'],
    ['CLOSED_BY_EXCEPTION', 'errors.reason.closed'],
    ['OUTSIDE_SERVICE_HOURS', 'errors.reason.outsideHours'],
    ['NO_LIVE_MENU', 'errors.reason.noLiveMenu'],
    ['AT_CAPACITY', 'errors.reason.atCapacity'],
  ] as const)('maps %s to %s', (reason, key) => {
    expect(reasonMessageKey(reason)).toBe(key);
  });

  it('is null for an absent, empty or unrecognised reason', () => {
    expect(reasonMessageKey(null)).toBeNull();
    expect(reasonMessageKey(undefined)).toBeNull();
    expect(reasonMessageKey('')).toBeNull();
    expect(reasonMessageKey('SOME_FUTURE_REASON')).toBeNull();
  });
});
