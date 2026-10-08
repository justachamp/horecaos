import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { WiringSentence, viewOf, wiringSentence } from '../channel-wiring';
import { ChannelView, MarketingApi } from '../marketing-api';
import { formatMoment } from '../moment';
import { OfferView, OffersApi } from '../offers/offers-api';
import { REFUSAL_EFFECT_KEYS, RefusalExplanation, explainRefusal } from '../refusal-explainer';
import {
  SCENARIO_CHANNEL_KEYS,
  SCENARIO_CONDITION_KEYS,
  isMessagingChannel,
} from './scenario-draft';
import {
  AttributionModel,
  ContinuationCondition,
  ScenarioChannel,
  ScenarioDecisionView,
  ScenarioResultsView,
  ScenarioStepView,
  ScenarioView,
  ScenariosApi,
  StopCondition,
} from './scenarios-api';

/** A decision log row with what the console can say about it: the explanation behind its reason. */
interface DecisionRow {
  readonly decision: ScenarioDecisionView;
  readonly explanation: RefusalExplanation | null;
}

/** An account id as the log shows it: the first segment, the whole id in a tooltip. */
function shortAccount(id: string): string {
  return id.slice(0, 8);
}

/** Whether a string can be an account id, so a typo is caught before a request is spent on it. */
const ACCOUNT_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * What a scenario is, where its guests are, what it decided and why, and whether it worked
 * (ADR 0112): the part of a campaign's detail only a scenario has.
 *
 * **Why a guest did not get a step is a row, never a guess.** Every choice action selection made
 * is on the log with a reason, and this panel is where a marketer reads it: the reason in words,
 * whether it ended that guest's run or only holds the step, the sentence the engine recorded
 * (which rule, which numbers: a cap, a window), and what a person can do about it. Narrowing the
 * log to one guest is the answer to "why did *this* guest not get it".
 *
 * **Results are measured, and honest about what they cannot say.** The goal is the guest's next
 * order within the window, credited under first touch or last touch, against the withheld control
 * group. A scenario that ran without a control group has no baseline, so the panel states no lift
 * for it and says why, instead of a number that pretended otherwise. A guest whose window is
 * still open is counted as not having ordered yet, and the panel says how many such guests there
 * are, because the figures will move.
 *
 * **A published version is never edited.** A draft's steps are edited (the editor is the page's);
 * anything past DRAFT offers "a new version", which is a new draft that needs its own approval and
 * halts this one when it launches. The panel only asks for either: opening the editor and moving
 * to the new version are the detail pane's.
 */
