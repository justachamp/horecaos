import { describe, expect, it } from 'vitest';

import { catalogueRow, groupByArea } from './job-catalogue';
import { CAPABILITY_SENTENCES, SentenceLocale } from './capability-sentences';
import { CapabilityEntry } from './staff-api';

const LOCALES: readonly SentenceLocale[] = ['ru', 'uz-Latn', 'en'];

/**
 * What `GET .../capabilities` answers for rows whose capability had no sentence until the grid was audited against
 * `TenantJobRules.catalogue()`: the server's `area` is the capability's own resource type, which is often not a word
 * a reader has (`voice-presence`, `marketing-contact-policy`) and never one in her language.
 */
function entry(code: string, area: string): CapabilityEntry {
  return { code, area, action: code.slice(area.length + 1), held: true, composable: true };
}

const ROWS_THE_GRID_SHOWED_AS_CODES: readonly CapabilityEntry[] = [
  entry('print.reprint', 'print'),
  entry('customer.pii.export', 'customer'),
  entry('order.place', 'order'),
  entry('terms.manage', 'terms'),
  entry('voice.presence.read', 'voice-presence'),
  entry('voice.call-log.read', 'voice-call-log'),
  entry('marketing.contact_policy.manage', 'marketing-contact-policy'),
  entry('marketing-link.manage', 'marketing-link'),
  entry('payment-method.read', 'payment-method'),
  entry('fiscal-terminal.read', 'fiscal-terminal'),
  entry('report.export', 'report'),
  entry('storefront-app.authorise', 'storefront-app'),
  entry('support.session.read', 'support'),
  entry('referral.read', 'referral'),
];

describe('catalogueRow over rows the platform sends', () => {
  it.each(LOCALES)(
    'prints a sentence and a named heading in %s, never a dotted code or a raw type',
    (locale) => {
      for (const row of ROWS_THE_GRID_SHOWED_AS_CODES) {
        const shown = catalogueRow(row, locale);
        expect(shown.sentence, `${row.code} sentence`).not.toBe(row.code);
        expect(shown.sentence, `${row.code} sentence`).not.toContain('.');
        expect(shown.area, `${row.code} heading`).not.toBe(row.area);
        expect(shown.area, `${row.code} heading`).not.toMatch(/^[a-z-]+$/);
      }
    },
  );

  it('files rows of one resource type with the same sentence-table heading rather than one heading per type', () => {
    const voice = ROWS_THE_GRID_SHOWED_AS_CODES.filter((row) => row.code.startsWith('voice.')).map(
      (row) => catalogueRow(row, 'ru').area,
    );
    expect(new Set(voice)).toEqual(new Set(['Телефония']));

    const groups = groupByArea(
      ROWS_THE_GRID_SHOWED_AS_CODES.map((row) => catalogueRow(row, 'en')),
      'en',
    );
    expect(groups.map((group) => group.area)).toContain('Phone calls');
    expect(groups.map((group) => group.area)).toContain('Printing');
  });

  it('falls back to the code and the server type only for a row with no sentence, which the platform build now refuses', () => {
    const unknown = catalogueRow(entry('nothing.here', 'nothing'), 'en');
    expect(unknown.sentence).toBe('nothing.here');
    expect(unknown.area).toBe('nothing');
    expect(CAPABILITY_SENTENCES['nothing.here']).toBeUndefined();
  });
});
