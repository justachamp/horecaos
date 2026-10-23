import {
  formatDate,
  formatDistance,
  formatDuration,
  formatMoney,
  formatTime,
  minorUnitsOf,
  parseMinorUnits,
} from './format';

describe('formatting for a courier', () => {
  it('shows a time in the branch’s zone, whatever zone the phone is set to', () => {
    expect(formatTime('2026-10-14T04:30:00Z', 'en', 'Asia/Tashkent')).toBe('09:30');
    expect(formatTime('not a time', 'en', 'Asia/Tashkent')).toBe('');
  });

  it('shows a calendar date in the courier’s language', () => {
    expect(formatDate('2027-01-31', 'en')).toContain('January');
    expect(formatDate('31/01/2027', 'en')).toBe('');
  });

  describe('durations', () => {
    it('is hours and zero-padded minutes, dropping the seconds', () => {
      expect(formatDuration(0)).toBe('0:00');
      expect(formatDuration(59)).toBe('0:00');
      expect(formatDuration(3_660)).toBe('1:01');
      expect(formatDuration(8 * 3_600 + 5 * 60 + 59)).toBe('8:05');
    });

    it('never goes negative', () => {
      expect(formatDuration(-30)).toBe('0:00');
    });
  });

  describe('money', () => {
    it('knows how many minor units make a major one from the currency, not from a guess', () => {
      expect(minorUnitsOf('UZS')).toBe(2);
      expect(minorUnitsOf('USD')).toBe(2);
      expect(minorUnitsOf('JPY')).toBe(0);
    });

    it('shows integer minor units as an amount with the currency', () => {
      const text = formatMoney(12_500_000, 'UZS', 'en');
      expect(text).toContain('125,000');
      expect(text).toMatch(/UZS|soʻm|so'm/);
      expect(formatMoney(1_250, 'USD', 'en')).toBe('$12.50');
    });

    it('does not turn a large amount into a float it cannot hold exactly', () => {
      expect(formatMoney(900_719_925_474_099, 'USD', 'en')).toContain('9,007,199,254,740.99');
    });
  });

  describe('reading an amount the courier typed', () => {
    it('turns major units into minor units without float arithmetic', () => {
      expect(parseMinorUnits('125000', 'UZS')).toBe(12_500_000);
      expect(parseMinorUnits('12.50', 'USD')).toBe(1_250);
      expect(parseMinorUnits('12,5', 'USD')).toBe(1_250);
      expect(parseMinorUnits('0.29', 'USD')).toBe(29);
      expect(parseMinorUnits('1 250 000', 'UZS')).toBe(125_000_000);
      expect(parseMinorUnits('1 250', 'JPY')).toBe(1_250);
    });

    it('refuses what is not an amount, so nothing odd reaches the platform', () => {
      for (const text of ['', ' ', 'abc', '-5', '12.505', '1e3', '12.5.5', '1,000.50.1']) {
        expect(parseMinorUnits(text, 'USD'), text).toBeNull();
      }
      expect(parseMinorUnits('12.5', 'JPY')).toBeNull();
    });
  });

  describe('distance', () => {
    it('is metres below a kilometre and kilometres above', () => {
      expect(formatDistance(850, 'en')).toBe('850 m');
      expect(formatDistance(3_200, 'en')).toBe('3.2 km');
      expect(formatDistance(12_000, 'en')).toBe('12 km');
    });
  });
});
