import { describe, expect, it } from 'vitest';

import { messagesEn } from '../../core/i18n/messages.en';
import { messagesRu } from '../../core/i18n/messages.ru';
import { messagesUzLatn } from '../../core/i18n/messages.uz-latn';
import { LABELLED_APPROVAL_ACTIONS, approvalActionLabelKey } from './approval-action-labels';

describe('approvalActionLabelKey', () => {
  it('names the fiscal correction request a second approver is asked to decide (ADR 0166)', () => {
    const key = approvalActionLabelKey('fiscal.correction.request');

    expect(key).toBe('staff.approvals.action.fiscalCorrectionRequest');
    // In words in all three languages, so the checker is not shown a dotted code to decide on.
    expect(messagesEn[key!]).toBe('Fiscal receipt correction or void');
    expect(messagesRu[key!]).not.toBe(messagesEn[key!]);
    expect(messagesUzLatn[key!]).not.toBe(messagesEn[key!]);
  });

  it('names the attachment of the agent’s reference, which a second approver also decides (ADR 0166)', () => {
    const key = approvalActionLabelKey('fiscal.correction.attach');

    expect(key).toBe('staff.approvals.action.fiscalCorrectionAttach');
    expect(messagesEn[key!]).toBe('Attaching a fiscal agent’s reference to a receipt');
    expect(messagesRu[key!]).not.toBe(messagesEn[key!]);
    expect(messagesUzLatn[key!]).not.toBe(messagesEn[key!]);
  });

  it('words the four producers that used to reach a checker as dotted codes (row 9.4, ADR 0181)', () => {
    for (const code of [
      'ordering.amendment.decrease',
      'courier.run-pay.change',
      'reporting.business-day-boundary.change',
      'iam.platform-grant.manage',
    ]) {
      const key = approvalActionLabelKey(code);
      expect(key, code).not.toBeNull();
      expect(messagesEn[key!], code).not.toContain('.');
      expect(messagesRu[key!], code).not.toBe(messagesEn[key!]);
      expect(messagesUzLatn[key!], code).not.toBe(messagesEn[key!]);
    }
  });

  it('labels every code in its list in all three languages, and no label is a code', () => {
    expect(LABELLED_APPROVAL_ACTIONS.length).toBeGreaterThanOrEqual(16);
    for (const code of LABELLED_APPROVAL_ACTIONS) {
      const key = approvalActionLabelKey(code)!;
      for (const catalogue of [messagesEn, messagesRu, messagesUzLatn]) {
        const text = catalogue[key];
        expect(text, `${code} / ${key}`).toBeTruthy();
        expect(text, `${code} / ${key}`).not.toBe(code);
      }
    }
  });

  it('answers null for a code it does not name, so the page shows the code and not a blank', () => {
    expect(approvalActionLabelKey('something.new.entirely')).toBeNull();
  });
});
