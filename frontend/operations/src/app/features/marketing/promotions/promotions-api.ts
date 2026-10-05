import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { promotionPaths } from '../../../core/api/promotion-paths';

/** `pricing.promotions.status`'s five states (ADR 0140's lifecycle). */
export type PromotionStatus = 'DRAFT' | 'VALIDATED' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';

export type PromotionKind = 'DISCOUNT' | 'MARKUP';

/** `ITEM` is a product-scope rule (stage 3); `ORDER` and `DELIVERY` land on the cart (stage 4). */
export type PromotionScope = 'ITEM' | 'ORDER' | 'DELIVERY';

export type LoyaltyAccrual = 'ACCRUE' | 'SUPPRESS';
export type LoyaltyRedemption = 'ALLOW' | 'BLOCK';

/** A condition or an action: a type from the closed vocabulary and its operands. Mirrors `PromotionController.RuleBody`/`RuleResponse`. */
export interface PromotionRule {
  readonly sequence: number;
  readonly type: string;
  readonly operands: Readonly<Record<string, unknown>>;
}

/** Mirrors `PromotionController.VersionResponse`: the canonical document exactly as the engine read it. */
export interface PromotionVersion {
  readonly definitionVersion: number;
  readonly reason: string;
  readonly recordedBy: string;
  readonly recordedAt: string;
  readonly definition: Readonly<Record<string, unknown>>;
}

/** Mirrors `PromotionController.PromotionResponse`. */
export interface PromotionView {
  readonly promotionId: string;
  /** The row version, sent back as `If-Match`. */
  readonly version: number;
  /** The immutable definition version an adjustment names. */
  readonly definitionVersion: number;
  readonly code: string;
  readonly name: string;
  readonly kind: PromotionKind;
  readonly scope: PromotionScope;
  readonly stackingGroup: string;
  readonly exclusive: boolean;
  readonly priority: number;
  readonly status: PromotionStatus;
  readonly maximumDiscountMinor: number | null;
  readonly currency: string;
  readonly validFrom: string | null;
  readonly validUntil: string | null;
  readonly maximumRedemptions: number | null;
  readonly maximumPerCustomer: number | null;
  readonly consumedCount: number;
  readonly loyaltyAccrual: LoyaltyAccrual;
  readonly loyaltyRedemption: LoyaltyRedemption;
  readonly conditions: readonly PromotionRule[];
  readonly actions: readonly PromotionRule[];
  readonly validatedAt: string | null;
  readonly activatedAt: string | null;
  readonly activatedBy: string | null;
  readonly approvalId: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  /** Newest first; present on the detail read only. */
  readonly versions: readonly PromotionVersion[];
}

/**
 * Mirrors `PromotionController.PromotionBody`. Every optional field is
 * omitted-or-null, never a missing primitive: Jackson 3 refuses a missing
 * primitive in a request body, and the server boxes what the console leaves out.
 */
export interface PromotionBody {
  readonly code: string;
  readonly name: string;
  readonly kind: PromotionKind;
  readonly scope: PromotionScope;
  readonly stackingGroup: string;
  readonly exclusive: boolean;
  readonly priority: number;
  readonly maximumDiscountMinor: number | null;
  readonly currency: string;
  readonly validFrom: string | null;
  readonly validUntil: string | null;
  readonly maximumRedemptions: number | null;
  readonly maximumPerCustomer: number | null;
  readonly loyaltyAccrual: LoyaltyAccrual;
  readonly loyaltyRedemption: LoyaltyRedemption;
  readonly conditions: readonly PromotionRule[];
  readonly actions: readonly PromotionRule[];
}

/** One refusal or warning of `PromotionValidator`: a stable code, the condition or action it concerns, and a message. */
export interface ValidationIssue {
  readonly code: string;
  readonly message: string;
  readonly sequence: number | null;
}

/** Mirrors `PromotionController.ValidationResponse`. */
export interface ValidationResult {
  readonly valid: boolean;
  readonly refusals: readonly ValidationIssue[];
  readonly warnings: readonly ValidationIssue[];
  readonly promotion: PromotionView;
}

/**
 * Mirrors `PromotionController.ActivationResponse`: `ACTIVATED` with the
 * promotion, or `PENDING_APPROVAL` (HTTP 202) with the approval request a second
 * person has to decide before the call is repeated (ADR 0027).
 */
