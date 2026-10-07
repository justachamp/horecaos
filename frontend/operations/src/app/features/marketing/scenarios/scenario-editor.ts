import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { viewOf, wiringSentence } from '../channel-wiring';
import { ContactPolicyApi } from '../contact-policy/contact-policy-api';
import {
  AudienceSummary,
  ChannelView,
  MarketingApi,
  MarketingTemplateView,
} from '../marketing-api';
import { OfferPicker } from '../offers/offer-picker';
import { OfferView, OffersApi } from '../offers/offers-api';
import {
  SCENARIO_CHANNELS,
  SCENARIO_CHANNEL_KEYS,
  SCENARIO_CONDITION_KEYS,
  SCENARIO_MAX_STEPS,
  SCENARIO_MAX_WAIT_DAYS,
  SCENARIO_PROBLEM_KEYS,
  ScenarioProblem,
  StepDraft,
  WAIT_UNITS,
  WaitUnit,
  isMessagingChannel,
  newStepDraft,
  offerEligible,
  primaryStep,
  scenarioProblems,
  stepFromView,
  toStepRequest,
  unwiredSteps,
} from './scenario-draft';
import {
  ContinuationCondition,
  CreateScenarioRequest,
  ScenarioChannel,
  ScenariosApi,
  StopCondition,
} from './scenarios-api';

/** Used when the step's template names no consent purpose, the same fallback the broadcast form takes. */
const DEFAULT_CONSENT_PURPOSE = 'MARKETING_PROMOTIONS';

/**
 * The scenario editor (ADR 0112): an ordered list of steps, each a channel, an optional offer, a
 * template, a wait and two conditions from closed sets.
 *
 * **It authors a plan, and nothing here sends.** Saving writes a DRAFT campaign of kind SCENARIO;
 * it is then estimated, submitted and approved by somebody who is not its author, exactly as a
 * broadcast is, and only launching starts the per-guest engine. Once it leaves DRAFT its steps are
 * fixed: a change is a new version that needs its own approval (`POST .../revisions`), which is
 * the cost ADR 0112 names for "a published version is immutable".
 *
 * **A step cannot state a discount.** There is no field for an amount, a percentage or a number of
 * points, because marketing never authors a benefit: a step names an offer, and an offer names a
 * promotion or an accrual rule that already exists (see {@link OfferPicker}).
 *
 * **Channels are honest.** SMS, email and push are listed beside Telegram and the in-app banner,
 * each marked when this brand cannot deliver on it, with the reason in words. SMS in particular
 * says its gate: marketing SMS is refused until the platform owner answers in writing which
 * account may carry it. Authoring such a step is allowed, because a draft is a plan and the
 * channel may be connected by the time it is approved, but the editor says, beside the step and
 * again before saving, that launching it will be refused, so a second signature is never the
 * moment someone finds out. Call-centre steps are not buildable (their lead queue does not exist)
 * and are listed disabled, with that reason.
 *
 * **Waits are capped at ninety days** (ADR 0112's open input on how long a wait may hold a guest,
 * taken on the default the record proposes), longer than every contact-policy window, so a wait
 * cannot starve a step of the slot its cap would admit.
 *
 * **The control group is optional** (ADR 0112's first open input, taken on its default): off, the
 * scenario runs against its whole audience with no baseline and its results state no lift. On, a
 * fixed share of the snapshot is withheld from every step, decided once at the start and never
 * resampled. The percentage offered is the tenant's own default.
 */
