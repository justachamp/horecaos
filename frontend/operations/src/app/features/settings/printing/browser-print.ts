import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

/**
 * The page a browser prints for a receipt's picture: the roll's physical width and nothing else.
 *
 * ADR 0154 decision 10. The bitmap is 1-bit, drawn on the platform at the printer's own dot width (384 dots on a
 * 58 mm roll, 576 on an 80 mm one), so the only thing the browser decides is how wide to print it. The page
 * has no margin and the image is not smoothed, because a smoothed 1-bit picture is grey and a thermal head
 * prints grey as a smear.
 *
 * Pure so a spec can read it; the width is a number the caller already holds, never text from a person.
 */
export function receiptPrintHtml(imageUrl: string, tapeWidthMm: number): string {
  const width = tapeWidthMm === 58 ? 58 : 80;
  return (
    '<!doctype html><html><head><meta charset="utf-8">' +
    `<style>@page{size:${width}mm auto;margin:0}` +
    'html,body{margin:0;padding:0;background:#fff}' +
    `img{display:block;width:${width}mm;max-width:100%;image-rendering:pixelated}</style></head>` +
    `<body><img src="${imageUrl}" alt=""></body></html>`
  );
}

/**
 * Prints a stored picture through the browser's own print dialog, at the roll's physical width.
 *
 * It is the operator's workstation path (rollout step 1): the platform can only know the job was handed to the
 * browser, which is exactly what `HANDED_OFF` says. The picture is drawn into a hidden frame, printed once it has
 * loaded, and the frame and the object URL are removed afterwards.
 */
@Injectable({ providedIn: 'root' })
export class BrowserPrinter {
  private readonly document = inject(DOCUMENT);

  /** Resolves when the print dialog has been asked for (or the picture could not be drawn). */
  print(picture: Blob, tapeWidthMm: number): Promise<void> {
    const url = URL.createObjectURL(picture);
    const frame = this.document.createElement('iframe');
    frame.setAttribute('aria-hidden', 'true');
    frame.tabIndex = -1;
    frame.style.cssText =
      'position:fixed;inset-inline-end:0;bottom:0;width:0;height:0;border:0;visibility:hidden';
    return new Promise<void>((resolve) => {
      const finish = (): void => {
        frame.remove();
        URL.revokeObjectURL(url);
        resolve();
      };
      frame.addEventListener('load', () => {
        const view = frame.contentWindow;
        if (!view) {
          finish();
          return;
        }
        view.addEventListener('afterprint', finish);
        view.focus();
        view.print();
        // A browser that never fires `afterprint` (a cancelled dialog on some engines) must not leak the frame.
        setTimeout(finish, 60_000);
      });
      frame.srcdoc = receiptPrintHtml(url, tapeWidthMm);
      this.document.body.appendChild(frame);
    });
  }
}
