import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { dispatchRulesPaths } from '../../core/api/delivery-paths';
import { command } from '../../core/api/idempotency';

/**
 * The dispatch rules, the sourcing timings and the unpaid-order window (IA 3.8, ADR 0142).
 *
 * The types mirror `OperationsDispatchRulesController` and `OperationsPaymentWindowController`
 * field for field. They are hand-written here, as every feature API in this console is, and the
 * generated `horecaos-api-v1.operations.ts` is what `make openapi-client-check` holds the server
 * side of them to.
 */

/** Which lanes a plan runs and in what order (ADR 0142 Decision 9). */
export type SourcingMode =
  'FLEET_FIRST' | 'FLEET_ONLY' | 'PARTNER_ONLY' | 'PARTNER_FIRST' | 'MANUAL';

export const SOURCING_MODES: readonly SourcingMode[] = [
  'FLEET_FIRST',
  'PARTNER_FIRST',
  'FLEET_ONLY',
  'PARTNER_ONLY',
  'MANUAL',
];

/** `LADDER` asks no quote and tries the partners in the given order; `CHEAPEST` quotes them all and books the winner. */
export type PartnerSelection = 'LADDER' | 'CHEAPEST';

export type StartBasis = 'LEAD' | 'CONFIRMATION' | 'READY';

export type Weekday = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN';

export const WEEKDAYS: readonly Weekday[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

/** ADR 0036's closed set of sales channel types. */
export const SOURCE_TYPES: readonly string[] = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
  'AGGREGATOR',
  'POS',
];

export type ScopeLevel = 'TENANT' | 'BRAND' | 'LOCATION';

export interface IntRange {
  readonly min: number | null;
  readonly max: number | null;
}

export interface TimeWindow {
  readonly days: readonly Weekday[];
  /** `HH:mm`, inclusive. */
  readonly from: string;
  /** `HH:mm` or `24:00`, exclusive. A `from` later than `to` wraps midnight. */
  readonly to: string;
}

export interface DispatchConditions {
  readonly sources: readonly string[];
  readonly channelIds: readonly string[];
  readonly zoneIds: readonly string[];
  readonly locationIds: readonly string[];
  readonly prepMinutes: IntRange | null;
  readonly distanceMeters: IntRange | null;
  readonly localTime: TimeWindow | null;
  readonly prepaid: boolean | null;
}

export interface PartnerSet {
  /** ADR 0026 installation ids. Empty means every bound delivery installation, in binding order. */
  readonly order: readonly string[];
  readonly exclude: readonly string[];
  readonly selection: PartnerSelection;
}

export interface DispatchStart {
  readonly basis: StartBasis;
  readonly offsetSeconds: number;
}

export interface Grouping {
  readonly mergeRadiusMeters: number;
  readonly maxOrdersPerRun: number;
  readonly maxWaitSeconds: number;
}

export interface DispatchAction {
  readonly mode: SourcingMode;
  readonly partners: PartnerSet;
  readonly dispatchAt: DispatchStart;
  readonly grouping: Grouping | null;
  readonly holdBeforeConfirm?: boolean | null;
}

export interface DispatchRule {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly when: DispatchConditions;
  readonly then: DispatchAction;
}

export interface DispatchRulesDocument {
  readonly rules: readonly DispatchRule[];
  readonly default: DispatchAction;
}

export interface LadderLevel {
  readonly scopeType: ScopeLevel;
  readonly authored: boolean;
}

/** Mirrors `OperationsDispatchRulesController.RulesResponse`. */
export interface DispatchRulesView extends DispatchRulesDocument {
  readonly schema: number;
  readonly isBuiltIn: boolean;
  readonly winningScope: ScopeLevel | null;
  readonly policyId: string | null;
  readonly policyVersion: number;
  /** The version authored at exactly this scope (0 when it inherits): the `If-Match` of the next save. */
  readonly versionAtScope: number;
  readonly levels: readonly LadderLevel[];
  readonly groupingAllowed: boolean;
}

export interface InstallationOption {
  readonly id: string;
  readonly providerType: string;
  readonly displayName: string;
  readonly status: string;
}

export interface ZoneOption {
  readonly id: string;
  readonly brandId: string;
  readonly code: string;
  readonly nameEn: string;
  readonly nameRu: string;
  readonly nameUz: string;
  readonly status: string;
}

export interface ChannelOption {
  readonly id: string;
  readonly code: string;
  readonly systemType: string;
  readonly displayName: string;
  readonly status: string;
}

export interface LocationOption {
  readonly id: string;
  readonly brandId: string;
  readonly displayName: string;
}

