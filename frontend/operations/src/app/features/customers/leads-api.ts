import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient, QueryParams } from '../../core/api/api-client';
import { Versioned } from '../../core/api/aggregate-version';
import { command } from '../../core/api/idempotency';
import { LeadReach, leadPaths } from '../../core/api/lead-paths';
import { CursorState, Page } from '../../core/api/page';

/**
 * Types mirror `LeadController`, `CustomerCardController` and the records they return
 * (`LeadService.LeadView`, `ContactAttemptService.ContactAttemptView`,
 * `CustomerCardAssemblyService.CustomerCard`), copied by hand like `customers-api.ts` does.
 */

export type LeadStatus =
  'NEW' | 'CONTACTED' | 'CALLBACK_SCHEDULED' | 'CONVERTED' | 'DECLINED' | 'LOST';

export const LEAD_STATUSES: readonly LeadStatus[] = [
  'NEW',
  'CONTACTED',
  'CALLBACK_SCHEDULED',
  'CONVERTED',
  'DECLINED',
  'LOST',
];

export type LeadSource =
  | 'STOREFRONT'
  | 'TELEGRAM_BOT'
  | 'SITE'
  | 'RESERVATION'
  | 'CALLBACK_REQUEST'
  | 'AGGREGATOR_FIRST_ORDER'
  | 'B2B_CATERING_ENQUIRY'
  | 'CAMPAIGN_SCENARIO';

/** The sources an operator may register by hand: a campaign scenario's call task arrives by itself. */
export const REGISTRABLE_LEAD_SOURCES: readonly LeadSource[] = [
  'CALLBACK_REQUEST',
  'B2B_CATERING_ENQUIRY',
  'SITE',
  'STOREFRONT',
  'TELEGRAM_BOT',
  'RESERVATION',
  'AGGREGATOR_FIRST_ORDER',
];

export const LEAD_SOURCES: readonly LeadSource[] = [
  ...REGISTRABLE_LEAD_SOURCES,
  'CAMPAIGN_SCENARIO',
];

export type LeadClosedReason =
  | 'OUT_OF_CAPACITY'
  | 'OUT_OF_COVERAGE'
  | 'WRONG_CUISINE_OR_MENU'
  | 'NOT_REACHABLE'
  | 'NOT_INTERESTED'
  | 'DUPLICATE'
  | 'SPAM_OR_WRONG_NUMBER'
  | 'OTHER';

export const LEAD_CLOSED_REASONS: readonly LeadClosedReason[] = [
  'OUT_OF_CAPACITY',
  'OUT_OF_COVERAGE',
  'WRONG_CUISINE_OR_MENU',
  'NOT_REACHABLE',
  'NOT_INTERESTED',
  'DUPLICATE',
  'SPAM_OR_WRONG_NUMBER',
  'OTHER',
];

/** `LeadService.LeadView` — never a number, a name or a note: only whether a name and notes exist. */
export interface Lead {
  readonly id: string;
  readonly brandId: string;
  readonly status: LeadStatus;
  readonly source: LeadSource;
  readonly phoneMasked: string;
  readonly hasName: boolean;
  readonly hasNotes: boolean;
  readonly customerAccountId: string | null;
  readonly assignedLocationId: string | null;
  readonly assignedAt: string | null;
  readonly callbackDueAt: string | null;
  readonly originCampaignId: string | null;
  readonly originStepSequence: number | null;
  readonly convertedOrderId: string | null;
  readonly convertedReservationId: string | null;
  readonly closedReason: LeadClosedReason | null;
  readonly createdBy: string;
  readonly version: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  /** Accounts holding the same number — a hint to confirm with `linkCustomer`, never a link of its own. */
  readonly possibleAccountIds: readonly string[];
  readonly otherOpenLeadIds: readonly string[];
}

/** `LeadController.RegisterLeadRequest`. */
export interface RegisterLeadRequest {
  readonly source: LeadSource;
  readonly phone: string;
  readonly displayName?: string;
  readonly notes?: string;
  readonly customerAccountId?: string;
  readonly assignedLocationId?: string;
}

/** `LeadController.TransitionLeadRequest`. Only the fields its target uses are read. */
export interface TransitionLeadRequest {
  readonly target: LeadStatus;
  readonly callbackDueAt?: string;
  readonly convertedOrderId?: string;
  readonly convertedReservationId?: string;
  readonly closedReason?: LeadClosedReason;
  readonly reason?: string;
}

