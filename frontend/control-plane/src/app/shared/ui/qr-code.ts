import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { QrCapacityExceededError, QrMatrix, encodeQrMatrix } from './qr-encode';

/**
 * A QR bitmap, rendered client-side from a plain string — a copy of
 * `frontend/operations/src/app/shared/ui/qr-code.ts`, made for ADR 0148's enrolment screen
 * (see `otp-input.ts` for why it is copied rather than shared yet).
 *
 * **Display only.** No camera, no scanning input. Black on white whatever the theme: that
 * contrast is what a scanner's own binarisation step relies on. The one difference from the
 * original: the "too long to render" sentence is an input, because this console's catalogue is
 * its own and a shared component should not reach into either.
 */
@Component({
  selector: 'q-qr-code',
  template: `
    @switch (outcome().kind) {
      @case ('matrix') {
        <svg
          class="q-qr-code"
          [style.width.px]="size()"
          [style.height.px]="size()"
          [attr.viewBox]="'0 0 ' + matrixSize() + ' ' + matrixSize()"
          [attr.role]="label() ? 'img' : null"
          [attr.aria-label]="label()"
          [attr.aria-hidden]="label() ? null : 'true'"
          data-testid="qr-code-svg"
        >
          <rect width="100%" height="100%" fill="#ffffff" />
          @for (cell of darkCells(); track trackCell($index, cell)) {
            <rect [attr.x]="cell.col" [attr.y]="cell.row" width="1" height="1" fill="#000000" />
          }
        </svg>
      }
      @case ('tooLong') {
        <p class="q-caption qr-code__fallback" data-testid="qr-code-too-long">
          {{ tooLongText() }}
        </p>
      }
      @default {
        <!-- Empty value: render nothing, not an error state. -->
      }
    }
  `,
  styles: `
    .q-qr-code {
      display: block;
      shape-rendering: crispEdges;
    }

    .qr-code__fallback {
      color: var(--q-ink-subtle);
      max-width: 220px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QQrCode {
  /** The payload to render. Empty renders nothing rather than an error state. */
  readonly value = input.required<string>();
  /** Rendered box size in CSS pixels; the SVG itself scales (viewBox), so this is a hint, not a hard cap. */
  readonly size = input(160);
  /** An accessible label for screen readers — what this code is *for*, not its raw payload. */
  readonly label = input<string | null>(null);
  /** Already translated: what to say when the payload does not fit. */
  readonly tooLongText = input('');

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
