import { describe, expect, it } from 'vitest';

import { messagesEn } from '../../core/i18n/messages.en';
import { messagesRu } from '../../core/i18n/messages.ru';
import { messagesUzLatn } from '../../core/i18n/messages.uz-latn';
import { approvalActionLabelKey } from './approval-action-labels';

describe('approvalActionLabelKey', () => {
  it('names the fiscal correction request a second approver is asked to decide (ADR 0166)', () => {
    const key = approvalActionLabelKey('fiscal.correction.request');

    expect(key).toBe('staff.approvals.action.fiscalCorrectionRequest');
    // In words in all three languages, so the checker is not shown a dotted code to decide on.
    expect(messagesEn[key!]).toBe('Fiscal receipt correction or void');
    expect(messagesRu[key!]).not.toBe(messagesEn[key!]);
    expect(messagesUzLatn[key!]).not.toBe(messagesEn[key!]);
  });

  it('answers null for a code it does not name, so the page shows the code and not a blank', () => {
    expect(approvalActionLabelKey('something.new.entirely')).toBeNull();
  });
});
