import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { ConditionBuilder } from '../../../shared/ui/condition-builder';
import {
  ConditionGroup,
  ConditionRow,
  ConditionTypeDescriptor,
  ConditionValueKind,
  conditionRowIsWorkable,
  descriptorFor,
  emptyConditionRow,
} from '../../../shared/ui/condition-types';
import { nextDomId } from '../../../shared/ui/overlay';
import {
  AudienceDetail,
  AudiencePredicate,
  AudienceSummary,
  MarketingChannel,
  PredicateOperator,
  PredicateType,
  RefusalBreakdown,
  SegmentsApi,
  SnapshotResult,
} from './segments-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * The closed predicate catalogue (ADR 0044, `PredicateType`), expressed as a
 * `q-condition-builder` catalogue rather than hand-rolled markup. `AUDIENCE_MEMBERSHIP`
 * stays excluded — it needs an audience picker this wave does not build, same
 * scoping note the previous hand-rolled builder carried.
 */
const CATALOGUE: readonly ConditionTypeDescriptor[] = [
  {
    type: 'RECENCY_DAYS',
    labelKey: 'customers.segments.predicate.type.RECENCY_DAYS',
    valueKind: 'NUMERIC',
  },
  {
    type: 'ORDER_COUNT',
    labelKey: 'customers.segments.predicate.type.ORDER_COUNT',
    valueKind: 'NUMERIC',
  },
  {
    type: 'COMPLETED_ORDER_COUNT',
    labelKey: 'customers.segments.predicate.type.COMPLETED_ORDER_COUNT',
    valueKind: 'NUMERIC',
  },
  {
    type: 'NET_SPEND_MINOR',
    labelKey: 'customers.segments.predicate.type.NET_SPEND_MINOR',
    valueKind: 'NUMERIC',
  },
  {
    type: 'AVERAGE_CHECK_MINOR',
    labelKey: 'customers.segments.predicate.type.AVERAGE_CHECK_MINOR',
    valueKind: 'NUMERIC',
  },
  {
    type: 'ACQUISITION_CHANNEL',
    labelKey: 'customers.segments.predicate.type.ACQUISITION_CHANNEL',
    valueKind: 'TEXT_SET',
  },
  {
    type: 'REGISTERED_BETWEEN',
    labelKey: 'customers.segments.predicate.type.REGISTERED_BETWEEN',
    valueKind: 'DATE_RANGE',
  },
  {
    type: 'BIRTHDAY_WITHIN_DAYS',
    labelKey: 'customers.segments.predicate.type.BIRTHDAY_WITHIN_DAYS',
    valueKind: 'NUMERIC',
  },
  {
    type: 'PREFERRED_LOCALE',
    labelKey: 'customers.segments.predicate.type.PREFERRED_LOCALE',
    valueKind: 'TEXT_SET',
  },
];

/**
 * Every `RefusalReason` a snapshot build can produce, in the fixed order
 * `RefusalReason.java` declares them — mirrors the order the five
 * subtractions actually run in, so the breakdown reads as the checks
 * themselves rather than an arbitrary sort. `CAMPAIGN_HALTED` is left out: it
 * can only ever be a send-time reason, never one a snapshot build reaches.
 */
const REFUSAL_REASONS: readonly string[] = [
  'ACCOUNT_NOT_ACTIVE',
  'CONSENT_WITHHELD',
  'SUPPRESSED',
  'FREQUENCY_CAP_REACHED',
  'NO_VERIFIED_ENDPOINT',
];

/** Wraps a flat row list in the one `AND` group `AudiencePredicate`'s combinator-free shape needs — `q-condition-builder`'s model always carries at least one group. */
function singleGroup(rows: readonly ConditionRow[]): readonly ConditionGroup[] {
  return [{ id: nextDomId('condition-group'), combinator: 'AND', rows }];
}

