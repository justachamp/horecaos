import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import { sanitizeRichHtml } from './rich-text-sanitizer';

export type RichTextBlockKind = 'paragraph' | 'heading' | 'bullet' | 'numbered';

export interface RichTextBlock {
  readonly id: string;
  readonly kind: RichTextBlockKind;
  readonly text: string;
}

/** Already translated — one label per block kind, for the kind picker's options. */
export type RichTextBlockKindLabels = Readonly<Record<RichTextBlockKind, string>>;

let blockSequence = 0;
function nextBlockId(): string {
  blockSequence += 1;
  return `block-${blockSequence}`;
}

function emptyDocument(): readonly RichTextBlock[] {
  return [{ id: nextBlockId(), kind: 'paragraph', text: '' }];
}

/**
 * Parses `html` into a block sequence. Runs {@link sanitizeRichHtml} first,
 * so a document loaded from storage — the terms document's own
 * `contentsByLocale`, today plain text; tomorrow, whatever an earlier
 * session of this editor wrote — can never seed a block with markup, only
 * text. `h1`/`h2`/`h3` become `heading`; `li` under `ol` becomes `numbered`,
 * under anything else (including a bare `li`, which the sanitizer's
 * allowlist can produce from a malformed document) `bullet`; everything
 * else is a `paragraph`. Consecutive `li` siblings are not merged back into
 * one list — each is its own block, which is what lets an operator reorder
 * or retype one item without touching the rest.
 */
const BLOCK_LEVEL_TAGS: ReadonlySet<string> = new Set(['P', 'H1', 'H2', 'H3', 'LI', 'BLOCKQUOTE']);

function parseBlocks(html: string): readonly RichTextBlock[] {
  const clean = sanitizeRichHtml(html);
  if (clean.trim() === '') {
    return emptyDocument();
  }
  const doc = new DOMParser().parseFromString(`<div>${clean}</div>`, 'text/html');
  const root = doc.body.firstElementChild;
  const blocks: RichTextBlock[] = [];

  function kindOf(tag: string, parentTag: string | null): RichTextBlockKind {
    if (tag === 'H1' || tag === 'H2' || tag === 'H3') {
      return 'heading';
    }
    if (tag === 'LI') {
      return parentTag === 'OL' ? 'numbered' : 'bullet';
    }
    return 'paragraph';
  }

  /**
   * A legacy plain-text value (`terms-page.ts`'s three `<textarea>` fields,
   * today) carries no tags at all, so `node.children` alone would see
   * nothing — a bare text node is not an "element child". Walking
   * `childNodes` and folding a run of bare text and inline elements
   * (`STRONG`/`EM`/`B`/`I`/`BR`) into one implicit paragraph is what keeps
   * that legacy value from parsing to an empty document.
   */
  function walk(node: Element, parentTag: string | null): void {
    let pending = '';
    const flushPending = (): void => {
      const trimmed = pending.trim();
      if (trimmed !== '') {
        blocks.push({ id: nextBlockId(), kind: 'paragraph', text: trimmed });
      }
      pending = '';
    };

    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === Node.TEXT_NODE) {
        pending += child.textContent ?? '';
        continue;
      }
      if (child.nodeType !== Node.ELEMENT_NODE) {
        continue;
      }
      const element = child as Element;
      if (element.tagName === 'UL' || element.tagName === 'OL') {
        flushPending();
        walk(element, element.tagName);
        continue;
      }
      if (BLOCK_LEVEL_TAGS.has(element.tagName)) {
        flushPending();
        const text = (element.textContent ?? '').trim();
        if (text !== '') {
          blocks.push({ id: nextBlockId(), kind: kindOf(element.tagName, parentTag), text });
        }
        continue;
      }
      // An inline element (STRONG/EM/B/I/BR) at this level joins the pending
      // paragraph run — its own tag carries no block identity.
      pending += element.textContent ?? '';
    }
    flushPending();
  }

  if (root) {
    walk(root, null);
  }
  return blocks.length > 0 ? blocks : emptyDocument();
}

/**
 * Serializes a block sequence back to sanitized HTML. Every block's text is
 * assigned through `textContent`, never concatenated into a string — the
 * DOM's own escaping is what guarantees a block whose text happens to read
 * `<script>` round-trips as the literal words, not a tag, the same
 * commitment `sanitizeRichHtml` makes on the way in.
 */
function serializeBlocks(blocks: readonly RichTextBlock[]): string {
  const doc = document.implementation.createHTMLDocument('');
  const root = doc.createElement('div');
  let currentList: HTMLElement | null = null;
  let currentListKind: 'bullet' | 'numbered' | null = null;

  for (const block of blocks) {
    if (block.text.trim() === '') {
      continue;
    }
    if (block.kind === 'bullet' || block.kind === 'numbered') {
      if (currentListKind !== block.kind) {
        currentList = doc.createElement(block.kind === 'numbered' ? 'ol' : 'ul');
        currentListKind = block.kind;
        root.appendChild(currentList);
      }
      const li = doc.createElement('li');
      li.textContent = block.text;
      currentList!.appendChild(li);
      continue;
    }
    currentList = null;
    currentListKind = null;
    const el = doc.createElement(block.kind === 'heading' ? 'h2' : 'p');
    el.textContent = block.text;
    root.appendChild(el);
  }
  return root.innerHTML;
}

