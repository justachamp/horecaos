import { Injectable, computed, inject, signal } from '@angular/core';

import { TelegramLinkApi, type TelegramLinkCode } from '../core/api/telegram-link-api';
import { isNotFound } from '../core/api/problem-details';
import { newIdempotencyKey } from '../core/api/idempotency';

/**
 * The customer's own Telegram link, as the profile screen needs it.
 *
 * Wraps {@link TelegramLinkApi} the same way `FavouritesService` wraps its own
 * platform surface: state kept in signals, and a not-found on the status read
 * treated as the honest "no account at this brand yet" reading -- the same
 * choice `FavouritesService.load` and `CustomerProfileService.load` make about
 * their own not-found, rather than surfacing it as a failure to a customer who
 * has simply never ordered here.
 *
 * No polling lives here. `ProfileTelegramComponent` owns the polling
 * `Subscription`, using `OrdersService.poll`'s own idiom -- skip while
 * `document.hidden`, swallow a failed tick -- the same way
 * `ActiveOrderComponent` and `CartOrderStatusComponent` already reuse it for
 * their own screens rather than each screen inventing its own interval.
 */
@Injectable({ providedIn: 'root' })
export class TelegramLinkService {
  private readonly api = inject(TelegramLinkApi);

  private readonly current = signal<boolean | null>(null);
  private readonly pending = signal<TelegramLinkCode | null>(null);

  /** null before the first read; true/false once a status read has completed. */
  readonly linked = computed(() => this.current());

  /** The most recently minted code, until the account links or the screen discards it. */
  readonly pendingCode = computed(() => this.pending());

  /**
   * Reads whether this account has a linked chat.
   *
   * @returns false for a guest -- signed in, no account at this brand yet --
   *          which is a state to render, not a failure to report.
   */
  async refresh(): Promise<boolean> {
    try {
      const status = await this.api.status();
      this.current.set(status.linked);
      if (status.linked) {
        // Linked is the terminal state this whole screen is waiting for; a
        // code that got here is spent or moot either way.
        this.pending.set(null);
      }
      return status.linked;
    } catch (failure) {
      if (isNotFound(failure)) {
        this.current.set(false);
        return false;
      }
      throw failure;
    }
  }

  /** Mints a fresh deep link and holds it until the account links or it is discarded. */
  async mintCode(): Promise<TelegramLinkCode> {
    const code = await this.api.issueCode(newIdempotencyKey());
    this.pending.set(code);
    return code;
  }

  /** Retires the link. */
  async unlink(): Promise<void> {
    await this.api.unlink(newIdempotencyKey());
    this.current.set(false);
    this.pending.set(null);
  }

  /**
   * Drops a minted code without asking the platform to unlink anything.
   *
   * Called when the customer leaves the screen before finishing: the code is
   * single-use and short-lived on the platform regardless, and starting the
   * next visit from a clean "Connect Telegram" state is more honest than
   * silently resuming a wait on a link that may already be stale.
   */
  discardPendingCode(): void {
    this.pending.set(null);
  }

  /** Forgotten when the session ends: the next customer on this handset must not inherit this one's link state. */
  forget(): void {
    this.current.set(null);
    this.pending.set(null);
  }
}
