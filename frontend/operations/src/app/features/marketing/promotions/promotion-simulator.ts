import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { BrandScope } from '../../../core/api/catalog-paths';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { ConditionFixedValue, ConditionTypeDescriptor } from '../../../shared/ui/condition-types';
import { ExternalRuleResult, RuleSimulator } from '../../../shared/ui/rule-simulator';
import { describeApiError } from '../../orders/order-errors';
import { FULFILLMENT_MODES, localInputToIso } from './promotion-draft';
import {
  PromotionBody,
  PromotionsApi,
  SimulationRequest,
  SimulationResult,
  TraceEntry,
} from './promotions-api';

/** One cart line the operator is building. */
interface CartLine {
  readonly id: string;
  readonly variantId: string;
  readonly label: string;
  readonly quantity: number;
}

/** Which stored definition version of one promotion to replay in place of its current one. */
export interface ReplayChoice {
  readonly promotionId: string;
  readonly code: string;
  /** Recorded definition versions, newest first. */
  readonly versions: readonly number[];
}

/**
 * The promotion simulator (ADR 0140, row `X.25`'s intended consumer of
 * `q-rule-simulator`): a synthetic cart priced by the **real engine**, with a
 * decision trace for every promotion in the brand.
 *
 * Unlike the client-side dry run `q-rule-simulator` does for automations, this
 * asks the server (`POST .../promotions/simulate`), which runs the same price book,
 * tax profile, delivery resolution and promotion inputs a real quote runs and
 * writes no quote, no redemption and no counter. The verdicts it returns are handed
 * to `q-rule-simulator` in its engine mode, so the list reads the same as it does
 * everywhere else in the console while the answer comes from the one evaluator.
 *
 * **The customer is facts, never an account.** The order number of the customer at
 * the brand, at the channel, and the audiences they are in: nothing identifies
 * anybody, so a read-only screen holds no personal history.
 *
 * **Unsaved work can be tried.** With a candidate (the draft being edited, if it
 * validates) the engine prices the cart as if that promotion existed; with a replay
 * choice it prices with an older recorded version of one promotion, which is how a
 * marketer explains an old receipt against the rule that priced it.
 */
