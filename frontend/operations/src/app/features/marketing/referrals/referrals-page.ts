import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { AttributionLinkView, MarketingApi } from '../marketing-api';
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
 * **Acquisition links (T18, operations §6.6a).** ADR 0044's own
 * `marketing.attribution_links` now exists — a marketer mints a trackable
 * website `?ref={token}` link or a Telegram `startapp` deep link for a
 * campaign or an influencer, both rendered from the token client-side. What
 * stays honestly not built, in this same screen rather than a separate
 * route: the guided Mini-App/BotFather setup flow, and recording which link
 * actually brought a given account or order (ADR 0044's own note: that half
 * needs columns on a customer account and an order, other modules' tables,
 * and is follow-on integration work for whichever surface serves the
 * redirect or deep link). A customer still gets a referral code and a
 * friend can still redeem it — `ReferralStorefrontController` exists for
 * exactly that, and is a distinct mechanism from an acquisition link.
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
  private readonly marketing = inject(MarketingApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly programs = signal<readonly ReferralProgramView[]>([]);
  protected readonly summary = signal<ReferralSummaryView | null>(null);
  protected readonly redemptions = signal<readonly ReferralRedemptionView[]>([]);

  // -------------------------------------------------------- acquisition links

  protected readonly links = signal<readonly AttributionLinkView[]>([]);
  protected readonly linksError = signal<string | null>(null);
  protected readonly linksActingId = signal<string | null>(null);

  protected readonly showLinkForm = signal(false);
  protected readonly linkFormSubmitting = signal(false);
  protected readonly linkFormError = signal<string | null>(null);
  protected readonly linkFormLabel = signal('');
  protected readonly linkFormOwnerNote = signal('');
  protected readonly linkFormChannel = signal('WEB');
  protected readonly linkFormDestinationType = signal('STOREFRONT_HOME');
  protected readonly linkFormDestinationId = signal('');
  protected readonly linkChannels: readonly string[] = [
    'WEB',
    'TELEGRAM_BOT',
    'TELEGRAM_MINI_APP',
    'MOBILE_APP',
  ];
  protected readonly linkDestinationTypes: readonly string[] = [
    'STOREFRONT_HOME',
    'CAMPAIGN',
    'INFLUENCER',
  ];

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
      // MARKETING_LINK_MANAGE is a separate grant from the referral program
      // capabilities above (T18) — best-effort for the same reason.
      try {
        this.links.set(await this.marketing.listAttributionLinks(scope));
      } catch {
        this.links.set([]);
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

  // -------------------------------------------------------- acquisition links

  protected linkChannelLabelKey(channel: string): MessageKey {
    return `marketing.attributionLinks.channel.${channel}` as MessageKey;
  }

  protected linkDestinationTypeLabelKey(destinationType: string): MessageKey {
    return `marketing.attributionLinks.destinationType.${destinationType}` as MessageKey;
  }

  /**
   * What the console renders as the shareable link — computed here, never
   * stored: a tenant's own domain can change without invalidating an issued
   * token (ADR 0044's own note). The bot deep link is a placeholder shape
   * ({@code https://t.me/<bot>?start=<token>}) until this build knows which
   * bot a brand's Telegram channel actually is.
   */
  protected renderedLink(link: AttributionLinkView): string {
    if (link.channel === 'WEB') {
      return `https://{tenant-domain}/?ref=${link.token}`;
    }
    return `https://t.me/{bot}?start=${link.token}`;
  }

  protected openLinkForm(): void {
    this.linkFormLabel.set('');
    this.linkFormOwnerNote.set('');
    this.linkFormChannel.set('WEB');
    this.linkFormDestinationType.set('STOREFRONT_HOME');
    this.linkFormDestinationId.set('');
    this.linkFormError.set(null);
    this.showLinkForm.set(true);
  }

  protected closeLinkForm(): void {
    this.showLinkForm.set(false);
  }

  protected canSubmitLinkForm(): boolean {
    return (
      !this.linkFormSubmitting() &&
      this.linkFormLabel().trim().length > 0 &&
      (this.linkFormDestinationType() !== 'CAMPAIGN' ||
        this.linkFormDestinationId().trim().length > 0)
    );
  }

  protected async submitLinkForm(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmitLinkForm()) {
      return;
    }
    this.linkFormSubmitting.set(true);
    this.linkFormError.set(null);
    try {
      await this.marketing.mintAttributionLink(scope, {
        label: this.linkFormLabel().trim(),
        ownerNote: this.linkFormOwnerNote().trim() || null,
        channel: this.linkFormChannel(),
        destinationType: this.linkFormDestinationType(),
        destinationId:
          this.linkFormDestinationType() === 'CAMPAIGN'
            ? this.linkFormDestinationId().trim()
            : null,
      });
      this.showLinkForm.set(false);
      this.links.set(await this.marketing.listAttributionLinks(scope));
    } catch (error) {
      this.linkFormError.set(this.describe(error));
    } finally {
      this.linkFormSubmitting.set(false);
    }
  }

  protected async archiveLink(link: AttributionLinkView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.linksActingId.set(link.linkId);
    this.linksError.set(null);
    try {
      await this.marketing.archiveAttributionLink(scope, link.linkId);
      this.links.set(await this.marketing.listAttributionLinks(scope));
    } catch (error) {
      this.linksError.set(this.describe(error));
    } finally {
      this.linksActingId.set(null);
    }
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
