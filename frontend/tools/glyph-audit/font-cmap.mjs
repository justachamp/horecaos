/* Which code points a font file really draws.
 *
 * The glyph audit (ADR 0149, Decision 7; gap-map row X.40) has to answer "does the face we ship
 * contain the letters this language is written in", and a stylesheet's `unicode-range` cannot answer
 * that: it is the author's claim about what a file is for, not a statement about what is in it. So
 * this reads the file. It understands the three containers a web font arrives in -- WOFF2 (the
 * format both consoles ship), WOFF and a bare sfnt -- and returns the set of code points whose
 * `cmap` entry maps to a glyph other than .notdef.
 *
 * No dependency: Node's own zlib inflates WOFF and brotli-decompresses WOFF2. Only the `cmap` table
 * is read, and it is never transformed by WOFF2 (only `glyf`, `loca` and `hmtx` are), so the WOFF2
 * table directory is walked just far enough to find where `cmap` starts in the decompressed stream.
 */
import { brotliDecompressSync, inflateSync } from 'node:zlib';

/** WOFF2 "known table" tags by flag index (W3C WOFF2, section 5.1). */
const WOFF2_KNOWN_TAGS = [
  'cmap', 'head', 'hhea', 'hmtx', 'maxp', 'name', 'OS/2', 'post', 'cvt ', 'fpgm', 'glyf', 'loca',
  'prep', 'CFF ', 'VORG', 'EBDT', 'EBLC', 'gasp', 'hdmx', 'kern', 'LTSH', 'PCLT', 'VDMX', 'vhea',
  'vmtx', 'BASE', 'GDEF', 'GPOS', 'GSUB', 'EBSC', 'JSTF', 'MATH', 'CBDT', 'CBLC', 'COLR', 'CPAL',
  'SVG ', 'sbix', 'acnt', 'avar', 'bdat', 'bloc', 'bsln', 'cvar', 'fdsc', 'feat', 'fmtx', 'fvar',
  'gvar', 'hsty', 'just', 'lcar', 'mort', 'morx', 'opbd', 'prop', 'trak', 'Zapf', 'Silf', 'Glat',
  'Gloc', 'Feat', 'Sill',
];

/** @param {Buffer} buffer @returns {Buffer} the `cmap` table of the font in `buffer` */
function cmapTable(buffer) {
  const signature = buffer.toString('latin1', 0, 4);
  if (signature === 'wOF2') {
    return woff2Cmap(buffer);
  }
  if (signature === 'wOFF') {
    return woffCmap(buffer);
  }
  return sfntCmap(buffer, 0);
}

function sfntCmap(buffer, base) {
  const numTables = buffer.readUInt16BE(base + 4);
  for (let index = 0; index < numTables; index += 1) {
    const record = base + 12 + index * 16;
    if (buffer.toString('latin1', record, record + 4) === 'cmap') {
      const offset = buffer.readUInt32BE(record + 8);
      const length = buffer.readUInt32BE(record + 12);
      return buffer.subarray(offset, offset + length);
    }
  }
  throw new Error('the font has no cmap table');
}

function woffCmap(buffer) {
  const numTables = buffer.readUInt16BE(12);
  for (let index = 0; index < numTables; index += 1) {
    const record = 44 + index * 20;
    if (buffer.toString('latin1', record, record + 4) === 'cmap') {
      const offset = buffer.readUInt32BE(record + 4);
      const compressed = buffer.readUInt32BE(record + 8);
      const original = buffer.readUInt32BE(record + 12);
      const data = buffer.subarray(offset, offset + compressed);
      return compressed === original ? data : inflateSync(data);
    }
  }
  throw new Error('the font has no cmap table');
}

