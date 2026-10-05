import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { RouterLink } from '@angular/router';

import { firstPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { DateRange, DateRangePicker } from '../../shared/ui/date-range-picker';
import { CustomerSummary, CustomersApi } from '../customers/customers-api';
import { customerStatusLabel } from '../customers/customer-status';
import { CampaignView, MarketingApi, RecipientCountsView } from '../marketing/marketing-api';
import { orderStatusLabel } from '../orders/order-status';
import {
  CustomerDiscountHistory,
  MarketingReportApi,
  PromotionRedemptionRow,
  PromotionSummaryRow,
} from './marketing-report-api';
import {
  PromotionTextKey,
  PromotionTextPipe,
  promotionText,
} from '../marketing/promotions/promotion-texts';
import { ProvenanceBanner } from './provenance-banner';
import { ReportRange, defaultPromotionRange } from './promotion-report-range';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from './reports-filter-state';
import { ProvenanceResponse } from './reporting-api';

type Tab = 'discounts' | 'campaigns' | 'promotions';
type LoadState = 'idle' | 'loading' | 'ready' | 'denied' | 'error';

/**
 * Where one «who redeemed it» lookup stands. `account` carries the id the customer card is opened
 * with and nothing about the person; `guest` is an answer, not a failure (a guest order has no
 * account); `denied` is the platform's 403, shown as such rather than as a fault.
 */
type RevealState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'account'; readonly accountId: string }
  | { readonly kind: 'guest' }
  | { readonly kind: 'denied' }
  | { readonly kind: 'error' };

/**
 * Fixed, English, machine-facing purpose, not translated: the same reason
 * `customer-detail-pane.ts`'s `REVEAL_PURPOSE` is not. It is read by whoever reviews the audit log
 * of the customer who was looked at, not by the operator.
 */
const WHO_REDEEMED_PURPOSE = 'Operations console: marketing report, who redeemed it';

const TAB_DEFINITIONS: readonly { readonly id: Tab; readonly labelKey: MessageKey }[] = [
  { id: 'discounts', labelKey: 'reports.marketing.tab.discounts' },
  { id: 'campaigns', labelKey: 'reports.marketing.tab.campaigns' },
  { id: 'promotions', labelKey: 'reports.marketing.tab.promotions' },
];

/**
 * 7.9 Marketing reports (`frontend-information-architecture.md` §7.9) — tier
 * 2. Three of the screen's facts: 7.9a per-customer discount history, 7.9b
 * campaign delivery counts, and (ADR 0140) the promotion summary with its
 * per-promotion redemption log, read from `reporting.fact_promotion_redemption`
 * alone — ADR 0023 forbids `reporting` reading `pricing` directly, so the fact
 * is built at day close and the report lags by up to a business day.
 *
 * **7.9a — "how much has this customer been discounted".** The abuse check a
 * marketer runs before granting another goodwill code. `q-combobox` plus
 * `CustomersApi.list` (both reused, not duplicated — this screen has no
 * customer picker of its own) find the account, then
 * `CustomerDiscountHistoryController` (new this wave) answers: every coupon
 * reservation, redemption and release that account has ever held, across
 * every brand under the tenant, plus a per-currency total of what actually
 * paid out. The customer detail pane (`P40`, not yet merged) is this same
 * endpoint's other named consumer; until it lands, a marketer reaches this
 * screen by searching, not by a link from the customer record.
 *
 * **7.9b — campaign delivery.** `MarketingApi.recipients` already returns
 * the raw per-recipient list (Marketing §6.4); this tab adds the aggregate
 * (`recipientCounts`, new this wave) so a marketer does not have to page the
 * whole list to answer "how many recipients ended each way". The counts are
 * grouped by `campaign_recipients.status` (pending/queued/deferred/refused),
 * not by the ADR 0020 terminal outcome — "delivered" vs "failed" needs a
 * projection this wave does not add. Read receipts have no data source at
 * all (`NotificationStatus` has no `READ`, V0043 has no `read_at`), so the
 * tab says so rather than rendering a zero.
 */
