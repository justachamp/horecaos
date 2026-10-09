import { describe, expect, it } from 'vitest';

import {
  BLOCK_LIMITS,
  Block,
  BlockDocument,
  blockDocumentProblems,
  compactBlocks,
  convertBlock,
  documentTextLength,
  hasForbiddenCharacter,
  isAllowedHref,
  newBlock,
  readBlockDocument,
} from './block-document';

const paragraph = (text: string): Block => ({ type: 'PARAGRAPH', inlines: [{ text }] });
const documentOf = (...blocks: Block[]): BlockDocument => ({ schemaVersion: 1, blocks });

describe('isAllowedHref (ADR 0157: the link schemes a block document may store)', () => {
  it.each([
    'https://example.uz/menu',
    'HTTPS://Example.uz/a?b=1#c',
    'tel:+998901234567',
    'tel:998901234567',
    'mailto:hello@example.uz',
  ])('allows %s', (href) => {
    expect(isAllowedHref(href)).toBe(true);
  });

  it.each([
    ['http, not https', 'http://example.uz'],
    ['javascript:', 'javascript:alert(1)'],
    ['data:', 'data:text/html;base64,PHNjcmlwdD4='],
    ['a relative link', '/menu'],
    ['a bare host', 'example.uz'],
    ['an https address with credentials', 'https://user:pw@example.uz/'],
    ['an https address with no host', 'https://'],
    ['a short tel:', 'tel:12345'],
    ['a malformed mailto', 'mailto:not-an-address'],
    ['a link with a control character', 'https://example.uz/\u0007'],
    ['an empty string', ''],
    ['whitespace only', '   '],
  ])('refuses %s', (_label, href) => {
    expect(isAllowedHref(href)).toBe(false);
  });
});

describe('hasForbiddenCharacter', () => {
  it('refuses controls, separators and the bidirectional overrides that spoof text direction', () => {
    for (const code of [0x01, 0x7f, 0x85, 0x2028, 0x2029, 0x202a, 0x202e, 0x2066, 0x2069]) {
      expect(hasForbiddenCharacter(`a${String.fromCharCode(code)}b`, true)).toBe(true);
    }
  });

  it('allows a newline only where asked', () => {
    expect(hasForbiddenCharacter('a\nb', true)).toBe(false);
    expect(hasForbiddenCharacter('a\nb', false)).toBe(true);
  });

  it('allows ordinary text in the three platform languages', () => {
    expect(hasForbiddenCharacter('Здравствуйте, oʻzbek tilida ham, and English', true)).toBe(false);
  });
});

describe('readBlockDocument', () => {
  it('reads every block type the grammar has', () => {
    const read = readBlockDocument({
      schemaVersion: 1,
      blocks: [
        {
          type: 'PARAGRAPH',
          inlines: [
            { text: 'a', bold: true },
            { text: 'b', href: 'https://x.uz' },
          ],
        },
        { type: 'HEADING', level: 3, inlines: [{ text: 'h' }] },
        { type: 'QUOTE', inlines: [{ text: 'q', italic: true }] },
        { type: 'LIST', ordered: true, items: [[{ text: '1' }], [{ text: '2' }]] },
        {
          type: 'IMAGE',
          assetId: '5e1f0000-0000-4000-8000-000000000001',
          caption: [{ text: 'c' }],
        },
        { type: 'DIVIDER' },
      ],
    });

    expect(read.blocks.map((b) => b.type)).toEqual([
      'PARAGRAPH',
      'HEADING',
      'QUOTE',
      'LIST',
      'IMAGE',
      'DIVIDER',
    ]);
    expect(read.blocks[0]).toEqual({
      type: 'PARAGRAPH',
      inlines: [
        { text: 'a', bold: true },
        { text: 'b', href: 'https://x.uz' },
      ],
    });
    expect(read.blocks[1]).toMatchObject({ level: 3 });
  });

  it('skips a block type it does not know, and a run that is not text, instead of failing', () => {
    const read = readBlockDocument({
      blocks: [
        { type: 'TABLE', rows: [] },
        { type: 'PARAGRAPH', inlines: [{ text: 'kept' }, { notText: 1 }, 'bare string', null] },
        42,
        null,
      ],
    });

    expect(read.blocks).toEqual([{ type: 'PARAGRAPH', inlines: [{ text: 'kept' }] }]);
  });

  it('reads anything that is not a document as an empty one', () => {
    for (const raw of [null, undefined, 'text', 7, [], {}, { blocks: 'nope' }]) {
      expect(readBlockDocument(raw)).toEqual({ schemaVersion: 1, blocks: [] });
    }
  });

  it('does not carry a flag that is not literally true', () => {
    const read = readBlockDocument({
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'x', bold: 'yes', italic: 1, href: '' }] }],
    });

    expect(read.blocks[0]).toEqual({ type: 'PARAGRAPH', inlines: [{ text: 'x' }] });
  });
});

describe('compactBlocks', () => {
  it('drops a blank paragraph, an empty list, an image with no asset and runs with no text, in order', () => {
    const compacted = compactBlocks([
      paragraph('first'),
      paragraph(''),
      { type: 'HEADING', level: 2, inlines: [{ text: '' }] },
      { type: 'LIST', ordered: false, items: [[{ text: '' }], [{ text: 'kept' }]] },
      { type: 'LIST', ordered: true, items: [[{ text: '' }]] },
      { type: 'IMAGE', assetId: '', caption: null },
      { type: 'IMAGE', assetId: 'a1', caption: [{ text: '' }] },
      { type: 'DIVIDER' },
      paragraph('last'),
    ]);

    expect(compacted.blocks).toEqual([
      paragraph('first'),
      { type: 'LIST', ordered: false, items: [[{ text: 'kept' }]] },
      { type: 'IMAGE', assetId: 'a1', caption: null },
      { type: 'DIVIDER' },
      paragraph('last'),
    ]);
    expect(compacted.schemaVersion).toBe(1);
  });
});

