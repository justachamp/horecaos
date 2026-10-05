import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  type OnDestroy,
  type OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import type { AuthCodeState } from '../../../auth/auth.state';
import { ReturnDestination } from '../../../core/auth/return-destination';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpChallengeOverError,
  OtpCodeRejectedError,
  OtpRateLimitedError,
} from '../../../core/session/customer-otp';
import { formatUzPhone } from '../../../core/session/phone';
import { DeliverySelectionService } from '../../../services/delivery-selection.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

/** The window a customer gets when the challenge did not say otherwise. */
const DEFAULT_COUNTDOWN_SECONDS = 44;
const CODE_LENGTH = 6;
const EMPTY_CELLS: readonly string[] = Array.from({ length: CODE_LENGTH }, () => '');

/** Where a sign-in ends when the customer did not start it on their way somewhere specific. */
const DEFAULT_DESTINATION = '/home';

interface FailureMessage {
  readonly key: string;
  readonly params?: Readonly<Record<string, number>>;
}

/**
 * SMS kodini tasdiqlash: the six digits, and the two calls that turn them into a
 * session (ADR 0051).
 *
 * <h2>Two calls, and the split is the point</h2>
 *
 * The attempt proves the customer controls the number and returns a *grant*,
 * which is not a session: single-use proof, for this brand, at this moment.
 * `POST /sessions` redeems it, and does the whole of sign-in in one transaction
 * -- spends the grant, finds or creates the account, mints the bearer. The token
 * is installed by `CustomerOtp.signIn` before it returns, so this screen never
 * holds a session the rest of the app does not know about.
 *
 * <h2>Where a sign-in ends</h2>
 *
 * At `/home`, unless the customer signed in on their way somewhere specific --
 * today only a guest at a table who tapped «sign in to order»
 * (`ReturnDestination`, which holds one allow-listed path and spends it on read).
 * The destination is read once, *after* the sign-in has succeeded: a wrong code
 * must not spend it, or the second, correct try would land at the front door.
 *
 * There is no terms screen in this app yet, so unlike `frontend/storefront` this
 * does not route a first-time customer through one; nothing on the platform
 * refuses an order for a stale acceptance.
 */
@Component({
  selector: 'app-auth-code',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auth-code.component.html',
  styleUrl: './auth-code.component.scss',
})
export class AuthCodeComponent implements OnInit, OnDestroy {
  private readonly otp = inject(CustomerOtp);
  private readonly router = inject(Router);
  private readonly delivery = inject(DeliverySelectionService);
  private readonly returnDestination = inject(ReturnDestination);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Canonical E.164, from the login screen. Never rendered whole. */
  private phone = '';
  /**
   * The challenge this screen is answering. A challenge is single-use and
   * short-lived and a resend supersedes it, so this changes when the customer
   * asks for another code -- and answering the old one afterwards is refused.
   */
  private challengeId: string | null = null;

  protected readonly digits = [0, 1, 2, 3, 4, 5];
  /** One entry per box, so a digit typed into the fourth box stays in the fourth box. */
  private readonly cells = signal<readonly string[]>(EMPTY_CELLS);
  protected readonly code = computed(() => this.cells().join(''));
  protected readonly countdown = signal(DEFAULT_COUNTDOWN_SECONDS);
  protected readonly loading = signal(false);
  protected readonly failure = signal<FailureMessage | null>(null);

  /** `+998 90 *** ** 67` -- enough to recognise, not enough to read out. */
  protected readonly phoneDisplay = signal('');