function woff2Cmap(buffer) {
  const numTables = buffer.readUInt16BE(12);
  const compressedSize = buffer.readUInt32BE(20);
  let cursor = 48;

  const readBase128 = () => {
    let value = 0;
    for (let byte = 0; byte < 5; byte += 1) {
      const next = buffer[cursor];
      cursor += 1;
      value = (value << 7) | (next & 0x7f);
      if ((next & 0x80) === 0) {
        return value >>> 0;
      }
    }
    throw new Error('a UIntBase128 is at most five bytes');
  };

  const tables = [];
  for (let index = 0; index < numTables; index += 1) {
    const flags = buffer[cursor];
    cursor += 1;
    const tagIndex = flags & 0x3f;
    const transformVersion = flags >> 6;
    let tag;
    if (tagIndex === 63) {
      tag = buffer.toString('latin1', cursor, cursor + 4);
      cursor += 4;
    } else {
      tag = WOFF2_KNOWN_TAGS[tagIndex];
    }
    const originalLength = readBase128();
    // glyf and loca are transformed unless the version is 3; every other table is transformed
    // unless the version is 0. Only a transformed table carries a second length.
    const transformed = tag === 'glyf' || tag === 'loca' ? transformVersion !== 3 : transformVersion !== 0;
    const storedLength = transformed ? readBase128() : originalLength;
    tables.push({ tag, storedLength });
  }

  const stream = brotliDecompressSync(buffer.subarray(cursor, cursor + compressedSize));
  let offset = 0;
  for (const table of tables) {
    if (table.tag === 'cmap') {
      return stream.subarray(offset, offset + table.storedLength);
    }
    offset += table.storedLength;
  }
  throw new Error('the font has no cmap table');
}

/**
 * The code points a `cmap` maps to a real glyph. Formats 4 (BMP) and 12 (full range) are read;
 * a font carrying only another format is reported rather than guessed at.
 *
 * @param {Buffer} cmap
 * @returns {Set<number>}
 */
function codePointsOf(cmap) {
  const subtables = cmap.readUInt16BE(2);
  const points = new Set();
  let understood = false;
  for (let index = 0; index < subtables; index += 1) {
    const offset = cmap.readUInt32BE(4 + index * 8 + 4);
    const format = cmap.readUInt16BE(offset);
    if (format === 4) {
      understood = true;
      readFormat4(cmap, offset, points);
    } else if (format === 12) {
      understood = true;
      readFormat12(cmap, offset, points);
    }
  }
  if (!understood) {
    throw new Error('the cmap has neither a format 4 nor a format 12 subtable');
  }
  return points;
}

function readFormat4(cmap, offset, points) {
  const segCount = cmap.readUInt16BE(offset + 6) / 2;
  const endCodes = offset + 14;
  const startCodes = endCodes + segCount * 2 + 2;
  const idDeltas = startCodes + segCount * 2;
  const idRangeOffsets = idDeltas + segCount * 2;
  for (let segment = 0; segment < segCount; segment += 1) {
    const end = cmap.readUInt16BE(endCodes + segment * 2);
    const start = cmap.readUInt16BE(startCodes + segment * 2);
    const delta = cmap.readInt16BE(idDeltas + segment * 2);
    const rangeOffset = cmap.readUInt16BE(idRangeOffsets + segment * 2);
    for (let code = start; code <= end && code !== 0xffff; code += 1) {
      let glyph;
      if (rangeOffset === 0) {
        glyph = (code + delta) & 0xffff;
      } else {
        const position = idRangeOffsets + segment * 2 + rangeOffset + (code - start) * 2;
        const raw = cmap.readUInt16BE(position);
        glyph = raw === 0 ? 0 : (raw + delta) & 0xffff;
      }
      if (glyph !== 0) {
        points.add(code);
      }
    }
  }
}

function readFormat12(cmap, offset, points) {
  const groups = cmap.readUInt32BE(offset + 12);
  for (let group = 0; group < groups; group += 1) {
    const record = offset + 16 + group * 12;
    const start = cmap.readUInt32BE(record);
    const end = cmap.readUInt32BE(record + 4);
    const firstGlyph = cmap.readUInt32BE(record + 8);
    for (let code = start; code <= end; code += 1) {
      if (firstGlyph + (code - start) !== 0) {
        points.add(code);
      }
    }
  }
}

/** @param {Buffer} fontFile a WOFF2, WOFF or sfnt file @returns {Set<number>} */
export function codePointsInFont(fontFile) {
  return codePointsOf(cmapTable(fontFile));
}
