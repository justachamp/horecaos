import { describe, expect, it } from 'vitest';

import { ChannelView } from './marketing-api';
import {
  courierCodeIn,
  courierWiringSentence,
  handedKey,
  isSelectable,
  readKey,
  viewOf,
  wiringCodeIn,
  wiringSentence,
} from './channel-wiring';

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

  it('says why push cannot send: the platform’s sending identity is not set up, or it only carries campaigns (ADR 0182)', () => {
    expect(wiringSentence('PUSH', 'PUSH_NOT_CONFIGURED').key).toBe(
      'marketing.wiring.PUSH_NOT_CONFIGURED',
    );
    expect(wiringSentence('PUSH', 'PUSH_PURPOSE_NOT_PERMITTED').key).toBe(
      'marketing.wiring.PUSH_PURPOSE_NOT_PERMITTED',
    );
  });

  it('gives a channel with no delivery at all the one generic sentence, push included', () => {
    expect(wiringSentence('PUSH', 'NO_DELIVERY_ADAPTER').key).toBe(
      'marketing.wiring.NO_DELIVERY_ADAPTER',
    );
    expect(wiringSentence('SMS', 'NO_DELIVERY_ADAPTER').key).toBe(
      'marketing.wiring.NO_DELIVERY_ADAPTER',
    );
  });

  it('says why email cannot send: the platform’s relay is not set up, or only carries campaigns (ADR 0182)', () => {
    expect(wiringSentence('EMAIL', 'EMAIL_NOT_CONFIGURED').key).toBe(
      'marketing.wiring.EMAIL_NOT_CONFIGURED',
    );
    expect(wiringSentence('EMAIL', 'EMAIL_PURPOSE_NOT_PERMITTED').key).toBe(
      'marketing.wiring.EMAIL_PURPOSE_NOT_PERMITTED',
    );
    expect(wiringSentence('EMAIL', 'ACCOUNT_MISCONFIGURED').key).toBe(
      'marketing.wiring.ACCOUNT_MISCONFIGURED',
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

describe('courierWiringSentence', () => {
  it('gives a courier SMS its own sentences, apart from marketing’s, for the two things only the owner can change', () => {
    expect(courierWiringSentence('SMS_PURPOSE_NOT_PERMITTED').key).toBe(
      'marketing.courierBroadcasts.wiring.SMS_PURPOSE_NOT_PERMITTED',
    );
    expect(courierWiringSentence('COURIER_CONTACTS_NOT_WIRED').key).toBe(
      'marketing.courierBroadcasts.wiring.COURIER_CONTACTS_NOT_WIRED',
    );
  });

  it('maps every code a courier send can be refused with', () => {
    for (const code of [
      'COURIER_CONTACTS_UNAVAILABLE',
      'NO_PROVIDER_BINDING',
      'INSTALLATION_INACTIVE',
      'INSTALLATION_MISSING',
      'SMS_ACCOUNT_MISCONFIGURED',
      'PROVIDER_ADAPTER_MISMATCH',
      'NO_ADAPTER',
      'NO_DELIVERY_ADAPTER',
      'COURIER_TEMPLATE_NOT_AVAILABLE',
      'TEMPLATE_AWAITING_PROVIDER',
      'NO_ACTIVE_COURIER',
      'NO_COURIER_HAS_A_NUMBER',
    ]) {
      expect(courierWiringSentence(code).key).toBe(`marketing.courierBroadcasts.wiring.${code}`);
    }
  });

  it('still says something, with the code in it, for a code a newer server added', () => {
    const sentence = courierWiringSentence('SOMETHING_NEW');
    expect(sentence.key).toBe('marketing.courierBroadcasts.wiring.UNKNOWN');
    expect(sentence.values).toEqual({ reason: 'SOMETHING_NEW' });
  });
});

describe('courierCodeIn', () => {
  it('finds the code a refused broadcast’s recorded sentence names', () => {
    expect(
      courierCodeIn(
        'No SMS delivery path can reach couriers for this brand (COURIER_CONTACTS_NOT_WIRED)',
      ),
    ).toBe('COURIER_CONTACTS_NOT_WIRED');
    expect(courierCodeIn('... (SMS_PURPOSE_NOT_PERMITTED)')).toBe('SMS_PURPOSE_NOT_PERMITTED');
  });

  it('says nothing for a sentence that names none, or for nothing at all', () => {
    expect(courierCodeIn('The broadcast was refused')).toBeNull();
    expect(courierCodeIn(null)).toBeNull();
    expect(courierCodeIn(undefined)).toBeNull();
  });
});

describe('handedKey', () => {
  it('calls an accepted message «sent» for Telegram, which has no operator in between, and «handed to the operator» elsewhere', () => {
    expect(handedKey('MESSAGING_APP')).toBe('marketing.delivery.HANDED_TO_OPERATOR.MESSAGING_APP');
    expect(handedKey('SMS')).toBe('marketing.delivery.HANDED_TO_OPERATOR');
    expect(handedKey('EMAIL')).toBe('marketing.delivery.HANDED_TO_OPERATOR');
    expect(handedKey(null)).toBe('marketing.delivery.HANDED_TO_OPERATOR');
    expect(handedKey('PUSH')).toBe('marketing.delivery.HANDED_TO_OPERATOR.PUSH');
  });
});

describe('readKey', () => {
  it('calls a tap a tap: Web Push reports a click, not that the message was read (ADR 0182)', () => {
    expect(readKey('PUSH')).toBe('marketing.delivery.READ.PUSH');
    expect(readKey('EMAIL')).toBe('marketing.delivery.READ');
    expect(readKey('SMS')).toBe('marketing.delivery.READ');
    expect(readKey(null)).toBe('marketing.delivery.READ');
  });
});