/** `LeadService.RevealedLeadContact`. A guest's number, name and notes: render, never log. */
export interface RevealedLeadContact {
  readonly phone: string;
  readonly displayName: string | null;
  readonly notes: string | null;
}

export type ContactDirection = 'INBOUND' | 'OUTBOUND';
export type ContactOutcome = 'CONNECTED' | 'NO_ANSWER' | 'DECLINED' | 'VOICEMAIL' | 'BLOCKED';
export type BlockingReason = 'BLACKLISTED' | 'OUTSIDE_QUIET_HOURS' | 'NO_CONSENT' | 'WRONG_NUMBER';
export type NextAction = 'CALL_AGAIN' | 'AWAIT_GUEST' | 'HAND_TO_BRANCH';

export const CONTACT_OUTCOMES: readonly ContactOutcome[] = [
  'CONNECTED',
  'NO_ANSWER',
  'DECLINED',
  'VOICEMAIL',
  'BLOCKED',
];
export const BLOCKING_REASONS: readonly BlockingReason[] = [
  'BLACKLISTED',
  'OUTSIDE_QUIET_HOURS',
  'NO_CONSENT',
  'WRONG_NUMBER',
];
export const NEXT_ACTIONS: readonly NextAction[] = ['CALL_AGAIN', 'AWAIT_GUEST', 'HAND_TO_BRANCH'];

/** `ContactAttemptService.ContactAttemptView` — append-only: there is no way to change one. */
export interface ContactAttempt {
  readonly id: string;
  readonly brandId: string;
  readonly leadId: string | null;
  readonly customerAccountId: string | null;
  readonly direction: ContactDirection;
  readonly attemptId: string;
  readonly outcome: ContactOutcome;
  readonly blockingReason: BlockingReason | null;
  readonly operatorActorId: string;
  readonly occurredAt: string;
  readonly recordedAt: string;
  readonly nextAction: NextAction | null;
  readonly nextActionAt: string | null;
}

/** `LeadController.ContactAttemptRequest`. */
export interface RecordContactAttemptRequest {
  readonly direction: ContactDirection;
  readonly outcome: ContactOutcome;
  readonly blockingReason?: BlockingReason;
  readonly attemptId?: string;
  readonly occurredAt?: string;
  readonly nextAction?: NextAction;
  readonly nextActionAt?: string;
}

export type HistoryKind =
  'NOTIFICATION' | 'CAMPAIGN_RECEIPT' | 'PROMO_REDEMPTION' | 'REVIEW' | 'VOICE_CONTACT';

/** `CustomerHistoryEntry` — a stable code per field, never prose and never a guest's own words. */
export interface HistoryEntry {
  readonly kind: HistoryKind;
  readonly occurredAt: string;
  readonly channel: string | null;
  readonly statusCode: string;
  readonly detailCode: string | null;
  readonly referenceId: string | null;
  readonly orderId: string | null;
  readonly rating: number | null;
  readonly label: string | null;
}

/** `CustomerCardAssemblyService.CustomerCard`. */
export interface CustomerCard {
  readonly customerAccountId: string;
  readonly status: string;
  readonly displayName: string | null;
  readonly preferredLocale: string | null;
  readonly version: number;
  readonly blacklisted: boolean;
  readonly leads: readonly Lead[];
  readonly history: readonly HistoryEntry[];
  /** Pass as `before`, with {@link nextBeforeId}, for the next older page; null at the end. */
  readonly nextBefore: string | null;
  /**
   * Pass as `beforeId` with {@link nextBefore}. The pair is one position: entries that share the last
   * one's instant and did not fit are on the next page, and an instant alone would skip them.
   */
  readonly nextBeforeId: string | null;
}

export interface LeadFilters {
  /** `attention`: new leads and callbacks that are due or about to be; absent is everything. */
  readonly view?: 'attention';
  readonly status?: readonly LeadStatus[];
  readonly source?: LeadSource;
  readonly assignedLocationId?: string;
  readonly unassigned?: boolean;
  readonly customerAccountId?: string;
}

function toQueryParams(filters: LeadFilters): QueryParams {
  return {
    view: filters.view,
    status: filters.status && filters.status.length > 0 ? [...filters.status] : undefined,
    source: filters.source,
    assignedLocationId: filters.assignedLocationId,
    unassigned: filters.unassigned ? true : undefined,
    customerAccountId: filters.customerAccountId,
  };
}

