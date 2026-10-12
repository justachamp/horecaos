import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrowserPrinter, receiptPrintHtml } from './browser-print';

describe('receiptPrintHtml', () => {
  it('prints the picture at the roll’s physical width with no margin and no smoothing', () => {
    const page80 = receiptPrintHtml('blob:x', 80);
    expect(page80).toContain('@page{size:80mm auto;margin:0}');
    expect(page80).toContain('width:80mm');
    expect(page80).toContain('image-rendering:pixelated');
    expect(page80).toContain('<img src="blob:x"');

    expect(receiptPrintHtml('blob:x', 58)).toContain('size:58mm auto');
  });

  it('only ever prints a 58 or an 80 mm roll, whatever it is handed', () => {
    expect(receiptPrintHtml('blob:x', 100)).toContain('size:80mm auto');
    expect(receiptPrintHtml('blob:x', 0)).toContain('size:80mm auto');
  });
});

describe('BrowserPrinter', () => {
  it('draws the picture into a hidden frame, prints it once it has loaded, and removes the frame', async () => {
    const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    const revokeObjectURL = vi.fn();
    URL.createObjectURL = vi.fn().mockReturnValue('blob:roll');
    URL.revokeObjectURL = revokeObjectURL;
    const printer = TestBed.inject(BrowserPrinter);

    const done = printer.print(new Blob(['png']), 58);
    const frame = document.querySelector('iframe') as HTMLIFrameElement;
    expect(frame).not.toBeNull();
    expect(frame.getAttribute('aria-hidden')).toBe('true');
    expect(frame.srcdoc).toContain('size:58mm auto');
    expect(frame.srcdoc).toContain('blob:roll');

    const print = vi.fn();
    const view = frame.contentWindow as Window;
    view.print = print;
    view.focus = vi.fn();
    frame.dispatchEvent(new Event('load'));
    expect(print).toHaveBeenCalledOnce();

    view.dispatchEvent(new Event('afterprint'));
    await done;
    expect(document.querySelector('iframe')).toBeNull();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:roll');
    URL.createObjectURL = original.create;
    URL.revokeObjectURL = original.revoke;
  });
});
