import { Component, OnDestroy, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Subscription, from } from 'rxjs';
import { CustomerOtp, OtpNumberRejectedError, OtpRateLimitedError,
  OtpUndeliverableError, CustomerSignInUnavailableError } from '../../../core/session/customer-otp';
import {
  TelegramSignIn,
  TelegramSignInExpiredError,
  TelegramSignInRateLimitedError,
  TelegramSignInUnavailableError,
} from '../../../core/session/telegram-signin';
import type { TelegramSignInCode } from '../../../core/api/telegram-signin-api';
import { toE164 } from '../../../core/session/phone';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { LangService } from '../../../services/lang.service';
import { TranslateService } from '../../../services/translate.service';
import { OrdersService } from '../../../services/orders.service';
import { hardReloadTelegramEntryPage } from '../../../utils/telegram-entry-reload';
import packageJson from '../../../../../package.json';

/** {@code ProfileTelegramComponent}'s own interval, matched here for the same reason: a customer just switched to Telegram and is actively waiting. */
const TELEGRAM_POLL_INTERVAL_MS = 5_000;

const LANGUAGES = [
  { id: 'uz' as const, flag: '🇺🇿' },
  { id: 'ru' as const, flag: '🇷🇺' },
  { id: 'en' as const, flag: '🇬🇧' },
];

@Component({
  selector: 'app-auth-login',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe],
  templateUrl: './auth-login.component.html',
  styleUrl: './auth-login.component.scss',
})
export class AuthLoginComponent implements OnInit, OnDestroy {
  phone = '';
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly languages = LANGUAGES;
  readonly keyboardOpen = signal(false);

  /** The outstanding "Continue with Telegram" code, while its poll is running. */
  readonly telegramCode = signal<TelegramSignInCode | null>(null);
  readonly telegramMinting = signal(false);
  readonly telegramError = signal<string | null>(null);

  private readonly router = inject(Router);
  private readonly otp = inject(CustomerOtp);
  private readonly telegramSignIn = inject(TelegramSignIn);
  private readonly ordersService = inject(OrdersService);
  private readonly lang = inject(LangService);
  private readonly translate = inject(TranslateService);

  readonly selectedLangId = this.lang.langId;
  readonly appVersion = signal(packageJson.version);

  private telegramPollSub?: Subscription;

  constructor() {
    hardReloadTelegramEntryPage();
  }

  ngOnInit(): void {
    this.lang.load().then(() => this.translate.loadTranslations());
  }

  ngOnDestroy(): void {
    this.telegramPollSub?.unsubscribe();
  }

  selectLanguage(id: string): void {
    this.translate.setLang(id);
  }

  get phoneValid(): boolean {
    const digits = this.phone.replace(/\D/g, '');
    return digits.length >= 9;
  }

  /** Full phone for navigation (e.g. +998 12 123 45 67 — max 9 national digits) */
  get formattedPhone(): string {
    const d = this.phone.replace(/\D/g, '').slice(0, 9);
    if (!d.length) return '';
    if (d.length <= 2) return `+998 ${d}`;
    if (d.length <= 5) return `+998 ${d.slice(0, 2)} ${d.slice(2)}`;
    if (d.length <= 7) return `+998 ${d.slice(0, 2)} ${d.slice(2, 5)} ${d.slice(5)}`;
    if (d.length === 8) {
      return `+998 ${d.slice(0, 2)} ${d.slice(2, 5)} ${d.slice(5, 7)} ${d.slice(7)}`;
    }
    return `+998 ${d.slice(0, 2)} ${d.slice(2, 5)} ${d.slice(5, 7)} ${d.slice(7, 9)}`;
  }

  /** National part only for input display (no +998), same grouping: 12 123 45 67 */
  get formattedNational(): string {
    const d = this.phone.replace(/\D/g, '').slice(0, 9);
    if (!d.length) return '';
    if (d.length <= 2) return d;
    if (d.length <= 5) return `${d.slice(0, 2)} ${d.slice(2)}`;
    if (d.length <= 7) return `${d.slice(0, 2)} ${d.slice(2, 5)} ${d.slice(5)}`;
    if (d.length === 8) return `${d.slice(0, 2)} ${d.slice(2, 5)} ${d.slice(5, 7)} ${d.slice(7)}`;
    return `${d.slice(0, 2)} ${d.slice(2, 5)} ${d.slice(5, 7)} ${d.slice(7, 9)}`;
  }

  /**
   * The canonical E.164 form, `+998901234567`.
   *
   * The legacy backend took `998…` with no plus. The platform hands the number
   * to an SMS gateway, which takes E.164 and nothing else.
   */
  private get phoneForApi(): string {
    return toE164(this.phone) ?? '';
  }

  onPhoneInput(e: Event): void {
    const input = (e.target as HTMLInputElement).value;
    const digits = input.replace(/\D/g, '').slice(0, 9);
    this.phone = digits;
    this.error.set(null);
  }

  onPhoneFocus(): void {
    this.keyboardOpen.set(true);
  }

