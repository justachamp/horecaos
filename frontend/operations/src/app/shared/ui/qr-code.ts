import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { QrCapacityExceededError, QrMatrix, encodeQrMatrix } from './qr-encode';

/**
 * Row `X.35` — a QR bitmap, rendered client-side from a plain string.
 *
 * **Display only.** No camera, no scanning input — `DeviceEnrolmentController`'s
 * pairing code is typed by a manager, never scanned (ADR 0079's own decision;
 * this component does not reopen it). Two call sites this wave: the device
 * shell's own enrolment screen (`device/device-shell.ts`) and the payments
 * page's `qrPayload` (`features/finance/payments/payments-page.ts`,
 * `payments-api.ts:93`) — a provider's own checkout/QR payload the console
 * used to drop entirely. `P38`'s table QR cards are a third, later.
 *
 * See `qr-encode.ts`'s own doc for what this encoder does and does not cover
 * (versions 1–5, error-correction level L, byte mode, 106 bytes maximum) and
 * for the one residual risk worth repeating here: it has not been scanned
 * with a real device in this environment.
 */
@Component({
  selector: 'q-qr-code',
  imports: [TPipe],
  templateUrl: './qr-code.html',
  styleUrl: './qr-code.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QQrCode {
  /** The payload to render. Empty renders nothing rather than an error state. */
  readonly value = input.required<string>();
  /** Rendered box size in CSS pixels; the SVG itself scales (viewBox), so this is a hint, not a hard cap. */
  readonly size = input(160);
  /** An accessible label for screen readers — what this code is *for*, not its raw payload (which may be opaque or, for a checkout URL, a link screen readers should not attempt to visit here). */
  readonly label = input<string | null>(null);

  protected readonly outcome = computed<
    | { readonly kind: 'empty' }
    | { readonly kind: 'matrix'; readonly matrix: QrMatrix }
    | { readonly kind: 'tooLong' }
  >(() => {
    const value = this.value();
    if (value.trim().length === 0) {
      return { kind: 'empty' };
    }
    try {
      return { kind: 'matrix', matrix: encodeQrMatrix(value) };
    } catch (error) {
      if (error instanceof QrCapacityExceededError) {
        return { kind: 'tooLong' };
      }
      throw error;
    }
  });

  /** `matrix.modules`, flattened to `(row, col, dark)` triples — simpler for the template's one `@for` than nested loops over a 2-D signal. */
  protected readonly darkCells = computed<
    readonly { readonly row: number; readonly col: number }[]
  >(() => {
    const outcome = this.outcome();
    if (outcome.kind !== 'matrix') {
      return [];
    }
    const cells: { row: number; col: number }[] = [];
    const { modules } = outcome.matrix;
    for (let row = 0; row < modules.length; row++) {
      for (let col = 0; col < modules[row].length; col++) {
        if (modules[row][col]) {
          cells.push({ row, col });
        }
      }
    }
    return cells;
  });

  protected readonly matrixSize = computed<number>(() => {
    const outcome = this.outcome();
    return outcome.kind === 'matrix' ? outcome.matrix.size : 0;
  });

  protected trackCell(
    _index: number,
    cell: { readonly row: number; readonly col: number },
  ): string {
    return `${cell.row}:${cell.col}`;
  }
}
