import { HttpErrorResponse } from '@angular/common/http';

/**
 * The stable error vocabulary from `uz.horecaos.platform.web.api.ErrorCode`.
 *
 * ADR 0031 says clients branch on `code`, never on `title` or `detail`, and that
 * new codes are an additive change a client must tolerate. The `(string & {})`
 * arm is what makes an unrecognised code a value this client can carry and
 * report rather than a parse failure.
 */
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'INVALID_REQUEST'
  | 'MALFORMED_BODY'
  | 'IDEMPOTENCY_KEY_REQUIRED'
  | 'UNAUTHENTICATED'
  | 'INSUFFICIENT_CAPABILITY'
  | 'ENTITLEMENT_REQUIRED'
  | 'TENANT_ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND'
  | 'RESOURCE_CONFLICT'
  | 'STALE_VERSION'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'IDEMPOTENCY_KEY_IN_PROGRESS'
  | 'PRICE_CHANGED'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'RATE_LIMIT_EXCEEDED'
  | 'INTERNAL_ERROR'
  // eslint-disable-next-line @typescript-eslint/ban-types
  | (string & {});

/** A field-level failure: a stable code, not prose. */
export interface FieldError {
  readonly field: string;
  readonly code: string;
  readonly message?: string;
}

/** RFC 9457, plus the HorecaOS extensions ApiProblem always sets. */
export interface ProblemDetails {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly code?: ErrorCode;
  readonly correlationId?: string;
  readonly errors?: readonly FieldError[];
  /** STALE_VERSION carries the version the server actually holds. */
  readonly currentVersion?: number;
  readonly expectedVersion?: number;
  /** Checkout rejections carry a business reason beside the code. */
  readonly reason?: string;
}

export function isProblemDetails(body: unknown): body is ProblemDetails {
  return (
    typeof body === 'object' &&
    body !== null &&
    ('code' in body || 'title' in body) &&
    'status' in body
  );
}

/**
 * Every failure a caller sees, whatever produced it.
 *
 * A transport failure is normalised into the same shape as a server rejection so
 * no call site has to distinguish "the server said no" from "the request never
 * arrived" before it can read a code.
 */
export class HorecaOSApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  readonly detail: string;
  readonly correlationId?: string;
  readonly fieldErrors: readonly FieldError[];
  readonly problem?: ProblemDetails;
  readonly retryAfterSeconds?: number;

  constructor(init: {
    status: number;
    code: ErrorCode;
    detail: string;
    correlationId?: string;
    fieldErrors?: readonly FieldError[];
    problem?: ProblemDetails;
    retryAfterSeconds?: number;
  }) {
    // `detail` is developer-facing and, per ADR 0031, carries no PII. It is
    // still never the string shown to a customer: see `messageKeyFor`.
    super(`${init.code} (${init.status}): ${init.detail}`);
    this.name = 'HorecaOSApiError';
    this.status = init.status;
    this.code = init.code;
    this.detail = init.detail;
    this.correlationId = init.correlationId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.problem = init.problem;
    this.retryAfterSeconds = init.retryAfterSeconds;
  }

  /** True when the aggregate moved under the caller and a reload is the fix. */
  get isStaleVersion(): boolean {
    return this.code === 'STALE_VERSION';
  }

  /** True when the same idempotency key is already in flight; retry, do not resend. */
  get isIdempotencyInProgress(): boolean {
    return this.code === 'IDEMPOTENCY_KEY_IN_PROGRESS';
  }
}

/**
 * True when the platform did not accept the caller as anybody.
 *
 * The realistic failure on a long-lived screen: a customer opens the app, walks
 * away, comes back, and the token that was valid when the screen mounted has
 * expired. **This must never be shown as an empty list.** "You have no saved
 * addresses" and "we could not tell who you are" look identical if a screen
 * treats every failure as nothing-to-show, and the first one is a lie that
 * invites the customer to type their address in again.
 *
 * Both arms are needed. The filter chain rejects an unauthenticated call before
 * a handler runs, so the body is not always an ADR 0031 problem document with a
 * `code` in it — and a bare 401 normalises to `INTERNAL_ERROR` at status 401.
 */
export function isUnauthenticated(failure: unknown): boolean {
  return (
    failure instanceof HorecaOSApiError &&
    (failure.status === 401 ||
      failure.code === 'UNAUTHENTICATED' ||
      failure.code === 'SESSION_EXPIRED')
  );
}

