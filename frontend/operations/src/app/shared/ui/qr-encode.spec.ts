import { describe, expect, it } from 'vitest';

import {
  DATA_CODEWORDS,
  EC_CODEWORDS,
  QR_MAX_BYTE_CAPACITY,
  QrCapacityExceededError,
  QrMatrix,
  encodeQrMatrix,
  reedSolomonEncode,
} from './qr-encode';

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

// ---------------------------------------------------------------------------
// A reference decoder, independent of qr-encode.ts's own placement loop, used to prove
// a rendered matrix actually carries the payload it was built from — regression
// coverage for the P17 finding where the encoder's zigzag double-wrote column 4 and
// never wrote column 0 at all (`col` was remapped per-iteration instead of mutating the
// loop counter, so the next decrement never picked up the shift). This walks the exact
// ISO/IEC 18004 zigzag qr-encode.ts's own doc comment claims to implement — if a future
// edit reintroduces that class of bug, encodeQrMatrix's *output* stops matching what
// this fixed-order walk expects, and these tests fail without needing a physical
// scanner or a full Reed–Solomon error-correcting decode.
// ---------------------------------------------------------------------------

/** Reconstructs which modules are function patterns for `version`, independent of any payload. */
function functionModuleMap(version: number): boolean[][] {
  const size = 17 + 4 * version;
  const isFunction: boolean[][] = Array.from({ length: size }, () =>
    new Array<boolean>(size).fill(false),
  );
  const mark = (row: number, col: number): void => {
    isFunction[row][col] = true;
  };
  const placeFinder = (topRow: number, topCol: number): void => {
    for (let r = -1; r <= 7; r++) {
      for (let c = -1; c <= 7; c++) {
        const row = topRow + r;
        const col = topCol + c;
        if (row < 0 || row >= size || col < 0 || col >= size) {
          continue;
        }
        mark(row, col);
      }
    }
  };
  placeFinder(0, 0);
  placeFinder(0, size - 7);
  placeFinder(size - 7, 0);
  for (let i = 8; i < size - 8; i++) {
    mark(6, i);
    mark(i, 6);
  }
  if (version >= 2) {
    const center = 18 + 4 * (version - 2);
    for (let r = -2; r <= 2; r++) {
      for (let c = -2; c <= 2; c++) {
        mark(center + r, center + c);
      }
    }
  }
  mark(4 * version + 9, 8);
  for (let i = 0; i <= 8; i++) {
    mark(8, i);
    mark(i, 8);
  }
  for (let i = 0; i < 8; i++) {
    mark(8, size - 1 - i);
    mark(size - 1 - i, 8);
  }
  return isFunction;
}

/** `encodeQrMatrix`'s output size identifies its version uniquely: `size = 17 + 4*version + 8` (quiet zone). */
function versionForMatrix(matrix: QrMatrix): number {
  return (matrix.size - 8 - 17) / 4;
}

/**
 * Reads the data/mask bits back out of `matrix` in the standard bottom-right-up zigzag
 * order (column 6 skipped by shifting the loop counter itself, not a per-iteration
 * local) — the same order `renderMatrix` must write in for the symbol to decode.
 */
function extractBits(matrix: QrMatrix, version: number): number[] {
  const size = 17 + 4 * version;
  const quiet = 4;
  const isFunction = functionModuleMap(version);
  const bits: number[] = [];
  let upward = true;
  for (let col = size - 1; col > 0; col -= 2) {
    if (col === 6) {
      col = 5;
    }
    for (let step = 0; step < size; step++) {
      const row = upward ? size - 1 - step : step;
      for (const c of [col, col - 1]) {
        if (isFunction[row][c]) {
          continue;
        }
        const dark = matrix.modules[row + quiet][c + quiet];
        const maskCondition = (row + c) % 2 === 0;
        bits.push(maskCondition ? (dark ? 0 : 1) : dark ? 1 : 0);
      }
    }
    upward = !upward;
  }
  return bits;
}

function bitsToBytes(bits: readonly number[]): number[] {
  const bytes: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    let byte = 0;
    for (let j = 0; j < 8; j++) {
      byte = (byte << 1) | bits[i + j];
    }
    bytes.push(byte);
  }
  return bytes;
}

/** Decodes byte-mode's own header (4-bit mode indicator + 8-bit length, versions 1–9) back to text. */
function decodePayloadText(bits: readonly number[]): string {
  let pos = 0;
  const read = (count: number): number => {
    let value = 0;
    for (let i = 0; i < count; i++) {
      value = (value << 1) | (bits[pos] ?? 0);
      pos++;
    }
    return value;
  };
  expect(read(4)).toBe(0b0100); // byte mode — the only mode this encoder ever writes
  const length = read(8);
  const bytes = new Uint8Array(length);
  for (let i = 0; i < length; i++) {
    bytes[i] = read(8);
  }
  return new TextDecoder().decode(bytes);
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

  it('round-trips through the standard ISO 18004 zigzag decode order — payload and error-correction codewords both come back exactly', () => {
    for (const text of [
      'x',
      'device-pairing-code-1234',
      'https://pay.click.uz/services/pay?merchant_id=12345&amount=50000',
      'a'.repeat(QR_MAX_BYTE_CAPACITY), // fills version 5 completely — no slack in any column
    ]) {
      const matrix = encodeQrMatrix(text);
      const version = versionForMatrix(matrix);
      const bits = extractBits(matrix, version);

      expect(decodePayloadText(bits)).toBe(text);

      // The header decoding correctly is necessary but not sufficient: this placement
      // bug corrupted only the columns visited late in the zigzag walk, which for every
      // supported version land entirely inside the error-correction codewords rather
      // than the data codewords — so a decode that stops at the payload text would
      // report success even while every code silently failed error correction. Recomputing
      // the expected EC codewords from the (correctly decoded) data codewords and
      // comparing them to what was actually rendered closes that gap.
      const codewords = bitsToBytes(bits);
      const dataCount = DATA_CODEWORDS[version - 1];
      const ecCount = EC_CODEWORDS[version - 1];
      const dataCodewords = codewords.slice(0, dataCount);
      const extractedEc = codewords.slice(dataCount, dataCount + ecCount);
      expect(extractedEc).toEqual(reedSolomonEncode(dataCodewords, ecCount));
    }
  });
});
