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

import {
  BLOCK_LIMITS,
  BLOCK_TYPES,
  Block,
  BlockDocument,
  BlockProblem,
  BlockProblemCode,
  BlockType,
  Inline,
  blockDocumentProblems,
  compactBlocks,
  convertBlock,
  newBlock,
  readBlockDocument,
} from './block-document';
import { MediaUploader } from './media-uploader';
import { RichTextRunLabels, RichTextRuns } from './rich-text-runs';

/** Already translated: this component never looks a key up, like `q-rich-text` itself. */
export interface RichTextBlockLabels extends RichTextRunLabels {
  readonly blockType: string;
  readonly types: Readonly<Record<BlockType, string>>;
  readonly headingLevel: string;
  readonly level2: string;
  readonly level3: string;
  readonly ordered: string;
  readonly moveUp: string;
  readonly moveDown: string;
  readonly removeBlock: string;
  readonly addBlock: string;
  readonly addItem: string;
  readonly removeItem: string;
  readonly caption: string;
  readonly imageNone: string;
  readonly imageUploaded: string;
  readonly imageFailed: string;
  readonly dividerLabel: string;
  /** One sentence per problem the server would refuse the document for; `{block}` is the block's number. */
  readonly problems: Readonly<Record<BlockProblemCode, string>>;
}

/** What the host's upload resolves to: the asset the block will name, and a URL to show it by. */
export interface RichTextImageUploaded {
  readonly assetId: string;
  readonly previewUrl: string | null;
}

export type RichTextImageUpload = (file: File) => Promise<RichTextImageUploaded>;

interface EditorBlock {
  readonly id: string;
  readonly block: Block;
}

let sequence = 0;
function nextId(): string {
  sequence += 1;
  return `doc-block-${sequence}`;
}

function fromDocument(document: BlockDocument | null): readonly EditorBlock[] {
  const blocks = readBlockDocument(document).blocks;
  const seeded = blocks.length > 0 ? blocks : [newBlock('PARAGRAPH')];
  return seeded.map((block) => ({ id: nextId(), block }));
}

/**
 * The block-document mode of `q-rich-text` (ADR 0157, Decision 6 and Decision 11; row `6.7a`).
 *
 * Authors a {@link BlockDocument} -- paragraphs, headings, quotes, lists, images and dividers over
 * plain-text runs -- and emits that, never an HTML string. There is no HTML, Markdown or embed
 * anywhere in the model, so nothing an author does can store markup; a block type this console does
 * not know is dropped on the way in (a client skips what it does not know), and the limits and the
 * link-scheme rule are the server's own ({@link blockDocumentProblems}), reported on screen while
 * the author types.
 *
 * Fully controlled, with the echo guard `q-rich-text` has: the document this component last emitted
 * coming straight back as `[document]` does not rebuild the blocks (which would recreate every
 * textarea under the author's cursor). Images go through the host's {@link imageUpload}, which owns
 * the media API; with none supplied the image block is not offered.
 */
