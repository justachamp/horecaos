/**
 * Where the call centre's lead queue and the customer card live (ADR 0111).
 *
 * The queue is reached at two levels because a grant covers only the routes whose path names its
 * own level (ADR 0025): the *brand* routes are the call centre's — the whole queue, where a lead is
 * registered and handed to a branch — and the *branch* routes are a branch's own, the leads handed
 * to it and no others. {@link LeadReach} says which one the signed-in operator is using.
 */

/** Which of the two levels a lead call is made at. */
export type LeadReach =
  | { readonly kind: 'BRAND'; readonly tenantId: string; readonly brandId: string }
  | {
      readonly kind: 'LOCATION';
      readonly tenantId: string;
      readonly brandId: string;
      readonly locationId: string;
    };

const TENANTS = '/api/v1/tenants';

function enc(value: string): string {
  return encodeURIComponent(value);
}

export const leadPaths = {
  /** `LeadController` — the queue (GET) and registering a lead (POST, brand reach only). */
  leads(reach: LeadReach): string {
    const brand = `${TENANTS}/${enc(reach.tenantId)}/brands/${enc(reach.brandId)}`;
    return reach.kind === 'BRAND'
      ? `${brand}/leads`
      : `${brand}/locations/${enc(reach.locationId)}/leads`;
  },

  lead(reach: LeadReach, leadId: string): string {
    return `${this.leads(reach)}/${enc(leadId)}`;
  },

  transitions(reach: LeadReach, leadId: string): string {
    return `${this.lead(reach, leadId)}/transitions`;
  },

  /** Handing a lead to a branch — brand reach only: a branch does not decide who else gets a lead. */
  assignment(reach: LeadReach, leadId: string): string {
    return `${this.lead(reach, leadId)}/assignment`;
  },

  /** The one decrypt a lead has, purpose-stamped and audited. */
  contact(reach: LeadReach, leadId: string): string {
    return `${this.lead(reach, leadId)}/contact`;
  },

  contactAttempts(reach: LeadReach, leadId: string): string {
    return `${this.lead(reach, leadId)}/contact-attempts`;
  },

  /** `CustomerCardController` — opening one guest's card writes one audit fact. */
  card(tenantId: string, accountId: string): string {
    return `${TENANTS}/${enc(tenantId)}/customers/${enc(accountId)}/card`;
  },

  /** A call about a guest who is already an account. */
  customerContactAttempts(tenantId: string, accountId: string): string {
    return `${TENANTS}/${enc(tenantId)}/customers/${enc(accountId)}/contact-attempts`;
  },
} as const;
