import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/** One HorecaOS support visit to this account (ADR 0081). */
export interface SupportVisit {
  readonly id: string;
  readonly principalSubject: string;
  readonly access: 'VIEW' | 'ASSIST';
  readonly reason: string;
  readonly ticketReference: string | null;
  readonly startedAt: string;
  readonly expiresAt: string;
  readonly endedAt: string | null;
  readonly endedBy: string | null;
  readonly endReason: string | null;
  readonly open: boolean;
}

/**
 * Settings · HorecaOS support visits (ADR 0081, behind the ADR 0082 flag
 * `feature.support_visits`).
 *
 * Every time someone from HorecaOS support entered this account: who, how
 * much access, why, and how it ended. A visit still in progress can be ended
 * here at once; ending it takes the access away immediately.
 */
@Component({
  selector: 'q-support-visits-page',
  imports: [TPipe],
  templateUrl: './support-visits-page.html',
  styleUrl: './support-visits-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupportVisitsPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(ApiClient);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly visits = signal<readonly SupportVisit[]>([]);
  protected readonly ending = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionText = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<{ items: SupportVisit[] }>(`/api/v1/operations/tenants/${tenantId}/support-sessions`),
      );
      this.visits.set(result.value?.items ?? []);
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

  protected when(iso: string): string {
    return formatDateTime(new Date(iso), 'Asia/Tashkent');
  }

  protected openEnd(visit: SupportVisit): void {
    this.ending.set(this.ending() === visit.id ? null : visit.id);
    this.reason.set('');
    this.actionText.set(null);
  }

  protected async end(visit: SupportVisit): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const reason = this.reason().trim();
    if (!tenantId || reason.length === 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      await firstValueFrom(
        this.api.post<{ reason: string }, SupportVisit>(
          `/api/v1/operations/tenants/${tenantId}/support-sessions/${visit.id}/end`,
          command({ reason }),
        ),
      );
      this.ending.set(null);
      this.actionText.set(this.i18n.t('settings.supportVisits.ended'));
      await this.load();
    } catch (error) {
      this.actionText.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
