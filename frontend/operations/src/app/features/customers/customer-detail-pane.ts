import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';

import { Versioned } from '../../core/api/aggregate-version';
import { CursorState, firstPage, nextPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatDate, formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { customerStatusLabel } from './customer-status';
import {
  BlacklistStatus,
  ConsentDecision,
  CustomerAddressFields,
  CustomerOrderSummary,
  CustomerProfile,
  CustomersApi,
  LoyaltyBalance,
  LoyaltyEntry,
  RevealedBlacklistEntry,
  RevealedContact,
  RevealedCustomerAddress,
} from './customers-api';

type Tab = 'profile' | 'addresses' | 'orders' | 'consent' | 'cashback' | 'blacklist';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/**
 * Fixed, English, machine-facing purpose strings for every ADR 0029 reveal on
 * this pane — the same reason `order-detail-pane.ts`'s `REVEAL_PURPOSE` is
 * not translated (its own doc explains why): these are read by whoever
 * reviews the audit log, not the operator.
 */
const REVEAL_PURPOSE = {
  contacts: 'Operations console: view customer contact details',
  dateOfBirth: 'Operations console: view customer date of birth',
  addresses: 'Operations console: view customer addresses',
  blacklistHistory: 'Operations console: view blacklist history',
} as const;

/**
 * The address form's own draft shape: every field a plain, possibly-empty
 * string, unlike {@link CustomerAddressFields} where `line1`/`city`/`district`
 * are required and the rest are `string | null`. Keeping the draft
 * all-string avoids coercing an empty required field to `null` mid-edit,
 * which `CustomerAddressFields`'s own type would otherwise forbid; {@link
 * toAddressFields} is the one place the draft becomes the real request.
 */
interface AddressFormState {
  readonly line1: string;
  readonly line2: string;
  readonly city: string;
  readonly district: string;
  readonly postalCode: string;
  readonly entrance: string;
  readonly floor: string;
  readonly apartment: string;
  readonly landmark: string;
}

const EMPTY_ADDRESS_FORM: AddressFormState = {
  line1: '',
  line2: '',
  city: '',
  district: '',
  postalCode: '',
  entrance: '',
  floor: '',
  apartment: '',
  landmark: '',
};

function formFromAddressFields(fields: CustomerAddressFields): AddressFormState {
  return {
    line1: fields.line1,
    line2: fields.line2 ?? '',
    city: fields.city,
    district: fields.district,
    postalCode: fields.postalCode ?? '',
    entrance: fields.entrance ?? '',
    floor: fields.floor ?? '',
    apartment: fields.apartment ?? '',
    landmark: fields.landmark ?? '',
  };
}

function toAddressFields(form: AddressFormState): CustomerAddressFields {
  return {
    line1: form.line1.trim(),
    line2: form.line2.trim() || null,
    city: form.city.trim(),
    district: form.district.trim(),
    postalCode: form.postalCode.trim() || null,
    entrance: form.entrance.trim() || null,
    floor: form.floor.trim() || null,
    apartment: form.apartment.trim() || null,
    landmark: form.landmark.trim() || null,
  };
}

/**
 * 5.2 Customer detail — the screen Delever does not have.
 *
 * **What this wave builds, and what it does not.** Profile + DOB, addresses
 * (operator-visible and editable), order history + reorder, blacklist with
 * reason/actor/expiry and its enforcement point, consent history, and manual
 * identity merge are all built. Cashback (loyalty points) balance and ledger
 * are built by reusing the already-built loyalty module — see the cashback
 * tab's own note on what "cashback" maps to here. Three things named in the
 * frontend information architecture are honestly not built, each named where
 * it would otherwise appear rather than silently omitted: a customer-funded
 * deposit balance (`loyalty`'s own package doc: "no deposit account… and
 * none is deferred" — an architectural exclusion, not a gap), promo code
 * redemptions (the pricing module has no redemption ledger at all), and
 * reviews left (no review/feedback entity exists in this codebase yet — 5.4
 * and 5.5 are tier 2/3 and out of scope for this wave regardless).
 */
@Component({
  selector: 'q-customer-detail-pane',
  imports: [TPipe],
  templateUrl: './customer-detail-pane.html',
  styleUrl: './customer-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerDetailPane {
  private readonly api = inject(CustomersApi);
  private readonly baseLocation = inject(CurrentLocation);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  /** Route param, bound by `withComponentInputBinding()` — see `order-detail-pane.ts` for the same idiom. */
  readonly accountId = input.required<string>();

  protected readonly activeTab = signal<Tab>('profile');
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly profile = signal<Versioned<CustomerProfile> | null>(null);
  protected readonly notice = signal<string | null>(null);

  constructor() {
    // The route reuses this component across an `:accountId` change (default
    // RouteReuseStrategy), so a plain constructor-only load only fires once —
    // the same reason `location-detail-pane.ts` re-reads inside an `effect()`
    // keyed on the input signal rather than on init.
    effect(() => {
      const id = this.accountId();
      void this.load(id);
    });
  }

  protected selectTab(tab: Tab): void {
    this.activeTab.set(tab);
    this.loadTabData(tab);
  }

  protected statusLabel(status: string): string {
    return customerStatusLabel(status, (key) => this.i18n.t(key));
  }

  protected formatRegisteredAt(instant: string): string {
    return formatDate(new Date(instant), PLACEHOLDER_TIME_ZONE);
  }

  protected dismissNotice(): void {
    this.notice.set(null);
  }

  private noticeFrom(error: unknown): void {
    if (error instanceof ApiError) {
      this.notice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
    } else {
      throw error;
    }
  }

  private scope() {
    return this.baseLocation.scope();
  }

  private async load(accountId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.resetTabState();
    await this.baseLocation.ensureLoaded();
    const scope = this.scope();
    if (!scope) {
      this.denied.set(this.baseLocation.denied());
      this.loading.set(false);
      return;
    }
    this.denied.set(false);
    try {
      this.profile.set(await this.api.profile(scope, accountId));
      this.loadTabData(this.activeTab());
    } catch (error) {
      if (error instanceof ApiError) {
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  private loadTabData(tab: Tab): void {
    switch (tab) {
      case 'addresses':
        void this.loadAddresses();
        return;
      case 'orders':
        if (this.orders().length === 0) {
          void this.loadOrders(true);
        }
        return;
      case 'consent':
        void this.loadConsent();
        return;
      case 'cashback':
        void this.loadBalances();
        return;
      case 'blacklist':
        void this.loadBlacklistStatus();
        return;
      case 'profile':
        return;
    }
  }

  private resetTabState(): void {
    this.revealedContacts.set(null);
    this.dateOfBirth.set(undefined);
    this.editingProfile.set(false);
    this.addresses.set(null);
    this.orders.set([]);
    this.ordersState = firstPage();
    this.ordersHasMore.set(false);
    this.consentHistory.set(null);
    this.balances.set(null);
    this.expandedBalanceId.set(null);
    this.loyaltyEntries.set(null);
    this.blacklistStatus.set(null);
    this.blacklistHistory.set(null);
  }

  // ------------------------------------------------------------------ profile

  protected readonly editingProfile = signal(false);
  protected readonly profileSaving = signal(false);
  protected readonly draftDisplayName = signal('');
  protected readonly draftPreferredLocale = signal('');
  protected readonly draftPreferredTimezone = signal('');

  protected startEditingProfile(): void {
    const current = this.profile()?.value;
    this.draftDisplayName.set(current?.displayName ?? '');
    this.draftPreferredLocale.set(current?.preferredLocale ?? '');
    this.draftPreferredTimezone.set(current?.preferredTimezone ?? '');
    this.editingProfile.set(true);
  }

  protected async saveProfile(): Promise<void> {
    const scope = this.scope();
    const current = this.profile();
    if (!scope || !current || this.profileSaving()) {
      return;
    }
    this.profileSaving.set(true);
    try {
      const updated = await this.api.updateProfile(
        scope,
        this.accountId(),
        {
          displayName: this.draftDisplayName().trim() || null,
          preferredLocale: this.draftPreferredLocale().trim() || null,
          preferredTimezone: this.draftPreferredTimezone().trim() || null,
        },
        current.value.version,
      );
      this.profile.set({ value: updated, version: updated.version });
      this.editingProfile.set(false);
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.profileSaving.set(false);
    }
  }

  protected readonly revealedContacts = signal<readonly RevealedContact[] | null>(null);
  protected readonly revealingContacts = signal(false);

  protected async revealContacts(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.revealingContacts()) {
      return;
    }
    this.revealingContacts.set(true);
    try {
      this.revealedContacts.set(
        await this.api.revealContacts(scope, this.accountId(), REVEAL_PURPOSE.contacts),
      );
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.revealingContacts.set(false);
    }
  }

  protected readonly newContactValue = signal('');
  protected readonly addingContact = signal(false);

  protected async addContact(): Promise<void> {
    const scope = this.scope();
    const value = this.newContactValue().trim();
    if (!scope || !value || this.addingContact()) {
      return;
    }
    this.addingContact.set(true);
    try {
      await this.api.addContact(
        scope,
        this.accountId(),
        'PHONE',
        value,
        this.revealedContacts()?.length === 0,
      );
      this.newContactValue.set('');
      await this.revealContacts();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.addingContact.set(false);
    }
  }

  /** `undefined` = never revealed this load; `null` = revealed and genuinely absent. */
  protected readonly dateOfBirth = signal<string | null | undefined>(undefined);
  protected readonly revealingDob = signal(false);
  protected readonly editingDob = signal(false);
  protected readonly draftDob = signal('');
  protected readonly dobSaving = signal(false);

  protected async revealDob(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.revealingDob()) {
      return;
    }
    this.revealingDob.set(true);
    try {
      this.dateOfBirth.set(
        await this.api.revealDateOfBirth(scope, this.accountId(), REVEAL_PURPOSE.dateOfBirth),
      );
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.revealingDob.set(false);
    }
  }

  protected startEditingDob(): void {
    this.draftDob.set(this.dateOfBirth() ?? '');
    this.editingDob.set(true);
  }

  protected async saveDob(): Promise<void> {
    const scope = this.scope();
    const current = this.profile();
    if (!scope || !current || this.dobSaving()) {
      return;
    }
    this.dobSaving.set(true);
    try {
      await this.api.setDateOfBirth(
        scope,
        this.accountId(),
        this.draftDob().trim() || null,
        current.value.version,
      );
      const refreshed = await this.api.profile(scope, this.accountId());
      this.profile.set(refreshed);
      this.dateOfBirth.set(this.draftDob().trim() || null);
      this.editingDob.set(false);
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.dobSaving.set(false);
    }
  }

  // ---------------------------------------------------------------- addresses

  protected readonly addresses = signal<readonly RevealedCustomerAddress[] | null>(null);
  protected readonly loadingAddresses = signal(false);
  protected readonly editingAddressId = signal<string | null>(null);
  protected readonly addingAddress = signal(false);
  protected readonly addressSaving = signal(false);
  protected readonly addressLabel = signal('');
  protected readonly addressFields = signal<AddressFormState>(EMPTY_ADDRESS_FORM);
  protected readonly addressInstructions = signal('');

  private async loadAddresses(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingAddresses()) {
      return;
    }
    this.loadingAddresses.set(true);
    try {
      this.addresses.set(
        await this.api.revealAddresses(scope, this.accountId(), REVEAL_PURPOSE.addresses),
      );
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingAddresses.set(false);
    }
  }

  protected startAddingAddress(): void {
    this.addressLabel.set('');
    this.addressFields.set(EMPTY_ADDRESS_FORM);
    this.addressInstructions.set('');
    this.editingAddressId.set(null);
    this.addingAddress.set(true);
  }

  protected startEditingAddress(address: RevealedCustomerAddress): void {
    this.addressLabel.set(address.label);
    this.addressFields.set(formFromAddressFields(address.fields));
    this.addressInstructions.set(address.deliveryInstructions ?? '');
    this.editingAddressId.set(address.id);
    this.addingAddress.set(false);
  }

  protected setAddressField(field: keyof AddressFormState, value: string): void {
    this.addressFields.update((current) => ({ ...current, [field]: value }));
  }

  protected cancelAddressForm(): void {
    this.addingAddress.set(false);
    this.editingAddressId.set(null);
  }

  protected async saveNewAddress(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.addressSaving()) {
      return;
    }
    this.addressSaving.set(true);
    try {
      await this.api.addAddress(scope, this.accountId(), {
        label: this.addressLabel().trim(),
        fields: toAddressFields(this.addressFields()),
        deliveryInstructions: this.addressInstructions().trim() || null,
        coordinateSource: 'NOT_GEOCODED',
      });
      this.addingAddress.set(false);
      await this.loadAddresses();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.addressSaving.set(false);
    }
  }

  /**
   * `original` supplies the coordinate and its source unchanged: this form has
   * no map or pin picker, and the backend refuses a `coordinateSource` that
   * claims a point (`GEOCODER`, `*_PIN`, `LEGACY_UNSOURCED`) with none
   * attached (`CustomerProfileService#requireCoordinatesMatchSource`) — so
   * editing the text fields must carry the existing point through rather
   * than silently dropping it.
   */
  protected async saveEditedAddress(original: RevealedCustomerAddress): Promise<void> {
    const scope = this.scope();
    if (!scope || this.addressSaving()) {
      return;
    }
    this.addressSaving.set(true);
    try {
      await this.api.updateAddress(
        scope,
        this.accountId(),
        original.id,
        {
          label: this.addressLabel().trim(),
          fields: toAddressFields(this.addressFields()),
          deliveryInstructions: this.addressInstructions().trim() || null,
          latitude: original.latitude,
          longitude: original.longitude,
          coordinateSource: original.coordinateSource,
        },
        original.version,
      );
      this.editingAddressId.set(null);
      await this.loadAddresses();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.addressSaving.set(false);
    }
  }

  protected async archiveAddress(addressId: string, expectedVersion: number): Promise<void> {
    const scope = this.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.archiveAddress(scope, this.accountId(), addressId, expectedVersion);
      await this.loadAddresses();
    } catch (error) {
      this.noticeFrom(error);
    }
  }

  // ------------------------------------------------------------------- orders

  protected readonly orders = signal<readonly CustomerOrderSummary[]>([]);
  protected readonly loadingOrders = signal(false);
  protected readonly ordersHasMore = signal(false);
  private ordersState: CursorState = firstPage();

  private async loadOrders(reset: boolean): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingOrders()) {
      return;
    }
    this.loadingOrders.set(true);
    try {
      if (reset) {
        this.ordersState = firstPage();
      }
      const page = await this.api.ordersPage(scope, this.accountId(), this.ordersState);
      this.orders.update((current) => (reset ? [...page.items] : [...current, ...page.items]));
      const next = nextPage(this.ordersState, page);
      this.ordersHasMore.set(next !== null);
      if (next) {
        this.ordersState = next;
      }
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingOrders.set(false);
    }
  }

  protected loadMoreOrders(): void {
    void this.loadOrders(false);
  }

  /**
   * §5.2 "reorder": navigates to the take-order flow, which is not built
   * (`operations-spec/orders.md` §5, the `/orders/new` route's own
   * `NotBuiltPage`). This link goes there honestly rather than fabricating a
   * working reorder button — "omit, do not disable": it navigates somewhere,
   * and that somewhere says plainly what is missing.
   */
  protected reorder(): void {
    void this.router.navigate(['/orders/new']);
  }

  protected openOrder(orderId: string): void {
    void this.router.navigate(['/orders', orderId]);
  }

  protected formatOrderTotal(order: CustomerOrderSummary): string {
    return formatMoney(
      { amountMinor: order.totalMinor, currency: order.currency },
      this.i18n.locale(),
      {
        withUnit: true,
      },
    );
  }

  protected formatOrderPlacedAt(placedAt: string): string {
    return formatDateTime(new Date(placedAt), PLACEHOLDER_TIME_ZONE);
  }

  // ------------------------------------------------------------------ consent

  protected readonly consentHistory = signal<readonly ConsentDecision[] | null>(null);
  protected readonly loadingConsent = signal(false);
  protected readonly recordingConsent = signal(false);
  protected readonly consentPurpose = signal('marketing');
  protected readonly consentChannel = signal('');
  protected readonly consentDecision = signal<'GRANTED' | 'WITHDRAWN'>('GRANTED');

  private async loadConsent(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingConsent()) {
      return;
    }
    this.loadingConsent.set(true);
    try {
      this.consentHistory.set(await this.api.consentHistory(scope, this.accountId()));
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingConsent.set(false);
    }
  }

  protected async recordConsent(): Promise<void> {
    const scope = this.scope();
    const purpose = this.consentPurpose().trim();
    if (!scope || !purpose || this.recordingConsent()) {
      return;
    }
    this.recordingConsent.set(true);
    try {
      await this.api.recordConsent(scope, this.accountId(), {
        purpose,
        channel: this.consentChannel().trim() || null,
        decision: this.consentDecision(),
        policyVersion: 'operator-recorded-v1',
        source: 'SUPPORT_AGENT',
      });
      await this.loadConsent();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.recordingConsent.set(false);
    }
  }

  protected formatConsentDecidedAt(decidedAt: string): string {
    return formatDateTime(new Date(decidedAt), PLACEHOLDER_TIME_ZONE);
  }

  // ----------------------------------------------------------------- cashback

  protected readonly balances = signal<readonly LoyaltyBalance[] | null>(null);
  protected readonly loadingBalances = signal(false);
  protected readonly expandedBalanceId = signal<string | null>(null);
  protected readonly loyaltyEntries = signal<readonly LoyaltyEntry[] | null>(null);
  protected readonly loadingEntries = signal(false);

  private async loadBalances(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingBalances()) {
      return;
    }
    this.loadingBalances.set(true);
    try {
      this.balances.set(await this.api.loyaltyBalances(scope, this.accountId()));
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingBalances.set(false);
    }
  }

  protected async toggleBalance(loyaltyAccountId: string): Promise<void> {
    if (this.expandedBalanceId() === loyaltyAccountId) {
      this.expandedBalanceId.set(null);
      return;
    }
    this.expandedBalanceId.set(loyaltyAccountId);
    const scope = this.scope();
    if (!scope) {
      return;
    }
    this.loadingEntries.set(true);
    try {
      this.loyaltyEntries.set(
        await this.api.loyaltyEntries(scope, this.accountId(), loyaltyAccountId),
      );
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingEntries.set(false);
    }
  }

  protected formatBalanceMoney(money: LoyaltyBalance['balance']): string {
    return formatMoney(money, this.i18n.locale(), { withUnit: true });
  }

  // ---------------------------------------------------------------- blacklist

  protected readonly blacklistStatus = signal<BlacklistStatus | null>(null);
  protected readonly loadingBlacklistStatus = signal(false);
  protected readonly blacklistHistory = signal<readonly RevealedBlacklistEntry[] | null>(null);
  protected readonly revealingBlacklistHistory = signal(false);
  protected readonly addingBlacklistEntry = signal(false);
  protected readonly blacklistReason = signal('');
  protected readonly blacklistExpiresAt = signal('');
  protected readonly blacklistSaving = signal(false);
  protected readonly liftingBlacklist = signal(false);
  protected readonly liftReason = signal('');

  private async loadBlacklistStatus(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingBlacklistStatus()) {
      return;
    }
    this.loadingBlacklistStatus.set(true);
    try {
      this.blacklistStatus.set(await this.api.blacklistStatus(scope, this.accountId()));
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingBlacklistStatus.set(false);
    }
  }

  protected async revealBlacklistHistory(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.revealingBlacklistHistory()) {
      return;
    }
    this.revealingBlacklistHistory.set(true);
    try {
      this.blacklistHistory.set(
        await this.api.revealBlacklistHistory(
          scope,
          this.accountId(),
          REVEAL_PURPOSE.blacklistHistory,
        ),
      );
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.revealingBlacklistHistory.set(false);
    }
  }

  protected startAddingBlacklistEntry(): void {
    this.blacklistReason.set('');
    this.blacklistExpiresAt.set('');
    this.addingBlacklistEntry.set(true);
  }

  protected async submitBlacklistEntry(): Promise<void> {
    const scope = this.scope();
    const reason = this.blacklistReason().trim();
    if (!scope || !reason || this.blacklistSaving()) {
      return;
    }
    this.blacklistSaving.set(true);
    try {
      await this.api.addBlacklistEntry(scope, this.accountId(), {
        reason,
        expiresAt: this.blacklistExpiresAt()
          ? new Date(this.blacklistExpiresAt()).toISOString()
          : null,
      });
      this.addingBlacklistEntry.set(false);
      await this.loadBlacklistStatus();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.blacklistSaving.set(false);
    }
  }

  protected async lift(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.liftingBlacklist()) {
      return;
    }
    this.liftingBlacklist.set(true);
    try {
      await this.api.liftBlacklistEntry(scope, this.accountId(), this.liftReason().trim() || null);
      this.liftReason.set('');
      await this.loadBlacklistStatus();
      this.blacklistHistory.set(null);
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.liftingBlacklist.set(false);
    }
  }

  // ------------------------------------------------------------------- merge

  protected readonly mergeDialogOpen = signal(false);
  protected readonly mergeQuery = signal('');
  protected readonly mergeCandidates = signal<
    readonly { readonly id: string; readonly displayName: string | null }[]
  >([]);
  protected readonly mergeSearching = signal(false);
  protected readonly mergeTargetId = signal<string | null>(null);
  protected readonly merging = signal(false);

  protected openMergeDialog(): void {
    this.mergeQuery.set('');
    this.mergeCandidates.set([]);
    this.mergeTargetId.set(null);
    this.mergeDialogOpen.set(true);
  }

  protected closeMergeDialog(): void {
    this.mergeDialogOpen.set(false);
  }

  protected async searchMergeTarget(): Promise<void> {
    const scope = this.scope();
    const query = this.mergeQuery().trim();
    if (!scope || !query || this.mergeSearching()) {
      return;
    }
    this.mergeSearching.set(true);
    try {
      const page = await this.api.list(scope, firstPage(10), { query });
      this.mergeCandidates.set(
        page.items
          .filter((row) => row.id !== this.accountId())
          .map((row) => ({ id: row.id, displayName: row.displayName })),
      );
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.mergeSearching.set(false);
    }
  }

  protected selectMergeTarget(id: string): void {
    this.mergeTargetId.set(id);
  }

  protected async confirmMerge(): Promise<void> {
    const scope = this.scope();
    const target = this.mergeTargetId();
    const current = this.profile();
    if (!scope || !target || !current || this.merging()) {
      return;
    }
    this.merging.set(true);
    try {
      await this.api.merge(scope, this.accountId(), target, current.value.version);
      this.mergeDialogOpen.set(false);
      // The source is now MERGED; there is nothing further to show for it
      // here, so send the operator to the account that survived.
      void this.router.navigate(['/customers', target]);
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.merging.set(false);
    }
  }
}
