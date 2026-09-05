import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { DiscountShape, DraftPromoCodeRequest, PromoCodeView, PromoCodesApi } from './promo-codes-api';

/**
 * Marketing §6.2 Promo codes (ADR 0072) — a brand's own promo codes: shape,
 * value, limits, and the draft → activate → retire lifecycle.
 *
 * **Built, against the real backend.** `pricing.promotions` and
 * `pricing.coupon_codes` (and their siblings) have existed since V0093 with
 * no authoring surface above them — this screen and `PromoCodeController`
 * are the first. Draft, then activate: a drafted code's coupon row is
 * `SUSPENDED` and discounts nothing until a separate activation call
 * promotes both rows together.
 *
 * **A closed set of three discount shapes**, not a rule editor: percentage
 * off the order, a fixed amount off the order, or free delivery. An operator
 * cannot author an item-level, time-windowed, or condition-combining
 * discount from this screen — see ADR 0072's own Alternatives table for why,
 * and `frontend-information-architecture.md` §6.1 Promotions (a separate,
 * unbuilt rule-engine screen) for where that would live.
 *
 * **Reduced relative to the spec.** Channel and location restrictions exist
 * in the backend (`Promotion.Condition`s a promo code may carry) but this
 * form does not expose pickers for them yet — every code this screen creates
 * applies to every channel and location in the brand. The same
 * "day one, not the richest editor" trade-off `LoyaltyPage` documents for its
 * own scope picker.
 */
