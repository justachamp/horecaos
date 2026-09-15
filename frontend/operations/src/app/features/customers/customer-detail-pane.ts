import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';

import { Versioned } from '../../core/api/aggregate-version';
import { CursorState, firstPage, nextPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { formatDate, formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ActorChip } from '../../shared/ui/actor-chip';
import { MoneyInput } from '../../shared/ui/money-input';
import { describeApiError } from '../orders/order-errors';
import { orderStatusLabel } from '../orders/order-status';
import { BrandProfileApi, BrandView } from '../settings/brand-profile/brand-profile-api';
import { customerStatusLabel } from './customer-status';
import { ReviewRow, ReviewsApi } from './reviews/reviews-api';
import {
  BlacklistStatus,
  ConsentDecision,
  ContactType,
  CustomerAddressFields,
  CustomerCoordinateSource,
  CustomerDiscountHistory,
  CustomerEligibility,
  CustomerOrderSummary,
  CustomerProfile,
  CustomersApi,
  ErasureRequest,
  LoyaltyAdjustmentRequest,
  LoyaltyAdjustmentResult,
  LoyaltyBalance,
  LoyaltyEntry,
  PromoRedemption,
  RevealedBlacklistEntry,
  RevealedContact,
  RevealedCustomerAddress,
} from './customers-api';

type Tab =
  | 'profile'
  | 'addresses'
  | 'orders'
  | 'consent'
  | 'cashback'
  | 'blacklist'
  | 'promos'
  | 'reviews'
  | 'erasure';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/**
 * The one marketing purpose the console records consent under (5.2b).
 *
 * <p>Three spellings of "marketing" were live at once before this: this
 * screen posted a lowercase `'marketing'`, the SendPulse import records
 * `'MARKETING'`, and `campaigns-page.ts` already defaults every new
 * campaign's own `consentPurpose` to `'MARKETING_PROMOTIONS'`. A consent
 * type registry is a separate, tenant-wide decision (ADR 0015/0044, not yet
 * built — `data-privacy-page.ts`'s own doc says so), so the fix here is not
 * to build one; it is to stop guessing and match the string campaigns
 * actually check.
 */
const CONSENT_PURPOSE = 'MARKETING_PROMOTIONS';

/** `MarketingEligibility#consentChannel`'s own vocabulary — the strings a consent decision is actually matched against. */
const CONSENT_CHANNELS = ['SMS', 'EMAIL', 'PUSH', 'TELEGRAM'] as const;
type ConsentChannel = (typeof CONSENT_CHANNELS)[number];

const CONSENT_CHANNEL_LABEL_KEYS: Readonly<Record<ConsentChannel, MessageKey>> = {
  SMS: 'customers.consent.channel.SMS',
  EMAIL: 'customers.consent.channel.EMAIL',
  PUSH: 'customers.consent.channel.PUSH',
  TELEGRAM: 'customers.consent.channel.TELEGRAM',
};

const CONTACT_TYPE_LABEL_KEYS: Readonly<Record<ContactType, MessageKey>> = {
  PHONE: 'customers.profile.contact.type.PHONE',
  EMAIL: 'customers.profile.contact.type.EMAIL',
};

/** `ck_contact_verification` (V0017) — the four values a contact point's `verification_status` may carry. */
const CONTACT_VERIFICATION_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  UNVERIFIED: 'customers.profile.contact.verification.UNVERIFIED',
  PENDING: 'customers.profile.contact.verification.PENDING',
  VERIFIED: 'customers.profile.contact.verification.VERIFIED',
  FAILED: 'customers.profile.contact.verification.FAILED',
};

