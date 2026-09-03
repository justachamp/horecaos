import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import { Auth } from '../../../core/auth/auth';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { CampaignView, MarketingApi, RecipientView } from '../marketing-api';

/**
 * One campaign's full lifecycle (ADR 0044, §6.4 Campaigns) — the dock
 * `CampaignsPage` opens beside its list, the same shape `OrderDetailPane`
 * uses beside the order queue.
 *
 * Renders the machinery honestly, per this wave's own working rule:
 *
 * - **Four-eyes.** `IN_REVIEW` shows one of two things depending on who is
 *   looking: the author sees "awaiting a second signature"; anybody else
 *   sees the approve action. This is an identity comparison
 *   (`campaign.createdBy` vs {@link Auth#subject}), not a capability check —
 *   the server still enforces `campaign.approve` and "not the author" on its
 *   own (`CampaignService#approve`'s own doc).
 * - **The estimate is captioned as an estimate**, never presented as a
 *   promise: `estimatedRecipients` is an upper bound (ADR 0044: the same five
 *   checks run again per recipient at send), the cost is a low–high range,
 *   and the delivery window is "a planning number, not a promise"
 *   (`CampaignService#prepare`'s own doc).
 * - **A paused campaign shows what the pause is costing** —
 *   `campaign.blockedCount` — and a resume's response reports exactly how
 *   many messages `CAMPAIGN_NOT_SENDING` will not retry, shown inline rather
 *   than discarded.
 * - **An entitlement refusal names the entitlement.** Launching a TELEGRAM
 *   (`MESSAGING_APP`) campaign against a tenant without
 *   `telegram.broadcasts.enabled` comes back `ENTITLEMENT_REQUIRED` carrying
 *   `entitlementKey` in the problem body (`EntitlementQueryService#requireFeature`);
 *   this pane reads it rather than showing the generic "subscription does not
 *   include this" sentence alone.
 */
@Component({
  selector: 'q-campaign-detail-pane',
  imports: [TPipe],
  templateUrl: './campaign-detail-pane.html',
  styleUrl: './campaign-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CampaignDetailPane implements OnInit {
  /** Bound by `withComponentInputBinding()` from the `:campaignId` route param. */
  readonly campaignId = input.required<string>();

  private readonly api = inject(MarketingApi);
  private readonly brand = inject(CurrentBrand);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly campaign = signal<CampaignView | null>(null);
  protected readonly recipients = signal<readonly RecipientView[]>([]);

  protected readonly actionSubmitting = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly lastResumeCost = signal<number | null>(null);

  protected readonly showReasonPrompt = signal<'approve' | 'halt' | 'resume' | null>(null);
  protected readonly reasonText = signal('');

  /** True when the signed-in operator authored this campaign — the maker/checker split. */
  protected readonly isAuthor = computed(() => {
    const c = this.campaign();
    const subject = this.auth.subject();
    return c !== null && subject !== null && c.createdBy.toLowerCase() === subject.toLowerCase();
  });

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.loading.set(false);
      return;
    }
    try {
      const [campaign, recipients] = await Promise.all([
        this.api.getCampaign(scope, this.campaignId()),
        this.api.recipients(scope, this.campaignId()).catch(() => []),
      ]);
      this.campaign.set(campaign);
      this.recipients.set(recipients);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected close(): void {
    void this.router.navigate(['/marketing/campaigns']);
  }

  protected channelLabelKey(channel: string): MessageKey {
    return `marketing.channel.${channel}` as MessageKey;
  }

  protected statusLabelKey(status: string): MessageKey {
    return `marketing.campaign.status.${status}` as MessageKey;
  }

  protected refusalLabelKey(reason: string): MessageKey {
    return `marketing.refusal.${reason}` as MessageKey;
  }

  protected reasonPromptTitleKey(kind: 'approve' | 'halt' | 'resume'): MessageKey {
    return `marketing.campaign.reasonPrompt.title.${kind}` as MessageKey;
  }

  // -------------------------------------------------------------- estimate

  protected async runEstimate(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.api.estimate(scope, this.campaignId());
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  protected async submit(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.api.submit(scope, this.campaignId());
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  // ------------------------------------------------------- reason-carrying actions

  protected openReasonPrompt(kind: 'approve' | 'halt' | 'resume'): void {
    this.reasonText.set('');
    this.actionError.set(null);
    this.showReasonPrompt.set(kind);
  }

  protected closeReasonPrompt(): void {
    this.showReasonPrompt.set(null);
  }

  protected async confirmReasonPrompt(): Promise<void> {
    const scope = this.brand.scope();
    const kind = this.showReasonPrompt();
    const reason = this.reasonText().trim();
    if (!scope || !kind || reason.length === 0) {
      return;
    }
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      if (kind === 'approve') {
        await this.api.approve(scope, this.campaignId(), reason);
      } else if (kind === 'halt') {
        await this.api.halt(scope, this.campaignId(), reason);
      } else {
        const outcome = await this.api.resume(scope, this.campaignId(), reason);
        this.lastResumeCost.set(outcome.suppressedDuringPause);
      }
      this.showReasonPrompt.set(null);
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  // -------------------------------------------------------------------- launch

  protected async launch(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.api.launch(scope, this.campaignId());
      await this.load();
    } catch (error) {
      this.actionError.set(this.describeLaunchError(error));
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  /**
   * Names the entitlement rather than only the generic refusal, per this
   * wave's rule that a launch refused on entitlement grounds shows the
   * entitlement reason.
   */
  private describeLaunchError(error: unknown): string {
    if (error instanceof ApiError && error.code === ApiErrorCode.ENTITLEMENT_REQUIRED) {
      const key = error.problem?.['entitlementKey'];
      if (key === 'telegram.broadcasts.enabled') {
        return this.i18n.t('marketing.campaign.entitlement.telegramBroadcasts');
      }
      return (
        this.i18n.t('error.ENTITLEMENT_REQUIRED') + (typeof key === 'string' ? ` (${key})` : '')
      );
    }
    return this.describe(error);
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
