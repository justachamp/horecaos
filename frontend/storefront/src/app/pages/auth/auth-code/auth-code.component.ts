import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import type { AuthCodeState } from '../../../auth/auth.state';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpChallengeOverError,
  OtpCodeRejectedError,
  OtpRateLimitedError,
} from '../../../core/session/customer-otp';
import { formatUzPhone } from '../../../core/session/phone';
import { TranslateService } from '../../../services/translate.service';
import { DeliverySelectionService } from '../../../services/delivery-selection.service';
import { TelegramWebappService } from '../../../services/telegram-webapp.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';

@Component({
  selector: 'app-auth-code',
  standalone: true,
  imports: [CommonModule, TranslatePipe, BackDirective],
  templateUrl: './auth-code.component.html',
  styleUrl: './auth-code.component.scss',
})
export class AuthCodeComponent implements OnInit {
  private readonly telegramWebapp = inject(TelegramWebappService);
  /** Canonical E.164, e.g. +998901234567. */
  phone = '';

  /** Masked, for reading back: "+998 90 *** ** 67". */
  phoneDisplay = '';

  /**
   * The challenge this screen is answering.
   *
   * Replaces the legacy `otp_job_id`. A challenge is single-use and short-lived,
   * and a resend supersedes it -- so this field changes when the customer asks
   * for another code, and answering the old one afterwards is refused.
   */
  challengeId: string | null = null;

  /** 6-digit code as string (one char per box) */
  code = signal('');

  /** Countdown seconds remaining (0 = show resend) */
  countdown = signal(44);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  showResend = computed(() => this.countdown() <= 0);

  private countdownInterval: ReturnType<typeof setInterval> | null = null;

  private readonly otp = inject(CustomerOtp);
  private readonly translate = inject(TranslateService);
  private readonly delivery = inject(DeliverySelectionService);

  constructor(private router: Router) {}

  ngOnInit(): void {
    const authState = history.state as Partial<AuthCodeState> | undefined;
    if (authState?.phone) {
      this.phone = authState.phone;
      this.phoneDisplay = maskPhone(authState.phone);
    }
    if (authState?.challengeId) {
      this.challengeId = authState.challengeId;
    }
    // Arriving here without a challenge means this screen was opened directly or
    // reloaded, and there is nothing to answer. Sending them back beats six
    // input boxes that can only ever fail.
    if (!this.challengeId || !this.phone) {
      this.router.navigate(['/auth/login']).catch(() => {});
      return;
    }
    this.startCountdown();
    if (this.isTelegramContext()) {
      setTimeout(() => this.requestTelegramClipboard(), 500);
    }
  }

  /** Request clipboard via Telegram API (SMS code from keyboard suggestion) */
  private requestTelegramClipboard(): void {
    const reqId = 'auth-otp-' + Date.now();
    this.telegramWebapp.readTextFromClipboard(reqId, (text) => {
      if (typeof text === 'string') {
        const digits = text.replace(/\D/g, '').slice(0, 6);
        if (digits.length >= 6) {
          this.error.set(null);
          this.code.set(digits);
          const el = document.querySelector('[data-code-input="5"]') as HTMLInputElement;
          el?.focus();
        }
      }
    });
  }

  /** Detect Telegram: WebApp API, TelegramWebviewProxy, URL params, or User-Agent */
  private isTelegramContext(): boolean {
    if (this.telegramWebapp.isTelegram) return true;
    if (typeof window === 'undefined') return false;
    const params = new URLSearchParams(window.location.search);
    if (params.has('tgWebAppData') || params.has('tgWebAppStartParam')) return true;
    const ua = navigator.userAgent ?? '';
    return /Telegram-Android|Telegram-iOS|TelegramDesktop/i.test(ua);
  }

  private startCountdown(): void {
    this.countdownInterval = setInterval(() => {
      const next = this.countdown() - 1;
      this.countdown.set(next);
      if (next <= 0 && this.countdownInterval) {
        clearInterval(this.countdownInterval);
        this.countdownInterval = null;
      }
    }, 1000);
  }

