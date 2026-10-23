import { describe, expect, it } from 'vitest';

import { TemplateResponse } from './notifications-api';
import { missingLocales, severityOf, sortTemplates } from './template-list-order';

function template(overrides: Partial<TemplateResponse> = {}): TemplateResponse {
  return {
    id: 't-' + (overrides.templateKey ?? 'ORDER_CONFIRMED') + (overrides.channel ?? 'SMS'),
    brandId: 'b1',
    templateKey: 'ORDER_CONFIRMED',
    notificationClass: 'TRANSACTIONAL_REQUIRED',
    channel: 'SMS',
    consentPurpose: null,
    status: 'ACTIVE',
    activeVersion: 1,
    version: 2,
    fulfillmentMode: null,
    channelSource: null,
    origin: 'BRAND',
    shownVersion: 1,
    versionLocales: ['ru', 'uz-Latn', 'en'],
    requiredLocales: ['ru', 'uz-Latn', 'en'],
    providerReview: 'NOT_REQUIRED',
    ...overrides,
  };
}

describe('template list severity (settings.md 10.9, Tab 1)', () => {
  it('ranks a required template the gateway has withheld above everything', () => {
    expect(severityOf(template({ providerReview: 'REJECTED' }))).toBe(0);
    expect(severityOf(template({ providerReview: 'PENDING' }))).toBe(0);
  });

  it('ranks a withheld optional template and an active template missing a served language as 1', () => {
    expect(
      severityOf(
        template({ notificationClass: 'TRANSACTIONAL_OPTIONAL', providerReview: 'PENDING' }),
      ),
    ).toBe(1);
    expect(severityOf(template({ versionLocales: ['ru', 'uz-Latn'] }))).toBe(1);
  });

  it('ranks anything not in force as a draft, whatever its moderation state', () => {
    expect(
      severityOf(template({ status: 'DRAFT', activeVersion: null, providerReview: 'PENDING' })),
    ).toBe(2);
    expect(severityOf(template({ status: 'DRAFT', shownVersion: null, versionLocales: [] }))).toBe(
      2,
    );
  });

  it('ranks an active, complete, unwithheld template last', () => {
    expect(severityOf(template())).toBe(3);
  });

  it('does not count a missing language against a channel nothing is sent on', () => {
    // SMS, Telegram, email and push are all wired now (ADR 0182); the platform still declares a messaging-app
    // channel with no adapter, which is what this rule is about.
    expect(severityOf(template({ channel: 'MESSAGING_APP', versionLocales: ['ru'] }))).toBe(3);
  });

  it('names the languages the shown version lacks', () => {
    expect(missingLocales(template({ versionLocales: ['ru'] }))).toEqual(['uz-Latn', 'en']);
  });
});

describe('template list order', () => {
  it('puts severity first, then walks the order through its life', () => {
    const sorted = sortTemplates([
      template({ templateKey: 'ORDER_CANCELLED' }),
      template({ templateKey: 'ORDER_READY' }),
      template({ templateKey: 'ORDER_CONFIRMED' }),
      template({ templateKey: 'ORDER_PREPARING', status: 'DRAFT', activeVersion: null }),
      template({ templateKey: 'ORDER_REJECTED', providerReview: 'REJECTED' }),
      template({ templateKey: 'A_TENANT_KEY' }),
    ]).map((row) => row.templateKey);

    expect(sorted).toEqual([
      'ORDER_REJECTED', // withheld and required: 0
      'ORDER_PREPARING', // draft: 2
      'ORDER_CONFIRMED', // then in force, in the order's life
      'ORDER_READY',
      'ORDER_CANCELLED',
      'A_TENANT_KEY', // an unknown key follows the known ones
    ]);
  });

  it('puts a brand row ahead of the tenant row of the same key and channel', () => {
    const sorted = sortTemplates([
      template({ id: 'tenant', brandId: null, origin: 'TENANT' }),
      template({ id: 'brand' }),
    ]).map((row) => row.id);
    expect(sorted).toEqual(['brand', 'tenant']);
  });

  it('does not mutate its input', () => {
    const input = [
      template({ templateKey: 'ORDER_READY' }),
      template({ templateKey: 'ORDER_CONFIRMED' }),
    ];
    sortTemplates(input);
    expect(input.map((row) => row.templateKey)).toEqual(['ORDER_READY', 'ORDER_CONFIRMED']);
  });
});