/** `ck_address_coordinate_source` — `CustomerProfileService.CoordinateSource`'s six values. */
const COORDINATE_SOURCE_LABEL_KEYS: Readonly<Record<CustomerCoordinateSource, MessageKey>> = {
  NOT_GEOCODED: 'customers.address.coordinateSource.NOT_GEOCODED',
  LANDMARK_ONLY: 'customers.address.coordinateSource.LANDMARK_ONLY',
  GEOCODER: 'customers.address.coordinateSource.GEOCODER',
  CUSTOMER_PIN: 'customers.address.coordinateSource.CUSTOMER_PIN',
  OPERATOR_PIN: 'customers.address.coordinateSource.OPERATOR_PIN',
  LEGACY_UNSOURCED: 'customers.address.coordinateSource.LEGACY_UNSOURCED',
};

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
 * reviews left — the review entity now exists (ADR 0071, wave 59) and answers
 * this exact question filtered by customer (`reviews.order_reviews
 * .customer_account_id`), but wiring a tab here is out of this pane's own
 * scope; the operator reads the same rows today from the §5.4 Reviews screen.
 */
@Component({
  selector: 'q-customer-detail-pane',
  imports: [TPipe, ActorChip, MoneyInput],
  templateUrl: './customer-detail-pane.html',
  styleUrl: './customer-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerDetailPane {
  private readonly api = inject(CustomersApi);
  private readonly reviewsApi = inject(ReviewsApi);
  private readonly brandProfiles = inject(BrandProfileApi);
  private readonly baseLocation = inject(CurrentLocation);
  private readonly router = inject(Router);
  private readonly auth = inject(Auth);
  protected readonly capabilities = inject(SessionCapabilities);
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
      case 'promos':
        void this.loadDiscountHistory();
        return;
      case 'reviews':
        void this.loadReviews();
        return;
      case 'erasure':
        void this.loadErasureRequests();
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
    this.eligibility.set(null);
    this.balances.set(null);
    this.brandNames.set(new Map());
    this.expandedBalanceId.set(null);
    this.loyaltyEntries.set(null);
    this.adjustingBalanceId.set(null);
    this.adjustResult.set(null);
    this.blacklistStatus.set(null);
    this.blacklistHistory.set(null);
    this.discountHistory.set(null);
    this.customerReviews.set(null);
    this.erasureRequests.set(null);
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

  protected readonly newContactType = signal<ContactType>('PHONE');
  protected readonly newContactValue = signal('');
  protected readonly addingContact = signal(false);

  protected setNewContactType(type: string): void {
    this.newContactType.set(type === 'EMAIL' ? 'EMAIL' : 'PHONE');
  }

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
        this.newContactType(),
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

  protected readonly editingContactId = signal<string | null>(null);
  protected readonly editContactValue = signal('');
  protected readonly contactActionPending = signal(false);

  protected startEditingContact(contactId: string, currentValue: string): void {
    this.editingContactId.set(contactId);
    this.editContactValue.set(currentValue);
  }

  protected cancelEditingContact(): void {
    this.editingContactId.set(null);
  }

  protected async saveEditedContact(): Promise<void> {
    const scope = this.scope();
    const contactId = this.editingContactId();
    const value = this.editContactValue().trim();
    if (!scope || !contactId || !value || this.contactActionPending()) {
      return;
    }
    this.contactActionPending.set(true);
    try {
      await this.api.updateContact(scope, this.accountId(), contactId, value);
      this.editingContactId.set(null);
      await this.refreshContacts();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.contactActionPending.set(false);
    }
  }

  protected async removeContact(contactId: string): Promise<void> {
    const scope = this.scope();
    if (!scope || this.contactActionPending()) {
      return;
    }
    this.contactActionPending.set(true);
    try {
      await this.api.removeContact(scope, this.accountId(), contactId);
      await this.refreshContacts();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.contactActionPending.set(false);
    }
  }

  protected async makeContactPrimary(contactId: string): Promise<void> {
    const scope = this.scope();
    if (!scope || this.contactActionPending()) {
      return;
    }
    this.contactActionPending.set(true);
    try {
      await this.api.setPrimaryContact(scope, this.accountId(), contactId);
      await this.refreshContacts();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.contactActionPending.set(false);
    }
  }

  /** Re-reads whichever of the profile summary or the full reveal this pane currently shows. */
  private async refreshContacts(): Promise<void> {
    const scope = this.scope();
    if (!scope) {
      return;
    }
    this.profile.set(await this.api.profile(scope, this.accountId()));
    if (this.revealedContacts()) {
      await this.revealContacts();
    }
  }

  /** The decrypted value for one contact point, once {@link revealContacts} has run; null before that, or for a value this account no longer holds. */
  protected revealedValueFor(contactId: string): string | null {
    return this.revealedContacts()?.find((contact) => contact.id === contactId)?.value ?? null;
  }

  protected contactTypeLabel(type: ContactType): string {
    return this.i18n.t(CONTACT_TYPE_LABEL_KEYS[type]);
  }

  /** Known values only (`ck_contact_verification`, V0017); an unrecognised one renders as the raw wire value. */
  protected contactVerificationLabel(status: string): string {
    return status in CONTACT_VERIFICATION_LABEL_KEYS
      ? this.i18n.t(
          CONTACT_VERIFICATION_LABEL_KEYS[status as keyof typeof CONTACT_VERIFICATION_LABEL_KEYS],
        )
      : status;
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

  /**
   * `NOT_GEOCODED` and `LANDMARK_ONLY` carry no pin — the whole reason this
   * row exists (5.2c): a phone order captured from one of these cannot be
   * zone-resolved or tariffed by location, only by the free-text landmark.
   */
  protected coordinateSourceLabel(source: CustomerCoordinateSource): string {
    return this.i18n.t(COORDINATE_SOURCE_LABEL_KEYS[source]);
  }

  protected addressHasPin(source: CustomerCoordinateSource): boolean {
    return source !== 'NOT_GEOCODED' && source !== 'LANDMARK_ONLY';
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
   * §5.2 row 5.2d "Повторить": hands the new-order screen (wave P14) this
   * order and this customer through the identical query params that
   * screen's own history popover would produce, so `NewOrderPage.ngOnInit`
   * pre-selects the customer and resolves the same staff reorder-plan
   * wrapper (`GET .../customers/{accountId}/orders/{orderId}/reorder`) it
   * already calls from its own header — the operator lands with the basket
   * already filled rather than retyping it by hand.
   */
  protected reorder(orderId: string): void {
    void this.router.navigate(['/orders/new'], {
      queryParams: { reorderAccountId: this.accountId(), reorderOrderId: orderId },
    });
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

  /** Row 5.2d: the console's own label map — never the raw wire enum. */
  protected orderStatusText(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  // ------------------------------------------------------------------ consent

  protected readonly consentHistory = signal<readonly ConsentDecision[] | null>(null);
  protected readonly loadingConsent = signal(false);
  protected readonly recordingConsent = signal(false);
  /** Fixed rather than free text (5.2b) — see {@link CONSENT_PURPOSE}'s own doc. */
  protected readonly consentPurpose = CONSENT_PURPOSE;
  protected readonly consentChannels = CONSENT_CHANNELS;
  protected readonly consentChannel = signal<ConsentChannel>('SMS');
  protected readonly consentDecision = signal<'GRANTED' | 'WITHDRAWN'>('GRANTED');
  /** No default: a fabricated version was the bug (5.2b), and there is no registry yet to pick a real one from. */
  protected readonly consentPolicyVersion = signal('');

  protected readonly eligibility = signal<CustomerEligibility | null>(null);
  protected readonly loadingEligibility = signal(false);

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
    await this.loadEligibility();
  }

  protected setConsentChannel(channel: string): void {
    this.consentChannel.set(
      (CONSENT_CHANNELS as readonly string[]).includes(channel)
        ? (channel as ConsentChannel)
        : 'SMS',
    );
    void this.loadEligibility();
  }

  private async loadEligibility(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingEligibility()) {
      return;
    }
    this.loadingEligibility.set(true);
    try {
      this.eligibility.set(
        await this.api.eligibility(
          scope,
          this.accountId(),
          scope.brandId,
          this.consentPurpose,
          this.consentChannel(),
        ),
      );
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingEligibility.set(false);
    }
  }

  protected async recordConsent(): Promise<void> {
    const scope = this.scope();
    const policyVersion = this.consentPolicyVersion().trim();
    if (!scope || !policyVersion || this.recordingConsent()) {
      return;
    }
    this.recordingConsent.set(true);
    try {
      await this.api.recordConsent(scope, this.accountId(), {
        brandId: scope.brandId,
        purpose: this.consentPurpose,
        channel: this.consentChannel(),
        decision: this.consentDecision(),
        policyVersion,
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

  protected consentChannelLabel(channel: ConsentChannel): string {
    return this.i18n.t(CONSENT_CHANNEL_LABEL_KEYS[channel]);
  }

  protected eligibilityRefusalLabel(reason: 'CONSENT_WITHHELD' | 'NO_VERIFIED_ENDPOINT'): string {
    return this.i18n.t(
      reason === 'CONSENT_WITHHELD'
        ? 'customers.consent.eligibility.refusal.CONSENT_WITHHELD'
        : 'customers.consent.eligibility.refusal.NO_VERIFIED_ENDPOINT',
    );
  }

  // ----------------------------------------------------------------- cashback

  protected readonly balances = signal<readonly LoyaltyBalance[] | null>(null);
  protected readonly loadingBalances = signal(false);
  /** brandId -> displayName, for labelling each balance card (row 5.2e) — see {@link brandLabel}'s own doc. */
  protected readonly brandNames = signal<ReadonlyMap<string, string>>(new Map());
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
    // A brand name lookup that fails (a role holding LOYALTY_READ but not
    // BRAND_READ, say) still leaves every balance card labelled — with its
    // raw id, per brandLabel's own fallback — rather than blocking the tab
    // the way new-order-page.ts's own channel-list read is already allowed
    // to degrade.
    try {
      const brands: readonly BrandView[] = await this.brandProfiles.list(scope.tenantId);
      this.brandNames.set(new Map(brands.map((brand) => [brand.id, brand.displayName])));
    } catch {
      this.brandNames.set(new Map());
    }
  }

  /**
   * Row 5.2e: "per-brand separation is the endpoint's whole point and two
   * brands render as two indistinguishable cards" — this is the label that
   * fixes it. Falls back to the raw id when the tenant's brand list could
   * not be read, so a lookup failure degrades to an ugly-but-true label
   * rather than a blank one.
   */
  protected brandLabel(brandId: string): string {
    return this.brandNames().get(brandId) ?? brandId;
  }

  protected async toggleBalance(loyaltyAccountId: string): Promise<void> {
    if (this.expandedBalanceId() === loyaltyAccountId) {
      this.expandedBalanceId.set(null);
      return;
    }
    this.expandedBalanceId.set(loyaltyAccountId);
    await this.refreshEntries(loyaltyAccountId);
  }

  private async refreshEntries(loyaltyAccountId: string): Promise<void> {
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

  /**
   * Row 5.2e: "ledger rows also print entry.amountMinor raw while the
   * balance above uses formatMoney, so the two numbers look like different
   * currencies" — `LoyaltyEntry` carries no currency of its own
   * (`LoyaltyOperationsController.EntryResponse`'s own doc: it lives on the
   * balance this entry belongs to, already fetched), so the enclosing
   * balance's currency is threaded in here rather than re-fetched per row.
   */
  protected formatEntryAmount(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  // --------------------------------------------------- row 5.2e: manual adjustment

  protected readonly adjustingBalanceId = signal<string | null>(null);
  protected readonly adjustDirection = signal<'CREDIT' | 'DEBIT'>('CREDIT');
  protected readonly adjustAmountMinor = signal(0);
  protected readonly adjustReasonCode = signal('');
  protected readonly adjustReason = signal('');
  protected readonly adjustSaving = signal(false);
  protected readonly adjustResult = signal<LoyaltyAdjustmentResult | null>(null);

  /** `loyalty-page.ts:54-56`'s own claim that this UI is "already built... on Customer detail" was not true; this is what makes it true. */
  protected canAdjustLoyalty(): boolean {
    return this.capabilities.has('LOYALTY_ADJUST');
  }

  protected startAdjusting(balance: LoyaltyBalance): void {
    this.adjustDirection.set('CREDIT');
    this.adjustAmountMinor.set(0);
    this.adjustReasonCode.set('');
    this.adjustReason.set('');
    this.adjustResult.set(null);
    this.adjustingBalanceId.set(balance.accountId);
  }

  protected cancelAdjusting(): void {
    this.adjustingBalanceId.set(null);
  }

  protected async submitAdjustment(balance: LoyaltyBalance): Promise<void> {
    const scope = this.scope();
    const subject = this.auth.subject();
    const reasonCode = this.adjustReasonCode().trim();
    const reason = this.adjustReason().trim();
    const magnitude = this.adjustAmountMinor();
    if (!scope || !subject || !reasonCode || !reason || magnitude <= 0 || this.adjustSaving()) {
      return;
    }
    this.adjustSaving.set(true);
    try {
      const request: LoyaltyAdjustmentRequest = {
        brandId: balance.brandId,
        amountMinor: this.adjustDirection() === 'DEBIT' ? -magnitude : magnitude,
        currency: balance.balance.currency,
        reasonCode,
        reason,
        // Ignored server-side (see `LoyaltyAdjustmentRequest.actorSubject`'s own
        // doc) — sent only because the published schema still requires it.
        actorSubject: subject,
      };
      const result = await this.api.adjustLoyalty(scope, this.accountId(), request);
      this.adjustResult.set(result);
      if (result.status !== 'DECLINED') {
        await this.loadBalances();
        if (this.expandedBalanceId() === balance.accountId) {
          await this.refreshEntries(balance.accountId);
        }
      }
      this.adjustingBalanceId.set(null);
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.adjustSaving.set(false);
    }
  }

  protected adjustmentStatusLabel(status: LoyaltyAdjustmentResult['status']): string {
    switch (status) {
      case 'NOT_REQUIRED':
      case 'APPROVED':
        return this.i18n.t('customers.cashback.adjust.result.applied');
      case 'PENDING':
        return this.i18n.t('customers.cashback.adjust.result.pending');
      case 'DECLINED':
        return this.i18n.t('customers.cashback.adjust.result.declined');
    }
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

  protected blacklistExpiryLabel(status: BlacklistStatus): string {
    return status.expired
      ? this.i18n.t('customers.blacklist.expiredNotLifted')
      : this.i18n.t('customers.blacklist.active');
  }

  // -------------------------------------------------------- row 5.2g: promo redemptions

  protected readonly discountHistory = signal<CustomerDiscountHistory | null>(null);
  protected readonly loadingDiscountHistory = signal(false);

  private async loadDiscountHistory(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingDiscountHistory()) {
      return;
    }
    this.loadingDiscountHistory.set(true);
    try {
      this.discountHistory.set(await this.api.discountHistory(scope, this.accountId()));
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingDiscountHistory.set(false);
    }
  }

  protected formatRedemptionAmount(redemption: PromoRedemption): string {
    return formatMoney(
      { amountMinor: redemption.amountMinor, currency: redemption.currency },
      this.i18n.locale(),
      { withUnit: true },
    );
  }

  protected formatRedemptionTotal(total: { amountMinor: number; currency: string }): string {
    return formatMoney(total, this.i18n.locale(), { withUnit: true });
  }

  protected redemptionStatusLabel(status: PromoRedemption['status']): string {
    switch (status) {
      case 'REDEEMED':
        return this.i18n.t('customers.promos.status.REDEEMED');
      case 'RESERVED':
        return this.i18n.t('customers.promos.status.RESERVED');
      case 'RELEASED':
        return this.i18n.t('customers.promos.status.RELEASED');
    }
  }

  // -------------------------------------------------------------- row 5.2h: reviews

  protected readonly customerReviews = signal<readonly ReviewRow[] | null>(null);
  protected readonly loadingReviews = signal(false);

  private async loadReviews(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingReviews()) {
      return;
    }
    this.loadingReviews.set(true);
    try {
      const page = await this.reviewsApi.list(scope, firstPage(50), {
        customerAccountId: this.accountId(),
      });
      this.customerReviews.set(page.items);
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingReviews.set(false);
    }
  }

  protected formatReviewSubmittedAt(submittedAt: string): string {
    return formatDateTime(new Date(submittedAt), PLACEHOLDER_TIME_ZONE);
  }

  // -------------------------------------------------------------- row 5/X.1: erasure

  protected readonly erasureRequests = signal<readonly ErasureRequest[] | null>(null);
  protected readonly loadingErasureRequests = signal(false);
  protected readonly raisingErasure = signal(false);
  protected readonly erasureActionPendingId = signal<string | null>(null);

  private async loadErasureRequests(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.loadingErasureRequests()) {
      return;
    }
    this.loadingErasureRequests.set(true);
    try {
      this.erasureRequests.set(await this.api.erasureRequests(scope, this.accountId()));
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingErasureRequests.set(false);
    }
  }

  /**
   * `CustomerController.requestErasure`/`cancelErasure` are both gated on
   * `CUSTOMER_MANAGE` server-side (see that controller's own Javadoc on
   * `CUSTOMER_ERASURE_RAISE`, written for this exact wave) — a support agent
   * raises a request on a customer's behalf without holding the tenant-wide
   * worklist capability, so this pane gates raise/withdraw on the
   * capability the endpoints actually require rather than the one the gap
   * map names, which the tenant-wide worklist alone still holds.
   */
  protected canRaiseErasure(): boolean {
    return this.capabilities.has('CUSTOMER_MANAGE');
  }

  protected canExecuteErasure(): boolean {
    return this.capabilities.has('CUSTOMER_ERASURE_EXECUTE');
  }

  protected hasPendingErasureRequest(): boolean {
    return (this.erasureRequests() ?? []).some((request) => request.status === 'PENDING');
  }

  protected async raiseErasure(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.raisingErasure()) {
      return;
    }
    this.raisingErasure.set(true);
    try {
      await this.api.requestErasure(scope, this.accountId());
      await this.loadErasureRequests();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.raisingErasure.set(false);
    }
  }

  protected async withdrawErasure(requestId: string): Promise<void> {
    const scope = this.scope();
    if (!scope || this.erasureActionPendingId() !== null) {
      return;
    }
    this.erasureActionPendingId.set(requestId);
    try {
      await this.api.cancelErasure(scope, this.accountId(), requestId);
      await this.loadErasureRequests();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.erasureActionPendingId.set(null);
    }
  }

  protected async executeErasureRequest(requestId: string): Promise<void> {
    const scope = this.scope();
    if (!scope || !this.canExecuteErasure() || this.erasureActionPendingId() !== null) {
      return;
    }
    this.erasureActionPendingId.set(requestId);
    try {
      await this.api.executeErasure(scope, this.accountId(), requestId);
      await this.loadErasureRequests();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.erasureActionPendingId.set(null);
    }
  }

  protected erasureStatusLabel(status: string): string {
    switch (status) {
      case 'PENDING':
        return this.i18n.t('customers.erasure.status.PENDING');
      case 'COMPLETED':
        return this.i18n.t('customers.erasure.status.COMPLETED');
      case 'CANCELLED':
        return this.i18n.t('customers.erasure.status.CANCELLED');
      default:
        return status;
    }
  }

  protected formatErasureAt(instant: string): string {
    return formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE);
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
