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

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  DispatchRulesApi,
  DispatchScope,
  PaymentWindowAction,
  PaymentWindowView,
} from './dispatch-rules-api';
import { parseOptionalInt } from './dispatch-rules-model';

/**
 * The unpaid-order window, shown beside the dispatch rules and owned by `ordering` (ADR 0142 Decision 7):
 * how long an order may wait for payment before it appears in the list that needs a person.
 *
 * It lives on this screen because an operator thinks of it as one question -- what happens to an order
 * nobody has paid for -- and not in the rules because no dispatch plan exists for an unpaid order. The
 * deploy property it replaces survives as the answer when nothing is published.
 *
 * **"Cancel the order" is a real choice (ADR 0170).** It ends an order nobody paid as «Cancelled: not
 * paid» (`PAYMENT_FAILED`): its stock and promo code go back, the payment page is closed, and a payment
 * that arrives afterwards never revives it -- finance is told a refund is due instead. It needs a wait of
 * at least fifteen minutes, which the card enforces before the server has to; "flag the order" stays the
 * default and what a tenant that publishes nothing gets. Whether the platform acts on a published
 * cancelling window yet is the deployment's own switch (shadow first), not this card's.
 */
@Component({
  selector: 'q-payment-window-card',
  imports: [TPipe],
  templateUrl: './payment-window-card.html',
  styleUrl: './payment-window-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentWindowCard {
  private readonly api = inject(DispatchRulesApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<DispatchScope>();
  readonly canWrite = input(true);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saveError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly isDefault = signal(true);

  private readonly loadedMinutes = signal<number | null>(null);
  private readonly loadedAction = signal<PaymentWindowAction>('FLAG_ONLY');
  private readonly version = signal(0);
  protected readonly minutes = signal<number | null>(null);
  protected readonly action = signal<PaymentWindowAction>('FLAG_ONLY');
  protected readonly reason = signal('');

  protected readonly dirty = computed(
    () =>
      this.minutes() !== null &&
      (this.minutes() !== this.loadedMinutes() || this.action() !== this.loadedAction()),
  );

  /** A window that ends the order is at least this long: the customer may be on a slow provider page. */
  protected readonly minimumMinutes = computed(() => (this.action() === 'CANCEL' ? 15 : 1));

  protected readonly cancelTooShort = computed(
    () => this.action() === 'CANCEL' && (this.minutes() ?? 0) < this.minimumMinutes(),
  );

  protected readonly canPublish = computed(
    () =>
      this.canWrite() &&
      this.dirty() &&
      (this.minutes() ?? 0) >= this.minimumMinutes() &&
      this.reason().trim() !== '' &&
      !this.saving(),
  );

  constructor() {
    effect(() => {
      const scope = this.scope();
      untracked(() => void this.load(scope));
    });
  }

  protected setMinutes(raw: string): void {
    const value = parseOptionalInt(raw);
    if (value !== null) {
      this.minutes.set(value);
      this.notice.set(null);
    }
  }

  protected setAction(raw: string): void {
    if (raw === 'FLAG_ONLY' || raw === 'CANCEL') {
      this.action.set(raw);
      this.notice.set(null);
    }
  }

  protected discard(): void {
    this.minutes.set(this.loadedMinutes());
    this.action.set(this.loadedAction());
    this.reason.set('');
    this.saveError.set(null);
  }

  protected async publish(): Promise<void> {
    const minutes = this.minutes();
    if (minutes === null || !this.canPublish()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const published = await this.api.publishPaymentWindow(
        this.scope(),
        { windowMinutes: minutes, action: this.action() },
        this.reason().trim(),
        this.version(),
      );
      this.adopt(published);
      this.reason.set('');
      this.notice.set(
        this.i18n.t('delivery.paymentWindow.saved', { version: published.policyVersion }),
      );
    } catch (failure) {
      this.saveError.set(this.describe(failure));
    } finally {
      this.saving.set(false);
    }
  }

  private async load(scope: DispatchScope): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.saveError.set(null);
    this.notice.set(null);
    try {
      const { value } = await this.api.paymentWindow(scope);
      this.adopt(value);
    } catch (failure) {
      this.loadError.set(this.describe(failure));
    } finally {
      this.loading.set(false);
    }
  }

  private adopt(view: PaymentWindowView): void {
    this.loadedMinutes.set(view.windowMinutes);
    this.loadedAction.set(view.action);
    this.minutes.set(view.windowMinutes);
    this.action.set(view.action);
    this.isDefault.set(view.isDefault);
    this.version.set(view.versionAtScope);
  }

  private describe(failure: unknown): string {
    return failure instanceof ApiError
      ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
