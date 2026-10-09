import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { AgentView, PendingAgentView, PrintingApi } from './printing-api';
import { describePrintError } from './printing-errors';
import { PRINTER_STATUS_KEYS } from './printing-labels';

/**
 * Settings · Printing and receipts · Print agents (ADR 0154 decision 9).
 *
 * A print agent is a small program on a machine at the branch that is on the same network as the printers. It
 * enrols like any device (ADR 0079): it shows a short code, and a manager with `print.manage` types the code
 * here, sees what the code claims to be, and approves it. Approving grants the agent exactly two capabilities at
 * this branch (claim a job, report an outcome) and nothing a person could use. Revoking is the stop button: the
 * agent is refused on its very next request, and nobody is signed out.
 */
@Component({
  selector: 'q-print-agents-card',
  imports: [TPipe],
  templateUrl: './agents-card.html',
  styleUrl: './printing-shared.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentsCard {
  private readonly api = inject(PrintingApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly agents = input.required<readonly AgentView[]>();
  readonly changed = output<void>();

  protected readonly statusKeys = PRINTER_STATUS_KEYS;

  protected readonly code = signal('');
  protected readonly pending = signal<PendingAgentView | null>(null);
  protected readonly agentName = signal('');
  protected readonly revoking = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly canLookUp = computed(() => !this.busy() && this.code().trim().length > 0);
  protected readonly canApprove = computed(
    () => !this.busy() && this.pending() !== null && this.agentName().trim().length > 0,
  );
  protected readonly canRevoke = computed(() => !this.busy() && this.reason().trim().length > 0);

  protected when(iso: string): string {
    return formatDateTime(new Date(iso), 'Asia/Tashkent');
  }

  protected setCode(value: string): void {
    this.code.set(value);
    this.pending.set(null);
    this.error.set(null);
  }

  /** Reads what the typed code claims before anything is approved, so the manager sees what they would grant. */
  protected async lookUp(): Promise<void> {
    if (!this.canLookUp()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.pending.set(null);
    try {
      const found = await this.api.pendingAgent(this.scope(), this.code().trim());
      this.pending.set(found);
      this.agentName.set(found.requestedLabel ?? '');
    } catch (error) {
      this.error.set(
        error instanceof ApiError && error.code === ApiErrorCode.RESOURCE_NOT_FOUND
          ? this.i18n.t('settings.printing.agents.codeUnknown')
          : this.describe(error),
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected async approve(): Promise<void> {
    if (!this.canApprove()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.approveAgent(this.scope(), this.code().trim(), this.agentName().trim());
      this.pending.set(null);
      this.code.set('');
      this.agentName.set('');
      this.notice.set(this.i18n.t('settings.printing.agents.approved'));
      this.changed.emit();
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected openRevoke(agent: AgentView): void {
    this.revoking.set(this.revoking() === agent.device.deviceId ? null : agent.device.deviceId);
    this.reason.set('');
    this.error.set(null);
  }

  protected async revoke(agent: AgentView): Promise<void> {
    if (!this.canRevoke()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.revokeAgent(this.scope(), agent.device.deviceId, this.reason().trim());
      this.revoking.set(null);
      this.notice.set(this.i18n.t('settings.printing.agents.revoked'));
      this.changed.emit();
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  private describe(error: unknown): string {
    return describePrintError(error, this.i18n, {
      off: 'settings.printing.error.off',
      noRoute: 'settings.printing.error.noRoute',
    });
  }
}
