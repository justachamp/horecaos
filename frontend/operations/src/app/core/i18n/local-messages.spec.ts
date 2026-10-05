import { describe, expect, it } from 'vitest';

import { LOCALES } from './i18n';
import { localMessages } from './local-messages';

describe('LocalMessages', () => {
  const messages = localMessages({
    ru: { greeting: 'Здравствуйте, {name}', plain: 'Готово' },
    'uz-Latn': { greeting: 'Salom, {name}', plain: 'Tayyor' },
    en: { greeting: 'Hello, {name}', plain: 'Done' },
  });

  it('answers in the requested language', () => {
    expect(messages.text('ru', 'plain')).toBe('Готово');
    expect(messages.text('uz-Latn', 'plain')).toBe('Tayyor');
    expect(messages.text('en', 'plain')).toBe('Done');
  });

  it('fills {placeholders} the way the shared catalogues do, and leaves an unmatched one visible', () => {
    expect(messages.text('en', 'greeting', { name: 'Aziza' })).toBe('Hello, Aziza');
    expect(messages.text('en', 'greeting', {})).toBe('Hello, {name}');
  });

  it('covers every locale the console supports', () => {
    for (const locale of LOCALES) {
      expect(messages.text(locale, 'plain')).not.toBe('');
    }
  });
});
