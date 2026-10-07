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
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  RuleEnabledChange,
  RuleList,
  RuleListItem,
  RuleReorder,
} from '../../../shared/ui/rule-list';
import { RuleSimulator, SimulatedRule } from '../../../shared/ui/rule-simulator';
import { MarketingChannel } from '../../customers/segments/segments-api';
import { describeApiError } from '../../orders/order-errors';
import { WiringSentence, viewOf, wiringSentence } from '../channel-wiring';
import { ChannelView, MarketingApi } from '../marketing-api';
import { refusalLabelKey } from '../refusal-explainer';
import { AUTOMATION_CONDITION_CATALOGUE, simulatedAutomationRule } from './automation-conditions';
import {
  AUTOMATION_TRIGGER_CONFIG_KEY,
  AutomationPreviewCandidate,
  AutomationRuleRequest,
  AutomationRuleView,
  AutomationRunView,
  AutomationTriggerKind,
  AutomationsApi,
} from './automations-api';

/**
 * Marketing §6.5 Automations (gap-map row 6.5, ADR 0044 Triggers) —
 * `q-rule-list`'s first live consumer (row `X.25`).
 *
 * **"Nothing sends without a human" is two acts, shown as two acts.** A rule
 * is authored inert; {@link RuleList}'s own enabled toggle is wired to
 * {@link activate}/{@link deactivate}, `campaign.approve`-gated on the
 * server, not the `campaign.author` grant the create form itself needs — a
 * principal who can save a rule may still be refused arming it, and the
 * refusal shows up here rather than being hidden behind a toggle that always
 * looks like it worked.
 *
 * **Five trigger kinds: every one the server has.** `LATE_ORDER_APOLOGY` was
 * "deliberately absent" under ADR 0044 until ADR 0112 reconciled it with ADR
 * 0013's remedies, and is offered now on those terms: words and never a
 * benefit (the request has no field to state one), once per order, held back
 * thirty minutes so support gets first refusal, and cancelled when a remedy is
 * recorded. It has no cooldown to ask for, because once per order is its guard,
 * so the form does not ask for a number nothing reads. `CASHBACK_CHANGE` has no
 * cooldown-days default of its own reason the way BIRTHDAY's 365 does, so it
 * shares INACTIVITY's.
 *
 * **A channel with no delivery path is shown as not connected, with the reason.**
 * Arming a rule on one is refused by the server; saying so in the channel list
 * is kinder than saying so after the toggle. The wiring is read best-effort: a
 * read that fails leaves every channel selectable, as it always was.
 *
 * **Priority is q-rule-list's own drag/keyboard reorder**, persisted through
 * one whole-set `PUT .../automations/reorder` call — the same contract
 * `OrderOutcomeReasonController.reorder` already gives its sibling screen.
 *
 * **Preview has two halves, both row X.25's answer to "what would this rule do"**,
 * and neither arms the rule or sends anything. `q-rule-simulator` is the
 * client-side half: the operator types the figures of a hypothetical customer
 * (days since the last order, say) and it says whether this rule would fire and
 * what it would do. The rule's trigger becomes a typed condition through
 * `automation-conditions.ts`, which mirrors `AutomationTriggerType` — there is no
 * second candidate query and the component reads nothing. The server half,
 * `AutomationRulePreviewService`, answers the other question — which *real*
 * customers match today, name masked — and is unchanged.
 */