/**
 * A sanitized block editor — `q-rich-text` (row `X.31`). Not a textarea: the
 * parity behaviour being replaced is Delever accepting raw tenant HTML, and
 * a single freeform field that round-trips through `innerHTML` is exactly
 * that surface with a different owner. Its first honest consumer is
 * `settings/terms/terms-page.ts`, whose three locale fields ship plain
 * `<textarea>`s today.
 *
 * **Block, not rich-inline.** Each block carries a kind — paragraph,
 * heading, bullet or numbered — and plain text; there is no bold, italic or
 * link *inside* a block. That is a deliberate, smaller surface than a full
 * WYSIWYG editor: a block's text is a native `<textarea>`, which cannot
 * receive a pasted `<script>` at all (a textarea only ever accepts plain
 * text, by the platform's own behaviour, not this component's), so the
 * paste path this row exists to close is shut by construction rather than
 * by a paste handler this component would otherwise have to get right.
 *
 * **The sanitizer runs on both ends.** {@link sanitizeRichHtml} parses
 * `value` on the way in — closing a document loaded from storage, not only
 * one typed here — and {@link RichText.value}'s emitted HTML is built by
 * assigning each block's text to `textContent`, never string concatenation,
 * so escaping is the DOM's job, not a regex this component could get wrong.
 */
@Component({
  selector: 'q-rich-text',
  templateUrl: './rich-text.html',
  styleUrl: './rich-text.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RichText {
  /** Sanitized HTML, e.g. a previously-published terms version. */
  readonly value = input<string>('');
  readonly disabled = input(false, { transform: booleanAttribute });
  /** Shown on an empty block. Already translated. */
  readonly placeholder = input<string | null>(null);
  /** Already translated — this component never looks a key up (`q-action-menu`'s own rule for itself). */
  readonly kindLabels = input.required<RichTextBlockKindLabels>();
  readonly blockTypeLabel = input('Block type');
  readonly addBlockLabel = input('Add block');
  readonly removeBlockLabel = input('Remove block');
  /** The editor group's own accessible name. */
  readonly ariaLabel = input<string | null>(null);

  /** Sanitized HTML — always emitted, whatever channel the change came through. */
  readonly valueChange = output<string>();

  protected readonly blocks = signal<readonly RichTextBlock[]>(emptyDocument());
  protected readonly kindEntries = computed(
    () => Object.entries(this.kindLabels()) as [RichTextBlockKind, string][],
  );

  /**
   * The HTML this component itself last emitted. A caller that wires
   * `[value]="doc()" (valueChange)="doc.set($event)"` — the ordinary
   * controlled-component shape every consumer of this editor will use —
   * echoes each emission straight back as the next `value`; without this
   * guard the effect below would re-parse it, mint fresh block ids, and
   * Angular would tear down and recreate every `<textarea>` on the operator's
   * own keystroke, losing focus and cursor position mid-sentence. `null`
   * until the first emission, so an externally-supplied initial value (a
   * previously-published terms version, say) still parses once.
   */
  private lastEmittedHtml: string | null = null;

  constructor() {
    effect(
      () => {
        const html = this.value();
        if (html === this.lastEmittedHtml) {
          return;
        }
        this.blocks.set(parseBlocks(html));
      },
      { allowSignalWrites: true },
    );
  }

  protected onTextInput(id: string, event: Event): void {
    const text = (event.target as HTMLTextAreaElement).value;
    this.blocks.update((current) => current.map((b) => (b.id === id ? { ...b, text } : b)));
    this.emit();
  }

  protected setKind(id: string, kind: RichTextBlockKind): void {
    this.blocks.update((current) => current.map((b) => (b.id === id ? { ...b, kind } : b)));
    this.emit();
  }

  protected addBlockAfter(id: string): void {
    this.blocks.update((current) => {
      const index = current.findIndex((b) => b.id === id);
      const next = [...current];
      next.splice(index + 1, 0, { id: nextBlockId(), kind: 'paragraph', text: '' });
      return next;
    });
    this.emit();
  }

  protected removeBlock(id: string): void {
    this.blocks.update((current) =>
      current.length > 1 ? current.filter((b) => b.id !== id) : current,
    );
    this.emit();
  }

  private emit(): void {
    const html = sanitizeRichHtml(serializeBlocks(this.blocks()));
    this.lastEmittedHtml = html;
    this.valueChange.emit(html);
  }
}
