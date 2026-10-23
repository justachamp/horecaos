import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient, QueryParams } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { IntentCommandRegistry, command } from '../../../core/api/idempotency';
import { CursorState, Page } from '../../../core/api/page';
import { benefitGrantPaths, reviewPaths } from '../../../core/api/reviews-paths';

/** `CaseState.java`: the four states, in the order a case moves through them. */
export type CaseState = 'NEW' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED_UNRESOLVED';

export const CASE_STATES: readonly CaseState[] = [
  'NEW',
  'IN_PROGRESS',
  'RESOLVED',
  'CLOSED_UNRESOLVED',
];

/** `SlaState.java`: where a case's clocks stand, computed on read by the server. */
export type SlaState = 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET';

/** `CaseOutcome.java`: the four that resolve a case and the four that close it unresolved. */
export type CaseOutcome =
  | 'REMEDY_GRANTED'
  | 'OFFLINE_GESTURE'
  | 'EXPLAINED_NO_REMEDY'
  | 'NO_REMEDY_NEEDED'
  | 'CUSTOMER_UNREACHABLE'
  | 'CUSTOMER_DECLINED'
  | 'COMPLAINT_NOT_UPHELD'
  | 'NOT_ACTIONABLE';

/** The outcomes a case may close with, by the state it closes into (`CaseOutcome.endsIn`). */
export const OUTCOMES_BY_CLOSING_STATE: Readonly<
  Record<'RESOLVED' | 'CLOSED_UNRESOLVED', readonly CaseOutcome[]>
> = {
  RESOLVED: ['REMEDY_GRANTED', 'OFFLINE_GESTURE', 'EXPLAINED_NO_REMEDY', 'NO_REMEDY_NEEDED'],
  CLOSED_UNRESOLVED: [
    'CUSTOMER_UNREACHABLE',
    'CUSTOMER_DECLINED',
    'COMPLAINT_NOT_UPHELD',
    'NOT_ACTIONABLE',
  ],
};

export type CaseEventKind =
  | 'OPENED'
  | 'ASSIGNED'
  | 'STARTED'
  | 'RELEASED'
  | 'NOTE'
  | 'COMPENSATION_ATTACHED'
  | 'FIRST_RESPONSE_BREACHED'
  | 'RESOLUTION_BREACHED'
  | 'RESOLVED'
  | 'CLOSED_UNRESOLVED';

export type CompensationKind = 'ORDER_REMEDY' | 'BENEFIT_GRANT';

/** A staff member as the board shows them: a name when there is one, else the non-personal reference. */
export interface StaffRef {
  readonly id: string;
  readonly displayReference: string;
  readonly name: string;
}

/** Mirrors `OperationsRecoveryCaseController.CaseSummaryResponse`. */
export interface CaseSummary {
  readonly id: string;
  readonly state: CaseState;
  readonly outcomeCode: CaseOutcome | null;
  readonly source: 'AUTO' | 'MANUAL';
  readonly reviewId: string;
  readonly orderId: string;
  readonly orderNumber: string | null;
  readonly locationId: string;
  readonly customerAccountId: string;
  readonly rating: number;
  readonly slaState: SlaState;
  readonly owner: StaffRef | null;
  readonly createdAt: string;
  readonly firstResponseDueAt: string;
  readonly resolutionDueAt: string;
  readonly firstResponseAt: string | null;
  readonly closedAt: string | null;
  readonly firstResponseBreached: boolean;
  readonly resolutionBreached: boolean;
  readonly compensationCount: number;
  readonly version: number;
}

/** Mirrors `OperationsRecoveryCaseController.CountsResponse`. */
export interface CaseCounts {
  readonly byState: Readonly<Partial<Record<CaseState, number>>>;
  readonly bySla: Readonly<Partial<Record<SlaState, number>>>;
}

export interface CaseTag {
  readonly id: string;
  readonly code: string;
  readonly category: string;
  readonly sentiment: 'POSITIVE' | 'NEGATIVE';
  readonly labels: Readonly<Record<string, string>>;
  readonly iconAssetId: string | null;
}

export interface CaseOrder {
  readonly id: string;
  readonly publicOrderNumber: string;
  readonly status: string;
  readonly currency: string;
  readonly totalMinor: number;
}

/** One line of the timeline. `note` is present only in the drawer and only for a `NOTE`. */
export interface CaseTimelineEntry {
  readonly id: string;
  readonly kind: CaseEventKind;
  readonly fromState: CaseState | null;
  readonly toState: CaseState | null;
  readonly detailCode: string | null;
  readonly actorType: string;
  readonly actor: StaffRef | null;
  readonly note: string | null;
  readonly occurredAt: string;
}

export interface CaseCompensation {
  readonly caseId: string;
  readonly kind: CompensationKind;
  readonly refId: string;
  readonly orderId: string;
  readonly amountMinor: number | null;
  readonly currency: string | null;
  readonly attachedAt: string;
  readonly attachedBy: StaffRef | null;
}