  protected readonly showResend = computed(() => this.countdown() <= 0);
  protected readonly canSubmit = computed(
    () => this.code().length === CODE_LENGTH && !this.loading(),
  );
  protected readonly clock = computed(() => {
    const seconds = Math.max(0, this.countdown());
    const minutes = Math.floor(seconds / 60);
    return `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  });

  private countdownTimer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    const state = history.state as Partial<AuthCodeState> | null;
    if (state?.phone) {
      this.phone = state.phone;
      this.phoneDisplay.set(mask(state.phone));
    }
    if (state?.challengeId) {
      this.challengeId = state.challengeId;
    }
    // Arriving here without a challenge means the screen was opened directly or
    // reloaded, and there is nothing to answer. Sending the customer back beats
    // six boxes that can only ever fail.
    if (!this.challengeId || !this.phone) {
      void this.router.navigate(['/auth/login']);
      return;
    }
    // The challenge's own deadline, not a guess: a countdown that ignores it can
    // promise more (or less) time than the platform is still honouring the code.
    this.countdown.set(secondsUntil(state?.expiresAt));
    this.startCountdown();
  }

  ngOnDestroy(): void {
    // A customer who leaves mid-countdown -- back, a deep link, the tab closing
    // -- must not leave this ticking: only reaching zero ever cleared it.
    this.stopCountdown();
  }

  protected digitAt(index: number): string {
    return this.cells()[index] ?? '';
  }

  protected onDigitInput(index: number, event: Event): void {
    const input = event.target as HTMLInputElement;
    this.failure.set(null);
    const typed = input.value.replace(/\D/g, '');
    if (typed.length >= CODE_LENGTH) {
      // A whole code at once -- an SMS autofill, or a paste into the first box.
      this.setCode(typed);
      this.focusBox(CODE_LENGTH - 1);
      return;
    }
    this.cells.update((cells) => cells.map((cell, at) => (at === index ? typed.slice(-1) : cell)));
    this.syncBoxes();
    if (typed && index < CODE_LENGTH - 1) {
      this.focusBox(index + 1);
    }
  }

  protected onDigitKeydown(index: number, event: KeyboardEvent): void {
    if (event.key === 'Backspace' && !this.digitAt(index) && index > 0) {
      this.focusBox(index - 1);
    }
  }

  protected onPaste(event: ClipboardEvent): void {
    event.preventDefault();
    const pasted = (event.clipboardData?.getData('text/plain') ?? '').replace(/\D/g, '');
    if (pasted.length >= CODE_LENGTH) {
      this.failure.set(null);
      this.setCode(pasted);
      this.focusBox(CODE_LENGTH - 1);
    }
  }

  /**
   * Asks for another code -- a real request, not a timer reset. A resend after
   * the window is a new intent and takes a new challenge, which supersedes the
   * old one: the id is replaced and any code from the first message stops
   * working. That is the platform's rule, not a choice made here.
   */
  protected async resend(): Promise<void> {
    if (this.countdown() > 0 || this.loading()) {
      return;
    }
    this.failure.set(null);
    this.loading.set(true);
    try {
      const challenge = await this.otp.requestCode(this.phone);
      this.challengeId = challenge.challengeId;
      this.setCode('');
      this.countdown.set(secondsUntil(challenge.expiresAt));
      this.startCountdown();
    } catch (error) {
      this.failure.set(this.messageFor(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected async submit(): Promise<void> {
    if (!this.canSubmit() || !this.challengeId) {
      return;
    }
    this.failure.set(null);
    this.loading.set(true);
    try {
      const grant = await this.otp.submitCode({ challengeId: this.challengeId, code: this.code() });
      await this.otp.signIn(grant.grant);
      // The number the customer just proved they control, kept in memory for
      // this session so the delivery form can offer it. Never persisted: see
      // DeliverySelectionService.
      this.delivery.rememberSignInPhone(this.phone);
      await this.router.navigate([this.returnDestination.consume() ?? DEFAULT_DESTINATION], {
        replaceUrl: true,
      });
    } catch (error) {
      this.setCode('');
      this.failure.set(this.messageFor(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected back(): void {
    void this.router.navigate(['/auth/login']);
  }

  /**
   * The outcomes a customer can tell apart and act on differently. A wrong code
   * says how many tries are left, because "wrong code" with two attempts
   * remaining and with none are the same sentence and completely different
   * situations; an ended challenge sends them for a new one instead of letting
   * them type into something spent.
   */
  private messageFor(error: unknown): FailureMessage {
    if (error instanceof OtpCodeRejectedError) {
      return error.attemptsRemaining === null
        ? { key: 'auth.errors.codeRejected' }
        : { key: 'auth.errors.codeRejectedWithTries', params: { count: error.attemptsRemaining } };
    }
    if (error instanceof OtpChallengeOverError) {
      return { key: 'auth.errors.challengeOver' };
    }
    if (error instanceof OtpRateLimitedError) {
      return { key: 'auth.errors.rateLimited' };
    }
    if (error instanceof CustomerSignInUnavailableError) {
      return { key: 'auth.errors.unavailable' };
    }
    return { key: 'errors.generic' };
  }

  private startCountdown(): void {
    this.stopCountdown();
    if (this.countdown() <= 0) {
      return;
    }
    this.countdownTimer = setInterval(() => {
      const next = this.countdown() - 1;
      this.countdown.set(next);
      if (next <= 0) {
        this.stopCountdown();
      }
    }, 1000);
  }

  private stopCountdown(): void {
    if (this.countdownTimer) {
      clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    }
  }

  private box(index: number): HTMLInputElement | null {
    return this.host.nativeElement.querySelector<HTMLInputElement>(`[data-code-input="${index}"]`);
  }

  private focusBox(index: number): void {
    this.box(index)?.focus();
  }

  /** Replaces the whole code -- `digits` beyond the sixth are dropped, and '' empties every box. */
  private setCode(digits: string): void {
    this.cells.set(EMPTY_CELLS.map((_, index) => digits.charAt(index)));
    this.syncBoxes();
  }

  /** Puts the boxes back in step with the code: a bound value that did not change is not rewritten. */
  private syncBoxes(): void {
    for (const index of this.digits) {
      const box = this.box(index);
      if (box) {
        box.value = this.digitAt(index);
      }
    }
  }
}

/** Whole seconds until `expiresAt`, or the default when there is no usable deadline. A passed one is 0. */
function secondsUntil(expiresAt: string | undefined): number {
  if (expiresAt) {
    const deadline = Date.parse(expiresAt);
    if (Number.isFinite(deadline)) {
      return Math.max(0, Math.floor((deadline - Date.now()) / 1000));
    }
  }
  return DEFAULT_COUNTDOWN_SECONDS;
}

/** `+998 90 *** ** 67`. */
function mask(e164: string): string {
  const groups = formatUzPhone(e164).split(' ');
  return groups.length === 5
    ? `${groups[0]} ${groups[1]} *** ** ${groups[4]}`
    : formatUzPhone(e164);
}
