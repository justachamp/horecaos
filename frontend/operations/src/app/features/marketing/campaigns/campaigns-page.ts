import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  AudienceSummary,
  CampaignView,
  MarketingApi,
  MarketingTemplateView,
  SuppressionView,
} from '../marketing-api';
import {
  PREDICATE_TYPES,
  descriptorFor,
  operatorsFor,
  PredicateValueKind,
} from '../audience-predicates';

/** A marketing channel this build knows, with whether one more recipient costs money. */
const CHANNELS: readonly { readonly value: string; readonly marginalCost: boolean }[] = [
  { value: 'SMS', marginalCost: true },
  { value: 'EMAIL', marginalCost: true },
  { value: 'PUSH', marginalCost: false },
  { value: 'MESSAGING_APP', marginalCost: false },
];

/** One predicate row being authored — the form's own shape, converted to the wire shape on submit. */
interface PredicateDraft {
  type: string;
  operator: string;
  numericLow: number | null;
  numericHigh: number | null;
  dateLow: string | null;
  dateHigh: string | null;
  textValuesCsv: string;
  audienceId: string | null;
}

function newPredicateDraft(): PredicateDraft {
  return {
    type: 'RECENCY_DAYS',
    operator: 'AT_LEAST',
    numericLow: null,
    numericHigh: null,
    dateLow: null,
    dateHigh: null,
    textValuesCsv: '',
    audienceId: null,
  };
}

/**
 * Marketing §6.4 Campaigns — the one Marketing screen with a real backend to
 * render (ADR 0044): audience targeting (this row's own "RFM targeting"
 * bullet), the campaign lifecycle, and suppression management (this row's
 * "consent and suppression enforced in audience selection" bullet).
 *
 * Three sub-views toggled in-page rather than three more routes, because none
 * of Audiences or Suppressions is its own IA row — both are folded into 6.4 —
 * and a deep link into one row's lifecycle is the one case that earns a real
 * route: see `campaign-detail-pane.ts`.
 */
