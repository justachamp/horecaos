import { describe, expect, it } from 'vitest';

import { ChannelView } from './marketing-api';
import { isSelectable, viewOf, wiringCodeIn, wiringSentence } from './channel-wiring';

const SMS_GATED: ChannelView = {
  channel: 'SMS',
  carriesMarginalCost: true,
  isWired: false,
  notWiredReason: 'SMS_PURPOSE_NOT_PERMITTED',
};

describe('wiringSentence', () => {
  it('gives SMS its own sentence for a purpose its account is not cleared to carry', () => {
    expect(wiringSentence('SMS', 'SMS_PURPOSE_NOT_PERMITTED').key).toBe(
      'marketing.wiring.SMS_PURPOSE_NOT_PERMITTED',
    );
  });

  it('says email and push have no delivery in their own words, not a generic one', () => {
    expect(wiringSentence('EMAIL', 'NO_DELIVERY_ADAPTER').key).toBe(
      'marketing.wiring.NO_DELIVERY_ADAPTER.EMAIL',
    );
    expect(wiringSentence('PUSH', 'NO_DELIVERY_ADAPTER').key).toBe(
      'marketing.wiring.NO_DELIVERY_ADAPTER.PUSH',
    );
    expect(wiringSentence('SMS', 'NO_DELIVERY_ADAPTER').key).toBe(
      'marketing.wiring.NO_DELIVERY_ADAPTER',
    );
  });

  it('maps every other code the transport can answer with', () => {
    for (const code of [
      'NO_PROVIDER_BINDING',
      'INSTALLATION_INACTIVE',
      'INSTALLATION_MISSING',
      'SMS_ACCOUNT_MISCONFIGURED',
      'PROVIDER_ADAPTER_MISMATCH',
      'NO_ADAPTER',
    ]) {
      expect(wiringSentence('SMS', code).key).toBe(`marketing.wiring.${code}`);
    }
  });

  it('still says something, with the code in it, for a code a newer server added', () => {
    const sentence = wiringSentence('SMS', 'SOMETHING_NEW');
    expect(sentence.key).toBe('marketing.wiring.UNKNOWN');
    expect(sentence.values).toEqual({ reason: 'SOMETHING_NEW' });
    expect(wiringSentence('SMS', null).values).toEqual({ reason: '—' });
  });
});

describe('channel selection', () => {
  it('lets a channel be chosen only when the server says it is wired', () => {
    expect(isSelectable([SMS_GATED], 'SMS')).toBe(false);
    expect(isSelectable([{ ...SMS_GATED, isWired: true, notWiredReason: null }], 'SMS')).toBe(true);
  });

  it('does not make a working channel vanish because the read model has not listed it', () => {
    expect(isSelectable([], 'SMS')).toBe(true);
    expect(viewOf([], 'SMS')).toBeUndefined();
  });
});

describe('wiringCodeIn', () => {
  it('finds the reason a launch refusal names in its sentence', () => {
    expect(
      wiringCodeIn(
        'No ADR 0020 delivery path is wired for SMS for this brand (SMS_PURPOSE_NOT_PERMITTED); this campaign cannot be launched',
      ),
    ).toBe('SMS_PURPOSE_NOT_PERMITTED');
    expect(wiringCodeIn('... (NO_PROVIDER_BINDING) ...')).toBe('NO_PROVIDER_BINDING');
  });

  it('says nothing for a sentence that names none, or for nothing at all', () => {
    expect(wiringCodeIn('The campaign is not approved')).toBeNull();
    expect(wiringCodeIn(null)).toBeNull();
    expect(wiringCodeIn(undefined)).toBeNull();
    expect(wiringCodeIn('')).toBeNull();
  });
});
