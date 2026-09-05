import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  AudienceDetail,
  AudiencePredicate,
  AudienceSummary,
  MarketingChannel,
  PredicateOperator,
  PredicateType,
  SegmentsApi,
  allowedOperators,
} from './segments-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/** One predicate row in the builder, as plain editable strings — converted to {@link AudiencePredicate} on save. */
interface PredicateRow {
  type: PredicateType;
  operator: PredicateOperator;
  numericLow: string;
  numericHigh: string;
  dateLow: string;
  dateHigh: string;
  /** Comma-separated for `TEXT_SET`; the raw audience id for `AUDIENCE`. */
  textValues: string;
}

const PREDICATE_TYPES: readonly PredicateType[] = [
  'RECENCY_DAYS',
  'ORDER_COUNT',
  'COMPLETED_ORDER_COUNT',
  'NET_SPEND_MINOR',
  'AVERAGE_CHECK_MINOR',
  'ACQUISITION_CHANNEL',
  'REGISTERED_BETWEEN',
  'BIRTHDAY_WITHIN_DAYS',
  'PREFERRED_LOCALE',
];

const PREDICATE_TYPE_KEYS: Readonly<Record<PredicateType, MessageKey>> = {
  RECENCY_DAYS: 'customers.segments.predicate.type.RECENCY_DAYS',
  ORDER_COUNT: 'customers.segments.predicate.type.ORDER_COUNT',
  COMPLETED_ORDER_COUNT: 'customers.segments.predicate.type.COMPLETED_ORDER_COUNT',
  NET_SPEND_MINOR: 'customers.segments.predicate.type.NET_SPEND_MINOR',
  AVERAGE_CHECK_MINOR: 'customers.segments.predicate.type.AVERAGE_CHECK_MINOR',
  ACQUISITION_CHANNEL: 'customers.segments.predicate.type.ACQUISITION_CHANNEL',
  REGISTERED_BETWEEN: 'customers.segments.predicate.type.REGISTERED_BETWEEN',
  BIRTHDAY_WITHIN_DAYS: 'customers.segments.predicate.type.BIRTHDAY_WITHIN_DAYS',
  PREFERRED_LOCALE: 'customers.segments.predicate.type.PREFERRED_LOCALE',
  AUDIENCE_MEMBERSHIP: 'customers.segments.predicate.type.AUDIENCE_MEMBERSHIP',
};

const OPERATOR_KEYS: Readonly<Record<PredicateOperator, MessageKey>> = {
  AT_LEAST: 'customers.segments.operator.AT_LEAST',
  AT_MOST: 'customers.segments.operator.AT_MOST',
  BETWEEN: 'customers.segments.operator.BETWEEN',
  IN: 'customers.segments.operator.IN',
  NOT_IN: 'customers.segments.operator.NOT_IN',
};

