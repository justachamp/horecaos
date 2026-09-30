import { MessageKey } from '../../../core/i18n/messages.en';
import {
  ConditionGroup,
  ConditionOperator,
  ConditionTypeDescriptor,
  emptyConditionRow,
} from '../../../shared/ui/condition-types';
import { SimulatedRule } from '../../../shared/ui/rule-simulator';
import {
  AUTOMATION_TRIGGER_CONFIG_KEY,
  AutomationRuleView,
  AutomationTriggerKind,
} from './automations-api';

/**
 * An automation rule's trigger, expressed as a typed condition so
 * `q-rule-simulator` can dry-run it (gap-map row `X.25`).
 *
 * **Why this is not "inventing a predicate catalogue nobody asked for".** An
 * automation rule has no author-built condition tree: it is one trigger kind and
 * one numeric threshold (`AutomationTriggerType.configKey()`), and the server
 * decides who matches by asking a fixed, per-kind question of a real customer.
 * That question *is* a single typed condition, and each of the four is closed on
 * the server side too — so the catalogue below mirrors
 * `AutomationTriggerType` one for one, exactly as `audience-predicates.ts`
 * mirrors `PredicateType`. Nothing here is a second implementation of the
 * server's candidate query (`AutomationRulePreviewService` stays the answer to
 * "who matches *today*", over real, masked customers); this answers the cheaper,
 * hypothetical question — "if a customer looked like *this*, would the rule
 * fire?" — against figures the operator types in, and reads nothing.
 *
 * The cooldown is deliberately not a second condition: it suppresses a repeat
 * message to the same customer, and a hypothetical candidate has no history for
 * it to act on. It is stated in the outcome line instead.
 */

/** One trigger kind's question, in the simulator's vocabulary. */
interface TriggerCondition {
  readonly labelKey: MessageKey;
  readonly valueKind: ConditionTypeDescriptor['valueKind'];
  /** Which side of the threshold fires the rule. */
  readonly operator: ConditionOperator;
}

const TRIGGER_CONDITIONS: Readonly<Record<AutomationTriggerKind, TriggerCondition>> = {
  // `JdbcCustomerMetricStore#birthdaysWithin`: a birthday within `birthdayWindowDays`
  // of today, either side — a distance from the birthday no larger than the window.
  BIRTHDAY: {
    labelKey: 'marketing.automations.condition.BIRTHDAY',
    valueKind: 'NUMERIC',
    operator: 'AT_MOST',
  },
  // `#inactiveSince`: `days_since_last_order` at least `inactivityDays`.
  INACTIVITY: {
    labelKey: 'marketing.automations.condition.INACTIVITY',
    valueKind: 'NUMERIC',
    operator: 'AT_LEAST',
  },
  // A cart left at least `abandonmentDelayHours` ago with no order since.
  CART_ABANDONMENT: {
    labelKey: 'marketing.automations.condition.CART_ABANDONMENT',
    valueKind: 'NUMERIC',
    operator: 'AT_LEAST',
  },
  // A balance change whose absolute size is at least `minimumChangeMinor`.
  CASHBACK_CHANGE: {
    labelKey: 'marketing.automations.condition.CASHBACK_CHANGE',
    valueKind: 'MONEY_MINOR',
    operator: 'AT_LEAST',
  },
};

/** `q-rule-simulator`'s catalogue: the four trigger kinds this build offers. */
export const AUTOMATION_CONDITION_CATALOGUE: readonly ConditionTypeDescriptor[] = (
  Object.keys(TRIGGER_CONDITIONS) as AutomationTriggerKind[]
).map((type) => ({
  type,
  labelKey: TRIGGER_CONDITIONS[type].labelKey,
  valueKind: TRIGGER_CONDITIONS[type].valueKind,
}));

function isTriggerKind(triggerType: string): triggerType is AutomationTriggerKind {
  return Object.prototype.hasOwnProperty.call(TRIGGER_CONDITIONS, triggerType);
}

/**
 * The rule's trigger as condition groups, or `null` for a rule this build cannot
 * express: a trigger kind it does not know (a newer server), or a `triggerConfig`
 * without the number its kind reads. A rule it cannot express is simply not
 * simulated — the dialog still shows the server's sample.
 */
export function automationConditionGroups(
  rule: AutomationRuleView,
): readonly ConditionGroup[] | null {
  if (!isTriggerKind(rule.triggerType)) {
    return null;
  }
  const threshold = rule.triggerConfig[AUTOMATION_TRIGGER_CONFIG_KEY[rule.triggerType]];
  if (typeof threshold !== 'number' || !Number.isFinite(threshold)) {
    return null;
  }
  const row = {
    ...emptyConditionRow(AUTOMATION_CONDITION_CATALOGUE, rule.triggerType),
    operator: TRIGGER_CONDITIONS[rule.triggerType].operator,
    numericLow: String(threshold),
  };
  return [{ id: `automation-${rule.id}`, combinator: 'AND', rows: [row] }];
}

/**
 * The rule as `q-rule-simulator` takes it, or `null` when it cannot be expressed
 * ({@link automationConditionGroups}).
 *
 * `enabled` is always true: a preview answers "would this fire once armed", and a
 * rule is authored inert (arming is a separate, `campaign.approve`-gated act), so
 * honouring `active` would leave every rule someone is still deciding whether to
 * arm marked "disabled — never evaluated".
 *
 * @param outcome already-translated: the simulator renders it and never invents it.
 */
export function simulatedAutomationRule(
  rule: AutomationRuleView,
  outcome: string,
): SimulatedRule | null {
  const groups = automationConditionGroups(rule);
  return groups === null ? null : { id: rule.id, label: rule.name, enabled: true, groups, outcome };
}
