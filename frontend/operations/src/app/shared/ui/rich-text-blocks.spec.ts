import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, expect, it } from 'vitest';

import { BlockDocument } from './block-document';
import { MediaUploader } from './media-uploader';
import { RichTextBlockLabels, RichTextBlocks, RichTextImageUpload } from './rich-text-blocks';

const LABELS: RichTextBlockLabels = {
  text: 'Text',
  bold: 'Bold',
  italic: 'Italic',
  link: 'Link',
  linkAddress: 'Link address',
  linkRefused: 'Refused link',
  addRun: 'Add text',
  removeRun: 'Remove text',
  blockType: 'Block type',
  types: {
    PARAGRAPH: 'Paragraph',
    HEADING: 'Heading',
    QUOTE: 'Quote',
    LIST: 'List',
    IMAGE: 'Image',
    DIVIDER: 'Divider',
  },
  headingLevel: 'Level',
  level2: 'Heading',
  level3: 'Subheading',
  ordered: 'Numbered',
  moveUp: 'Move up',
  moveDown: 'Move down',
  removeBlock: 'Remove block',
  addBlock: 'Add block',
  addItem: 'Add item',
  removeItem: 'Remove item',
  caption: 'Caption',
  imageNone: 'No image yet',
  imageUploaded: 'Image added',
  imageFailed: 'The image could not be uploaded',
  dividerLabel: 'Divider',
  problems: {
    TOO_MANY_BLOCKS: 'Too many blocks',
    TOO_MANY_RUNS: 'Block {block}: too many pieces of text',
    TOO_MANY_ITEMS: 'Block {block}: too many items',
    TEXT_TOO_LONG: 'Too much text',
    RUN_TOO_LONG: 'Block {block}: a piece of text is too long',
    FORBIDDEN_CHARACTER: 'Block {block}: contains a character that is not allowed',
    HREF_NOT_ALLOWED: 'Block {block}: a link is not allowed',
    EMPTY_LIST: 'Block {block}: an empty list',
    IMAGE_WITHOUT_ASSET: 'Block {block}: an image with no picture',
  },
};

@Component({
  selector: 'q-blocks-host',
  imports: [RichTextBlocks],
  template: `<q-rich-text-blocks
    [document]="doc()"
    [labels]="labels"
    [imageUpload]="upload()"
    [imagePreviews]="previews"
    (documentChange)="onChange($event)"
  />`,
})
class Host {
  readonly doc = signal<BlockDocument | null>(null);
  readonly upload = signal<RichTextImageUpload | null>(null);
  readonly labels = LABELS;
  readonly previews = { 'asset-1': 'blob:one' };
  readonly emitted: BlockDocument[] = [];
  /** `true` wires the ordinary controlled shape: every emission comes straight back in. */
  echo = true;
  onChange(doc: BlockDocument): void {
    this.emitted.push(doc);
    if (this.echo) {
      this.doc.set(doc);
    }
  }
}

function render(doc: BlockDocument | null = null): {
  fixture: ReturnType<typeof TestBed.createComponent<Host>>;
  root: HTMLElement;
} {
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.doc.set(doc);
  fixture.detectChanges();
  return { fixture, root: fixture.nativeElement as HTMLElement };
}

function blocks(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>('[data-testid="q-rich-text-doc-block"]'));
}

function type(textarea: HTMLTextAreaElement, text: string): void {
  textarea.value = text;
  textarea.dispatchEvent(new Event('input'));
}

function firstText(root: HTMLElement, block = 0): HTMLTextAreaElement {
  return blocks(root)[block].querySelector<HTMLTextAreaElement>(
    '[data-testid="q-rich-text-run-text"]',
  )!;
}

function select(el: HTMLSelectElement, value: string): void {
  el.value = value;
  el.dispatchEvent(new Event('change'));
}

