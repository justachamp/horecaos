import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { describeApiError } from '../../orders/order-errors';
import { StatusPill } from '../../../shared/ui/status-pill';
import { IntegrationsApi, LivenessView } from './integrations-api';

/**
 * The locations-by-bindings liveness matrix (ADR 0040, ADR 0106, gap-map row
 * `10.8c`): `MarketplaceOperationsController.liveness` was built and
 * capability-gated well before this wave and had no caller anywhere in this
 * app — a dead marketplace integration produces no errors, so without this
 * screen a manager only notices a channel went quiet when a courier or a
 * customer complains.
 *
 * Self-contained (loads its own data on construction) rather than routed
 * through `IntegrationsPage`'s state, the same shape {@link FailureInboxPanel}
 * beside it uses: a read-mostly widget with no cross-panel state to
 * coordinate does not need the page in the middle.
 *
 * <p>Read-only. `silenceSeconds` null means nothing has ever arrived on that
 * binding at all — an unfinished configuration, not a channel that stopped —
 * and is rendered as a dash rather than "0s", which would read as "just
 * arrived".
 */
@Component({
  selector: 'app-liveness-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusPill],
  template: `
    <section class="panel">
      <div class="panel-header">
        <h2 class="q-subhead">{{ i18n.t('settings.integrations.liveness.title') }}</h2>
      </div>
      <p class="q-body-sm lead">{{ i18n.t('settings.integrations.liveness.lead') }}</p>

      @if (loading()) {
        <p class="q-body-sm">{{ i18n.t('settings.integrations.loading') }}</p>
      } @else if (error(); as message) {
        <p class="q-body-sm error" role="alert">{{ message }}</p>
      } @else {
        <table class="table">
          <thead>
            <tr>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.liveness.column.provider') }}
              </th>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.liveness.column.direction') }}
              </th>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.liveness.column.lastSuccess') }}
              </th>
              <th class="q-caption">
                {{ i18n.t('settings.integrations.liveness.column.silence') }}
              </th>
              <th class="q-caption">{{ i18n.t('settings.integrations.liveness.column.alert') }}</th>
            </tr>
          </thead>
          <tbody>
            @for (row of rows(); track row.bindingId) {
              <tr>
                <td class="q-body-sm">{{ row.providerName }}</td>
                <td class="q-body-sm">{{ row.direction }}</td>
                <td class="q-body-sm">{{ lastSuccessLabel(row) }}</td>
                <td class="q-body-sm">{{ silenceLabel(row) }}</td>
                <td class="q-body-sm">
                  <q-status-pill [label]="row.alertState" [tone]="alertTone(row.alertState)" />
                </td>
              </tr>
            } @empty {
              <tr>
                <td class="q-body-sm empty" colspan="5">
                  {{ i18n.t('settings.integrations.liveness.empty') }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styleUrl: './integrations-page.css',
})
export class LivenessPanel {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(IntegrationsApi);
  private readonly location = inject(CurrentLocation);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly rows = signal<readonly LivenessView[]>([]);

  constructor() {
    void this.load();
  }

  protected lastSuccessLabel(row: LivenessView): string {
    return row.lastSuccessAt === null
      ? this.i18n.t('settings.integrations.liveness.never')
      : new Date(row.lastSuccessAt).toLocaleString(this.i18n.locale());
  }

  protected silenceLabel(row: LivenessView): string {
    if (row.silenceSeconds === null) {
      return this.i18n.t('settings.integrations.liveness.noneYet');
    }
    const minutes = Math.round(row.silenceSeconds / 60);
    return this.i18n.t('settings.integrations.liveness.silenceMinutes', { minutes });
  }

  protected alertTone(alertState: string): 'success' | 'warning' | 'danger' | 'none' {
    switch (alertState) {
      case 'HEALTHY':
        return 'success';
      case 'STALE':
        return 'danger';
      case 'UNCONFIGURED':
        return 'none';
      default:
        return 'warning';
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.loading.set(false);
      return;
    }
    try {
      this.rows.set(await this.api.marketplaceLiveness(scope));
    } catch (failure) {
      if (failure instanceof ApiError) {
        this.error.set(describeApiError(failure, (key, values) => this.i18n.t(key, values)));
      } else {
        this.error.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.loading.set(false);
    }
  }
}
