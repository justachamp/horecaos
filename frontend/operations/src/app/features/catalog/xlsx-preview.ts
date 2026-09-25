/**
 * Row 4.5b: a minimal, dependency-free reader for the one `.xlsx` shape
 * `catalog-import-page.ts`'s own client-side row preview needs -- just
 * enough of the OOXML/ZIP container to pull the first sheet's cell text
 * out, purely so `q-import-wizard` can show a preview table before
 * anything is sent to the server. The server's own parse
 * (`CatalogImportParser`, fastexcel) is authoritative and unaffected by
 * anything here; if this reader cannot make sense of a workbook for any
 * reason it returns an empty grid rather than throwing -- losing the
 * preview is an acceptable degrade, corrupting or blocking the upload is
 * not (the same forgiveness an empty/malformed CSV's preview already has).
 *
 * No third-party unzip/xlsx library, and none needed: `DecompressionStream`
 * (`'deflate-raw'`) is the one runtime primitive this borrows, present in
 * every evergreen browser and in this project's own Vitest/jsdom test
 * environment (verified empirically, not assumed -- Node exposes it as a
 * global and jsdom does not shadow it).
 *
 * Deliberately narrow: assumes the first sheet lives at the conventional
 * `xl/worksheets/sheet1.xml` path -- true for every workbook this page's
 * own `template.xlsx` download produces, and for the overwhelming majority
 * of real spreadsheets. A workbook whose first sheet was reordered away
 * from that path in a spreadsheet editor simply gets no client-side
 * preview, never a wrong one. No zip64, no encryption, no formulas.
 */

const LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;
const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
const END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
const END_OF_CENTRAL_DIRECTORY_MIN_LENGTH = 22;
const MAX_ZIP_COMMENT_LENGTH = 65536;

/** Bounds the work a single preview parse ever does -- well above any real brand catalog and far above the 50 rows `q-import-wizard` ever renders, just a safety valve against a pathological upload. */
const MAX_XLSX_PREVIEW_ROWS = 2000;

const SHEET1_PATH = 'xl/worksheets/sheet1.xml';
const SHARED_STRINGS_PATH = 'xl/sharedStrings.xml';

export interface XlsxGrid {
  readonly header: readonly string[];
  readonly rows: readonly (readonly string[])[];
}

const EMPTY_GRID: XlsxGrid = { header: [], rows: [] };

/**
 * Reads the first sheet of an `.xlsx` workbook as a header row plus data
 * rows -- the same `string[]`-per-row shape `splitCsvLine` already hands
 * the CSV preview path, so the caller can map both formats through one
 * column-matching routine.
 */
export async function parseXlsxGrid(buffer: ArrayBuffer): Promise<XlsxGrid> {
  try {
    const bytes = new Uint8Array(buffer);
    const entries = readCentralDirectory(bytes);
    const sheetEntry = entries.get(SHEET1_PATH);
    if (!sheetEntry) {
      return EMPTY_GRID;
    }
    const sharedStringsEntry = entries.get(SHARED_STRINGS_PATH);
    const [sheetXml, sharedStringsXml] = await Promise.all([
      readEntryText(bytes, sheetEntry),
      sharedStringsEntry ? readEntryText(bytes, sharedStringsEntry) : Promise.resolve(null),
    ]);
    const sharedStrings = sharedStringsXml === null ? [] : parseSharedStrings(sharedStringsXml);
    return parseSheetGrid(sheetXml, sharedStrings);
  } catch {
    return EMPTY_GRID;
  }
}

// ---------------------------------------------------------------- ZIP container

interface ZipEntry {
  readonly localHeaderOffset: number;
  readonly compressedSize: number;
  readonly compressionMethod: number;
}

function readCentralDirectory(bytes: Uint8Array): Map<string, ZipEntry> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const eocdOffset = findEndOfCentralDirectory(view);
  const entries = new Map<string, ZipEntry>();
  if (eocdOffset < 0) {
    return entries;
  }
  const entryCount = view.getUint16(eocdOffset + 10, true);
  const decoder = new TextDecoder('utf-8');
  let offset = view.getUint32(eocdOffset + 16, true);
  for (let i = 0; i < entryCount; i++) {
    if (
      offset + 46 > view.byteLength ||
      view.getUint32(offset, true) !== CENTRAL_DIRECTORY_SIGNATURE
    ) {
      break;
    }
    const compressionMethod = view.getUint16(offset + 10, true);
    const compressedSize = view.getUint32(offset + 20, true);
    const fileNameLength = view.getUint16(offset + 28, true);
    const extraLength = view.getUint16(offset + 30, true);
    const commentLength = view.getUint16(offset + 32, true);
    const localHeaderOffset = view.getUint32(offset + 42, true);
    const nameStart = offset + 46;
    const fileName = decoder.decode(bytes.subarray(nameStart, nameStart + fileNameLength));
    entries.set(fileName, { localHeaderOffset, compressedSize, compressionMethod });
    offset = nameStart + fileNameLength + extraLength + commentLength;
  }
  return entries;
}

