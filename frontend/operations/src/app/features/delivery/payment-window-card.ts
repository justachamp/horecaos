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
import { DispatchRulesApi, DispatchScope, PaymentWindowView } from './dispatch-rules-api';
import { parseOptionalInt } from './dispatch-rules-model';

/**
 * The unpaid-order window, shown beside the dispatch rules and owned by `ordering` (ADR 0142 Decision 7):
 * how long an order may wait for payment before it appears in the list that needs a person.
 *
 * It lives on this screen because an operator thinks of it as one question -- what happens to an order
 * nobody has paid for -- and not in the rules because no dispatch plan exists for an unpaid order. The
 * deploy property it replaces survives as the answer when nothing is published.
 *
 * **"Cancel the order" is shown and cannot be chosen.** Whether an unpaid order should be cancelled at all
 * is ADR 0019's open product input, and the server refuses it at publish; offering it as a live choice
 * would only teach an operator to expect a refusal. Until product answers, the window decides when an
 * order reaches the stuck list and nothing more.
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
  private readonly version = signal(0);
  protected readonly minutes = signal<number | null>(null);
  protected readonly reason = signal('');

  protected readonly dirty = computed(
    () => this.minutes() !== null && this.minutes() !== this.loadedMinutes(),
  );

  protected readonly canPublish = computed(
    () =>
      this.canWrite() &&
      this.dirty() &&
      (this.minutes() ?? 0) >= 1 &&
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

  protected discard(): void {
    this.minutes.set(this.loadedMinutes());
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
        { windowMinutes: minutes, action: 'FLAG_ONLY' },
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
    this.minutes.set(view.windowMinutes);
    this.isDefault.set(view.isDefault);
    this.version.set(view.versionAtScope);
  }

  private describe(failure: unknown): string {
    return failure instanceof ApiError
      ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
