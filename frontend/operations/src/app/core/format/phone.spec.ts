import { afterEach, describe, expect, it } from 'vitest';

import { formatPhone } from './phone';
import { applyRegionalFormats, resetRegionalFormats } from './regional-format';

describe('formatPhone', () => {
  afterEach(() => resetRegionalFormats());

  it('shows a number exactly as it arrives when the brand chose no pattern', () => {
    expect(formatPhone('+998901234567')).toBe('+998901234567');
    expect(formatPhone('+998 90 ••• •• 42')).toBe('+998 90 ••• •• 42');
  });

  it('writes an E.164 number in the brand’s pattern', () => {
    applyRegionalFormats({ phoneDisplayPattern: '+### (##) ###-##-##' });

    expect(formatPhone('+998901234567')).toBe('+998 (90) 123-45-67');
  });

  it('re-writes a masked number too, because a mask bullet fills a slot like a digit', () => {
    applyRegionalFormats({ phoneDisplayPattern: '+###-##-###-##-##' });

    expect(formatPhone('+998 90 ••• •• 42')).toBe('+998-90-•••-••-42');
  });

  it('follows the pattern when it is passed explicitly, ahead of the brand’s', () => {
    applyRegionalFormats({ phoneDisplayPattern: '+### ## ### ## ##' });

    expect(formatPhone('+998901234567', '###-##-###-##-##')).toBe('998-90-123-45-67');
  });

  it('returns a number that does not fill the pattern untouched, never padded or cut', () => {
    applyRegionalFormats({ phoneDisplayPattern: '+### ## ### ## ##' });

    expect(formatPhone('901234567')).toBe('901234567');
    expect(formatPhone('+7 495 123 45 67 89')).toBe('+7 495 123 45 67 89');
  });

  it('is an empty string for no number at all, not the word undefined', () => {
    applyRegionalFormats({ phoneDisplayPattern: '+### ## ### ## ##' });

    expect(formatPhone(null)).toBe('');
    expect(formatPhone(undefined)).toBe('');
  });
});
