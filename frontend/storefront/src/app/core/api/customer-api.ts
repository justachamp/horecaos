import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../config/app-config';
import { ApiClient } from './api-client';

/**
 * The `me` surface: what a customer may read and change about themselves.
 *
 * A faithful transcription of `StorefrontCustomerController` on the day it
 * landed, built directly on {@link ApiClient}. An earlier hand-written
 * `storefront-api.ts` existed alongside this file and said of itself that
 * ADR 0035 would replace it wholesale with generated output; it never grew a
 * second consumer and was deleted rather than extended -- generated output is
 * still the intended replacement for hand-written clients like this one.
 *
 * **The account is never in a path here.** Every method below addresses
 * `/me`, and the server resolves the account from the caller's own verified
 * token. There is no account id to pass and no place to put one, which is what
 * makes an ownership-authorised endpoint different from a capability-scoped one:
 * the caller cannot name whose record they mean.
 *
 * Three things about this surface are worth knowing before calling it.
 *
 * - **An address is ADR 0029 personal data.** The server decrypts it because the
 *   person it belongs to asked, and records `CUSTOMER_SELF_SERVICE` as the
 *   purpose of every decrypt. Nothing in this client may put a field of one into
 *   a log, an analytics event, an error message or a URL.
 * - **The profile PATCH replaces all three fields it owns.** The server writes
 *   `display_name`, `preferred_locale` and `preferred_timezone` unconditionally,
 *   so a field left out of the body is a field set to null. {@link
 *   CustomerApi.updateProfile} therefore takes all three and callers echo back
 *   what they already hold; there is no partial form of it.
 * - **A guest is answered not-found, not forbidden.** A caller with a token but
 *   no account at this brand gets 404 from every method here. That is a state to
 *   render — "you have not ordered here yet" — and not a failure to report;
 *   `isNotFound` in `problem-details` is what recognises it.
 */
