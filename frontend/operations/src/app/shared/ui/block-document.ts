/**
 * The typed block document `q-rich-text` authors in its block mode (ADR 0157, Decision 6).
 *
 * Editorial text is **never markup**. It is a JSON document of a closed set of blocks over plain-text
 * runs carrying `bold`, `italic` and `href` flags, validated by the server on write (unknown keys
 * refused, sizes bounded) and rendered by one fixed component per block type on the storefront. HTML,
 * Markdown, SVG, iframes, scripts, `data:` and `javascript:` links are not representable here and so
 * cannot be stored. This file is the console's half of that contract: the types, a tolerant reader
 * for a document the server sent, and the same limits the server enforces, so an author hears about
 * a problem while typing and not as a 422 at publication. The server stays the authority.
 *
 * Pure and DOM-free, so every rule is tested without a component.
 */

/** One run of plain text. `href` is `https:`, `tel:` or `mailto:` (see {@link isAllowedHref}). */
export interface Inline {
  readonly text: string;
  readonly bold?: boolean;
  readonly italic?: boolean;
  readonly href?: string;
}

export type Block =
  | { readonly type: 'PARAGRAPH'; readonly inlines: readonly Inline[] }
  | { readonly type: 'HEADING'; readonly level: 2 | 3; readonly inlines: readonly Inline[] }
  | { readonly type: 'QUOTE'; readonly inlines: readonly Inline[] }
  | {
      readonly type: 'LIST';
      readonly ordered: boolean;
      readonly items: readonly (readonly Inline[])[];
    }
  | {
      readonly type: 'IMAGE';
      readonly assetId: string;
      readonly caption: readonly Inline[] | null;
    }
  | { readonly type: 'DIVIDER' };

export type BlockType = Block['type'];

export interface BlockDocument {
  readonly schemaVersion: 1;
  readonly blocks: readonly Block[];
}

/** The figures `BlockDocuments` enforces on the server. */
export const BLOCK_LIMITS = {
  maxBlocks: 200,
  maxRunsPerBlock: 40,
  maxListItems: 50,
  maxTextCharacters: 50_000,
  maxRunCharacters: 5_000,
} as const;

export const BLOCK_TYPES: readonly BlockType[] = [
  'PARAGRAPH',
  'HEADING',
  'QUOTE',
  'LIST',
  'IMAGE',
  'DIVIDER',
];

export function emptyBlockDocument(): BlockDocument {
  return { schemaVersion: 1, blocks: [] };
}

const TEL = /^tel:\+?[0-9]{7,15}$/;
const MAILTO = /^mailto:[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/;

/**
 * Whether a link may be stored: `https` (a parseable address with a host and no credentials), `tel:`
 * or `mailto:`. `http`, `javascript:`, `data:` and relative links are refused, as is anything with
 * a control character in it. Whether an `https` host is on the brand's allowlist is a publication
 * gate the server answers; this is the shape rule.
 */
export function isAllowedHref(href: string): boolean {
  const value = href.trim();
  if (value === '' || hasForbiddenCharacter(value, false)) {
    return false;
  }
  if (TEL.test(value) || MAILTO.test(value)) {
    return true;
  }
  if (!/^https:\/\//i.test(value)) {
    return false;
  }
  try {
    const url = new URL(value);
    return (
      url.protocol === 'https:' && url.hostname !== '' && url.username === '' && url.password === ''
    );
  } catch {
    return false;
  }
}

/**
 * C0 controls (other than a newline where one is allowed), DEL, C1 controls, the line and paragraph
 * separators and the bidirectional override and isolate controls -- the ones that spoof the direction
 * of text -- are refused, as `TextRules.hasForbiddenCharacter` refuses them.
 */
export function hasForbiddenCharacter(text: string, allowNewline: boolean): boolean {
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (code === 0x0a) {
      if (!allowNewline) {
        return true;
      }
      continue;
    }
    if (code < 0x20 || code === 0x7f || (code >= 0x80 && code <= 0x9f)) {
      return true;
    }
    if (code === 0x2028 || code === 0x2029) {
      return true;
    }
    if ((code >= 0x202a && code <= 0x202e) || (code >= 0x2066 && code <= 0x2069)) {
      return true;
    }
  }
  return false;
}

/** The text of a run list, concatenated: what a character count and a plain-text summary see. */
export function runsText(runs: readonly Inline[]): string {
  return runs.map((run) => run.text).join('');
}

function blockRuns(block: Block): readonly (readonly Inline[])[] {
  switch (block.type) {
    case 'PARAGRAPH':
    case 'HEADING':
    case 'QUOTE':
      return [block.inlines];
    case 'LIST':
      return block.items;
    case 'IMAGE':
      return block.caption ? [block.caption] : [];
    case 'DIVIDER':
      return [];
  }
}

