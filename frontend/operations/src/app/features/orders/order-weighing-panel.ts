import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { formatMoney } from '../../core/format/money';
import { formatWeight } from '../../core/format/quantity';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { OrderLine } from './order-detail';
import { describeApiError } from './order-errors';
import { ActualWeightResult, OrderWeighingApi } from './order-weighing-api';

/** `CatchweightReconciliationService.WEIGHABLE`: the kitchen is making it, or it is on the pass. */
const WEIGHABLE_STATUSES: ReadonlySet<string> = new Set(['CONFIRMED', 'PREPARING', 'READY']);

/** `OperationsOrderWeighingController.ActualWeightRequest`'s `@Max`. */
const MAX_GRAMS = 10_000_000;

/** What a refusal's `reason` is worth to an operator at the scale. */
const REFUSAL_MESSAGE: Readonly<Record<string, MessageKey>> = {
  PAYMENT_ALREADY_TAKEN: 'orders.weigh.error.paymentTaken',
  ORDER_NOT_WEIGHABLE: 'orders.weigh.error.notWeighable',
  CATCHWEIGHT_PRICE_CHANGED: 'orders.weigh.error.priceChanged',
  ORDER_REPRICE_DRIFT: 'orders.weigh.error.priceChanged',
  SETTLEMENT_NOT_RESTATABLE: 'orders.weigh.error.settlement',
  REPRICE_QUOTE_LAPSED: 'orders.weigh.error.lapsed',
};

/**
 * ADR 0137, gap map row 4.2c — the scale at the pass. Every line of an order that is sold by
 * weight, with what it was estimated at or weighed at, and a box to record the weight.
 *
 * **Why it exists at all.** A weighed item is quoted at its nominal weight and charged at the
 * weight the kitchen reads at handover; until that reading is recorded the order's amount is an
 * estimate and the platform will not let the order leave the pass (`CATCHWEIGHT_NOT_RECONCILED`).
 * The reading is the whole line's, all its units together — a scale reads one pan, not a
 * quantity — and the platform re-prices the order from it, so this panel never computes money:
 * it shows what the platform answered.
 *
 * Self-contained — the order detail and the expo pass both embed it — so it owns its write and
 * reports one fact upward: the order changed ({@link orderChanged}), after which its host reads
 * the order again. The version it writes under is the host's, or the one the last weighing
 * returned if that is newer, so weighing two lines in a row does not need a re-read in between.
 */