describe('RichTextBlocks (ADR 0157, Decision 6: the typed block document)', () => {
  it('starts an empty document with one blank paragraph and says nothing until the author types', () => {
    const { fixture, root } = render();

    expect(blocks(root)).toHaveLength(1);
    expect(fixture.componentInstance.emitted).toEqual([]);
  });

  it('emits a document, never an HTML string, when the author types', () => {
    const { fixture, root } = render();

    type(firstText(root), 'Hello');

    expect(fixture.componentInstance.emitted.at(-1)).toEqual({
      schemaVersion: 1,
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Hello' }] }],
    });
  });

  it('keeps markup as the literal text the author typed and renders no element from it', () => {
    const { fixture, root } = render();

    type(firstText(root), '<img src=x onerror=alert(1)><script>alert(2)</script>');
    fixture.detectChanges();

    expect(root.querySelector('img')).toBeNull();
    expect(root.querySelector('script')).toBeNull();
    expect(fixture.componentInstance.emitted.at(-1)?.blocks[0]).toEqual({
      type: 'PARAGRAPH',
      inlines: [{ text: '<img src=x onerror=alert(1)><script>alert(2)</script>' }],
    });
  });

  it('turns a paragraph into a heading without retyping it, and sets the heading level', () => {
    const { fixture, root } = render({
      schemaVersion: 1,
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Dishes' }] }],
    });

    select(
      root.querySelector<HTMLSelectElement>('[data-testid="q-rich-text-doc-type"]')!,
      'HEADING',
    );
    fixture.detectChanges();
    select(root.querySelector<HTMLSelectElement>('[data-testid="q-rich-text-doc-level"]')!, '3');

    expect(fixture.componentInstance.emitted.at(-1)?.blocks).toEqual([
      { type: 'HEADING', level: 3, inlines: [{ text: 'Dishes' }] },
    ]);
  });

  it('edits a list item by item, and numbers it on request', () => {
    const { fixture, root } = render({
      schemaVersion: 1,
      blocks: [{ type: 'LIST', ordered: false, items: [[{ text: 'one' }]] }],
    });

    (root.querySelector('[data-testid="q-rich-text-doc-item-add"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const items = root.querySelectorAll<HTMLElement>('[data-testid="q-rich-text-doc-item"]');
    expect(items).toHaveLength(2);
    type(items[1].querySelector('textarea')!, 'two');
    fixture.detectChanges();

    const ordered = root.querySelector<HTMLInputElement>(
      '[data-testid="q-rich-text-doc-ordered"]',
    )!;
    ordered.checked = true;
    ordered.dispatchEvent(new Event('change'));

    expect(fixture.componentInstance.emitted.at(-1)?.blocks).toEqual([
      { type: 'LIST', ordered: true, items: [[{ text: 'one' }], [{ text: 'two' }]] },
    ]);
  });

  it('moves a block up and down and removes one, keeping the order of the rest', () => {
    const { fixture, root } = render({
      schemaVersion: 1,
      blocks: [
        { type: 'PARAGRAPH', inlines: [{ text: 'a' }] },
        { type: 'PARAGRAPH', inlines: [{ text: 'b' }] },
        { type: 'PARAGRAPH', inlines: [{ text: 'c' }] },
      ],
    });
    const text = (): string[] =>
      fixture.componentInstance.emitted
        .at(-1)!
        .blocks.map((b) => (b.type === 'PARAGRAPH' ? b.inlines[0].text : '?'));

    (
      blocks(root)[2].querySelector('[data-testid="q-rich-text-doc-up"]') as HTMLButtonElement
    ).click();
    expect(text()).toEqual(['a', 'c', 'b']);
    fixture.detectChanges();

    (
      blocks(root)[0].querySelector('[data-testid="q-rich-text-doc-down"]') as HTMLButtonElement
    ).click();
    expect(text()).toEqual(['c', 'a', 'b']);
    fixture.detectChanges();

    (
      blocks(root)[1].querySelector('[data-testid="q-rich-text-doc-remove"]') as HTMLButtonElement
    ).click();
    expect(text()).toEqual(['c', 'b']);
  });

  it('cannot move the first block up, the last down, or leave the document with no block', () => {
    const { fixture, root } = render({
      schemaVersion: 1,
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'only' }] }],
    });

    const only = blocks(root)[0];
    expect(
      (only.querySelector('[data-testid="q-rich-text-doc-up"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(
      (only.querySelector('[data-testid="q-rich-text-doc-down"]') as HTMLButtonElement).disabled,
    ).toBe(true);

    (only.querySelector('[data-testid="q-rich-text-doc-remove"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(blocks(root)).toHaveLength(1);
    expect(fixture.componentInstance.emitted.at(-1)?.blocks).toEqual([]);
  });

  it('drops a block type it does not know on the way in instead of failing', () => {
    const { root } = render({
      schemaVersion: 1,
      blocks: [
        { type: 'TABLE' },
        { type: 'PARAGRAPH', inlines: [{ text: 'kept' }] },
      ] as unknown as BlockDocument['blocks'],
    });

    expect(blocks(root)).toHaveLength(1);
    expect(firstText(root).value).toBe('kept');
  });

  it('does not rebuild the textareas when its own emission comes straight back in', () => {
    const { fixture, root } = render();
    const before = firstText(root);

    type(before, 'typing');
    fixture.detectChanges();

    expect(fixture.componentInstance.doc()).not.toBeNull();
    expect(firstText(root)).toBe(before);
  });

  it('replaces the blocks when the host hands it a different document', () => {
    const { fixture, root } = render({
      schemaVersion: 1,
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'first' }] }],
    });

    fixture.componentInstance.doc.set({
      schemaVersion: 1,
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'another language' }] }],
    });
    fixture.detectChanges();

    expect(firstText(root).value).toBe('another language');
  });

  it('shows what the server would refuse the document for, with the block number', () => {
    const { fixture, root } = render({
      schemaVersion: 1,
      blocks: [
        { type: 'PARAGRAPH', inlines: [{ text: 'fine' }] },
        { type: 'PARAGRAPH', inlines: [{ text: 'bad', href: 'javascript:alert(1)' }] },
      ],
    });
    fixture.detectChanges();

    const problems = root.querySelector('[data-testid="q-rich-text-doc-problems"]');
    expect(problems?.textContent).toContain('Block 2: a link is not allowed');
  });

  it('shows no problem list for a document that is fine', () => {
    const { root } = render({
      schemaVersion: 1,
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'fine' }] }],
    });

    expect(root.querySelector('[data-testid="q-rich-text-doc-problems"]')).toBeNull();
  });

  describe('images', () => {
    it('offers the image block only when the host can upload one', () => {
      const { fixture, root } = render();
      const options = (): string[] =>
        Array.from(
          root.querySelectorAll<HTMLOptionElement>('[data-testid="q-rich-text-doc-type"] option'),
        ).map((o) => o.value);

      expect(options()).not.toContain('IMAGE');

      fixture.componentInstance.upload.set(() =>
        Promise.resolve({ assetId: 'x', previewUrl: null }),
      );
      fixture.detectChanges();
      expect(options()).toContain('IMAGE');
    });

    it('uploads through the host and names the asset in the block', async () => {
      const { fixture, root } = render({
        schemaVersion: 1,
        blocks: [{ type: 'IMAGE', assetId: '', caption: null }],
      });
      const files: File[] = [];
      fixture.componentInstance.upload.set((file) => {
        files.push(file);
        return Promise.resolve({ assetId: 'asset-9', previewUrl: 'blob:nine' });
      });
      fixture.detectChanges();

      const uploader = fixture.debugElement.query(By.directive(MediaUploader));
      const file = new File(['x'], 'p.jpg', { type: 'image/jpeg' });
      (uploader.componentInstance as MediaUploader).cropped.emit(file);
      await new Promise((resolve) => setTimeout(resolve, 0));
      fixture.detectChanges();

      expect(files).toEqual([file]);
      expect(fixture.componentInstance.emitted.at(-1)?.blocks).toEqual([
        { type: 'IMAGE', assetId: 'asset-9', caption: null },
      ]);
      expect(root.querySelector('img')?.getAttribute('src')).toBe('blob:nine');
    });

    it('says so, and names no asset, when the upload fails', async () => {
      const { fixture, root } = render({
        schemaVersion: 1,
        blocks: [{ type: 'IMAGE', assetId: '', caption: null }],
      });
      fixture.componentInstance.upload.set(() => Promise.reject(new Error('503')));
      fixture.detectChanges();

      const uploader = fixture.debugElement.query(By.directive(MediaUploader));
      (uploader.componentInstance as MediaUploader).cropped.emit(
        new File(['x'], 'p.jpg', { type: 'image/jpeg' }),
      );
      await new Promise((resolve) => setTimeout(resolve, 0));
      fixture.detectChanges();

      expect(root.querySelector('[role="alert"]')?.textContent).toContain(
        'The image could not be uploaded',
      );
      expect(fixture.componentInstance.emitted.at(-1)?.blocks ?? []).toEqual([]);
    });

    it('shows a preview for an asset the document names but this session did not upload', () => {
      const { root } = render({
        schemaVersion: 1,
        blocks: [{ type: 'IMAGE', assetId: 'asset-1', caption: null }],
      });

      expect(root.querySelector('img')?.getAttribute('src')).toBe('blob:one');
    });
  });
});
