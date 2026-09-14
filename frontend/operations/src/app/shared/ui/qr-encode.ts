/**
 * Row `X.35` — a from-scratch QR Code (ISO/IEC 18004) byte-mode encoder.
 *
 * No npm dependency exists for this today (`q-qr-code.ts`'s own doc names
 * why one is not added: this frontend's node_modules is a symlink onto a
 * package-lock-pinned tree, and every external script this platform's
 * artifacts load is CDN-allowlisted — neither route fits a library pulled in
 * ad hoc for one component). What follows is the standard, published
 * algorithm — mode indicator, Reed–Solomon error correction over GF(256),
 * function-pattern placement, a fixed mask — not a novel one; ADR 0119
 * records why this wave stops at **versions 1–5, error-correction level L,
 * byte mode only** (106 bytes maximum) rather than the full versions 1–40 ×
 * 4-level table every commercial generator supports: that needs multi-block
 * Reed–Solomon interleaving this wave does not build, and 106 bytes already
 * covers both of this wave's own payloads — an ADR 0079 pairing `userCode`
 * (a handful of characters) and a provider `qrPayload` URL.
 *
 * **A fixed mask, not the "best of eight."** Mask selection only optimises
 * contrast/legibility; any of the eight standard masks, correctly declared
 * in the 15-bit format-information field, produces an equally *valid*,
 * decodable symbol — a scanner reads the mask id out of the format bits and
 * un-masks before touching Reed–Solomon. Fixing mask 0
 * (`(row + column) % 2 === 0`) removes the whole penalty-scoring rule set
 * (ISO/IEC 18004 §8.8.2, four separate heuristics) from this file's surface
 * without weakening correctness.
 *
 * **Not verified against a physical scanner in this environment** — no
 * camera or reference decoder is available here (see this repo's own
 * `qr-encode.spec.ts` for the structural checks that *are* run: finder/timing
 * pattern shape, matrix dimensions, format-info self-consistency). Spot-check
 * a real render with a phone camera before this reaches a printed table card
 * or a production kitchen tablet.
 */

/** Thrown when a payload exceeds this encoder's 106-byte ceiling (version 5, level L). */
export class QrCapacityExceededError extends Error {
  constructor(
    readonly byteLength: number,
    readonly maxBytes: number,
  ) {
    super(`QR payload is ${byteLength} bytes, over this encoder's ${maxBytes}-byte ceiling`);
    this.name = 'QrCapacityExceededError';
  }
}

export interface QrMatrix {
  /** Modules per side, including the standard 4-module quiet zone on every edge. */
  readonly size: number;
  /** `modules[row][col]` — `true` is a dark module. Row 0 / column 0 is the top-left quiet-zone corner. */
  readonly modules: readonly (readonly boolean[])[];
}

/** One row per supported version (index 0 = version 1). */
const DATA_CODEWORDS: readonly number[] = [19, 34, 55, 80, 108];
const EC_CODEWORDS: readonly number[] = [7, 10, 15, 20, 26];

/** `floor((dataCodewords*8 - 12) / 8)` — 12 bits is the byte-mode mode+length-indicator overhead (versions 1–9). */
export const QR_BYTE_CAPACITY: readonly number[] = DATA_CODEWORDS.map((cw) =>
  Math.floor((cw * 8 - 12) / 8),
);

export const QR_MAX_BYTE_CAPACITY = QR_BYTE_CAPACITY[QR_BYTE_CAPACITY.length - 1];

/** Encodes `text` (UTF-8) as a QR symbol. Throws {@link QrCapacityExceededError} past 106 bytes. */
export function encodeQrMatrix(text: string): QrMatrix {
  const bytes = new TextEncoder().encode(text);
  const version = QR_BYTE_CAPACITY.findIndex((capacity) => bytes.length <= capacity);
  if (version === -1) {
    throw new QrCapacityExceededError(bytes.length, QR_MAX_BYTE_CAPACITY);
  }

  const dataCodewords = buildDataCodewords(bytes, DATA_CODEWORDS[version]);
  const ecCodewords = reedSolomonEncode(dataCodewords, EC_CODEWORDS[version]);
  const codewords = [...dataCodewords, ...ecCodewords];

  return renderMatrix(version + 1, codewords);
}

// ---------------------------------------------------------------------------
// Bit-stream construction (ISO/IEC 18004 §8.4)
// ---------------------------------------------------------------------------