@Injectable({ providedIn: 'root' })
export class CustomerApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get mePath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/me`;
  }

  /**
   * The caller's own account.
   *
   * Carries `accountId`, which the storefront had no other way to learn, and
   * `contactPoints` by kind and verification state — never by value. A phone
   * number is personal data whose decrypt is recorded against a purpose, and
   * putting one here would decrypt it on every screen paint.
   */
  profile(): Promise<CustomerProfile> {
    return this.api.get<CustomerProfile>(this.mePath);
  }

  /**
   * Changes the three fields a customer owns.
   *
   * @param input all three are sent every time, including the ones that did not
   *        change. The server's UPDATE names all three columns, so an omitted
   *        field is a cleared field: sending only `displayName` would silently
   *        wipe the language the notification path reads.
   * @param input.expectedVersion the version last read, sent as `If-Match`. A
   *        second tab that got there first loses loudly with `STALE_VERSION`
   *        rather than quietly overwriting.
   * @param input.idempotencyKey generated when the customer pressed save and
   *        reused on every retry of that one intent.
   */
  updateProfile(input: {
    expectedVersion: number;
    displayName: string | null;
    preferredLocale: string | null;
    preferredTimezone: string | null;
    idempotencyKey?: string;
  }): Promise<CustomerProfile> {
    return this.api.mutate<CustomerProfile>('PATCH', this.mePath, {
      body: {
        displayName: input.displayName,
        preferredLocale: input.preferredLocale,
        preferredTimezone: input.preferredTimezone,
      },
      expectedVersion: input.expectedVersion,
      idempotencyKey: input.idempotencyKey,
    });
  }

  /** The caller's own saved addresses. Archived ones are not listed. */
  addresses(): Promise<readonly CustomerAddress[]> {
    return this.api.get<readonly CustomerAddress[]>(`${this.mePath}/addresses`);
  }

  /**
   * One of the caller's own addresses.
   *
   * Somebody else's, an archived one and one that never existed are a single
   * answer — not-found — because telling them apart is how an address id becomes
   * something to probe with.
   */
  address(addressId: string): Promise<CustomerAddress> {
    return this.api.get<CustomerAddress>(`${this.mePath}/addresses/${addressId}`);
  }

  addAddress(input: { address: AddressDraft; idempotencyKey: string }): Promise<CustomerAddress> {
    return this.api.mutate<CustomerAddress>('POST', `${this.mePath}/addresses`, {
      body: input.address,
      idempotencyKey: input.idempotencyKey,
    });
  }

  /**
   * Replaces one address in full.
   *
   * The whole document and never a field of it: the lines live inside one
   * encrypted blob, and the coordinate and the source that explains it are a
   * single fact the server stores under one constraint.
   */
  replaceAddress(input: {
    addressId: string;
    expectedVersion: number;
    address: AddressDraft;
    idempotencyKey: string;
  }): Promise<CustomerAddress> {
    return this.api.mutate<CustomerAddress>('PUT', `${this.mePath}/addresses/${input.addressId}`, {
      body: input.address,
      expectedVersion: input.expectedVersion,
      idempotencyKey: input.idempotencyKey,
    });
  }

  /**
   * Archives one address. 204, and no body.
   *
   * Not an erasure: a cart and an order each hold their own copy of where they
   * were going, so an order in flight is unaffected, and the row remains what a
   * dispute about a delivery is answered from.
   */
  async removeAddress(input: {
    addressId: string;
    expectedVersion: number;
    idempotencyKey: string;
  }): Promise<void> {
    await this.api.mutate<void>('DELETE', `${this.mePath}/addresses/${input.addressId}`, {
      expectedVersion: input.expectedVersion,
      idempotencyKey: input.idempotencyKey,
    });
  }

  // ---------------------------------------------------------------- a copy of my data

  private get exportPath(): string {
    return `${this.mePath}/export-request`;
  }

  /**
   * My current request for a copy of my data: the waiting one if there is one, else the most recent of any status.
   * `null` when I have never asked (the platform answers 204).
   */
  async exportRequest(): Promise<ExportRequestStatus | null> {
    return (await this.api.get<ExportRequestStatus | null>(this.exportPath)) ?? null;
  }

  /**
   * Asks for a copy of my data. It records the request and the day the business owes me an answer by; it gathers nothing
   * by itself (ADR 0029), a person on the business's side does that. Asking again while one waits returns the same request.
   */
  requestExport(idempotencyKey?: string): Promise<ExportRequestStatus> {
    return this.api.mutate<ExportRequestStatus>('POST', this.exportPath, { idempotencyKey });
  }

  /** Withdraws my waiting request. Refused (422) once the file has been produced. */
  cancelExport(idempotencyKey?: string): Promise<ExportRequestStatus> {
    return this.api.mutate<ExportRequestStatus>('POST', `${this.exportPath}/cancel`, { idempotencyKey });
  }

  /**
   * A link to my data once the business has produced it. It works for a few minutes and is recorded against me as the
   * reader; 404 until the file exists and after it has expired. Nothing here may put the link in a log or an analytics event.
   */
  exportBundleLink(): Promise<ExportBundleLink> {
    return this.api.get<ExportBundleLink>(`${this.exportPath}/bundle`);
  }
}

/**
 * Why an address does or does not carry a point, narrowed to what a customer may
 * say about their own.
 *
 * `GEOCODER` and `OPERATOR_PIN` assert who produced a point, and the server
 * refuses them from this surface — a provenance audit reads that column and a
 * backfill decides whether to re-query on it. `LEGACY_UNSOURCED` is refused for
 * the same reason. Narrowing the type here means the refusal is a compile error
 * rather than a 400 a customer discovers.
 */
export type CustomerCoordinateSource = 'CUSTOMER_PIN' | 'NOT_GEOCODED' | 'LANDMARK_ONLY';

/** Every source the platform stores, for reading back an address staff created. */
export type CoordinateSource =
  CustomerCoordinateSource | 'GEOCODER' | 'OPERATOR_PIN' | 'LEGACY_UNSOURCED' | (string & {});

/**
 * The parts of an address, each in its own field.
 *
 * подъезд, этаж and ориентир are not decoration and are not stuffed into a
 * street line: they are what actually locates a door in this market, and a
 * courier cannot use them buried in `line1`.
 */
export interface AddressFields {
  readonly line1?: string | null;
  readonly line2?: string | null;
  readonly city?: string | null;
  readonly district?: string | null;
  readonly postalCode?: string | null;
  readonly entrance?: string | null;
  readonly floor?: string | null;
  readonly apartment?: string | null;
  readonly landmark?: string | null;
}

/** The maximum lengths the server validates, so a form can refuse before it asks. */
export const ADDRESS_FIELD_LIMITS = {
  label: 64,
  line1: 200,
  line2: 200,
  city: 120,
  district: 120,
  postalCode: 32,
  entrance: 32,
  floor: 32,
  apartment: 32,
  landmark: 300,
  deliveryInstructions: 500,
} as const;

export interface CustomerAddress {
  readonly addressId: string;
  readonly label: string | null;
  readonly fields: AddressFields;
  readonly deliveryInstructions: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly coordinateSource: CoordinateSource;
  /** Sent back as `If-Match` on an edit or a removal. */
  readonly version: number;
}

/** What POST and PUT both take: the whole address, never a patch of one. */
export interface AddressDraft {
  readonly label: string | null;
  readonly fields: AddressFields;
  readonly deliveryInstructions: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly coordinateSource: CustomerCoordinateSource;
}

export interface ContactPointSummary {
  readonly id: string;
  readonly type: 'PHONE' | 'EMAIL' | (string & {});
  readonly verificationStatus: string;
  readonly primary: boolean;
}

/**
 * @param identityMode how the tenant partitions customer identity *now*.
 * @param profileScope where a change to this profile actually lands, read from
 *        the account's own partition. `TENANT` means one profile across every
 *        brand; `BRAND` means this brand's only. The two can disagree — an
 *        account is partitioned when it is created and a later governed mode
 *        change does not re-partition it — and this one is the one that is true.
 */
export interface CustomerProfile {
  readonly accountId: string;
  readonly brandId: string;
  readonly status: string;
  readonly identityMode: string;
  readonly profileScope: 'TENANT' | 'BRAND' | (string & {});
  readonly identityPolicyVersion: number | null;
  readonly displayName: string | null;
  readonly preferredLocale: string | null;
  readonly preferredTimezone: string | null;
  readonly contactPoints: readonly ContactPointSummary[];
  readonly version: number;
  readonly createdAt: string;
}

/**
 * `StorefrontCustomerExportController.ExportRequestStatus`: where my request stands. Nothing about who handled it and
 * nothing about what is in the file.
 *
 * @param responseDueAt the day the business owes me my data, fixed when I asked
 * @param bundleAvailable whether a link can be had right now: produced, not expired
 */
export interface ExportRequestStatus {
  readonly id: string;
  /** PENDING, COMPLETED or CANCELLED. */
  readonly status: 'PENDING' | 'COMPLETED' | 'CANCELLED' | (string & {});
  readonly requestedAt: string;
  readonly responseDueAt: string;
  readonly producedAt: string | null;
  readonly bundleExpiresAt: string | null;
  readonly bundleAvailable: boolean;
  readonly cancelledAt: string | null;
}

/** `StorefrontCustomerExportController.BundleLink`. */
export interface ExportBundleLink {
  readonly url: string;
  readonly expiresAt: string;
}