/**
 * 5.3 Segments — the RFM builder over ADR 0044's audiences
 * (`frontend-information-architecture.md` §5.3, "the screen Delever does not
 * have… feeding campaigns directly, rather than an ad-hoc filter re-typed per
 * send").
 *
 * **What is real here.** Every predicate, every list row and every snapshot
 * count is `OperationsMarketingController`'s own audience surface — nothing
 * here invents an aggregate or reaches into a customer's contact details.
 * `AudienceService`'s own doc is explicit that a predicate reads
 * `marketing.customer_metrics` and the result never leaves the platform.
 *
 * **What is scoped down for this wave.** `AUDIENCE_MEMBERSHIP` (nesting one
 * segment inside another) is in the closed catalogue but not offered in this
 * builder — it needs an audience picker this wave does not build. Handing a
 * segment to a campaign is Marketing 6.4's own screen (a sibling wave); this
 * page links out to the audience id rather than duplicating campaign
 * authoring.
 *
 * **The predicate editor is `q-condition-builder` (ADR 0101, row `X.25`), not
 * hand-rolled markup.** This page used to be the only place in the console
 * that could author a rule at all, with its own copy of the predicate-row
 * form; `campaigns-page.ts`'s "create audience" panel independently grew a
 * second, near-identical copy over the same `PredicateType` catalogue
 * (`audience-predicates.ts`) and was left alone here — repointing it is a
 * separate, larger change outside this wave's named file scope; see the wave
 * report for `T21`. This page carries exactly one flat `AND` group
 * (`allowGroups` left at its default `false`), since `AudiencePredicate` has
 * no combinator of its own — the grouping the shared component offers is for
 * a future consumer whose domain actually has OR logic (a promotion's
 * eligibility, a dispatch rule's fallback).
 */