function buildDataCodewords(bytes: Uint8Array, dataCodewordCount: number): number[] {
  const bits = new BitWriter();
  bits.write(0b0100, 4); // byte-mode indicator
  bits.write(bytes.length, 8); // character-count indicator, versions 1–9
  for (const byte of bytes) {
    bits.write(byte, 8);
  }

  const totalBits = dataCodewordCount * 8;
  bits.write(0, Math.min(4, totalBits - bits.length)); // terminator, truncated if there is no room
  bits.padToByteBoundary();

  const pads = [0xec, 0x11];
  let padIndex = 0;
  while (bits.length < totalBits) {
    bits.write(pads[padIndex % 2], 8);
    padIndex++;
  }
  return bits.toBytes();
}

class BitWriter {
  private bits: number[] = [];

  get length(): number {
    return this.bits.length;
  }

  write(value: number, bitCount: number): void {
    for (let i = bitCount - 1; i >= 0; i--) {
      this.bits.push((value >> i) & 1);
    }
  }

  padToByteBoundary(): void {
    const remainder = this.bits.length % 8;
    if (remainder !== 0) {
      this.write(0, 8 - remainder);
    }
  }

  toBytes(): number[] {
    const out: number[] = [];
    for (let i = 0; i < this.bits.length; i += 8) {
      let byte = 0;
      for (let j = 0; j < 8; j++) {
        byte = (byte << 1) | (this.bits[i + j] ?? 0);
      }
      out.push(byte);
    }
    return out;
  }
}

// ---------------------------------------------------------------------------
// Reed–Solomon error correction over GF(256) (ISO/IEC 18004 §8.5, Annex A)
// ---------------------------------------------------------------------------

const GF_EXP = new Array<number>(512).fill(0);
const GF_LOG = new Array<number>(256).fill(0);
(function buildGaloisTables(): void {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) {
      x ^= 0x11d; // primitive polynomial x^8 + x^4 + x^3 + x^2 + 1
    }
  }
  for (let i = 255; i < 512; i++) {
    GF_EXP[i] = GF_EXP[i - 255];
  }
})();

function gfMultiply(a: number, b: number): number {
  if (a === 0 || b === 0) {
    return 0;
  }
  return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

/** The degree-`ecCount` generator polynomial, highest-degree coefficient first; `[0]` is always 1. */
function reedSolomonGenerator(ecCount: number): number[] {
  let poly = [1];
  for (let i = 0; i < ecCount; i++) {
    const next = new Array<number>(poly.length + 1).fill(0);
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMultiply(poly[j], GF_EXP[i]);
    }
    poly = next;
  }
  return poly;
}

function reedSolomonEncode(dataCodewords: number[], ecCount: number): number[] {
  const generator = reedSolomonGenerator(ecCount);
  const buffer = [...dataCodewords, ...new Array<number>(ecCount).fill(0)];
  for (let i = 0; i < dataCodewords.length; i++) {
    const coefficient = buffer[i];
    if (coefficient === 0) {
      continue;
    }
    for (let j = 0; j < generator.length; j++) {
      buffer[i + j] ^= gfMultiply(generator[j], coefficient);
    }
  }
  return buffer.slice(dataCodewords.length);
}

// ---------------------------------------------------------------------------
// Format information (ISO/IEC 18004 §8.9) — BCH(15,5), generator 0x537
// ---------------------------------------------------------------------------

const FORMAT_MASK = 0b101010000010010;
const FORMAT_GENERATOR = 0x537;

/** EC-level bits for the 15-bit format field (§8.9, Table 25). Only `L` is ever used here. */
const EC_LEVEL_BITS_L = 0b01;

/** The 15-bit format-information value for EC level L and the given mask pattern (0–7). */
function formatInformationBits(maskPattern: number): number {
  const data5 = (EC_LEVEL_BITS_L << 3) | maskPattern;
  let value = data5 << 10;
  for (let bit = 14; bit >= 10; bit--) {
    if ((value >> bit) & 1) {
      value ^= FORMAT_GENERATOR << (bit - 10);
    }
  }
  return ((data5 << 10) | value) ^ FORMAT_MASK;
}

// ---------------------------------------------------------------------------
// Matrix construction (ISO/IEC 18004 §6.3, §8.7)
// ---------------------------------------------------------------------------

const QUIET_ZONE = 4;
const MASK_PATTERN = 0; // (row + column) % 2 === 0 — see this file's own doc

