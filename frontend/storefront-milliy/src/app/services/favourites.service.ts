import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';
import { isNotFound } from '../core/api/problem-details';

/**
 * The products this customer marked.
 *
 * Kept on the platform now rather than in this browser. The device-local version
 * this replaces was an honest interim -- there was no favourites table, endpoint
 * or port at all -- and it had the failure that goes with one: favouriting on a
 * phone was invisible everywhere else, and clearing site data lost the list.
 *
 * <h2>Optimistic, and honest when it fails</h2>
 *
 * The heart flips before the request lands, because a heart that waits for a
 * round trip feels broken. If the write is refused the flip is undone, so the
 * screen never keeps a state the server rejected -- which is the failure mode
 * optimistic updates are usually criticised for and is entirely avoidable.
 *
 * <h2>The list is ids</h2>
 *
 * The platform returns product ids and not menu items, and the screen resolves
 * them against the published menu it already holds. That is what makes a dish
 * this branch has stopped serving disappear from the list instead of rendering
 * as a card that cannot be ordered.
 */
@Injectable({ providedIn: 'root' })
export class FavouritesService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  /** Product ids this customer has marked. */
  readonly addedIds = signal<Set<string>>(new Set());

  /** True once a read has completed, whatever it found. */
  readonly loaded = signal(false);

  /**
   * Kept for the screens that still read it. Always empty now: a removal takes
   * effect in `addedIds` immediately, so there is no separate pending-removal
   * state to track.
   */
  readonly removedIds = signal<Set<string>>(new Set());

  private get path(): string {
    return (
      `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
      // Under /me: the account is never in the path, and the server resolves it
      // from the caller's own token. There is no id to pass and nowhere to put
      // one, which is what makes this ownership-authorised.
      `/me/favourites`
    );
  }

  /**
   * Whether a product is marked.
   *
   * @param isFavouriteFromApi the legacy menu carried a per-item flag; the
   *     published menu does not, so this is only ever the caller's default and
   *     is used until the list has been read.
   */
  isFavourite(productId: string, isFavouriteFromApi = false): boolean {
    return this.loaded() ? this.addedIds().has(productId) : isFavouriteFromApi;
  }

  /**
   * Reads the list.
   *
   * A guest -- signed in with no account at this brand yet -- is answered
   * not-found, which is an empty list rather than a failure: they have simply
   * not marked anything, because they could not have.
   */
  async load(): Promise<void> {
    try {
      const response = await this.api.get<{ productIds: string[] }>(this.path);
      this.addedIds.set(new Set(response?.productIds ?? []));
    } catch (failure) {
      if (!isNotFound(failure)) {
        throw failure;
      }
      this.addedIds.set(new Set());
    } finally {
      this.loaded.set(true);
    }
  }

  /** Marks a product, undoing the flip if the platform refuses. */
  async add(productId: string): Promise<void> {
    this.flip(productId, true);
    try {
      await this.api.mutate<void>('PUT', `${this.path}/${productId}`, {
        idempotencyKey: newIdempotencyKey(),
      });
    } catch (failure) {
      this.flip(productId, false);
      throw failure;
    }
  }

  async remove(productId: string): Promise<void> {
    this.flip(productId, false);
    try {
      await this.api.mutate<void>('DELETE', `${this.path}/${productId}`, {
        idempotencyKey: newIdempotencyKey(),
      });
    } catch (failure) {
      this.flip(productId, true);
      throw failure;
    }
  }

  /** The ids to resolve against the menu when the favourites screen opens. */
  ids(): string[] {
    return [...this.addedIds()];
  }

  /** Forgotten when the session ends: the next customer's list is not this one. */
  forget(): void {
    this.addedIds.set(new Set());
    this.loaded.set(false);
  }

  private flip(productId: string, marked: boolean): void {
    this.addedIds.update((current) => {
      const next = new Set(current);
      if (marked) {
        next.add(productId);
      } else {
        next.delete(productId);
      }
      return next;
    });
  }
}
