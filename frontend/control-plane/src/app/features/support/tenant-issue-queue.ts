import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { BlockedDocumentResponse, FiscalApi } from '../compliance/fiscal-api';
import { FailureSummary, IntegrationOpsApi } from '../integration-ops/integration-ops-api';

/**
 * IA 10.2 Tenant issue queue -- open problems per tenant with linked
 * evidence (DLQ entries, failed fiscalizations, expired credentials).
 *
 * Composes two already-real, already-tenant-scoped reads -- dead-lettered
 * outbox events (`FailureOperationsController`, tenant-filterable) and
 * blocked fiscal documents (`FiscalDocumentController`) -- rather than
 * inventing a new backend read-model: both are exactly the "linked evidence"
 * this row names, for one tenant at a time.
 *
 * Named gap: "expired credentials" is not shown. `ProviderInstallationController`
 * returns `lastSecretRotatedAt` per installation, but nothing in the schema
 * declares an expiry threshold or computes an "expired" state -- inventing
 * one here (30 days? 90?) would show a judgement nobody made.
 */
@Component({
  selector: 'app-tenant-issue-queue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker, RouterLink],
  templateUrl: './tenant-issue-queue.html',
  styleUrl: './tenant-issue-queue.css',
})
export class TenantIssueQueue {
  protected readonly i18n = inject(I18nService);
  private readonly integrationOpsApi = inject(IntegrationOpsApi);
  private readonly fiscalApi = inject(FiscalApi);

  private readonly directory = inject(TenantDirectory);
  private readonly tenantRoute = inject(ActivatedRoute);
  /** From a `?tenantId=` link first, else the tenant chosen last on any screen. */
  protected readonly tenantId = signal(
    this.tenantRoute.snapshot.queryParamMap.get('tenantId') ?? this.directory.selected(),
  );
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly searched = signal(false);

  protected readonly deadLetters = signal<readonly FailureSummary[]>([]);
  protected readonly blockedDocuments = signal<readonly BlockedDocumentResponse[]>([]);

  constructor() {
    if (this.tenantId().length > 0) {
      void this.search();
    }
  }

  /** A tenant chosen in the picker: shown at once, nothing to press. */
  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    if (tenantId.length > 0) {
      void this.search();
    }
  }

  protected async search(event?: Event): Promise<void> {
    event?.preventDefault();
    const tenantId = this.tenantId().trim();
    if (tenantId.length === 0) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.searched.set(true);
    try {
      const [outbox, blocked] = await Promise.all([
        this.integrationOpsApi.outboxFailures('DEAD_LETTER', 100, tenantId),
        this.fiscalApi.blocked(tenantId),
      ]);
      this.deadLetters.set(outbox.items);
      this.blockedDocuments.set(blocked.documents);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected get issueCount(): number {
    return this.deadLetters().length + this.blockedDocuments().length;
  }
}
