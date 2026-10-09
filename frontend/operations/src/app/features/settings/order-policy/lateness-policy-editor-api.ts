import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { ConfigurationScopeType, EditableScopeType } from '../../../core/api/configuration';
import { command } from '../../../core/api/idempotency';
import { settingsPaths } from '../../../core/api/settings-paths';

/** The three fulfilment modes, in the wire's own field names. */
export type LatenessMode = 'delivery' | 'pickup' | 'dineIn';

export const LATENESS_MODES: readonly LatenessMode[] = ['delivery', 'pickup', 'dineIn'];

/** Mirrors `OrderLatenessPolicyEditorController.ModeResponse`. */
export interface LatenessModeView {
  /** The mode's own at-risk window in seconds; null when it takes {@link LatenessEditorView.atRiskDefault}. */
  readonly atRiskBeforeSeconds: number | null;
  /** What the boards actually use for this mode. */
  readonly effectiveAtRiskBeforeSeconds: number;
  readonly lateAfterSeconds: number;
  /** The mode's own no-promise fallback in seconds; null when it takes {@link LatenessEditorView.noPromiseDefault} (ADR 0150). */
  readonly noPromiseFallbackSeconds: number | null;
  /** What the boards actually use for an order of this mode with no promised time. */
  readonly effectiveNoPromiseFallbackSeconds: number;
}

/** Mirrors `OrderLatenessPolicyEditorController.AtRiskDefaultResponse`. */
export interface LatenessAtRiskDefault {
  readonly seconds: number;
  /** `SCALAR`: `ordering.at_risk_before_minutes` was set somewhere in the chain. `PLATFORM_DEFAULT`: nothing was. */
  readonly source: 'SCALAR' | 'PLATFORM_DEFAULT';
}

/** Mirrors `OrderLatenessPolicyEditorController.NoPromiseDefaultResponse` (ADR 0150). */
export interface LatenessNoPromiseDefault {
  readonly seconds: number;
  /** `SCALAR`: `ordering.late_order_threshold_minutes` was set somewhere in the chain. `PLATFORM_DEFAULT`: nothing was. */
  readonly source: 'SCALAR' | 'PLATFORM_DEFAULT';
}

export interface LatenessLevelView {
  readonly scopeType: ConfigurationScopeType;
  readonly outcome: 'VALUE' | 'NOT_SET';
  /** The policy version in force at exactly this scope; absent when nothing was authored here. */
  readonly version?: number | null;
  /** Who approved that version, by the name this tenant knows them by; absent without a member record. */
  readonly approvedByName?: string | null;
  /** When that version took effect (RFC 3339, UTC). */
  readonly validFrom?: string | null;
}

/** Mirrors `OrderLatenessPolicyEditorController.EditorResponse`. */
export interface LatenessEditorView {
  readonly delivery: LatenessModeView;
  readonly pickup: LatenessModeView;
  readonly dineIn: LatenessModeView;
  readonly atRiskDefault: LatenessAtRiskDefault;
  readonly noPromiseDefault: LatenessNoPromiseDefault;
  readonly isPlatformDefault: boolean;
  /** The scope whose document is in force; null when the platform default applies. */
  readonly winningScope: ConfigurationScopeType | null;
  readonly policyId: string | null;
  readonly policyVersion: number;
  /** The latest version authored at exactly the scope asked about; 0 when it only inherits. */
  readonly currentVersionAtScope: number;
  readonly inspectedLevels: readonly LatenessLevelView[];
}

/** One mode as it is sent: seconds throughout, an unset at-risk window or no-promise fallback as null. */
export interface LatenessModeInput {
  readonly atRiskBeforeSeconds: number | null;
  readonly lateAfterSeconds: number;
  readonly noPromiseFallbackSeconds: number | null;
}

/** Mirrors `OrderLatenessPolicyEditorController.AuthorLatenessPolicyRequest` — no tenantId; the path supplies it. */
export interface AuthorLatenessPolicyInput {
  readonly scopeType: EditableScopeType;
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly delivery: LatenessModeInput;
  readonly pickup: LatenessModeInput;
  readonly dineIn: LatenessModeInput;
  /** The `currentVersionAtScope` the form was opened at; null when that scope had authored nothing. */
  readonly expectedVersion: number | null;
  readonly reason: string;
}

/**
 * The `ordering.lateness` document's editor (rows `X.39`/`10.3b`,
 * `OrderLatenessPolicyEditorController`). The boards do not use this: they read the
 * resolved per-location thresholds through `core/lateness-policy-api.ts`, which is
 * what an edit saved here becomes the next time they poll.
 */
@Injectable({ providedIn: 'root' })
export class LatenessPolicyEditorApi {
  private readonly api = inject(ApiClient);

  async get(
    tenantId: string,
    scopeType: EditableScopeType,
    brandId: string | null,
    locationId: string | null,
  ): Promise<LatenessEditorView> {
    const params: Record<string, string> = { scopeType };
    if (scopeType !== 'TENANT' && brandId) {
      params['brandId'] = brandId;
    }
    if (scopeType === 'LOCATION' && locationId) {
      params['locationId'] = locationId;
    }
    const result = await firstValueFrom(
      this.api.get<LatenessEditorView>(this.path(tenantId), { params }),
    );
    return result.value;
  }

  async publish(tenantId: string, input: AuthorLatenessPolicyInput): Promise<LatenessEditorView> {
    return firstValueFrom(
      this.api.post<AuthorLatenessPolicyInput, LatenessEditorView>(
        this.path(tenantId),
        command(input),
      ),
    );
  }

  private path(tenantId: string): string {
    return settingsPaths.orderLatenessPolicy({ tenantId, brandId: '', locationId: '' });
  }
}