/** Characters of text in the whole document: the figure the 50,000 limit counts. */
export function documentTextLength(document: BlockDocument): number {
  let total = 0;
  for (const block of document.blocks) {
    for (const runs of blockRuns(block)) {
      total += [...runsText(runs)].length;
    }
  }
  return total;
}

/** A stable code per problem, for a sentence in the author's language. */
export type BlockProblemCode =
  | 'TOO_MANY_BLOCKS'
  | 'TOO_MANY_RUNS'
  | 'TOO_MANY_ITEMS'
  | 'TEXT_TOO_LONG'
  | 'RUN_TOO_LONG'
  | 'FORBIDDEN_CHARACTER'
  | 'HREF_NOT_ALLOWED'
  | 'EMPTY_LIST'
  | 'IMAGE_WITHOUT_ASSET';

export interface BlockProblem {
  readonly code: BlockProblemCode;
  /** The block's place in the document, from 0. */
  readonly blockIndex: number | null;
}

/** Every problem the server would refuse the document for, found before it is sent. */
export function blockDocumentProblems(document: BlockDocument): readonly BlockProblem[] {
  const problems: BlockProblem[] = [];
  if (document.blocks.length > BLOCK_LIMITS.maxBlocks) {
    problems.push({ code: 'TOO_MANY_BLOCKS', blockIndex: null });
  }
  if (documentTextLength(document) > BLOCK_LIMITS.maxTextCharacters) {
    problems.push({ code: 'TEXT_TOO_LONG', blockIndex: null });
  }
  document.blocks.forEach((block, index) => {
    const add = (code: BlockProblemCode): void => {
      problems.push({ code, blockIndex: index });
    };
    if (block.type === 'LIST') {
      if (block.items.length === 0) {
        add('EMPTY_LIST');
      }
      if (block.items.length > BLOCK_LIMITS.maxListItems) {
        add('TOO_MANY_ITEMS');
      }
    }
    if (block.type === 'IMAGE' && block.assetId === '') {
      add('IMAGE_WITHOUT_ASSET');
    }
    for (const runs of blockRuns(block)) {
      if (runs.length > BLOCK_LIMITS.maxRunsPerBlock) {
        add('TOO_MANY_RUNS');
      }
      for (const run of runs) {
        if ([...run.text].length > BLOCK_LIMITS.maxRunCharacters) {
          add('RUN_TOO_LONG');
        }
        if (hasForbiddenCharacter(run.text, true)) {
          add('FORBIDDEN_CHARACTER');
        }
        if (run.href !== undefined && !isAllowedHref(run.href)) {
          add('HREF_NOT_ALLOWED');
        }
      }
    }
  });
  return problems;
}

// ----------------------------------------------------------------- reading what the server sent

function readInline(raw: unknown): Inline | null {
  if (raw === null || typeof raw !== 'object') {
    return null;
  }
  const source = raw as Record<string, unknown>;
  if (typeof source['text'] !== 'string') {
    return null;
  }
  const inline: { text: string; bold?: boolean; italic?: boolean; href?: string } = {
    text: source['text'],
  };
  if (source['bold'] === true) {
    inline.bold = true;
  }
  if (source['italic'] === true) {
    inline.italic = true;
  }
  if (typeof source['href'] === 'string' && source['href'] !== '') {
    inline.href = source['href'];
  }
  return inline;
}

function readRuns(raw: unknown): readonly Inline[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw.map(readInline).filter((inline): inline is Inline => inline !== null);
}

/**
 * Reads a body the server stored (or any value) into a document, tolerantly: a block of a type this
 * console does not know is dropped (a client skips what it does not know), a malformed run is
 * dropped, and anything that is not a document at all is an empty one. It never throws and never
 * invents content; it is not a validator -- {@link blockDocumentProblems} is.
 */
export function readBlockDocument(raw: unknown): BlockDocument {
  if (raw === null || typeof raw !== 'object') {
    return emptyBlockDocument();
  }
  const blocksRaw = (raw as Record<string, unknown>)['blocks'];
  if (!Array.isArray(blocksRaw)) {
    return emptyBlockDocument();
  }
  const blocks: Block[] = [];
  for (const entry of blocksRaw) {
    if (entry === null || typeof entry !== 'object') {
      continue;
    }
    const source = entry as Record<string, unknown>;
    switch (source['type']) {
      case 'PARAGRAPH':
        blocks.push({ type: 'PARAGRAPH', inlines: readRuns(source['inlines']) });
        break;
      case 'QUOTE':
        blocks.push({ type: 'QUOTE', inlines: readRuns(source['inlines']) });
        break;
      case 'HEADING':
        blocks.push({
          type: 'HEADING',
          level: source['level'] === 3 ? 3 : 2,
          inlines: readRuns(source['inlines']),
        });
        break;
      case 'LIST': {
        const items = Array.isArray(source['items']) ? source['items'].map(readRuns) : [];
        blocks.push({ type: 'LIST', ordered: source['ordered'] === true, items });
        break;
      }
      case 'IMAGE': {
        const assetId = typeof source['assetId'] === 'string' ? source['assetId'] : '';
        const caption = Array.isArray(source['caption']) ? readRuns(source['caption']) : null;
        blocks.push({ type: 'IMAGE', assetId, caption });
        break;
      }
      case 'DIVIDER':
        blocks.push({ type: 'DIVIDER' });
        break;
      default:
        // An unknown block type is skipped, not an error.
        break;
    }
  }
  return { schemaVersion: 1, blocks };
}

