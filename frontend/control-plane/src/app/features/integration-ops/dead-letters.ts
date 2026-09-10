import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import {
  FailureDetail,
  FailureSummary,
  InboxFailureSummary,
  IntegrationOpsApi,
  RESOLUTION_CATEGORIES,
  ResolutionCategory,
} from './integration-ops-api';

type Queue = 'outbox' | 'inbox';

/** One failed message from either queue; an inbox row also names its consumer. */
interface Row {
  readonly queue: Queue;
  readonly consumerName: string | null;
  readonly failure: FailureSummary;
}

type Mode = 'details' | 'retry' | 'resolve';

/**
 * IA 4.2 Dead letters & replay -- both queues, each failure retried or
 * resolved with a reason that goes to the audit log.
 *
 * The outbox holds events the platform could not publish; the inbox holds
 * events a consumer could not process, one row per consumer that failed, each
 * with its own decision. The inbox half needed one consumer's name per call
 * and nothing said which consumers exist, so it was left out; the worklist
 * across every consumer is what it reads now.
 */
@Component({
  selector: 'app-dead-letters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
  templateUrl: './dead-letters.html',
  styleUrl: './dead-letters.css',
})
export class DeadLetters {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(IntegrationOpsApi);
  protected readonly directory = inject(TenantDirectory);
  protected readonly categories = RESOLUTION_CATEGORIES;

  /** '' means every tenant: the dead-letter queues are the platform's, not one tenant's. */
  protected readonly tenantId = signal('');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly outbox = signal<readonly Row[]>([]);
  protected readonly inbox = signal<readonly Row[]>([]);

  protected readonly openKey = signal<string | null>(null);
  protected readonly mode = signal<Mode>('details');
  protected readonly detail = signal<FailureDetail | null>(null);
  protected readonly reason = signal('');
  protected readonly category = signal<ResolutionCategory>('UNKNOWN');
  protected readonly evidence = signal('');
  protected readonly acting = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.openKey.set(null);
    const tenant = this.tenantId() || undefined;
    try {
      const [outbox, inbox] = await Promise.all([
        this.api.outboxFailures('DEAD_LETTER', 100, tenant),
        this.api.inboxFailuresAcrossConsumers('DEAD_LETTER', 100, tenant),
      ]);
      this.outbox.set(outbox.items.map((failure) => ({ queue: 'outbox', consumerName: null, failure })));
      this.inbox.set(
        inbox.items.map((failure: InboxFailureSummary) => ({
          queue: 'inbox',
          consumerName: failure.consumerName,
          failure,
        })),
      );
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected sections(): readonly { queue: Queue; title: MessageKey; empty: MessageKey; rows: readonly Row[] }[] {
    return [
      { queue: 'outbox', title: 'deadLetters.outbox.title', empty: 'deadLetters.outbox.empty', rows: this.outbox() },
      { queue: 'inbox', title: 'deadLetters.inbox.title', empty: 'deadLetters.inbox.empty', rows: this.inbox() },
    ];
  }

  /** A row's identity: an event id alone is not unique on the inbox side. */
  protected key(row: Row): string {
    return `${row.queue}:${row.consumerName ?? ''}:${row.failure.id}`;
  }

  protected tenantName(tenantId: string | null): string {
    return tenantId === null ? '—' : this.directory.nameOf(tenantId);
  }

  protected categoryKey(category: string): MessageKey {
    return `deadLetters.category.${category}` as MessageKey;
  }

  protected async open(row: Row, mode: Mode): Promise<void> {
    const key = this.key(row);
    if (this.openKey() === key && this.mode() === mode) {
      this.openKey.set(null);
      return;
    }
    this.openKey.set(key);
    this.mode.set(mode);
    this.reason.set('');
    this.evidence.set('');
    this.category.set('UNKNOWN');
    this.actionError.set(null);
    this.actionMessage.set(null);
    if (mode !== 'details') {
      return;
    }
    this.detail.set(null);
    try {
      this.detail.set(
        row.queue === 'outbox'
          ? await this.api.outboxFailure(row.failure.id)
          : await this.api.inboxFailure(row.consumerName!, row.failure.id),
      );
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected canAct(): boolean {
    const needsEvidence = this.mode() === 'resolve' && this.category() === 'UNCERTAIN_EXTERNAL_OUTCOME';
    return (
      !this.acting() && this.reason().trim().length > 0 && (!needsEvidence || this.evidence().trim().length > 0)
    );
  }

  /**
   * Retry returns the message to pending; resolve closes it for good. Either
   * way the row leaves the list only when the server says something changed:
   * a colleague may have acted on it first.
   */
  protected async act(row: Row): Promise<void> {
    if (!this.canAct()) {
      return;
    }
    const reason = this.reason().trim();
    const evidence = this.evidence().trim() || undefined;
    this.acting.set(true);
    this.actionError.set(null);
    try {
      const result =
        this.mode() === 'retry'
          ? row.queue === 'outbox'
            ? await this.api.retryOutbox(row.failure.id, reason)
            : await this.api.retryInbox(row.consumerName!, row.failure.id, reason)
          : row.queue === 'outbox'
            ? await this.api.resolveOutbox(row.failure.id, this.category(), reason, evidence)
            : await this.api.resolveInbox(row.consumerName!, row.failure.id, this.category(), reason, evidence);
      if (result.changed) {
        const key = this.key(row);
        const list = row.queue === 'outbox' ? this.outbox : this.inbox;
        list.update((rows) => rows.filter((candidate) => this.key(candidate) !== key));
        this.openKey.set(null);
        this.actionMessage.set(
          this.i18n.t(this.mode() === 'retry' ? 'deadLetters.retry.succeeded' : 'deadLetters.resolve.succeeded'),
        );
      } else {
        this.actionMessage.set(this.i18n.t('deadLetters.retry.noChange'));
      }
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.acting.set(false);
    }
  }

  /** A detail field as text, or null when the server sent nothing for it. */
  protected field(name: string): string | null {
    const value = this.detail()?.[name];
    return value === null || value === undefined || value === '' ? null : String(value);
  }
}
