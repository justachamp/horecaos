import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { marketingPaths } from '../../../core/api/marketing-paths';

/** The trigger kinds this build offers. See `AutomationTriggerType`'s own doc for why only three. */
export type AutomationTriggerKind = 'BIRTHDAY' | 'INACTIVITY' | 'CART_ABANDONMENT';

/** The one `trigger_config` key each trigger kind reads — `AutomationTriggerType.configKey()`. */
export const AUTOMATION_TRIGGER_CONFIG_KEY: Readonly<Record<AutomationTriggerKind, string>> = {
  BIRTHDAY: 'birthdayWindowDays',
  INACTIVITY: 'inactivityDays',
  CART_ABANDONMENT: 'abandonmentDelayHours',
};

/** Mirrors `AutomationRuleController.AutomationRuleResponse`. */
export interface AutomationRuleView {
  readonly id: string;
  readonly name: string;
  readonly triggerType: string;
  readonly channel: string;
  readonly consentPurpose: string;
  readonly templateKey: string;
  readonly triggerConfig: Readonly<Record<string, number>>;
  readonly cooldownDays: number;
  readonly priority: number;
  readonly active: boolean;
  readonly activatedBy: string | null;
  readonly activatedAt: string | null;
  readonly version: number;
}

/** Mirrors `AutomationRuleController.AutomationRuleRequest`. */
export interface AutomationRuleRequest {
  readonly name: string;
  readonly triggerType: AutomationTriggerKind;
  readonly channel: string;
  readonly consentPurpose: string;
  readonly templateKey: string;
  readonly triggerConfig: Readonly<Record<string, number>>;
  readonly cooldownDays: number;
}

/** Mirrors `AutomationRuleController.AutomationRunResponse`. */
export interface AutomationRunView {
  readonly id: string;
  readonly customerAccountId: string;
  /** `FIRED` | `REFUSED` | `CANCELLED`. */
  readonly status: string;
  readonly refusalReason: string | null;
  readonly cancelledReason: string | null;
  readonly firedAt: string;
}

/**
 * Gap-map row 6.5 (ADR 0044 Triggers) — unattended BIRTHDAY, INACTIVITY and
 * CART_ABANDONMENT rules. Authoring (`campaign.author`) and arming
 * (`campaign.approve`) are two distinct calls on the server; this client
 * mirrors that split rather than folding it into one method, so a caller
 * cannot accidentally arm a rule it only meant to save.
 */
@Injectable({ providedIn: 'root' })
export class AutomationsApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly AutomationRuleView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly AutomationRuleView[]>(marketingPaths.automations(scope)),
    );
    return result.value ?? [];
  }

  async create(scope: BrandScope, request: AutomationRuleRequest): Promise<AutomationRuleView> {
    return firstValueFrom(
      this.api.post<AutomationRuleRequest, AutomationRuleView>(
        marketingPaths.automations(scope),
        command(request),
      ),
    );
  }

  async update(
    scope: BrandScope,
    ruleId: string,
    request: AutomationRuleRequest,
    expectedVersion: number,
  ): Promise<AutomationRuleView> {
    return firstValueFrom(
      this.api.put<AutomationRuleRequest, AutomationRuleView>(
        marketingPaths.automation(scope, ruleId),
        command(request),
        { expectedVersion },
      ),
    );
  }

  /** The one human act that arms a rule (`campaign.approve`). */
  async activate(
    scope: BrandScope,
    ruleId: string,
    expectedVersion: number,
  ): Promise<AutomationRuleView> {
    return firstValueFrom(
      this.api.post<null, AutomationRuleView>(
        marketingPaths.automationActivations(scope, ruleId),
        command(null),
        { expectedVersion },
      ),
    );
  }

  async deactivate(
    scope: BrandScope,
    ruleId: string,
    expectedVersion: number,
  ): Promise<AutomationRuleView> {
    return firstValueFrom(
      this.api.post<null, AutomationRuleView>(
        marketingPaths.automationDeactivations(scope, ruleId),
        command(null),
        { expectedVersion },
      ),
    );
  }

  /** q-rule-list's own whole-set contract: every id the caller means to keep, in the new order. */
  async reorder(
    scope: BrandScope,
    orderedRuleIds: readonly string[],
  ): Promise<readonly AutomationRuleView[]> {
    const result = await firstValueFrom(
      this.api.put<{ readonly orderedRuleIds: readonly string[] }, readonly AutomationRuleView[]>(
        marketingPaths.automationReorder(scope),
        command({ orderedRuleIds }),
      ),
    );
    return result ?? [];
  }

  async runs(scope: BrandScope, ruleId: string): Promise<readonly AutomationRunView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly AutomationRunView[]>(marketingPaths.automationRuns(scope, ruleId)),
    );
    return result.value ?? [];
  }
}