export interface RecentPlan {
  readonly planId: string;
  readonly locationId: string;
  readonly orderReference: string;
  readonly status: string;
  readonly sourcingMode: string;
  readonly ruleId: string | null;
  readonly createdAt: string;
}

export interface DispatchOptions {
  readonly installations: readonly InstallationOption[];
  readonly zones: readonly ZoneOption[];
  readonly channels: readonly ChannelOption[];
  readonly locations: readonly LocationOption[];
  readonly recentPlans: readonly RecentPlan[];
  readonly groupingAllowed: boolean;
}

export interface RuleUsage {
  /** The rule's id, or `DEFAULT` for plans the default (built-in or the document's own) decided. */
  readonly ruleId: string;
  readonly plans: number;
}

export interface DispatchUsage {
  readonly days: number;
  readonly since: string;
  readonly totalPlans: number;
  readonly perRule: readonly RuleUsage[];
}

export interface SimulationScenario {
  readonly sourceSystemType?: string;
  readonly channelId?: string;
  readonly zoneId?: string;
  readonly preparationMinutes: number;
  readonly distanceMeters: number;
  /** An instant (ISO-8601). */
  readonly confirmedAt: string;
  readonly prepaid?: boolean;
}

export interface SimulationRequest {
  readonly brandId: string;
  readonly locationId: string;
  /** The scope the draft is being written for, deciding what it may name. */
  readonly scopeType?: ScopeLevel;
  readonly draft?: DispatchRulesDocument;
  readonly scenario?: SimulationScenario;
  readonly planId?: string;
}

export type TraceState = 'MATCHED' | 'NOT_MATCHED' | 'DISABLED' | 'NOT_EVALUATED';

export interface SimulationTrace {
  readonly ruleId: string;
  readonly name: string;
  readonly state: TraceState;
  /** SOURCE, CHANNEL, ZONE, BRANCH, PREPARATION, DISTANCE, LOCAL_TIME or PREPAID. */
  readonly failedCondition: string | null;
}

export interface SimulationLadderStep {
  readonly position: number;
  readonly installationId: string | null;
  readonly providerType: string;
  readonly displayName: string | null;
}

export interface SimulationSkip {
  readonly installationId: string;
  readonly reason: string;
}

export interface SimulationResult {
  readonly documentSource: 'DRAFT' | 'PUBLISHED' | 'BUILT_IN';
  readonly policyId: string | null;
  readonly policyVersion: number;
  readonly facts: {
    readonly sourceSystemType: string | null;
    readonly channelId: string | null;
    readonly zoneId: string | null;
    readonly preparationMinutes: number;
    readonly distanceMeters: number;
    readonly confirmedAt: string;
    readonly branchTimezone: string;
    readonly prepaid: boolean;
  };
  readonly decision: {
    readonly ruleId: string;
    readonly mode: SourcingMode;
    readonly partners: PartnerSet;
    readonly dispatchAt: DispatchStart;
    readonly grouping: Grouping | null;
    readonly skips: readonly SimulationSkip[];
  };
  readonly trace: readonly SimulationTrace[];
  readonly lanes: readonly ('FLEET' | 'PARTNERS')[];
  readonly ladder: readonly SimulationLadderStep[];
  readonly skips: readonly SimulationSkip[];
  readonly pickup: {
    readonly confirmedAt: string;
    readonly sourceAt: string;
    readonly pickupWindowStart: string;
    readonly pickupWindowEnd: string;
    readonly latestAssignmentAt: string;
    readonly calculationVersion: number;
  };
  readonly notes: readonly string[];
  readonly violations: readonly string[];
  /** Always false: a simulation calls nobody. */
  readonly providerCalled: boolean;
}

/** Mirrors `OperationsDispatchRulesController.TimingResponse` / `TimingWriteRequest`. */
export interface SourcingTimings {
  readonly preparationLeadSeconds: number;
  readonly partnerLeadSeconds: number;
  readonly safetyBufferSeconds: number;
  readonly pickupToleranceSeconds: number;
  readonly offerRounds: number;
  readonly maxOfferSeconds: number;
  readonly latestAssignmentSlackSeconds: number;
}

export interface SourcingTimingsView extends SourcingTimings {
  readonly isDefaults: boolean;
  readonly winningScope: ScopeLevel | null;
  readonly policyId: string | null;
  readonly policyVersion: number;
  readonly versionAtScope: number;
  readonly levels: readonly LadderLevel[];
}

export type PaymentWindowAction = 'FLAG_ONLY' | 'CANCEL';