@Component({
  selector: 'q-campaigns-page',
  imports: [TPipe, RouterOutlet],
  templateUrl: './campaigns-page.html',
  styleUrl: './campaigns-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CampaignsPage implements OnInit {
  private readonly api = inject(MarketingApi);
  private readonly brand = inject(CurrentBrand);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  /** Whether the detail route (`:campaignId`) is currently activated — same pattern `OrdersPage` uses for its own dock. */
  protected readonly docked = signal(false);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly view = signal<'campaigns' | 'audiences' | 'suppressions'>('campaigns');

  protected readonly campaigns = signal<readonly CampaignView[]>([]);
  protected readonly audiences = signal<readonly AudienceSummary[]>([]);
  protected readonly templates = signal<readonly MarketingTemplateView[]>([]);
  protected readonly templatesDenied = signal(false);

  protected readonly suppressions = signal<readonly SuppressionView[]>([]);
  protected readonly suppressionsLoaded = signal(false);
  protected readonly suppressionsLoading = signal(false);
  protected readonly suppressionsActiveOnly = signal(true);
  protected readonly suppressionsError = signal<string | null>(null);

  protected readonly liftingId = signal<string | null>(null);
  protected readonly liftReason = signal('');
  protected readonly liftSubmitting = signal(false);
  protected readonly liftError = signal<string | null>(null);

  // --------------------------------------------------------------- create campaign

  protected readonly showCreateCampaign = signal(false);
  protected readonly createCampaignSubmitting = signal(false);
  protected readonly createCampaignError = signal<string | null>(null);
  protected readonly newCampaignName = signal('');
  protected readonly newCampaignAudienceId = signal('');
  protected readonly newCampaignChannel = signal('SMS');
  protected readonly newCampaignTemplateKey = signal('');
  protected readonly newCampaignRecipientCap = signal(1000);
  protected readonly newCampaignCostCeilingMinor = signal<number | null>(null);
  protected readonly newCampaignCurrency = signal('UZS');

  protected readonly channels = CHANNELS;

  protected readonly channelCarriesMarginalCost = computed(
    () => CHANNELS.find((c) => c.value === this.newCampaignChannel())?.marginalCost ?? false,
  );

  /** MARKETING-class templates for the channel selected, so a campaign's consent purpose comes from the template it will actually use. */
  protected readonly templatesForChannel = computed(() =>
    this.templates().filter(
      (t) => t.notificationClass === 'MARKETING' && t.channel === this.newCampaignChannel(),
    ),
  );

  protected readonly selectedTemplate = computed(
    () =>
      this.templatesForChannel().find((t) => t.templateKey === this.newCampaignTemplateKey()) ??
      null,
  );

  // --------------------------------------------------------------- create audience

  protected readonly showCreateAudience = signal(false);
  protected readonly createAudienceSubmitting = signal(false);
  protected readonly createAudienceError = signal<string | null>(null);
  protected readonly newAudienceName = signal('');
  protected readonly newAudienceDescription = signal('');
  protected readonly newAudiencePredicates = signal<PredicateDraft[]>([newPredicateDraft()]);

  protected readonly predicateTypes = PREDICATE_TYPES;

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
      const [campaigns, audiences] = await Promise.all([
        this.api.listCampaigns(scope),
        this.api.listAudiences(scope),
      ]);
      this.campaigns.set(campaigns);
      this.audiences.set(audiences);
      // Best-effort: a campaign author who does not also hold
      // NOTIFICATION_TEMPLATE_AUTHOR still gets a working page — the create
      // form falls back to typing the template key by hand.
      try {
        this.templates.set(await this.api.listTemplates(scope));
      } catch (error) {
        if (error instanceof ApiError && error.status === 403) {
          this.templatesDenied.set(true);
        }
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

  protected switchView(next: 'campaigns' | 'audiences' | 'suppressions'): void {
    this.view.set(next);
    if (next === 'suppressions' && !this.suppressionsLoaded()) {
      void this.loadSuppressions();
    }
  }

  protected audienceName(audienceId: string): string {
    return this.audiences().find((a) => a.audienceId === audienceId)?.name ?? audienceId;
  }

  protected channelLabelKey(channel: string): MessageKey {
    return `marketing.channel.${channel}` as MessageKey;
  }

  protected statusLabelKey(status: string): MessageKey {
    return `marketing.campaign.status.${status}` as MessageKey;
  }

  /**
   * The four-eyes hint a maker/checker actually needs (task rule: a maker sees
   * "awaiting second signature", a checker sees the approve action). This page
   * only has room for the short row-level hint; the full action lives in
   * `CampaignDetailPane`.
   */
  protected fourEyesHint(campaign: CampaignView): MessageKey | null {
    if (campaign.status !== 'IN_REVIEW') {
      return null;
    }
    return 'marketing.campaigns.list.awaitingSignature';
  }

  protected openCampaign(campaign: CampaignView): void {
    void this.router.navigate(['/marketing/campaigns', campaign.campaignId]);
  }

  // ----------------------------------------------------------- create campaign

  protected openCreateCampaign(): void {
    this.newCampaignName.set('');
    this.newCampaignAudienceId.set(this.audiences()[0]?.audienceId ?? '');
    this.newCampaignChannel.set('SMS');
    this.newCampaignTemplateKey.set('');
    this.newCampaignRecipientCap.set(1000);
    this.newCampaignCostCeilingMinor.set(null);
    this.newCampaignCurrency.set('UZS');
    this.createCampaignError.set(null);
    this.showCreateCampaign.set(true);
  }

  protected closeCreateCampaign(): void {
    this.showCreateCampaign.set(false);
  }

  protected canCreateCampaign(): boolean {
    return (
      !this.createCampaignSubmitting() &&
      this.newCampaignName().trim().length > 0 &&
      this.newCampaignAudienceId().length > 0 &&
      this.newCampaignTemplateKey().trim().length > 0 &&
      this.newCampaignRecipientCap() > 0 &&
      (!this.channelCarriesMarginalCost() ||
        (this.newCampaignCostCeilingMinor() !== null && this.newCampaignCostCeilingMinor()! > 0))
    );
  }

  protected async submitCreateCampaign(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canCreateCampaign()) {
      return;
    }
    this.createCampaignSubmitting.set(true);
    this.createCampaignError.set(null);
    try {
      const template = this.selectedTemplate();
      const created = await this.api.createCampaign(scope, {
        name: this.newCampaignName().trim(),
        channel: this.newCampaignChannel(),
        // The template a campaign sends is the ADR 0015 authority on which
        // purpose gates it — never a value typed separately from the template,
        // which is how a campaign and its own copy end up gating on two
        // different purposes.
        consentPurpose: template?.consentPurpose ?? 'MARKETING_PROMOTIONS',
        audienceId: this.newCampaignAudienceId(),
        templateKey: this.newCampaignTemplateKey().trim(),
        recipientCap: this.newCampaignRecipientCap(),
        costCeilingMinor: this.channelCarriesMarginalCost()
          ? this.newCampaignCostCeilingMinor()
          : null,
        currency: this.newCampaignCurrency().trim() || 'UZS',
      });
      this.showCreateCampaign.set(false);
      await this.router.navigate(['/marketing/campaigns', created.campaignId]);
    } catch (error) {
      this.createCampaignError.set(this.describe(error));
    } finally {
      this.createCampaignSubmitting.set(false);
    }
  }

  // ----------------------------------------------------------- create audience

  protected openCreateAudience(): void {
    this.newAudienceName.set('');
    this.newAudienceDescription.set('');
    this.newAudiencePredicates.set([newPredicateDraft()]);
    this.createAudienceError.set(null);
    this.showCreateAudience.set(true);
  }

  protected closeCreateAudience(): void {
    this.showCreateAudience.set(false);
  }

  protected addPredicateRow(): void {
    this.newAudiencePredicates.update((rows) => [...rows, newPredicateDraft()]);
  }

  protected removePredicateRow(index: number): void {
    this.newAudiencePredicates.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected updatePredicateRow(index: number, patch: Partial<PredicateDraft>): void {
    this.newAudiencePredicates.update((rows) =>
      rows.map((row, i) => (i === index ? { ...row, ...patch } : row)),
    );
  }

  /** Changing the type resets the operator to the first one the new type actually accepts. */
  protected onPredicateTypeChange(index: number, type: string): void {
    const operators = operatorsFor(descriptorFor(type).valueKind);
    this.updatePredicateRow(index, { type, operator: operators[0] });
  }

  protected operatorsForRow(row: PredicateDraft): readonly string[] {
    return operatorsFor(descriptorFor(row.type).valueKind);
  }

  protected operatorLabelKey(operator: string): MessageKey {
    return `marketing.predicate.operator.${operator}` as MessageKey;
  }

  protected valueKindOfRow(row: PredicateDraft): PredicateValueKind {
    return descriptorFor(row.type).valueKind;
  }

  protected fixedValuesOfRow(row: PredicateDraft): readonly string[] | null {
    return descriptorFor(row.type).fixedValues;
  }

  protected canCreateAudience(): boolean {
    return (
      !this.createAudienceSubmitting() &&
      this.newAudienceName().trim().length > 0 &&
      this.newAudiencePredicates().length > 0
    );
  }

  protected async submitCreateAudience(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canCreateAudience()) {
      return;
    }
    this.createAudienceSubmitting.set(true);
    this.createAudienceError.set(null);
    try {
      await this.api.defineAudience(scope, {
        name: this.newAudienceName().trim(),
        description: this.newAudienceDescription().trim() || null,
        predicates: this.newAudiencePredicates().map((row) => toWirePredicate(row)),
      });
      this.showCreateAudience.set(false);
      // Re-read rather than assemble the new row locally: listByBrand orders by
      // created_at, and the server's timestamps are the honest ones to show.
      this.audiences.set(await this.api.listAudiences(scope));
    } catch (error) {
      this.createAudienceError.set(this.describe(error));
    } finally {
      this.createAudienceSubmitting.set(false);
    }
  }

  // ------------------------------------------------------------- suppressions

  private async loadSuppressions(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.suppressionsLoading.set(true);
    this.suppressionsError.set(null);
    try {
      this.suppressions.set(await this.api.listSuppressions(scope, this.suppressionsActiveOnly()));
      this.suppressionsLoaded.set(true);
    } catch (error) {
      this.suppressionsError.set(this.describe(error));
    } finally {
      this.suppressionsLoading.set(false);
    }
  }

  protected async toggleSuppressionsActiveOnly(): Promise<void> {
    this.suppressionsActiveOnly.update((v) => !v);
    this.suppressionsLoaded.set(false);
    await this.loadSuppressions();
  }

  protected openLift(suppression: SuppressionView): void {
    this.liftingId.set(suppression.suppressionId);
    this.liftReason.set('');
    this.liftError.set(null);
  }

  protected closeLift(): void {
    this.liftingId.set(null);
  }

  protected async submitLift(): Promise<void> {
    const scope = this.brand.scope();
    const suppressionId = this.liftingId();
    if (!scope || !suppressionId || this.liftReason().trim().length === 0) {
      return;
    }
    this.liftSubmitting.set(true);
    this.liftError.set(null);
    try {
      await this.api.liftSuppression(scope, suppressionId, this.liftReason().trim());
      this.liftingId.set(null);
      this.suppressionsLoaded.set(false);
      await this.loadSuppressions();
    } catch (error) {
      this.liftError.set(this.describe(error));
    } finally {
      this.liftSubmitting.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/** Turns one form row into ADR 0044's closed predicate wire shape. */
function toWirePredicate(row: PredicateDraft): {
  type: string;
  operator: string;
  numericLow?: number | null;
  numericHigh?: number | null;
  dateLow?: string | null;
  dateHigh?: string | null;
  textValues?: readonly string[] | null;
  audienceId?: string | null;
} {
  const kind = descriptorFor(row.type).valueKind;
  switch (kind) {
    case 'NUMERIC':
      return {
        type: row.type,
        operator: row.operator,
        numericLow: row.numericLow,
        numericHigh: row.operator === 'BETWEEN' ? row.numericHigh : null,
      };
    case 'DATE_RANGE':
      return { type: row.type, operator: 'BETWEEN', dateLow: row.dateLow, dateHigh: row.dateHigh };
    case 'TEXT_SET':
      return {
        type: row.type,
        operator: row.operator,
        textValues: row.textValuesCsv
          .split(',')
          .map((v) => v.trim())
          .filter((v) => v.length > 0),
      };
    case 'AUDIENCE':
      return { type: row.type, operator: row.operator, audienceId: row.audienceId };
  }
}