@Component({
  selector: 'q-scenario-editor',
  imports: [TPipe, OfferPicker],
  templateUrl: './scenario-editor.html',
  styleUrl: './scenario-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScenarioEditor implements OnInit {
  /** The draft whose steps are being edited, or null for a new scenario. */
  readonly campaignId = input<string | null>(null);
  readonly audiences = input<readonly AudienceSummary[]>([]);
  readonly channels = input<readonly ChannelView[]>([]);
  readonly templates = input<readonly MarketingTemplateView[]>([]);

  /** The campaign id, once a draft was saved. */
  readonly saved = output<string>();
  readonly cancelled = output<void>();

  private readonly scenariosApi = inject(ScenariosApi);
  private readonly offersApi = inject(OffersApi);
  private readonly policyApi = inject(ContactPolicyApi);
  private readonly marketing = inject(MarketingApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly maxSteps = SCENARIO_MAX_STEPS;
  protected readonly maxWaitDays = SCENARIO_MAX_WAIT_DAYS;
  protected readonly waitUnits = WAIT_UNITS;
  protected readonly stepChannels = SCENARIO_CHANNELS;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** The scenario being edited is past DRAFT, so its steps are fixed. */
  protected readonly notDraft = signal(false);
  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  protected readonly offers = signal<readonly OfferView[]>([]);
  private readonly extraChannels = signal<readonly ChannelView[]>([]);
  private readonly extraTemplates = signal<readonly MarketingTemplateView[]>([]);

  // The scenario as a whole (a new one only; an edit changes steps and nothing else).
  protected readonly name = signal('');
  protected readonly audienceId = signal('');
  protected readonly recipientCap = signal(1000);
  protected readonly costCeilingMinor = signal<number | null>(null);
  protected readonly currency = signal('UZS');
  protected readonly scheduledAt = signal('');
  protected readonly controlGroupOn = signal(false);
  protected readonly controlGroupPercent = signal(10);
  private controlGroupDefault = 10;

  protected readonly steps = signal<readonly StepDraft[]>([newStepDraft(1)]);
  private nextUid = 2;

  protected readonly editingName = signal('');

  protected readonly isEdit = computed(() => this.campaignId() !== null);

  /** What the server would refuse in the steps, read now so the author sees it beside the field. */
  protected readonly problems = computed<readonly ScenarioProblem[]>(() =>
    scenarioProblems(this.steps(), this.offers(), Date.now()),
  );

  protected problemsOf(step: number): readonly ScenarioProblem[] {
    return this.problems().filter((problem) => problem.step === step);
  }

  protected readonly wholeProblems = computed(() =>
    this.problems().filter((problem) => problem.step === undefined),
  );

  /** The channel views in force: the page's, or the editor's own read when the page passed none. */
  protected readonly channelViews = computed(() =>
    this.channels().length > 0 ? this.channels() : this.extraChannels(),
  );

  protected readonly templateViews = computed(() =>
    this.templates().length > 0 ? this.templates() : this.extraTemplates(),
  );

  /** Steps this brand cannot deliver on today: authoring them is allowed, launching them is not. */
  protected readonly unwired = computed(() => unwiredSteps(this.steps(), this.channelViews()));

  protected readonly primary = computed(() => primaryStep(this.steps(), this.offers()));

  /** A scenario that sends on a per-segment channel needs a ceiling, as a broadcast does. */
  protected readonly needsCostCeiling = computed(() => {
    const primary = this.primary();
    return primary
      ? (viewOf(this.channelViews(), primary.step.channel)?.carriesMarginalCost ?? false)
      : false;
  });

  /** The consent purpose of the template the first messaging step sends, as the broadcast form derives it. */
  protected readonly consentPurpose = computed(() => {
    const primary = this.primary();
    const template = this.templateViews().find(
      (candidate) =>
        candidate.templateKey === primary?.templateKey &&
        candidate.channel === primary?.step.channel,
    );
    return template?.consentPurpose ?? DEFAULT_CONSENT_PURPOSE;
  });

  /** How many guests the control group would withhold at the recipient cap: what it costs a small tenant. */
  protected readonly withheldAtCap = computed(() =>
    this.controlGroupOn()
      ? Math.floor((this.recipientCap() * this.controlGroupPercent()) / 100)
      : 0,
  );

  protected readonly formProblem = computed<MessageKey | null>(() => {
    if (this.isEdit()) {
      return null;
    }
    if (this.name().trim() === '') {
      return 'marketing.scenario.editor.problem.name';
    }
    if (this.audienceId() === '') {
      return 'marketing.scenario.editor.problem.audience';
    }
    if (!Number.isInteger(this.recipientCap()) || this.recipientCap() < 1) {
      return 'marketing.scenario.editor.problem.cap';
    }
    if (this.needsCostCeiling()) {
      const ceiling = this.costCeilingMinor();
      if (ceiling === null || !Number.isFinite(ceiling) || ceiling < 0) {
        return 'marketing.scenario.editor.problem.ceiling';
      }
    }
    if (!/^[A-Za-z]{3}$/.test(this.currency().trim())) {
      return 'marketing.scenario.editor.problem.currency';
    }
    if (this.controlGroupOn()) {
      const percent = this.controlGroupPercent();
      if (!Number.isInteger(percent) || percent < 0 || percent > 100) {
        return 'marketing.scenario.editor.problem.controlGroup';
      }
    }
    if (this.scheduledAt() !== '' && new Date(this.scheduledAt()).getTime() <= Date.now()) {
      return 'marketing.scenario.editor.problem.scheduledAt';
    }
    return null;
  });

  protected readonly canSave = computed(
    () =>
      !this.submitting() &&
      !this.notDraft() &&
      this.problems().length === 0 &&
      this.formProblem() === null,
  );

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.loading.set(false);
      return;
    }
    const id = this.campaignId();
    const reads: Promise<unknown>[] = [
      this.offersApi
        .list(scope)
        .then((offers) => this.offers.set(offers))
        .catch((error) => this.loadError.set(this.describe(error))),
      this.policyApi
        .defaults(scope)
        .then((defaults) => {
          this.controlGroupDefault = defaults.controlGroupPercentDefault;
          this.controlGroupPercent.set(defaults.controlGroupPercentDefault);
        })
        .catch(() => undefined),
    ];
    // A page that already read these hands them in; an editor opened on its own reads them itself.
    if (this.channels().length === 0) {
      reads.push(
        this.marketing
          .listChannels(scope)
          .then((views) => this.extraChannels.set(views))
          .catch(() => undefined),
      );
    }
    if (this.templates().length === 0) {
      reads.push(
        this.marketing
          .listTemplates(scope)
          .then((views) => this.extraTemplates.set(views))
          .catch(() => undefined),
      );
    }
    if (id !== null) {
      reads.push(
        this.scenariosApi
          .get(scope, id)
          .then((view) => {
            this.editingName.set(view.campaign.name);
            if (view.campaign.status !== 'DRAFT') {
              this.notDraft.set(true);
            }
            this.steps.set(view.steps.map((step, index) => stepFromView(step, index + 1)));
            this.nextUid = view.steps.length + 2;
          })
          .catch((error) => this.loadError.set(this.describe(error))),
      );
    } else if (this.audiences().length > 0) {
      this.audienceId.set(this.audiences()[0].audienceId);
    }
    await Promise.all(reads);
    this.loading.set(false);
  }

  // ---------------------------------------------------------------- rendering

  protected channelLabelKey(channel: ScenarioChannel): MessageKey {
    return SCENARIO_CHANNEL_KEYS[channel];
  }

  protected conditionKey(kind: ContinuationCondition | StopCondition): MessageKey {
    return SCENARIO_CONDITION_KEYS[kind];
  }

  protected unitKey(unit: WaitUnit): MessageKey {
    return UNIT_KEYS[unit];
  }

  protected problemKey(problem: ScenarioProblem): MessageKey {
    return SCENARIO_PROBLEM_KEYS[problem.code];
  }

  /** The channel's option label: its name, and when the brand cannot deliver on it, that. */
  protected channelOptionDisabled(channel: ScenarioChannel): boolean {
    return channel === 'CALL_CENTRE';
  }

  protected isUnwired(channel: ScenarioChannel): boolean {
    return isMessagingChannel(channel) && viewOf(this.channelViews(), channel)?.isWired === false;
  }

  protected wiringOf(channel: ScenarioChannel) {
    const view = viewOf(this.channelViews(), channel);
    return wiringSentence(channel, view?.notWiredReason ?? null);
  }

  protected templatesFor(channel: ScenarioChannel): readonly MarketingTemplateView[] {
    return this.templateViews().filter(
      (template) => template.notificationClass === 'MARKETING' && template.channel === channel,
    );
  }

  /** The template the step will send when it names none: its offer's, if it has one. */
  protected offerTemplateOf(step: StepDraft): string | null {
    return this.offers().find((offer) => offer.offerId === step.offerId)?.templateKey ?? null;
  }

  // ------------------------------------------------------------------- steps

  protected addStep(): void {
    if (this.steps().length >= SCENARIO_MAX_STEPS) {
      return;
    }
    this.steps.update((steps) => [...steps, newStepDraft(this.nextUid++)]);
  }

  protected removeStep(index: number): void {
    this.steps.update((steps) => steps.filter((_, i) => i !== index));
  }

  protected moveStep(index: number, by: -1 | 1): void {
    const target = index + by;
    if (target < 0 || target >= this.steps().length) {
      return;
    }
    this.steps.update((steps) => {
      const next = [...steps];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  }

  protected patchStep(index: number, patch: Partial<StepDraft>): void {
    this.steps.update((steps) => steps.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  }

  /**
   * A new channel keeps the offer and the template only while they still fit it: an offer not
   * allowed in the new channel, or a template written for another channel, would be refused.
   */
  protected changeChannel(index: number, channel: ScenarioChannel): void {
    const step = this.steps()[index];
    const offer = this.offers().find((o) => o.offerId === step.offerId);
    const keepOffer = offer !== undefined && offerEligible(offer, channel, Date.now());
    const known = this.templatesFor(channel);
    const keepTemplate =
      step.templateKey === '' ||
      known.length === 0 ||
      known.some((template) => template.templateKey === step.templateKey);
    this.patchStep(index, {
      channel,
      offerId: keepOffer ? step.offerId : null,
      templateKey: keepTemplate ? step.templateKey : '',
    });
  }

  protected onWaitValue(index: number, raw: string): void {
    this.patchStep(index, { waitValue: raw === '' ? Number.NaN : Number(raw) });
  }

  // -------------------------------------------------------------------- save

  protected onCostCeiling(raw: string): void {
    this.costCeilingMinor.set(raw === '' ? null : Number(raw));
  }

  protected toggleControlGroup(on: boolean): void {
    this.controlGroupOn.set(on);
    if (on) {
      this.controlGroupPercent.set(this.controlGroupDefault);
    }
  }

  protected async save(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSave()) {
      return;
    }
    this.submitting.set(true);
    this.submitError.set(null);
    try {
      const steps = this.steps().map(toStepRequest);
      const id = this.campaignId();
      if (id !== null) {
        await this.scenariosApi.replaceSteps(scope, id, steps);
        this.saved.emit(id);
        return;
      }
      const request: CreateScenarioRequest = {
        name: this.name().trim(),
        audienceId: this.audienceId(),
        consentPurpose: this.consentPurpose(),
        recipientCap: this.recipientCap(),
        costCeilingMinor: this.needsCostCeiling() ? this.costCeilingMinor() : null,
        currency: this.currency().trim().toUpperCase(),
        controlGroupPercent: this.controlGroupOn() ? this.controlGroupPercent() : null,
        scheduledAt: this.scheduledAt() === '' ? null : new Date(this.scheduledAt()).toISOString(),
        steps,
      };
      const created = await this.scenariosApi.create(scope, request);
      this.saved.emit(created.campaign.campaignId);
    } catch (error) {
      this.submitError.set(this.describe(error));
    } finally {
      this.submitting.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

const UNIT_KEYS: Readonly<Record<WaitUnit, MessageKey>> = {
  MINUTES: 'marketing.scenario.unit.MINUTES',
  HOURS: 'marketing.scenario.unit.HOURS',
  DAYS: 'marketing.scenario.unit.DAYS',
};