  onPhoneBlur(): void {
    this.keyboardOpen.set(false);
  }

  /**
   * Asks the platform to send a code.
   *
   * Nothing is carried to the next screen but the challenge: the platform
   * answers with a `challengeId`, and the code itself exists only in the
   * customer's SMS. The legacy flow passed an `otp_job_id` and a server-rendered
   * `phone_mask`; the mask is now built locally from the number the customer
   * just typed, because it is their own number and there is no reason to ask a
   * server to describe it back.
   */
  async continue(): Promise<void> {
    const phone = this.phoneForApi;
    if (!phone || this.loading()) return;
    this.error.set(null);
    this.loading.set(true);
    try {
      const challenge = await this.otp.requestCode(phone);
      this.router.navigate(['/auth/code'], {
        state: {
          phone,
          challengeId: challenge.challengeId,
          codeLength: challenge.codeLength,
          attemptsAllowed: challenge.attemptsAllowed,
          expiresAt: challenge.expiresAt,
        },
      });
    } catch (failure) {
      this.error.set(this.messageFor(failure));
    } finally {
      this.loading.set(false);
    }
  }

  // ------------------------------------------------------- continue with Telegram

  /**
   * Mints a sign-in code and starts polling it, exactly the shape
   * {@code ProfileTelegramComponent.connect} already gives the wave-7 "Connect
   * Telegram" screen -- the deep link is rendered by the template below and
   * opened by the customer's own tap on it, not from here, so this never has
   * to reason about pop-up blockers or a WebView's own navigation rules.
   */
  async continueWithTelegram(): Promise<void> {
    if (this.telegramMinting()) return;
    this.telegramError.set(null);
    this.telegramMinting.set(true);
    try {
      const code = await this.telegramSignIn.mintCode();
      this.telegramCode.set(code);
      this.startTelegramPolling(code.code);
    } catch (failure) {
      this.telegramError.set(this.telegramMessageFor(failure));
    } finally {
      this.telegramMinting.set(false);
    }
  }

  /** Discards the outstanding code and returns to the plain phone form. */
  cancelTelegramSignIn(): void {
    this.telegramPollSub?.unsubscribe();
    this.telegramCode.set(null);
    this.telegramError.set(null);
  }

  private startTelegramPolling(code: string): void {
    this.telegramPollSub?.unsubscribe();
    this.telegramPollSub = this.ordersService
      .poll(TELEGRAM_POLL_INTERVAL_MS, () => from(this.pollTelegramOnce(code)))
      .subscribe();
  }

  /**
   * One tick, folded into the shape `OrdersService.poll` wants: a settled
   * promise per interval, its own failures swallowed by `poll`'s own
   * `catchError` -- except the two failures that end this screen's wait
   * outright, which are handled here instead so the customer is told rather
   * than left watching a spinner against a code that will never redeem.
   */
  private async pollTelegramOnce(code: string): Promise<void> {
    try {
      const signedIn = await this.telegramSignIn.pollOnce(code);
      if (signedIn) {
        this.telegramPollSub?.unsubscribe();
        this.router.navigate(['/locations']).catch(() => {});
      }
    } catch (failure) {
      if (failure instanceof TelegramSignInExpiredError) {
        this.telegramPollSub?.unsubscribe();
        this.telegramCode.set(null);
        this.telegramError.set(this.telegramMessageFor(failure));
        return;
      }
      // A transient network hiccup: let the next tick try again, the same
      // "swallow a failed tick" idiom `OrdersService.poll` already applies to
      // everything else it drives.
    }
  }

  private telegramMessageFor(failure: unknown): string {
    if (failure instanceof TelegramSignInExpiredError) {
      return this.translate.get('auth.errors.telegramExpired');
    }
    if (failure instanceof TelegramSignInRateLimitedError) {
      return this.translate.get('auth.errors.rateLimited');
    }
    if (failure instanceof TelegramSignInUnavailableError) {
      return this.translate.get('auth.errors.telegramUnavailable');
    }
    return this.translate.get('errors.generic');
  }

  /**
   * Says what actually happened, in the customer's language.
   *
   * The distinctions are the ones a customer can act on. A blacklisted number
   * will never receive a code however many times they press the button, and
   * telling them to try again is a lie -- so it is the one case that names a
   * different remedy.
   */
  private messageFor(failure: unknown): string {
    if (failure instanceof OtpNumberRejectedError) {
      return this.translate.get('auth.errors.numberRejected');
    }
    if (failure instanceof OtpRateLimitedError) {
      return this.translate.get('auth.errors.rateLimited');
    }
    if (failure instanceof OtpUndeliverableError) {
      return this.translate.get(
        failure.permanent ? 'auth.errors.undeliverablePermanent' : 'auth.errors.undeliverable');
    }
    if (failure instanceof CustomerSignInUnavailableError) {
      return this.translate.get('auth.errors.unavailable');
    }
    return this.translate.get('errors.generic');
  }

}