@Component({
  selector: 'q-scenario-panel',
  imports: [TPipe],
  templateUrl: './scenario-panel.html',
  styleUrl: './scenario-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScenarioPanel {
  readonly campaignId = input.required<string>();
  /** The campaign's lifecycle status; a change re-reads the panel, because the lifecycle moves what it shows. */
  readonly status = input.required<string>();
  /** The brand's zone, for the moments on the log. */
  readonly timezone = input<string | null>(null);

  /** The author asked to edit this draft's steps. */
  readonly editRequested = output<void>();
  /** A new version was drafted; the campaign id of it. */
  readonly revised = output<string>();

  private readonly scenarios = inject(ScenariosApi);
  private readonly offersApi = inject(OffersApi);
  private readonly marketing = inject(MarketingApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly view = signal<ScenarioView | null>(null);
  protected readonly offers = signal<readonly OfferView[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);

  protected readonly decisions = signal<readonly ScenarioDecisionView[]>([]);
  protected readonly decisionsError = signal<string | null>(null);
  protected readonly guestFilter = signal('');
  protected readonly filteredGuest = signal<string | null>(null);

  protected readonly results = signal<ScenarioResultsView | null>(null);
  protected readonly resultsError = signal<string | null>(null);
  protected readonly resultsLoading = signal(false);
  protected readonly model = signal<AttributionModel>('FIRST_TOUCH');
  protected readonly windowDays = signal(14);

  protected readonly acting = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly effectKeys = REFUSAL_EFFECT_KEYS;

  protected readonly rows = computed<readonly DecisionRow[]>(() =>
    this.decisions().map((decision) => ({
      decision,
      explanation: explainRefusal(decision.refusalReason),
    })),
  );

  protected readonly participantEntries = computed(() =>
    Object.entries(this.view()?.participants ?? {}),
  );

  protected readonly decisionEntries = computed(() => Object.entries(this.view()?.decisions ?? {}));

  protected readonly hasGuests = computed(() => this.participantEntries().length > 0);

  protected readonly isDraft = computed(() => this.view()?.campaign.status === 'DRAFT');

  /** Steps this brand cannot deliver on today, by step number, for the badge beside the channel. */
  protected readonly unwiredBySequence = computed(() => {
    const out = new Map<number, WiringSentence>();
    for (const step of this.view()?.steps ?? []) {
      const view = viewOf(this.channels(), step.channel);
      if (isMessagingChannel(step.channel) && view !== undefined && !view.isWired) {
        out.set(step.sequence, wiringSentence(step.channel, view.notWiredReason));
      }
    }
    return out;
  });

  constructor() {
    // Re-read whenever the campaign or its lifecycle changes: approving, launching and halting all
    // move what this panel shows. The load itself reads signals, so it runs untracked.
    effect(() => {
      this.campaignId();
      this.status();
      untracked(() => void this.load());
    });
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
      const view = await this.scenarios.get(scope, this.campaignId());
      this.view.set(view);
    } catch (error) {
      this.loadError.set(this.describe(error));
      this.loading.set(false);
      return;
    }
    // Context for the steps; none of it is needed to read them.
    await Promise.all([
      this.offersApi
        .list(scope)
        .then((offers) => this.offers.set(offers))
        .catch(() => this.offers.set([])),
      this.marketing
        .listChannels(scope)
        .then((channels) => this.channels.set(channels))
        .catch(() => this.channels.set([])),
      this.loadDecisions(null),
    ]);
    this.loading.set(false);
    if (this.hasGuests()) {
      await this.loadResults();
    } else {
      this.results.set(null);
    }
  }

  // --------------------------------------------------------------- decisions

  private async loadDecisions(accountId: string | null): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.decisionsError.set(null);
    try {
      this.decisions.set(
        await this.scenarios.decisions(scope, this.campaignId(), {
          accountId: accountId ?? undefined,
          limit: 100,
        }),
      );
      this.filteredGuest.set(accountId);
    } catch (error) {
      this.decisionsError.set(this.describe(error));
    }
  }

  protected async lookUpGuest(): Promise<void> {
    const id = this.guestFilter().trim();
    if (!ACCOUNT_ID.test(id)) {
      this.decisionsError.set(this.i18n.t('marketing.scenario.decisions.guest.invalid'));
      return;
    }
    await this.loadDecisions(id);
  }

  protected async clearGuest(): Promise<void> {
    this.guestFilter.set('');
    await this.loadDecisions(null);
  }

  // ----------------------------------------------------------------- results

  protected async loadResults(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    const days = this.windowDays();
    if (!Number.isInteger(days) || days < 1 || days > 90) {
      this.resultsError.set(this.i18n.t('marketing.scenario.results.window.invalid'));
      return;
    }
    this.resultsLoading.set(true);
    this.resultsError.set(null);
    try {
      this.results.set(await this.scenarios.results(scope, this.campaignId(), this.model(), days));
    } catch (error) {
      this.resultsError.set(this.describe(error));
    } finally {
      this.resultsLoading.set(false);
    }
  }

  protected percent(rate: number | null): string {
    return rate === null ? '—' : `${(rate * 100).toFixed(1)}%`;
  }

  /** The lift in percentage points of the guests, signed. */
  protected points(lift: number | null): string {
    if (lift === null) {
      return '—';
    }
    const points = (lift * 100).toFixed(1);
    return lift > 0 ? `+${points}` : points;
  }

  // ------------------------------------------------------------------ actions

  protected async revise(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.acting()) {
      return;
    }
    this.acting.set(true);
    this.actionError.set(null);
    try {
      const created = await this.scenarios.revise(scope, this.campaignId());
      this.revised.emit(created.campaign.campaignId);
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.acting.set(false);
    }
  }

  // ----------------------------------------------------------------- rendering

  protected channelLabelKey(channel: ScenarioChannel): MessageKey {
    return SCENARIO_CHANNEL_KEYS[channel];
  }

  protected offerName(step: ScenarioStepView): string | null {
    if (step.offerId === null) {
      return null;
    }
    const offer = this.offers().find((candidate) => candidate.offerId === step.offerId);
    return offer ? `${offer.displayName} · v${offer.versionNumber}` : step.offerId;
  }

  /** A wait as an operator reads it: the largest unit with no fraction. */
  protected waitText(seconds: number): string {
    if (seconds === 0) {
      return this.i18n.t('marketing.scenario.wait.none');
    }
    if (seconds % 86_400 === 0) {
      return this.i18n.t('marketing.scenario.wait.days', { count: seconds / 86_400 });
    }
    if (seconds % 3_600 === 0) {
      return this.i18n.t('marketing.scenario.wait.hours', { count: seconds / 3_600 });
    }
    return this.i18n.t('marketing.scenario.wait.minutes', { count: Math.round(seconds / 60) });
  }

  protected participantLabel(state: string): string {
    const key = PARTICIPANT_KEYS[state];
    return key ? this.i18n.t(key) : state;
  }

  /** A decision count's name: SENT, or the refusal reason it was blocked for, in words. */
  protected decisionCountLabel(label: string): string {
    if (label === 'SENT') {
      return this.i18n.t('marketing.scenario.decision.SENT');
    }
    const explanation = explainRefusal(label);
    return explanation ? this.i18n.t(explanation.labelKey) : label;
  }

  protected channelOf(channel: string | null): string {
    if (channel === null) {
      return '—';
    }
    const key = SCENARIO_CHANNEL_KEYS[channel as ScenarioChannel];
    return key ? this.i18n.t(key) : channel;
  }

  protected moment(iso: string): string {
    return formatMoment(iso, this.timezone());
  }

  protected shortAccount(id: string): string {
    return shortAccount(id);
  }

  protected conditionKey(kind: ContinuationCondition | StopCondition): MessageKey {
    return SCENARIO_CONDITION_KEYS[kind];
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

const PARTICIPANT_KEYS: Readonly<Record<string, MessageKey>> = {
  IN_PROGRESS: 'marketing.scenario.participant.IN_PROGRESS',
  CONTROL: 'marketing.scenario.participant.CONTROL',
  COMPLETED: 'marketing.scenario.participant.COMPLETED',
  STOPPED_BY_CONDITION: 'marketing.scenario.participant.STOPPED_BY_CONDITION',
  STOPPED_BY_CONSENT_WITHDRAWN: 'marketing.scenario.participant.STOPPED_BY_CONSENT_WITHDRAWN',
  STOPPED_BY_SUPPRESSION: 'marketing.scenario.participant.STOPPED_BY_SUPPRESSION',
};