describe('convertBlock', () => {
  it('keeps the text when a paragraph becomes a heading, a quote or a list item', () => {
    const source = paragraph('Dishes');

    expect(convertBlock(source, 'HEADING')).toEqual({
      type: 'HEADING',
      level: 2,
      inlines: [{ text: 'Dishes' }],
    });
    expect(convertBlock(source, 'QUOTE')).toEqual({ type: 'QUOTE', inlines: [{ text: 'Dishes' }] });
    expect(convertBlock(source, 'LIST')).toEqual({
      type: 'LIST',
      ordered: false,
      items: [[{ text: 'Dishes' }]],
    });
  });

  it('joins a list into a paragraph with a space between items', () => {
    const list: Block = {
      type: 'LIST',
      ordered: false,
      items: [[{ text: 'one' }], [{ text: 'two', bold: true }]],
    };

    expect(convertBlock(list, 'PARAGRAPH')).toEqual({
      type: 'PARAGRAPH',
      inlines: [{ text: 'one' }, { text: ' ' }, { text: 'two', bold: true }],
    });
  });

  it('starts an image or a divider afresh and returns the same block for the same type', () => {
    const source = paragraph('x');

    expect(convertBlock(source, 'IMAGE')).toEqual({ type: 'IMAGE', assetId: '', caption: null });
    expect(convertBlock(source, 'DIVIDER')).toEqual({ type: 'DIVIDER' });
    expect(convertBlock(source, 'PARAGRAPH')).toBe(source);
  });
});

describe('blockDocumentProblems (the limits the server refuses a document for)', () => {
  it('finds nothing wrong with an ordinary document', () => {
    expect(
      blockDocumentProblems(
        documentOf(paragraph('hello'), {
          type: 'PARAGRAPH',
          inlines: [{ text: 'link', href: 'https://example.uz' }],
        }),
      ),
    ).toEqual([]);
  });

  it('counts blocks, runs, list items, run length and the whole text', () => {
    const tooManyBlocks = documentOf(
      ...Array.from({ length: BLOCK_LIMITS.maxBlocks + 1 }, () => paragraph('x')),
    );
    expect(blockDocumentProblems(tooManyBlocks).map((p) => p.code)).toContain('TOO_MANY_BLOCKS');

    const tooManyRuns = documentOf({
      type: 'PARAGRAPH',
      inlines: Array.from({ length: BLOCK_LIMITS.maxRunsPerBlock + 1 }, () => ({ text: 'x' })),
    });
    expect(blockDocumentProblems(tooManyRuns)).toEqual([{ code: 'TOO_MANY_RUNS', blockIndex: 0 }]);

    const tooManyItems = documentOf({
      type: 'LIST',
      ordered: false,
      items: Array.from({ length: BLOCK_LIMITS.maxListItems + 1 }, () => [{ text: 'x' }]),
    });
    expect(blockDocumentProblems(tooManyItems)).toEqual([
      { code: 'TOO_MANY_ITEMS', blockIndex: 0 },
    ]);

    const longRun = documentOf(paragraph('x'.repeat(BLOCK_LIMITS.maxRunCharacters + 1)));
    expect(blockDocumentProblems(longRun)).toEqual([{ code: 'RUN_TOO_LONG', blockIndex: 0 }]);

    const lotsOfText = documentOf(
      ...Array.from({ length: 11 }, () => paragraph('x'.repeat(BLOCK_LIMITS.maxRunCharacters))),
    );
    expect(documentTextLength(lotsOfText)).toBe(55_000);
    expect(blockDocumentProblems(lotsOfText)).toEqual([
      { code: 'TEXT_TOO_LONG', blockIndex: null },
    ]);
  });

  it('flags a forbidden character, a link the grammar refuses, an empty list and an image with no asset', () => {
    const codes = blockDocumentProblems(
      documentOf(
        paragraph('bi‮di'),
        { type: 'PARAGRAPH', inlines: [{ text: 'bad', href: 'javascript:alert(1)' }] },
        { type: 'LIST', ordered: false, items: [] },
        { type: 'IMAGE', assetId: '', caption: null },
      ),
    );

    expect(codes).toEqual([
      { code: 'FORBIDDEN_CHARACTER', blockIndex: 0 },
      { code: 'HREF_NOT_ALLOWED', blockIndex: 1 },
      { code: 'EMPTY_LIST', blockIndex: 2 },
      { code: 'IMAGE_WITHOUT_ASSET', blockIndex: 3 },
    ]);
  });

  it('counts a text by code points, as the server does, so an emoji is one character', () => {
    expect(documentTextLength(documentOf(paragraph('a😀b')))).toBe(3);
  });
});

describe('newBlock', () => {
  it('makes an empty block of every type', () => {
    expect(newBlock('PARAGRAPH')).toEqual(paragraph(''));
    expect(newBlock('HEADING')).toEqual({ type: 'HEADING', level: 2, inlines: [{ text: '' }] });
    expect(newBlock('LIST')).toEqual({ type: 'LIST', ordered: false, items: [[{ text: '' }]] });
    expect(newBlock('DIVIDER')).toEqual({ type: 'DIVIDER' });
    expect(
      compactBlocks(
        ['PARAGRAPH', 'HEADING', 'QUOTE', 'LIST', 'IMAGE'].map((t) => newBlock(t as never)),
      ).blocks,
    ).toEqual([]);
  });
});
