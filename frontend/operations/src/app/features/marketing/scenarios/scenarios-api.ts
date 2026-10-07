import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { marketingPaths } from '../../../core/api/marketing-paths';

/** Mirrors `marketing.domain.ScenarioChannel`: six, not four, because two send no message. */
export type ScenarioChannel = 'SMS' | 'EMAIL' | 'PUSH' | 'MESSAGING_APP' | 'IN_APP' | 'CALL_CENTRE';

/** Mirrors `ScenarioCondition.Continuation`: whether to go on to a step at all. */
export type ContinuationCondition = 'ALWAYS' | 'NO_ORDER_SINCE_ENTRY';

/** Mirrors `ScenarioCondition.Stop`: whether the whole scenario is over for the guest. */
export type StopCondition = 'NONE' | 'ORDER_PLACED_SINCE_ENTRY';

/** Mirrors `AttributionModel`. */
export type AttributionModel = 'FIRST_TOUCH' | 'LAST_TOUCH';

/**
 * Mirrors `ScenarioController.StepRequest`. A step names a channel, an offer and a template; it
 * has no field in which to state a discount or a number of points, and this type must never
 * grow one (ADR 0112: marketing never authors a benefit).
 */
export interface ScenarioStepRequest {
  readonly channel: ScenarioChannel;
  readonly offerId: string | null;
  readonly templateKey: string | null;
  readonly waitAfterPreviousSeconds: number;
  readonly continuationCondition: ContinuationCondition;
  readonly stopCondition: StopCondition;
}

/** Mirrors `ScenarioController.CreateScenarioRequest`. */
export interface CreateScenarioRequest {
  readonly name: string;
  readonly audienceId: string;
  readonly consentPurpose: string;
  readonly recipientCap: number;
  readonly costCeilingMinor: number | null;
  readonly currency: string;
  /** Null runs the scenario against the whole audience with no measurement baseline. */
  readonly controlGroupPercent: number | null;
  readonly scheduledAt: string | null;
  readonly steps: readonly ScenarioStepRequest[];
}

/** Mirrors `ScenarioController.ScenarioSummary`. */
export interface ScenarioSummaryView {
  readonly campaignId: string;
  readonly name: string;
  readonly status: string;
  readonly consentPurpose: string;
  readonly controlGroupPercent: number | null;
  readonly supersedesCampaignId: string | null;
  readonly createdAt: string;
}

/** Mirrors `ScenarioController.StepResponse`. */
export interface ScenarioStepView {
  readonly sequence: number;
  readonly channel: ScenarioChannel;
  readonly offerId: string | null;
  readonly templateKey: string;
  readonly waitAfterPreviousSeconds: number;
  readonly continuationCondition: ContinuationCondition;
  readonly stopCondition: StopCondition;
}

/**
 * Mirrors `ScenarioController.ScenarioResponse`.
 *
 * `participants` counts guests by where they are (`IN_PROGRESS`, `CONTROL`, `COMPLETED`, and one
 * key per `STOPPED_BY_*` outcome); `decisions` counts decisions by `SENT` or by the refusal reason
 * they were blocked for. Both are keyed by name, so a key this build does not know is shown as
 * written rather than dropped.
 */
export interface ScenarioView {
  readonly campaign: ScenarioSummaryView;
  readonly steps: readonly ScenarioStepView[];
  readonly participants: Readonly<Record<string, number>>;
  readonly decisions: Readonly<Record<string, number>>;
}

/** Mirrors `ScenarioController.DecisionResponse`. A guest is an account id, never a contact value. */
export interface ScenarioDecisionView {
  readonly decisionId: string;
  readonly customerAccountId: string;
  readonly stepSequence: number;
  /** `SENT` or `BLOCKED`. */
  readonly decision: string;
  /** A `RefusalReason` name, for a `BLOCKED` row. */
  readonly refusalReason: string | null;
  /** The sentence the engine recorded: which rule, which numbers. English, free of contact values. */
  readonly reasonText: string | null;
  readonly resolvedChannel: string | null;
  readonly attemptId: string | null;
  readonly acknowledgedAt: string | null;
  readonly decidedAt: string;
}

/** Mirrors `ScenarioResultsService.Results`. Rates are fractions between 0 and 1. */
export interface ScenarioResultsView {
  readonly model: AttributionModel;
  readonly windowDays: number;
  readonly treatedParticipants: number;
  readonly treatedConverted: number;
  readonly controlParticipants: number;
  readonly controlConverted: number;
  readonly treatedRate: number | null;
  readonly controlRate: number | null;
  /** `treatedRate - controlRate`; null when either rate is, which includes "no control group". */
  readonly lift: number | null;
  readonly hasControlGroup: boolean;
  readonly participantsWithOpenWindow: number;
}

/**
 * Scenario campaigns (ADR 0112, row 6.4): the steps, a new version, what was decided and why,
 * and whether it worked against its withheld control group.
 *
 * The lifecycle is the campaign one: estimate, submit, approve (by somebody who is not the
 * author), launch and halt all go through {@link MarketingApi}, because a scenario is a campaign
 * and a second path would be a second approval to get wrong.
 */
@Injectable({ providedIn: 'root' })
export class ScenariosApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly ScenarioSummaryView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ScenarioSummaryView[]>(marketingPaths.scenarios(scope)),
    );
    return result.value ?? [];
  }

  async get(scope: BrandScope, campaignId: string): Promise<ScenarioView> {
    const result = await firstValueFrom(
      this.api.get<ScenarioView>(marketingPaths.scenario(scope, campaignId)),
    );
    return result.value;
  }

  /** A draft. Nothing is sent until it is estimated, submitted, approved and launched. */
  async create(scope: BrandScope, request: CreateScenarioRequest): Promise<ScenarioView> {
    return firstValueFrom(
      this.api.post<CreateScenarioRequest, ScenarioView>(
        marketingPaths.scenarios(scope),
        command(request),
      ),
    );
  }

  /** Only while the scenario is a DRAFT; afterwards its steps are fixed and a change is {@link revise}. */
  async replaceSteps(
    scope: BrandScope,
    campaignId: string,
    steps: readonly ScenarioStepRequest[],
  ): Promise<ScenarioView> {
    return firstValueFrom(
      this.api.put<{ readonly steps: readonly ScenarioStepRequest[] }, ScenarioView>(
        marketingPaths.scenarioSteps(scope, campaignId),
        command({ steps }),
      ),
    );
  }

  /** A new DRAFT with the same steps, pointing at the version it supersedes; it needs its own approval. */
  async revise(scope: BrandScope, campaignId: string): Promise<ScenarioView> {
    return firstValueFrom(
      this.api.post<null, ScenarioView>(
        marketingPaths.scenarioRevisions(scope, campaignId),
        command(null),
      ),
    );
  }

  /** Newest first. `accountId` narrows it to one guest: the answer to why that guest did not get a step. */
  async decisions(
    scope: BrandScope,
    campaignId: string,
    options: { readonly accountId?: string; readonly limit?: number } = {},
  ): Promise<readonly ScenarioDecisionView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ScenarioDecisionView[]>(
        marketingPaths.scenarioDecisions(scope, campaignId),
        { params: { accountId: options.accountId, limit: options.limit } },
      ),
    );
    return result.value ?? [];
  }

  async results(
    scope: BrandScope,
    campaignId: string,
    model: AttributionModel,
    windowDays?: number,
  ): Promise<ScenarioResultsView> {
    const result = await firstValueFrom(
      this.api.get<ScenarioResultsView>(marketingPaths.scenarioResults(scope, campaignId), {
        params: { model, windowDays },
      }),
    );
    return result.value;
  }
}
