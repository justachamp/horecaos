import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The message key for a failed API call's `code` — shared between the queue's
 * error band and the detail pane, which both surface the same ADR 0031 codes
 * (`error.STALE_VERSION` is listed here even though the mutation call sites
 * handle that one specially, re-reading rather than merely displaying it — see
 * `order-actions-api.ts` — because a caller that reaches this map for some
 * other reason should still get an honest label rather than the generic
 * fallback).
 */
export const ERROR_MESSAGE_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  [ApiErrorCode.NETWORK_UNREACHABLE]: 'error.NETWORK_UNREACHABLE',
  [ApiErrorCode.UNAUTHENTICATED]: 'error.UNAUTHENTICATED',
  [ApiErrorCode.INSUFFICIENT_CAPABILITY]: 'error.INSUFFICIENT_CAPABILITY',
  [ApiErrorCode.ENTITLEMENT_REQUIRED]: 'error.ENTITLEMENT_REQUIRED',
  [ApiErrorCode.STALE_VERSION]: 'error.STALE_VERSION',
  [ApiErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS]: 'error.IDEMPOTENCY_KEY_IN_PROGRESS',
  [ApiErrorCode.RESOURCE_NOT_FOUND]: 'error.RESOURCE_NOT_FOUND',
  [ApiErrorCode.RATE_LIMIT_EXCEEDED]: 'error.RATE_LIMIT_EXCEEDED',
};

/** The operator-facing sentence for a failed call, never `problem.detail` (that is English, for a developer). */
export function describeApiError(
  error: ApiError,
  translate: (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string,
): string {
  const key = ERROR_MESSAGE_KEYS[error.code];
  if (key) {
    return translate(key);
  }
  return error.correlationId
    ? translate('error.unknown', { correlationId: error.correlationId })
    : translate('error.unknown.noReference');
}

/** ADR 0031's errorCode and correlation id, for support. */
export function errorReference(error: ApiError): string {
  return error.correlationId ? `${error.code} · ${error.correlationId}` : error.code;
}

/**
 * Which of ADR 0025's two refusal codes a failed call carries, and the name
 * the server attached to it — `INSUFFICIENT_CAPABILITY` and
 * `ENTITLEMENT_REQUIRED` both fall through {@link describeApiError} to the
 * same flat one-line sentence today, and operations IA §9.1d's whole
 * complaint is that a screen cannot then tell its operator whether she is
 * looking at a wall or an upsell. A caller that wants to distinguish them —
 * to render `q-denied-state` or `q-locked-state` instead of the flat
 * sentence — reaches for this first.
 */
export interface AccessRefusal {
  /** `'denied'` for a missing capability (a wall); `'locked'` for a missing entitlement (an upsell). */
  readonly kind: 'denied' | 'locked';
  /**
   * The capability constant (`kind: 'denied'`, from `ApiException.insufficientCapability`'s
   * `requiredCapability` property) or the entitlement key (`kind: 'locked'`,
   * from `ApiException.entitlementRequired`'s `entitlementKey` — see
   * `campaign-detail-pane.ts`'s own read of the same field). `null` when the
   * server refused without naming one, which `q-denied-state`/`q-locked-state`
   * both render as an unnamed refusal rather than a missing value.
   */
  readonly name: string | null;
}

/** `null` for every code that is neither of ADR 0025's two refusals — see {@link AccessRefusal}. */
export function accessRefusal(error: ApiError): AccessRefusal | null {
  if (error.code === ApiErrorCode.INSUFFICIENT_CAPABILITY) {
    const capability = error.problem?.['requiredCapability'];
    return { kind: 'denied', name: typeof capability === 'string' ? capability : null };
  }
  if (error.code === ApiErrorCode.ENTITLEMENT_REQUIRED) {
    const entitlementKey = error.problem?.['entitlementKey'];
    return { kind: 'locked', name: typeof entitlementKey === 'string' ? entitlementKey : null };
  }
  return null;
}

/**
 * A `409 RESOURCE_CONFLICT` from an illegal transition (§4.1: "renders the
 * from/to pair in words"). `ApiException`'s extra properties carry `from` and
 * `to` on `IllegalTransitionException`; both are present or neither is, so
 * this reads them together rather than letting one be silently null.
 */
export function transitionConflict(error: ApiError): { from: string; to: string } | null {
  if (error.code !== ApiErrorCode.RESOURCE_CONFLICT || !error.problem) {
    return null;
  }
  const from = error.problem['from'];
  const to = error.problem['to'];
  return typeof from === 'string' && typeof to === 'string' ? { from, to } : null;
}

export interface MutationOutcomeNotice {
  readonly text: string;
  /**
   * True when the caller should re-read the order rather than merely show the
   * message: `STALE_VERSION` and a refused transition both mean this
   * client's picture of the order is now wrong, not just that one click
   * failed (§4.1).
   */
  readonly shouldReread: boolean;
}

/**
 * What to tell the operator, and whether to re-read, for a failed mutation —
 * shared between the queue's row actions and the detail pane's header
 * actions, since both submit the same three calls (`order-actions-api.ts`)
 * and must react to `STALE_VERSION` and a refused transition identically
 * (§4.1: "never by retrying").
 */
export function mutationErrorNotice(
  error: ApiError,
  translate: (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string,
  statusLabel: (status: string) => string,
): MutationOutcomeNotice {
  if (error.code === ApiErrorCode.STALE_VERSION) {
    return { text: translate('orders.action.staleVersion'), shouldReread: true };
  }
  const conflict = transitionConflict(error);
  if (conflict) {
    return {
      text: translate('orders.action.conflict', {
        from: statusLabel(conflict.from),
        to: statusLabel(conflict.to),
      }),
      shouldReread: true,
    };
  }
  return { text: describeApiError(error, translate), shouldReread: false };
}