/** Mirrors `OperationsPaymentWindowController.WindowResponse`. */
export interface PaymentWindowView {
  readonly windowMinutes: number;
  readonly action: PaymentWindowAction;
  readonly isDefault: boolean;
  readonly winningScope: ScopeLevel | null;
  readonly policyId: string | null;
  readonly policyVersion: number;
  readonly versionAtScope: number;
  readonly levels: readonly LadderLevel[];
}

/** The scope a call is made at: the tenant, optionally narrowed to a brand and a branch. */
export interface DispatchScope {
  readonly tenantId: string;
  readonly brandId?: string;
  readonly locationId?: string;
}

function scopeParams(scope: DispatchScope): Record<string, string> {
  return {
    ...(scope.brandId ? { brandId: scope.brandId } : {}),
    ...(scope.locationId ? { locationId: scope.locationId } : {}),
  };
}

/** What `GET` returned and the version a save at that scope is checked against. */
export interface Loaded<T> {
  readonly value: T;
  readonly version: number;
}

@Injectable({ providedIn: 'root' })
export class DispatchRulesApi {
  private readonly api = inject(ApiClient);

  async rules(scope: DispatchScope): Promise<Loaded<DispatchRulesView>> {
    const result = await firstValueFrom(
      this.api.get<DispatchRulesView>(dispatchRulesPaths.rules(scope.tenantId), {
        params: scopeParams(scope),
      }),
    );
    return { value: result.value, version: result.version ?? result.value.versionAtScope };
  }

  /**
   * Publishes the next whole document at exactly this scope. `expectedVersion` is the
   * `versionAtScope` the last read returned; the server refuses a stale one with `STALE_VERSION`
   * rather than publishing over a version the operator never saw.
   */
  publishRules(
    scope: DispatchScope,
    document: DispatchRulesDocument,
    reason: string,
    expectedVersion: number,
  ): Promise<DispatchRulesView> {
    return firstValueFrom(
      this.api.put<DispatchRulesDocument & { reason: string }, DispatchRulesView>(
        dispatchRulesPaths.rules(scope.tenantId),
        command({ ...document, reason }),
        { expectedVersion, params: scopeParams(scope) },
      ),
    );
  }

  async options(scope: DispatchScope): Promise<DispatchOptions> {
    const result = await firstValueFrom(
      this.api.get<DispatchOptions>(dispatchRulesPaths.options(scope.tenantId), {
        params: scopeParams(scope),
      }),
    );
    return result.value;
  }

  async usage(scope: DispatchScope, days = 30): Promise<DispatchUsage> {
    const result = await firstValueFrom(
      this.api.get<DispatchUsage>(dispatchRulesPaths.usage(scope.tenantId), {
        params: { ...scopeParams(scope), days },
      }),
    );
    return result.value;
  }

  simulate(tenantId: string, request: SimulationRequest): Promise<SimulationResult> {
    return firstValueFrom(
      this.api.post<SimulationRequest, SimulationResult>(
        dispatchRulesPaths.simulations(tenantId),
        command(request),
      ),
    );
  }

  async timings(scope: DispatchScope): Promise<Loaded<SourcingTimingsView>> {
    const result = await firstValueFrom(
      this.api.get<SourcingTimingsView>(dispatchRulesPaths.timings(scope.tenantId), {
        params: scopeParams(scope),
      }),
    );
    return { value: result.value, version: result.version ?? result.value.versionAtScope };
  }

  publishTimings(
    scope: DispatchScope,
    timings: SourcingTimings,
    reason: string,
    expectedVersion: number,
  ): Promise<SourcingTimingsView> {
    return firstValueFrom(
      this.api.put<SourcingTimings & { reason: string }, SourcingTimingsView>(
        dispatchRulesPaths.timings(scope.tenantId),
        command({ ...timings, reason }),
        { expectedVersion, params: scopeParams(scope) },
      ),
    );
  }

  async paymentWindow(scope: DispatchScope): Promise<Loaded<PaymentWindowView>> {
    const result = await firstValueFrom(
      this.api.get<PaymentWindowView>(dispatchRulesPaths.paymentWindow(scope.tenantId), {
        params: scopeParams(scope),
      }),
    );
    return { value: result.value, version: result.version ?? result.value.versionAtScope };
  }

  publishPaymentWindow(
    scope: DispatchScope,
    input: { windowMinutes: number; action: PaymentWindowAction },
    reason: string,
    expectedVersion: number,
  ): Promise<PaymentWindowView> {
    return firstValueFrom(
      this.api.put<
        { windowMinutes: number; action: PaymentWindowAction; reason: string },
        PaymentWindowView
      >(dispatchRulesPaths.paymentWindow(scope.tenantId), command({ ...input, reason }), {
        expectedVersion,
        params: scopeParams(scope),
      }),
    );
  }
}