@Component({
  selector: 'q-promotion-simulator',
  imports: [TPipe, Combobox, RuleSimulator],
  templateUrl: './promotion-simulator.html',
  styleUrl: './promotion-simulator.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromotionSimulator {
  private readonly api = inject(PromotionsApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  readonly catalogue = input.required<readonly ConditionTypeDescriptor[]>();
  readonly locations = input.required<readonly ConditionFixedValue[]>();
  readonly channels = input.required<readonly ConditionFixedValue[]>();
  readonly paymentMethods = input.required<readonly ConditionFixedValue[]>();
  readonly segments = input.required<readonly ConditionFixedValue[]>();
  /** Dishes at {@link menuLocationId}, the only ones the engine can price there. */
  readonly variants = input.required<readonly ConditionFixedValue[]>();
  readonly menuLocationId = input<string | null>(null);
  readonly menuLocationChange = output<string>();
  /** The draft being edited, ready to try; `null` when none validates yet. */
  readonly candidate = input<PromotionBody | null>(null);
  /** The open promotion's recorded versions, for a replay. */
  readonly replay = input<ReplayChoice | null>(null);

  protected readonly modes = FULFILLMENT_MODES;

  protected readonly channelCode = signal('');
  protected readonly mode = signal<string>('PICKUP');
  protected readonly paymentMethodCode = signal('');
  protected readonly serviceLocal = signal('');
  protected readonly couponCode = signal('');
  protected readonly latitude = signal('');
  protected readonly longitude = signal('');
  protected readonly brandOrderPosition = signal('');
  protected readonly channelOrderPosition = signal('');
  protected readonly segmentIds = signal<readonly string[]>([]);
  protected readonly useCandidate = signal(false);
  protected readonly replayVersion = signal('');

  protected readonly lines = signal<readonly CartLine[]>([]);
  protected readonly lineQuery = signal('');

  protected readonly running = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<SimulationResult | null>(null);

  /** Options the line search offers: a bounded slice of the branch's dishes matching the typed text. */
  protected readonly lineOptions = computed<readonly ComboboxOption[]>(() => {
    const needle = this.lineQuery().trim().toLocaleLowerCase();
    if (needle === '') {
      return [];
    }
    return this.variants()
      .filter((option) => option.label.toLocaleLowerCase().includes(needle))
      .slice(0, 12)
      .map((option) => ({ id: option.value, label: option.label }));
  });

  protected readonly canRun = computed(
    () =>
      !this.running() &&
      this.menuLocationId() !== null &&
      this.channelCode() !== '' &&
      this.lines().length > 0,
  );

  protected readonly traceLabels = computed(
    () => new Map((this.result()?.trace ?? []).map((entry) => [entry.promotionId, entry.code])),
  );

  /** The engine's verdicts as `q-rule-simulator` shows them: matched when it applied, otherwise why not. */
  protected readonly verdicts = computed<readonly ExternalRuleResult[]>(() => {
    const result = this.result();
    if (!result) {
      return [];
    }
    return result.trace.map((entry): ExternalRuleResult => ({
      id: entry.promotionId,
      label: entry.code,
      state: entry.verdict === 'APPLIED' ? 'matched' : 'unmatched',
      detail: this.describeVerdict(entry, result.currency),
    }));
  });

  protected money(amountMinor: number): string {
    const currency = this.result()?.currency ?? 'UZS';
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected adjustmentLabel(adjustment: { type: string; promotionId: string | null }): string {
    const code = adjustment.promotionId ? this.traceLabels().get(adjustment.promotionId) : null;
    const type = this.i18n.t(`marketing.promotions.adjustment.${adjustment.type}` as MessageKey);
    return code ? `${type} · ${code}` : type;
  }

  protected modeLabelKey(mode: string): MessageKey {
    return `marketing.promotions.fulfillment.${mode}` as MessageKey;
  }

  private describeVerdict(entry: TraceEntry, currency: string): string {
    const t = (key: MessageKey, values?: Record<string, string | number>): string =>
      this.i18n.t(key, values);
    const names = entry.lostTo.map((id) => this.traceLabels().get(id) ?? id).join(', ');
    switch (entry.verdict) {
      case 'APPLIED':
        return t('marketing.promotions.verdict.APPLIED', {
          amount: formatMoney({ amountMinor: entry.benefitMinor, currency }, this.i18n.locale(), {
            withUnit: true,
          }),
        });
      case 'CONDITION_FAILED':
        return entry.conditionSequence === null
          ? t('marketing.promotions.verdict.CONDITION_FAILED.any')
          : t('marketing.promotions.verdict.CONDITION_FAILED', { n: entry.conditionSequence });
      case 'LOST_TO':
        return t('marketing.promotions.verdict.LOST_TO', { others: names });
      case 'SUPPRESSED_BY_EXCLUSIVE':
        return t('marketing.promotions.verdict.SUPPRESSED_BY_EXCLUSIVE', { others: names });
      default:
        return t(`marketing.promotions.verdict.${entry.verdict}` as MessageKey);
    }
  }

  // ------------------------------------------------------------------ the cart

  protected onLineQuery(text: string): void {
    this.lineQuery.set(text);
  }

  protected addLine(option: ComboboxOption): void {
    this.lines.update((current) => {
      const existing = current.find((line) => line.variantId === option.id);
      if (existing) {
        return current.map((line) =>
          line === existing ? { ...line, quantity: line.quantity + 1 } : line,
        );
      }
      return [
        ...current,
        {
          id: `line-${current.length + 1}-${option.id}`,
          variantId: option.id,
          label: option.label,
          quantity: 1,
        },
      ];
    });
    this.lineQuery.set('');
  }

  protected setQuantity(id: string, quantity: number): void {
    this.lines.update((current) =>
      current.map((line) =>
        line.id === id ? { ...line, quantity: Math.max(1, Math.min(999, quantity)) } : line,
      ),
    );
  }

  protected removeLine(id: string): void {
    this.lines.update((current) => current.filter((line) => line.id !== id));
  }

  protected toggleSegment(id: string): void {
    this.segmentIds.update((current) =>
      current.includes(id) ? current.filter((entry) => entry !== id) : [...current, id],
    );
  }

  protected numberOrNull(text: string): number | null {
    const value = Number(text);
    return text.trim() !== '' && Number.isFinite(value) ? value : null;
  }

  // ------------------------------------------------------------------ the run

  /** The request the form stands for, or `null` when it is not runnable. Exposed for the spec. */
  buildRequest(): SimulationRequest | null {
    const location = this.menuLocationId();
    if (location === null || this.channelCode() === '' || this.lines().length === 0) {
      return null;
    }
    const latitude = this.numberOrNull(this.latitude());
    const longitude = this.numberOrNull(this.longitude());
    const brandPosition = this.numberOrNull(this.brandOrderPosition());
    const channelPosition = this.numberOrNull(this.channelOrderPosition());
    const replay = this.replay();
    const replayVersion = this.numberOrNull(this.replayVersion());
    return {
      locationId: location,
      channelCode: this.channelCode(),
      fulfillmentMode: this.mode(),
      paymentMethodCode: this.paymentMethodCode() === '' ? null : this.paymentMethodCode(),
      serviceInstant: localInputToIso(this.serviceLocal()),
      lines: this.lines().map((line) => ({
        lineId: line.id,
        variantId: line.variantId,
        quantity: line.quantity,
        modifierOptionIds: null,
      })),
      presentedCouponCode: this.couponCode().trim() === '' ? null : this.couponCode().trim(),
      destination:
        this.mode() === 'DELIVERY' && latitude !== null && longitude !== null
          ? { latitude, longitude }
          : null,
      facts:
        brandPosition !== null || channelPosition !== null || this.segmentIds().length > 0
          ? {
              brandOrderPosition: brandPosition,
              channelOrderPosition: channelPosition,
              segments: this.segmentIds().length > 0 ? this.segmentIds() : null,
            }
          : null,
      candidate: this.useCandidate() ? this.candidate() : null,
      definitionVersions:
        replay && replayVersion !== null
          ? [{ promotionId: replay.promotionId, definitionVersion: replayVersion }]
          : null,
    };
  }

  protected async run(): Promise<void> {
    const request = this.buildRequest();
    if (request === null) {
      return;
    }
    this.running.set(true);
    this.error.set(null);
    try {
      this.result.set(await this.api.simulate(this.scope(), request));
    } catch (error) {
      this.result.set(null);
      this.error.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.running.set(false);
    }
  }
}
