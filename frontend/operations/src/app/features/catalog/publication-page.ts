import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
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
  DraftPreview,
  PublicationHistoryEntry,
  PublicationResult,
  ValidationFinding,
  ValidationReport,
} from './catalog-domain';

/** One channel card's own state — line 4.6's "no hash, no last-published time" gap. */
interface ChannelPublicationState {
  readonly channel: ChannelView;
  readonly lastPublished: PublicationHistoryEntry | null;
  /** `UNKNOWN` until both the draft preview and a published history entry have loaded. */
  readonly draftStatus: 'MATCHES' | 'DIFFERS' | 'UNKNOWN';
}

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this board reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * IA 4.6 — Publication & channel readiness.
 *
 * **Built.** `CatalogPublicationController.validate`/`publish`/`activate`
 * were all already real (ADR 0016), reachable from no screen; earlier waves
 * added `GET .../publications` for Region 3's history and wired all three to
 * a page. This wave turns Region 1 from a histogram into a report —
 * `entityType`/`entityId`/`entityCode`/`detail` were on the wire and
 * discarded by `findingsByCode()`'s collapse to `{code, severity, count}`;
 * every finding now renders in full, and a `PRODUCT`-scoped one deep-links
 * to the product editor. It also gives Region 2's channel cards the state
 * they were missing — a hash and a last-published time, both derived from
 * the history this page already loads — plus the draft-versus-live
 * comparison: `CatalogApi.draftPreview` composes the same snapshot+hash
 * pipeline `publish` uses without writing anything, so a card can say
 * whether the draft differs from what is live before an operator commits to
 * publishing.
 *
 * **Reduced relative to the spec, deliberately.** A finding scoped to a
 * `VARIANT`, `MODIFIER_GROUP` or `MODIFIER_OPTION` cannot deep-link to its
 * owning product: `entityId` names the variant/group/option itself, and
 * nothing in the catalog API resolves "which product owns this variant" —
 * that finding renders its code and `entityCode` as text instead of a link
 * that would go nowhere useful (Togora §2n: omit, do not disable). No
 * aggregator preview (ADR 0040, not built — the spec itself says the same).
 */
@Component({
  selector: 'q-publication-page',
  imports: [TPipe, RouterLink],
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
  protected readonly draftPreview = signal<DraftPreview | null>(null);
  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly history = signal<readonly PublicationHistoryEntry[]>([]);

  protected readonly publishingChannel = signal<string | null>(null);
  protected readonly lastResult = signal<PublicationResult | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly rollingBackId = signal<string | null>(null);

  /** Region 2 — each channel joined against its own last-published history entry and the draft's hash. */
  protected readonly channelStates = computed<readonly ChannelPublicationState[]>(() => {
    const history = this.history();
    const preview = this.draftPreview();
    return this.channels().map((channel) => {
      const lastPublished =
        history.find((entry) => entry.channel === channel.code && entry.status === 'PUBLISHED') ??
        null;
      const draftStatus =
        lastPublished && preview
          ? lastPublished.contentHash === preview.contentHash
            ? 'MATCHES'
            : 'DIFFERS'
          : 'UNKNOWN';
      return { channel, lastPublished, draftStatus };
    });
  });

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

      const [history, report, preview] = await Promise.all([
        firstValueFrom(this.api.listPublicationHistory(brandScope)),
        catalogId
          ? firstValueFrom(this.api.validate(brandScope, catalogId))
          : Promise.resolve(null),
        catalogId
          ? firstValueFrom(this.api.draftPreview(brandScope, catalogId)).catch(() => null)
          : Promise.resolve(null),
      ]);
      this.history.set(history);
      this.report.set(report);
      this.draftPreview.set(preview);

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

  /**
   * Region 1's own findings, blockers first — replaces the old
   * `findingsByCode()` collapse to `{code, severity, count}`, which rendered
   * "PRICE_MISSING ×12" and discarded `entityType`/`entityId`/`entityCode`/
   * `detail`, every one of which is on the wire. An operator reading this
   * list can now tell *which* twelve, not just that there are twelve.
   */
  protected findings(): readonly ValidationFinding[] {
    return [...(this.report()?.findings ?? [])].sort((a, b) =>
      a.severity === b.severity ? 0 : a.severity === 'BLOCKER' ? -1 : 1,
    );
  }

  /**
   * Deep-links a finding to the tab that fixes it. Only `PRODUCT` resolves:
   * its `entityId` *is* the product id the editor route takes. A `VARIANT`,
   * `MODIFIER_GROUP` or `MODIFIER_OPTION` finding names that entity's own
   * id, not its owning product's, and nothing in the catalog API resolves
   * one from the other — so those render as text, not a link to nowhere.
   */
  protected findingLink(finding: ValidationFinding): string[] | null {
    return finding.entityType === 'PRODUCT' && finding.entityId
      ? ['/catalog/products', finding.entityId]
      : null;
  }

  protected findingEntityLabel(finding: ValidationFinding): string | null {
    if (!finding.entityType) {
      return null;
    }
    return finding.entityCode ? `${finding.entityType} ${finding.entityCode}` : finding.entityType;
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