// --------------------------------------------------------------------------- writing it back

function trimRuns(runs: readonly Inline[]): readonly Inline[] {
  // A run with no text says nothing; the server wants at least one run, so a block left with none
  // is dropped by the caller, not padded here. A link address the author opened and left blank is
  // not a link.
  return runs
    .filter((run) => run.text !== '')
    .map((run) => {
      if (run.href === undefined || run.href.trim() !== '') {
        return run;
      }
      const { href: _blank, ...rest } = run;
      return rest;
    });
}

/**
 * The document an author's blocks come to: empty text blocks and empty lists are dropped (a blank
 * paragraph is a keystroke, not content), runs with no text are dropped, a list drops its empty
 * items, and an image with no asset is dropped. Order is kept.
 */
export function compactBlocks(blocks: readonly Block[]): BlockDocument {
  const out: Block[] = [];
  for (const block of blocks) {
    switch (block.type) {
      case 'PARAGRAPH':
      case 'QUOTE': {
        const inlines = trimRuns(block.inlines);
        if (inlines.length > 0) {
          out.push({ type: block.type, inlines });
        }
        break;
      }
      case 'HEADING': {
        const inlines = trimRuns(block.inlines);
        if (inlines.length > 0) {
          out.push({ type: 'HEADING', level: block.level, inlines });
        }
        break;
      }
      case 'LIST': {
        const items = block.items.map(trimRuns).filter((item) => item.length > 0);
        if (items.length > 0) {
          out.push({ type: 'LIST', ordered: block.ordered, items });
        }
        break;
      }
      case 'IMAGE': {
        if (block.assetId !== '') {
          const caption = block.caption ? trimRuns(block.caption) : null;
          out.push({
            type: 'IMAGE',
            assetId: block.assetId,
            caption: caption && caption.length > 0 ? caption : null,
          });
        }
        break;
      }
      case 'DIVIDER':
        out.push(block);
        break;
    }
  }
  return { schemaVersion: 1, blocks: out };
}

/** A block of `type` with nothing in it yet, as the editor adds one. */
export function newBlock(type: BlockType): Block {
  switch (type) {
    case 'PARAGRAPH':
      return { type, inlines: [{ text: '' }] };
    case 'QUOTE':
      return { type, inlines: [{ text: '' }] };
    case 'HEADING':
      return { type, level: 2, inlines: [{ text: '' }] };
    case 'LIST':
      return { type, ordered: false, items: [[{ text: '' }]] };
    case 'IMAGE':
      return { type, assetId: '', caption: null };
    case 'DIVIDER':
      return { type };
  }
}

/**
 * Changes a block's type, keeping its text where the new type has somewhere to put it: a paragraph
 * becomes a heading, a quote or a one-item list without retyping, and a list becomes a paragraph of
 * its items joined by a space. An image and a divider carry no text, so changing to or from them
 * starts that block afresh.
 */
export function convertBlock(block: Block, to: BlockType): Block {
  if (block.type === to) {
    return block;
  }
  const text = blockRuns(block)[0] ?? [];
  const carried: readonly Inline[] = block.type === 'LIST' ? joinItems(block.items) : text;
  switch (to) {
    case 'PARAGRAPH':
    case 'QUOTE':
      return { type: to, inlines: carried.length > 0 ? carried : [{ text: '' }] };
    case 'HEADING':
      return { type: 'HEADING', level: 2, inlines: carried.length > 0 ? carried : [{ text: '' }] };
    case 'LIST':
      return {
        type: 'LIST',
        ordered: false,
        items: [carried.length > 0 ? carried : [{ text: '' }]],
      };
    case 'IMAGE':
    case 'DIVIDER':
      return newBlock(to);
  }
}

function joinItems(items: readonly (readonly Inline[])[]): readonly Inline[] {
  const out: Inline[] = [];
  items.forEach((item, index) => {
    if (index > 0 && item.length > 0) {
      out.push({ text: ' ' });
    }
    out.push(...item);
  });
  return out;
}
