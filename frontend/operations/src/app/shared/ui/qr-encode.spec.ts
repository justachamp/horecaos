import { describe, expect, it } from 'vitest';

import { QR_MAX_BYTE_CAPACITY, QrCapacityExceededError, encodeQrMatrix } from './qr-encode';

/** The standard 7x7 finder pattern, `true` = dark, read top-left-first. */
const FINDER_PATTERN: readonly (readonly boolean[])[] = [
  [true, true, true, true, true, true, true],
  [true, false, false, false, false, false, true],
  [true, false, true, true, true, false, true],
  [true, false, true, true, true, false, true],
  [true, false, true, true, true, false, true],
  [true, false, false, false, false, false, true],
  [true, true, true, true, true, true, true],
];

function finderAt(
  modules: readonly (readonly boolean[])[],
  topRow: number,
  topCol: number,
): readonly (readonly boolean[])[] {
  return FINDER_PATTERN.map((row, r) => row.map((_, c) => modules[topRow + r][topCol + c]));
}

describe('encodeQrMatrix', () => {
  it('renders a square matrix sized for the smallest version that fits the payload', () => {
    const short = encodeQrMatrix('123456'); // fits version 1 (17 bytes)
    expect(short.size).toBe(21 + 8); // 21 modules + 4-module quiet zone on each edge

    const longer = encodeQrMatrix('A'.repeat(60)); // needs version 3 or 4
    expect(longer.size).toBeGreaterThan(short.size);
    for (const row of longer.modules) {
      expect(row.length).toBe(longer.size);
    }
  });

  it('places the three standard finder patterns exactly, in every supported version', () => {
    for (const text of ['x', 'A'.repeat(40), 'A'.repeat(100)]) {
      const { size, modules } = encodeQrMatrix(text);
      const quiet = 4;
      const core = size - quiet * 2;
      expect(finderAt(modules, quiet, quiet)).toEqual(FINDER_PATTERN); // top-left
      expect(finderAt(modules, quiet, quiet + core - 7)).toEqual(FINDER_PATTERN); // top-right
      expect(finderAt(modules, quiet + core - 7, quiet)).toEqual(FINDER_PATTERN); // bottom-left
    }
  });

  it('surrounds the symbol with a light (false) quiet zone', () => {
    const { size, modules } = encodeQrMatrix('quiet-zone-check');
    for (let i = 0; i < size; i++) {
      expect(modules[0][i]).toBe(false);
      expect(modules[3][i]).toBe(false); // still inside the 4-module zone
      expect(modules[i][0]).toBe(false);
      expect(modules[size - 1][i]).toBe(false);
    }
  });

  it('alternates the timing pattern, starting and ending dark', () => {
    const { size, modules } = encodeQrMatrix('timing-check');
    const quiet = 4;
    const core = size - quiet * 2;
    for (let i = 8; i < core - 8; i++) {
      expect(modules[quiet + 6][quiet + i]).toBe(i % 2 === 0);
    }
  });

  it('renders a different matrix for a different payload', () => {
    const a = encodeQrMatrix('device-pairing-code-AAAA');
    const b = encodeQrMatrix('device-pairing-code-BBBB');
    expect(a.modules).not.toEqual(b.modules);
  });

  it('renders the same matrix twice for the same payload — deterministic, no randomness', () => {
    const a = encodeQrMatrix('https://pay.example.uz/checkout?id=1');
    const b = encodeQrMatrix('https://pay.example.uz/checkout?id=1');
    expect(a).toEqual(b);
  });

  it('accepts a payload right at the documented 106-byte ceiling', () => {
    const payload = 'a'.repeat(QR_MAX_BYTE_CAPACITY);
    expect(() => encodeQrMatrix(payload)).not.toThrow();
  });

  it('throws QrCapacityExceededError one byte past the ceiling, rather than emitting a wrong code', () => {
    const payload = 'a'.repeat(QR_MAX_BYTE_CAPACITY + 1);
    expect(() => encodeQrMatrix(payload)).toThrow(QrCapacityExceededError);
  });

  it('encodes the payments qrPayload shape (a provider checkout URL) without error', () => {
    const matrix = encodeQrMatrix(
      'https://pay.click.uz/services/pay?merchant_id=12345&amount=50000',
    );
    expect(matrix.size).toBeGreaterThan(0);
  });
});
