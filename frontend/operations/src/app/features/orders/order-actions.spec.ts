import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { orderStatusLabel } from './order-status';
import {
  DecisionIdRegistry,
  OrderActionResponse,
  actionLabel,
  advanceReasonCode,
  decisionOutcomeLabel,
  splitInlineOverflow,
} from './order-actions';

const i18n = new I18n();
i18n.setLocale('en');
const t = i18n.t.bind(i18n);
const statusLabel = (status: string) => orderStatusLabel(status, t);

function action(overrides: Partial<OrderActionResponse>): OrderActionResponse {
  return { action: 'APPROVE', ...overrides };
}

describe('splitInlineOverflow', () => {
  it('renders nothing for a status with no actions — never a disabled control', () => {
    expect(splitInlineOverflow(undefined)).toEqual({ inline: [], overflow: [] });
    expect(splitInlineOverflow([])).toEqual({ inline: [], overflow: [] });
  });

  it('takes at most two actions inline, in the server’s own order (§2.9)', () => {
    const actions = [action({ action: 'APPROVE' }), action({ action: 'REJECT' }), action({ action: 'CANCEL' })];
    const split = splitInlineOverflow(actions);
    expect(split.inline).toEqual([actions[0], actions[1]]);
    expect(split.overflow).toEqual([actions[2]]);
  });

  it('leaves the overflow empty when there are two or fewer actions', () => {
    const actions = [action({ action: 'ADVANCE', targetStatus: 'PREPARING' })];
    expect(splitInlineOverflow(actions)).toEqual({ inline: actions, overflow: [] });
  });
});

describe('actionLabel', () => {
  it('labels the decision actions', () => {
    expect(actionLabel(action({ action: 'APPROVE' }), null, t, statusLabel)).toBe('Accept');
    expect(actionLabel(action({ action: 'REJECT' }), null, t, statusLabel)).toBe('Reject');
    expect(actionLabel(action({ action: 'CANCEL' }), null, t, statusLabel)).toBe('Cancel');
  });

  it('labels an advance by its target status, not the status’s own noun', () => {
    expect(actionLabel(action({ action: 'ADVANCE', targetStatus: 'PREPARING' }), null, t, statusLabel)).toBe(
      'Send to kitchen',
    );
    expect(actionLabel(action({ action: 'ADVANCE', targetStatus: 'READY' }), null, t, statusLabel)).toBe('Ready');
    expect(
      actionLabel(action({ action: 'ADVANCE', targetStatus: 'FULFILLING' }), 'DELIVERY', t, statusLabel),
    ).toBe('Send out for delivery');
  });

  it('splits COMPLETED by fulfilment mode', () => {
    expect(
      actionLabel(action({ action: 'ADVANCE', targetStatus: 'COMPLETED' }), 'DELIVERY', t, statusLabel),
    ).toBe('Delivered');
    expect(
      actionLabel(action({ action: 'ADVANCE', targetStatus: 'COMPLETED' }), 'PICKUP', t, statusLabel),
    ).toBe('Handed over');
    expect(
      actionLabel(action({ action: 'ADVANCE', targetStatus: 'COMPLETED' }), null, t, statusLabel),
    ).toBe('Handed over');
  });

  it('falls back to a generic label for an advance target this client has no dedicated copy for', () => {
    expect(
      actionLabel(action({ action: 'ADVANCE', targetStatus: 'CONFIRMED' }), null, t, statusLabel),
    ).toBe('→ Confirmed');
  });

  it('renders an unrecognised action code as its own raw value rather than throwing', () => {
    expect(actionLabel(action({ action: 'RESCHEDULE' }), null, t, statusLabel)).toBe('RESCHEDULE');
  });
});

describe('decisionOutcomeLabel', () => {
  it('renders the past-tense outcome, not the imperative button word', () => {
    expect(decisionOutcomeLabel('APPROVE', t)).toBe('accepted');
    expect(decisionOutcomeLabel('REJECT', t)).toBe('rejected');
  });

  it('falls back to the raw action code for one this client does not know', () => {
    expect(decisionOutcomeLabel('SOMETHING_NEW', t)).toBe('SOMETHING_NEW');
  });
});

describe('advanceReasonCode', () => {
  it('names the target status, so the audit trail says what actually happened', () => {
    expect(advanceReasonCode('PREPARING')).toBe('OPERATIONS_ADVANCE_PREPARING');
  });
});

describe('DecisionIdRegistry', () => {
  it('mints one id and reuses it on a repeat click for the same order', () => {
    const registry = new DecisionIdRegistry();
    const first = registry.idFor('order-1');
    const second = registry.idFor('order-1');
    expect(second).toBe(first);
  });

  it('mints a different id for a different order', () => {
    const registry = new DecisionIdRegistry();
    expect(registry.idFor('order-1')).not.toBe(registry.idFor('order-2'));
  });

  it('mints a fresh id once the previous decision has settled', () => {
    const registry = new DecisionIdRegistry();
    const first = registry.idFor('order-1');
    registry.settle('order-1');
    const second = registry.idFor('order-1');
    expect(second).not.toBe(first);
  });
});