@Component({
  selector: 'q-promo-codes-page',
  imports: [TPipe],
  templateUrl: './promo-codes-page.html',
  styleUrl: './promo-codes-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromoCodesPage implements OnInit {
  private readonly api = inject(PromoCodesApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly codes = signal<readonly PromoCodeView[]>([]);

  protected readonly actionError = signal<string | null>(null);
  protected readonly actingCouponId = signal<string | null>(null);

  /** The plaintext of a just-drafted code, shown once — see the API's own doc. */
  protected readonly justCreated = signal<PromoCodeView | null>(null);

  protected readonly shapeChoices: readonly DiscountShape[] = [
    'PERCENTAGE_OFF_ORDER',
    'FIXED_AMOUNT_OFF_ORDER',
    'FREE_DELIVERY',
  ];

  // ------------------------------------------------------------- draft form

  protected readonly showForm = signal(false);
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly formName = signal('');
  protected readonly formCode = signal('');
  protected readonly formShape = signal<DiscountShape>('PERCENTAGE_OFF_ORDER');
  protected readonly formPercent = signal(10);
  protected readonly formAmountMinor = signal(10_000);
  protected readonly formHasCap = signal(false);
  protected readonly formMaximumDiscountMinor = signal(50_000);
  protected readonly formMinBasketMinor = signal(0);
  protected readonly formHasTotalLimit = signal(false);
  protected readonly formTotalLimit = signal(100);
  protected readonly formPerCustomerLimit = signal(1);

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.codes.set(await this.api.list(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  // ------------------------------------------------------------- rendering

  protected shapeLabelKey(actionType: string): MessageKey {
    return this.shapeChoiceLabelKey(this.shapeOfActionType(actionType));
  }

  protected shapeChoiceLabelKey(shape: DiscountShape): MessageKey {
    return `marketing.promoCodes.shape.${shape}` as MessageKey;
  }

  protected valueLabel(code: PromoCodeView): string {
    const shape = this.shapeOfActionType(code.actionType);
    if (shape === 'PERCENTAGE_OFF_ORDER') {
      return `${(code.value / 100).toFixed(2)}%`;
    }
    if (shape === 'FIXED_AMOUNT_OFF_ORDER') {
      return this.formatWhole(code.value);
    }
    return this.i18n.t('marketing.promoCodes.shape.FREE_DELIVERY');
  }

  private shapeOfActionType(actionType: string): DiscountShape {
    if (actionType === 'ORDER_PERCENTAGE_DISCOUNT') {
      return 'PERCENTAGE_OFF_ORDER';
    }
    if (actionType === 'ORDER_FIXED_DISCOUNT') {
      return 'FIXED_AMOUNT_OFF_ORDER';
    }
    return 'FREE_DELIVERY';
  }

  protected statusLabelKey(status: string): MessageKey {
    return `marketing.promoCodes.status.${status}` as MessageKey;
  }

  protected isLive(status: string): boolean {
    return status === 'ACTIVE';
  }

  protected canActivate(status: string): boolean {
    return status === 'SUSPENDED';
  }

  protected canRetire(status: string): boolean {
    return status === 'SUSPENDED' || status === 'ACTIVE' || status === 'EXHAUSTED';
  }

  protected formatWhole(value: number): string {
    return formatMoney({ amountMinor: value, currency: 'UZS' }, this.i18n.locale());
  }

  // ---------------------------------------------------------------- draft

  protected openForm(): void {
    this.formName.set('');
    this.formCode.set('');
    this.formShape.set('PERCENTAGE_OFF_ORDER');
    this.formPercent.set(10);
    this.formAmountMinor.set(10_000);
    this.formHasCap.set(false);
    this.formMaximumDiscountMinor.set(50_000);
    this.formMinBasketMinor.set(0);
    this.formHasTotalLimit.set(false);
    this.formTotalLimit.set(100);
    this.formPerCustomerLimit.set(1);
    this.formError.set(null);
    this.justCreated.set(null);
    this.showForm.set(true);
  }

  protected closeForm(): void {
    this.showForm.set(false);
  }

  protected canSubmit(): boolean {
    if (this.submitting() || this.formName().trim().length === 0) {
      return false;
    }
    if (!/^[A-Za-z0-9]{4,32}$/.test(this.formCode().trim())) {
      return false;
    }
    if (this.formPerCustomerLimit() < 1) {
      return false;
    }
    if (this.formShape() === 'PERCENTAGE_OFF_ORDER') {
      const basisPoints = Math.round(this.formPercent() * 100);
      return basisPoints > 0 && basisPoints <= 10_000;
    }
    if (this.formShape() === 'FIXED_AMOUNT_OFF_ORDER') {
      return this.formAmountMinor() > 0;
    }
    return true;
  }

  protected async submit(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.formError.set(null);
    try {
      const request: DraftPromoCodeRequest = {
        name: this.formName().trim(),
        code: this.formCode().trim(),
        shape: this.formShape(),
        value: this.valueForShape(),
        maximumDiscountMinor: this.formHasCap() ? this.formMaximumDiscountMinor() : null,
        currency: 'UZS',
        minBasketMinor: this.formMinBasketMinor(),
        totalLimit: this.formHasTotalLimit() ? this.formTotalLimit() : null,
        perCustomerLimit: this.formPerCustomerLimit(),
      };
      const created = await this.api.draft(scope, request);
      this.showForm.set(false);
      this.justCreated.set(created);
      this.codes.set(await this.api.list(scope));
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.submitting.set(false);
    }
  }

  private valueForShape(): number {
    if (this.formShape() === 'PERCENTAGE_OFF_ORDER') {
      return Math.round(this.formPercent() * 100);
    }
    if (this.formShape() === 'FIXED_AMOUNT_OFF_ORDER') {
      return this.formAmountMinor();
    }
    return 0;
  }

  protected async activate(code: PromoCodeView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingCouponId.set(code.couponId);
    this.actionError.set(null);
    try {
      await this.api.activate(scope, code.couponId);
      this.codes.set(await this.api.list(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingCouponId.set(null);
    }
  }

  protected async retire(code: PromoCodeView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingCouponId.set(code.couponId);
    this.actionError.set(null);
    try {
      await this.api.retire(scope, code.couponId);
      this.codes.set(await this.api.list(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingCouponId.set(null);
    }
  }

  protected dismissJustCreated(): void {
    this.justCreated.set(null);
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
