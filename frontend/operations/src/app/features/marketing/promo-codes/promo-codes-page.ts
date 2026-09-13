import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { MoneyOrPercent, MoneyOrPercentKind } from '../../../shared/ui/money-or-percent';
import { describeApiError } from '../../orders/order-errors';
import { LocationView, LocationsApi } from '../../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import {
  DiscountShape,
  DraftPromoCodeRequest,
  PromoCodeRedemption,
  PromoCodeView,
  PromoCodesApi,
} from './promo-codes-api';

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
 * **`validFrom`/`validUntil`/`channels`/`locationIds`, authored here.** The
 * request has always accepted them and `PromoCodeAuthoringService` has always
 * validated and enforced them — the gap was this form, which offered no field
 * for any of the four, so every code drafted from this screen ran forever
 * across every channel and branch regardless of what an operator intended.
 * Leaving every checkbox unchecked and both dates blank still means exactly
 * what it always meant: no restriction, effective immediately, no expiry —
 * this form makes that the explicit default rather than the only option.
 *
 * **The redemption ledger.** `pricing.coupon_redemptions` used to have no
 * reader at any layer, so `redeemedCount` was a bare number with no
 * drill-down. Each row now opens which customer redeemed the code, on which
 * order, and when — `PromoCodeController.redemptions`, this wave's own new
 * endpoint.
 */
