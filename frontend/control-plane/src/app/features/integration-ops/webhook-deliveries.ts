import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { IntegrationOpsApi, WebhookDelivery } from './integration-ops-api';

/**
 * IA 4.3 Webhook deliveries -- every call a payment provider made to
 * HorecaOS, newest first.
 *
 * For each: whether its signature checked out, what HorecaOS answered in the
 * provider's own codes, and whether it matched one of our payments. The
 * bodies stay in protected storage; the provider's reference and our answer
 * are what a dispute with the provider's support is settled with.
 */
@Component({
  selector: 'app-webhook-deliveries',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './webhook-deliveries.html',
  styleUrl: './webhook-deliveries.css',
})
export class WebhookDeliveries {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(IntegrationOpsApi);

  protected readonly providers = ['CLICK', 'PAYME', 'TELEGRAM'];
  protected readonly provider = signal('');
  protected readonly invalidOnly = signal(false);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly deliveries = signal<readonly WebhookDelivery[]>([]);
  protected readonly invalidCount = computed(() => this.deliveries().filter((d) => !d.signatureValid).length);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.deliveries.set(await this.api.webhooks(this.provider() || null, this.invalidOnly()));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected chooseProvider(provider: string): void {
    this.provider.set(provider);
    void this.load();
  }

  protected chooseInvalidOnly(on: boolean): void {
    this.invalidOnly.set(on);
    void this.load();
  }
}
