import { Injectable, inject } from '@angular/core';

import {
  CustomerApi,
  type AddressDraft,
  type CustomerAddress,
} from '../core/api/customer-api';
import { newIdempotencyKey } from '../core/api/idempotency';

/**
 * The customer's own saved addresses.
 *
 * Replaces the legacy `LocationsService`'s `/customers/addresses/` calls. Four
 * differences matter to the screens above it.
 *
 * **An edit or a removal needs the version it is replacing.** It travels as
 * `If-Match`, so a caller must hold the address it read. The list is cached here
 * for that reason, and `remove` looks the version up rather than making every
 * screen carry it.
 *
 * **A write replaces the whole address.** The lines live inside one encrypted
 * blob and the coordinate and its provenance are a single fact under one
 * constraint, so there is no partial form and a caller assembles the whole
 * document every time.
 *
 * **Removal archives rather than erases.** A cart and an order each hold their
 * own copy of where they were going, so an order in flight is unaffected and the
 * row remains what a dispute about a delivery is answered from.
 *
 * **There is no default address.** The legacy API had `is_default` and a `PUT`
 * that set it; the platform has no such concept, because which address an order
 * goes to is a property of the cart's destination and not of the address book.
 * `setDefault` is therefore gone rather than emulated: remembering a favourite
 * locally would show one thing on this screen and send an order somewhere else.
 */
@Injectable({ providedIn: 'root' })
export class AddressBookService {
  private readonly api = inject(CustomerApi);

  /** The last list read, by id, so a write can present the right version. */
  private known = new Map<string, CustomerAddress>();

  async list(): Promise<readonly CustomerAddress[]> {
    const addresses = await this.api.addresses();
    this.known = new Map(addresses.map((address) => [address.addressId, address]));
    return addresses;
  }

  /**
   * Saves a new address.
   *
   * @param input.coordinateSource `CUSTOMER_PIN` when the customer dropped or
   *        dragged the marker themselves, which is the only honest answer from
   *        this surface. The server refuses `GEOCODER` and `OPERATOR_PIN` here:
   *        those assert who produced a point, a provenance audit reads that
   *        column, and a backfill decides whether to re-query on it.
   */
  async add(input: {
    label: string | null;
    line1: string | null;
    latitude: number | null;
    longitude: number | null;
    deliveryInstructions?: string | null;
  }): Promise<CustomerAddress> {
    const saved = await this.api.addAddress({
      address: draft(input),
      idempotencyKey: newIdempotencyKey(),
    });
    this.known.set(saved.addressId, saved);
    return saved;
  }

  /** Replaces one address in full. The version comes from the last read. */
  async replace(
    addressId: string,
    input: {
      label: string | null;
      line1: string | null;
      latitude: number | null;
      longitude: number | null;
      deliveryInstructions?: string | null;
    },
  ): Promise<CustomerAddress> {
    const saved = await this.api.replaceAddress({
      addressId,
      expectedVersion: this.versionOf(addressId),
      address: draft(input),
      idempotencyKey: newIdempotencyKey(),
    });
    this.known.set(saved.addressId, saved);
    return saved;
  }

  async remove(addressId: string): Promise<void> {
    await this.api.removeAddress({
      addressId,
      expectedVersion: this.versionOf(addressId),
      idempotencyKey: newIdempotencyKey(),
    });
    this.known.delete(addressId);
  }

  /**
   * @throws when the address was not in the last list read. Refusing beats
   *         guessing a version: `If-Match: 1` against an address at version 4
   *         is answered `STALE_VERSION`, which reads to a customer as a random
   *         failure rather than as "reload the list".
   */
  private versionOf(addressId: string): number {
    const known = this.known.get(addressId);
    if (!known) {
      throw new Error(`Address ${addressId} was not read before it was written to.`);
    }
    return known.version;
  }
}

function draft(input: {
  label: string | null;
  line1: string | null;
  latitude: number | null;
  longitude: number | null;
  deliveryInstructions?: string | null;
}): AddressDraft {
  const hasPoint = input.latitude !== null && input.longitude !== null;
  return {
    label: input.label,
    fields: { line1: input.line1 },
    deliveryInstructions: input.deliveryInstructions ?? null,
    latitude: input.latitude,
    longitude: input.longitude,
    // The only two a customer's own screen may assert. A saved address with no
    // marker is `NOT_GEOCODED` rather than a silent (0, 0), which would put every
    // such customer in the Gulf of Guinea.
    coordinateSource: hasPoint ? 'CUSTOMER_PIN' : 'NOT_GEOCODED',
  };
}

/** The single line a list row shows, from the parts the platform stores. */
export function addressLine(address: CustomerAddress): string {
  const { fields } = address;
  return [fields.line1, fields.line2, fields.district, fields.city]
    .filter((part) => part && part.trim())
    .join(', ');
}
