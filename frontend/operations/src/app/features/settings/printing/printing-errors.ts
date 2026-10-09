import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { describeApiError } from '../../orders/order-errors';
import { printRefusal } from './printing-api';

/** The two sentences a screen owns for the printing module's own refusals; the rest go through the console's usual path. */
export interface RefusalKeys {
  readonly off: MessageKey;
  readonly noRoute: MessageKey;
}

/**
 * The sentence for a failed printing call. `PRINTING_OFF` and `NO_ROUTE` say what to do about it in the
 * screen's own words (the keys differ by screen because the settings and orders catalogues load separately);
 * anything else is the ordinary ADR 0031 description.
 */
export function describePrintError(error: unknown, i18n: I18n, keys: RefusalKeys): string {
  switch (printRefusal(error)) {
    case 'PRINTING_OFF':
      return i18n.t(keys.off);
    case 'NO_ROUTE':
      return i18n.t(keys.noRoute);
    default:
      break;
  }
  if (error instanceof ApiError) {
    return describeApiError(error, (key, values) => i18n.t(key, values));
  }
  return i18n.t('error.unknown.noReference');
}
