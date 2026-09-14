import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Versioned } from '../../core/api/aggregate-version';
import { command } from '../../core/api/idempotency';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
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

/**
 * `CustomersApi.exportFiltered`'s own result: `CustomerExportRow[]` (the
 * body, unchanged in shape) plus `truncated` (the `X-Export-Truncated`
 * response header — never folded into the body; see `exportFiltered`'s own
 * doc for why).
 */
export interface CustomerExportResult {
  readonly rows: readonly CustomerExportRow[];
  /** True when the filter matched more than the server's row cap and the export was cut. */
  readonly truncated: boolean;
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

/**
 * `CustomerEligibility.Answer` — whether this customer may be reached for a
 * purpose and channel right now. `refusalReason` is `CONSENT_WITHHELD` or
 * `NO_VERIFIED_ENDPOINT` when `eligible` is false, and null otherwise.
 */
export interface CustomerEligibility {
  readonly eligible: boolean;
  readonly refusalReason: 'CONSENT_WITHHELD' | 'NO_VERIFIED_ENDPOINT' | null;
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

/**
 * `StorefrontOrderingController.ReorderPlanResponse` (ADR 0074), answered
 * for a staff caller through `CustomerOrderHistoryController.reorderPlan`
 * (row 1.3f) — the identical shape the storefront's own `@CustomerOwned`
 * read returns.
 */
export interface ReorderPlan {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly locationId: string;
  readonly channelCode: string;
  readonly verdict: 'READY' | 'PARTIAL' | 'UNAVAILABLE';
  readonly currency: string;
  readonly lines: readonly ReorderPlanLine[];
}

export interface ReorderPlanLine {
  readonly lineNumber: number;
  readonly productName: string;
  readonly variantName: string | null;
  readonly productId: string | null;
  readonly variantId: string;
  readonly quantity: number;
  readonly modifierOptionIds: readonly string[];
  readonly status: 'AVAILABLE' | 'SOLD_OUT' | 'WITHDRAWN' | 'UNPRICED' | 'MODIFIERS_WITHDRAWN';
  readonly unitAmountMinor: number | null;
  readonly originalUnitAmountMinor: number;
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

// ------------------------------------------------------------ row X.13/5.1b: import

/** `CustomerImportController.CustomerImportSubmitResponse`/`CustomerImportStatusResponse`'s shared status vocabulary. */
export type CustomerImportJobStatus =
  'QUEUED' | 'RUNNING' | 'DRY_RUN_COMPLETE' | 'COMPLETE' | 'FAILED';

export interface CustomerImportSubmitRequest {
  readonly brandId: string;
  readonly fileName: string;
  readonly content: string;
}

/** `CustomerImportController.CustomerImportStatusResponse`. */
export interface CustomerImportStatus {
  readonly runId: string;
  readonly status: CustomerImportJobStatus;
  readonly dryRun: boolean;
  readonly sourceFileName: string;
  readonly rowsTotal: number;
  readonly rowsProcessed: number;
  readonly rowsCreatedCustomer: number;
  readonly rowsMatchedCustomer: number;
  readonly rowsRejected: number;
  readonly failureReason: string | null;
}

/** `CustomerImportController.CustomerImportRowResponse` — no phone number and no display name (ADR 0029). */
export interface CustomerImportRow {
  readonly rowNumber: number;
  readonly outcome: 'CREATED_CUSTOMER' | 'MATCHED_CUSTOMER' | 'REJECTED';
  readonly customerAccountId: string | null;
  readonly rejectReason: string | null;
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
  ): Promise<CustomerExportResult> {
    const params: Record<string, string> = { purpose };
    if (filters.status) {
      params['status'] = filters.status;
    }
    if (filters.query) {
      params['query'] = filters.query;
    }
    // The body stays a plain array (CustomerController.export's own doc
    // explains why: OpenApiContractTests refuses an already-released
    // endpoint's response type narrowing or changing) — truncation travels
    // on the X-Export-Truncated header instead.
    const result = await firstValueFrom(
      this.api.getWithFlag<readonly CustomerExportRow[]>(
        operationsPaths.customersExport(scope),
        'X-Export-Truncated',
        { params },
      ),
    );
    return { rows: result.value ?? [], truncated: result.flag };
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

  /** Corrects a mistyped value in place — no `type`, which never changes (`CustomerController.UpdateContactRequest`). */
  async updateContact(
    scope: LocationScope,
    accountId: string,
    contactPointId: string,
    value: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.put<{ value: string }, void>(
        operationsPaths.customerContactPoint(scope, accountId, contactPointId),
        command({ value }),
      ),
    );
  }

  /** Removes a contact point added by mistake. Tombstoned server-side, never physically deleted. */
  async removeContact(
    scope: LocationScope,
    accountId: string,
    contactPointId: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        operationsPaths.customerContactPoint(scope, accountId, contactPointId),
        command(null),
      ),
    );
  }

  /** Makes this the account's primary contact of its kind, demoting whichever one held that place. */
  async setPrimaryContact(
    scope: LocationScope,
    accountId: string,
    contactPointId: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        operationsPaths.customerContactPointSetPrimary(scope, accountId, contactPointId),
        command(null),
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

  /** @returns the new address's id — row 1.3b's inline add auto-selects it rather than leaving the operator to find it in a refreshed list. */
  async addAddress(
    scope: LocationScope,
    accountId: string,
    request: SaveCustomerAddressRequest,
  ): Promise<{ id: string }> {
    return firstValueFrom(
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

  async eligibility(
    scope: LocationScope,
    accountId: string,
    brandId: string,
    purpose: string,
    channel: string,
  ): Promise<CustomerEligibility> {
    return (
      await firstValueFrom(
        this.api.get<CustomerEligibility>(operationsPaths.customerEligibility(scope, accountId), {
          params: { brandId, purpose, channel },
        }),
      )
    ).value;
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

  /**
   * Row 1.3f's «Повторить» — whether one of this customer's own orders can
   * be ordered again, and with what. `null` when the order does not exist or
   * is not this account's own (the server answers `RESOURCE_NOT_FOUND`
   * either way, never distinguishing the two).
   */
  async reorderPlan(
    scope: LocationScope,
    accountId: string,
    orderId: string,
  ): Promise<ReorderPlan | null> {
    try {
      const result = await firstValueFrom(
        this.api.get<ReorderPlan>(operationsPaths.customerOrderReorder(scope, accountId, orderId)),
      );
      return result.value;
    } catch (error) {
      if (error instanceof ApiError && error.code === ApiErrorCode.RESOURCE_NOT_FOUND) {
        return null;
      }
      throw error;
    }
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

  // ---------------------------------------------------------- row X.13/5.1b: import

  /** Queues a run; `dryRun` defaults true server-side (`CustomerImportController.submit`) but is always passed here explicitly. */
  async submitImport(
    scope: LocationScope,
    request: CustomerImportSubmitRequest,
    dryRun: boolean,
  ): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<CustomerImportSubmitRequest, { runId: string }>(
        operationsPaths.customerImports(scope),
        command(request),
        { params: { dryRun } },
      ),
    );
    return response.runId;
  }

  async importStatus(scope: LocationScope, runId: string): Promise<CustomerImportStatus> {
    return (
      await firstValueFrom(
        this.api.get<CustomerImportStatus>(operationsPaths.customerImport(scope, runId)),
      )
    ).value;
  }

  async importRows(scope: LocationScope, runId: string): Promise<readonly CustomerImportRow[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CustomerImportRow[]>(operationsPaths.customerImportRows(scope, runId)),
    );
    return result.value ?? [];
  }
}