/**
 * True when the caller *was* somebody and the session they were holding has
 * ended — expired on its own, or revoked from another device.
 *
 * Narrower than {@link isUnauthenticated} and deliberately so. Both answer 401,
 * so a client that branches on the status alone cannot tell them apart, and the
 * two want opposite handling: a stranger is shown a first-time sign-in, while
 * somebody whose token died mid-basket is shown that they were signed out and
 * given the same screen back. The platform added `SESSION_EXPIRED` to make the
 * distinction available (ADR 0051); reading only the status throws it away.
 *
 * The code is only ever produced against a presented customer token, so this is
 * also the one failure that proves the bearer this tab is holding is dead —
 * which is what {@link Session.expire} acts on.
 */
export function isSessionExpired(failure: unknown): boolean {
  return failure instanceof HorecaOSApiError && failure.code === 'SESSION_EXPIRED';
}

/**
 * True when the platform says there is no such resource *for this caller*.
 *
 * On an ownership-authorised surface this is deliberately overloaded: somebody
 * else's resource, an archived one, and one that never existed are the same
 * answer. A client that renders them apart re-creates the leak the server went
 * out of its way to close.
 */
export function isNotFound(failure: unknown): boolean {
  return failure instanceof HorecaOSApiError && failure.status === 404;
}

export function toHorecaOSApiError(response: HttpErrorResponse): HorecaOSApiError {
  const retryAfter = Number(response.headers?.get('Retry-After'));
  const retryAfterSeconds = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined;

  if (isProblemDetails(response.error)) {
    const problem = response.error;
    return new HorecaOSApiError({
      status: problem.status ?? response.status,
      code: problem.code ?? 'INTERNAL_ERROR',
      detail: problem.detail ?? problem.title ?? response.statusText,
      correlationId: problem.correlationId,
      fieldErrors: problem.errors ?? [],
      problem,
      retryAfterSeconds,
    });
  }

  // Status 0 is the browser refusing to tell us why: offline, DNS, CORS, or a
  // WebView that killed the request. Guessing between them would be fiction.
  if (response.status === 0) {
    return new HorecaOSApiError({
      status: 0,
      code: 'NETWORK_UNREACHABLE',
      detail: 'The request did not reach the platform.',
      retryAfterSeconds,
    });
  }

  return new HorecaOSApiError({
    status: response.status,
    code: 'INTERNAL_ERROR',
    detail: response.statusText || 'Unrecognised error response.',
    retryAfterSeconds,
  });
}

/**
 * Maps a business `reason` code to a translation key.
 *
 * Two vocabularies land here, both stable machine codes a customer must never
 * read as-is:
 *
 * - The cart/checkout refusal codes `CartService.CartRefusedException` and
 *   `CheckoutService.CheckoutResult` carry (`StorefrontOrderingController`'s
 *   `refusal`/`errorCodeFor`, including ADR 0037's `DELIVERY_FEE_UNRESOLVED`
 *   and `DELIVERY_MINIMUM_BASKET_NOT_MET`, and the granular
 *   `DeliveryChargeResponse.reasonCode` a priced cart's own `delivery` block
 *   carries -- `OUT_OF_ZONE`, `NO_TARIFF`, `LOCATION_NOT_LOCATED`,
 *   `OUTSIDE_CATCHMENT`, `BEYOND_MAX_DISTANCE`, `BELOW_MINIMUM_BASKET`).
 * - `uz.horecaos.platform.tenancy.api.ServiceabilityReason` (`CHANNEL_NOT_ENABLED`,
 *   `FULFILMENT_MODE_UNAVAILABLE`, `MANUALLY_CLOSED`, `CLOSED_BY_EXCEPTION`,
 *   `OUTSIDE_SERVICE_HOURS`, `NO_LIVE_MENU`, `AT_CAPACITY`), which the
 *   fulfilment-modes read reports per mode.
 *
 * A code not named here (a future addition, or one this build never expected
 * to see outside its own screen) resolves to `null` so a caller can fall back
 * to its own generic wording instead of silently mis-describing it.
 */
