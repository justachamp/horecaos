import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { approvalActionLabelKey } from './approval-action-labels';
import { ApprovalDecision, ApprovalsApi, PendingApproval } from './approvals-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

interface ConfirmTarget {
  readonly request: PendingApproval;
  readonly decision: ApprovalDecision;
}

const MAXIMUM_REASON_LENGTH = 1000;

/**
 * Staff 9.4 Approvals — frontend-information-architecture.md §9.4: "the
 * approval queue for discretionary discounts, penalties levied against a
 * worker, PII exports, refunds above a threshold."
 *
 * **Built on the existing maker-checker model, not a second one.** Every row
 * here is a `audit.approval_requests` request some other module already
 * raised through `ApprovalService.requireApproval` (ADR 0027) — this screen
 * adds no new kind of approval, only the one place a manager sees everything
 * waiting on *them* and signs it, via `ApprovalRequestController`'s
 * operations-surface mirror (`approvals-api.ts`'s own doc).
 *
 * **Scoped down, honestly, from the IA's own description.** "PII exports"
 * names a capability (`CUSTOMER_PII_REVEAL`) with its own audited reveal
 * calls (Settings 10.11's own screen surfaces those), but no `requireApproval`
 * call site in the platform raises a PII-export approval request today —
 * `customers.web.CustomerController.export` records the reveal and does not
 * gate it behind a second signature. This worklist can only ever show what
 * `audit.approval_requests` actually holds; it does not invent a row for a
 * producer that does not exist.
 *
 * **The maker's own reason never appears here** (ADR 0029: unclassified
 * prose about a named customer) — only the frozen threshold sentence, who
 * asked, and when. A checker who needs the story opens the action's own
 * console (Finance's remedy queue, Couriers' adjustment history, …); this
 * screen exists to say *what* is waiting, not to duplicate *why* a second,
 * uncontrolled time.
 */
@Component({
  selector: 'q-approvals-page',
  imports: [TPipe],
  templateUrl: './approvals-page.html',
  styleUrl: './approvals-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApprovalsPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(ApprovalsApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly pending = signal<readonly PendingApproval[]>([]);

  protected readonly confirmTarget = signal<ConfirmTarget | null>(null);
  protected readonly reason = signal('');
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly reasonTouched = signal(false);

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      this.pending.set(await this.api.pending(tenantId));
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  protected actionLabel(request: PendingApproval): string {
    const key = approvalActionLabelKey(request.actionCode);
    return key ? this.i18n.t(key) : request.actionCode;
  }

  protected scopeLabel(request: PendingApproval): string {
    if (request.scopeType === 'TENANT') {
      return this.i18n.t('staff.approvals.scope.tenant');
    }
    return `${request.scopeType} · ${request.scopeId ?? '—'}`;
  }

  protected openApprove(request: PendingApproval): void {
    this.openConfirm(request, 'APPROVE');
  }

  protected openDecline(request: PendingApproval): void {
    this.openConfirm(request, 'DECLINE');
  }

  private openConfirm(request: PendingApproval, decision: ApprovalDecision): void {
    this.confirmTarget.set({ request, decision });
    this.reason.set('');
    this.reasonTouched.set(false);
    this.submitError.set(null);
  }

  protected cancelConfirm(): void {
    if (this.submitting()) {
      return;
    }
    this.confirmTarget.set(null);
  }

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  protected readonly reasonInvalid = computed(() => {
    const trimmed = this.reason().trim();
    return this.reasonTouched() && (trimmed === '' || trimmed.length > MAXIMUM_REASON_LENGTH);
  });

  protected async submitDecision(): Promise<void> {
    const target = this.confirmTarget();
    const tenantId = this.tenant.tenantId();
    this.reasonTouched.set(true);
    const trimmed = this.reason().trim();
    if (!target || !tenantId || trimmed === '' || trimmed.length > MAXIMUM_REASON_LENGTH) {
      return;
    }
    this.submitting.set(true);
    this.submitError.set(null);
    try {
      await this.api.decide(tenantId, target.request.id, target.decision, trimmed);
      this.pending.update((rows) => rows.filter((row) => row.id !== target.request.id));
      this.confirmTarget.set(null);
    } catch (error) {
      if (error instanceof ApiError && (error.status === 409 || error.status === 422)) {
        // Somebody else decided it first, or it lapsed underneath us — the
        // row this dialog was raised against is stale either way, so the
        // honest recovery is to close it and reload the queue rather than
        // let the operator retry against a request that no longer exists.
        this.confirmTarget.set(null);
        void this.load();
        return;
      }
      this.submitError.set(this.describe(error));
    } finally {
      this.submitting.set(false);
    }
  }

  protected confirmTitleKey(decision: ApprovalDecision): MessageKey {
    return decision === 'APPROVE'
      ? 'staff.approvals.confirm.titleApprove'
      : 'staff.approvals.confirm.titleDecline';
  }

  protected confirmButtonKey(decision: ApprovalDecision): MessageKey {
    return decision === 'APPROVE'
      ? 'staff.approvals.action.approve'
      : 'staff.approvals.action.decline';
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
