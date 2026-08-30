import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../config/app-config';
import { ApiClient } from './api-client';
import { isNotFound } from './problem-details';
import type { CartResponse } from './storefront-api';

/**
 * Where a delivery cart is going.
 *
 * `PUT /carts/{cartId}/destination` and its `GET`, which
 * `StorefrontOrderingController` deliberately models as a sub-resource rather
 * than as fields on the cart: reading a basket stays one query, and a
 * `CartResponse` can never grow a member that is somebody's home.
 *
 * Built on {@link ApiClient} rather than added to `storefront-api.ts`, which
 * says of itself that ADR 0035 replaces it wholesale with generated output and
 * that it is not to be extended by hand.
 *
 * **Two of the four things this sends are ADR 0029 personal data.** The
 * recipient's name and telephone number travel in the body — never in a path,
 * never in a query string — and the server envelope-encrypts them on to the
 * cart. Nothing in this file logs a request, echoes one into an error, or keeps
 * one: the only thing it holds afterwards is an address id.
 *
 * The address itself is *not* sent. The request names one of the caller's own
 * saved addresses by id and the platform copies it across server-side, so a
 * customer who later edits or archives that address cannot move an order that is
 * already in flight.
 */
@Injectable({ providedIn: 'root' })
export class CartDestinationApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /**
   * Which saved address this cart is going to.
   *
   * @returns null when the platform answers not-found, which covers both "no
   *          such cart of yours" and "this cart has no destination". They are
   *          one answer on purpose — a cart id must not become probeable through
   *          the answer to a question about it — and a caller that rendered them
   *          apart would rebuild the leak the server closed.
   */
  async read(cartId: string): Promise<CartDestinationResponse | null> {
    try {
      return await this.api.get<CartDestinationResponse>(
        `${this.brandPath}/carts/${cartId}/destination`,
      );
    } catch (failure) {
      if (isNotFound(failure)) {
        return null;
      }
      throw failure;
    }
  }

  /**
   * Says where this cart is going, and gets the cart back.
   *
   * @param input.expectedVersion the cart version this client holds, sent as
   *        `If-Match`. The platform bumps the version and clears the attached
   *        quote in the same statement, because ADR 0037 prices delivery from
   *        the destination and a basket priced to one door is not priced to
   *        another — so the returned cart carries a new version and no quote,
   *        and the caller must price it again before it can be checked out.
   * @param input.deliveryNote optional, and an override rather than an addition:
   *        left empty, the platform uses the standing instruction saved on the
   *        address, so a customer who wrote "ring the top bell" once does not
   *        write it again for every order.
   * @param input.idempotencyKey formed when the customer pressed the button and
   *        reused on every retry of that one intent.
   */
  set(input: {
    cartId: string;
    expectedVersion: number;
    addressId: string;
    recipientName: string;
    recipientPhone: string;
    deliveryNote?: string;
    idempotencyKey: string;
  }): Promise<CartResponse> {
    return this.api.mutate<CartResponse>(
      'PUT',
      `${this.brandPath}/carts/${input.cartId}/destination`,
      {
        body: {
          addressId: input.addressId,
          recipientName: input.recipientName,
          recipientPhone: input.recipientPhone,
          deliveryNote: input.deliveryNote,
        },
        expectedVersion: input.expectedVersion,
        idempotencyKey: input.idempotencyKey,
      },
    );
  }
}

/**
 * `StorefrontOrderingController.DestinationResponse`.
 *
 * An address id and nothing else. The doorstep is read from ADR 0015's own
 * endpoint, where the decrypt is recorded against a purpose.
 */
export interface CartDestinationResponse {
  readonly cartId: string;
  readonly addressId: string;
}
