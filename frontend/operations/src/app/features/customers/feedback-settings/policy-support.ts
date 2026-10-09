import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { describeApiError } from '../../orders/order-errors';

/** The sentence for a failed policy read or save: a lost race is its own message, the rest the shared one. */
export function describePolicyError(i18n: I18n, error: unknown): string {
  if (error instanceof ApiError) {
    return error.code === ApiErrorCode.STALE_VERSION
      ? i18n.t('customers.feedback.error.stale')
      : describeApiError(error, (key, values) => i18n.t(key, values));
  }
  throw error;
}

export function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION;
}

export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

export function toInt(value: string, fallback: number): number {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}
