import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Versioned } from '../../core/api/aggregate-version';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { CursorState, Page } from '../../core/api/page';
import { Money } from '../../core/format/money';

/**
 * Types mirror `CustomerController`, `CustomerOrderHistoryController`, and
 * `LoyaltyOperationsController`'s response records directly, the same
 * hand-copied-from-Java-source practice `order-detail.ts` documents and uses
 * for the identical reason: springdoc's component registry has already been
 * seen to collide two same-named records from different controllers
 * (`AddressResponse`), so the generated spec is not trusted blindly here
 * either.
 */

// ------------------------------------------------------------------ §5.1 the grid

export interface CustomerSummary {
  readonly id: string;
  readonly status: string;
  readonly displayName: string | null;
  readonly createdAt: string;
}

export interface CustomerCounts {
  readonly total: number;
  readonly registeredToday: number;
  readonly orderedToday: number;
}

export interface CustomerExportRow {
  readonly accountId: string;
  readonly status: string;
  readonly displayName: string | null;
  readonly phone: string | null;
}

export interface CreateCustomerRequest {
  readonly brandId: string;
  readonly phone: string;
  readonly displayName?: string | null;
}

// ---------------------------------------------------------------- §5.2 the detail

export type ContactType = 'PHONE' | 'EMAIL';

/** `ContactSummaryResponse` — named by kind and verification state, no value and therefore no decrypt. */
export interface ContactSummary {
  readonly id: string;
  readonly type: ContactType;
  readonly verificationStatus: string;
  readonly isPrimary: boolean;
}

/** `RevealedContact` — `GET .../contact-points?purpose=`. */
export interface RevealedContact {
  readonly id: string;
  readonly type: ContactType;
  readonly value: string;
  readonly verificationStatus: string;
  readonly isPrimary: boolean;
}

export interface CustomerProfile {
  readonly id: string;
  readonly status: string;
  readonly displayName: string | null;
  readonly preferredLocale: string | null;
  readonly preferredTimezone: string | null;
  readonly createdAt: string;
  readonly version: number;
  readonly hasDateOfBirth: boolean;
  readonly contactSummaries: readonly ContactSummary[];
}

export interface UpdateProfileRequest {
  readonly displayName?: string | null;
  readonly preferredLocale?: string | null;
  readonly preferredTimezone?: string | null;
}

/** Mirrors `CustomerProfileService.CoordinateSource`. */
export type CustomerCoordinateSource =
  | 'NOT_GEOCODED'
  | 'LANDMARK_ONLY'
  | 'GEOCODER'
  | 'CUSTOMER_PIN'
  | 'OPERATOR_PIN'
  | 'LEGACY_UNSOURCED';

/** `AddressFields` — дом/квартира/подъезд/этаж/ориентир as structured fields, never one line. */
export interface CustomerAddressFields {
  readonly line1: string;
  readonly line2?: string | null;
  readonly city: string;
  readonly district: string;
  readonly postalCode?: string | null;
  readonly entrance?: string | null;
  readonly floor?: string | null;
  readonly apartment?: string | null;
  readonly landmark?: string | null;
}

/** `RevealedAddress` — decrypted, `GET .../addresses?purpose=`. */
export interface RevealedCustomerAddress {
  readonly id: string;
  readonly label: string;
  readonly fields: CustomerAddressFields;
  readonly deliveryInstructions: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly coordinateSource: CustomerCoordinateSource;
  readonly version: number;
}

export interface SaveCustomerAddressRequest {
  readonly label: string;
  readonly fields: CustomerAddressFields;
  readonly deliveryInstructions?: string | null;
  readonly latitude?: number | null;
  readonly longitude?: number | null;
  readonly coordinateSource: CustomerCoordinateSource;
}

export interface ConsentDecision {
  readonly purpose: string;
  readonly brandId: string | null;
  readonly channel: string | null;
  readonly decision: 'GRANTED' | 'WITHDRAWN';
  readonly policyVersion: string;
  readonly source: string;
  readonly decidedAt: string;
}

export interface RecordConsentRequest {
  readonly brandId?: string | null;
  readonly purpose: string;
  readonly channel?: string | null;
  readonly decision: 'GRANTED' | 'WITHDRAWN';
  readonly policyVersion: string;
  readonly source: 'STOREFRONT' | 'SUPPORT_AGENT' | 'IMPORT' | 'MIGRATION' | 'API';
  readonly evidenceReference?: string | null;
}

export interface BlacklistStatus {
  readonly active: boolean;
  readonly expired: boolean;
  readonly expiresAt: string | null;
  readonly since: string | null;
}

/** `CustomerBlacklistService.RevealedEntry`. */
export interface RevealedBlacklistEntry {
  readonly id: string;
  readonly reason: string;
  readonly status: string;
  readonly actorType: string;
  readonly actorId: string;
  readonly createdAt: string;
  readonly expiresAt: string | null;
  readonly liftedAt: string | null;
  readonly liftedByActorId: string | null;
  readonly liftReason: string | null;
}

