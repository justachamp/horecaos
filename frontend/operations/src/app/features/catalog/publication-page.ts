import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import {
  CatalogSummary,
  PublicationHistoryEntry,
  PublicationResult,
  ValidationReport,
} from './catalog-domain';

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 4.6 — Publication & channel readiness.
 *
 * **Built.** `CatalogPublicationController.validate`/`publish`/`activate`
 * were all already real (ADR 0016), reachable from no screen; this wave adds
 * the one missing read — `GET .../publications` — for Region 3's history,
 * and wires all three to a page. Region 1 (readiness report), Region 2
 * (channel cards with publish), Region 3 (history with rollback) are the
 * spec's own three-region layout.
 *
 * **Reduced relative to the spec, deliberately.** No deep links from a
 * finding to the exact editor tab (`entityType`/`entityId` are on the wire;
 * the product editor has no readiness-rail anchor to receive one) — a
 * finding names the entity by id instead. No aggregator preview (ADR 0040,
 * not built — the spec itself says the same). No `Черновик отличается` /
 * `Актуально` content-hash comparison chip — it needs the *draft's* would-be
 * hash, which nothing computes without a snapshot; the channel card instead
 * shows the live publication's own hash for reference.
 */
@Component({
  selector: 'q-publication-page',
  imports: [TPipe],
  templateUrl: './publication-page.html',
  styleUrl: './publication-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PublicationPage implements OnInit {
  private readonly api = inject(CatalogApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly catalogs = signal<readonly CatalogSummary[]>([]);
  protected readonly activeCatalogId = signal<string | null>(null);
  protected readonly report = signal<ValidationReport | null>(null);
  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly history = signal<readonly PublicationHistoryEntry[]>([]);

  protected readonly publishingChannel = signal<string | null>(null);
  protected readonly lastResult = signal<PublicationResult | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly rollingBackId = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await Promise.all([this.brand.ensureLoaded(), this.location.ensureLoaded()]);
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    const brandScope = this.brand.scope();
    if (!brandScope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      const catalogs = await firstValueFrom(this.api.listCatalogs(brandScope));
      this.catalogs.set(catalogs);
      const catalogId = this.activeCatalogId() ?? catalogs[0]?.catalogId ?? null;
      this.activeCatalogId.set(catalogId);

      const [history, report] = await Promise.all([
        firstValueFrom(this.api.listPublicationHistory(brandScope)),
        catalogId
          ? firstValueFrom(this.api.validate(brandScope, catalogId))
          : Promise.resolve(null),
      ]);
      this.history.set(history);
      this.report.set(report);

      const locationScope = this.location.scope();
      if (locationScope) {
        try {
          this.channels.set(await this.channelsApi.list(locationScope));
        } catch {
          // Channel cards are a nicety on top of the validation report; the
          // report and history are the load this screen cannot do without.
        }
      }
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected findingsByCode(): ReadonlyArray<{ code: string; severity: string; count: number }> {
    const report = this.report();
    if (!report) {
      return [];
    }
    const counts = new Map<string, { code: string; severity: string; count: number }>();
    for (const finding of report.findings) {
      const existing = counts.get(finding.code);
      if (existing) {
        existing.count += 1;
      } else {
        counts.set(finding.code, { code: finding.code, severity: finding.severity, count: 1 });
      }
    }
    return [...counts.values()].sort((a, b) => {
      if (a.severity !== b.severity) {
        return a.severity === 'BLOCKER' ? -1 : 1;
      }
      return b.count - a.count;
    });
  }

  protected async publish(channel: ChannelView): Promise<void> {
    const brandScope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    if (!brandScope || !catalogId || this.publishingChannel() !== null) {
      return;
    }
    this.publishingChannel.set(channel.code);
    this.actionError.set(null);
    try {
      const result = await firstValueFrom(this.api.publish(brandScope, catalogId, channel.code));
      this.lastResult.set(result);
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.publishingChannel.set(null);
    }
  }

  protected canRollback(entry: PublicationHistoryEntry): boolean {
    return entry.status !== 'REJECTED' && this.rollingBackId() === null;
  }

  protected async rollback(entry: PublicationHistoryEntry): Promise<void> {
    const brandScope = this.brand.scope();
    if (!brandScope || !this.canRollback(entry)) {
      return;
    }
    this.rollingBackId.set(entry.publicationId);
    this.actionError.set(null);
    try {
      await firstValueFrom(this.api.rollback(brandScope, entry.publicationId));
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.rollingBackId.set(null);
    }
  }

  protected timeLabel(iso: string | null | undefined): string {
    return iso ? formatClock(new Date(iso), PLACEHOLDER_TIME_ZONE) : '—';
  }

  protected dismissResult(): void {
    this.lastResult.set(null);
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
