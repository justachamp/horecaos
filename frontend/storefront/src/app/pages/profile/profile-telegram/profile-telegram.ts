import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  WritableSignal,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription, from } from 'rxjs';

import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';
import { TranslateService } from '../../../services/translate.service';
import { NotificationService } from '../../../services/notification.service';
import { OrdersService } from '../../../services/orders.service';
import { TelegramLinkService } from '../../../services/telegram-link.service';
import type { TelegramLinkCode } from '../../../core/api/telegram-link-api';
import { Session } from '../../../core/auth/session';
import { HorecaOSApiError, isUnauthenticated, messageKeyFor } from '../../../core/api/problem-details';

/**
 * How often the status is re-read while a minted code is outstanding and this
 * screen is open and visible. Faster than `ActiveOrderComponent`'s 10s: the
 * customer just switched to Telegram to press "/start" and is actively
 * waiting for this screen to notice, not leaving an order open in the
 * background.
 */
const POLL_INTERVAL_MS = 5_000;

/**
 * "Telegram notifications" -- the wave-7 customer linking surface
 * (`StorefrontTelegramLinkController`), reached from the profile menu the same
 * way `profile-support` and `profile-language` are.
 *
 * Two states, and only two: unlinked (with an optional outstanding code the
 * customer is expected to open in Telegram) and linked. The platform's status
 * response carries nothing beyond `linked: boolean` -- no chat name, no
 * username -- so this screen shows exactly that and nothing invented.
 *
 * The Telegram Mini App auto-link path (`POST /mini-app-link`, one round trip,
 * no code) is deliberately not built here. ADR 0035 names a `MiniAppHost`
 * abstraction as the seam for "is this app running inside a Mini App host",
 * and nothing in this frontend implements it yet -- there is no
 * `telegram-host.ts`, no `MiniAppHost` interface, anywhere in `src/`.
 * `TelegramWebappService.isTelegram` exists and does its own raw detection for
 * an unrelated purpose (viewport chrome, clipboard events), but reaching for it
 * here would be exactly the "half-built host detection" this task was told to
 * avoid rather than the ADR's own seam. The `/start` deep-link path below works
 * identically whether or not the storefront happens to be inside Telegram, so
 * nothing is lost by waiting for `MiniAppHost` to land properly.
 */
@Component({
  selector: 'app-profile-telegram',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe, BackDirective],
  templateUrl: './profile-telegram.html',
  styleUrl: './profile-telegram.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileTelegramComponent implements OnInit, OnDestroy {
  private readonly session = inject(Session);
  private readonly telegramLink = inject(TelegramLinkService);
  private readonly ordersService = inject(OrdersService);
  private readonly translate = inject(TranslateService);
  private readonly notification = inject(NotificationService);

  readonly loading = signal(true);
  readonly needsSignIn = signal(false);
  readonly error = signal<string | null>(null);

  readonly minting = signal(false);
  readonly mintError = signal<string | null>(null);

  readonly confirmingUnlink = signal(false);
  readonly unlinking = signal(false);
  readonly unlinkError = signal<string | null>(null);

  private pollSub?: Subscription;

  /**
   * Read straight from {@link TelegramLinkService} on every call, the same way
   * `CartConfirmationComponent` reads `UiCartService.cartData()` directly
   * rather than re-wrapping it in a component-local `computed()`. Angular's
   * signal-aware change detection follows a signal read through any number of
   * plain function calls, so this stays reactive without a second layer of
   * memoisation that would only risk going stale.
   */
  linked(): boolean | null {
    return this.telegramLink.linked();
  }

  pendingCode(): TelegramLinkCode | null {
    return this.telegramLink.pendingCode();
  }

  ngOnInit(): void {
    if (!this.session.isAuthenticated()) {
      // Mirrors ProfileComponent's own check: a guest is shown the sign-in
      // prompt directly, without a round trip that would only 401 anyway.
      this.needsSignIn.set(true);
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.telegramLink
      .refresh()
      .catch((failure) => this.handleFailure(failure, this.error))
      .finally(() => this.loading.set(false));
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
    if (!this.telegramLink.linked()) {
      this.telegramLink.discardPendingCode();
    }
  }

  async connect(): Promise<void> {
    if (this.minting()) return;
    this.mintError.set(null);
    this.minting.set(true);
    try {
      await this.telegramLink.mintCode();
      this.startPolling();
    } catch (failure) {
      this.handleFailure(failure, this.mintError);
    } finally {
      this.minting.set(false);
    }
  }

  requestUnlink(): void {
    this.unlinkError.set(null);
    this.confirmingUnlink.set(true);
  }

  cancelUnlink(): void {
    this.confirmingUnlink.set(false);
  }

  async confirmUnlink(): Promise<void> {
    if (this.unlinking()) return;
    this.unlinkError.set(null);
    this.unlinking.set(true);
    try {
      await this.telegramLink.unlink();
      this.pollSub?.unsubscribe();
      this.confirmingUnlink.set(false);
      this.notification.show(this.translate.get('profile.telegramUnlinked'));
    } catch (failure) {
      this.handleFailure(failure, this.unlinkError);
    } finally {
      this.unlinking.set(false);
    }
  }

  private startPolling(): void {
    this.pollSub?.unsubscribe();
    this.pollSub = this.ordersService
      .poll(POLL_INTERVAL_MS, () => from(this.telegramLink.refresh()))
      .subscribe((linked) => {
        if (linked) {
          this.pollSub?.unsubscribe();
        }
      });
  }

  /**
   * Renders a failure the app's established way: an expired/dead session gets
   * the sign-in prompt rather than a toast that explains nothing, everything
   * else gets `messageKeyFor`'s translated, code-driven message (network
   * failure, rate limiting, and so on) in the caller's own inline slot.
   */
  private handleFailure(failure: unknown, target: WritableSignal<string | null>): void {
    if (isUnauthenticated(failure)) {
      this.needsSignIn.set(true);
      return;
    }
    target.set(this.messageFor(failure));
  }

  private messageFor(failure: unknown): string {
    if (failure instanceof HorecaOSApiError) {
      return this.translate.get(messageKeyFor(failure));
    }
    return this.translate.get('errors.generic');
  }
}
