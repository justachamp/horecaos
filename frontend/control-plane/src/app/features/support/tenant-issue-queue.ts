import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { BlockedDocumentResponse, FiscalApi } from '../compliance/fiscal-api';
import { FailureSummary, IntegrationOpsApi } from '../integration-ops/integration-ops-api';
import { PosExportCandidate, PosExportDecision, PosExportView, PosExportsApi } from './pos-exports-api';

/**
 * IA 10.2 Tenant issue queue -- one tenant's open problems, with the evidence
 * and, where there is one, the decision that closes each.
 *
 * Three kinds today: events the platform could not publish, fiscal documents
 * that could not be issued, and orders sent to the tenant's POS whose outcome
 * nobody could establish. The last are settled here: ask the POS what it
 * received, then decide whether the order reached the till, never did (one
 * more send is allowed), or is being handled another way.
 *
 * Expiring credentials are not listed: nothing defines when a credential
 * counts as expired yet.
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
  protected readonly posExports = signal<readonly PosExportView[]>([]);

  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly posExportsApi = inject(PosExportsApi);
  protected readonly deciding = signal<string | null>(null);
  protected readonly candidates = signal<readonly PosExportCandidate[]>([]);
  protected readonly decisions: readonly PosExportDecision[] = ['LANDED', 'ABSENT', 'ABANDON'];
  protected readonly decision = signal<PosExportDecision>('LANDED');
  protected readonly landedAs = signal('');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

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
      const [outbox, blocked, exports] = await Promise.all([
        this.integrationOpsApi.outboxFailures('DEAD_LETTER', 100, tenantId),
        this.fiscalApi.blocked(tenantId),
        this.posExportsApi.awaiting(tenantId),
      ]);
      this.deadLetters.set(outbox.items);
      this.blockedDocuments.set(blocked.documents);
      this.posExports.set(exports.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected get issueCount(): number {
    return this.deadLetters().length + this.blockedDocuments().length + this.posExports().length;
  }

  // ------------------------------------------------------------ POS exports

  protected stateKey(state: string): 'tenantIssueQueue.pos.state.UNCERTAIN' | 'tenantIssueQueue.pos.state.AWAITING_OPERATOR' {
    return state === 'UNCERTAIN' ? 'tenantIssueQueue.pos.state.UNCERTAIN' : 'tenantIssueQueue.pos.state.AWAITING_OPERATOR';
  }

  protected decisionKey(decision: PosExportDecision): MessageKey {
    return `tenantIssueQueue.pos.decision.${decision}` as MessageKey;
  }

  /** Opens the decision for an export a person has to settle, with what the POS read found. */
  protected async openDecision(row: PosExportView): Promise<void> {
    if (this.deciding() === row.exportId) {
      this.deciding.set(null);
      return;
    }
    this.deciding.set(row.exportId);
    this.candidates.set([]);
    this.reason.set('');
    this.actionError.set(null);
    try {
      const found = (await this.posExportsApi.candidates(this.tenantId(), row.exportId)).items;
      this.candidates.set(found);
      const ours = found.find((candidate) => candidate.correlationEchoed);
      this.decision.set(ours || found.length > 0 ? 'LANDED' : 'ABSENT');
      this.landedAs.set(ours?.externalOrderId ?? '');
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  /** Asks the POS what it received. It never re-sends the order. */
  protected async askPos(row: PosExportView): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      const outcome = await this.posExportsApi.discover(this.tenantId(), row.exportId);
      this.actionMessage.set(
        outcome.status === 'SUCCESS'
          ? this.i18n.t('tenantIssueQueue.pos.asked')
          : this.i18n.t('tenantIssueQueue.pos.askFailed', { detail: outcome.detail || outcome.errorCode }),
      );
      await this.search();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }

  protected canDecide(): boolean {
    return (
      !this.busy() &&
      this.reason().trim().length > 0 &&
      (this.decision() !== 'LANDED' || this.landedAs().trim().length > 0)
    );
  }

  protected async decide(row: PosExportView): Promise<void> {
    if (!this.canDecide()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    const decision = this.decision();
    try {
      const result = await this.posExportsApi.resolve(
        this.tenantId(),
        row.exportId,
        decision,
        this.reason().trim(),
        decision === 'LANDED' ? this.landedAs().trim() : undefined,
      );
      this.deciding.set(null);
      if (result.changed) {
        this.posExports.update((rows) => rows.filter((candidate) => candidate.exportId !== row.exportId));
        this.actionMessage.set(this.i18n.t('tenantIssueQueue.pos.decided'));
      } else {
        this.actionMessage.set(this.i18n.t('tenantIssueQueue.pos.noChange'));
        await this.search();
      }
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
