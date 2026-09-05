import { Injectable, computed, inject, signal } from '@angular/core';

import { AddressBookService, addressLine } from './address-book.service';
import { CustomerProfileService } from './customer-profile.service';
import type { CustomerAddress } from '../core/api/customer-api';

const ADDRESS_KEY = 'horecaos_delivery_address';

/**
 * Where this customer's next delivery is going, and who receives it.
 *
 * The platform models the destination as a sub-resource of the cart
 * (`PUT /carts/{id}/destination`) and requires three things: one of the
 * caller's own saved addresses by id, a recipient name, and a recipient phone.
 * Checkout refuses a delivery cart without one — `DELIVERY_DESTINATION_REQUIRED`
 * — so this is what stands between a basket and an order.
 *
 * <h2>The phone, and why it is only in memory</h2>
 *
 * The recipient's phone is required and the platform will not give it back: a
 * number is ADR 0029 personal data, `GET /me` reports contact points by kind and
 * verification state and never by value, and the token is opaque so there is no
 * claim to read it from either.
 *
 * What the storefront does know is the number the customer typed at sign-in. It
 * is kept **in memory for this session** and deliberately not in local storage,
 * for the same reason `Session` stores no personal data: a shared handset should
 * not be left holding somebody's number. A reload therefore loses it and the
 * customer types it once more, which is the correct trade rather than an
 * oversight.
 *
 * It is a *default* in any case, not a fact: the recipient of a delivery is
 * often not the account holder, and the confirmation screen lets it be changed.
 */
@Injectable({ providedIn: 'root' })
export class DeliverySelectionService {
  private readonly profile = inject(CustomerProfileService);
  private readonly addressBook = inject(AddressBookService);

  /** The saved address the customer chose. Survives a reload; an id is not PII. */
  readonly addressId = signal<string | null>(readAddressId());

  /**
   * The chosen address itself, once it has been read back.
   *
   * Only the *id* survives a reload (see above), so on a fresh page the
   * confirmation screen knows an address was chosen but not what it says. This
   * holds the resolved row so the screen can name the destination instead of
   * reporting "not selected" over a choice the customer already made.
   */
  private readonly chosen = signal<CustomerAddress | null>(null);

  /** The chosen address as one line, or '' when none is chosen or resolved. */
  readonly addressLabel = computed(() => {
    const address = this.chosen();
    if (!address || address.addressId !== this.addressId()) {
      return '';
    }
    return address.label?.trim() || addressLine(address);
  });

  /** Session-scoped, never persisted. See the class comment. */
  private readonly signedInPhone = signal<string | null>(null);

  private readonly overriddenName = signal<string | null>(null);
  private readonly overriddenPhone = signal<string | null>(null);

  /** Defaults to the profile's display name, which the customer may override. */
  readonly recipientName = computed(
    () => this.overriddenName() ?? this.profile.profile()?.displayName ?? '',
  );

  readonly recipientPhone = computed(() => this.overriddenPhone() ?? this.signedInPhone() ?? '');

  /** True when the destination can be set without asking anything further. */
  readonly isComplete = computed(
    () => !!this.addressId() && !!this.recipientName().trim() && !!this.recipientPhone().trim(),
  );

  /**
   * Reads the chosen address back when only its id survived a reload.
   *
   * Cheap to call repeatedly: it returns as soon as the resolved row already
   * matches the chosen id, so a screen may await it on every entry.
   */
  async ensureAddressResolved(): Promise<void> {
    const addressId = this.addressId();
    if (!addressId) {
      this.chosen.set(null);
      return;
    }
    if (this.chosen()?.addressId === addressId) {
      return;
    }
    try {
      const addresses = await this.addressBook.list();
      const match = addresses.find((address) => address.addressId === addressId) ?? null;
      // An address removed since it was chosen leaves the choice dangling, and
      // a dangling id would be sent to a destination endpoint that refuses it.
      // Forgetting it here sends the customer back to pick again instead.
      if (!match) {
        this.clear();
        return;
      }
      this.chosen.set(match);
    } catch {
      // Offline or refused: the id still stands, the label simply stays empty.
    }
  }

  choose(addressId: string, address?: CustomerAddress): void {
    this.addressId.set(addressId);
    this.chosen.set(address ?? null);
    try {
      localStorage.setItem(ADDRESS_KEY, addressId);
    } catch {
      // The choice lasts for this page only.
    }
  }

  /** Called once at sign-in with the number the customer typed. */
  rememberSignInPhone(e164: string): void {
    this.signedInPhone.set(e164);
  }

  setRecipient(name: string, phone: string): void {
    this.overriddenName.set(name.trim() || null);
    this.overriddenPhone.set(phone.trim() || null);
  }

  clear(): void {
    this.addressId.set(null);
    this.chosen.set(null);
    this.overriddenName.set(null);
    this.overriddenPhone.set(null);
    try {
      localStorage.removeItem(ADDRESS_KEY);
    } catch {
      // Nothing stored.
    }
  }
}

function readAddressId(): string | null {
  try {
    return localStorage.getItem(ADDRESS_KEY);
  } catch {
    return null;
  }
}