export interface AddBlacklistEntryRequest {
  readonly reason: string;
  readonly expiresAt?: string | null;
}

// ----------------------------------------------------------------------- orders

/** `CustomerOrderHistoryController.OrderSummaryResponse`. */
export interface CustomerOrderSummary {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly locationId: string;
  readonly fulfillmentMode: string;
  readonly status: string;
  readonly paymentStatus: string;
  readonly fulfillmentStatus: string;
  readonly currency: string;
  readonly totalMinor: number;
  readonly promisedAt: string | null;
  readonly version: number;
  readonly placedAt: string;
}

// ---------------------------------------------------------------------- cashback

/** `LoyaltyStorefrontController.BalanceResponse`, as `LoyaltyOperationsController.balances` returns it. */
export interface LoyaltyBalance {
  readonly accountId: string;
  readonly brandId: string;
  readonly balance: Money;
  readonly spendable: Money;
  readonly held: Money;
  readonly nextExpiryAt: string | null;
  readonly nextExpiryAmount: Money;
}

/** `LoyaltyOperationsController.EntryResponse`. */
export interface LoyaltyEntry {
  readonly id: string;
  readonly entryType: string;
  readonly amountMinor: number;
  readonly balanceAfterMinor: number;
  readonly lotId: string | null;
  readonly orderId: string | null;
  readonly tenderId: string | null;
  readonly reasonCode: string | null;
  readonly occurredAt: string;
}

/**
 * §5.1-5.2 of the Customers section: the CRM grid, one customer's detail, and
 * everything the detail pane's tabs read and write.
 */
@Injectable({ providedIn: 'root' })
export class CustomersApi {
  private readonly api = inject(ApiClient);

  // -------------------------------------------------------------- the grid

  list(
    scope: LocationScope,
    state: CursorState,
    filters: { status?: string; query?: string },
  ): Promise<Page<CustomerSummary>> {
    return firstValueFrom(
      this.api.page<CustomerSummary>(operationsPaths.customers(scope), state, {
        status: filters.status,
        query: filters.query,
      }),
    );
  }

  async counts(scope: LocationScope): Promise<CustomerCounts> {
    return (
      await firstValueFrom(this.api.get<CustomerCounts>(operationsPaths.customersCounts(scope)))
    ).value;
  }

  async exportFiltered(
    scope: LocationScope,
    filters: { status?: string; query?: string },
    purpose: string,
  ): Promise<readonly CustomerExportRow[]> {
    const params: Record<string, string> = { purpose };
    if (filters.status) {
      params['status'] = filters.status;
    }
    if (filters.query) {
      params['query'] = filters.query;
    }
    const result = await firstValueFrom(
      this.api.get<readonly CustomerExportRow[]>(operationsPaths.customersExport(scope), {
        params,
      }),
    );
    return result.value ?? [];
  }

