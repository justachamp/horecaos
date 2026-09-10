import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { AccessApi } from '../access/access-api';
import { PlatformPendingApproval, ResidencyApi } from './residency-api';

/**
 * IA 6.5 Approvals -- platform decisions waiting for a second signature, in
 * every tenant: a change of the country a tenant trades in, and a tenant's
 * activation where a policy asks for one.
 *
 * Nobody can decide their own request, and each row says whether the reader
 * could. Bulk export and retention override are not actions the platform has
 * yet, so there is nothing of theirs to wait here.
 */
@Component({
  selector: 'app-platform-approvals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './platform-approvals.html',
  styleUrl: './residency-hosting.css',
})
export class PlatformApprovals {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(ResidencyApi);
  private readonly access = inject(AccessApi);
  protected readonly directory = inject(TenantDirectory);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly waiting = signal<readonly PlatformPendingApproval[]>([]);

  protected readonly deciding = signal<{ id: string; decision: 'APPROVE' | 'DECLINE' } | null>(null);
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  constructor() {
    void this.directory.load();
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.waiting.set(await this.api.platformApprovals());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected actionKey(code: string): MessageKey {
    return `platformApprovals.action.${code.replace(/\./g, '_')}` as MessageKey;
  }

  protected open(row: PlatformPendingApproval, decision: 'APPROVE' | 'DECLINE'): void {
    const current = this.deciding();
    this.deciding.set(
      current?.id === row.request.id && current.decision === decision ? null : { id: row.request.id, decision },
    );
    this.reason.set('');
    this.actionError.set(null);
  }

  protected async confirm(row: PlatformPendingApproval): Promise<void> {
    const action = this.deciding();
    const reason = this.reason().trim();
    if (action === null || reason.length === 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      await this.access.decide(row.tenantId, row.request.id, action.decision, reason);
      this.deciding.set(null);
      this.actionMessage.set(
        this.i18n.t(action.decision === 'APPROVE' ? 'platformApprovals.approved' : 'platformApprovals.declined', {
          tenant: this.directory.nameOf(row.tenantId),
        }),
      );
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