const REASON_MESSAGE_KEYS: Readonly<Record<string, string>> = {
  // ADR 0037 delivery-fee resolution, and the checkout-time refusals guarding it.
  DELIVERY_FEE_UNRESOLVED: 'errors.reason.deliveryFeeUnresolved',
  DELIVERY_MINIMUM_BASKET_NOT_MET: 'errors.reason.minimumBasketNotMet',
  BELOW_MINIMUM_BASKET: 'errors.reason.minimumBasketNotMet',
  DELIVERY_DESTINATION_REQUIRED: 'errors.reason.destinationRequired',
  OUT_OF_ZONE: 'errors.reason.outOfZone',
  OUTSIDE_CATCHMENT: 'errors.reason.outOfZone',
  BEYOND_MAX_DISTANCE: 'errors.reason.outOfZone',
  NO_TARIFF: 'errors.reason.deliveryFeeUnresolved',
  LOCATION_NOT_LOCATED: 'errors.reason.deliveryFeeUnresolved',
  // Cart / checkout refusals.
  NOT_SERVICEABLE: 'errors.reason.notServiceable',
  CHANNEL_NOT_SELLABLE: 'errors.reason.notServiceable',
  GUEST_ORDERS_NOT_ALLOWED: 'errors.reason.signInRequired',
  CUSTOMER_BLACKLISTED: 'errors.reason.accountBlocked',
  CART_EXPIRED: 'errors.reason.cartExpired',
  CART_NOT_EDITABLE: 'errors.reason.cartNotEditable',
  ADDRESS_NOT_FOUND: 'errors.reason.addressNotFound',
  CODE_NOT_FOUND: 'errors.reason.codeNotFound',
  CODE_NOT_ACTIVE: 'errors.reason.codeNotActive',
  CODE_NOT_YET_ACTIVE: 'errors.reason.codeNotActive',
  CODE_EXPIRED: 'errors.reason.codeExpired',
  REDEMPTION_LIMIT_REACHED: 'errors.reason.codeLimitReached',
  PER_CUSTOMER_LIMIT_REACHED: 'errors.reason.codeLimitReached',
  // Row 4.2g: a well-formed line against an item whose own sale schedule
  // currently excludes it -- shown, distinct from a product that vanished
  // from the menu entirely.
  ITEM_OUT_OF_SALE_WINDOW: 'errors.reason.itemOutOfSaleWindow',
  // Row 2.1b: a checked preset code the product does not actually offer --
  // the client's own picker only ever shows offered codes, so this is a
  // catalogue change in the gap between page load and the write, not a bug
  // a customer caused.
  COMMENT_PRESET_NOT_OFFERED: 'errors.reason.presetNotOffered',
  // ServiceabilityReason (tenancy.api), from the fulfilment-modes read.
  CHANNEL_NOT_ENABLED: 'errors.reason.channelNotEnabled',
  FULFILMENT_MODE_UNAVAILABLE: 'errors.reason.modeUnavailable',
  MANUALLY_CLOSED: 'errors.reason.closed',
  CLOSED_BY_EXCEPTION: 'errors.reason.closed',
  OUTSIDE_SERVICE_HOURS: 'errors.reason.outsideHours',
  NO_LIVE_MENU: 'errors.reason.noLiveMenu',
  AT_CAPACITY: 'errors.reason.atCapacity',
};

/** See {@link REASON_MESSAGE_KEYS}. `null` for an absent or unmapped reason. */
export function reasonMessageKey(reason: string | null | undefined): string | null {
  if (!reason) {
    return null;
  }
  return REASON_MESSAGE_KEYS[reason] ?? null;
}

/**
 * The customer-facing message key for a failure.
 *
 * A specific business `reason` (see {@link reasonMessageKey}) always wins when
 * one is present -- "outside the delivery area" explains a `RESOURCE_CONFLICT`
 * far better than the code alone would. Everything else collapses to one
 * honest sentence per code rather than leaking a server `detail` into the
 * interface. The returned value is a dot-notation key for
 * `TranslateService.get`, never a sentence — the wording lives in `i18n/` with
 * every other string a customer reads.
 */
export function messageKeyFor(error: HorecaOSApiError): string {
  const reasonKey = reasonMessageKey(error.problem?.reason);
  if (reasonKey) {
    return reasonKey;
  }
  switch (error.code) {
    case 'STALE_VERSION':
      return 'errors.staleVersion';
    case 'PRICE_CHANGED':
      return 'errors.priceChanged';
    case 'INSUFFICIENT_CAPABILITY':
    case 'TENANT_ACCESS_DENIED':
      return 'errors.insufficientCapability';
    case 'ENTITLEMENT_REQUIRED':
      return 'errors.entitlementRequired';
    case 'RESOURCE_NOT_FOUND':
      return 'errors.notFound';
    case 'RATE_LIMIT_EXCEEDED':
      return 'errors.rateLimited';
    case 'NETWORK_UNREACHABLE':
      return 'errors.offline';
    default:
      return 'errors.generic';
  }
}