/** Mirrors `OperationsRecoveryCaseController.CaseDetailResponse`: the drawer. */
export interface CaseDetail {
  readonly summary: CaseSummary;
  readonly tags: readonly CaseTag[];
  readonly hasComment: boolean;
  readonly comment: string | null;
  readonly commentWithheld: boolean;
  readonly order: CaseOrder | null;
  readonly timeline: readonly CaseTimelineEntry[];
  readonly compensations: readonly CaseCompensation[];
  /** The ETag the drawer was read at: the version a transition is checked against. */
  readonly version: number;
}

/** What a mutation answers with: ids, states, a version and codes -- never a note or the comment. */
export interface CaseMutation {
  readonly id: string;
  readonly state: CaseState;
  readonly outcomeCode: CaseOutcome | null;
  readonly ownerStaffMemberId: string | null;
  readonly version: number;
}

export interface CaseFilters {
  readonly state?: CaseState;
  readonly ownerStaffMemberId?: string;
  readonly locationId?: string;
  readonly slaState?: SlaState;
  /** The rating of the review the case is about, 1 to 5, inclusive at both ends. */
  readonly minRating?: number;
  readonly maxRating?: number;
}

/** A remedy recorded against the case's order that the case could be linked to. `label` is a remedy type code. */
export interface RemedyCandidate {
  readonly refId: string;
  readonly label: string;
  /** `RECORDED` while nothing has been given, `EXECUTED` once it has. */
  readonly state: string;
  readonly amountMinor: number;
  readonly currency: string;
  readonly recordedAt: string;
  /** Already linked to a case: linking it again would be a conflict. */
  readonly taken: boolean;
}

/** A one-off code minted for the case's customer at its brand. The code itself is never listed, only its tail. */
export interface GrantCandidate {
  readonly grantId: string;
  readonly benefitType: 'PERCENTAGE_OFF_ORDER' | 'FIXED_AMOUNT_OFF_ORDER' | 'FREE_DELIVERY';
  /** Basis points, minor units, or zero for free delivery. */
  readonly value: number;
  readonly currency: string | null;
  readonly status: string;
  readonly sourceType: string;
  readonly codeHint: string;
  readonly createdAt: string;
  readonly expiresAt: string | null;
  readonly taken: boolean;
}

/** Mirrors `OperationsRecoveryCaseController.CompensationCandidatesResponse`: the picker behind the drawer's link. */
export interface CompensationCandidates {
  readonly remedies: readonly RemedyCandidate[];
  readonly grants: readonly GrantCandidate[];
  readonly orderId: string;
  readonly customerAccountId: string | null;
}

export type ApologyShape = GrantCandidate['benefitType'];

/** What the drawer asks the platform to mint for the case's customer. */
export interface ApologyCodeInput {
  readonly customerAccountId: string;
  readonly shape: ApologyShape;
  readonly value: number;
  readonly currency: string | null;
  readonly minBasketMinor: number;
  readonly sourceId: string;
  /** ISO instant; null lets the platform default. */
  readonly expiresAt: string | null;
}

/** The request body of `POST .../benefit-grants` as the apology-code mint sends it. */
interface ApologyCodeBody {
  readonly customerAccountId: string;
  readonly shape: ApologyShape;
  readonly value: number;
  readonly maximumDiscountMinor: null;
  readonly currency: string | null;
  readonly minBasketMinor: number;
  readonly sourceType: 'RECOVERY_CASE';
  readonly sourceId: string;
  readonly validFrom: null;
  readonly expiresAt: string | null;
}

/** The only time the plaintext code exists outside the customer's hands: shown once, kept nowhere. */
export interface MintedApologyCode {
  readonly grantId: string;
  readonly plaintextCode: string;
  readonly codeHint: string;
  readonly validFrom: string;
}

export interface TransitionInput {
  readonly to: CaseState;
  readonly ownerStaffMemberId?: string | null;
  readonly outcomeCode?: CaseOutcome | null;
}

function toQueryParams(filters: CaseFilters): QueryParams {
  return {
    state: filters.state,
    ownerStaffMemberId: filters.ownerStaffMemberId,
    locationId: filters.locationId,
    slaState: filters.slaState,
    minRating: filters.minRating,
    maxRating: filters.maxRating,
  };
}

/**
 * Service recovery (ADR 0159, row 5.4): the worklist of cases about low reviews, one case's
 * drawer, and the five things an operator does to a case.
 *
 * Every mutation here answers with ids, states, a version and codes. A note and the customer's
 * comment come back only from {@link detail}: the response of a mutation is stored for a day by
 * idempotency, and a note must not sit in that store.
 */
@Injectable({ providedIn: 'root' })
export class RecoveryApi {
  private readonly api = inject(ApiClient);

  /** The mint in flight per case, so a retry of the same code reuses its key. See {@link mintApologyCode}. */
  private readonly mintIntents = new IntentCommandRegistry<ApologyCodeBody>();

  list(scope: BrandScope, state: CursorState, filters: CaseFilters): Promise<Page<CaseSummary>> {
    return firstValueFrom(
      this.api.page<CaseSummary>(reviewPaths.recoveryCases(scope), state, toQueryParams(filters)),
    );
  }

