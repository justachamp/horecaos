// This app's tsconfig carries no "node" types (a browser app has no business
// importing Node built-ins at runtime) -- this import is the exception, the
// same one `design-tokens.testing.ts` already makes: it runs only under
// Vitest's Node test runner, never shipped to a browser.
// @ts-expect-error node:zlib is available at Vitest's Node runtime
import { deflateRawSync } from 'node:zlib';

interface ZipEntrySource {
  readonly name: string;
  readonly content: string;
}

/**
 * Builds a minimal, deflate-compressed ZIP container carrying exactly the
 * named entries -- just enough for `xlsx-preview.ts`'s own reader, not a
 * general-purpose ZIP writer, and test-only. Every real `.xlsx` a merchant
 * uploads is deflate-compressed the same way (fastexcel and Excel both
 * write compression method 8), so this builder does too, via Node's own
 * zlib -- there is no CRC-32 check on the read side to satisfy, so entries
 * carry a zero CRC.
 */
export function buildXlsxFixture(entries: readonly ZipEntrySource[]): ArrayBuffer {
  const encoder = new TextEncoder();
  const localParts: Uint8Array[] = [];
  const centralParts: Uint8Array[] = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = encoder.encode(entry.name);
    const contentBytes = encoder.encode(entry.content);
    const compressed = new Uint8Array(deflateRawSync(contentBytes));

    const localHeader = new DataView(new ArrayBuffer(30));
    localHeader.setUint32(0, 0x04034b50, true);
    localHeader.setUint16(4, 20, true);
    localHeader.setUint16(6, 0, true);
    localHeader.setUint16(8, 8, true);
    localHeader.setUint16(10, 0, true);
    localHeader.setUint16(12, 0, true);
    localHeader.setUint32(14, 0, true);
    localHeader.setUint32(18, compressed.length, true);
    localHeader.setUint32(22, contentBytes.length, true);
    localHeader.setUint16(26, nameBytes.length, true);
    localHeader.setUint16(28, 0, true);

    const localEntry = concatBytes([new Uint8Array(localHeader.buffer), nameBytes, compressed]);
    localParts.push(localEntry);

    const centralHeader = new DataView(new ArrayBuffer(46));
    centralHeader.setUint32(0, 0x02014b50, true);
    centralHeader.setUint16(4, 20, true);
    centralHeader.setUint16(6, 20, true);
    centralHeader.setUint16(8, 0, true);
    centralHeader.setUint16(10, 8, true);
    centralHeader.setUint16(12, 0, true);
    centralHeader.setUint16(14, 0, true);
    centralHeader.setUint32(16, 0, true);
    centralHeader.setUint32(20, compressed.length, true);
    centralHeader.setUint32(24, contentBytes.length, true);
    centralHeader.setUint16(28, nameBytes.length, true);
    centralHeader.setUint16(30, 0, true);
    centralHeader.setUint16(32, 0, true);
    centralHeader.setUint16(34, 0, true);
    centralHeader.setUint16(36, 0, true);
    centralHeader.setUint32(38, 0, true);
    centralHeader.setUint32(42, offset, true);

    centralParts.push(concatBytes([new Uint8Array(centralHeader.buffer), nameBytes]));

    offset += localEntry.length;
  }

  const centralDirectory = concatBytes(centralParts);
  const centralDirectoryOffset = offset;

  const eocd = new DataView(new ArrayBuffer(22));
  eocd.setUint32(0, 0x06054b50, true);
  eocd.setUint16(4, 0, true);
  eocd.setUint16(6, 0, true);
  eocd.setUint16(8, entries.length, true);
  eocd.setUint16(10, entries.length, true);
  eocd.setUint32(12, centralDirectory.length, true);
  eocd.setUint32(16, centralDirectoryOffset, true);
  eocd.setUint16(20, 0, true);

  const whole = concatBytes([...localParts, centralDirectory, new Uint8Array(eocd.buffer)]);
  return whole.buffer as ArrayBuffer;
}

function concatBytes(parts: readonly Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const out = new Uint8Array(total);
  let position = 0;
  for (const part of parts) {
    out.set(part, position);
    position += part.length;
  }
  return out;
}
