import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { RuleEnabledChange, RuleList, RuleListItem, RuleReorder } from '../../../shared/ui/rule-list';
import { MarketingChannel } from '../../customers/segments/segments-api';
import { describeApiError } from '../../orders/order-errors';
import {
  AUTOMATION_TRIGGER_CONFIG_KEY,
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
 * **Three trigger kinds, not four.** `AutomationTriggerType`'s own doc names
 * why `CASHBACK_CHANGE` (no producer exists in `loyalty` yet) and
 * `LATE_ORDER_APOLOGY` (ADR 0044 states it "deliberately absent", pending
 * the still-`Proposed` ADR 0112) are not offered here — this form cannot
 * create a rule of a kind the server would refuse to keep firing.
 *
 * **Priority is q-rule-list's own drag/keyboard reorder**, persisted through
 * one whole-set `PUT .../automations/reorder` call — the same contract
 * `OrderOutcomeReasonController.reorder` already gives its sibling screen.
 */
@Component({
  selector: 'q-automations-page',
  imports: [TPipe, RuleList],
  templateUrl: './automations-page.html',
  styleUrl: './automations-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AutomationsPage implements OnInit {
  private readonly api = inject(AutomationsApi);
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
  ];
  protected readonly channels: readonly MarketingChannel[] = ['MESSAGING_APP', 'SMS', 'EMAIL', 'PUSH'];

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

  // ------------------------------------------------------------- run history

  protected readonly runsForRule = signal<AutomationRuleView | null>(null);
  protected readonly runsLoading = signal(false);
  protected readonly runsError = signal<string | null>(null);
  protected readonly runs = signal<readonly AutomationRunView[]>([]);

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
  }

  // ---------------------------------------------------------------- rendering

  /** `q-rule-list`'s own input shape — presentational, knows nothing about triggers. */
  protected readonly listItems = computed<readonly RuleListItem[]>(() =>
    this.rules().map(
      (rule): RuleListItem => ({
        id: rule.id,
        label: rule.name,
        description: this.ruleDescription(rule),
        enabled: rule.active,
      }),
    ),
  );

  protected ruleDescription(rule: AutomationRuleView): string {
    const trigger = this.i18n.t(this.triggerLabelKey(rule.triggerType));
    const channel = this.i18n.t(`marketing.channel.${rule.channel}` as MessageKey);
    return this.i18n.t('marketing.automations.rule.description', {
      trigger,
      channel,
      configValue: this.configValueOf(rule),
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
      this.rules.set(this.rules().map((candidate) => (candidate.id === updated.id ? updated : candidate)));
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
    this.formChannel.set('MESSAGING_APP');
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
    this.formCooldownDays.set(triggerType === 'BIRTHDAY' ? 365 : 30);
  }

  private defaultConfigValue(triggerType: AutomationTriggerKind): number {
    if (triggerType === 'BIRTHDAY') {
      return 0;
    }
    if (triggerType === 'INACTIVITY') {
      return 90;
    }
    return 2;
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
        cooldownDays: this.formCooldownDays(),
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

  protected formatMoment(iso: string): string {
    return new Date(iso).toLocaleString(this.i18n.locale() === 'en' ? 'en-GB' : 'ru-RU');
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