@Component({
  selector: 'q-automations-page',
  imports: [TPipe, RuleList, RuleSimulator],
  templateUrl: './automations-page.html',
  styleUrl: './automations-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AutomationsPage implements OnInit {
  private readonly api = inject(AutomationsApi);
  private readonly marketing = inject(MarketingApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly rules = signal<readonly AutomationRuleView[]>([]);

  protected readonly actionError = signal<string | null>(null);
  protected readonly actingRuleId = signal<string | null>(null);

  protected readonly triggerKinds: readonly AutomationTriggerKind[] = [
    'BIRTHDAY',
    'INACTIVITY',
    'CART_ABANDONMENT',
    'CASHBACK_CHANGE',
    'LATE_ORDER_APOLOGY',
  ];
  protected readonly channels: readonly MarketingChannel[] = [
    'MESSAGING_APP',
    'SMS',
    'EMAIL',
    'PUSH',
  ];

  /** What the server says each channel can carry for this brand; empty when it could not be read. */
  protected readonly channelViews = signal<readonly ChannelView[]>([]);

  // --------------------------------------------------------------- create form

  protected readonly showForm = signal(false);
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly formName = signal('');
  protected readonly formTriggerType = signal<AutomationTriggerKind>('BIRTHDAY');
  protected readonly formChannel = signal<MarketingChannel>('MESSAGING_APP');
  protected readonly formConsentPurpose = signal('MARKETING_PROMOTIONS');
  protected readonly formTemplateKey = signal('');
  protected readonly formConfigValue = signal(0);
  protected readonly formCooldownDays = signal(30);

  /** The apology is once per order, so the form asks for no cooldown and sends the smallest valid one. */
  protected readonly formIsApology = computed(
    () => this.formTriggerType() === 'LATE_ORDER_APOLOGY',
  );

  /** Each channel the form lists, whether it can be picked, and why not. */
  protected readonly channelOptions = computed(() =>
    this.channels.map((channel) => {
      const view = viewOf(this.channelViews(), channel);
      const wired = view?.isWired ?? true;
      return {
        channel,
        wired,
        sentence: wired ? null : wiringSentence(channel, view?.notWiredReason ?? null),
      };
    }),
  );

  /** Why the channel currently chosen cannot deliver, or null when it can (or nobody knows). */
  protected readonly formChannelUnwired = computed<WiringSentence | null>(
    () => this.channelOptions().find((o) => o.channel === this.formChannel())?.sentence ?? null,
  );

  // ------------------------------------------------------------- run history

  protected readonly runsForRule = signal<AutomationRuleView | null>(null);
  protected readonly runsLoading = signal(false);
  protected readonly runsError = signal<string | null>(null);
  protected readonly runs = signal<readonly AutomationRunView[]>([]);

  // ------------------------------------------------------------- preview (X.25)

  protected readonly previewForRule = signal<AutomationRuleView | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly previewError = signal<string | null>(null);
  protected readonly previewCandidates = signal<readonly AutomationPreviewCandidate[]>([]);

  /** `q-rule-simulator`'s catalogue: the four trigger kinds this build offers. */
  protected readonly conditionCatalogue = AUTOMATION_CONDITION_CATALOGUE;

  /**
   * The previewed rule as the simulator takes it: empty when nothing is being
   * previewed or the rule's trigger cannot be expressed as a condition, in which
   * case the dialog shows the server's sample alone.
   */
  protected readonly simulatedRules = computed<readonly SimulatedRule[]>(() => {
    const rule = this.previewForRule();
    if (!rule) {
      return [];
    }
    const simulated = simulatedAutomationRule(rule, this.ruleOutcome(rule));
    return simulated ? [simulated] : [];
  });

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
      this.rules.set(await this.api.list(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
    // Best-effort and after the list is on screen: the wiring only decorates the channel picker.
    try {
      this.channelViews.set(await this.marketing.listChannels(scope));
    } catch {
      this.channelViews.set([]);
    }
  }

  // ---------------------------------------------------------------- rendering

  /** `q-rule-list`'s own input shape — presentational, knows nothing about triggers. */
  protected readonly listItems = computed<readonly RuleListItem[]>(() =>
    this.rules().map((rule): RuleListItem => ({
      id: rule.id,
      label: rule.name,
      description: this.ruleDescription(rule),
      enabled: rule.active,
    })),
  );

  protected ruleDescription(rule: AutomationRuleView): string {
    const trigger = this.i18n.t(this.triggerLabelKey(rule.triggerType));
    const channel = this.i18n.t(`marketing.channel.${rule.channel}` as MessageKey);
    if (rule.triggerType === 'LATE_ORDER_APOLOGY') {
      return this.i18n.t('marketing.automations.rule.description.LATE_ORDER_APOLOGY', {
        trigger,
        channel,
        configValue: this.configValueOf(rule),
      });
    }
    return this.i18n.t('marketing.automations.rule.description', {
      trigger,
      channel,
      configValue: this.configValueOf(rule),
      cooldownDays: rule.cooldownDays,
    });
  }

  /** What the simulator shows a matching rule doing — already translated, as it requires. */
  private ruleOutcome(rule: AutomationRuleView): string {
    if (rule.triggerType === 'LATE_ORDER_APOLOGY') {
      return this.i18n.t('marketing.automations.preview.outcome.LATE_ORDER_APOLOGY', {
        template: rule.templateKey,
        channel: this.i18n.t(`marketing.channel.${rule.channel}` as MessageKey),
      });
    }
    return this.i18n.t('marketing.automations.preview.outcome', {
      template: rule.templateKey,
      channel: this.i18n.t(`marketing.channel.${rule.channel}` as MessageKey),
      cooldownDays: rule.cooldownDays,
    });
  }

  protected triggerLabelKey(triggerType: string): MessageKey {
    return `marketing.automations.trigger.${triggerType}` as MessageKey;
  }

  protected configLabelKey(triggerType: AutomationTriggerKind): MessageKey {
    return `marketing.automations.configLabel.${triggerType}` as MessageKey;
  }

  protected channelLabelKey(channel: string): MessageKey {
    return `marketing.channel.${channel}` as MessageKey;
  }

  private configValueOf(rule: AutomationRuleView): number {
    const key = AUTOMATION_TRIGGER_CONFIG_KEY[rule.triggerType as AutomationTriggerKind];
    return rule.triggerConfig[key] ?? 0;
  }

  // ------------------------------------------------------------------- reorder

  protected async onReorder(order: RuleReorder): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    // Optimistic: q-rule-list already reflects the new order in its own DOM,
    // so re-deriving `rules()` from the server's confirmation (rather than
    // trusting the client's own guess at the new priority numbers) is what
    // keeps this screen and the database from silently disagreeing.
    this.actionError.set(null);
    try {
      this.rules.set(await this.api.reorder(scope, order));
    } catch (error) {
      this.actionError.set(this.describe(error));
      this.rules.set(await this.api.list(scope));
    }
  }

  // ------------------------------------------------------------- arm / disarm

  protected async onEnabledChange(change: RuleEnabledChange): Promise<void> {
    const scope = this.brand.scope();
    const rule = this.rules().find((candidate) => candidate.id === change.id);
    if (!scope || !rule) {
      return;
    }
    this.actingRuleId.set(rule.id);
    this.actionError.set(null);
    try {
      const updated = change.enabled
        ? await this.api.activate(scope, rule.id, rule.version)
        : await this.api.deactivate(scope, rule.id, rule.version);
      this.rules.set(
        this.rules().map((candidate) => (candidate.id === updated.id ? updated : candidate)),
      );
    } catch (error) {
      this.actionError.set(this.describe(error));
      // The toggle's own optimistic DOM state must not survive a refusal —
      // reloading the list is what makes an unwired-channel refusal (or a
      // stale version) visible rather than leaving the switch looking armed.
      this.rules.set(await this.api.list(scope));
    } finally {
      this.actingRuleId.set(null);
    }
  }

  // ---------------------------------------------------------------- create form

  protected openForm(): void {
    this.formName.set('');
    this.formTriggerType.set('BIRTHDAY');
    // The first channel that can deliver, so a brand whose Telegram is not set up does not open on
    // a choice it cannot arm.
    this.formChannel.set(
      this.channelOptions().find((option) => option.wired)?.channel ?? 'MESSAGING_APP',
    );
    this.formConsentPurpose.set('MARKETING_PROMOTIONS');
    this.formTemplateKey.set('');
    this.formConfigValue.set(this.defaultConfigValue('BIRTHDAY'));
    this.formCooldownDays.set(365);
    this.formError.set(null);
    this.showForm.set(true);
  }

  protected closeForm(): void {
    this.showForm.set(false);
  }

  protected onTriggerTypeChange(triggerType: AutomationTriggerKind): void {
    this.formTriggerType.set(triggerType);
    this.formConfigValue.set(this.defaultConfigValue(triggerType));
    this.formCooldownDays.set(
      triggerType === 'BIRTHDAY' ? 365 : triggerType === 'LATE_ORDER_APOLOGY' ? 1 : 30,
    );
  }

  private defaultConfigValue(triggerType: AutomationTriggerKind): number {
    if (triggerType === 'BIRTHDAY') {
      return 0;
    }
    if (triggerType === 'INACTIVITY') {
      return 90;
    }
    if (triggerType === 'CART_ABANDONMENT') {
      return 2;
    }
    if (triggerType === 'LATE_ORDER_APOLOGY') {
      // Half an hour late: past the point a guest has stopped calling it "about on time", and
      // exactly the settle delay the sweep waits before it considers an order at all.
      return 30;
    }
    // CASHBACK_CHANGE's minimumChangeMinor: 1 000 so'm, small enough that a
    // typical accrual or redemption clears it, large enough that a rounding
    // entry does not.
    return 1_000;
  }

  protected canSubmit(): boolean {
    return (
      !this.submitting() &&
      this.formName().trim().length > 0 &&
      this.formConsentPurpose().trim().length > 0 &&
      this.formTemplateKey().trim().length > 0 &&
      this.formConfigValue() >= 0 &&
      this.formCooldownDays() > 0
    );
  }

  protected async submit(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.formError.set(null);
    try {
      const request: AutomationRuleRequest = {
        name: this.formName().trim(),
        triggerType: this.formTriggerType(),
        channel: this.formChannel(),
        consentPurpose: this.formConsentPurpose().trim(),
        templateKey: this.formTemplateKey().trim(),
        triggerConfig: {
          [AUTOMATION_TRIGGER_CONFIG_KEY[this.formTriggerType()]]: this.formConfigValue(),
        },
        cooldownDays: this.formIsApology() ? 1 : this.formCooldownDays(),
      };
      await this.api.create(scope, request);
      this.showForm.set(false);
      this.rules.set(await this.api.list(scope));
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.submitting.set(false);
    }
  }

  // ------------------------------------------------------------- run history

  protected async openRuns(rule: AutomationRuleView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.runsForRule.set(rule);
    this.runsError.set(null);
    this.runs.set([]);
    this.runsLoading.set(true);
    try {
      this.runs.set(await this.api.runs(scope, rule.id));
    } catch (error) {
      this.runsError.set(this.describe(error));
    } finally {
      this.runsLoading.set(false);
    }
  }

  protected closeRuns(): void {
    this.runsForRule.set(null);
  }

  protected runStatusLabelKey(status: string): MessageKey {
    return `marketing.automations.runStatus.${status}` as MessageKey;
  }

  /** A refusal reason in words; a code this build does not know is shown as written. */
  protected refusalLabel(reason: string): string {
    const key = refusalLabelKey(reason);
    return key ? this.i18n.t(key) : reason;
  }

  /**
   * The sentence behind a run: what the engine recorded for a refusal, or why a firing was
   * cancelled. Both are English, written for whoever reads the audit trail, and free of any
   * contact value.
   */
  protected runDetail(run: AutomationRunView): string | null {
    return run.refusalDetail ?? run.cancelledReason ?? null;
  }

  // ------------------------------------------------------------- preview (X.25)

  protected async openPreview(rule: AutomationRuleView): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.previewForRule.set(rule);
    this.previewError.set(null);
    this.previewCandidates.set([]);
    this.previewLoading.set(true);
    try {
      this.previewCandidates.set(await this.api.preview(scope, rule.id));
    } catch (error) {
      this.previewError.set(this.describe(error));
    } finally {
      this.previewLoading.set(false);
    }
  }

  protected closePreview(): void {
    this.previewForRule.set(null);
  }

  protected formatMoment(iso: string): string {
    return new Date(iso).toLocaleString(this.i18n.locale() === 'en' ? 'en-GB' : 'ru-RU');
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
