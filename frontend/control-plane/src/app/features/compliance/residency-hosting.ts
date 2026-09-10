import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { ResidencyApi, ResidencyView, TenantProfileView } from './residency-api';

/**
 * IA 6.3 Residency & hosting -- where every tenant trades, and where its data
 * lives.
 *
 * All tenant data is hosted in one country whatever market the tenant trades
 * in, and the screen says so rather than inventing a region per tenant. A
 * tenant's market is changeable, but only with a second signature: the first
 * request raises the approval, and asking again once it is approved makes the
 * change. Currency and timezone stay as they are.
 */
@Component({
  selector: 'app-residency-hosting',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './residency-hosting.html',
  styleUrl: './residency-hosting.css',
})
export class ResidencyHosting {
  protected readonly i18n = inject(I18nService);
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(ResidencyApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly view = signal<ResidencyView | null>(null);

  protected readonly changing = signal<string | null>(null);
  protected readonly country = signal('');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  protected readonly countByCountry = computed(() => {
    const counts = new Map<string, number>();
    for (const tenant of this.view()?.tenants ?? []) {
      counts.set(tenant.countryCode, (counts.get(tenant.countryCode) ?? 0) + 1);
    }
    return counts;
  });

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.view.set(await this.api.residency());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected marketName(code: string): string {
    return this.view()?.markets.find((market) => market.code === code)?.name ?? code;
  }

  protected typeKey(type: string): MessageKey {
    return `businessTypes.type.${type}` as MessageKey;
  }

  protected open(tenant: TenantProfileView): void {
    this.changing.set(this.changing() === tenant.tenantId ? null : tenant.tenantId);
    this.country.set('');
    this.reason.set('');
    this.actionError.set(null);
  }

  protected canChange(tenant: TenantProfileView): boolean {
    return (
      !this.busy() &&
      this.country().length === 2 &&
      this.country() !== tenant.countryCode &&
      this.reason().trim().length > 0
    );
  }

  protected async change(tenant: TenantProfileView): Promise<void> {
    if (!this.canChange(tenant)) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      const outcome = await this.api.changeCountry(tenant.tenantId, this.country(), this.reason().trim());
      const parameters = { tenant: tenant.displayName, country: this.marketName(this.country()) };
      this.actionMessage.set(
        outcome.status === 'CHANGED'
          ? this.i18n.t('residency.change.done', parameters)
          : outcome.status === 'DECLINED'
            ? this.i18n.t('residency.change.declined', parameters)
            : this.i18n.t('residency.change.awaiting', parameters),
      );
      if (outcome.status === 'CHANGED') {
        this.changing.set(null);
        await this.load();
      }
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