export interface ActivationResult {
  readonly outcome: 'ACTIVATED' | 'PENDING_APPROVAL';
  readonly approvalRequestId: string | null;
  readonly promotion: PromotionView | null;
}

/**
 * Mirrors `PromotionController.RedemptionResponse`: ids and amounts, never the customer. The account
 * behind a redemption is answered only by the audited customer-reveal (ADR 0029).
 */
export interface PromotionRedemption {
  readonly redemptionId: string;
  readonly orderId: string;
  readonly definitionVersion: number;
  readonly discountMinor: number;
  readonly markupMinor: number;
  readonly currency: string;
  /** `REDEEMED` or `RELEASED`. */
  readonly status: string;
}

// ------------------------------------------------------------------ simulator

/** Mirrors `PromotionController.SimulationBody`. There is no account id on it, by design. */
export interface SimulationRequest {
  readonly locationId: string;
  readonly channelCode: string;
  readonly fulfillmentMode: string | null;
  readonly paymentMethodCode: string | null;
  readonly serviceInstant: string | null;
  readonly lines: readonly {
    readonly lineId: string;
    readonly variantId: string;
    readonly quantity: number;
    readonly modifierOptionIds: readonly string[] | null;
  }[];
  readonly presentedCouponCode: string | null;
  readonly destination: { readonly latitude: number; readonly longitude: number } | null;
  readonly facts: {
    readonly brandOrderPosition: number | null;
    readonly channelOrderPosition: number | null;
    readonly segments: readonly string[] | null;
  } | null;
  /** An unsaved candidate definition tried against the real cart. */
  readonly candidate: PromotionBody | null;
  /** Stored definition versions to replay in place of the current ones. */
  readonly definitionVersions:
    | readonly {
        readonly promotionId: string;
        readonly definitionVersion: number;
      }[]
    | null;
}

/** `PromotionEvaluator.Verdict`: applied, or the reason a promotion did not apply. */
export type TraceVerdict =
  | 'APPLIED'
  | 'CONDITION_FAILED'
  | 'OUTSIDE_WINDOW'
  | 'COUPON_NOT_PRESENTED'
  | 'LIMIT_REACHED'
  | 'NOT_CLAIMED_AT_PLACEMENT'
  | 'LOST_TO'
  | 'SUPPRESSED_BY_EXCLUSIVE'
  | 'ZERO_BENEFIT'
  | 'CURRENCY_MISMATCH';

/** Mirrors `PromotionController.TraceResponse`: one line of the decision trace. */
export interface TraceEntry {
  readonly promotionId: string;
  readonly code: string;
  readonly verdict: TraceVerdict;
  /** The first condition that did not hold, for `CONDITION_FAILED`. */
  readonly conditionSequence: number | null;
  /** The promotions that won instead. */
  readonly lostTo: readonly string[];
  readonly benefitMinor: number;
}

export interface SimulatedLine {
  readonly lineId: string;
  readonly variantId: string | null;
  readonly quantity: number;
  readonly description: string;
  readonly unitAmountMinor: number;
  readonly finalAmountMinor: number;
  readonly taxAmountMinor: number;
}

export interface SimulatedAdjustment {
  readonly sequence: number;
  readonly lineId: string | null;
  readonly type: string;
  readonly descriptionCode: string;
  readonly promotionId: string | null;
  readonly definitionVersion: number | null;
  readonly amountMinor: number;
}

/** Mirrors `PromotionController.SimulationResponse`: what the quote endpoint would return, plus the trace and the inputs the engine read. */
export interface SimulationResult {
  readonly currency: string;
  readonly contextHash: string;
  readonly subtotalMinor: number;
  readonly taxMinor: number;
  readonly feeMinor: number;
  readonly discountMinor: number;
  readonly totalMinor: number;
  readonly loyaltyAccrualAllowed: boolean;
  readonly loyaltyRedemptionAllowed: boolean;
  readonly serviceInstant: string;
  readonly timeZone: string;
  readonly fulfillmentMode: string;
  readonly paymentMethodCode: string | null;
  readonly lines: readonly SimulatedLine[];
  readonly adjustments: readonly SimulatedAdjustment[];
  readonly trace: readonly TraceEntry[];
}

