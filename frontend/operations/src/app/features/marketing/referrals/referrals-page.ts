import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  DraftReferralProgramRequest,
  ReferralProgramView,
  ReferralRedemptionView,
  ReferralSummaryView,
  ReferralsApi,
} from './referrals-api';

type RewardShape = 'BOTH_SIDES' | 'REFERRER_ONLY';

/**
 * Marketing §6.6 Referrals — a new ADR, riding on ADR 0046's loyalty ledger.
 *
 * **Built, against the real backend.** The owner's 2026-09-05 decision is
 * that a tenant configures the reward shape itself rather than the platform
 * fixing one: `ReferralPolicyController`'s draft-then-activate-then-retire
 * program authors which shape a brand runs (both sides rewarded, or the
 * referrer only), the amounts, a per-referrer cap, and how long a redeemed
 * code stays open before it lapses unqualified. The redemptions table below
 * `ReferralOperationsController` reads is "referrals actually happening":
 * every code redemption a brand's customers have made, whether it already
 * paid out, and why a referrer's own reward was skipped when their cap was
 * already reached.
 *
 * **Honestly not built, in this same screen rather than a separate route.**
 * The IA row also owns website `?ref=` links, Telegram `startapp` deep links,
 * and a guided Mini-App/BotFather setup flow — none of which exists: those
 * are ADR 0044's `marketing.attribution_links`, still on that ADR's own
 * checklist ("Not built. All three are independent of the send path and can
 * ship in parallel"). A customer still gets a code and a friend can still
 * redeem it — `ReferralStorefrontController` exists for exactly that — but
 * nothing here renders a shareable link, because no link table exists to
 * render one from.
 */
@Component({
  selector: 'q-referrals-page',
  imports: [TPipe],
  templateUrl: './referrals-page.html',
  styleUrl: './referrals-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReferralsPage implements OnInit {
  private readonly api = inject(ReferralsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly programs = signal<readonly ReferralProgramView[]>([]);
  protected readonly summary = signal<ReferralSummaryView | null>(null);
  protected readonly redemptions = signal<readonly ReferralRedemptionView[]>([]);

  protected readonly actionError = signal<string | null>(null);
  protected readonly actingProgramId = signal<string | null>(null);

  // ------------------------------------------------------------- new program

  protected readonly showForm = signal(false);
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly formShape = signal<RewardShape>('BOTH_SIDES');
  protected readonly formReferrerRewardMinor = signal(10_000);
  protected readonly formRefereeRewardMinor = signal(5_000);
  protected readonly formHasCap = signal(false);
  protected readonly formCap = signal(5);
  protected readonly formRedemptionWindowDays = signal(14);
  protected readonly formRewardLotLifetimeDays = signal(90);

  protected readonly shapeChoices: readonly RewardShape[] = ['BOTH_SIDES', 'REFERRER_ONLY'];

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.programs.set(await this.api.listPrograms(scope));
      // Best-effort: the summary and the redemption list are context beside
      // the authoring form, not the form itself, so a principal who cannot
      // read them still gets a working authoring screen.
      try {
        this.summary.set(await this.api.summary(scope));
      } catch {
        this.summary.set(null);
      }
      try {
        this.redemptions.set(await this.api.redemptions(scope));
      } catch {
        this.redemptions.set([]);
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  // ------------------------------------------------------------- rendering

  protected statusLabelKey(status: string): MessageKey {
    return `marketing.referrals.status.${status}` as MessageKey;
  }

  protected shapeLabelKey(shape: RewardShape): MessageKey {
    return `marketing.referrals.shape.${shape}` as MessageKey;
  }

  protected skipReasonLabelKey(reason: string): MessageKey {
    return `marketing.referrals.skipReason.${reason}` as MessageKey;
  }

  /** Points have no currency symbol of their own — the same `formatWhole` trick `LoyaltyPage` uses. */
  protected formatWhole(value: number): string {
    return formatMoney({ amountMinor: value, currency: 'UZS' }, this.i18n.locale());
  }

  /** A UUID nobody reads in full — the first eight characters are enough to tell rows apart on this screen. */
  protected shortId(id: string): string {
    return id.slice(0, 8);
  }

  // -------------------------------------------------------------- authoring

  protected openForm(): void {
    this.formShape.set('BOTH_SIDES');
    this.formReferrerRewardMinor.set(10_000);
    this.formRefereeRewardMinor.set(5_000);
    this.formHasCap.set(false);
    this.formCap.set(5);
    this.formRedemptionWindowDays.set(14);
    this.formRewardLotLifetimeDays.set(90);
    this.formError.set(null);
    this.showForm.set(true);
  }

  protected closeForm(): void {
    this.showForm.set(false);
  }

  protected canSubmit(): boolean {
    return (
      !this.formSubmitting() &&
      this.formReferrerRewardMinor() > 0 &&
      (this.formShape() === 'REFERRER_ONLY' || this.formRefereeRewardMinor() > 0) &&
      this.formRedemptionWindowDays() > 0 &&
      this.formRewardLotLifetimeDays() > 0 &&
      (!this.formHasCap() || this.formCap() > 0)
    );
  }

  protected async submitForm(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmit()) {
      return;
    }
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      const request: DraftReferralProgramRequest = {
        rewardShape: this.formShape(),
        referrerRewardMinor: this.formReferrerRewardMinor(),
        refereeRewardMinor:
          this.formShape() === 'REFERRER_ONLY' ? 0 : this.formRefereeRewardMinor(),
        rewardCurrency: 'UZS',
        maxRewardedReferralsPerReferrer: this.formHasCap() ? this.formCap() : null,
        redemptionWindowDays: this.formRedemptionWindowDays(),
        rewardLotLifetimeDays: this.formRewardLotLifetimeDays(),
      };
      await this.api.draftProgram(scope, request);
      this.showForm.set(false);
      this.programs.set(await this.api.listPrograms(scope));
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.formSubmitting.set(false);
    }
  }

  protected async activateProgram(program: ReferralProgramView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingProgramId.set(program.id);
    this.actionError.set(null);
    try {
      await this.api.activateProgram(scope, program.id);
      this.programs.set(await this.api.listPrograms(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingProgramId.set(null);
    }
  }

  protected async retireProgram(program: ReferralProgramView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actingProgramId.set(program.id);
    this.actionError.set(null);
    try {
      await this.api.retireProgram(scope, program.id);
      this.programs.set(await this.api.listPrograms(scope));
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actingProgramId.set(null);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
