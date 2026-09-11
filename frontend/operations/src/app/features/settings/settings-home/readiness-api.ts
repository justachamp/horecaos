import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.tenancy.web.OnboardingController.RunSummary — only the field this reads. */
export interface OnboardingRunSummary {
  readonly id: string;
}

/** Mirrors uz.horecaos.platform.tenancy.web.OnboardingController.RunView — only the field this reads. */
export interface OnboardingRunView {
  readonly run: OnboardingRunSummary;
}

/**
 * One `validate` finding, expanded to one row per offending item (wave P31's
 * own reshape of `OnboardingService.ValidationResult` — `locationId` is the
 * field that reshape added).
 */
export interface ValidationResult {
  readonly stepKey: string;
  readonly passed: boolean;
  readonly errorCode: string | null;
  readonly detail: string | null;
  readonly locationId: string | null;
}

/** Mirrors uz.horecaos.platform.tenancy.application.onboarding.OnboardingService.ValidationOutcome. */
export interface ValidationOutcome {
  readonly allPassed: boolean;
  readonly checks: readonly ValidationResult[];
}

/**
 * settings.md §10.0's readiness panel, over `OnboardingController.validate`
 * (wave P31, gap map row `10.0`) — a cross-surface, `TENANT_READ`-gated
 * control-plane call, the same shape `settingsPaths`' own doc comment
 * explains for the order-acceptance-policy and legal-entity reads: onboarding
 * has never had an operations-native mirror to prefer.
 */
@Injectable({ providedIn: 'root' })
export class ReadinessApi {
  private readonly api = inject(ApiClient);

  /**
   * Dry-runs every readiness check now. Rejects with the underlying
   * `ApiError` on a 404 (no onboarding run exists for this tenant — a
   * hand-seeded fixture, not a real ADR 0008 onboarding) or a 403 (denied);
   * the settings home page turns each into its own state.
   */
  async validate(tenantId: string): Promise<ValidationOutcome> {
    const run = await firstValueFrom(
      this.api.get<OnboardingRunView>(settingsPaths.onboardingCurrentRun(tenantId)),
    );
    return firstValueFrom(
      this.api.post<Record<string, never>, ValidationOutcome>(
        settingsPaths.onboardingValidate(tenantId, run.value.run.id),
        command({}),
      ),
    );
  }
}