function emptyRow(): PredicateRow {
  return {
    type: 'RECENCY_DAYS',
    operator: 'AT_LEAST',
    numericLow: '',
    numericHigh: '',
    dateLow: '',
    dateHigh: '',
    textValues: '',
  };
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
 */
@Component({
  selector: 'q-segments-page',
  imports: [TPipe],
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

  protected readonly predicateTypes = PREDICATE_TYPES;

  // ---------------------------------------------------------------- builder
  protected readonly builderOpen = signal(false);
  protected readonly editingAudienceId = signal<string | null>(null);
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly rows = signal<readonly PredicateRow[]>([emptyRow()]);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  // ---------------------------------------------------------------- snapshot
  protected readonly snapshottingId = signal<string | null>(null);
  protected readonly snapshotChannel = signal<MarketingChannel>('SMS');
  protected readonly snapshotBusy = signal(false);
  protected readonly snapshotError = signal<string | null>(null);
  protected readonly snapshotResult = signal<{ readonly audienceId: string; readonly members: number } | null>(
    null,
  );

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
    this.rows.set([emptyRow()]);
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
      this.rows.set(
        detail.predicates.length > 0 ? detail.predicates.map(toRow) : [emptyRow()],
      );
      this.builderOpen.set(true);
    } catch (error) {
      this.loadErrorText.set(this.describe(error));
    }
  }

  protected closeBuilder(): void {
    this.builderOpen.set(false);
  }

  protected addRow(): void {
    this.rows.update((current) => [...current, emptyRow()]);
  }

  protected removeRow(index: number): void {
    this.rows.update((current) => current.filter((_, i) => i !== index));
  }

  protected updateRow(index: number, patch: Partial<PredicateRow>): void {
    this.rows.update((current) =>
      current.map((row, i) => {
        if (i !== index) {
          return row;
        }
        const next = { ...row, ...patch };
        // Changing the type resets the operator to the first one it allows —
        // a stale BETWEEN left over from a numeric field makes no sense once
        // the type switches to a text-set predicate.
        if (patch.type && patch.type !== row.type) {
          next.operator = allowedOperators(patch.type)[0];
        }
        return next;
      }),
    );
  }

  protected operatorsFor(type: PredicateType): readonly PredicateOperator[] {
    return allowedOperators(type);
  }

  protected valueKindOf(type: PredicateType): 'NUMERIC' | 'DATE_RANGE' | 'TEXT_SET' {
    switch (type) {
      case 'ACQUISITION_CHANNEL':
      case 'PREFERRED_LOCALE':
        return 'TEXT_SET';
      case 'REGISTERED_BETWEEN':
        return 'DATE_RANGE';
      default:
        return 'NUMERIC';
    }
  }

  protected canSave(): boolean {
    return (
      !this.saving() &&
      this.name().trim().length > 0 &&
      this.rows().length > 0 &&
      this.rows().every((row) => rowIsWorkable(row, this.valueKindOf(row.type)))
    );
  }

  protected async save(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const predicates = this.rows().map((row) => toPredicate(row, this.valueKindOf(row.type)));
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
  }

  protected cancelSnapshot(): void {
    this.snapshottingId.set(null);
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
      const result = await this.api.buildSnapshot(
        scope,
        audienceId,
        this.snapshotChannel(),
        SegmentsPage.SNAPSHOT_PURPOSE,
      );
      this.snapshotResult.set({ audienceId, members: result.members });
      this.snapshottingId.set(null);
      await this.load();
    } catch (error) {
      this.snapshotError.set(this.describe(error));
    } finally {
      this.snapshotBusy.set(false);
    }
  }

  /** Fixed, machine-facing purpose — the same convention `CustomersPage.EXPORT_PURPOSE` uses, for the same reason. */
  private static readonly SNAPSHOT_PURPOSE = 'Operations console: segment snapshot from Customers 5.3';

  // ------------------------------------------------------------------ format

  protected typeLabel(type: PredicateType): string {
    return this.i18n.t(PREDICATE_TYPE_KEYS[type]);
  }

  protected operatorLabel(operator: PredicateOperator): string {
    return this.i18n.t(OPERATOR_KEYS[operator]);
  }

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

function toRow(predicate: AudiencePredicate): PredicateRow {
  return {
    type: predicate.type,
    operator: predicate.operator,
    numericLow: predicate.numericLow === null ? '' : String(predicate.numericLow),
    numericHigh: predicate.numericHigh === null ? '' : String(predicate.numericHigh),
    dateLow: predicate.dateLow ?? '',
    dateHigh: predicate.dateHigh ?? '',
    textValues: predicate.textValues ? predicate.textValues.join(', ') : '',
  };
}

function rowIsWorkable(row: PredicateRow, kind: 'NUMERIC' | 'DATE_RANGE' | 'TEXT_SET'): boolean {
  switch (kind) {
    case 'NUMERIC':
      if (row.numericLow.trim().length === 0) {
        return false;
      }
      return row.operator !== 'BETWEEN' || row.numericHigh.trim().length > 0;
    case 'DATE_RANGE':
      return row.dateLow.trim().length > 0 && row.dateHigh.trim().length > 0;
    case 'TEXT_SET':
      return row.textValues.trim().length > 0;
  }
}

function toPredicate(row: PredicateRow, kind: 'NUMERIC' | 'DATE_RANGE' | 'TEXT_SET'): AudiencePredicate {
  return {
    type: row.type,
    operator: row.operator,
    numericLow: kind === 'NUMERIC' && row.numericLow.trim() ? Number(row.numericLow) : null,
    numericHigh:
      kind === 'NUMERIC' && row.operator === 'BETWEEN' && row.numericHigh.trim()
        ? Number(row.numericHigh)
        : null,
    dateLow: kind === 'DATE_RANGE' ? row.dateLow || null : null,
    dateHigh: kind === 'DATE_RANGE' ? row.dateHigh || null : null,
    textValues:
      kind === 'TEXT_SET'
        ? row.textValues
            .split(',')
            .map((value) => value.trim())
            .filter((value) => value.length > 0)
        : null,
    audienceId: null,
  };
}