  /**
   * One branch's own worklist, for an operator who holds `recovery.case.manage` at that branch and
   * nowhere wider: the brand route above is `BRAND` scope and refuses her. The branch is in the path,
   * so `filters.locationId` is never sent.
   */
  listAtLocation(
    scope: BrandScope,
    locationId: string,
    state: CursorState,
    filters: CaseFilters,
  ): Promise<Page<CaseSummary>> {
    return firstValueFrom(
      this.api.page<CaseSummary>(
        reviewPaths.locationRecoveryCases(scope, locationId),
        state,
        toQueryParams({ ...filters, locationId: undefined }),
      ),
    );
  }

  /** One branch's counts, the branch-scope reading of {@link counts}. */
  async countsAtLocation(scope: BrandScope, locationId: string): Promise<CaseCounts> {
    const result = await firstValueFrom(
      this.api.get<CaseCounts>(reviewPaths.recoveryLocationSummary(scope, locationId)),
    );
    return result.value;
  }

  async counts(scope: BrandScope, locationId?: string): Promise<CaseCounts> {
    const result = await firstValueFrom(
      this.api.get<CaseCounts>(reviewPaths.recoverySummary(scope), {
        params: { locationId },
      }),
    );
    return result.value;
  }

  async detail(scope: BrandScope, locationId: string, caseId: string): Promise<CaseDetail> {
    const result = await firstValueFrom(
      this.api.get<Omit<CaseDetail, 'version'>>(
        reviewPaths.recoveryCase(scope, locationId, caseId),
      ),
    );
    return { ...result.value, version: result.version ?? result.value.summary.version };
  }

  transition(
    scope: BrandScope,
    locationId: string,
    caseId: string,
    input: TransitionInput,
    expectedVersion: number,
  ): Promise<CaseMutation> {
    return firstValueFrom(
      this.api.post<TransitionInput, CaseMutation>(
        reviewPaths.recoveryTransitions(scope, locationId, caseId),
        command(input),
        { expectedVersion },
      ),
    );
  }

  open(scope: BrandScope, locationId: string, reviewId: string): Promise<CaseMutation> {
    return firstValueFrom(
      this.api.post<{ readonly reviewId: string }, CaseMutation>(
        reviewPaths.locationRecoveryCases(scope, locationId),
        command({ reviewId }),
      ),
    );
  }

  async addNote(
    scope: BrandScope,
    locationId: string,
    caseId: string,
    note: string,
  ): Promise<string> {
    const result = await firstValueFrom(
      this.api.post<
        { readonly note: string },
        { readonly caseId: string; readonly entryId: string }
      >(reviewPaths.recoveryNotes(scope, locationId, caseId), command({ note })),
    );
    return result.entryId;
  }

  /** What this case could be linked to, read under the case's own capability (a branch case worker's). */
  async candidates(
    scope: BrandScope,
    locationId: string,
    caseId: string,
  ): Promise<CompensationCandidates> {
    const result = await firstValueFrom(
      this.api.get<CompensationCandidates>(
        reviewPaths.recoveryCompensationCandidates(scope, locationId, caseId),
      ),
    );
    return result.value;
  }

  /**
   * Issues a one-off code for the case's customer (`POST .../benefit-grants`, `pricing.promotion.manage`). The
   * source is the case, so the grant says why it exists. The case is not linked here: the code is shown once and
   * the operator links the grant like any other candidate.
   *
   * <p>One mint is one intent per case (ADR 0031): the same input again after a failure -- a lost response, a
   * 502 -- goes out under the same `Idempotency-Key`, so a mint that did land is replayed and not issued a second
   * time. Both codes would be spendable, and the console cannot retract either. An input that differs is a new
   * intent and takes a new key; an issued code settles the intent.
   */
  async mintApologyCode(scope: BrandScope, input: ApologyCodeInput): Promise<MintedApologyCode> {
    const body: ApologyCodeBody = {
      customerAccountId: input.customerAccountId,
      shape: input.shape,
      value: input.value,
      maximumDiscountMinor: null,
      currency: input.currency,
      minBasketMinor: input.minBasketMinor,
      sourceType: 'RECOVERY_CASE',
      sourceId: input.sourceId,
      validFrom: null,
      expiresAt: input.expiresAt,
    };
    const issued = await firstValueFrom(
      this.api.post<ApologyCodeBody, MintedApologyCode>(
        benefitGrantPaths.mint(scope),
        this.mintIntents.next(input.sourceId, body),
      ),
    );
    this.mintIntents.forget(input.sourceId);
    return issued;
  }

  attach(
    scope: BrandScope,
    locationId: string,
    caseId: string,
    kind: CompensationKind,
    refId: string,
  ): Promise<CaseCompensation> {
    return firstValueFrom(
      this.api.post<{ readonly kind: CompensationKind; readonly refId: string }, CaseCompensation>(
        reviewPaths.recoveryCompensations(scope, locationId, caseId),
        command({ kind, refId }),
      ),
    );
  }
}
