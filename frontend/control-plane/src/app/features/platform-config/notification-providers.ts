import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { Gateway, NotificationProvidersApi, REVIEW_STATES, Sender, TemplateReview } from './notification-providers-api';

/**
 * IA 8.4 Notification providers & template moderation -- the messaging
 * gateways the platform may send through, which tenant sends as what, and
 * where each SMS wording stands with its gateway.
 *
 * A wording marked as waiting on its gateway is not sent until the answer is
 * recorded here: approved with the gateway's reference, or refused with what
 * it objected to. Wordings that never needed approval send as before.
 */
@Component({
  selector: 'app-notification-providers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './notification-providers.html',
  styleUrls: ['../compliance/residency-hosting.css', './notification-providers.css'],
})
export class NotificationProviders {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(NotificationProvidersApi);

  protected readonly states = REVIEW_STATES;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly gateways = signal<readonly Gateway[]>([]);
  protected readonly senders = signal<readonly Sender[]>([]);
  protected readonly reviews = signal<readonly TemplateReview[]>([]);
  protected readonly filter = signal<string>('PENDING');

  protected readonly acting = signal<string | null>(null);
  protected readonly nextState = signal<string>('APPROVED');
  protected readonly reference = signal('');
  protected readonly providerNote = signal('');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [registry, reviews] = await Promise.all([
        this.api.registry(),
        this.api.reviews(this.filter() === '' ? null : this.filter()),
      ]);
      this.gateways.set(registry.gateways);
      this.senders.set(registry.senders);
      this.reviews.set(reviews);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected async chooseFilter(state: string): Promise<void> {
    this.filter.set(state);
    try {
      this.reviews.set(await this.api.reviews(state === '' ? null : state));
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected stateKey(state: string): MessageKey {
    return `notificationProviders.review.${state}` as MessageKey;
  }

  protected open(review: TemplateReview): void {
    this.acting.set(this.acting() === review.versionId ? null : review.versionId);
    this.nextState.set(review.providerReview === 'PENDING' ? 'APPROVED' : 'PENDING');
    this.reference.set('');
    this.providerNote.set('');
    this.reason.set('');
    this.actionError.set(null);
  }

  protected canRecord(): boolean {
    if (this.busy() || this.reason().trim().length === 0) {
      return false;
    }
    if (this.nextState() === 'APPROVED') {
      return this.reference().trim().length > 0;
    }
    if (this.nextState() === 'REJECTED') {
      return this.providerNote().trim().length > 0;
    }
    return true;
  }

  protected async record(review: TemplateReview): Promise<void> {
    if (!this.canRecord()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    const reference = this.reference().trim();
    const note = this.providerNote().trim();
    try {
      await this.api.record(review.tenantId, review.versionId, {
        state: this.nextState(),
        reference: reference.length > 0 ? reference : undefined,
        providerNote: note.length > 0 ? note : undefined,
        reason: this.reason().trim(),
      });
      this.acting.set(null);
      this.actionMessage.set(
        this.i18n.t('notificationProviders.recorded', {
          template: review.templateKey,
          state: this.i18n.t(this.stateKey(this.nextState())),
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
