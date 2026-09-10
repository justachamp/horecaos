import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG } from '../../core/config/app-config';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantDirectory } from '../../shared/tenant-directory';
import { SupportAccess, SupportSessionView, SupportSessionsApi } from './support-sessions-api';

/**
 * IA 2.8 Support sessions -- entering a tenant's operations app, for a while,
 * for a stated reason the tenant can read.
 *
 * Opening one gives the signed-in person a support role in this tenant until
 * the chosen deadline; access then ends by itself. VIEW looks; ASSIST also
 * does the order-floor acts a location manager could, and nothing that moves
 * money, reveals a customer or changes access. The tenant's own
 * administrators see every visit and can end one.
 */
@Component({
  selector: 'app-tenant-impersonation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './tenant-impersonation.html',
  styleUrl: './tenant-impersonation.css',
})
export class TenantImpersonation {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  protected readonly directory = inject(TenantDirectory);
  private readonly api = inject(SupportSessionsApi);
  private readonly config = inject(APP_CONFIG);
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId')!;

  protected readonly lengths = [15, 30, 60, 120, 240];

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly sessions = signal<readonly SupportSessionView[]>([]);
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  protected readonly access = signal<SupportAccess>('VIEW');
  protected readonly minutes = signal(60);
  protected readonly reason = signal('');
  protected readonly ticket = signal('');
  protected readonly endReason = signal('');

  /** The signed-in person's own open session here, if any. */
  protected readonly mine = computed(() => {
    const me = this.session.current()?.subject;
    return this.sessions().find((candidate) => candidate.open && candidate.principalSubject === me) ?? null;
  });

  constructor() {
    void this.directory.load();
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.sessions.set((await this.api.list(this.tenantId)).items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  /** Where the operations app opens for this tenant, or null when this deployment has not said. */
  protected operationsLink(): string | null {
    const origin = this.config.operationsAppUrl ?? '';
    return origin.length > 0 ? `${origin}/?supportTenant=${this.tenantId}` : null;
  }

  protected canOpen(): boolean {
    return !this.busy() && this.mine() === null && this.reason().trim().length > 0;
  }

  protected async open(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canOpen()) {
      return;
    }
    await this.run(async () => {
      const ticket = this.ticket().trim();
      await this.api.open(this.tenantId, {
        access: this.access(),
        reason: this.reason().trim(),
        ticketReference: ticket.length > 0 ? ticket : undefined,
        minutes: this.minutes(),
      });
      this.reason.set('');
      this.ticket.set('');
      return this.i18n.t('support.opened');
    });
  }

  protected async end(session: SupportSessionView): Promise<void> {
    const reason = this.endReason().trim();
    if (this.busy() || reason.length === 0) {
      return;
    }
    await this.run(async () => {
      await this.api.end(this.tenantId, session.id, reason);
      this.endReason.set('');
      return this.i18n.t('support.ended');
    });
  }

  protected who(subject: string): string {
    return subject === this.session.current()?.subject ? this.i18n.t('planCatalog.you') : subject;
  }

  /** How a session finished, in words: still open, ended early by someone, or ran out. */
  protected outcome(session: SupportSessionView): string {
    if (session.open) {
      return this.i18n.t('support.state.open', { time: this.i18n.dateTime(asDate(session.expiresAt)) });
    }
    if (session.endedAt !== null) {
      return this.i18n.t('support.state.ended', {
        time: this.i18n.dateTime(asDate(session.endedAt)),
        who: this.who(session.endedBy ?? ''),
      });
    }
    return this.i18n.t('support.state.lapsed', { time: this.i18n.dateTime(asDate(session.expiresAt)) });
  }

  private async run(write: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      const message = await write();
      await this.load();
      this.actionMessage.set(message);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
