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
import { Money, formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { LocationView, LocationsApi } from '../../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import {
  AccrualRuleView,
  LoyaltyApi,
  LoyaltyLiabilityView,
  RedemptionPolicyView,
} from './loyalty-api';

type ScopeType = 'BRAND' | 'LOCATION' | 'CHANNEL';

/**
 * Marketing §6.3 Loyalty (ADR 0046) — a brand's own accrual rate and
 * redemption cap.
 *
 * **Built, against the real backend.** `LoyaltyOperationsController` already
 * read a customer's own balances into Customer detail (wave 39); this screen
 * is the other half — authoring the numbers those balances accrue and redeem
 * against, through `LoyaltyPolicyController` (this wave) and the
 * `LOYALTY_POLICY_MANAGE` capability that already existed in code with no
 * caller. Draft, then activate: a drafted rule or policy accrues and redeems
 * nothing, and activating one retires whichever row currently holds the same
 * scope in the same transaction — so a rate change is never silently doubled
 * up with the one it replaces.
 *
 * **Honestly not built, in this same screen rather than a separate route.**
 * Deposit accounts (Депозит): ADR 0046 withdrew customer-funded stored value
 * from scope outright — "Loyalty is points only" — because nobody has a
 * Central Bank of Uzbekistan authorisation to hold customer funds. That is a
 * closed decision, not a gap to fill. POS balance sync: no ADR, no capability,
 * no adapter — the IA row names it and nothing beneath it exists yet.
 *
 * **Reduced relative to the spec.** The location and channel pickers below
 * list every branch/channel this brand has, but a rule's own `scopeId` is
 * typed as a plain identifier rather than resolved through a dedicated
 * `ConditionBuilder` — the same "day one, not the richest editor" trade-off
 * `DeliveryTariffsPage` documents for its own single-band form. Manual
 * per-customer adjustment (`LoyaltyOperationsController.adjust`) stays where
 * ADR 0046 puts it, on a customer's own record — Customer detail (§5.2),
 * already built and out of this wave's two rows.
 */
