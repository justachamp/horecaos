import { describe, expect, it } from 'vitest';

import { buildXlsxFixture } from './xlsx-preview.testing';
import { parseXlsxGrid } from './xlsx-preview';

const SHARED_STRINGS_XML =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="8" uniqueCount="8">' +
  '<si><t>product_code</t></si>' + // 0
  '<si><t>category_code</t></si>' + // 1
  '<si><t>product_name</t></si>' + // 2
  '<si><t>variant_sku</t></si>' + // 3
  '<si><t>status</t></si>' + // 4
  '<si><t>BURGER-CLASSIC</t></si>' + // 5
  '<si><t>MAIN</t></si>' + // 6
  '<si><t>ACTIVE</t></si>' + // 7
  '</sst>';

/**
 * One header row (A-F: product_code, category_code, product_name,
 * variant_sku, price_amount_minor [inlineStr, deliberately not a shared
 * string], status) plus three data rows:
 *
 * - row 2: every column filled, a mix of shared-string and numeric cells,
 *   with F2 written *before* E2 in document order -- proving column
 *   placement reads the `r="..."` cell reference, not element order.
 * - row 3: only `product_code` and `price_amount_minor` present (the same
 *   "price-only correction" shape `CatalogImportParser.templateWorkbook`'s
 *   own Examples sheet demonstrates) -- proving a sparse row does not shift
 *   later columns into earlier ones.
 * - row 4: present but every cell blank -- proving an entirely blank row is
 *   skipped, the same forgiveness the CSV path and the server's own xlsx
 *   parser both give a trailing blank row.
 */
const SHEET_XML =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' +
  '<row r="1">' +
  '<c r="A1" t="s"><v>0</v></c>' +
  '<c r="B1" t="s"><v>1</v></c>' +
  '<c r="C1" t="s"><v>2</v></c>' +
  '<c r="D1" t="s"><v>3</v></c>' +
  '<c r="E1" t="inlineStr"><is><t>price_amount_minor</t></is></c>' +
  '<c r="F1" t="s"><v>4</v></c>' +
  '</row>' +
  '<row r="2">' +
  '<c r="A2" t="s"><v>5</v></c>' +
  '<c r="B2" t="s"><v>6</v></c>' +
  '<c r="C2" t="inlineStr"><is><t>Классический бургер</t></is></c>' +
  '<c r="D2" t="inlineStr"><is><t>SKU-BURGER-001</t></is></c>' +
  '<c r="F2" t="s"><v>7</v></c>' +
  '<c r="E2"><v>45000</v></c>' +
  '</row>' +
  '<row r="3">' +
  '<c r="A3" t="s"><v>5</v></c>' +
  '<c r="E3"><v>48000</v></c>' +
  '</row>' +
  '<row r="4">' +
  '<c r="A4"><v></v></c>' +
  '</row>' +
  '</sheetData></worksheet>';

describe('parseXlsxGrid', () => {
  it('reads the header and data rows of a real deflate-compressed workbook, columns placed by cell reference', async () => {
    const buffer = buildXlsxFixture([
      { name: 'xl/worksheets/sheet1.xml', content: SHEET_XML },
      { name: 'xl/sharedStrings.xml', content: SHARED_STRINGS_XML },
    ]);

    const grid = await parseXlsxGrid(buffer);

    expect(grid.header).toEqual([
      'product_code',
      'category_code',
      'product_name',
      'variant_sku',
      'price_amount_minor',
      'status',
    ]);
    // Row 4 (entirely blank) is dropped -- only two data rows survive.
    expect(grid.rows).toEqual([
      ['BURGER-CLASSIC', 'MAIN', 'Классический бургер', 'SKU-BURGER-001', '45000', 'ACTIVE'],
      ['BURGER-CLASSIC', '', '', '', '48000', ''],
    ]);
  });

  it('resolves the first sheet correctly even with other zip entries interleaved before it', async () => {
    const buffer = buildXlsxFixture([
      { name: '[Content_Types].xml', content: '<Types/>' },
      { name: 'xl/worksheets/sheet1.xml', content: SHEET_XML },
      { name: 'xl/sharedStrings.xml', content: SHARED_STRINGS_XML },
      { name: 'xl/workbook.xml', content: '<workbook/>' },
    ]);

    const grid = await parseXlsxGrid(buffer);

    expect(grid.header[0]).toBe('product_code');
    expect(grid.rows).toHaveLength(2);
  });

  it('degrades to an empty grid, never throws, when the first sheet is not at the conventional path', async () => {
    const buffer = buildXlsxFixture([{ name: 'xl/worksheets/sheet2.xml', content: SHEET_XML }]);

    const grid = await parseXlsxGrid(buffer);

    expect(grid).toEqual({ header: [], rows: [] });
  });

  it('degrades to an empty grid, never throws, for a buffer that is not a zip at all', async () => {
    const notAZip = new TextEncoder().encode('this is not a zip file').buffer;

    const grid = await parseXlsxGrid(notAZip as ArrayBuffer);

    expect(grid).toEqual({ header: [], rows: [] });
  });

  it('reads a sheet with no shared strings table -- every cell inline or numeric', async () => {
    const inlineOnlySheet =
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' +
      '<row r="1"><c r="A1" t="inlineStr"><is><t>product_code</t></is></c></row>' +
      '<row r="2"><c r="A2" t="inlineStr"><is><t>BURGER-CLASSIC</t></is></c></row>' +
      '</sheetData></worksheet>';
    const buffer = buildXlsxFixture([
      { name: 'xl/worksheets/sheet1.xml', content: inlineOnlySheet },
    ]);

    const grid = await parseXlsxGrid(buffer);

    expect(grid.header).toEqual(['product_code']);
    expect(grid.rows).toEqual([['BURGER-CLASSIC']]);
  });
});
