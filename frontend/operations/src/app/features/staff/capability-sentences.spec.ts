import { describe, expect, it } from 'vitest';

import { CAPABILITY_AREAS, CAPABILITY_SENTENCES } from './capability-sentences';

describe('CAPABILITY_SENTENCES', () => {
  it('describes the four staff-record capabilities (ADR 0139) in plain words, the audited read saying it is recorded', () => {
    for (const code of [
      'staff.profile.read',
      'staff.profile.manage',
      'staff.emergency-contact.read',
      'staff.self.manage',
    ]) {
      expect(CAPABILITY_SENTENCES[code], code).toBeDefined();
      expect(CAPABILITY_SENTENCES[code].area).toBe('staff');
    }
    expect(CAPABILITY_SENTENCES['staff.emergency-contact.read'].en).toContain('recorded');
    expect(CAPABILITY_SENTENCES['staff.emergency-contact.read'].ru).toContain('записывается');
  });

  it('describes the five staff-hours capabilities (ADR 0156) in plain words, the export saying it is recorded', () => {
    for (const code of [
      'staff.shift.read',
      'staff.shift.manage',
      'staff.shift.approve',
      'staff.shift.export',
      'staff.shift.clock',
    ]) {
      expect(CAPABILITY_SENTENCES[code], code).toBeDefined();
      expect(CAPABILITY_SENTENCES[code].area).toBe('staff');
    }
    expect(CAPABILITY_SENTENCES['staff.shift.export'].en).toContain('recorded');
    expect(CAPABILITY_SENTENCES['staff.shift.manage'].en).toContain('recorded');
    expect(CAPABILITY_SENTENCES['staff.shift.approve'].ru).toContain('по одной');
  });

  it('describes the two chat-assistant capabilities (ADR 0069) as what a person can do, under their own heading', () => {
    expect(CAPABILITY_SENTENCES['assistant.read'].area).toBe('assistant');
    expect(CAPABILITY_SENTENCES['assistant.knowledge.manage'].area).toBe('assistant');
    expect(CAPABILITY_SENTENCES['assistant.read'].en).toContain('what an answer was based on');
    expect(CAPABILITY_SENTENCES['assistant.knowledge.manage'].ru).toContain('заметки');
    expect(CAPABILITY_AREAS['assistant']['uz-Latn']).toBe('Chat yordamchisi');
  });

  // The completeness check against the live catalogue is the platform's, not this spec's: a list pasted here
  // drifted from `TenantJobRules.catalogue()` (156 of 216 rows had a sentence while this stayed green), so
  // `CapabilitySentencesMirrorTests` reads this file against the real set and fails the build that adds a
  // capability without one. What only this side can see follows.

  it('describes the money, customer-data and print capabilities the grid used to show as dotted codes', () => {
    for (const code of [
      'customer.pii.export',
      'customer.erasure.execute',
      'commercial.wallet.topup',
      'commercial.card.manage',
      'print.reprint',
      'order.place',
      'payment.initiate',
      'report.export',
    ]) {
      expect(CAPABILITY_SENTENCES[code], code).toBeDefined();
    }
    expect(CAPABILITY_SENTENCES['customer.pii.export'].en).toContain('contact details');
    expect(CAPABILITY_SENTENCES['customer.erasure.execute'].ru).toContain('Безвозвратно');
  });

  it('fills in all three locales, non-blank, for every entry', () => {
    for (const [code, sentence] of Object.entries(CAPABILITY_SENTENCES)) {
      expect(sentence.ru.trim(), `${code}.ru`).not.toBe('');
      expect(sentence['uz-Latn'].trim(), `${code}.uz-Latn`).not.toBe('');
      expect(sentence.en.trim(), `${code}.en`).not.toBe('');
      expect(sentence.area.trim(), `${code}.area`).not.toBe('');
    }
  });

  it('never carries a dotted code as a sentence', () => {
    for (const [code, sentence] of Object.entries(CAPABILITY_SENTENCES)) {
      expect(sentence.ru, code).not.toBe(code);
      expect(sentence['uz-Latn'], code).not.toBe(code);
      expect(sentence.en, code).not.toBe(code);
    }
  });

  it('every entry names an area with all three locales filled in', () => {
    for (const [code, sentence] of Object.entries(CAPABILITY_SENTENCES)) {
      const area = CAPABILITY_AREAS[sentence.area];
      expect(area, `${code}'s area "${sentence.area}"`).toBeDefined();
      expect(area.ru.trim()).not.toBe('');
      expect(area['uz-Latn'].trim()).not.toBe('');
      expect(area.en.trim()).not.toBe('');
    }
  });
});
