import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { PromotionsApi } from '../marketing/promotions/promotions-api';
import { describeApiError } from './order-errors';
import {
  OrderRequoteApi,
  RequoteChange,
  RequoteFinding,
  RequotePromotionChange,
} from './order-requote-api';

/** The branch's own clock is not on the order summary yet; every orders panel formats in the pilot's zone. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

const CHANGE_KEYS: Readonly<Record<RequoteChange, MessageKey>> = {
  DROPPED: 'orders.detail.requote.change.DROPPED',
  GAINED: 'orders.detail.requote.change.GAINED',
  CHANGED: 'orders.detail.requote.change.CHANGED',
};

/** One finding as the panel prints it: every sentence and amount already formatted. */
interface FindingRow {
  readonly id: string;
  readonly outcome: RequoteFinding['outcome'];
  readonly heading: string;
  readonly judgedAt: string;
  readonly totals: string | null;
  readonly delta: string | null;
  readonly refusal: string | null;
  readonly changes: readonly { readonly id: string; readonly text: string }[];
}

/**
 * Promotions at the checkpoint (ADR 0140, ADR 0019, gap-map row `6.1`): what the platform found when it
 * priced a scheduled order's basket again, and the operator's re-check.
 *
 * Evidence, not an action. The order keeps the price the guest was quoted; a finding that says the price
 * would change is for a person to act on, by phoning the guest or cancelling with the order's own cancel
 * path. The panel therefore has two things only, the findings newest first and the button that asks for
 * another, and it says so in words so that nobody expects the button to reprice anything.
 *
 * The promotion's own name is read best-effort from the brand's promotion list; an operator without
 * `pricing.read` (or a promotion since archived out of that list, or a customer's one-off code, which is
 * not a promotion at all) sees "a promotion" and the short reference instead of a name.
 */
@Component({
  selector: 'q-order-requote-panel',
  imports: [TPipe],
  templateUrl: './order-requote-panel.html',
  styleUrl: './order-requote-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderRequotePanel {
  private readonly api = inject(OrderRequoteApi);
  private readonly promotions = inject(PromotionsApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly orderId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly findings = signal<readonly RequoteFinding[]>([]);
  protected readonly names = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly checking = signal(false);
  protected readonly checkError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const scope = this.scope();
      const orderId = this.orderId();
      void this.load(scope, orderId);
    });
  }

  protected rows(): readonly FindingRow[] {
    return this.findings().map((finding) => this.rowOf(finding));
  }

  private async load(scope: LocationScope, orderId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(false);
    try {
      this.findings.set(await this.api.findings(scope, orderId));
    } catch (error) {
      if (error instanceof ApiError) {
        this.loadError.set(true);
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
    void this.loadNames(scope);
  }

  private async loadNames(scope: LocationScope): Promise<void> {
    try {
      const list = await this.promotions.list({ tenantId: scope.tenantId, brandId: scope.brandId });
      this.names.set(new Map(list.map((promotion) => [promotion.promotionId, promotion.name])));
    } catch {
      // Names are a courtesy; the findings stand without them.
    }
  }

  protected async recheck(): Promise<void> {
    this.checking.set(true);
    this.checkError.set(null);
    try {
      const finding = await this.api.recheck(this.scope(), this.orderId());
      this.findings.update((current) => [
        finding,
        ...current.filter((entry) => entry.id !== finding.id),
      ]);
    } catch (error) {
      if (error instanceof ApiError) {
        this.checkError.set(
          error.problem?.['reason'] === 'NOT_AWAITING_CHECKPOINT'
            ? this.i18n.t('orders.detail.requote.notAwaiting')
            : describeApiError(error, (key, values) => this.i18n.t(key, values)),
        );
      } else {
        throw error;
      }
    } finally {
      this.checking.set(false);
    }
  }

  private rowOf(finding: RequoteFinding): FindingRow {
    const money = (minor: number): string =>
      formatMoney({ amountMinor: minor, currency: finding.currency }, this.i18n.locale(), {
        withUnit: true,
      });
    const requote = finding.requoteTotalMinor;
    const delta = finding.deltaTotalMinor;
    return {
      id: finding.id,
      outcome: finding.outcome,
      heading: this.i18n.t(
        finding.trigger === 'CHECKPOINT'
          ? 'orders.detail.requote.trigger.CHECKPOINT'
          : 'orders.detail.requote.trigger.OPERATOR',
      ),
      judgedAt: this.i18n.t('orders.detail.requote.judgedAt', {
        time: formatDateTime(new Date(finding.checkpointAt), PLACEHOLDER_TIME_ZONE),
      }),
      totals:
        requote === null
          ? null
          : this.i18n.t('orders.detail.requote.totals', {
              held: money(finding.heldTotalMinor),
              requote: money(requote),
            }),
      delta:
        delta === null || delta === 0
          ? null
          : this.i18n.t(
              delta > 0 ? 'orders.detail.requote.delta.up' : 'orders.detail.requote.delta.down',
              { amount: money(Math.abs(delta)) },
            ),
      refusal: finding.refusalCode
        ? this.i18n.t('orders.detail.requote.refusal', { code: finding.refusalCode })
        : null,
      changes: finding.promotionChanges.map((change) => ({
        id: change.promotionId,
        text: this.changeText(change, money),
      })),
    };
  }

  private changeText(change: RequotePromotionChange, money: (minor: number) => string): string {
    return this.i18n.t('orders.detail.requote.change.line', {
      promotion: this.names().get(change.promotionId) ?? this.fallbackName(change.promotionId),
      change: this.i18n.t(CHANGE_KEYS[change.change]),
      held: money(change.heldMinor),
      requote: money(change.requoteMinor),
    });
  }

  private fallbackName(promotionId: string): string {
    return this.i18n.t('orders.detail.requote.promotionFallback', {
      reference: promotionId.slice(0, 8),
    });
  }

  protected outcomeLabel(outcome: RequoteFinding['outcome']): string {
    return this.i18n.t(`orders.detail.requote.outcome.${outcome}` as MessageKey);
  }
}
