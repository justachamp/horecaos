import { describe, expect, it } from 'vitest';

import { evaluateConditionGroups } from '../../../shared/ui/condition-types';
import {
  AUTOMATION_CONDITION_CATALOGUE,
  automationConditionGroups,
  simulatedAutomationRule,
} from './automation-conditions';
import {
  AUTOMATION_TRIGGER_CONFIG_KEY,
  AutomationRuleView,
  AutomationTriggerKind,
} from './automations-api';

function rule(overrides: Partial<AutomationRuleView> = {}): AutomationRuleView {
  return {
    id: 'rule-1',
    name: 'Win-back',
    triggerType: 'INACTIVITY',
    channel: 'MESSAGING_APP',
    consentPurpose: 'MARKETING_PROMOTIONS',
    templateKey: 'AUTOMATION_INACTIVITY',
    triggerConfig: { inactivityDays: 90 },
    cooldownDays: 30,
    priority: 0,
    active: false,
    activatedBy: null,
    activatedAt: null,
    version: 1,
    ...overrides,
  };
}

function ruleOf(kind: AutomationTriggerKind, threshold: number): AutomationRuleView {
  return rule({
    triggerType: kind,
    triggerConfig: { [AUTOMATION_TRIGGER_CONFIG_KEY[kind]]: threshold },
  });
}

/** Whether a candidate carrying `value` for the rule's own trigger would fire it. */
function fires(kind: AutomationTriggerKind, threshold: number, value: number): boolean {
  const groups = automationConditionGroups(ruleOf(kind, threshold));
  expect(groups).not.toBeNull();
  return evaluateConditionGroups(groups!, AUTOMATION_CONDITION_CATALOGUE, { [kind]: value });
}

describe('automation conditions', () => {
  it('has one condition type per trigger kind the page offers, and no other', () => {
    expect(AUTOMATION_CONDITION_CATALOGUE.map((descriptor) => descriptor.type).sort()).toEqual(
      Object.keys(AUTOMATION_TRIGGER_CONFIG_KEY).sort(),
    );
  });

  it('INACTIVITY fires at the threshold and beyond, never before (days_since_last_order >= inactivityDays)', () => {
    expect(fires('INACTIVITY', 90, 89)).toBe(false);
    expect(fires('INACTIVITY', 90, 90)).toBe(true);
    expect(fires('INACTIVITY', 90, 400)).toBe(true);
  });

  it('CART_ABANDONMENT fires once the cart has been left for the delay', () => {
    expect(fires('CART_ABANDONMENT', 2, 1)).toBe(false);
    expect(fires('CART_ABANDONMENT', 2, 2)).toBe(true);
  });

  it('CASHBACK_CHANGE fires for a change at least as large as the minimum', () => {
    expect(fires('CASHBACK_CHANGE', 1_000, 999)).toBe(false);
    expect(fires('CASHBACK_CHANGE', 1_000, 1_000)).toBe(true);
  });

  it('BIRTHDAY fires within the window and not outside it (the other way round from the rest)', () => {
    expect(fires('BIRTHDAY', 3, 3)).toBe(true);
    expect(fires('BIRTHDAY', 3, 0)).toBe(true);
    expect(fires('BIRTHDAY', 3, 4)).toBe(false);
    // A window of 0 is "on the day itself" -- a real, valid threshold, not "no threshold".
    expect(fires('BIRTHDAY', 0, 0)).toBe(true);
    expect(fires('BIRTHDAY', 0, 1)).toBe(false);
  });

  it('does not fire for a candidate the operator has not given a figure for', () => {
    const groups = automationConditionGroups(ruleOf('INACTIVITY', 90))!;
    expect(
      evaluateConditionGroups(groups, AUTOMATION_CONDITION_CATALOGUE, { INACTIVITY: null }),
    ).toBe(false);
    expect(evaluateConditionGroups(groups, AUTOMATION_CONDITION_CATALOGUE, {})).toBe(false);
  });

  it('LATE_ORDER_APOLOGY fires for an order that closed at least the configured minutes after it was promised', () => {
    expect(fires('LATE_ORDER_APOLOGY', 30, 29)).toBe(false);
    expect(fires('LATE_ORDER_APOLOGY', 30, 30)).toBe(true);
    expect(fires('LATE_ORDER_APOLOGY', 30, 95)).toBe(true);
  });

  it('cannot express a rule of a trigger kind this build does not know', () => {
    expect(automationConditionGroups(rule({ triggerType: 'POST_ORDER_REVIEW' }))).toBeNull();
    expect(simulatedAutomationRule(rule({ triggerType: 'POST_ORDER_REVIEW' }), 'x')).toBeNull();
  });

  it('cannot express a rule whose config lacks the number its trigger reads', () => {
    expect(automationConditionGroups(rule({ triggerConfig: {} }))).toBeNull();
    expect(
      automationConditionGroups(rule({ triggerConfig: { birthdayWindowDays: 5 } })),
    ).toBeNull();
  });

  it('simulates an inert rule as enabled: a preview asks "would it fire once armed"', () => {
    const simulated = simulatedAutomationRule(rule({ active: false }), 'Sends it');

    expect(simulated).toMatchObject({
      id: 'rule-1',
      label: 'Win-back',
      enabled: true,
      outcome: 'Sends it',
    });
  });
});
