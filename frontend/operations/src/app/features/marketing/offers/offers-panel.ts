import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { parseZonedDatetimeLocal, toZonedDatetimeLocal } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Drawer } from '../../../shared/ui/drawer';
import { Modal } from '../../../shared/ui/modal';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { describeApiError } from '../../orders/order-errors';
import { LoyaltyApi } from '../loyalty/loyalty-api';
import { AudienceSummary, MarketingApi, MarketingTemplateView } from '../marketing-api';
import { MARKETING_FALLBACK_ZONE, formatMoment } from '../moment';
import { PromotionsApi } from '../promotions/promotions-api';
import { OfferRequest, OfferStatus, OfferView, OffersApi } from './offers-api';

/** The channels an offer can be shown in. A call-centre task has no offer to show, and no queue to land in yet. */
export const OFFER_CHANNELS: readonly string[] = [
  'MESSAGING_APP',
  'SMS',
  'EMAIL',
  'PUSH',
  'IN_APP',
];

type ReferenceKind = 'PROMOTION' | 'ACCRUAL_RULE';

/** Something an offer can point at, as the picker shows it. */
interface ReferenceChoice {
  readonly id: string;
  readonly label: string;
}

/** The offers of one lineage, newest version first. */
interface OfferGroup {
  readonly lineageId: string;
  readonly name: string;
  readonly versions: readonly OfferView[];
}

type FormMode = 'create' | 'edit' | 'version';

/**
 * A brand's offers (ADR 0112 Decision 3): versioned references to something pricing or loyalty
 * already owns, which the campaign and scenario editors select from.
 *
 * **Marketing never authors a discount or mints a point.** The form has a place to say *which*
 * promotion or accrual rule an offer points at, and no place to say how much it is worth: an
 * offer holds a reference, a validity window, the channels it may appear in, an audience and a
 * template, and the amount stays pricing's and loyalty's. A request that named both references or
 * neither is refused by the server with a sentence; this form prevents it by offering one choice.
 *
 * **A published version is never edited.** A scenario step names a specific version and an
 * approved scenario must not change meaning underfoot, so a published offer offers "new version"
 * and "retire" and nothing else; only a draft is rewritten in place. Publishing a version
 * supersedes the one in force, and retiring one makes every scenario that names it stop offering
 * it at the guest's next step, which the retire prompt says before it is confirmed.
 *
 * Reading is open to anyone who authors campaigns, because the editor selects from offers and a
 * marketer who cannot manage them must still see them. Writing needs `marketing.offer.manage`; the
 * server enforces it, so a refusal is shown, not hidden behind a button that is merely absent.
 */