@Component({
  selector: 'q-marketing-report-page',
  imports: [TPipe, PromotionTextPipe, Combobox, DateRangePicker, ProvenanceBanner, RouterLink],
  templateUrl: './marketing-report-page.html',
  styleUrl: './marketing-report-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarketingReportPage {
  private readonly location = inject(CurrentLocation);
  private readonly customersApi = inject(CustomersApi);
  private readonly marketingApi = inject(MarketingApi);
  private readonly discountApi = inject(MarketingReportApi);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  protected readonly tabs = TAB_DEFINITIONS;
  protected readonly activeTab = signal<Tab>('discounts');

  /**
   * A single access gate for the whole screen, checked once: both 7.9a and
   * 7.9b need the operator's tenant/brand scope before either can do
   * anything, so — unlike each tab's own loading/error state — this is not
   * per-tab. A tab-scoped denial would only ever show once that tab's own
   * lazy load ran, which for the discounts tab is "never" until a search is
   * typed; a session with no resolvable location must not look like it is
   * just waiting for input.
   */
  protected readonly pageState = signal<'loading' | 'ready' | 'denied' | 'error'>('loading');

  // ---------------------------------------------------- 7.9a customer discounts

  protected readonly searchQuery = signal('');
  protected readonly searchResults = signal<readonly CustomerSummary[]>([]);
  protected readonly searchState = signal<LoadState>('idle');
  protected readonly selectedCustomer = signal<CustomerSummary | null>(null);
  protected readonly historyState = signal<LoadState>('idle');
  protected readonly history = signal<CustomerDiscountHistory | null>(null);

  /** `q-combobox`'s suggestion list — the same rows the debounced search already fetched. */
  protected readonly searchOptions = computed<readonly ComboboxOption[]>(() =>
    this.searchResults().map((customer) => ({
      id: customer.id,
      label: customer.displayName ?? customer.id,
      sublabel: customerStatusLabel(customer.status, (key) => this.i18n.t(key)),
    })),
  );

  // ---------------------------------------------------- 7.9b campaign delivery

  protected readonly campaignsState = signal<LoadState>('idle');
  protected readonly campaigns = signal<readonly CampaignView[]>([]);
  protected readonly selectedCampaign = signal<CampaignView | null>(null);
  protected readonly countsState = signal<LoadState>('idle');
  protected readonly counts = signal<RecipientCountsView | null>(null);

  // ---------------------------------------------------- 7.9 promotion report

  protected readonly promoRange = signal<ReportRange>(defaultPromotionRange(new Date()));
  protected readonly promoState = signal<LoadState>('idle');
  protected readonly promoSummary = signal<readonly PromotionSummaryRow[]>([]);
  protected readonly promoProvenance = signal<ProvenanceResponse | null>(null);
  /** The promotion whose redemptions the log shows; `null` is every promotion. */
  protected readonly promoSelected = signal<PromotionSummaryRow | null>(null);
  protected readonly promoLogState = signal<LoadState>('idle');
  protected readonly promoLog = signal<readonly PromotionRedemptionRow[]>([]);
  /**
   * Which log rows have been asked «who redeemed it», by redemption id. Kept across a reload of the
   * log, so an operator who narrows it does not have to ask again (every ask is an audit fact).
   */
  protected readonly reveals = signal<ReadonlyMap<string, RevealState>>(new Map());

  constructor() {
    void this.init();
  }

  private async init(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.pageState.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    this.pageState.set('ready');
    void this.loadCampaigns();
  }

  protected selectTab(tab: Tab): void {
    this.activeTab.set(tab);
    if (tab === 'promotions' && this.promoState() === 'idle') {
      void this.loadPromotions();
    }
  }

  // ------------------------------------------------------------------ 7.9a

  /** `q-combobox`'s immediate `queryChange` — keeps the field responsive without issuing a request. */
  protected onSearchInput(value: string): void {
    this.searchQuery.set(value);
  }

  /** `q-combobox`'s own debounced `search` output — the signal to actually call the server. */
  protected onSearchDebounced(value: string): void {
    this.searchQuery.set(value);
    void this.searchCustomers();
  }

  private async searchCustomers(): Promise<void> {
    const query = this.searchQuery().trim();
    if (!query) {
      this.searchResults.set([]);
      this.searchState.set('idle');
      return;
    }
    this.searchState.set('loading');
    const scope = this.location.scope();
    if (!scope) {
      this.searchState.set('error');
      return;
    }
    try {
      const page = await this.customersApi.list(scope, firstPage(8), { query });
      this.searchResults.set(page.items);
      this.searchState.set('ready');
    } catch {
      this.searchState.set('error');
    }
  }

  protected retrySearch(): void {
    void this.searchCustomers();
  }

  /** `q-combobox`'s `optionSelected` carries only `{id, label}` — the row it came from is looked up here. */
  protected onCustomerSelected(option: ComboboxOption): void {
    const customer = this.searchResults().find((candidate) => candidate.id === option.id);
    if (customer) {
      void this.selectCustomer(customer);
    }
  }

  private async selectCustomer(customer: CustomerSummary): Promise<void> {
    this.selectedCustomer.set(customer);
    this.historyState.set('loading');
    const scope = this.location.scope();
    if (!scope) {
      this.historyState.set('error');
      return;
    }
    try {
      const result = await this.discountApi.discountHistory(scope.tenantId, customer.id);
      this.history.set(result);
      this.historyState.set('ready');
    } catch {
      this.historyState.set('error');
    }
  }

  protected retryHistory(): void {
    const customer = this.selectedCustomer();
    if (customer) {
      void this.selectCustomer(customer);
    }
  }

  protected customerLabel(customer: CustomerSummary): string {
    return customer.displayName ?? customer.id;
  }

  protected redemptionStatusLabelKey(status: string): MessageKey {
    return `reports.marketing.discounts.status.${status}` as MessageKey;
  }

  protected formatMoneyValue(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected formatWhen(iso: string): string {
    return formatDateTime(new Date(iso), REPORTS_PLACEHOLDER_TIME_ZONE);
  }

  // ------------------------------------------------------------------ 7.9b

  private async loadCampaigns(): Promise<void> {
    this.campaignsState.set('loading');
    const scope = this.location.scope();
    if (!scope) {
      this.campaignsState.set('error');
      return;
    }
    try {
      const list = await this.marketingApi.listCampaigns(scope);
      this.campaigns.set(list);
      this.campaignsState.set('ready');
    } catch {
      this.campaignsState.set('error');
    }
  }

  protected retryCampaigns(): void {
    void this.loadCampaigns();
  }

  protected async selectCampaign(campaign: CampaignView): Promise<void> {
    this.selectedCampaign.set(campaign);
    this.countsState.set('loading');
    const scope = this.location.scope();
    if (!scope) {
      this.countsState.set('error');
      return;
    }
    try {
      const result = await this.marketingApi.recipientCounts(scope, campaign.campaignId);
      this.counts.set(result);
      this.countsState.set('ready');
    } catch {
      this.countsState.set('error');
    }
  }

  protected retryCounts(): void {
    const campaign = this.selectedCampaign();
    if (campaign) {
      void this.selectCampaign(campaign);
    }
  }

  protected campaignStatusLabelKey(status: string): MessageKey {
    return `marketing.campaign.status.${status}` as MessageKey;
  }

  // ---------------------------------------------------------------- 7.9 promotions

  protected onPromoRange(range: DateRange): void {
    this.promoRange.set({ from: range.start, to: range.end });
    this.promoSelected.set(null);
    void this.loadPromotions();
  }

  /** The summary and the log for the chosen range, in step: the log follows the selected promotion, if any. */
  protected async loadPromotions(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.promoState.set('error');
      return;
    }
    this.promoState.set('loading');
    try {
      const summary = await this.discountApi.promotionSummary(
        scope.tenantId,
        this.promoRange(),
        scope.brandId,
      );
      this.promoSummary.set(summary.rows);
      this.promoProvenance.set(summary.provenance);
      this.promoState.set('ready');
    } catch {
      this.promoState.set('error');
      return;
    }
    await this.loadPromotionLog();
  }

  private async loadPromotionLog(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.promoLogState.set('error');
      return;
    }
    this.promoLogState.set('loading');
    try {
      const log = await this.discountApi.promotionRedemptions(
        scope.tenantId,
        this.promoRange(),
        this.promoSelected()?.promotionId ?? null,
        200,
      );
      this.promoLog.set(log.rows);
      this.promoLogState.set('ready');
    } catch {
      this.promoLogState.set('error');
    }
  }

  /**
   * Whether to offer «show customer» at all. A usability affordance and nothing more: the platform
   * enforces `customer.read` at the redemption's brand on the lookup itself, and a refusal there is
   * shown as one.
   */
  protected canRevealCustomer(): boolean {
    return this.capabilities.has('CUSTOMER_READ');
  }

  protected revealOf(row: PromotionRedemptionRow): RevealState | null {
    return this.reveals().get(row.redemptionId) ?? null;
  }

  /** The account a revealed row opens, or null while it is not (or cannot be) revealed. */
  protected accountIdOf(row: PromotionRedemptionRow): string | null {
    const state = this.revealOf(row);
    return state?.kind === 'account' ? state.accountId : null;
  }

  /**
   * Resolves one redemption to its customer account, recorded by the platform as a security fact
   * against that account. Scoped by the row's own brand and promotion: the log is read across the
   * tenant, and the lookup is authorized where the redemption happened.
   */
  protected async showCustomer(row: PromotionRedemptionRow): Promise<void> {
    const current = this.revealOf(row);
    if (current?.kind === 'loading' || current?.kind === 'account' || current?.kind === 'guest') {
      return;
    }
    const scope = this.location.scope();
    if (!scope) {
      this.setReveal(row, { kind: 'error' });
      return;
    }
    this.setReveal(row, { kind: 'loading' });
    try {
      const answer = await this.discountApi.revealRedemptionCustomer(
        { tenantId: scope.tenantId, brandId: row.brandId },
        row.promotionId,
        row.redemptionId,
        WHO_REDEEMED_PURPOSE,
      );
      this.setReveal(
        row,
        answer.customerAccountId
          ? { kind: 'account', accountId: answer.customerAccountId }
          : { kind: 'guest' },
      );
    } catch (failure) {
      this.setReveal(
        row,
        failure instanceof ApiError && failure.status === 403
          ? { kind: 'denied' }
          : { kind: 'error' },
      );
    }
  }

  private setReveal(row: PromotionRedemptionRow, state: RevealState): void {
    this.reveals.update((held) => new Map(held).set(row.redemptionId, state));
  }

  protected selectPromotion(row: PromotionSummaryRow | null): void {
    this.promoSelected.set(row);
    void this.loadPromotionLog();
  }

  protected retryPromotions(): void {
    void this.loadPromotions();
  }

  protected sourceLabelKey(kind: string): PromotionTextKey {
    return `report.source.${kind}` as PromotionTextKey;
  }

  protected orderStatus(status: string | null): string {
    return status === null
      ? promotionText(this.i18n.locale(), 'report.status.none')
      : orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  /** The platform stores whole som for UZS (ADR 0018); the fact's `...Som` columns are that amount. */
  protected som(amount: number): string {
    return formatMoney({ amountMinor: amount, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }

  /** `—` for an average over no orders, never a zero that reads as a figure. */
  protected somOrDash(amount: number | null): string {
    return amount === null ? '—' : this.som(amount);
  }

  /** A pseudonym is long and opaque; the first characters tell two apart, the full value stays in the tooltip. */
  protected shortSubject(subject: string | null): string {
    return subject === null ? '—' : subject.slice(0, 10);
  }
}