/** Scans backward for the EOCD signature -- the last fixed anchor in a ZIP, followed only by an optional (rarely used) comment of bounded length. */
function findEndOfCentralDirectory(view: DataView): number {
  const start = Math.max(
    0,
    view.byteLength - END_OF_CENTRAL_DIRECTORY_MIN_LENGTH - MAX_ZIP_COMMENT_LENGTH,
  );
  for (let i = view.byteLength - END_OF_CENTRAL_DIRECTORY_MIN_LENGTH; i >= start; i--) {
    if (view.getUint32(i, true) === END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
      return i;
    }
  }
  return -1;
}

async function readEntryText(bytes: Uint8Array, entry: ZipEntry): Promise<string> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const headerOffset = entry.localHeaderOffset;
  if (view.getUint32(headerOffset, true) !== LOCAL_FILE_HEADER_SIGNATURE) {
    throw new Error('xlsx-preview: local file header signature mismatch');
  }
  const fileNameLength = view.getUint16(headerOffset + 26, true);
  const extraLength = view.getUint16(headerOffset + 28, true);
  const dataStart = headerOffset + 30 + fileNameLength + extraLength;
  const data = bytes.subarray(dataStart, dataStart + entry.compressedSize);
  const raw = entry.compressionMethod === 0 ? data : await inflateRaw(data);
  return new TextDecoder('utf-8').decode(raw);
}

/** `compression method 8` (deflate) is what every real `.xlsx` writer this parser has seen uses; method 0 (stored) is handled above without this. */
async function inflateRaw(data: Uint8Array): Promise<Uint8Array> {
  // `DecompressionStream`'s own DOM typing declares `writable:
  // WritableStream<BufferSource>`, which `ReadableStream<Uint8Array>.
  // pipeThrough` does not accept structurally without this cast -- a TS
  // lib quirk, not a real type mismatch (a `Uint8Array` is exactly a
  // `BufferSource`).
  const transform = new DecompressionStream('deflate-raw') as unknown as ReadableWritablePair<
    Uint8Array,
    Uint8Array
  >;
  const decompressed = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(data);
      controller.close();
    },
  }).pipeThrough(transform);
  const reader = decompressed.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    chunks.push(value);
    total += value.length;
  }
  const out = new Uint8Array(total);
  let position = 0;
  for (const chunk of chunks) {
    out.set(chunk, position);
    position += chunk.length;
  }
  return out;
}

// ---------------------------------------------------------------- SpreadsheetML

function parseSharedStrings(xml: string): readonly string[] {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  return Array.from(doc.getElementsByTagName('si')).map((si) => si.textContent ?? '');
}

function parseSheetGrid(xml: string, sharedStrings: readonly string[]): XlsxGrid {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  const rowElements = Array.from(doc.getElementsByTagName('row')).slice(
    0,
    MAX_XLSX_PREVIEW_ROWS + 1,
  );
  const populated = rowElements
    .map((rowEl) => readRowCells(rowEl, sharedStrings))
    .filter((row) => row.size > 0);
  if (populated.length === 0) {
    return EMPTY_GRID;
  }
  const [headerCells, ...dataCells] = populated;
  const width = Math.max(...[headerCells, ...dataCells].map(maxColumnIndex)) + 1;
  return {
    header: toDenseRow(headerCells, width),
    rows: dataCells.map((cells) => toDenseRow(cells, width)),
  };
}

function readRowCells(rowEl: Element, sharedStrings: readonly string[]): Map<number, string> {
  const cells = new Map<number, string>();
  const cellElements = Array.from(rowEl.getElementsByTagName('c'));
  cellElements.forEach((cellEl, position) => {
    const ref = cellEl.getAttribute('r');
    const index = ref ? columnIndexFromRef(ref) : position;
    const text = cellText(cellEl, sharedStrings).trim();
    if (text !== '' && index >= 0) {
      cells.set(index, text);
    }
  });
  return cells;
}

function cellText(cellEl: Element, sharedStrings: readonly string[]): string {
  const type = cellEl.getAttribute('t');
  if (type === 'inlineStr') {
    const inline = cellEl.getElementsByTagName('is')[0];
    return inline?.textContent ?? '';
  }
  const valueEl = cellEl.getElementsByTagName('v')[0];
  const raw = valueEl?.textContent ?? '';
  if (type === 's') {
    const index = Number.parseInt(raw, 10);
    return Number.isFinite(index) ? (sharedStrings[index] ?? '') : '';
  }
  return raw;
}

/** `"B7"` -> 1 (zero-based). The digits are a row number this parser ignores -- row order already comes from document order. */
function columnIndexFromRef(ref: string): number {
  let index = 0;
  for (const char of ref) {
    const code = char.toUpperCase().charCodeAt(0);
    if (code < 65 || code > 90) {
      break;
    }
    index = index * 26 + (code - 64);
  }
  return index - 1;
}

function maxColumnIndex(cells: Map<number, string>): number {
  let max = -1;
  for (const index of cells.keys()) {
    if (index > max) {
      max = index;
    }
  }
  return max;
}

function toDenseRow(cells: Map<number, string>, width: number): readonly string[] {
  const row: string[] = new Array(width).fill('');
  for (const [index, value] of cells) {
    if (index >= 0 && index < width) {
      row[index] = value;
    }
  }
  return row;
}