@Component({
  selector: 'q-offers-panel',
  imports: [TPipe, Drawer, Modal, ConfirmDialog],
  templateUrl: './offers-panel.html',
  styleUrl: './offers-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OffersPanel implements OnInit {
  private readonly api = inject(OffersApi);
  private readonly marketing = inject(MarketingApi);
  private readonly promotions = inject(PromotionsApi);
  private readonly loyalty = inject(LoyaltyApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly offers = signal<readonly OfferView[]>([]);
  protected readonly actionError = signal<string | null>(null);
  protected readonly acting = signal(false);

  // What an offer may point at, and the words it may use. Each is read best-effort: a manager who
  // may write offers but not read promotions types the id by hand instead of losing the form.
  protected readonly promotionChoices = signal<readonly ReferenceChoice[] | null>(null);
  protected readonly accrualChoices = signal<readonly ReferenceChoice[] | null>(null);
  protected readonly templates = signal<readonly MarketingTemplateView[]>([]);
  protected readonly audiences = signal<readonly AudienceSummary[]>([]);

  protected readonly offerChannels = OFFER_CHANNELS;
  protected readonly zone = MARKETING_FALLBACK_ZONE;

  /** Offers grouped by lineage, the lineage touched most recently first, each newest version first. */
  protected readonly groups = computed<readonly OfferGroup[]>(() => {
    const byLineage = new Map<string, OfferView[]>();
    for (const offer of this.offers()) {
      byLineage.set(offer.lineageId, [...(byLineage.get(offer.lineageId) ?? []), offer]);
    }
    return [...byLineage.entries()]
      .map(([lineageId, versions]) => {
        const newestFirst = [...versions].sort((a, b) => b.versionNumber - a.versionNumber);
        return { lineageId, name: newestFirst[0].displayName, versions: newestFirst };
      })
      .sort((a, b) => Date.parse(b.versions[0].updatedAt) - Date.parse(a.versions[0].updatedAt));
  });

  // ------------------------------------------------------------------ the form

  protected readonly formMode = signal<FormMode | null>(null);
  private formSource: OfferView | null = null;
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly formName = signal('');
  protected readonly formReferenceKind = signal<ReferenceKind>('PROMOTION');
  protected readonly formReferenceId = signal('');
  protected readonly formValidFrom = signal('');
  protected readonly formValidUntil = signal('');
  protected readonly formAudienceId = signal('');
  protected readonly formChannels = signal<readonly string[]>(['MESSAGING_APP']);
  protected readonly formTemplateKey = signal('');
  protected readonly formBanner = signal('');

  protected readonly referenceChoices = computed(() =>
    this.formReferenceKind() === 'PROMOTION' ? this.promotionChoices() : this.accrualChoices(),
  );

  protected readonly marketingTemplates = computed(() =>
    this.templates().filter((template) => template.notificationClass === 'MARKETING'),
  );

  /** Why the form cannot be saved yet, as a key; null when it can. */
  protected readonly formProblem = computed<MessageKey | null>(() => {
    if (this.formName().trim() === '') {
      return 'marketing.offers.problem.name';
    }
    if (this.formReferenceId().trim() === '') {
      return 'marketing.offers.problem.reference';
    }
    if (this.formValidFrom() === '') {
      return 'marketing.offers.problem.validFrom';
    }
    if (
      this.formValidUntil() !== '' &&
      this.instant(this.formValidUntil()) <= this.instant(this.formValidFrom())
    ) {
      return 'marketing.offers.problem.window';
    }
    if (this.formChannels().length === 0) {
      return 'marketing.offers.problem.channels';
    }
    if (this.formTemplateKey().trim() === '') {
      return 'marketing.offers.problem.template';
    }
    return null;
  });

  // -------------------------------------------------------------- the prompts

  protected readonly publishing = signal<OfferView | null>(null);
  protected readonly retiring = signal<OfferView | null>(null);
  protected readonly retireReason = signal('');

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
      this.offers.set(await this.api.list(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
    if (this.denied()) {
      return;
    }
    await Promise.all([
      this.loadReferences(),
      this.marketing
        .listTemplates(scope)
        .then((templates) => this.templates.set(templates))
        .catch(() => this.templates.set([])),
      this.marketing
        .listAudiences(scope)
        .then((audiences) => this.audiences.set(audiences))
        .catch(() => this.audiences.set([])),
    ]);
  }

  private async loadReferences(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    try {
      const promotions = await this.promotions.list(scope);
      this.promotionChoices.set(
        promotions
          .filter((promotion) => promotion.status !== 'ARCHIVED')
          .map((promotion) => ({
            id: promotion.promotionId,
            label: `${promotion.name} (${promotion.code})`,
          })),
      );
    } catch {
      this.promotionChoices.set(null);
    }
    try {
      const rules = await this.loyalty.listAccrualRules(scope);
      this.accrualChoices.set(
        rules
          .filter((rule) => rule.status !== 'RETIRED')
          .map((rule) => ({
            id: rule.id,
            label: this.i18n.t('marketing.offers.accrualRuleLabel', {
              rate: (rule.rateBasisPoints / 100).toString(),
              status: rule.status,
            }),
          })),
      );
    } catch {
      this.accrualChoices.set(null);
    }
  }

  // -------------------------------------------------------------- rendering

  protected statusLabelKey(status: OfferStatus): MessageKey {
    return STATUS_KEYS[status];
  }

  protected channelLabel(channel: string): string {
    const key = CHANNEL_KEYS[channel];
    return key ? this.i18n.t(key) : channel;
  }

  protected window(offer: OfferView): string {
    const from = formatMoment(offer.validFrom);
    return offer.validUntil === null
      ? this.i18n.t('marketing.offerPicker.window.open', { from })
      : this.i18n.t('marketing.offerPicker.window.closed', {
          from,
          until: formatMoment(offer.validUntil),
        });
  }

  protected referenceText(offer: OfferView): string {
    const id = offer.pricingPromotionId ?? offer.loyaltyAccrualRuleId ?? '';
    const choices =
      offer.pricingPromotionId !== null ? this.promotionChoices() : this.accrualChoices();
    const label = choices?.find((choice) => choice.id === id)?.label;
    const kind = this.i18n.t(
      offer.pricingPromotionId !== null
        ? 'marketing.offer.reference.promotion'
        : 'marketing.offer.reference.accrualRule',
    );
    return label ? `${kind}: ${label}` : kind;
  }

  // ------------------------------------------------------------------ the form

  protected openCreate(): void {
    this.formSource = null;
    this.fillForm(null);
    this.formMode.set('create');
  }

  protected openEdit(offer: OfferView): void {
    this.formSource = offer;
    this.fillForm(offer);
    this.formMode.set('edit');
  }

  protected openNewVersion(offer: OfferView): void {
    this.formSource = offer;
    this.fillForm(offer);
    this.formMode.set('version');
  }

  protected closeForm(): void {
    this.formMode.set(null);
  }

  private fillForm(offer: OfferView | null): void {
    this.formError.set(null);
    this.formName.set(offer?.displayName ?? '');
    this.formReferenceKind.set(offer?.loyaltyAccrualRuleId ? 'ACCRUAL_RULE' : 'PROMOTION');
    this.formReferenceId.set(offer?.pricingPromotionId ?? offer?.loyaltyAccrualRuleId ?? '');
    this.formValidFrom.set(
      toZonedDatetimeLocal(offer ? new Date(offer.validFrom) : new Date(), this.zone),
    );
    this.formValidUntil.set(
      offer?.validUntil ? toZonedDatetimeLocal(new Date(offer.validUntil), this.zone) : '',
    );
    this.formAudienceId.set(offer?.audienceId ?? '');
    this.formChannels.set(offer ? [...offer.allowedChannels] : ['MESSAGING_APP']);
    this.formTemplateKey.set(offer?.templateKey ?? '');
    this.formBanner.set(offer?.bannerImageReference ?? '');
  }

  protected onReferenceKindChange(kind: ReferenceKind): void {
    this.formReferenceKind.set(kind);
    this.formReferenceId.set('');
  }

  protected toggleChannel(channel: string, on: boolean): void {
    this.formChannels.update((channels) =>
      on
        ? [...channels.filter((c) => c !== channel), channel]
        : channels.filter((c) => c !== channel),
    );
  }

  private instant(local: string): number {
    return parseZonedDatetimeLocal(local, this.zone).getTime();
  }

  /** The request the form describes: a reference, a window, channels, an audience, a template. Never a benefit. */
  protected request(): OfferRequest {
    const promotion = this.formReferenceKind() === 'PROMOTION';
    const referenceId = this.formReferenceId().trim();
    return {
      displayName: this.formName().trim(),
      pricingPromotionId: promotion ? referenceId : null,
      loyaltyAccrualRuleId: promotion ? null : referenceId,
      validFrom: parseZonedDatetimeLocal(this.formValidFrom(), this.zone).toISOString(),
      validUntil:
        this.formValidUntil() === ''
          ? null
          : parseZonedDatetimeLocal(this.formValidUntil(), this.zone).toISOString(),
      audienceId: this.formAudienceId() === '' ? null : this.formAudienceId(),
      allowedChannels: this.formChannels(),
      templateKey: this.formTemplateKey().trim(),
      templateVersion: null,
      bannerImageReference: this.formBanner().trim() === '' ? null : this.formBanner().trim(),
    };
  }

  protected async submitForm(): Promise<void> {
    const scope = this.brand.scope();
    const mode = this.formMode();
    if (!scope || mode === null || this.formProblem() !== null || this.formSubmitting()) {
      return;
    }
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      const request = this.request();
      const source = this.formSource;
      if (mode === 'create') {
        await this.api.create(scope, request);
      } else if (mode === 'edit' && source) {
        await this.api.rewriteDraft(scope, source.offerId, request, source.version);
      } else if (source) {
        await this.api.newVersion(scope, source.offerId, request);
      }
      this.formMode.set(null);
      this.offers.set(await this.api.list(scope));
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.formSubmitting.set(false);
    }
  }

  // --------------------------------------------------------- publish and retire

  protected askPublish(offer: OfferView): void {
    this.actionError.set(null);
    this.publishing.set(offer);
  }

  protected async confirmPublish(): Promise<void> {
    const offer = this.publishing();
    const scope = this.brand.scope();
    if (!offer || !scope) {
      return;
    }
    await this.act(async () => {
      await this.api.publish(scope, offer.offerId, offer.version);
    });
    this.publishing.set(null);
  }

  protected askRetire(offer: OfferView): void {
    this.actionError.set(null);
    this.retireReason.set('');
    this.retiring.set(offer);
  }

  protected async confirmRetire(): Promise<void> {
    const offer = this.retiring();
    const scope = this.brand.scope();
    const reason = this.retireReason().trim();
    if (!offer || !scope || reason === '') {
      return;
    }
    await this.act(async () => {
      await this.api.retire(scope, offer.offerId, reason, offer.version);
    });
    this.retiring.set(null);
  }

  /** One mutation, then a re-read: the server's version numbers are the ones the next click must send. */
  private async act(run: () => Promise<void>): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.acting.set(true);
    this.actionError.set(null);
    try {
      await run();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      try {
        this.offers.set(await this.api.list(scope));
      } catch {
        // The error above, if any, is the more useful thing to show.
      }
      this.acting.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

const STATUS_KEYS: Readonly<Record<OfferStatus, MessageKey>> = {
  DRAFT: 'marketing.offer.status.DRAFT',
  PUBLISHED: 'marketing.offer.status.PUBLISHED',
  SUPERSEDED: 'marketing.offer.status.SUPERSEDED',
  RETIRED: 'marketing.offer.status.RETIRED',
};

const CHANNEL_KEYS: Readonly<Record<string, MessageKey>> = {
  SMS: 'marketing.channel.SMS',
  EMAIL: 'marketing.channel.EMAIL',
  PUSH: 'marketing.channel.PUSH',
  MESSAGING_APP: 'marketing.channel.MESSAGING_APP',
  IN_APP: 'marketing.channel.IN_APP',
  CALL_CENTRE: 'marketing.channel.CALL_CENTRE',
};