  formatTime(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  digitAt(i: number): string {
    return this.code().charAt(i) || '';
  }

  onDigitInput(index: number, value: string): void {
    this.error.set(null);
    const digitsOnly = value.replace(/\D/g, '');
    if (digitsOnly.length >= 6) {
      this.code.set(digitsOnly.slice(0, 6));
      const el = document.querySelector('[data-code-input="5"]') as HTMLInputElement;
      el?.focus();
      return;
    }
    const char = digitsOnly.slice(-1);
    const current = this.code();
    const arr = current.split('');
    arr[index] = char;
    const next = arr.slice(0, 6).join('');
    this.code.set(next);
    if (char && index < 5) {
      setTimeout(() => {
        const el = document.querySelector(`[data-code-input="${index + 1}"]`) as HTMLInputElement;
        el?.focus();
      }, 0);
    }
  }

  onDigitKeydown(index: number, e: KeyboardEvent): void {
    if (e.key === 'Backspace' && !this.digitAt(index) && index > 0) {
      const el = document.querySelector(`[data-code-input="${index - 1}"]`) as HTMLInputElement;
      el?.focus();
    }
  }

  onPaste(e: ClipboardEvent): void {
    e.preventDefault();
    const text = e.clipboardData?.getData('text/plain') ?? '';
    const digits = text.replace(/\D/g, '').slice(0, 6);
    if (digits.length >= 6) {
      this.error.set(null);
      this.code.set(digits);
      const el = document.querySelector('[data-code-input="5"]') as HTMLInputElement;
      el?.focus();
    }
  }

  /**
   * Asks for another code.
   *
   * A real request rather than a timer reset. The legacy screen only restarted
   * its own countdown and kept the same job id, so "send again" sent nothing and
   * a customer whose first message never arrived waited forever.
   *
   * A resend after the window is a new intent and takes a new challenge, which
   * supersedes the old one -- so the id is replaced and any code from the first
   * message stops working. That is the platform's rule, not a choice made here.
   */
  async resend(): Promise<void> {
    if (this.countdown() > 0 || this.loading()) return;
    this.error.set(null);
    this.loading.set(true);
    try {
      const challenge = await this.otp.requestCode(this.phone);
      this.challengeId = challenge.challengeId;
      this.code.set('');
      this.countdown.set(44);
      this.startCountdown();
    } catch (failure) {
      this.error.set(this.messageFor(failure));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Two calls, and the split is the point.
   *
   * The attempt proves the customer controls the number and returns a *grant*,
   * which is not a session: single-use proof, for this brand, at this moment.
   * `POST /sessions` is what redeems it, and it does the whole of sign-in in one
   * transaction -- spends the grant, finds or creates the account, mints the
   * bearer (ADR 0051). The token is installed by `signIn` before it returns, so
   * this screen never holds a session the rest of the app does not know about.
   */
  async submit(): Promise<void> {
    if (this.code().length !== 6 || !this.challengeId || this.loading()) return;
    this.error.set(null);
    this.loading.set(true);
    try {
      const grant = await this.otp.submitCode({
        challengeId: this.challengeId,
        code: this.code(),
      });
      await this.otp.signIn(grant.grant);
      // The number the customer just proved they control, kept in memory for
      // this session so the delivery form can offer it. Never persisted: see
      // DeliverySelectionService.
      this.delivery.rememberSignInPhone(this.phone);
      this.router.navigate(['/locations']).catch(() => {});
    } catch (failure) {
      this.code.set('');
      this.error.set(this.messageFor(failure));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * The five outcomes a customer can tell apart, and can act on differently.
   *
   * A wrong code says how many tries are left, because "wrong code" with two
   * attempts remaining and "wrong code" with none are the same sentence and
   * completely different situations. An ended challenge sends them back for a
   * new one rather than letting them keep typing into something spent.
   */
  private messageFor(failure: unknown): string {
    if (failure instanceof OtpCodeRejectedError) {
      return failure.attemptsRemaining === null
        ? this.translate.get('auth.errors.codeRejected')
        : this.translate.getWithParams('auth.errors.codeRejectedWithTries', {
            count: failure.attemptsRemaining,
          });
    }
    if (failure instanceof OtpChallengeOverError) {
      return this.translate.get('auth.errors.challengeOver');
    }
    if (failure instanceof OtpRateLimitedError) {
      return this.translate.get('auth.errors.rateLimited');
    }
    if (failure instanceof CustomerSignInUnavailableError) {
      return this.translate.get('auth.errors.unavailable');
    }
    return this.translate.get('errors.generic');
  }
}

/** `+998 90 *** ** 67` -- enough to recognise, not enough to read out. */
function maskPhone(e164: string): string {
  const pretty = formatUzPhone(e164);
  const groups = pretty.split(' ');
  return groups.length === 5
    ? `${groups[0]} ${groups[1]} *** ** ${groups[4]}`
    : pretty;
}
