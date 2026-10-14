import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { TillRevisionApi, TillRevisionNotice, TillRevisionView } from './till-revision-api';

const OUTCOME_MESSAGES: Readonly<Record<string, MessageKey>> = {
  NO_OPEN_NOTICE: 'orders.tillRevision.outcome.NO_OPEN_NOTICE',
  NOT_AVAILABLE: 'orders.tillRevision.outcome.NOT_AVAILABLE',
  REJECTED: 'orders.tillRevision.outcome.REJECTED',
  RETRYABLE: 'orders.tillRevision.outcome.RETRYABLE',
  UNCERTAIN: 'orders.tillRevision.outcome.UNCERTAIN',
};

/**
 * An order the till already holds whose total moved after a weight was captured (ADR 0137, gap map row
 * `4.2c`): it says what the till holds against what the order is worth, and offers the two ways to close
 * it. Without it the till stays silently at the nominal weight until somebody finds the difference at the
 * cash desk.
 *
 * Self-contained: it reads its own state for the order it is given and re-reads whenever the host's order
 * version moves (a weighing bumps it), so the order detail and the pass show the same banner without either
 * knowing how it works. It shows only an open notice. The weighed total is not in doubt and the order has not
 * failed; the till's copy is what is stale, and the wording says so.
 */
@Component({
  selector: 'q-till-revision-banner',
  imports: [TPipe],
  templateUrl: './till-revision-banner.html',
  styleUrl: './till-revision-banner.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TillRevisionBanner {
  private readonly api = inject(TillRevisionApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly orderId = input.required<string>();
  /** The order's version as the host last read it: a change means a weighing may have moved the total. */
  readonly orderVersion = input.required<number>();

  protected readonly view = signal<TillRevisionView | null>(null);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly unreadable = signal(false);
  /** How the notice just ended in this session, so the operator sees the answer to the click they made. */
  protected readonly justClosed = signal<'TOLD' | 'ACKNOWLEDGED' | null>(null);

  protected readonly open = computed<TillRevisionNotice | null>(() => {
    const notice = this.view()?.notice ?? null;
    return notice?.status === 'OPEN' ? notice : null;
  });

  private loadSequence = 0;
  private shownFor: string | null = null;

  constructor() {
    effect(() => {
      const scope = this.scope();
      const orderId = this.orderId();
      this.orderVersion();
      untracked(() => void this.load(scope, orderId));
    });
  }

  protected money(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale());
  }

  /** `+7 000 UZS` or `−7 000 UZS`: the sign is the point. */
  protected signedMoney(amountMinor: number, currency: string): string {
    const sign = amountMinor > 0 ? '+' : '';
    return `${sign}${this.money(amountMinor, currency)}`;
  }

  protected async send(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    try {
      const result = await this.api.send(this.scope(), this.orderId());
      this.view.set(result.revision);
      if (result.outcome === 'TOLD') {
        this.justClosed.set('TOLD');
      } else {
        const key = OUTCOME_MESSAGES[result.outcome];
        this.message.set(key === undefined ? null : this.i18n.t(key));
      }
    } catch {
      this.message.set(this.i18n.t('orders.tillRevision.outcome.RETRYABLE'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async acknowledge(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    try {
      this.view.set(await this.api.acknowledge(this.scope(), this.orderId()));
      this.justClosed.set('ACKNOWLEDGED');
    } catch {
      // Nothing to acknowledge any more, or the platform could not be asked: read it again and show what is true.
      await this.load(this.scope(), this.orderId());
    } finally {
      this.busy.set(false);
    }
  }

  private async load(scope: LocationScope, orderId: string): Promise<void> {
    const sequence = ++this.loadSequence;
    if (this.shownFor !== orderId) {
      // The pane is reused across orders under one route: nothing said about the last one carries over.
      this.shownFor = orderId;
      this.view.set(null);
      this.message.set(null);
      this.justClosed.set(null);
      this.unreadable.set(false);
    }
    try {
      const view = await this.api.forOrder(scope, orderId);
      if (sequence === this.loadSequence) {
        this.view.set(view);
        this.unreadable.set(false);
      }
    } catch {
      if (sequence === this.loadSequence) {
        this.unreadable.set(true);
      }
    }
  }
}
