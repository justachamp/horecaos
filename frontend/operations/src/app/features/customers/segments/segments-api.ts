import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { BrandScope } from '../../../core/api/catalog-paths';
import { marketingPaths } from '../../../core/api/marketing-paths';

/** The closed predicate catalogue (ADR 0044) — `PredicateType.java`. */
export type PredicateType =
  | 'RECENCY_DAYS'
  | 'ORDER_COUNT'
  | 'COMPLETED_ORDER_COUNT'
  | 'NET_SPEND_MINOR'
  | 'AVERAGE_CHECK_MINOR'
  | 'ACQUISITION_CHANNEL'
  | 'REGISTERED_BETWEEN'
  | 'BIRTHDAY_WITHIN_DAYS'
  | 'PREFERRED_LOCALE'
  | 'AUDIENCE_MEMBERSHIP';

export type PredicateOperator = 'AT_LEAST' | 'AT_MOST' | 'BETWEEN' | 'IN' | 'NOT_IN';

export type PredicateValueKind = 'NUMERIC' | 'DATE_RANGE' | 'TEXT_SET' | 'AUDIENCE';

/** `PredicateType.valueKind()` and `.allowedOperators()`, mirrored client-side for the builder form. */
export const PREDICATE_VALUE_KIND: Readonly<Record<PredicateType, PredicateValueKind>> = {
  RECENCY_DAYS: 'NUMERIC',
  ORDER_COUNT: 'NUMERIC',
  COMPLETED_ORDER_COUNT: 'NUMERIC',
  NET_SPEND_MINOR: 'NUMERIC',
  AVERAGE_CHECK_MINOR: 'NUMERIC',
  ACQUISITION_CHANNEL: 'TEXT_SET',
  REGISTERED_BETWEEN: 'DATE_RANGE',
  BIRTHDAY_WITHIN_DAYS: 'NUMERIC',
  PREFERRED_LOCALE: 'TEXT_SET',
  AUDIENCE_MEMBERSHIP: 'AUDIENCE',
};

const RANGE_OPERATORS: readonly PredicateOperator[] = ['AT_LEAST', 'AT_MOST', 'BETWEEN'];
const SET_OPERATORS: readonly PredicateOperator[] = ['IN', 'NOT_IN'];

export function allowedOperators(type: PredicateType): readonly PredicateOperator[] {
  switch (PREDICATE_VALUE_KIND[type]) {
    case 'NUMERIC':
      return RANGE_OPERATORS;
    case 'DATE_RANGE':
      return ['BETWEEN'];
    case 'TEXT_SET':
    case 'AUDIENCE':
      return SET_OPERATORS;
  }
}

/** Mirrors `OperationsMarketingController.AudiencePredicateResponse` / `Request`. */
export interface AudiencePredicate {
  readonly type: PredicateType;
  readonly operator: PredicateOperator;
  readonly numericLow: number | null;
  readonly numericHigh: number | null;
  readonly dateLow: string | null;
  readonly dateHigh: string | null;
  readonly textValues: readonly string[] | null;
  readonly audienceId: string | null;
}

/** Mirrors `OperationsMarketingController.AudienceSummaryResponse`. */
export interface AudienceSummary {
  readonly audienceId: string;
  readonly name: string;
  readonly description: string | null;
  readonly status: string;
  readonly definitionVersion: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly lastReach: number | null;
  readonly lastEvaluatedAt: string | null;
}

/** Mirrors `OperationsMarketingController.AudienceDetailResponse`. */
export interface AudienceDetail {
  readonly audienceId: string;
  readonly name: string;
  readonly description: string | null;
  readonly status: string;
  readonly definitionVersion: number;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly predicates: readonly AudiencePredicate[];
}

export interface DefineAudienceInput {
  readonly name: string;
  readonly description: string | null;
  readonly predicates: readonly AudiencePredicate[];
}

export type MarketingChannel = 'SMS' | 'EMAIL' | 'PUSH' | 'MESSAGING_APP';

/** Mirrors `OperationsMarketingController.SnapshotResponse`. */
export interface SnapshotResult {
  readonly snapshotId: string;
  readonly candidates: number;
  readonly members: number;
  readonly excluded: number;
}

/**
 * Customers 5.3's segment builder over ADR 0044's audience machinery. Every
 * predicate here reads `marketing.customer_metrics` — counts and pseudonymous
 * ids only, never a phone number or a name (`AudienceService`'s own doc: "the
 * result never leaves the platform").
 */
@Injectable({ providedIn: 'root' })
export class SegmentsApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly AudienceSummary[]> {
    const result = await firstValueFrom(
      this.api.get<readonly AudienceSummary[]>(marketingPaths.audiences(scope)),
    );
    return result.value ?? [];
  }

  async detail(scope: BrandScope, audienceId: string): Promise<AudienceDetail> {
    const result = await firstValueFrom(
      this.api.get<AudienceDetail>(marketingPaths.audience(scope, audienceId)),
    );
    return result.value;
  }

  async define(scope: BrandScope, input: DefineAudienceInput): Promise<string> {
    const result = await firstValueFrom(
      this.api.post<DefineAudienceInput, { readonly audienceId: string }>(
        marketingPaths.audiences(scope),
        command(input),
      ),
    );
    return result.audienceId;
  }

  async redefine(
    scope: BrandScope,
    audienceId: string,
    predicates: readonly AudiencePredicate[],
  ): Promise<number> {
    const result = await firstValueFrom(
      this.api.put<{ readonly predicates: readonly AudiencePredicate[] }, { readonly definitionVersion: number }>(
        marketingPaths.audiencePredicates(scope, audienceId),
        command({ predicates }),
      ),
    );
    return result.definitionVersion;
  }

  async buildSnapshot(
    scope: BrandScope,
    audienceId: string,
    channel: MarketingChannel,
    consentPurpose: string,
  ): Promise<SnapshotResult> {
    return firstValueFrom(
      this.api.post<{ readonly channel: string; readonly consentPurpose: string }, SnapshotResult>(
        marketingPaths.audienceSnapshots(scope, audienceId),
        command({ channel, consentPurpose }),
      ),
    );
  }
}