@Component({
  selector: 'q-loyalty-page',
  imports: [TPipe],
  templateUrl: './loyalty-page.html',
  styleUrl: './loyalty-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoyaltyPage implements OnInit {
  private readonly api = inject(LoyaltyApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly accrualRules = signal<readonly AccrualRuleView[]>([]);
  protected readonly redemptionPolicies = signal<readonly RedemptionPolicyView[]>([]);
  protected readonly liabilities = signal<readonly LoyaltyLiabilityView[]>([]);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);

  protected readonly ownLiability = computed<LoyaltyLiabilityView | null>(() => {
    const brandId = this.brand.scope()?.brandId;
    return this.liabilities().find((row) => row.brandId === brandId) ?? null;
  });

  protected readonly actionError = signal<string | null>(null);
  protected readonly actingRuleId = signal<string | null>(null);
  protected readonly actingPolicyId = signal<string | null>(null);

  // ---------------------------------------------------------- accrual form

  protected readonly showAccrualForm = signal(false);
  protected readonly accrualSubmitting = signal(false);
  protected readonly accrualError = signal<string | null>(null);
  protected readonly accrualScopeType = signal<ScopeType>('BRAND');
  protected readonly accrualScopeId = signal('');
  protected readonly accrualRatePercent = signal(0);
  protected readonly accrualHasCap = signal(false);
  protected readonly accrualMaxAccrualMinor = signal(30_000);
  protected readonly accrualEarnDelayHours = signal(24);
  protected readonly accrualLotLifetimeDays = signal(180);
  protected readonly accrualExpiryWarningDays = signal(14);

  protected readonly scopeChoices: readonly ScopeType[] = ['BRAND', 'LOCATION', 'CHANNEL'];

  // -------------------------------------------------------- redemption form

  protected readonly showRedemptionForm = signal(false);
  protected readonly redemptionSubmitting = signal(false);
  protected readonly redemptionError = signal<string | null>(null);
  protected readonly redemptionSharePercent = signal(0);
  protected readonly redemptionMinOrderMinor = signal(50_000);
  protected readonly redemptionExcludesDeliveryFee = signal(true);

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
      const [rules, policies] = await Promise.all([
        this.api.listAccrualRules(scope),
        this.api.listRedemptionPolicies(scope),
      ]);
      this.accrualRules.set(rules);
      this.redemptionPolicies.set(policies);
      // Best-effort: the liability figure and the scope pickers are context
      // for authoring the policy, not the policy itself, so a principal who
      // cannot read them still gets a working authoring form.
      try {
        this.liabilities.set(await this.api.liability(scope));
      } catch {
        // Leave empty — the liability panel simply does not render.
      }
      try {
        this.locations.set(await this.locationsApi.list({ ...scope, locationId: '' }));
      } catch {
        // Leave empty — a LOCATION-scope rule still saves with a typed id.
      }
      try {
        this.channels.set(await this.channelsApi.list({ ...scope, locationId: '' }));
      } catch {
        // Leave empty — a CHANNEL-scope rule still saves with a typed id.
      }
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

  protected ratePercentLabel(rule: AccrualRuleView): string {
    return this.percentLabel(rule.rateBasisPoints);
  }

  protected sharePercentLabel(policy: RedemptionPolicyView): string {
    return this.percentLabel(policy.maxShareBasisPoints);
  }

  private percentLabel(basisPoints: number): string {
    return `${(basisPoints / 100).toFixed(2)}%`;
  }

  protected scopeLabel(rule: AccrualRuleView): string {
    if (rule.scopeType === 'BRAND') {
      return this.i18n.t('marketing.loyalty.scope.brand');
    }
    if (rule.scopeType === 'LOCATION') {
      const location = this.locations().find((row) => row.id === rule.scopeId);
      return location ? location.displayName : (rule.scopeId ?? '');
    }
    const channel = this.channels().find((row) => row.id === rule.scopeId);
    return channel ? channel.displayName : (rule.scopeId ?? '');
  }

  protected statusLabelKey(status: string): MessageKey {
    return `marketing.loyalty.status.${status}` as MessageKey;
  }

  protected scopeChoiceLabelKey(choice: ScopeType): MessageKey {
    return `marketing.loyalty.scope.${choice.toLowerCase()}` as MessageKey;
  }

  /**
   * Points are whole numbers with no currency of their own on this row — a
   * `maxAccrualMinor`/`minOrderMinor` field carries the brand's own money unit
   * (som today) but the API response names no currency here, unlike the
   * liability report's own {@link Money} fields. `formatMoney` is reused only
   * for its grouping (a UZS row is exponent 0, i.e. a plain whole number);
   * `withUnit` is left off so no currency code is ever implied.
   */
  protected formatWhole(value: number): string {
    return formatMoney({ amountMinor: value, currency: 'UZS' }, this.i18n.locale());
  }

  protected formatLiabilityMoney(money: Money): string {
    return formatMoney(money, this.i18n.locale(), { withUnit: true });
  }

  // -------------------------------------------------------------- accrual

  protected openAccrualForm(): void {
    this.accrualScopeType.set('BRAND');
    this.accrualScopeId.set('');
    this.accrualRatePercent.set(0);
    this.accrualHasCap.set(false);
    this.accrualMaxAccrualMinor.set(30_000);
    this.accrualEarnDelayHours.set(24);
    this.accrualLotLifetimeDays.set(180);
    this.accrualExpiryWarningDays.set(14);
    this.accrualError.set(null);
    this.showAccrualForm.set(true);
  }

  protected closeAccrualForm(): void {
    this.showAccrualForm.set(false);
  }

  protected canSubmitAccrual(): boolean {
    const rate = Math.round(this.accrualRatePercent() * 100);
    return (
      !this.accrualSubmitting() &&
      rate >= 0 &&
      rate <= 10_000 &&
      this.accrualLotLifetimeDays() > 0 &&
      this.accrualExpiryWarningDays() >= 0 &&
      this.accrualExpiryWarningDays() < this.accrualLotLifetimeDays() &&
      this.accrualEarnDelayHours() >= 0 &&
      (this.accrualScopeType() === 'BRAND' || this.accrualScopeId().trim().length > 0)
    );
  }

  protected async submitAccrualForm(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmitAccrual()) {
      return;
    }
    this.accrualSubmitting.set(true);
    this.accrualError.set(null);
    try {
      await this.api.draftAccrualRule(scope, {
        scopeType: this.accrualScopeType(),
        scopeId: this.accrualScopeType() === 'BRAND' ? null : this.accrualScopeId().trim(),
        rateBasisPoints: Math.round(this.accrualRatePercent() * 100),
        maxAccrualMinor: this.accrualHasCap() ? this.accrualMaxAccrualMinor() : null,
        earnDelayHours: this.accrualEarnDelayHours(),
        lotLifetimeDays: this.accrualLotLifetimeDays(),
        expiryWarningDays: this.accrualExpiryWarningDays(),
      });
      this.showAccrualForm.set(false);
      this.accrualRules.set(await this.api.listAccrualRules(scope));
    } catch (error) {
      this.accrualError.set(this.describe(error));
    } finally {
      this.accrualSubmitting.set(false);
    }
  }

  protected async activateAccrualRule(rule: AccrualRuleView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingRuleId.set(rule.id);
    this.actionError.set(null);
    try {
      await this.api.activateAccrualRule(scope, rule.id);
      this.accrualRules.set(await this.api.listAccrualRules(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingRuleId.set(null);
    }
  }

  protected async retireAccrualRule(rule: AccrualRuleView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingRuleId.set(rule.id);
    this.actionError.set(null);
    try {
      await this.api.retireAccrualRule(scope, rule.id);
      this.accrualRules.set(await this.api.listAccrualRules(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingRuleId.set(null);
    }
  }

  // ------------------------------------------------------------ redemption

  protected openRedemptionForm(): void {
    this.redemptionSharePercent.set(0);
    this.redemptionMinOrderMinor.set(50_000);
    this.redemptionExcludesDeliveryFee.set(true);
    this.redemptionError.set(null);
    this.showRedemptionForm.set(true);
  }

  protected closeRedemptionForm(): void {
    this.showRedemptionForm.set(false);
  }

  protected canSubmitRedemption(): boolean {
    const share = Math.round(this.redemptionSharePercent() * 100);
    return (
      !this.redemptionSubmitting() &&
      share > 0 &&
      share <= 9_000 &&
      this.redemptionMinOrderMinor() >= 0
    );
  }

  protected async submitRedemptionForm(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmitRedemption()) {
      return;
    }
    this.redemptionSubmitting.set(true);
    this.redemptionError.set(null);
    try {
      await this.api.draftRedemptionPolicy(scope, {
        maxShareBasisPoints: Math.round(this.redemptionSharePercent() * 100),
        minOrderMinor: this.redemptionMinOrderMinor(),
        excludesDeliveryFee: this.redemptionExcludesDeliveryFee(),
        allowedChannels: [],
      });
      this.showRedemptionForm.set(false);
      this.redemptionPolicies.set(await this.api.listRedemptionPolicies(scope));
    } catch (error) {
      this.redemptionError.set(this.describe(error));
    } finally {
      this.redemptionSubmitting.set(false);
    }
  }

  protected async activateRedemptionPolicy(policy: RedemptionPolicyView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingPolicyId.set(policy.id);
    this.actionError.set(null);
    try {
      await this.api.activateRedemptionPolicy(scope, policy.id);
      this.redemptionPolicies.set(await this.api.listRedemptionPolicies(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingPolicyId.set(null);
    }
  }

  protected async retireRedemptionPolicy(policy: RedemptionPolicyView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingPolicyId.set(policy.id);
    this.actionError.set(null);
    try {
      await this.api.retireRedemptionPolicy(scope, policy.id);
      this.redemptionPolicies.set(await this.api.listRedemptionPolicies(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingPolicyId.set(null);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
