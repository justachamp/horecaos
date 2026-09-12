/**
 * The GA4 ecommerce event contract, v1 (ADR 0106, gap-map row `10.8e`).
 *
 * Documented in full at `docs/analytics/ga4-ecommerce-event-contract-v1.md`
 * — this file is the runtime half: the shapes and the one function that ever
 * writes to `window.dataLayer`. Four named events (`view_item`,
 * `add_to_cart`, `begin_checkout`, `purchase`), each an object shaped like
 * GA4's own Measurement Protocol ecommerce object (`items[]`, `currency`,
 * `value`) so a tenant's GA4 property renders standard ecommerce reports
 * without a custom schema on Google's side.
 *
 * **Why a `contractVersion` on every event.** A future field rename (an item
 * gaining a `variant` dimension, say) ships as `v2` rather than silently
 * reinterpreting a year of a tenant's own GA4 history — the same reasoning
 * this platform already applies to its own event envelopes (ADR 0004/0032)
 * applied here for the first time to an event this platform's backend never
 * sees.
 *
 * **What this wave wires, and what it does not.** `purchase` fires from
 * `CartOrderStatusComponent` — every checkout ends there. `view_item` and
 * `add_to_cart` are contract-ready (the type union below names them, and
 * {@link pushEcommerceEvent} accepts them identically) but have no call site
 * yet: the product and cart screens are untouched this wave. Named as an
 * open input in ADR 0106 rather than silently dropped.
 */

export const ECOMMERCE_CONTRACT_VERSION = 1 as const;

/** One line of an order, shaped like GA4's own `items[]` entry. */
export interface EcommerceItem {
  readonly item_id: string;
  readonly item_name: string;
  readonly price: number;
  readonly quantity: number;
}

interface EcommercePayload {
  readonly currency: string;
  readonly value: number;
  readonly items: readonly EcommerceItem[];
  /** `purchase` only — the platform order id, so a retried render cannot double-count. */
  readonly transaction_id?: string;
}

export type EcommerceEventName = 'view_item' | 'add_to_cart' | 'begin_checkout' | 'purchase';

export interface EcommerceEvent {
  readonly contractVersion: typeof ECOMMERCE_CONTRACT_VERSION;
  readonly event: EcommerceEventName;
  readonly ecommerce: EcommercePayload;
}

declare global {
  interface Window {
    dataLayer?: unknown[];
  }
}

/**
 * Pushes one versioned ecommerce event to `window.dataLayer`.
 *
 * Safe to call before GTM/gtag.js has loaded, or when a brand has no
 * analytics installation at all: `dataLayer` is a plain array queue by
 * design (the standard GTM snippet drains it once the container loads), and
 * this function creates it if absent rather than requiring a caller to check
 * first. An event pushed with nothing downstream to read it is inert, not an
 * error — the same "inject nothing" posture `StorefrontAnalyticsConfigController`
 * takes for a brand with no `ANALYTICS` binding.
 */
export function pushEcommerceEvent(event: EcommerceEventName, ecommerce: EcommercePayload): void {
  if (typeof window === 'undefined') {
    return;
  }
  window.dataLayer = window.dataLayer ?? [];
  const payload: EcommerceEvent = { contractVersion: ECOMMERCE_CONTRACT_VERSION, event, ecommerce };
  window.dataLayer.push(payload);
}