/**
 * The call centre's seam: the lead queue, a lead's journal of calls, and the customer card (ADR 0111).
 *
 * Every write names the lead's version in `If-Match` where the server demands it and carries a fresh
 * `Idempotency-Key` (ADR 0031). **Nothing here caches**: a number, a name and notes are personal data
 * (ADR 0029), and a screen holds what it rendered and drops it with the screen.
 */
@Injectable({ providedIn: 'root' })
export class LeadsApi {
  private readonly api = inject(ApiClient);

  list(reach: LeadReach, state: CursorState, filters: LeadFilters = {}): Promise<Page<Lead>> {
    return firstValueFrom(
      this.api.page<Lead>(leadPaths.leads(reach), state, toQueryParams(filters)),
    );
  }

  detail(reach: LeadReach, leadId: string): Promise<Versioned<Lead>> {
    return firstValueFrom(this.api.get<Lead>(leadPaths.lead(reach, leadId)));
  }

  /** Brand reach only. A phoned-in catering enquiry, a callback somebody asked for. */
  register(reach: LeadReach, request: RegisterLeadRequest): Promise<Lead> {
    return firstValueFrom(
      this.api.post<RegisterLeadRequest, Lead>(leadPaths.leads(reach), command(request)),
    );
  }

  transition(
    reach: LeadReach,
    leadId: string,
    request: TransitionLeadRequest,
    expectedVersion: number,
  ): Promise<Lead> {
    return firstValueFrom(
      this.api.post<TransitionLeadRequest, Lead>(
        leadPaths.transitions(reach, leadId),
        command(request),
        { expectedVersion },
      ),
    );
  }

  /** Brand reach only. */
  assign(
    reach: LeadReach,
    leadId: string,
    locationId: string,
    expectedVersion: number,
  ): Promise<Lead> {
    return firstValueFrom(
      this.api.post<{ locationId: string }, Lead>(
        leadPaths.assignment(reach, leadId),
        command({ locationId }),
        { expectedVersion },
      ),
    );
  }

  /**
   * Confirms the guest behind a lead (ADR 0111 §4): the operator looked at an account the detail hinted at
   * and says it is her. The lead then belongs to that account's card, and to its erasure.
   */
  linkCustomer(
    reach: LeadReach,
    leadId: string,
    customerAccountId: string,
    expectedVersion: number,
  ): Promise<Lead> {
    return firstValueFrom(
      this.api.post<{ customerAccountId: string }, Lead>(
        leadPaths.customerLink(reach, leadId),
        command({ customerAccountId }),
        { expectedVersion },
      ),
    );
  }

  /** `customer.pii.reveal`, with the purpose the audit fact names. */
  reveal(reach: LeadReach, leadId: string, purpose: string): Promise<RevealedLeadContact> {
    return firstValueFrom(
      this.api.get<RevealedLeadContact>(leadPaths.contact(reach, leadId), { params: { purpose } }),
    ).then((result) => result.value);
  }

  async attempts(reach: LeadReach, leadId: string): Promise<readonly ContactAttempt[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ContactAttempt[]>(leadPaths.contactAttempts(reach, leadId)),
    );
    return result.value ?? [];
  }

  recordAttempt(
    reach: LeadReach,
    leadId: string,
    request: RecordContactAttemptRequest,
  ): Promise<ContactAttempt> {
    return firstValueFrom(
      this.api.post<RecordContactAttemptRequest, ContactAttempt>(
        leadPaths.contactAttempts(reach, leadId),
        command(request),
      ),
    );
  }

  /**
   * Opens one guest's card. **Every call writes one audit fact** (`customer.card.viewed`), so a screen
   * asks when the card is opened and when it pages further back, never on a timer.
   */
  async openCard(
    tenantId: string,
    accountId: string,
    purpose: string,
    before?: string,
    beforeId?: string,
  ): Promise<CustomerCard> {
    const result = await firstValueFrom(
      this.api.get<CustomerCard>(leadPaths.card(tenantId, accountId), {
        params: { purpose, before, beforeId },
      }),
    );
    return result.value;
  }

  /** A call about a guest who is already an account. The brand is the line the call was on. */
  recordCustomerAttempt(
    tenantId: string,
    accountId: string,
    brandId: string,
    request: RecordContactAttemptRequest,
  ): Promise<ContactAttempt> {
    const body = { brandId, ...request };
    return firstValueFrom(
      this.api.post<typeof body, ContactAttempt>(
        leadPaths.customerContactAttempts(tenantId, accountId),
        command(body),
      ),
    );
  }
}