@Component({
  selector: 'q-rich-text-blocks',
  imports: [MediaUploader, RichTextRuns],
  templateUrl: './rich-text-blocks.html',
  styleUrl: './rich-text-blocks.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RichTextBlocks {
  readonly document = input<BlockDocument | null>(null);
  readonly labels = input.required<RichTextBlockLabels>();
  readonly disabled = input(false, { transform: booleanAttribute });
  readonly ariaLabel = input<string | null>(null);
  readonly imageUpload = input<RichTextImageUpload | null>(null);
  /** URLs to show images by, for assets the document names but this session did not upload. */
  readonly imagePreviews = input<Readonly<Record<string, string>>>({});

  /** The compacted document -- blank blocks dropped -- on every change. */
  readonly documentChange = output<BlockDocument>();

  protected readonly blocks = signal<readonly EditorBlock[]>(fromDocument(null));
  protected readonly uploading = signal<string | null>(null);
  protected readonly imageError = signal<string | null>(null);
  private readonly uploadedPreviews = signal<Readonly<Record<string, string>>>({});

  protected readonly types = computed<readonly BlockType[]>(() =>
    BLOCK_TYPES.filter((type) => type !== 'IMAGE' || this.imageUpload() !== null),
  );
  protected readonly canAddBlock = computed(() => this.blocks().length < BLOCK_LIMITS.maxBlocks);
  protected readonly maxListItems = BLOCK_LIMITS.maxListItems;
  protected readonly imageRatios = ['3:2', '1:1', '16:9'] as const;

  /** What the author would be refused for, with the block's number (from 1) already worked out. */
  protected readonly problems = computed<
    readonly { code: BlockProblemCode; block: number | null }[]
  >(() =>
    blockDocumentProblems(this.compacted()).map((problem: BlockProblem) => ({
      code: problem.code,
      block: problem.blockIndex === null ? null : problem.blockIndex + 1,
    })),
  );

  /**
   * The problems are those of the document the host would be sent, not of the editor's rows: a blank
   * paragraph the author has not typed in yet is not part of it. A block's number is its place in
   * that document, which is what the server's own message would say.
   */
  private readonly compacted = computed(() => compactBlocks(this.blocks().map((b) => b.block)));

  /** The JSON this component last emitted, so its own echo does not rebuild the blocks. */
  private lastEmitted: string | null = null;

  constructor() {
    effect(() => {
      const document = this.document();
      if (JSON.stringify(compactBlocks(readBlockDocument(document).blocks)) === this.lastEmitted) {
        return;
      }
      this.blocks.set(fromDocument(document));
    });
  }

  protected typeLabel(type: BlockType): string {
    return this.labels().types[type];
  }

  protected problemText(problem: { code: BlockProblemCode; block: number | null }): string {
    return this.labels().problems[problem.code].replace('{block}', String(problem.block ?? ''));
  }

  protected previewOf(assetId: string): string | null {
    return this.uploadedPreviews()[assetId] ?? this.imagePreviews()[assetId] ?? null;
  }

  protected runsOf(block: Block): readonly Inline[] {
    return block.type === 'PARAGRAPH' || block.type === 'HEADING' || block.type === 'QUOTE'
      ? block.inlines
      : [];
  }

  protected captionOf(block: Block): readonly Inline[] {
    return block.type === 'IMAGE' && block.caption !== null ? block.caption : [{ text: '' }];
  }

  // ------------------------------------------------------------------------------- changes

  protected setType(id: string, type: BlockType): void {
    this.update(id, (block) => convertBlock(block, type));
  }

  protected setRuns(id: string, runs: readonly Inline[]): void {
    this.update(id, (block) => {
      if (block.type === 'PARAGRAPH' || block.type === 'QUOTE') {
        return { ...block, inlines: runs };
      }
      if (block.type === 'HEADING') {
        return { ...block, inlines: runs };
      }
      return block;
    });
  }

  protected setLevel(id: string, level: string): void {
    this.update(id, (block) =>
      block.type === 'HEADING' ? { ...block, level: level === '3' ? 3 : 2 } : block,
    );
  }

  protected setOrdered(id: string, ordered: boolean): void {
    this.update(id, (block) => (block.type === 'LIST' ? { ...block, ordered } : block));
  }

  protected setItemRuns(id: string, index: number, runs: readonly Inline[]): void {
    this.update(id, (block) =>
      block.type === 'LIST'
        ? { ...block, items: block.items.map((item, i) => (i === index ? runs : item)) }
        : block,
    );
  }

  protected addItem(id: string): void {
    this.update(id, (block) =>
      block.type === 'LIST' && block.items.length < BLOCK_LIMITS.maxListItems
        ? { ...block, items: [...block.items, [{ text: '' }]] }
        : block,
    );
  }

  protected removeItem(id: string, index: number): void {
    this.update(id, (block) => {
      if (block.type !== 'LIST') {
        return block;
      }
      const items = block.items.filter((_, i) => i !== index);
      return { ...block, items: items.length > 0 ? items : [[{ text: '' }]] };
    });
  }

  protected setCaption(id: string, runs: readonly Inline[]): void {
    this.update(id, (block) => (block.type === 'IMAGE' ? { ...block, caption: runs } : block));
  }

  protected addBlock(): void {
    if (!this.canAddBlock()) {
      return;
    }
    this.blocks.update((current) => [...current, { id: nextId(), block: newBlock('PARAGRAPH') }]);
    this.emit();
  }

  protected removeBlock(id: string): void {
    this.blocks.update((current) => {
      const next = current.filter((b) => b.id !== id);
      return next.length > 0 ? next : [{ id: nextId(), block: newBlock('PARAGRAPH') }];
    });
    this.emit();
  }

  protected move(id: string, by: -1 | 1): void {
    this.blocks.update((current) => {
      const index = current.findIndex((b) => b.id === id);
      const target = index + by;
      if (index < 0 || target < 0 || target >= current.length) {
        return current;
      }
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    this.emit();
  }

  protected async uploadImage(id: string, file: File): Promise<void> {
    const upload = this.imageUpload();
    if (!upload || this.uploading() !== null) {
      return;
    }
    this.uploading.set(id);
    this.imageError.set(null);
    try {
      const uploaded = await upload(file);
      if (uploaded.previewUrl) {
        this.uploadedPreviews.update((current) => ({
          ...current,
          [uploaded.assetId]: uploaded.previewUrl as string,
        }));
      }
      this.update(id, (block) =>
        block.type === 'IMAGE' ? { ...block, assetId: uploaded.assetId } : block,
      );
    } catch {
      this.imageError.set(this.labels().imageFailed);
    } finally {
      this.uploading.set(null);
    }
  }

  protected clearImage(id: string): void {
    this.update(id, (block) => (block.type === 'IMAGE' ? { ...block, assetId: '' } : block));
  }

  private update(id: string, change: (block: Block) => Block): void {
    this.blocks.update((current) =>
      current.map((entry) => (entry.id === id ? { ...entry, block: change(entry.block) } : entry)),
    );
    this.emit();
  }

  private emit(): void {
    const compacted = this.compacted();
    this.lastEmitted = JSON.stringify(compacted);
    this.documentChange.emit(compacted);
  }
}
