import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { describeApiError } from '../../orders/order-errors';
import { InboxFailureSummary, IntegrationsApi, TenantCategoryCount } from './integrations-api';

/**
 * A merchant's own error taxonomy and inbox replay (ADR 0006, ADR 0106,
 * gap-map row `10.8c`) — `FailureTaxonomyController` and
 * `FailureOperationsController` already answered these two questions, but
 * only at `/api/v1/control-plane/**` under `PLATFORM` scope, with a retry
 * method that trusted an *optional* tenant filter a caller could omit. This
 * renders `OperationsIntegrationFailureController`'s tenant-checked mirror
 * instead: a tenant admin can finally see their own aggregator's silence and
 * replay their own stuck message without phoning support.
 *
 * <p>Self-contained, the same reasoning {@link LivenessPanel} beside it gives.
 * Only categories with at least one dead-lettered or waiting message are
 * shown — a taxonomy with sixteen empty rows would bury the two that matter.
 */
@Component({
  selector: 'app-failure-inbox-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="panel">
      <div class="panel-header">
        <h2 class="q-subhead">{{ i18n.t('settings.integrations.failures.title') }}</h2>
      </div>
      <p class="q-body-sm lead">{{ i18n.t('settings.integrations.failures.lead') }}</p>

      @if (loading()) {
        <p class="q-body-sm">{{ i18n.t('settings.integrations.loading') }}</p>
      } @else if (loadError(); as message) {
        <p class="q-body-sm error" role="alert">{{ message }}</p>
      } @else {
        @if (activeCategories().length > 0) {
          <ul class="taxonomy">
            @for (category of activeCategories(); track category.code) {
              <li class="q-body-sm">
                <strong>{{ category.code }}</strong>
                — {{ i18n.t('settings.integrations.failures.deadLettered') }}:
                {{ category.outboxDeadLettered + category.inboxDeadLettered }},
                {{ i18n.t('settings.integrations.failures.waiting') }}:
                {{ category.outboxWaiting + category.inboxWaiting }}
              </li>
            }
          </ul>
        }

        @if (replayError(); as message) {
          <p class="q-body-sm error" role="alert">{{ message }}</p>
        }

        <table class="table">
          <thead>
            <tr>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.failures.column.consumer') }}
              </th>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.failures.column.eventType') }}
              </th>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.failures.column.errorCode') }}
              </th>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.failures.column.attempts') }}
              </th>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.failures.column.actions') }}
              </th>
            </tr>
          </thead>
          <tbody>
            @for (item of inbox(); track item.id) {
              <tr>
                <td class="q-body-sm">{{ item.consumerName }}</td>
                <td class="q-body-sm">{{ item.eventType }}</td>
                <td class="q-body-sm">{{ item.errorCode }}</td>
                <td class="q-body-sm">{{ item.attemptCount }}</td>
                <td class="q-body-sm">
                  <button
                    type="button"
                    class="q-body-sm link"
                    [disabled]="replaying() === item.id"
                    (click)="promptReplay(item)"
                  >
                    {{ i18n.t('settings.integrations.failures.replay') }}
                  </button>
                </td>
              </tr>
            } @empty {
              <tr>
                <td class="q-body-sm empty" colspan="5">
                  {{ i18n.t('settings.integrations.failures.empty') }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: `
    .taxonomy {
      margin: 0;
      padding: 16px 20px 0;
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
  `,
  styleUrl: './integrations-page.css',
})
export class FailureInboxPanel {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(IntegrationsApi);
  private readonly location = inject(CurrentLocation);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly taxonomy = signal<readonly TenantCategoryCount[]>([]);
  protected readonly inbox = signal<readonly InboxFailureSummary[]>([]);
  protected readonly replaying = signal<string | null>(null);
  protected readonly replayError = signal<string | null>(null);

  protected readonly activeCategories = computed(() =>
    this.taxonomy().filter(
      (category) =>
        category.outboxDeadLettered > 0 ||
        category.outboxWaiting > 0 ||
        category.inboxDeadLettered > 0 ||
        category.inboxWaiting > 0,
    ),
  );

  constructor() {
    void this.load();
  }

  protected promptReplay(item: InboxFailureSummary): void {
    const reason = window.prompt(this.i18n.t('settings.integrations.failures.replay.reasonPrompt'));
    if (reason === null || reason.trim().length === 0) {
      return;
    }
    void this.replay(item, reason.trim());
  }

  private async replay(item: InboxFailureSummary, reason: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.replaying.set(item.id);
    this.replayError.set(null);
    try {
      await this.api.replayInboxMessage(scope, item.consumerName, item.id, reason);
      await this.load();
    } catch (failure) {
      this.replayError.set(this.describeError(failure));
    } finally {
      this.replaying.set(null);
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.loading.set(false);
      return;
    }
    try {
      const [taxonomy, inbox] = await Promise.all([
        this.api.failureTaxonomy(scope),
        this.api.failureInbox(scope),
      ]);
      this.taxonomy.set(taxonomy);
      this.inbox.set(inbox);
    } catch (failure) {
      this.loadError.set(this.describeError(failure));
    } finally {
      this.loading.set(false);
    }
  }

  private describeError(failure: unknown): string {
    if (failure instanceof ApiError) {
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