function renderMatrix(version: number, codewords: number[]): QrMatrix {
  const size = 17 + 4 * version;
  const dark: boolean[][] = Array.from({ length: size }, () =>
    new Array<boolean>(size).fill(false),
  );
  const isFunction: boolean[][] = Array.from({ length: size }, () =>
    new Array<boolean>(size).fill(false),
  );

  const markFunction = (row: number, col: number, value: boolean): void => {
    dark[row][col] = value;
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
        const onRing = r === -1 || r === 7 || c === -1 || c === 7; // separator, always light
        const value =
          !onRing &&
          (r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
        markFunction(row, col, value);
      }
    }
  };
  placeFinder(0, 0);
  placeFinder(0, size - 7);
  placeFinder(size - 7, 0);

  // Timing patterns — row/column 6, alternating dark/light, between the finder separators.
  for (let i = 8; i < size - 8; i++) {
    const value = i % 2 === 0;
    markFunction(6, i, value);
    markFunction(i, 6, value);
  }

  // Alignment pattern — versions 2–5 carry exactly one, interior only (§6.3, Annex E small-version case).
  if (version >= 2) {
    const center = 18 + 4 * (version - 2);
    for (let r = -2; r <= 2; r++) {
      for (let c = -2; c <= 2; c++) {
        const ring = Math.max(Math.abs(r), Math.abs(c));
        markFunction(center + r, center + c, ring !== 1);
      }
    }
  }

  // The dark module — always present, always dark (§6.3).
  markFunction(4 * version + 9, 8, true);

  // Format-information cells, reserved now and filled after mask application (§8.9, Figure 25).
  const reserveFormatCells = (): void => {
    for (let i = 0; i <= 8; i++) {
      if (!isFunction[8][i]) {
        markFunction(8, i, false);
      }
      if (!isFunction[i][8]) {
        markFunction(i, 8, false);
      }
    }
    for (let i = 0; i < 8; i++) {
      markFunction(8, size - 1 - i, false);
      markFunction(size - 1 - i, 8, false);
    }
  };
  reserveFormatCells();

  // Data placement — the standard bottom-right-up zigzag, two columns at a time, skipping column 6.
  const bits = codewordsToBits(codewords);
  let bitIndex = 0;
  let upward = true;
  for (let colPair = size - 1; colPair > 0; colPair -= 2) {
    const col = colPair === 6 ? 5 : colPair; // column 6 is the timing column — shift left past it
    for (let step = 0; step < size; step++) {
      const row = upward ? size - 1 - step : step;
      for (const c of [col, col - 1]) {
        if (isFunction[row][c]) {
          continue;
        }
        const bit = bitIndex < bits.length ? bits[bitIndex] : 0;
        bitIndex++;
        // Mask 0's condition, applied by XOR: `dark = bit XOR maskCondition`.
        const maskCondition = (row + c) % 2 === 0;
        dark[row][c] = maskCondition ? bit === 0 : bit === 1;
      }
    }
    upward = !upward;
  }

  // Format information, written last so it is never treated as a data cell above.
  const format = formatInformationBits(MASK_PATTERN);
  for (let i = 0; i <= 5; i++) {
    dark[8][i] = ((format >> i) & 1) === 1;
  }
  dark[8][7] = ((format >> 6) & 1) === 1;
  dark[8][8] = ((format >> 7) & 1) === 1;
  dark[7][8] = ((format >> 8) & 1) === 1;
  for (let i = 9; i <= 14; i++) {
    dark[14 - i][8] = ((format >> i) & 1) === 1;
  }
  for (let i = 0; i <= 7; i++) {
    dark[size - 1 - i][8] = ((format >> i) & 1) === 1;
  }
  for (let i = 8; i <= 14; i++) {
    dark[8][size - 15 + i] = ((format >> i) & 1) === 1;
  }

  return withQuietZone(size, dark);
}

function codewordsToBits(codewords: number[]): number[] {
  const bits: number[] = [];
  for (const codeword of codewords) {
    for (let i = 7; i >= 0; i--) {
      bits.push((codeword >> i) & 1);
    }
  }
  return bits;
}

function withQuietZone(size: number, dark: boolean[][]): QrMatrix {
  const total = size + QUIET_ZONE * 2;
  const modules: boolean[][] = Array.from({ length: total }, () =>
    new Array<boolean>(total).fill(false),
  );
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) {
      modules[r + QUIET_ZONE][c + QUIET_ZONE] = dark[r][c];
    }
  }
  return { size: total, modules };
}
