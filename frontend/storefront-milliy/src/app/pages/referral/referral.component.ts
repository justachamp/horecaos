import { ChangeDetectionStrategy, Component, type OnInit, inject, signal } from '@angular/core';

import { HorecaOSApiError } from '../../core/api/problem-details';
import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import { type RedemptionResponse, ReferralService } from '../../services/referral.service';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TranslateService } from '../../services/translate.service';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * Referral: the caller's own code, sharing it, and redeeming a friend's
 * (ADR 0067, operations §6.6 Referrals, the customer half of its own Exit
 * Criteria).
 *
 * <h2>What a code discloses, and why sharing it is safe (ADR 0029)</h2>
 *
 * `ReferralCodeService.generate` mints eight characters of Crockford base32
 * from a `SecureRandom` -- not derived from the account id, phone, or signup
 * time, and looked up only by its own unique index. A code shared with a
 * friend hands them a bare random token; nothing about who issued it,
 * decodable or otherwise, travels with it. That is also why this screen
 * shares the *code alone* rather than minting a link: a link would need
 * either the account id in the URL (a leak this ADR 0029 audit exists to
 * catch) or a redirect service this platform does not have. A friend types
 * the eight characters by hand, the same as a promo code.
 *
 * <h2>What this screen shows, and what it deliberately does not</h2>
 *
 * `ReferralService.myReferral` is the only read a customer principal can
 * reach (see its own doc comment for why): this screen shows the caller's
 * code, and, once they have redeemed a friend's, that redemption's own
 * status and dates. It never shows "N friends joined" or a points total --
 * `ReferralQueryService` has no read of redemptions *by referrer* for a
 * customer, only for a marketer, so a friend-count here would be invented
 * rather than read. The empty state says as much rather than showing a zero
 * that looks like a real count.
 */
@Component({
  selector: 'app-referral',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './referral.component.html',
  styleUrl: './referral.component.scss',
})
export class ReferralComponent implements OnInit {
  private readonly referrals = inject(ReferralService);
  private readonly translate = inject(TranslateService);
  private readonly lang = inject(LangService);

  protected readonly state = signal<LoadState>('loading');
  protected readonly code = signal<string | null>(null);
  protected readonly redeemedAs = signal<RedemptionResponse | null>(null);

  protected readonly shareCopied = signal(false);

  protected readonly redeemInput = signal('');
  protected readonly redeeming = signal(false);
  protected readonly redeemErrorKey = signal<string | null>(null);
  protected readonly justRedeemed = signal(false);

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected async load(): Promise<void> {
    this.state.set('loading');
    try {
      const mine = await this.referrals.myReferral();
      this.code.set(mine.code);
      this.redeemedAs.set(mine.redeemedAs);
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  /**
   * Web Share on a device that offers it, otherwise the clipboard --
   * `shareCopied` covers the fallback so the customer sees the copy actually
   * happened. Shares the bare code (see the class doc comment for why there
   * is no link to share instead), inside one honest sentence.
   */
  protected async share(): Promise<void> {
    const code = this.code();
    if (!code) {
      return;
    }
    const text = this.translate.getWithParams('referral.shareMessage', { code });

    if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
      try {
        await navigator.share({ text });
        return;
      } catch {
        // A cancelled share sheet is not a failure to report; fall through
        // to clipboard only if share itself was never actually available.
        return;
      }
    }

    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        this.shareCopied.set(true);
        setTimeout(() => this.shareCopied.set(false), 2000);
      } catch {
        // Nothing this screen can do about a denied clipboard permission;
        // the code stays on screen for the customer to copy by hand.
      }
    }
  }

  protected async submitRedeem(): Promise<void> {
    const value = this.redeemInput().trim();
    if (!value || this.redeeming()) {
      return;
    }
    this.redeeming.set(true);
    this.redeemErrorKey.set(null);
    try {
      const redemption = await this.referrals.redeem(value);
      this.redeemedAs.set(redemption);
      this.justRedeemed.set(true);
      this.redeemInput.set('');
    } catch (failure) {
      this.redeemErrorKey.set(this.redeemErrorMessageKey(failure));
    } finally {
      this.redeeming.set(false);
    }
  }

  /** Matches `OrdersComponent.dateLabel`'s own locale handling -- there is no shared date pipe in this app. */
  protected dateLabel(iso: string): string {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return date.toLocaleDateString(this.lang.langId() === 'uz' ? 'uz-UZ' : this.lang.langId(), {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  }

  /**
   * `ReferralRedemptionService.redeem` distinguishes four refusals by
   * `ErrorCode` alone -- never by `detail`, per ADR 0031 -- and two of them
   * (self-referral, already having a completed order at this brand) share
   * `VALIDATION_FAILED` with no further reason on the wire. Both collapse to
   * one honest "you're not eligible" message rather than a guess at which one
   * happened.
   */
  private redeemErrorMessageKey(failure: unknown): string {
    if (!(failure instanceof HorecaOSApiError)) {
      return 'errors.generic';
    }
    switch (failure.code) {
      case 'RESOURCE_NOT_FOUND':
        return 'referral.codeNotFound';
      case 'RESOURCE_CONFLICT':
        return 'referral.alreadyRedeemed';
      case 'UNPROCESSABLE_STATE':
        return 'referral.noActiveProgram';
      case 'VALIDATION_FAILED':
        return 'referral.notEligible';
      default:
        return 'errors.generic';
    }
  }
}
