import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { DiffViewer } from '../../../shared/ui/diff-viewer';
import { formatDateTime } from '../../../core/format/datetime';
import { describeApiError } from '../../orders/order-errors';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from '../../reports/reports-filter-state';
import {
  ContentApi,
  ContentItemView,
  ContentRevisionSummary,
  ContentRevisionView,
} from './content-api';
import { diffDocuments } from './content-document';
import { dayLabel, endDay, startDay } from './content-schedule';
import { problemText } from './content-texts';

type Against = 'previous' | 'live';

/**
 * The immutable history of an item (ADR 0157, Decision 2): every version a customer may have been
 * shown, with what changed between it and the one before (or the live one), and a way back.
 *
 * A revision is never edited; **restoring one copies it into the draft** and publishing is a
 * separate act, so going back is as deliberate as going forward. A version whose images were
 * released after the retention period cannot be restored, and the platform says so by name.
 */
@Component({
  selector: 'q-content-history',
  imports: [DiffViewer, TPipe],
  templateUrl: './content-history.html',
  styleUrl: './content-history.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentHistory implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  readonly itemId = input.required<string>();
  /** The item's current version, which restoring presents as `If-Match`. */
  readonly version = input.required<number>();
  /** An archived item's history is read-only. */
  readonly readOnly = input(false, { transform: booleanAttribute });

  /** The item after a restore has put a revision into its draft. */
  readonly restored = output<ContentItemView>();

  protected readonly revisions = signal<readonly ContentRevisionSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly selected = signal<number | null>(null);
  protected readonly against = signal<Against>('previous');
  protected readonly busy = signal(false);

  private readonly loaded = signal<ReadonlyMap<number, ContentRevisionView>>(new Map());

  protected readonly chosen = computed(() => {
    const no = this.selected();
    return no === null ? null : (this.loaded().get(no) ?? null);
  });

  /** The revision the chosen one is compared with. */
  protected readonly baseNo = computed<number | null>(() => {
    const no = this.selected();
    if (no === null) {
      return null;
    }
    if (this.against() === 'live') {
      const live = this.revisions().find((revision) => revision.live);
      return live && live.revisionNo !== no ? live.revisionNo : null;
    }
    const earlier = this.revisions()
      .map((revision) => revision.revisionNo)
      .filter((n) => n < no);
    return earlier.length === 0 ? null : Math.max(...earlier);
  });

  protected readonly diff = computed(() => {
    const chosen = this.chosen();
    const baseNo = this.baseNo();
    if (chosen === null) {
      return null;
    }
    const base = baseNo === null ? null : (this.loaded().get(baseNo) ?? null);
    if (baseNo !== null && base === null) {
      return null;
    }
    const entries: Record<string, { before: unknown; after: unknown }> = {
      ...diffDocuments(base?.document ?? null, chosen.document),
    };
    const beforeCard = base ?? null;
    const around = (revision: ContentRevisionView | null): Record<string, unknown> => ({
      channelCodes: revision?.channelCodes ?? null,
      startsAt: revision?.startsAt ?? null,
      endsAt: revision?.endsAt ?? null,
      promotionId: revision?.promotionId ?? null,
      locationId: revision?.locationId ?? null,
    });
    const left = around(beforeCard);
    const right = around(chosen);
    for (const key of Object.keys(right)) {
      if (JSON.stringify(left[key]) !== JSON.stringify(right[key])) {
        entries[`⟂ ${key}`] = { before: left[key], after: right[key] };
      }
    }
    return entries;
  });

  async ngOnInit(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      return;
    }
    try {
      this.revisions.set(await this.api.revisions(scope, this.itemId()));
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.loading.set(false);
    }
  }

  protected async select(revisionNo: number): Promise<void> {
    this.selected.set(revisionNo);
    this.error.set(null);
    await this.ensure(revisionNo);
    const base = this.baseNo();
    if (base !== null) {
      await this.ensure(base);
    }
  }

  protected async setAgainst(value: string): Promise<void> {
    this.against.set(value === 'live' ? 'live' : 'previous');
    const base = this.baseNo();
    if (base !== null) {
      await this.ensure(base);
    }
  }

  protected async restore(): Promise<void> {
    const scope = this.brand.scope();
    const no = this.selected();
    if (scope === null || no === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      this.restored.emit(await this.api.republish(scope, this.itemId(), no, this.version()));
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.busy.set(false);
    }
  }

  /** When a version was published, in the tenant's zone (the placeholder until a brand carries its own). */
  protected when(iso: string): string {
    return formatDateTime(new Date(iso), REPORTS_PLACEHOLDER_TIME_ZONE);
  }

  protected window(revision: ContentRevisionSummary): string | null {
    const first = startDay(revision.startsAt);
    const last = endDay(revision.endsAt);
    return first === null && last === null
      ? null
      : `${first === null ? '…' : dayLabel(first)} – ${last === null ? '…' : dayLabel(last)}`;
  }

  private async ensure(revisionNo: number): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null || this.loaded().has(revisionNo)) {
      return;
    }
    try {
      const revision = await this.api.revision(scope, this.itemId(), revisionNo);
      this.loaded.update((current) => new Map(current).set(revisionNo, revision));
    } catch (failure) {
      this.error.set(this.describe(failure));
    }
  }

  private describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      const problem = failure.fieldErrors[0];
      if (problem !== undefined) {
        return problemText((key, values) => this.i18n.t(key, values), {
          field: problem.field,
          code: problem.code,
          message: problem.message ?? '',
        });
      }
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('marketing.content.history.failed');
  }
}
