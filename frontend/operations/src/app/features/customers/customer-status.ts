import { MessageKey } from '../../core/i18n/messages.en';

/** `ck_customer_status` (V0017) — the five values a `customer.customer_accounts` row may carry. */
export const CUSTOMER_STATUSES = ['ACTIVE', 'SUSPENDED', 'MERGED', 'ANONYMIZED', 'CLOSED'] as const;

export type CustomerStatus = (typeof CUSTOMER_STATUSES)[number];

const KNOWN_STATUSES: ReadonlySet<string> = new Set(CUSTOMER_STATUSES);

export function isKnownCustomerStatus(value: string): value is CustomerStatus {
  return KNOWN_STATUSES.has(value);
}

export const CUSTOMER_STATUS_LABEL_KEYS: Readonly<Record<CustomerStatus, MessageKey>> = {
  ACTIVE: 'customers.status.ACTIVE',
  SUSPENDED: 'customers.status.SUSPENDED',
  MERGED: 'customers.status.MERGED',
  ANONYMIZED: 'customers.status.ANONYMIZED',
  CLOSED: 'customers.status.CLOSED',
};

/**
 * The label to render for a status, known or not — mirrors `orderStatusLabel`
 * in `order-status.ts` and its own doc on why an unrecognised value renders
 * as the raw wire value rather than being refused.
 */
export function customerStatusLabel(
  status: string,
  translate: (key: MessageKey) => string,
): string {
  return isKnownCustomerStatus(status) ? translate(CUSTOMER_STATUS_LABEL_KEYS[status]) : status;
}
