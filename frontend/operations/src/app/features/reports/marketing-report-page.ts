import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { firstPage } from '../../core/api/page';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { CustomerSummary, CustomersApi } from '../customers/customers-api';
import { customerStatusLabel } from '../customers/customer-status';
import { CampaignView, MarketingApi, RecipientCountsView } from '../marketing/marketing-api';
import { CustomerDiscountHistory, MarketingReportApi } from './marketing-report-api';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from './reports-filter-state';

type Tab = 'discounts' | 'campaigns';
type LoadState = 'idle' | 'loading' | 'ready' | 'denied' | 'error';

const TAB_DEFINITIONS: readonly { readonly id: Tab; readonly labelKey: MessageKey }[] = [
  { id: 'discounts', labelKey: 'reports.marketing.tab.discounts' },
  { id: 'campaigns', labelKey: 'reports.marketing.tab.campaigns' },
];

/**
 * 7.9 Marketing reports (`frontend-information-architecture.md` §7.9) — tier
 * 2. Two of the screen's four owned facts ship this wave: 7.9a per-customer
 * discount history and 7.9b campaign delivery counts. The other two —
 * promo-code summary and per-code redemption detail — stay named-not-built:
 * ADR 0023 forbids `reporting` reading `pricing` directly, so 7.9 needs
 * `reporting.fact_promotion_redemption`, whose grain needs a promotions ADR
 * that does not exist yet (`statistics.md` §7).
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
  imports: [TPipe, Combobox],
  templateUrl: './marketing-report-page.html',
  styleUrl: './marketing-report-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarketingReportPage {
  private readonly location = inject(CurrentLocation);
  private readonly customersApi = inject(CustomersApi);
  private readonly marketingApi = inject(MarketingApi);
  private readonly discountApi = inject(MarketingReportApi);
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
}