@Component({
  selector: 'q-order-weighing-panel',
  imports: [TPipe],
  templateUrl: './order-weighing-panel.html',
  styleUrl: './order-weighing-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderWeighingPanel {
  private readonly api = inject(OrderWeighingApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly orderId = input.required<string>();
  /** The order's version as the host last read it. */
  readonly orderVersion = input.required<number>();
  readonly status = input.required<string>();
  readonly lines = input.required<readonly OrderLine[]>();
  readonly currency = input.required<string>();

  /** A weight was recorded, or the order turned out to have changed: the host should read it again. */
  readonly orderChanged = output<void>();

  /** What the operator typed per line; a line with no entry shows the weight it already has. */
  private readonly typed = signal<Readonly<Record<string, string>>>({});
  protected readonly busyLineId = signal<string | null>(null);
  protected readonly results = signal<Readonly<Record<string, string>>>({});
  protected readonly errors = signal<Readonly<Record<string, string>>>({});
  private readonly returnedVersion = signal<number | null>(null);

  protected readonly rows = computed(() => this.lines().filter((line) => line.catchweight));
  protected readonly weighable = computed(() => WEIGHABLE_STATUSES.has(this.status()));
  private readonly version = computed(() =>
    Math.max(this.orderVersion(), this.returnedVersion() ?? -1),
  );

  constructor() {
    // The detail pane is reused across orders under one route: a weight typed for the last one
    // must not carry to the next.
    effect(() => {
      this.orderId();
      untracked(() => {
        this.typed.set({});
        this.results.set({});
        this.errors.set({});
        this.returnedVersion.set(null);
      });
    });
  }

  protected boxValue(line: OrderLine): string {
    const typed = this.typed()[line.lineId];
    if (typed !== undefined) {
      return typed;
    }
    const actual = line.catchweight?.actualWeightGrams;
    return actual == null ? '' : String(actual);
  }

  protected setTyped(line: OrderLine, value: string): void {
    this.typed.update((current) => ({ ...current, [line.lineId]: value }));
    this.clear(line.lineId);
  }

  /** The grams in the box, or `null` when it is not a whole number above zero. */
  protected grams(line: OrderLine): number | null {
    const text = this.boxValue(line).trim();
    if (!/^\d+$/.test(text)) {
      return null;
    }
    const value = Number.parseInt(text, 10);
    return value > 0 && value <= MAX_GRAMS ? value : null;
  }

  /** A box that holds something that is not a weight; an empty box is not an error, just not ready. */
  protected showInputError(line: OrderLine): boolean {
    return this.boxValue(line).trim() !== '' && this.grams(line) === null;
  }

  protected canSave(line: OrderLine): boolean {
    return this.weighable() && this.busyLineId() === null && this.grams(line) !== null;
  }

  protected estimateText(line: OrderLine): string {
    return this.i18n.t('orders.weigh.state.pending', {
      weight: formatWeight(
        line.quantity * (line.catchweight?.nominalGramsPerUnit ?? 0),
        this.i18n.locale(),
      ),
    });
  }

  protected weighedText(line: OrderLine): string {
    return this.i18n.t('orders.weigh.state.done', {
      weight: formatWeight(line.catchweight?.actualWeightGrams ?? 0, this.i18n.locale()),
    });
  }

  protected estimateGrams(line: OrderLine): string {
    return String(Math.round(line.quantity * (line.catchweight?.nominalGramsPerUnit ?? 0)));
  }

  protected async save(line: OrderLine): Promise<void> {
    const grams = this.grams(line);
    if (grams === null || !this.canSave(line)) {
      return;
    }
    this.busyLineId.set(line.lineId);
    this.clear(line.lineId);
    try {
      const result = await firstValueFrom(
        this.api.captureActualWeight(
          this.scope(),
          this.orderId(),
          line.lineId,
          grams,
          this.version(),
        ),
      );
      this.returnedVersion.set(result.orderVersion);
      this.typed.update((current) => {
        const { [line.lineId]: _written, ...rest } = current;
        return rest;
      });
      this.results.update((current) => ({
        ...current,
        [line.lineId]: this.resultText(result),
      }));
      this.orderChanged.emit();
    } catch (error) {
      this.fail(line.lineId, error);
    } finally {
      this.busyLineId.set(null);
    }
  }

  private resultText(result: ActualWeightResult): string {
    const locale = this.i18n.locale();
    const weight = formatWeight(result.actualWeightGrams, locale);
    if (!result.changed) {
      return this.i18n.t('orders.weigh.result.same', { weight });
    }
    if (result.deltaTotalMinor === 0) {
      return this.i18n.t('orders.weigh.result.unchanged', { weight });
    }
    const sign = result.deltaTotalMinor > 0 ? '+' : '';
    return this.i18n.t('orders.weigh.result.changed', {
      weight,
      total: formatMoney({ amountMinor: result.totalMinor, currency: this.currency() }, locale, {
        withUnit: true,
      }),
      delta: `${sign}${formatMoney(
        { amountMinor: result.deltaTotalMinor, currency: this.currency() },
        locale,
      )}`,
    });
  }

  private fail(lineId: string, error: unknown): void {
    if (!(error instanceof ApiError)) {
      throw error;
    }
    if (error.code === ApiErrorCode.STALE_VERSION) {
      this.setError(lineId, this.i18n.t('orders.weigh.error.stale'));
      // The order moved under the operator: whoever hosts this reads it again.
      this.orderChanged.emit();
      return;
    }
    const reason = error.problem?.['reason'];
    const key = typeof reason === 'string' ? REFUSAL_MESSAGE[reason] : undefined;
    this.setError(
      lineId,
      key ? this.i18n.t(key) : describeApiError(error, (k, values) => this.i18n.t(k, values)),
    );
  }

  private setError(lineId: string, text: string): void {
    this.errors.update((current) => ({ ...current, [lineId]: text }));
  }

  private clear(lineId: string): void {
    const without = (current: Readonly<Record<string, string>>) => {
      const { [lineId]: _dropped, ...rest } = current;
      return rest;
    };
    this.errors.update(without);
    this.results.update(without);
  }
}
