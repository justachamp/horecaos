import { formatDate, initialOf, languageTag } from './content-text';

describe('languageTag', () => {
  it.each(['uz', 'ru', 'en', 'uz-Latn', 'uz-Cyrl-UZ', 'es-419'])('lets %s through', (tag) => {
    expect(languageTag(tag)).toBe(tag);
  });

  it.each(['', ' ', 'u', 'uz_Latn', '"><script>', 'uz Latn', 'x'.repeat(40), '-uz'])(
    'refuses %j',
    (tag) => {
      expect(languageTag(tag)).toBeNull();
    },
  );

  it('refuses what is not text', () => {
    expect(languageTag(null)).toBeNull();
    expect(languageTag(undefined)).toBeNull();
  });
});

describe('initialOf', () => {
  it('is the first letter, capitalised', () => {
    expect(initialOf('  chef')).toBe('C');
    expect(initialOf('Шеф')).toBe('Ш');
  });

  it('keeps a whole character when the first one is outside the basic plane', () => {
    expect(initialOf('🍕 Pizza')).toBe('🍕');
  });

  it('is empty for an empty title', () => {
    expect(initialOf('   ')).toBe('');
  });
});

describe('formatDate', () => {
  it('writes an instant as a date in the reader’s language', () => {
    expect(formatDate('2026-10-01T08:30:00Z', 'en')).toContain('2026');
    expect(formatDate('2026-10-01T08:30:00Z', 'en')).toContain('October');
    expect(formatDate('2026-10-01T08:30:00Z', 'ru')).toContain('октября');
  });

  it('writes Uzbek month names itself: the browser’s own data for Uzbek has none ("2026 M10 1")', () => {
    expect(formatDate('2026-10-01T08:30:00Z', 'uz')).toBe('1-oktabr, 2026');
    expect(formatDate('2026-01-15T12:00:00Z', 'uz')).toBe('15-yanvar, 2026');
    expect(formatDate('2026-12-31T12:00:00Z', 'uz')).toBe('31-dekabr, 2026');
  });

  it('does not depend on the runtime having Uzbek data at all', () => {
    // Some WebViews and desktop shells ship none, and answer "2026 M10 1".
    const real = Intl.DateTimeFormat;
    vi.spyOn(Intl, 'DateTimeFormat').mockImplementation(function (
      locale?: string | string[],
      options?: Intl.DateTimeFormatOptions,
    ) {
      return locale === 'uz'
        ? ({ format: () => '2026 M10 1' } as Intl.DateTimeFormat)
        : new real(locale, options);
    } as unknown as typeof Intl.DateTimeFormat);

    try {
      expect(formatDate('2026-10-01T08:30:00Z', 'uz')).toBe('1-oktabr, 2026');
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('is null for what is not a date', () => {
    expect(formatDate('yesterday', 'en')).toBeNull();
    expect(formatDate('', 'en')).toBeNull();
  });

  it('falls back to English for a language the runtime cannot format', () => {
    expect(formatDate('2026-10-01T08:30:00Z', 'not a language!')).toContain('2026');
  });
});
