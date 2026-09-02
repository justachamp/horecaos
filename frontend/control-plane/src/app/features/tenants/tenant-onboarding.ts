import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { OnboardingRunView, TenantsApi } from './tenants-api';

/**
 * IA 2.5 Onboarding -- the resumable run (ADR 0008): steps, blockers,
 * resume, and activate.
 *
 * The real step catalogue (`OnboardingStep`, 12 steps from
 * `KEYCLOAK_ORGANIZATION_RECONCILE` through `TENANT_ACTIVATE`) is shown as
 * the server names it, not the fiscal-code-backfill/SMS-sender-alias guess
 * this row's own IA prose makes -- `FRONTEND_DOMAIN_VALIDATE` is the closest
 * thing to domain verification, `POS_BINDINGS_VALIDATE` to a provider
 * install checklist, `CATALOG_READINESS_VALIDATE` to the first catalog
 * import.
 *
 * `activate` is this wave's maker-checker exit criterion's last step: its
 * response names `AWAITING_APPROVAL` with a pending request id when a
 * platform-scope policy governs `tenant.activate` (ADR 0027/0050) -- this
 * screen shows that state plainly rather than treating it as a failure, and
 * a second admin decides it from IA 7.1's approvals panel.
 */
@Component({
  selector: 'app-tenant-onboarding',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tenant-onboarding.html',
  styleUrl: './tenant-onboarding.css',
})
export class TenantOnboarding {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId')!;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly run = signal<OnboardingRunView | null>(null);
  protected readonly runId = signal<string | null>(null);

  protected readonly starting = signal(false);
  protected readonly ownerEmail = signal('');
  protected readonly actionError = signal<string | null>(null);

  protected readonly resumeReason = signal('');
  protected readonly resuming = signal(false);
  protected readonly resumeMessage = signal<string | null>(null);

  protected readonly activateReason = signal('');
  protected readonly activating = signal(false);
  protected readonly activationOutcomeKey = signal<string | null>(null);
  protected readonly pendingApprovalId = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const run = await this.tenantsApi.currentOnboardingRun(this.tenantId);
      this.run.set(run);
      this.runId.set(run?.run.id ?? null);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected async startRun(event: Event): Promise<void> {
    event.preventDefault();
    this.starting.set(true);
    this.actionError.set(null);
    try {
      const { runId } = await this.tenantsApi.startOnboarding(
        this.tenantId,
        this.ownerEmail().trim() || undefined,
      );
      this.runId.set(runId);
      await this.reloadRun();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.starting.set(false);
    }
  }

  private async reloadRun(): Promise<void> {
    const run = await this.tenantsApi.currentOnboardingRun(this.tenantId);
    this.run.set(run);
  }

  protected async resume(event: Event): Promise<void> {
    event.preventDefault();
    const runId = this.runId();
    if (runId === null || this.resumeReason().trim().length === 0) {
      return;
    }
    this.resuming.set(true);
    this.actionError.set(null);
    this.resumeMessage.set(null);
    try {
      const { reopenedSteps } = await this.tenantsApi.resumeOnboarding(
        this.tenantId,
        runId,
        this.resumeReason().trim(),
      );
      this.resumeMessage.set(
        this.i18n.t('onboarding.resume.result', { count: reopenedSteps }),
      );
      this.resumeReason.set('');
      await this.reloadRun();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.resuming.set(false);
    }
  }

  protected async activate(event: Event): Promise<void> {
    event.preventDefault();
    const runId = this.runId();
    if (runId === null || this.activateReason().trim().length === 0) {
      return;
    }
    this.activating.set(true);
    this.actionError.set(null);
    this.activationOutcomeKey.set(null);
    this.pendingApprovalId.set(null);
    try {
      const outcome = await this.tenantsApi.activateOnboarding(
        this.tenantId,
        runId,
        this.activateReason().trim(),
      );
      this.activationOutcomeKey.set(outcome.outcome);
      this.pendingApprovalId.set(outcome.approvalRequestId);
      this.activateReason.set('');
      await this.reloadRun();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.activating.set(false);
    }
  }
}
