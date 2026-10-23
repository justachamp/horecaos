import type { ApiError } from '../api/problem-details';
import type { I18n } from './i18n';
import type { MessageKey } from './message-keys';

/**
 * The sentence a courier reads for a failure.
 *
 * A request that never arrived is the connection's sentence. Otherwise the platform's stable
 * `reason` and then its `code` are looked up under the screen's own prefix
 * (`shift.error.UNPROCESSABLE_STATE`), so each screen says what the refusal means *there*; a
 * refusal nobody wrote a sentence for falls to the generic one. The platform's own `detail` is a
 * developer's string and is never shown (ADR 0031).
 */
export function errorMessage(i18n: I18n, error: ApiError | null, prefix: string): string | null {
  if (error === null) {
    return null;
  }
  if (error.isNetwork) {
    return i18n.t('error.network');
  }
  for (const part of [error.reason, error.code]) {
    const key = part === null ? null : `${prefix}.${part}`;
    if (key !== null && i18n.has(key)) {
      return i18n.t(key as MessageKey);
    }
  }
  return error.status === 403 ? i18n.t('error.forbidden') : i18n.t('error.generic');
}
