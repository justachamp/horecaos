import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { FailureSummary, IntegrationOpsApi } from './integration-ops-api';

/**
 * IA 4.1 Message flow -- outbox/inbox throughput, lag, and stuck partitions
 * (ADR 0004/0005).
 *
 * Partial by design, named here rather than hidden: `FailureOperationsController`
 * is a dead-letter queue (list + retry + resolve), not a monitoring API, and
 * `Page` carries no total count, so this screen cannot compute a real
 * throughput or lag number. What it shows honestly: the outbox's current
 * `PENDING` events (a proxy for "what is in flight right now") beside the
 * same dead-letter view 4.2 owns in full, with the queue-lag and
 * stuck-partition metrics named as not exposed over HTTP -- they live in
 * Micrometer/Prometheus (`MessagingBacklogMetrics`), not this API.
 */
@Component({
  selector: 'app-message-flow',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './message-flow.html',
  styleUrl: './message-flow.css',
})
export class MessageFlow {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(IntegrationOpsApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly pending = signal<readonly FailureSummary[]>([]);
  protected readonly deadLettered = signal<readonly FailureSummary[]>([]);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [pending, deadLettered] = await Promise.all([
        this.api.outboxFailures('PENDING', 20),
        this.api.outboxFailures('DEAD_LETTER', 20),
      ]);
      this.pending.set(pending.items);
      this.deadLettered.set(deadLettered.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