/**
 * A brand's automatic promotions and markups (operations §6.1 Promotions,
 * `PromotionController`, ADR 0140).
 *
 * The lifecycle is five calls, never one: a draft is saved, **validated** (the
 * rule checker, whose refusals are stable codes), **activated** (which asks a
 * second person above the ADR 0030 thresholds and then answers 202), and later
 * suspended, resumed or archived. An `ACTIVE` promotion is never edited in
 * place; the page suspends it first.
 */
@Injectable({ providedIn: 'root' })
export class PromotionsApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly PromotionView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PromotionView[]>(promotionPaths.base(scope)),
    );
    return result.value ?? [];
  }

  async detail(scope: BrandScope, promotionId: string): Promise<PromotionView> {
    const result = await firstValueFrom(
      this.api.get<PromotionView>(promotionPaths.one(scope, promotionId)),
    );
    return result.value;
  }

  async create(scope: BrandScope, body: PromotionBody): Promise<PromotionView> {
    return firstValueFrom(
      this.api.post<PromotionBody, PromotionView>(promotionPaths.base(scope), command(body)),
    );
  }

  async update(
    scope: BrandScope,
    promotionId: string,
    body: PromotionBody,
    expectedVersion: number,
  ): Promise<PromotionView> {
    return firstValueFrom(
      this.api.put<PromotionBody, PromotionView>(
        promotionPaths.one(scope, promotionId),
        command(body),
        { expectedVersion },
      ),
    );
  }

  async validate(
    scope: BrandScope,
    promotionId: string,
    expectedVersion: number,
  ): Promise<ValidationResult> {
    return firstValueFrom(
      this.api.post<null, ValidationResult>(
        promotionPaths.validate(scope, promotionId),
        command(null),
        { expectedVersion },
      ),
    );
  }

  /**
   * Activates a validated promotion, or asks a second person to (a markup, or a
   * percentage or fixed amount over the thresholds): the response says which.
   * `reason` is what the approver reads.
   */
  async activate(
    scope: BrandScope,
    promotionId: string,
    expectedVersion: number,
    reason: string | null,
  ): Promise<ActivationResult> {
    return firstValueFrom(
      this.api.post<{ readonly reason: string | null }, ActivationResult>(
        promotionPaths.activate(scope, promotionId),
        command({ reason }),
        { expectedVersion },
      ),
    );
  }

  async suspend(
    scope: BrandScope,
    promotionId: string,
    expectedVersion: number,
  ): Promise<PromotionView> {
    return firstValueFrom(
      this.api.post<null, PromotionView>(
        promotionPaths.suspend(scope, promotionId),
        command(null),
        {
          expectedVersion,
        },
      ),
    );
  }

  async resume(
    scope: BrandScope,
    promotionId: string,
    expectedVersion: number,
  ): Promise<PromotionView> {
    return firstValueFrom(
      this.api.post<null, PromotionView>(promotionPaths.resume(scope, promotionId), command(null), {
        expectedVersion,
      }),
    );
  }

  async archive(
    scope: BrandScope,
    promotionId: string,
    expectedVersion: number,
  ): Promise<PromotionView> {
    return firstValueFrom(
      this.api.post<null, PromotionView>(
        promotionPaths.archive(scope, promotionId),
        command(null),
        {
          expectedVersion,
        },
      ),
    );
  }

  /**
   * q-rule-list's own gesture: every live promotion of one stacking group, the
   * first with the highest priority. Priority only breaks ties (benefit comes
   * first), so it is the one change an active promotion accepts in place.
   */
  async reorder(
    scope: BrandScope,
    stackingGroup: string,
    orderedPromotionIds: readonly string[],
  ): Promise<readonly PromotionView[]> {
    const result = await firstValueFrom(
      this.api.put<
        { readonly stackingGroup: string; readonly orderedPromotionIds: readonly string[] },
        readonly PromotionView[]
      >(promotionPaths.priority(scope), command({ stackingGroup, orderedPromotionIds })),
    );
    return result ?? [];
  }

  async redemptions(
    scope: BrandScope,
    promotionId: string,
  ): Promise<readonly PromotionRedemption[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PromotionRedemption[]>(promotionPaths.redemptions(scope, promotionId)),
    );
    return result.value ?? [];
  }

  /** The real engine over a synthetic cart: no quote, no redemption, no counter is written. */
  async simulate(scope: BrandScope, request: SimulationRequest): Promise<SimulationResult> {
    return firstValueFrom(
      this.api.post<SimulationRequest, SimulationResult>(
        promotionPaths.simulate(scope),
        command(request),
      ),
    );
  }
}
