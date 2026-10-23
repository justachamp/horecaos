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

import { LocationScope } from '../../../core/api/operations-paths';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import type { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { MoneyInput } from '../../../shared/ui/money-input';
import { NewOrderApi, OperatorRedemptionOffer, PlaceOrderRequest } from './new-order-api';

/**
 * What the points control hands the screen (ADR 0185): how many points the operator means to spend and
 * whether the screen may place the order with them.
 *
 * `ready` is true with no redemption (there is nothing to wait for), and with one only when the server has
 * answered *this* amount against *this* price and the operator has said the customer agreed. Until then
 * «Создать» must not send the amount: the screen would be asking the server to spend points it has not seen
 * accepted.
 */
export interface RedemptionSelection {
  /** Whole som; 0 when no points are being spent. */
  readonly requestedMinor: number;
  readonly ready: boolean;
  /** What the customer still pays in money once the accepted redemption is out of the total; null until `ready`. */
  readonly cashToCollectMinor: number | null;
}

/** What the screen asks the server about: the request the current server price answered, and the branch it goes to. */
export interface PointsAsk {
  readonly scope: LocationScope;
  readonly request: PlaceOrderRequest;
}

/** Nothing spent, nothing to wait for: the selection every order starts with. */
export const NO_REDEMPTION: RedemptionSelection = {
  requestedMinor: 0,
  ready: true,
  cashToCollectMinor: null,
};

/**
 * Refusals that mean "this brand or this customer has no points to talk about", which would be noise to the
 * person taking an order: nothing is shown. Any other refusal is something the customer can be told.
 */
const SILENT_REFUSALS: ReadonlySet<string> = new Set([
  'NO_ACCOUNT',
  'REDEMPTION_NOT_ENABLED',
  'REDEMPTION_NOT_WIRED',
]);

const REFUSAL_KEYS: Readonly<Record<string, MessageKey>> = {
  CHANNEL_NOT_ELIGIBLE: 'orders.newOrder.points.refusal.channel',
  ACCOUNT_NOT_ACTIVE: 'orders.newOrder.points.refusal.account',
  ORDER_NOT_ELIGIBLE: 'orders.newOrder.points.refusal.order',
  INSUFFICIENT_BALANCE: 'orders.newOrder.points.refusal.balance',
  REDEMPTION_BLOCKED_BY_PROMOTION: 'orders.newOrder.points.refusal.promotion',
};

/**
 * The New order screen's points control (X.1, ADR 0046, ADR 0185): the customer on the phone can pay part of the
 * order with their points, the operator says so, and the screen shows what is left to collect before «Создать».
 *
 * It asks the server -- never works the maximum out itself -- because the cap, the minimum order and the rule
 * that some money must always change hands are the brand's, and a second reading in the browser is how an
 * offer promises what the checkout then refuses. It asks only once the screen has a server price for the basket,
 * and again whenever the amount changes, so the «to pay in money» figure is the server's. It spends nothing:
 * the points are debited inside the transaction that creates the order, and only when the operator has ticked
 * that the customer agreed (an operator spending a customer's points is an attestation the order records).
 */
@Component({
  selector: 'q-new-order-points',
  imports: [MoneyInput, TPipe],
  templateUrl: './new-order-points.html',
  styleUrl: './new-order-points.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewOrderPoints {
  private readonly api = inject(NewOrderApi);
  private readonly i18n = inject(I18n);

  /** The customer on the phone. A different customer starts again: an amount agreed for one is no one else's. */
  readonly customerAccountId = input.required<string | null>();
  /** Null while the screen has no current server price for the basket; the control then waits. */
  readonly ask = input.required<PointsAsk | null>();

  readonly selectionChange = output<RedemptionSelection>();

  /** Waits this long after the last keystroke before asking, so typing 40 000 is not five requests. */
  private static readonly DEBOUNCE_MS = 250;

  protected readonly requestedMinor = signal(0);
  protected readonly agreed = signal(false);
  protected readonly answer = signal<OperatorRedemptionOffer | null>(null);
  /** The ask the answer belongs to: an answer for an earlier price must not release the order. */
  private readonly answeredFor = signal<PointsAsk | null>(null);
  /** The server could not be asked (offline, refused): the order can still be placed, without points. */
  protected readonly unavailable = signal(false);

  private sequence = 0;

  protected readonly offer = computed(() => this.answer()?.offer ?? null);

  /** Shown when there are points to talk about, or an amount already typed that the operator may need to lower. */
  protected readonly visible = computed(() => {
    const offer = this.offer();
    if (this.unavailable() && this.requestedMinor() > 0) {
      return true;
    }
    if (offer === null) {
      return false;
    }
    if (offer.redeemable || this.requestedMinor() > 0) {
      return true;
    }
    return offer.refusal !== null && !SILENT_REFUSALS.has(offer.refusal);
  });

  protected readonly refusalText = computed(() => {
    const offer = this.offer();
    if (offer === null || offer.redeemable || offer.refusal === null) {
      return null;
    }
    const key = REFUSAL_KEYS[offer.refusal];
    return key === undefined ? null : this.i18n.t(key);
  });

  protected readonly exceedsMaximum = computed(
    () => this.answer()?.requestedRefusal === 'EXCEEDS_MAXIMUM',
  );

  /** The server's own answer for the amount typed, on the price now on screen. */
  private readonly accepted = computed(() => {
    const requested = this.requestedMinor();
    const answer = this.answer();
    if (requested <= 0 || answer === null || this.answeredFor() !== this.ask()) {
      return null;
    }
    if (answer.requested?.amountMinor !== requested || answer.requestedRefusal) {
      return null;
    }
    return answer.cashToCollect ?? null;
  });

  protected readonly cashToCollect = computed(() => this.accepted());

  readonly selection = computed<RedemptionSelection>(() => {
    const requested = this.requestedMinor();
    if (requested <= 0) {
      return NO_REDEMPTION;
    }
    const cash = this.accepted();
    const ready = cash !== null && this.agreed();
    return {
      requestedMinor: requested,
      ready,
      cashToCollectMinor: ready ? cash.amountMinor : null,
    };
  });

  constructor() {
    // A different customer starts again.
    effect(() => {
      this.customerAccountId();
      untracked(() => {
        this.sequence += 1;
        this.requestedMinor.set(0);
        this.agreed.set(false);
        this.answer.set(null);
        this.answeredFor.set(null);
        this.unavailable.set(false);
      });
    });

    // Ask whenever the server's price for the basket changes or the amount does. The price retires on
    // any edit that could move it, and the amount is a keystroke, so both go through one debounce.
    effect((onCleanup) => {
      const ask = this.ask();
      const requested = this.requestedMinor();
      this.sequence += 1;
      const sequence = this.sequence;
      if (ask === null) {
        return;
      }
      const timer = setTimeout(
        () => void this.refresh(sequence, ask, requested),
        NewOrderPoints.DEBOUNCE_MS,
      );
      onCleanup(() => clearTimeout(timer));
    });

    effect(() => this.selectionChange.emit(this.selection()));
  }

  private async refresh(sequence: number, ask: PointsAsk, requested: number): Promise<void> {
    try {
      const answer = await this.api.redemptionOffer(ask.scope, {
        ...ask.request,
        redeemFromBalanceMinor: requested > 0 ? requested : null,
        operatorAttestsCustomerAgreement: null,
      });
      if (sequence !== this.sequence) {
        return;
      }
      this.answer.set(answer);
      this.answeredFor.set(ask);
      this.unavailable.set(false);
    } catch {
      if (sequence !== this.sequence) {
        return;
      }
      // The points are a convenience on top of taking the order, never a gate in front of it: with
      // nothing typed the control simply is not there, and with an amount typed the operator is told
      // it could not be checked (and «Создать» waits, see `selection`). The last answer stays on screen
      // so the amount can still be changed or cleared, but it no longer counts as accepted.
      this.answeredFor.set(null);
      this.unavailable.set(true);
    }
  }

  protected setRequested(minor: number): void {
    // An agreement is to an amount. A different amount is a different question for the customer.
    if (minor !== this.requestedMinor()) {
      this.agreed.set(false);
    }
    this.requestedMinor.set(minor);
  }

  protected useMaximum(): void {
    const offer = this.offer();
    if (offer !== null) {
      this.setRequested(offer.maximum.amountMinor);
    }
  }

  protected clear(): void {
    this.setRequested(0);
  }

  protected setAgreed(value: boolean): void {
    this.agreed.set(value);
  }

  protected formatted(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale());
  }
}
