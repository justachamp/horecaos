import { LeadAccess } from './lead-reach';
import { Lead } from './leads-api';

/** A lead as the queue returns it: a masked number and flags, never the guest. */
export function lead(overrides: Partial<Lead> = {}): Lead {
  return {
    id: 'lead-1',
    brandId: 'brand-1',
    status: 'NEW',
    source: 'CALLBACK_REQUEST',
    phoneMasked: '+998 ** *** 45 67',
    hasName: true,
    hasNotes: false,
    customerAccountId: null,
    assignedLocationId: null,
    assignedAt: null,
    callbackDueAt: null,
    originCampaignId: null,
    originStepSequence: null,
    convertedOrderId: null,
    convertedReservationId: null,
    closedReason: null,
    createdBy: 'operator-1',
    version: 1,
    createdAt: '2026-10-07T08:00:00Z',
    updatedAt: '2026-10-07T08:00:00Z',
    possibleAccountIds: [],
    otherOpenLeadIds: [],
    ...overrides,
  };
}

export const BRAND_ACCESS: LeadAccess = {
  reach: { kind: 'BRAND', tenantId: 'tenant-1', brandId: 'brand-1' },
  canManage: true,
  canReveal: true,
};

export const BRANCH_ACCESS: LeadAccess = {
  reach: { kind: 'LOCATION', tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' },
  canManage: true,
  canReveal: true,
};

export async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}