@Component({
  selector: 'q-segments-page',
  imports: [TPipe, ConditionBuilder],
  templateUrl: './segments-page.html',
  styleUrl: './segments-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SegmentsPage {
  private readonly api = inject(SegmentsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly segments = signal<readonly AudienceSummary[]>([]);

  protected readonly catalogue = CATALOGUE;

  // ---------------------------------------------------------------- builder
  protected readonly builderOpen = signal(false);
  protected readonly editingAudienceId = signal<string | null>(null);
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly groups = signal<readonly ConditionGroup[]>(
    singleGroup([emptyConditionRow(CATALOGUE)]),
  );
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  // ---------------------------------------------------------------- snapshot
  protected readonly snapshottingId = signal<string | null>(null);
  protected readonly snapshotChannel = signal<MarketingChannel>('SMS');
  protected readonly snapshotBusy = signal(false);
  protected readonly snapshotError = signal<string | null>(null);
  protected readonly snapshotResult = signal<SnapshotResult | null>(null);
  protected readonly refusalReasons = REFUSAL_REASONS;

  protected readonly exportBusy = signal(false);
  protected readonly exportError = signal<string | null>(null);
  protected readonly exportedCount = signal<number | null>(null);

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.state.set(this.brand.denied() ? 'denied' : 'error');
      return;
    }
    try {
      this.segments.set(await this.api.list(scope));
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  // ------------------------------------------------------------- the builder

  protected openCreate(): void {
    this.editingAudienceId.set(null);
    this.name.set('');
    this.description.set('');
    this.groups.set(singleGroup([emptyConditionRow(CATALOGUE)]));
    this.saveError.set(null);
    this.builderOpen.set(true);
  }

  protected async openEdit(summary: AudienceSummary): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.saveError.set(null);
    try {
      const detail: AudienceDetail = await this.api.detail(scope, summary.audienceId);
      this.editingAudienceId.set(detail.audienceId);
      this.name.set(detail.name);
      this.description.set(detail.description ?? '');
      this.groups.set(
        singleGroup(
          detail.predicates.length > 0
            ? detail.predicates.map(toRow)
            : [emptyConditionRow(CATALOGUE)],
        ),
      );
      this.builderOpen.set(true);
    } catch (error) {
      this.loadErrorText.set(this.describe(error));
    }
  }

  protected closeBuilder(): void {
    this.builderOpen.set(false);
  }

  protected canSave(): boolean {
    const rows = this.groups()[0].rows;
    return (
      !this.saving() &&
      this.name().trim().length > 0 &&
      rows.length > 0 &&
      rows.every((row) => conditionRowIsWorkable(row, descriptorFor(CATALOGUE, row.type).valueKind))
    );
  }

  protected async save(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const predicates = this.groups()[0].rows.map((row) =>
      toPredicate(row, descriptorFor(CATALOGUE, row.type).valueKind),
    );
    try {
      const editingId = this.editingAudienceId();
      if (editingId) {
        await this.api.redefine(scope, editingId, predicates);
      } else {
        await this.api.define(scope, {
          name: this.name().trim(),
          description: this.description().trim() || null,
          predicates,
        });
      }
      this.builderOpen.set(false);
      await this.load();
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  // ------------------------------------------------------------------ snapshot

  protected startSnapshot(summary: AudienceSummary): void {
    this.snapshottingId.set(summary.audienceId);
    this.snapshotChannel.set('SMS');
    this.snapshotError.set(null);
    this.snapshotResult.set(null);
    this.exportError.set(null);
    this.exportedCount.set(null);
  }

  protected cancelSnapshot(): void {
    this.snapshottingId.set(null);
    this.snapshotResult.set(null);
    this.exportedCount.set(null);
  }

  protected async confirmSnapshot(): Promise<void> {
    const scope = this.brand.scope();
    const audienceId = this.snapshottingId();
    if (!scope || !audienceId || this.snapshotBusy()) {
      return;
    }
    this.snapshotBusy.set(true);
    this.snapshotError.set(null);
    try {
      // A real, recorded consent purpose — not a string this page invented.
      // `MarketingEligibility` matches this exactly against what
      // `ConsentService` recorded a decision under, and every consent
      // decision on this platform (storefront checkbox, the SendPulse
      // import, a campaign send) is recorded under this one purpose. Sending
      // anything else — including a human-readable description of what this
      // screen is doing — makes every candidate fail the match and read as
      // CONSENT_WITHHELD, which is the bug this page used to ship with.
      const result = await this.api.buildSnapshot(
        scope,
        audienceId,
        this.snapshotChannel(),
        SegmentsPage.CONSENT_PURPOSE,
      );
      // The panel stays open. Closing it here used to discard `snapshotResult`
      // the same tick it arrived, so the reach an operator had just asked for
      // was computed, stored and thrown away before it was ever rendered. The
      // refreshed row's last-reach column carries the member count too, but a
      // cell that changes quietly in a table of segments answers nothing —
      // and did not, on its own, distinguish a broken audience from a real
      // zero either.
      this.snapshotResult.set(result);
      await this.load();
    } catch (error) {
      this.snapshotError.set(this.describe(error));
    } finally {
      this.snapshotBusy.set(false);
    }
  }

  /**
   * The tenant-wide marketing consent purpose (`ConsentTypeService.DEFAULTS`,
   * `CampaignsPage`'s own default). Fixed and machine-facing, never a
   * description of what this screen is doing — see {@link confirmSnapshot}'s
   * own doc for why that distinction is the whole fix.
   */
  private static readonly CONSENT_PURPOSE = 'MARKETING_PROMOTIONS';

  // -------------------------------------------------------- refusal breakdown

  /** The reasons this snapshot actually excluded somebody under, in the fixed display order. */
  protected refusalRows(
    breakdown: RefusalBreakdown,
  ): ReadonlyArray<{ reason: string; count: number }> {
    return this.refusalReasons.map((reason) => ({ reason, count: breakdown[reason] ?? 0 }));
  }

  protected refusalReasonLabelKey(reason: string): MessageKey {
    return `customers.segments.snapshot.refusal.${reason}` as MessageKey;
  }

  // ------------------------------------------------------------------ export

  /**
   * Fixed, English, machine-facing purpose — not translated, the same reason
   * `CustomersPage.EXPORT_PURPOSE` is not: this is read by whoever reviews
   * the audit log, not the operator.
   */
  private static readonly EXPORT_PURPOSE =
    'Operations console: segment snapshot export from Customers 5.3';

  protected async exportCurrentSnapshot(): Promise<void> {
    const scope = this.brand.scope();
    const result = this.snapshotResult();
    if (!scope || !result || this.exportBusy()) {
      return;
    }
    this.exportBusy.set(true);
    this.exportError.set(null);
    try {
      const accountIds = await this.api.exportSnapshot(
        scope,
        result.snapshotId,
        SegmentsPage.EXPORT_PURPOSE,
      );
      this.exportedCount.set(accountIds.length);
      downloadAccountIdCsv(accountIds, `segment-${result.snapshotId}.csv`);
    } catch (error) {
      this.exportError.set(this.describe(error));
    } finally {
      this.exportBusy.set(false);
    }
  }

  // ------------------------------------------------------------------ format

  protected formatUpdatedAt(iso: string): string {
    return new Date(iso).toLocaleDateString(this.i18n.locale() === 'en' ? 'en-GB' : 'ru-RU');
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

/**
 * Builds the snapshot export as a browser-local CSV download — the same
 * "no server-side file" reasoning `customers-page.ts`'s own `downloadCsv`
 * carries: the export endpoint already returns the account ids as JSON in
 * one audited call, so there is nothing a second round trip would add.
 * Pseudonymous account ids only, never a name, phone or email — the export
 * endpoint carries none of those and cannot.
 */
function downloadAccountIdCsv(accountIds: readonly string[], filename: string): void {
  const lines = ['customerAccountId', ...accountIds];
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

function toRow(predicate: AudiencePredicate): ConditionRow {
  return {
    id: nextDomId('condition-row'),
    type: predicate.type,
    operator: predicate.operator,
    numericLow: predicate.numericLow === null ? '' : String(predicate.numericLow),
    numericHigh: predicate.numericHigh === null ? '' : String(predicate.numericHigh),
    dateLow: predicate.dateLow ?? '',
    dateHigh: predicate.dateHigh ?? '',
    textValues: predicate.textValues ? predicate.textValues.join(', ') : '',
    dayOfWeekValues: [],
  };
}

function toPredicate(row: ConditionRow, kind: ConditionValueKind): AudiencePredicate {
  switch (kind) {
    case 'NUMERIC':
      return {
        type: row.type as PredicateType,
        operator: row.operator as PredicateOperator,
        numericLow: row.numericLow.trim() ? Number(row.numericLow) : null,
        numericHigh:
          row.operator === 'BETWEEN' && row.numericHigh.trim() ? Number(row.numericHigh) : null,
        dateLow: null,
        dateHigh: null,
        textValues: null,
        audienceId: null,
      };
    case 'DATE_RANGE':
      return {
        type: row.type as PredicateType,
        operator: row.operator as PredicateOperator,
        numericLow: null,
        numericHigh: null,
        dateLow: row.dateLow || null,
        dateHigh: row.dateHigh || null,
        textValues: null,
        audienceId: null,
      };
    case 'TEXT_SET':
      return {
        type: row.type as PredicateType,
        operator: row.operator as PredicateOperator,
        numericLow: null,
        numericHigh: null,
        dateLow: null,
        dateHigh: null,
        textValues: row.textValues
          .split(',')
          .map((value) => value.trim())
          .filter((value) => value.length > 0),
        audienceId: null,
      };
    default:
      // Segments' own catalogue never offers MONEY_MINOR, PERCENT, DATE,
      // DAY_OF_WEEK_SET or REFERENCE — those exist for other consumers of
      // `q-condition-builder`, not for `AudiencePredicate`'s closed shape.
      throw new RangeError(`${kind} is not a value kind Customers 5.3's segment predicates use`);
  }
}
