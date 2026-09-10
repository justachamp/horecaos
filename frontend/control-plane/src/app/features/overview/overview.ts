import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { PlatformHealth, PlatformHealthApi, total, waited } from './platform-health-api';

/** A wait longer than this in any queue is worth someone's attention (the paging threshold is fifteen minutes). */
const STALE_SECONDS = 15 * 60;

/**
 * IA 1.1 Platform health -- one board: tenants, orders, fiscal receipts and
 * queues, counted across every tenant at the moment of asking.
 *
 * Every number is exact, not a sample. Waits are shown as the age of the
 * oldest thing waiting, because four hundred queued events on a busy evening
 * is the platform working and one event twenty minutes old is orders not
 * reaching a kitchen. Error-budget burn is not shown: it comes from the
 * monitoring stack, not from these tables.
 */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './overview.html',
  styleUrl: './overview.css',
})
export class Overview {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(PlatformHealthApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly health = signal<PlatformHealth | null>(null);

  protected readonly tenantsActive = computed(() => this.count(this.health()?.tenantsByStatus, 'ACTIVE'));
  protected readonly tenantsProvisioning = computed(() => this.count(this.health()?.tenantsByStatus, 'PROVISIONING'));
  protected readonly tenantsOther = computed(() => {
    const counts = this.health()?.tenantsByStatus ?? {};
    return total(counts) - (counts['ACTIVE'] ?? 0) - (counts['PROVISIONING'] ?? 0);
  });
  protected readonly ordersLive = computed(() => total(this.health()?.orders.liveByStatus ?? {}));
  protected readonly receiptsIssued = computed(() => this.health()?.receipts.lastDayByStatus['ISSUED'] ?? 0);
  protected readonly receiptsDay = computed(() => total(this.health()?.receipts.lastDayByStatus ?? {}));
  protected readonly outboxWaiting = computed(() => (this.health()?.queues.outbox ?? []).reduce((sum, q) => sum + q.pending, 0));
  protected readonly inboxWaiting = computed(() => (this.health()?.queues.inbox ?? []).reduce((sum, q) => sum + q.pending, 0));
  protected readonly oldestQueueWait = computed(() => {
    const queues = this.health()?.queues;
    if (!queues) {
      return 0;
    }
    return Math.max(0, ...queues.outbox.map((q) => q.oldestAgeSeconds), ...queues.inbox.map((q) => q.oldestAgeSeconds));
  });

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.health.set(await this.api.health());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  /** A status the server did not report has no rows, which is zero, not unknown. */
  private count(counts: Readonly<Record<string, number>> | undefined, status: string): number {
    return counts?.[status] ?? 0;
  }

  protected wait(seconds: number): string {
    const { value, unit } = waited(seconds);
    return this.i18n.t(`overview.wait.${unit}` as MessageKey, { value });
  }

  protected stale(seconds: number): boolean {
    return seconds >= STALE_SECONDS;
  }
}
