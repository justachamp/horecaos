import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { PlatformHealth, PlatformHealthApi, waited } from '../overview/platform-health-api';

const STALE_SECONDS = 15 * 60;

/**
 * IA 4.1 Message flow -- what is waiting in each queue and for how long.
 *
 * Events the platform has not yet published, by topic, and messages a
 * consumer has not yet processed, by consumer, each with the age of its
 * oldest waiting item. Anything waiting fifteen minutes is marked: a healthy
 * retry schedule never gets that far. Stuck broker partitions are not shown;
 * they are visible to the monitoring stack, not to these tables.
 */
@Component({
  selector: 'app-message-flow',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './message-flow.html',
  styleUrl: './message-flow.css',
})
export class MessageFlow {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(PlatformHealthApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly health = signal<PlatformHealth | null>(null);

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

  protected wait(seconds: number): string {
    const { value, unit } = waited(seconds);
    return this.i18n.t(`overview.wait.${unit}` as MessageKey, { value });
  }

  protected stale(seconds: number): boolean {
    return seconds >= STALE_SECONDS;
  }
}
