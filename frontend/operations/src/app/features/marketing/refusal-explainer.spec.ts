import { describe, expect, it } from 'vitest';

import { messagesEn } from '../../core/i18n/messages.en';
import {
  REFUSAL_EFFECT_KEYS,
  REFUSAL_EXPLANATIONS,
  explainRefusal,
  refusalLabelKey,
} from './refusal-explainer';

/** `marketing.domain.RefusalReason`, every constant: the explainer must know each one. */
const SERVER_REASONS = [
  'ACCOUNT_NOT_ACTIVE',
  'CONSENT_WITHHELD',
  'SUPPRESSED',
  'FREQUENCY_CAP_REACHED',
  'NO_VERIFIED_ENDPOINT',
  'CAMPAIGN_HALTED',
  'SCENARIO_CONFLICT',
  'SCENARIO_PRIORITY_LOST',
  'SCENARIO_STOPPED',
];

describe('refusal explanations', () => {
  it('explains every reason the server can write, and nothing it cannot', () => {
    expect(REFUSAL_EXPLANATIONS.map((e) => e.reason).sort()).toEqual([...SERVER_REASONS].sort());
  });

  it('has a sentence in the catalogue for every key it points at', () => {
    const keys = new Set(Object.keys(messagesEn));
    const missing = [
      ...REFUSAL_EXPLANATIONS.flatMap((e) => [e.labelKey, e.meaningKey, e.remedyKey]),
      ...Object.values(REFUSAL_EFFECT_KEYS),
    ].filter((key) => !keys.has(key));
    expect(missing).toEqual([]);
  });

  it('ends the run for what only the guest can change, and holds the step for what time can', () => {
    for (const reason of ['CONSENT_WITHHELD', 'SUPPRESSED', 'ACCOUNT_NOT_ACTIVE']) {
      expect(explainRefusal(reason)?.effect).toBe('ENDS');
    }
    for (const reason of [
      'FREQUENCY_CAP_REACHED',
      'NO_VERIFIED_ENDPOINT',
      'SCENARIO_CONFLICT',
      'SCENARIO_PRIORITY_LOST',
    ]) {
      expect(explainRefusal(reason)?.effect).toBe('HOLDS');
    }
  });

  it('does not claim a scenario-stopped row always ends the run: one of its causes is a retry in an hour', () => {
    expect(explainRefusal('SCENARIO_STOPPED')?.effect).toBe('VARIES');
  });

  it('names the contact policy only where it can be what said no', () => {
    expect(
      REFUSAL_EXPLANATIONS.filter((e) => e.governedByContactPolicy).map((e) => e.reason),
    ).toEqual(['FREQUENCY_CAP_REACHED']);
  });

  it('labels an automation’s own reason, and returns null for a reason it does not know', () => {
    expect(refusalLabelKey('CHANNEL_NOT_WIRED')).toBe('marketing.refusal.CHANNEL_NOT_WIRED');
    expect(refusalLabelKey('SUPPRESSED')).toBe('marketing.refusal.SUPPRESSED');
    expect(refusalLabelKey('NEW_THING')).toBeNull();
    expect(explainRefusal('NEW_THING')).toBeNull();
    expect(explainRefusal(null)).toBeNull();
  });
});