  async create(scope: LocationScope, request: CreateCustomerRequest): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<CreateCustomerRequest, { id: string }>(
        operationsPaths.customers(scope),
        command(request),
      ),
    );
    return response.id;
  }

  // ------------------------------------------------------------- the profile

  async profile(scope: LocationScope, accountId: string): Promise<Versioned<CustomerProfile>> {
    return firstValueFrom(
      this.api.get<CustomerProfile>(operationsPaths.customer(scope, accountId)),
    );
  }

  async updateProfile(
    scope: LocationScope,
    accountId: string,
    request: UpdateProfileRequest,
    expectedVersion: number,
  ): Promise<CustomerProfile> {
    return firstValueFrom(
      this.api.put<UpdateProfileRequest, CustomerProfile>(
        operationsPaths.customerProfile(scope, accountId),
        command(request),
        { expectedVersion },
      ),
    );
  }

  async setDateOfBirth(
    scope: LocationScope,
    accountId: string,
    dateOfBirth: string | null,
    expectedVersion: number,
  ): Promise<void> {
    await firstValueFrom(
      this.api.put<{ dateOfBirth: string | null }, void>(
        operationsPaths.customerDateOfBirth(scope, accountId),
        command({ dateOfBirth }),
        { expectedVersion },
      ),
    );
  }

  async revealDateOfBirth(
    scope: LocationScope,
    accountId: string,
    purpose: string,
  ): Promise<string | null> {
    const result = await firstValueFrom(
      this.api.get<{ dateOfBirth: string | null }>(
        operationsPaths.customerDateOfBirth(scope, accountId),
        {
          params: { purpose },
        },
      ),
    );
    return result.value.dateOfBirth;
  }

  async revealContacts(
    scope: LocationScope,
    accountId: string,
    purpose: string,
  ): Promise<readonly RevealedContact[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RevealedContact[]>(
        operationsPaths.customerContactPoints(scope, accountId),
        {
          params: { purpose },
        },
      ),
    );
    return result.value ?? [];
  }

  async addContact(
    scope: LocationScope,
    accountId: string,
    type: ContactType,
    value: string,
    primary: boolean,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ type: ContactType; value: string; primary: boolean }, { id: string }>(
        operationsPaths.customerContactPoints(scope, accountId),
        command({ type, value, primary }),
      ),
    );
  }

  // ------------------------------------------------------------ the addresses

  async revealAddresses(
    scope: LocationScope,
    accountId: string,
    purpose: string,
  ): Promise<readonly RevealedCustomerAddress[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RevealedCustomerAddress[]>(
        operationsPaths.customerAddresses(scope, accountId),
        {
          params: { purpose },
        },
      ),
    );
    return result.value ?? [];
  }

  async addAddress(
    scope: LocationScope,
    accountId: string,
    request: SaveCustomerAddressRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<SaveCustomerAddressRequest, { id: string }>(
        operationsPaths.customerAddresses(scope, accountId),
        command(request),
      ),
    );
  }

  async updateAddress(
    scope: LocationScope,
    accountId: string,
    addressId: string,
    request: SaveCustomerAddressRequest,
    expectedVersion: number,
  ): Promise<void> {
    await firstValueFrom(
      this.api.put<SaveCustomerAddressRequest, RevealedCustomerAddress>(
        operationsPaths.customerAddress(scope, accountId, addressId),
        command(request),
        { expectedVersion },
      ),
    );
  }

  async archiveAddress(
    scope: LocationScope,
    accountId: string,
    addressId: string,
    expectedVersion: number,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        operationsPaths.customerAddress(scope, accountId, addressId),
        command(null),
        { expectedVersion },
      ),
    );
  }

  // -------------------------------------------------------------- the consent

  async consentHistory(
    scope: LocationScope,
    accountId: string,
  ): Promise<readonly ConsentDecision[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ConsentDecision[]>(
        operationsPaths.customerConsentDecisions(scope, accountId),
      ),
    );
    return result.value ?? [];
  }

  async recordConsent(
    scope: LocationScope,
    accountId: string,
    request: RecordConsentRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<RecordConsentRequest, { id: string }>(
        operationsPaths.customerConsentDecisions(scope, accountId),
        command(request),
      ),
    );
  }

  // ------------------------------------------------------------ the blacklist

  async blacklistStatus(scope: LocationScope, accountId: string): Promise<BlacklistStatus> {
    return (
      await firstValueFrom(
        this.api.get<BlacklistStatus>(operationsPaths.customerBlacklistStatus(scope, accountId)),
      )
    ).value;
  }

  async revealBlacklistHistory(
    scope: LocationScope,
    accountId: string,
    purpose: string,
  ): Promise<readonly RevealedBlacklistEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RevealedBlacklistEntry[]>(
        operationsPaths.customerBlacklistEntries(scope, accountId),
        {
          params: { purpose },
        },
      ),
    );
    return result.value ?? [];
  }

  async addBlacklistEntry(
    scope: LocationScope,
    accountId: string,
    request: AddBlacklistEntryRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<AddBlacklistEntryRequest, { id: string }>(
        operationsPaths.customerBlacklistEntries(scope, accountId),
        command(request),
      ),
    );
  }

  async liftBlacklistEntry(
    scope: LocationScope,
    accountId: string,
    reason: string | null,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string | null }, void>(
        operationsPaths.customerBlacklistLift(scope, accountId),
        command({ reason }),
      ),
    );
  }

  // ----------------------------------------------------------------- the merge

  async merge(
    scope: LocationScope,
    accountId: string,
    targetAccountId: string,
    expectedVersion: number,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ targetAccountId: string }, void>(
        operationsPaths.customerMerge(scope, accountId),
        command({ targetAccountId }),
        { expectedVersion },
      ),
    );
  }

  // ----------------------------------------------------------------- the orders

  ordersPage(
    scope: LocationScope,
    accountId: string,
    state: CursorState,
  ): Promise<Page<CustomerOrderSummary>> {
    return firstValueFrom(
      this.api.page<CustomerOrderSummary>(operationsPaths.customerOrders(scope, accountId), state),
    );
  }

  // --------------------------------------------------------------- the cashback

  async loyaltyBalances(
    scope: LocationScope,
    accountId: string,
  ): Promise<readonly LoyaltyBalance[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LoyaltyBalance[]>(
        operationsPaths.customerLoyaltyBalances(scope, accountId),
      ),
    );
    return result.value ?? [];
  }

  async loyaltyEntries(
    scope: LocationScope,
    accountId: string,
    loyaltyAccountId: string,
  ): Promise<readonly LoyaltyEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LoyaltyEntry[]>(
        operationsPaths.customerLoyaltyEntries(scope, accountId, loyaltyAccountId),
      ),
    );
    return result.value ?? [];
  }
}