@Component({
  selector: 'q-promo-codes-page',
  imports: [TPipe, MoneyOrPercent],
  templateUrl: './promo-codes-page.html',
  styleUrl: './promo-codes-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromoCodesPage implements OnInit {
  private readonly api = inject(PromoCodesApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly codes = signal<readonly PromoCodeView[]>([]);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);

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
  /** `q-percent-input`'s own model — basis points, never the display percent (ADR 0101, row `X.10`). */
  protected readonly formBasisPoints = signal(1_000);
  protected readonly formAmountMinor = signal(10_000);
  protected readonly formHasCap = signal(false);
  protected readonly formMaximumDiscountMinor = signal(50_000);
  protected readonly formMinBasketMinor = signal(0);
  protected readonly formHasTotalLimit = signal(false);
  protected readonly formTotalLimit = signal(100);
  protected readonly formPerCustomerLimit = signal(1);

  /** Blank means "effective immediately on activation" — `DraftPromoCodeRequest.validFrom`'s own null case. */
  protected readonly formValidFrom = signal('');
  protected readonly formHasValidUntil = signal(false);
  protected readonly formValidUntil = signal(defaultValidUntilDate());
  /** Selected channel codes; empty means every channel — `DraftPromoCodeRequest.channels`'s own empty case. */
  protected readonly formChannelCodes = signal<readonly string[]>([]);
  /** Selected location ids; empty means every location — `DraftPromoCodeRequest.locationIds`'s own empty case. */
  protected readonly formLocationIds = signal<readonly string[]>([]);

  /**
   * `q-money-or-percent`'s `kind` — derived from {@link formShape} rather than
   * held independently, so the shape `<select>` above and the widget's own
   * AMOUNT/PERCENT toggle can never disagree about which one is live.
   * `FREE_DELIVERY` never renders the widget at all (see the template), so
   * its arbitrary `'PERCENT'` fallback here is never shown.
   */
  protected readonly moneyOrPercentKind = computed<MoneyOrPercentKind>(() =>
    this.formShape() === 'FIXED_AMOUNT_OFF_ORDER' ? 'AMOUNT' : 'PERCENT',
  );

  /** The widget's own toggle moves {@link formShape} itself — see {@link moneyOrPercentKind}'s doc. */
  protected onMoneyOrPercentKindChange(kind: MoneyOrPercentKind): void {
    this.formShape.set(kind === 'AMOUNT' ? 'FIXED_AMOUNT_OFF_ORDER' : 'PERCENTAGE_OFF_ORDER');
  }

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
    try {
      // Best-effort, the same way LoyaltyPage treats its own scope pickers: a
      // principal who cannot read locations or channels still gets a working
      // draft form, just with every checkbox unchecked and the code applying
      // to every channel and location — the same default it always had.
      this.locations.set(await this.locationsApi.list({ ...scope, locationId: '' }));
    } catch {
      // Leave empty.
    }
    try {
      this.channels.set(await this.channelsApi.list({ ...scope, locationId: '' }));
    } catch {
      // Leave empty.
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
    this.formBasisPoints.set(1_000);
    this.formAmountMinor.set(10_000);
    this.formHasCap.set(false);
    this.formMaximumDiscountMinor.set(50_000);
    this.formMinBasketMinor.set(0);
    this.formHasTotalLimit.set(false);
    this.formTotalLimit.set(100);
    this.formPerCustomerLimit.set(1);
    this.formValidFrom.set('');
    this.formHasValidUntil.set(false);
    this.formValidUntil.set(defaultValidUntilDate());
    this.formChannelCodes.set([]);
    this.formLocationIds.set([]);
    this.formError.set(null);
    this.justCreated.set(null);
    this.showForm.set(true);
  }

  protected isChannelSelected(code: string): boolean {
    return this.formChannelCodes().includes(code);
  }

  protected toggleChannel(code: string): void {
    this.formChannelCodes.set(
      this.isChannelSelected(code)
        ? this.formChannelCodes().filter((selected) => selected !== code)
        : [...this.formChannelCodes(), code],
    );
  }

  protected isLocationSelected(locationId: string): boolean {
    return this.formLocationIds().includes(locationId);
  }

  protected toggleLocation(locationId: string): void {
    this.formLocationIds.set(
      this.isLocationSelected(locationId)
        ? this.formLocationIds().filter((selected) => selected !== locationId)
        : [...this.formLocationIds(), locationId],
    );
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
    if (this.formHasValidUntil() && this.formValidUntil().trim().length === 0) {
      return false;
    }
    if (
      this.formHasValidUntil() &&
      this.formValidFrom().trim().length > 0 &&
      this.formValidUntil() <= this.formValidFrom()
    ) {
      // Mirrors PromoCodeAuthoringService's own validate(): validUntil must be
      // after validFrom. Caught here so the round trip to the server is not
      // what tells an operator they typed the dates the wrong way round.
      return false;
    }
    if (this.formShape() === 'PERCENTAGE_OFF_ORDER') {
      const basisPoints = this.formBasisPoints();
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
        validFrom: this.formValidFrom().trim()
          ? new Date(this.formValidFrom()).toISOString()
          : null,
        validUntil:
          this.formHasValidUntil() && this.formValidUntil().trim()
            ? endOfDayIso(this.formValidUntil())
            : null,
        channels: this.formChannelCodes(),
        locationIds: this.formLocationIds(),
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
      return this.formBasisPoints();
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

  // -------------------------------------------------------------- expiry

  protected expiryLabel(code: PromoCodeView): string {
    return code.validUntil === null ? '—' : this.formatShortDate(code.validUntil);
  }

  protected isExpired(code: PromoCodeView): boolean {
    return code.validUntil !== null && new Date(code.validUntil).getTime() <= Date.now();
  }

  private formatShortDate(iso: string): string {
    return new Date(iso).toLocaleDateString(this.i18n.locale() === 'en' ? 'en-GB' : 'ru-RU');
  }

  // --------------------------------------------------------- redemption ledger

  protected readonly redemptionsForCode = signal<PromoCodeView | null>(null);
  protected readonly redemptionsLoading = signal(false);
  protected readonly redemptionsError = signal<string | null>(null);
  protected readonly redemptions = signal<readonly PromoCodeRedemption[]>([]);

  protected async openRedemptions(code: PromoCodeView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.redemptionsForCode.set(code);
    this.redemptionsError.set(null);
    this.redemptions.set([]);
    this.redemptionsLoading.set(true);
    try {
      this.redemptions.set(await this.api.listRedemptions(scope, code.couponId));
    } catch (error) {
      this.redemptionsError.set(this.describe(error));
    } finally {
      this.redemptionsLoading.set(false);
    }
  }

  protected closeRedemptions(): void {
    this.redemptionsForCode.set(null);
  }

  protected redemptionStatusLabelKey(status: string): MessageKey {
    return `marketing.promoCodes.redemptions.status.${status}` as MessageKey;
  }

  protected formatRedemptionAmount(row: PromoCodeRedemption): string {
    return formatMoney(
      { amountMinor: row.amountMinor, currency: row.currency },
      this.i18n.locale(),
      {
        withUnit: true,
      },
    );
  }

  protected formatRedemptionMoment(iso: string | null): string {
    return iso === null
      ? '—'
      : new Date(iso).toLocaleString(this.i18n.locale() === 'en' ? 'en-GB' : 'ru-RU');
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/** Thirty days out, `YYYY-MM-DD` — a sensible opening default an operator can shorten or extend. */
function defaultValidUntilDate(): string {
  const date = new Date();
  date.setDate(date.getDate() + 30);
  return date.toISOString().slice(0, 10);
}

/** The last instant of the given calendar date, as an ISO instant — an expiry date includes its own day. */
function endOfDayIso(dateOnly: string): string {
  return new Date(`${dateOnly}T23:59:59.999`).toISOString();
}
