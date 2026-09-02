import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { FailureSummary, IntegrationOpsApi } from './integration-ops-api';

/**
 * IA 4.2 Dead letters & replay -- failed messages by cause with selective,
 * audited replay (ADR 0006).
 *
 * Outbox only: the inbox side (`GET .../inbox/{consumerName}`) needs one
 * consumer name per call and there is no "list every consumer" endpoint, so
 * a genuinely cross-consumer inbox view is out of scope here without a new
 * backend read-model this wave does not add.
 */
@Component({
  selector: 'app-dead-letters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dead-letters.html',
  styleUrl: './dead-letters.css',
})
export class DeadLetters {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(IntegrationOpsApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly events = signal<readonly FailureSummary[]>([]);
  protected readonly retrying = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.api.outboxFailures('DEAD_LETTER', 100);
      this.events.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected async retry(eventId: string): Promise<void> {
    this.retrying.set(eventId);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      const result = await this.api.retryOutbox(eventId, this.i18n.t('deadLetters.retry.reason'));
      this.actionMessage.set(
        result.changed ? this.i18n.t('deadLetters.retry.succeeded') : this.i18n.t('deadLetters.retry.noChange'),
      );
      if (result.changed) {
        this.events.update((events) => events.filter((event) => event.eventId !== eventId));
      }
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.retrying.set(null);
    }
  }
}
